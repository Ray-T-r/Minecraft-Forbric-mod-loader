#!/usr/bin/env python3
"""Export a saved world to a typed JSON snapshot, compare two snapshots under named rules, mutate a save for reverse
controls, and check a snapshot's names against a registry census.

    world-snapshot.py export <world-dir> --out <snap.json> [--chunks X0,Z0,X1,Z1] [--label TEXT]
    world-snapshot.py diff <a.json> <b.json> --rules upgrade|reopen [--ignore-namespace NS]... [--list N]
    world-snapshot.py mutate <world-dir> [--chest-count "DIM X Y Z" SLOT COUNT]... [--palette OLD=NEW]...
    world-snapshot.py census <snap.json> <registries.json>... [--drop KIND=NAME]

WHY. gate-m58 asks whether a world a Forbric from before MinecraftForge's removal wrote (merged base, two carriers:
v0.3.1-beta2 and earlier, and the 0.4.0-dev builds of that line) comes back whole under the genuine NeoForge base, and
gate-m59 whether a world written with a MinecraftForge build of a mod survives the swap to its NeoForge build. Both
questions are about the save files, so both are answered offline, after a clean stop, from what is on disk: the thing
that has to survive is the file, not what a running game says about it.

WHERE IT SITS. Not part of the compatibility sweep (PROTOCOL.md): only gate-m58 and gate-m59 run it. It reads saves
through world-parity.py's typed reader (`read_typed`), the tree's one NBT parser, so gate-m31 and these gates cannot
disagree about what a file holds; test_world_snapshot.py checks that reader against bytes the game itself wrote
(testdata/world-snapshot/, a flat world the old kernel saved) as well as against our own writer.

Snapshots are typed (see world-parity.py `read_typed`): `["int", 5]` and `["byte", 5]` differ, so a field that changes
type on resave is a difference, not a silent equality. Schema `forbric-world-snapshot/1`, facets (each a JSON object
with sorted keys):
  level           level.dat root, typed (Data, fml.LoadingModList, ...)
  blocks          "DIM X Y Z" -> "ns:id[k=v,...]" for every non-air block in the chunk box
  biomes          "DIM CX SY CZ" -> [[biome, run-length], ...] per section in the box
  block_entities  "DIM X Y Z" -> typed compound (minus x, y, z)
  chunk_extra     "DIM CX CZ" -> typed compound: the chunk root minus sections, block_entities and per-tick keys
                  (this is where neoforge:attachments, fabric:attachments or a ForgeCaps land)
  entities        "UUID" -> {"dim": DIM, "nbt": typed compound}
  players         "STEM" -> typed root of players/data/STEM.dat
  advancements    "STEM" -> JSON;  stats  "STEM" -> JSON
  saved_data      "relative/path.dat" -> typed root; any other file under a data/ dir -> {"sha1": ...}
  meta            exporter version, world dir, chunk box, label

`diff` exits 0 with no differences, 1 with some, 3 on unusable input. Every rule that hid something is printed with
its count (`rule <name>: <n>`), so an allowance that fires where it should not is visible, and one that never fires
can be asserted on. Everything not covered by a rule must be equal; a key only one side has is a difference.
"""
import argparse
import copy
import gzip
import hashlib
import importlib.util
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path

_spec = importlib.util.spec_from_file_location("world_parity", Path(__file__).with_name("world-parity.py"))
wp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(wp)

SCHEMA = "forbric-world-snapshot/1"
EXPORTER = 1
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
# Chunk-root keys that are not chunk content: what the other facets already hold, and per-tick bookkeeping.
CHUNK_SKIP = {"sections", "block_entities", "Heightmaps", "block_ticks", "fluid_ticks", "LastUpdate", "InhabitedTime",
              "xPos", "zPos"}

# --- NBT out (the reader is world-parity.py's) ----------------------------------------------------------------------

_PACK = {"byte": ">b", "short": ">h", "int": ">i", "long": ">q", "float": ">f", "double": ">d"}


def write_typed(typed):
    """The payload bytes of one typed value (no tag id, no name)."""
    tag = typed[0]
    if tag in _PACK:
        return struct.pack(_PACK[tag], typed[1])
    if tag == "string":
        raw = typed[1].encode("utf-8")
        return struct.pack(">H", len(raw)) + raw
    if tag == "byte_array":
        return struct.pack(">i", len(typed[1])) + struct.pack(">%db" % len(typed[1]), *typed[1])
    if tag == "int_array":
        return struct.pack(">i", len(typed[1])) + struct.pack(">%di" % len(typed[1]), *typed[1])
    if tag == "long_array":
        return struct.pack(">i", len(typed[1])) + struct.pack(">%dq" % len(typed[1]), *typed[1])
    if tag == "list":
        element, items = typed[1], typed[2]
        kind = 0 if element == "end" else wp.TAG_IDS[element]
        return bytes([kind]) + struct.pack(">i", len(items)) + b"".join(write_typed(wp.retag(element, i)) for i in items)
    if tag == "compound":
        out = bytearray()
        for name, value in typed[1].items():
            raw = name.encode("utf-8")
            out += bytes([wp.TAG_IDS[value[0]]]) + struct.pack(">H", len(raw)) + raw + write_typed(value)
        return bytes(out) + b"\x00"
    raise ValueError("cannot write NBT tag %r" % (tag,))


