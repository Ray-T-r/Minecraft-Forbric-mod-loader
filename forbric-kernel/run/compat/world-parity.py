#!/usr/bin/env python3
"""Compare two saved Anvil dimensions chunk by chunk and report what differs, by facet.

Usage: world-parity.py <region-dir-a> <region-dir-b>

Prints one machine-readable line per fact, for a gate to assert on:

    chunks: a=1764 b=1764 common=1764 only_a=0 only_b=0
    full: a=400 b=400
    differ biomes: 0
    differ structures: 0
    differ spawner_mobs: 0
    differ spawner_positions: 2
    differ heightmaps: 186
    differ blocks: 242
    differ block_entities: 17

`spawner_mobs` compares the mob of every spawner that stands at the same position in both worlds. It is
an assertion for the same reason biomes are: the feature decides it once, from its own random stream.
WHERE a spawner stands is not always that kind of fact: a mineshaft corridor that crosses chunks places its
cave spider spawner from whichever chunk first draws a spot inside itself (vanilla's mutable
`MineShaftCorridor.hasPlacedSpider`), so which of two neighbouring chunks gets it follows the worker pool.
Seen on seed `forbrickernel` at x=-168: vanilla put it at z=-52 three times, a Forbric run at z=-46.
`spawner_positions` counts chunks whose spawner positions differ and is evidence only.

Which facets are EVIDENCE and which are ASSERTIONS is not a detail — vanilla does not reproduce
itself at the block level. Features that read a neighbouring chunk (dripstone, sculk) resolve by
whichever chunk the worker pool finished first, so two runs of unmodified vanilla on the same seed
differ in over half of their fully generated chunks. Measured on seed `forbrickernel`: 274 of 400.

Biomes carry no such noise. They are written at the `biomes` chunk status from the climate sampler
alone, before any feature runs, and two vanilla runs — and two Forbric runs — agree on every one of
1764 chunks. Structure starts are the same kind of fact. So those two are the assertions, and
heightmaps, blocks and block entities are printed as evidence and nothing more.

`LastUpdate` and `InhabitedTime` are wall-clock-ish and are never compared; nothing here reads them.
"""
import argparse
import gzip
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

FACETS = ("biomes", "structures", "spawner_mobs", "spawner_positions", "heightmaps", "blocks", "block_entities")


# NBT tag ids, by the names the typed form uses (world-snapshot.py writes snapshots in that form).
TAGS = {1: "byte", 2: "short", 3: "int", 4: "long", 5: "float", 6: "double", 7: "byte_array", 8: "string",
        9: "list", 10: "compound", 11: "int_array", 12: "long_array"}
TAG_IDS = {name: kind for kind, name in TAGS.items()}
_SCALARS = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}


def read_typed(buf, off, kind):
    """One NBT payload of tag `kind` at `off`, typed: `[tag, value]` for every value, `["list", element-tag, [...]]`
    for a list, `["compound", {name: typed}]` for a compound; arrays keep their type. Returns (value, next offset).

    The only NBT parser in the tree: the untyped view gate-m31 compares (`_read`) is derived from it, and
    world-snapshot.py reads saves through it, because an upgrade check that cannot tell a byte from an int cannot see a
    field change type either."""
    if kind in _SCALARS:
        fmt = _SCALARS[kind]
        return [TAGS[kind], struct.unpack_from(fmt, buf, off)[0]], off + struct.calcsize(fmt)
    if kind == 7:
        n = struct.unpack_from(">i", buf, off)[0]
        return ["byte_array", list(struct.unpack_from(">%db" % n, buf, off + 4))], off + 4 + n
    if kind == 8:
        n = struct.unpack_from(">H", buf, off)[0]
        return ["string", buf[off + 2:off + 2 + n].decode("utf-8", "replace")], off + 2 + n
    if kind == 9:
        element = buf[off]
        n = struct.unpack_from(">i", buf, off + 1)[0]
        off += 5
        out = []
        for _ in range(n):
            value, off = read_typed(buf, off, element)
            out.append(untag(value))
        return ["list", TAGS.get(element, "end"), out], off
    if kind == 10:
        out = {}
        while True:
            element = buf[off]
            off += 1
            if element == 0:
                return ["compound", out], off
            length = struct.unpack_from(">H", buf, off)[0]
            off += 2
            name = buf[off:off + length].decode("utf-8", "replace")
            off += length
            out[name], off = read_typed(buf, off, element)
    if kind == 11:
        n = struct.unpack_from(">i", buf, off)[0]
        return ["int_array", list(struct.unpack_from(">%di" % n, buf, off + 4))], off + 4 + 4 * n
    if kind == 12:
        n = struct.unpack_from(">i", buf, off)[0]
        return ["long_array", list(struct.unpack_from(">%dq" % n, buf, off + 4))], off + 4 + 8 * n
    raise ValueError("unknown NBT tag %d at %d" % (kind, off))


def untag(typed):
    """A typed value as a list element: the value alone (the list carries the tag), or `[element-tag, items]` for a
    nested list."""
    return typed[1:] if typed[0] == "list" else typed[1]


def retag(element, item):
    """The inverse of `untag` for an element of a list whose element tag is `element`."""
    return ["list"] + list(item) if element == "list" else [element, item]


