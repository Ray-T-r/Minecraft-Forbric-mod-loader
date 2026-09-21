#!/usr/bin/env python3
"""Compare two saved Anvil dimensions chunk by chunk and report what differs, by facet.

Usage: world-parity.py <region-dir-a> <region-dir-b>

Prints one machine-readable line per fact, for a gate to assert on:

    chunks: a=1764 b=1764 common=1764 only_a=0 only_b=0
    full: a=400 b=400
    differ biomes: 0
    differ structures: 0
    differ heightmaps: 186
    differ blocks: 242
    differ block_entities: 17

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

FACETS = ("biomes", "structures", "heightmaps", "blocks", "block_entities")


def _read(buf, off, kind):
    if kind == 1:
        return buf[off], off + 1
    if kind == 2:
        return struct.unpack_from(">h", buf, off)[0], off + 2
    if kind == 3:
        return struct.unpack_from(">i", buf, off)[0], off + 4
    if kind == 4:
        return struct.unpack_from(">q", buf, off)[0], off + 8
    if kind == 5:
        return struct.unpack_from(">f", buf, off)[0], off + 4
    if kind == 6:
        return struct.unpack_from(">d", buf, off)[0], off + 8
    if kind == 7:
        n = struct.unpack_from(">i", buf, off)[0]
        return bytes(buf[off + 4:off + 4 + n]), off + 4 + n
    if kind == 8:
        n = struct.unpack_from(">H", buf, off)[0]
        return buf[off + 2:off + 2 + n].decode("utf-8", "replace"), off + 2 + n
    if kind == 9:
        element = buf[off]
        n = struct.unpack_from(">i", buf, off + 1)[0]
        off += 5
        out = []
        for _ in range(n):
            value, off = _read(buf, off, element)
            out.append(value)
        return out, off
    if kind == 10:
        out = {}
        while True:
            element = buf[off]
            off += 1
            if element == 0:
                return out, off
            length = struct.unpack_from(">H", buf, off)[0]
            off += 2
            name = buf[off:off + length].decode("utf-8", "replace")
            off += length
            out[name], off = _read(buf, off, element)
    if kind == 11:
        n = struct.unpack_from(">i", buf, off)[0]
        return list(struct.unpack_from(">%di" % n, buf, off + 4)), off + 4 + 4 * n
    if kind == 12:
        n = struct.unpack_from(">i", buf, off)[0]
        return list(struct.unpack_from(">%dq" % n, buf, off + 4)), off + 4 + 8 * n
    raise ValueError("unknown NBT tag %d at %d" % (kind, off))


def chunks(directory):
    """Yield (cx, cz, root compound) for every readable chunk in an Anvil region directory."""
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
            compression = data[start + 4]
            blob = data[start + 5:start + 4 + length]
            try:
                if compression == 1:
                    raw = gzip.decompress(blob)
                elif compression == 2:
                    raw = zlib.decompress(blob)
                elif compression == 3:
                    raw = blob
                else:
                    continue  # external .mcc payload or LZ4; not resident here
            except (OSError, EOFError, zlib.error):
                continue
            if len(raw) < 4 or raw[0] != 10:
                continue
            name_length = struct.unpack_from(">H", raw, 1)[0]
            root, _ = _read(raw, 3 + name_length, 10)
            yield rx * 32 + (slot % 32), rz * 32 + (slot // 32), root


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
    return {
        "blocks": digest(blocks),
        "biomes": digest(biomes),
        "heightmaps": digest(root.get("Heightmaps", {})),
        "block_entities": digest(root.get("block_entities", [])),
        "structures": digest(root.get("structures", {})),
        "_full": root.get("Status") == "minecraft:full",
    }


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
        differing = [c for c in common if a[c][facet] != b[c][facet]]
        named = " ".join("%d,%d" % c for c in differing[:args.list])
        print("differ %s: %d%s" % (facet, len(differing), ("  " + named) if named else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