def encode_root(typed, name=""):
    raw = name.encode("utf-8")
    return b"\x0a" + struct.pack(">H", len(raw)) + raw + write_typed(typed)


def read_nbt_file(path):
    """Typed root of a .dat file (gzip, zlib or raw), or None."""
    data = Path(path).read_bytes()
    for opener in (gzip.decompress, zlib.decompress, lambda b: b):
        try:
            root = wp.root_typed(opener(data))
        except (OSError, EOFError, zlib.error, struct.error, ValueError, IndexError):
            continue
        if root is not None:
            return root
    return None


# --- typed helpers -------------------------------------------------------------------------------------------------

def get(compound, key, default=None):
    """The untagged value of `key` in a typed compound (`["compound", {...}]` or its dict)."""
    d = compound[1] if isinstance(compound, list) else compound
    v = d.get(key)
    if v is None:
        return default
    return v[2] if v[0] == "list" else v[1]


def block_state_name(entry):
    """`ns:id[k=v,...]` (properties sorted) from a typed palette entry's dict."""
    name = get(entry, "Name", "?")
    props = get(entry, "Properties")
    if props:
        name += "[" + ",".join("%s=%s" % (k, props[k][1]) for k in sorted(props)) + "]"
    return name


def unpack_indices(longs, bits, count):
    """Indices of a 1.16+ packed array (no entry spans two longs)."""
    if bits == 0 or not longs:
        return [0] * count
    per = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for i in range(count):
        word = longs[i // per] & 0xFFFFFFFFFFFFFFFF
        out.append((word >> ((i % per) * bits)) & mask)
    return out


def section_blocks(section):
    states = get(section, "block_states")
    if not states:
        return None
    palette = get(states, "palette") or []
    names = [block_state_name(e) for e in palette]
    if len(names) <= 1:
        return [names[0] if names else "minecraft:air"] * 4096
    bits = max(4, math.ceil(math.log2(len(names))))
    return [names[i] if i < len(names) else "?index%d" % i for i in unpack_indices(get(states, "data") or [], bits, 4096)]


def section_biomes(section):
    biomes = get(section, "biomes")
    if not biomes:
        return None
    palette = get(biomes, "palette") or []
    if len(palette) <= 1:
        return [[palette[0] if palette else "?", 64]]
    bits = math.ceil(math.log2(len(palette)))
    runs = []
    for i in unpack_indices(get(biomes, "data") or [], bits, 64):
        name = palette[i] if i < len(palette) else "?index%d" % i
        if runs and runs[-1][0] == name:
            runs[-1][1] += 1
        else:
            runs.append([name, 1])
    return runs


def uuid_of(nbt):
    ints = get(nbt, "UUID")
    if isinstance(ints, list) and len(ints) == 4:
        h = "".join("%08x" % (i & 0xFFFFFFFF) for i in ints)
        return "%s-%s-%s-%s-%s" % (h[:8], h[8:12], h[12:16], h[16:20], h[20:])
    return None


# --- export --------------------------------------------------------------------------------------------------------

def dimensions(world):
    """{dimension id: its directory} for every dimension with region files."""
    out = {}
    root = world / "dimensions"
    if root.is_dir():
        for ns in sorted(p for p in root.iterdir() if p.is_dir()):
            for dim in sorted(p for p in ns.iterdir() if p.is_dir()):
                if (dim / "region").is_dir():
                    out["%s:%s" % (ns.name, dim.name)] = dim
    if (world / "region").is_dir() and "minecraft:overworld" not in out:
        out["minecraft:overworld"] = world
    return out


def in_box(cx, cz, box):
    return box is None or (box[0] <= cx <= box[2] and box[1] <= cz <= box[3])


def export(world, box=None, label=""):
    world = Path(world)
    snap = {"schema": SCHEMA, "meta": {"exporter": EXPORTER, "world": str(world), "chunks": box, "label": label},
            "level": None, "blocks": {}, "biomes": {}, "block_entities": {}, "chunk_extra": {}, "entities": {},
            "players": {}, "advancements": {}, "stats": {}, "saved_data": {}}
    if (world / "level.dat").is_file():
        snap["level"] = read_nbt_file(world / "level.dat")
    for dim, ddir in dimensions(world).items():
        for cx, cz, root in wp.chunks_typed(ddir / "region"):
            if not in_box(cx, cz, box):
                continue
            for section in get(root, "sections") or []:
                sy = get(section, "Y")
                blocks = section_blocks(section)
                if blocks is not None:
                    for i, name in enumerate(blocks):
                        if name.split("[", 1)[0] in AIR:
                            continue
                        x, z, y = cx * 16 + (i & 15), cz * 16 + ((i >> 4) & 15), sy * 16 + (i >> 8)
                        snap["blocks"]["%s %d %d %d" % (dim, x, y, z)] = name
                biomes = section_biomes(section)
                if biomes is not None:
                    snap["biomes"]["%s %d %d %d" % (dim, cx, sy, cz)] = biomes
            for be in get(root, "block_entities") or []:
                d = dict(be)
                pos = (d.pop("x", ["int", 0])[1], d.pop("y", ["int", 0])[1], d.pop("z", ["int", 0])[1])
                snap["block_entities"]["%s %d %d %d" % ((dim,) + pos)] = ["compound", d]
            extra = {k: v for k, v in root[1].items() if k not in CHUNK_SKIP}
            snap["chunk_extra"]["%s %d %d" % (dim, cx, cz)] = ["compound", extra]
        if (ddir / "entities").is_dir():
            for cx, cz, root in wp.chunks_typed(ddir / "entities"):
                if not in_box(cx, cz, box):
                    continue
                for ent in get(root, "Entities") or []:
                    nbt = ["compound", ent]
                    snap["entities"][uuid_of(nbt) or "%s %d %d #%d" % (dim, cx, cz, len(snap["entities"]))] = \
                        {"dim": dim, "nbt": nbt}
    for sub in ("players/data", "playerdata"):
        for f in sorted((world / sub).glob("*.dat")) if (world / sub).is_dir() else []:
            snap["players"][f.stem] = read_nbt_file(f)
    for facet, sub in (("advancements", "players/advancements"), ("stats", "players/stats"),
                       ("advancements", "advancements"), ("stats", "stats")):
        for f in sorted((world / sub).glob("*.json")) if (world / sub).is_dir() else []:
            snap[facet][f.stem] = json.loads(f.read_text(encoding="utf-8"))
    for f in sorted(world.rglob("*")):
        rel = f.relative_to(world).as_posix()
        if not f.is_file() or "/data/" not in "/" + rel or rel.startswith("players/"):
            continue
        if f.suffix == ".dat":
            snap["saved_data"][rel] = read_nbt_file(f) or {"unreadable_sha1": hashlib.sha1(f.read_bytes()).hexdigest()}
        elif not f.name.endswith((".dat_old", ".lock")):
            snap["saved_data"][rel] = {"sha1": hashlib.sha1(f.read_bytes()).hexdigest()}
    return snap


# --- rules ---------------------------------------------------------------------------------------------------------

# Keys only the merged base wrote (constant-pool diff of Entity/BlockEntity merged vs genuine; nothing in chunk, level
# or player storage classes): an upgrade drops them on resave, a reopen must never see them. Dropped only when they
# carry nothing the genuine base would have kept: a non-empty ForgeCaps (a Forge mod's capability data) or a ForgeData
# with no NeoForgeData beside it is reported as a loss, never allowed.
MERGED_ENTITY_KEYS = ("ForgeCaps", "ForgeData", "CanUpdate")
MERGED_BE_KEYS = ("ForgeCaps",)
# The MinecraftForge carrier's datapack ids (rank 1; staged forge-runtime-interop, installed forge-runtime-<mc>): Forbric no
# longer serves it, so an upgraded world loses exactly these.
FORGE_CARRIER_PACK = re.compile(r"^forbric/carrier/1-forge-runtime")
NEO_CARRIER_PACK = "forbric/carrier/2-neoforge-runtime"
# Per-tick player state (compared with Pos/Rotation at a tolerance instead).
PLAYER_TICK_KEYS = ("Motion", "OnGround", "fall_distance", "foodTickTimer", "current_impulse_context_reset_grace_time", "Air")
LEVEL_CLOCK_KEYS = ("LastPlayed", "Time")
# Runtime bookkeeping files: listed, never compared.
NOISE_FILES = re.compile(r"(^|/)data/minecraft/(random_sequences|world_clocks|stopwatches|scheduled_events|weather)\.dat$")
RAIDS_FILE = re.compile(r"(^|/)data/minecraft/raids\.dat$")
FORGE_DATA_FILE = re.compile(r"(^|/)data/forge/")
# Lists of compounds whose order is a HashMap artefact (measured: a pig's attributes came back reordered).
UNORDERED_LISTS = ("attributes", "modifiers")


class Rules:
    def __init__(self, mode, ignore=()):
        self.mode = mode
        self.ignore = tuple(ignore)
        self.used = {}
        self.violations = []

    def count(self, name, n=1):
        if n:
            self.used[name] = self.used.get(name, 0) + n

    def violation(self, text):
        self.violations.append(text)


def _canon(v):
    return json.dumps(v, sort_keys=True)


def _mentions(value, ns):
    """Whether a typed/plain structure names namespace `ns` anywhere (an id `ns:...`, a pack forbric/data/ns, a mod row)."""
    if isinstance(value, str):
        return value.startswith(ns + ":") or value == ns or value == "forbric/data/" + ns or ("/" + ns + "/") in value \
            or value.split("[", 1)[0].startswith(ns + ":")
    if isinstance(value, dict):
        return any(_mentions(k, ns) or _mentions(v, ns) for k, v in value.items())
    if isinstance(value, list):
        return any(_mentions(v, ns) for v in value)
    return False


def _strip_namespace(value, ns, rules):
    """Drop every list element and compound entry that names `ns`, recursively; count what went."""
    if isinstance(value, list) and len(value) >= 2 and value[0] == "compound" and isinstance(value[1], dict):
        out = {}
        for k, v in value[1].items():
            if _mentions(k, ns) or (isinstance(v, list) and len(v) == 2 and isinstance(v[1], str) and _mentions(v[1], ns)):
                rules.count("ignore-namespace " + ns)
                continue
            out[k] = _strip_namespace(v, ns, rules)
        return ["compound", out]
    if isinstance(value, list) and len(value) == 3 and value[0] == "list":
        items = []
        for item in value[2]:
            if _mentions(item, ns):
                rules.count("ignore-namespace " + ns)
                continue
            items.append(_strip_namespace(wp.retag(value[1], item), ns, rules))
        return ["list", value[1], [wp.untag(i) for i in items]]
    return value


def _sort_unordered(value, rules):
    if isinstance(value, list) and len(value) >= 2 and value[0] == "compound" and isinstance(value[1], dict):
        out = {}
        for k, v in value[1].items():
            v = _sort_unordered(v, rules)
            if k in UNORDERED_LISTS and isinstance(v, list) and len(v) == 3 and v[0] == "list" and v[1] == "compound":
                ordered = sorted(v[2], key=_canon)
                if ordered != v[2]:
                    rules.count("unordered list " + k)
                v = ["list", "compound", ordered]
            out[k] = v
        return ["compound", out]
    if isinstance(value, list) and len(value) == 3 and value[0] == "list" and value[1] in ("compound", "list"):
        return ["list", value[1], [wp.untag(_sort_unordered(wp.retag(value[1], i), rules)) for i in value[2]]]
    return value


def _nonempty(typed):
    """Whether a typed value holds anything: a compound or list with entries, or any other value."""
    if isinstance(typed, list) and typed and typed[0] == "compound":
        return bool(typed[1])
    if isinstance(typed, list) and len(typed) == 3 and typed[0] == "list":
        return bool(typed[2])
    return True


def _forge_caps_lost(caps, rules, where):
    """A non-empty ForgeCaps is a Forge mod's capability data, which the genuine base never reads: a loss, unless every
    capability belongs to a namespace the run declared gone (--ignore-namespace)."""
    entries = caps[1] if isinstance(caps, list) and caps and caps[0] == "compound" else None
    if entries is None:
        rules.violation("%s: ForgeCaps on the old side is not a compound: %s" % (where, _short(caps)))
        return
    lost = []
    for name in sorted(entries):
        gone = next((ns for ns in rules.ignore if _mentions(name, ns)), None)
        if gone:
            rules.count("ignore-namespace " + gone)
        else:
            lost.append(name)
    if lost:
        rules.violation("%s: ForgeCaps carries capability data the genuine base does not read (lost on upgrade): %s"
                        % (where, ",".join(lost)))
    else:
        rules.count("drop merged-only ForgeCaps")


def _drop_merged(nbt, keys, rules, side, where):
    """Upgrade: drop the merged-only keys from an old-side holder (checking ForgeData mirrored NeoForgeData first).
    Reopen, or the new side of an upgrade: any of them is a violation."""
    if not (isinstance(nbt, list) and nbt[0] == "compound"):
        return nbt
    d = dict(nbt[1])
    present = [k for k in keys if k in d]
    if present and (rules.mode != "upgrade" or side == "b"):
        rules.violation("%s carries merged-only key(s) %s on the %s side under %s rules"
                        % (where, ",".join(present), "new" if side == "b" else "old", rules.mode))
    elif present:
        if "ForgeData" in d and "NeoForgeData" in d and _canon(d["ForgeData"]) != _canon(d["NeoForgeData"]):
            rules.violation("%s: ForgeData differs from NeoForgeData on the old side" % where)
        if "ForgeData" in d and "NeoForgeData" not in d and _nonempty(d["ForgeData"]):
            # The merged writer fills both from one field, so this ForgeData has no copy the genuine base reads.
            rules.violation("%s: ForgeData without NeoForgeData on the old side (lost on upgrade): %s"
                            % (where, _short(d["ForgeData"])))
        for k in present:
            value = d.pop(k)
            if k == "ForgeCaps" and _nonempty(value):
                _forge_caps_lost(value, rules, where)
                continue
            rules.count("drop merged-only " + k)
    for k in ("Passengers",):
        if k in d and d[k][0] == "list" and d[k][1] == "compound":
            d[k] = ["list", "compound", [_drop_merged(["compound", p], keys, rules, side, where + " passenger")[1]
                                         for p in d[k][2]]]
    return ["compound", d]


def normalise(snap, rules, side):
    """A copy of `snap` with the rules applied; `side` is "a" (old/first) or "b" (new/second)."""
    s = copy.deepcopy(snap)
    upgrade = rules.mode == "upgrade"
    for facet in ("block_entities",):
        for key in list(s[facet]):
            s[facet][key] = _drop_merged(s[facet][key], MERGED_BE_KEYS, rules, side, "block entity " + key)
    for key in list(s["entities"]):
        s["entities"][key]["nbt"] = _drop_merged(s["entities"][key]["nbt"], MERGED_ENTITY_KEYS, rules, side, "entity " + key)
    for key in list(s["players"]):
        p = _drop_merged(s["players"][key], MERGED_ENTITY_KEYS, rules, side, "player " + key)
        d = dict(p[1]) if p else {}
        for k in PLAYER_TICK_KEYS:
            if k in d:
                d.pop(k)
                rules.count("player per-tick " + k)
        for k in ("Pos", "Rotation"):
            if k in d and d[k][0] == "list":
                d[k] = ["list", d[k][1], [round(v, 6) for v in d[k][2]]]
        if "active_effects" in d and d["active_effects"][0] == "list":
            effects = []
            for e in d["active_effects"][2]:
                e = dict(e)
                if "duration" in e:
                    e.pop("duration")
                    rules.count("effect duration")
                effects.append(e)
            d["active_effects"] = ["list", "compound", effects]
        s["players"][key] = ["compound", d] if p else p
    # level.dat
    if s["level"]:
        root = dict(s["level"][1])
        data = dict(root.get("Data", ["compound", {}])[1])
        for k in LEVEL_CLOCK_KEYS:
            if k in data:
                data.pop(k)
                rules.count("level clock " + k)
        if "ServerBrands" in data and data["ServerBrands"][0] == "list":
            data["ServerBrands"] = ["list", "string", sorted(set(data["ServerBrands"][2]))]
        packs = data.get("DataPacks")
        if packs:
            pd = dict(packs[1])
            enabled = list(pd.get("Enabled", ["list", "string", []])[2])
            if side == "b":
                if NEO_CARRIER_PACK in enabled:
                    first_mod = min([i for i, p in enumerate(enabled) if p.startswith("forbric/data/")] or [len(enabled)])
                    if enabled.index(NEO_CARRIER_PACK) > first_mod:
                        rules.violation("the NeoForge carrier pack %s sorts after a mod pack in DataPacks.Enabled: %s"
                                        % (NEO_CARRIER_PACK, enabled))
                elif any(p.startswith("forbric/carrier/2-") for p in enabled):
                    rules.violation("the NeoForge carrier is enabled under an id other than %s: %s" % (NEO_CARRIER_PACK, enabled))
            if upgrade and side == "a":
                kept = [p for p in enabled if not FORGE_CARRIER_PACK.match(p)]
                rules.count("drop MinecraftForge carrier pack", len(enabled) - len(kept))
                enabled = kept
            pd["Enabled"] = ["list", "string", enabled]
            data["DataPacks"] = ["compound", pd]
        root["Data"] = ["compound", data]
        s["level"] = ["compound", root]
    # saved data
    for rel in list(s["saved_data"]):
        if NOISE_FILES.search(rel):
            s["saved_data"].pop(rel)
            rules.count("runtime file " + rel.rsplit("/", 1)[-1])
        elif RAIDS_FILE.search(rel) and isinstance(s["saved_data"][rel], list):
            d = dict(s["saved_data"][rel][1])
            inner = d.get("data")
            if inner and inner[0] == "compound" and "tick" in inner[1]:
                inner = ["compound", {k: v for k, v in inner[1].items() if k != "tick"}]
                d["data"] = inner
                rules.count("raids tick")
            s["saved_data"][rel] = ["compound", d]
    for facet in ("block_entities", "chunk_extra", "players", "saved_data"):
        for key in list(s[facet]):
            s[facet][key] = _sort_unordered(s[facet][key], rules)
    for key in list(s["entities"]):
        s["entities"][key]["nbt"] = _sort_unordered(s["entities"][key]["nbt"], rules)
    for ns in rules.ignore:
        for facet in ("blocks", "block_entities", "entities", "chunk_extra", "players", "saved_data"):
            for key in list(s[facet]):
                if _mentions(key, ns) or _mentions(s[facet][key], ns) and facet in ("blocks",):
                    s[facet].pop(key)
                    rules.count("ignore-namespace " + ns)
                    continue
                if facet == "entities":
                    nbt = s[facet][key]["nbt"]
                    if _mentions(get(nbt, "id", ""), ns):
                        s[facet].pop(key)
                        rules.count("ignore-namespace " + ns)
                        continue
                    s[facet][key]["nbt"] = _strip_namespace(nbt, ns, rules)
                elif facet == "block_entities" and _mentions(get(s[facet][key], "id", ""), ns):
                    s[facet].pop(key)
                    rules.count("ignore-namespace " + ns)
                elif facet != "blocks":
                    s[facet][key] = _strip_namespace(s[facet][key], ns, rules)
        if s["level"]:
            s["level"] = _strip_namespace(s["level"], ns, rules)
    return s


def _walk(a, b, path, out):
    """Append (path, a, b) for every leaf where the typed structures differ."""
    if a == b:
        return
    if isinstance(a, list) and isinstance(b, list) and len(a) >= 2 and len(b) >= 2 and a[0] == b[0] == "compound":
        for k in sorted(set(a[1]) | set(b[1])):
            if k not in a[1]:
                out.append((path + "/" + k, None, b[1][k]))
            elif k not in b[1]:
                out.append((path + "/" + k, a[1][k], None))
            else:
                _walk(a[1][k], b[1][k], path + "/" + k, out)
        return
    if isinstance(a, list) and isinstance(b, list) and len(a) == 3 and len(b) == 3 and a[0] == b[0] == "list" \
            and a[1] == b[1] and len(a[2]) == len(b[2]):
        for i, (x, y) in enumerate(zip(a[2], b[2])):
            _walk(wp.retag(a[1], x), wp.retag(b[1], y), "%s[%d]" % (path, i), out)
        return
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append((path + "/" + k, None, b[k]))
            elif k not in b:
                out.append((path + "/" + k, a[k], None))
            else:
                _walk(a[k], b[k], path + "/" + k, out)
        return
    out.append((path, a, b))


FACETS = ("level", "blocks", "biomes", "block_entities", "chunk_extra", "entities", "players", "advancements",
          "stats", "saved_data")


def diff(a, b, mode, ignore=()):
    """(differences, rules): differences are (facet, path, a-value, b-value)."""
    for snap in (a, b):
        if snap.get("schema") != SCHEMA:
            raise ValueError("not a %s snapshot (schema %r)" % (SCHEMA, snap.get("schema")))
    rules = Rules(mode, ignore)
    na, nb = normalise(a, rules, "a"), normalise(b, rules, "b")
    out = []
    for facet in FACETS:
        found = []
        if facet == "saved_data" and mode == "upgrade":
            # MinecraftForge's own SavedData (data/forge/...): an upgraded world may lose it, never gain or change it.
            for rel in list(na[facet]):
                if FORGE_DATA_FILE.search(rel) and rel not in nb[facet]:
                    na[facet].pop(rel)
                    rules.count("forge SavedData gone")
        _walk(na[facet], nb[facet], "", found)
        out += [(facet, p, x, y) for p, x, y in found]
    for v in rules.violations:
        out.append(("rule", v, None, None))
    return out, rules


def _short(v, n=160):
    s = json.dumps(v, sort_keys=True) if v is not None else "(absent)"
    return s if len(s) <= n else s[:n] + "..."


# --- mutate --------------------------------------------------------------------------------------------------------

def _region_path(ddir, cx, cz):
    return Path(ddir) / "region" / ("r.%d.%d.mca" % (cx >> 5, cz >> 5))


def rewrite_chunk(path, cx, cz, root):
    """Write a typed chunk root back into its region file: in place when it fits its sectors, else appended."""
    data = bytearray(Path(path).read_bytes())
    slot = (cx & 31) + (cz & 31) * 32
    payload = zlib.compress(encode_root(root))
    body = struct.pack(">I", len(payload) + 1) + b"\x02" + payload
    need = (len(body) + 4095) // 4096
    entry = struct.unpack_from(">I", data, slot * 4)[0]
    sector, count = entry >> 8, entry & 255
    if sector < 2 or need > count:
        sector = (len(data) + 4095) // 4096
        data += b"\x00" * (sector * 4096 - len(data))
        data += b"\x00" * (need * 4096)
        count = need
    data[sector * 4096:(sector + count) * 4096] = body + b"\x00" * (count * 4096 - len(body))
    struct.pack_into(">I", data, slot * 4, (sector << 8) | count)
    Path(path).write_bytes(bytes(data))


def mutate(world, chests=(), palettes=()):
    world = Path(world)
    dims = dimensions(world)
    changed = []
    touched = {}
    for pos, slot, count in chests:
        dim, x, y, z = pos.split()
        x, y, z = int(x), int(y), int(z)
        cx, cz = x >> 4, z >> 4
        key = (dim, cx, cz)
        if key not in touched:
            touched[key] = next(r for c1, c2, r in wp.chunks_typed(dims[dim] / "region") if (c1, c2) == (cx, cz))
        root = touched[key]
        for be in get(root, "block_entities") or []:
            if (be["x"][1], be["y"][1], be["z"][1]) != (x, y, z):
                continue
            for item in be.get("Items", ["list", "compound", []])[2]:
                if item.get("Slot", ["byte", -1])[1] == slot:
                    old = item["count"][1]
                    item["count"] = ["int", count]
                    changed.append("chest %s slot %d count %d -> %d" % (pos, slot, old, count))
    for spec in palettes:
        old, new = spec.split("=", 1)
        n = 0
        for dim, ddir in dims.items():
            for cx, cz, root in list(wp.chunks_typed(ddir / "region")):
                key = (dim, cx, cz)
                root = touched.get(key, root)
                hit = 0
                for section in get(root, "sections") or []:
                    for entry in get(get(section, "block_states") or {}, "palette") or []:
                        if entry.get("Name", ["string", ""])[1] == old:
                            entry["Name"] = ["string", new]
                            hit += 1
                if hit:
                    touched[key] = root
                    n += hit
        changed.append("palette %s -> %s in %d section palette entr%s" % (old, new, n, "y" if n == 1 else "ies"))
    for (dim, cx, cz), root in touched.items():
        rewrite_chunk(_region_path(dims[dim], cx, cz), cx, cz, root)
    return changed


# --- census --------------------------------------------------------------------------------------------------------

# The registry a snapshot name must be present in, by kind. A census file lists names per registry:
# {"registries": {"minecraft:block": [...], "minecraft:item": [...], ...}}; several files are merged.
KIND_REGISTRY = {"block": "minecraft:block", "block_entity_type": "minecraft:block_entity_type",
                 "entity_type": "minecraft:entity_type", "item": "minecraft:item",
                 "data_component_type": "minecraft:data_component_type", "attachment_type": "attachment_types"}


def referenced_names(snap):
    """{kind: set(names)} for every registry name the snapshot's saved data refers to."""
    names = {k: set() for k in KIND_REGISTRY}
    for state in snap["blocks"].values():
        names["block"].add(state.split("[", 1)[0])

    def visit(v, holder=None):
        if isinstance(v, list) and len(v) >= 2 and v[0] == "compound" and isinstance(v[1], dict):
            d = v[1]
            ident = d.get("id")
            if ident and ident[0] == "string" and ":" in ident[1]:
                if "count" in d:
                    names["item"].add(ident[1])
                elif holder == "entity":
                    names["entity_type"].add(ident[1])
            comps = d.get("components")
            if comps and comps[0] == "compound":
                for k in comps[1]:
                    names["data_component_type"].add(k.lstrip("!"))
            for att in ("neoforge:attachments", "fabric:attachments"):
                if att in d and d[att][0] == "compound":
                    names["attachment_type"].update(d[att][1])
            for k, x in d.items():
                visit(x, "entity" if k == "Passengers" else None)
        elif isinstance(v, list) and len(v) == 3 and v[0] == "list" and v[1] in ("compound", "list"):
            for item in v[2]:
                visit(wp.retag(v[1], item), holder)

    for be in snap["block_entities"].values():
        names["block_entity_type"].add(get(be, "id", "?"))
        visit(be)
    for ent in snap["entities"].values():
        visit(ent["nbt"], "entity")
    for facet in ("players", "chunk_extra", "saved_data"):
        for v in snap[facet].values():
            visit(v)
    if snap.get("level"):
        visit(snap["level"])
    return names


def census(snap, registries, drops=()):
    """[(kind, name)] referenced by the snapshot and absent from the merged census."""
    have = {}
    for reg in registries:
        for key, values in reg.get("registries", {}).items():
            have.setdefault(key, set()).update(values)
    for spec in drops:
        kind, name = spec.split("=", 1)
        have.get(KIND_REGISTRY[kind], set()).discard(name)
    missing = []
    for kind, names in referenced_names(snap).items():
        reg = KIND_REGISTRY[kind]
        for n in sorted(names):
            if n not in have.get(reg, set()):
                missing.append((kind, n))
    return missing


# --- CLI -----------------------------------------------------------------------------------------------------------

def _box(text):
    if not text:
        return None
    v = [int(x) for x in text.split(",")]
    if len(v) != 4:
        raise argparse.ArgumentTypeError("--chunks wants X0,Z0,X1,Z1")
    return [min(v[0], v[2]), min(v[1], v[3]), max(v[0], v[2]), max(v[1], v[3])]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("export")
    e.add_argument("world", type=Path)
    e.add_argument("--out", type=Path, required=True)
    e.add_argument("--chunks", type=_box, default=None)
    e.add_argument("--label", default="")
    d = sub.add_parser("diff")
    d.add_argument("a", type=Path)
    d.add_argument("b", type=Path)
    d.add_argument("--rules", choices=("upgrade", "reopen"), required=True)
    d.add_argument("--ignore-namespace", action="append", default=[])
    d.add_argument("--list", type=int, default=40)
    m = sub.add_parser("mutate")
    m.add_argument("world", type=Path)
    m.add_argument("--chest-count", nargs=3, action="append", default=[], metavar=("POS", "SLOT", "COUNT"))
    m.add_argument("--palette", action="append", default=[])
    c = sub.add_parser("census")
    c.add_argument("snapshot", type=Path)
    c.add_argument("registries", type=Path, nargs="+")
    c.add_argument("--drop", action="append", default=[], help="KIND=NAME: remove a name from the census (a control)")
    args = parser.parse_args(argv)

    if args.cmd == "export":
        if not (args.world / "level.dat").is_file():
            print("[snapshot] FATAL no level.dat in %s" % args.world, file=sys.stderr)
            return 3
        snap = export(args.world, args.chunks, args.label)
        args.out.write_text(json.dumps(snap, sort_keys=True), encoding="utf-8")
        print("[snapshot] exported %s: %s" % (args.world, " ".join("%s=%d" % (f, len(snap[f])) for f in FACETS
                                                                   if isinstance(snap[f], dict))))
        return 0
    if args.cmd == "diff":
        try:
            a = json.loads(args.a.read_text(encoding="utf-8"))
            b = json.loads(args.b.read_text(encoding="utf-8"))
            found, rules = diff(a, b, args.rules, args.ignore_namespace)
        except (OSError, ValueError) as err:
            print("[snapshot] FATAL %s" % err, file=sys.stderr)
            return 3
        print("rules: %s%s" % (args.rules, "".join(" ignore-namespace=" + n for n in args.ignore_namespace)))
        for name in sorted(rules.used):
            print("rule %s: %d" % (name, rules.used[name]))
        by_facet = {}
        for facet, path, x, y in found:
            by_facet[facet] = by_facet.get(facet, 0) + 1
        for facet, path, x, y in found[:args.list]:
            if facet == "rule":
                print("diff rule %s" % path)
            else:
                print("diff %s %s: %s -> %s" % (facet, path, _short(x), _short(y)))
        if len(found) > args.list:
            print("... %d more" % (len(found) - args.list))
        print("differences by facet: %s" % (" ".join("%s=%d" % kv for kv in sorted(by_facet.items())) or "none"))
        print("differences: %d" % len(found))
        return 1 if found else 0
    if args.cmd == "mutate":
        changed = mutate(args.world, [(p, int(s), int(n)) for p, s, n in args.chest_count], args.palette)
        for line in changed:
            print("[snapshot] mutated %s" % line)
        return 0 if changed else 1
    if args.cmd == "census":
        snap = json.loads(args.snapshot.read_text(encoding="utf-8"))
        regs = [json.loads(p.read_text(encoding="utf-8")) for p in args.registries]
        missing = census(snap, regs, args.drop)
        names = referenced_names(snap)
        print("census: referenced %s" % " ".join("%s=%d" % (k, len(v)) for k, v in sorted(names.items())))
        for kind, name in missing:
            print("census missing %s %s" % (kind, name))
        print("census missing: %d" % len(missing))
        return 1 if missing else 0
    return 3


if __name__ == "__main__":
    sys.exit(main())