def plain(typed):
    """The untyped view of a typed value: what `_read` returned before the typed reader existed, value for value
    (a byte unsigned, a byte array as bytes), so the digests gate-m31 compares are unchanged."""
    tag, value = typed[0], typed[1]
    if tag == "byte":
        return value & 0xFF
    if tag == "byte_array":
        return bytes(v & 0xFF for v in value)
    if tag == "compound":
        return {name: plain(item) for name, item in value.items()}
    if tag == "list":
        return [plain(retag(typed[1], item)) for item in typed[2]]
    return value


def _read(buf, off, kind):
    value, off = read_typed(buf, off, kind)
    return plain(value), off


def chunk_payloads(directory):
    """Yield (cx, cz, compression, payload bytes) for every resident chunk in an Anvil region directory."""
    for path in sorted(Path(directory).glob("*.mca")):
        parts = path.stem.split(".")
        rx, rz = int(parts[1]), int(parts[2])
        data = path.read_bytes()
        if len(data) < 8192:
            continue
        for slot in range(1024):
            entry = struct.unpack_from(">I", data, slot * 4)[0]
            sector, count = entry >> 8, entry & 255
            if sector < 2 or count == 0:
                continue
            start = sector * 4096
            if start + 5 > len(data):
                continue
            length = struct.unpack_from(">I", data, start)[0]
            if length < 1 or start + 4 + length > len(data):
                continue
            yield rx * 32 + (slot % 32), rz * 32 + (slot // 32), data[start + 4], data[start + 5:start + 4 + length]


def decompress(compression, blob):
    """The raw NBT of one chunk payload, or None (external .mcc payload, LZ4, or unreadable)."""
    try:
        if compression == 1:
            return gzip.decompress(blob)
        if compression == 2:
            return zlib.decompress(blob)
        if compression == 3:
            return blob
    except (OSError, EOFError, zlib.error):
        return None
    return None  # external .mcc payload or LZ4; not resident here


def root_typed(raw):
    """The typed root compound of a raw (uncompressed) NBT file, or None when it is not one."""
    if raw is None or len(raw) < 4 or raw[0] != 10:
        return None
    name_length = struct.unpack_from(">H", raw, 1)[0]
    return read_typed(raw, 3 + name_length, 10)[0]


def chunks_typed(directory):
    """Yield (cx, cz, typed root compound) for every readable chunk in an Anvil region directory."""
    for cx, cz, compression, blob in chunk_payloads(directory):
        root = root_typed(decompress(compression, blob))
        if root is not None:
            yield cx, cz, root


def chunks(directory):
    """Yield (cx, cz, root compound) for every readable chunk in an Anvil region directory."""
    for cx, cz, root in chunks_typed(directory):
        yield cx, cz, plain(root)


def _normalise(value):
    if isinstance(value, dict):
        return {key: _normalise(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [_normalise(item) for item in value]
    if isinstance(value, bytes):
        return "b:" + hashlib.sha1(value).hexdigest()
    return value


def digest(value):
    return hashlib.sha1(json.dumps(_normalise(value), sort_keys=True, default=str).encode()).hexdigest()[:16]


def facets(root):
    blocks, biomes = {}, {}
    for section in root.get("sections", []):
        y = section.get("Y")
        if "block_states" in section:
            blocks[y] = digest(section["block_states"])
        if "biomes" in section:
            biomes[y] = digest(section["biomes"])
    spawners = {}
    for entity in root.get("block_entities", []) or []:
        if isinstance(entity, dict) and entity.get("id") == "minecraft:mob_spawner":
            spawn = entity.get("SpawnData") or {}
            mob = (spawn.get("entity") or {}).get("id") if isinstance(spawn, dict) else None
            spawners[(entity.get("x"), entity.get("y"), entity.get("z"))] = mob
    return {
        "_spawners": spawners,
        "blocks": digest(blocks),
        "biomes": digest(biomes),
        "heightmaps": digest(root.get("Heightmaps", {})),
        "block_entities": digest(root.get("block_entities", [])),
        "structures": digest(root.get("structures", {})),
        "_full": root.get("Status") == "minecraft:full",
    }


def differs(facet, a, b):
    """Whether one chunk's facet differs. Spawners are compared position by position (see the module notes)."""
    if facet == "spawner_mobs":
        return any(a["_spawners"][pos] != b["_spawners"][pos] for pos in set(a["_spawners"]) & set(b["_spawners"]))
    if facet == "spawner_positions":
        return set(a["_spawners"]) != set(b["_spawners"])
    return a[facet] != b[facet]


def load(directory):
    return {(cx, cz): facets(root) for cx, cz, root in chunks(directory)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("a", type=Path, help="region directory of the first world")
    parser.add_argument("b", type=Path, help="region directory of the second world")
    parser.add_argument("--list", type=int, default=6, help="how many differing chunks to name per facet")
    args = parser.parse_args()

    for side in (args.a, args.b):
        if not side.is_dir():
            print("[parity] FATAL no region directory at %s" % side, file=sys.stderr)
            return 3

    a, b = load(args.a), load(args.b)
    common = sorted(set(a) & set(b))
    print("chunks: a=%d b=%d common=%d only_a=%d only_b=%d"
          % (len(a), len(b), len(common), len(set(a) - set(b)), len(set(b) - set(a))))
    print("full: a=%d b=%d" % (sum(1 for c in a.values() if c["_full"]), sum(1 for c in b.values() if c["_full"])))
    for facet in FACETS:
        differing = [c for c in common if differs(facet, a[c], b[c])]
        named = " ".join("%d,%d" % c for c in differing[:args.list])
        print("differ %s: %d%s" % (facet, len(differing), ("  " + named) if named else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
