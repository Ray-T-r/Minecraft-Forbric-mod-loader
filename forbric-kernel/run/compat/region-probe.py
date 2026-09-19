#!/usr/bin/env python3
"""Count chunks containing requested byte strings in a saved Anvil region directory.

Usage: region-probe.py <region-dir> <needle>... [--dungeons]
Counts are chunks containing each needle, not individual blocks. The dungeon preset
retains spawner/mossy-cobblestone/mob observations and reports their co-occurrence.
LZ4, custom/external compression and corrupt chunks are explicitly unreadable.
"""
import argparse
from collections import Counter
import gzip
from pathlib import Path
import struct
import sys
import zlib


DUNGEON_NEEDLES = ("minecraft:spawner", "minecraft:mossy_cobblestone",
                   "minecraft:zombie", "minecraft:skeleton", "minecraft:spider")


def probe(directory, needles, dungeons=False):
    hits = Counter({needle: 0 for needle in needles})
    unreadable = Counter({"lz4": 0, "custom": 0, "corrupt": 0})
    chunks = 0
    dungeon_hits = Counter({"spawner block entity": 0, "mossy cobblestone": 0, "dungeons": 0})
    for mob in DUNGEON_NEEDLES[2:]:
        dungeon_hits["spawner chunk naming " + mob] = 0
    encoded = {needle: needle.encode("utf-8") for needle in needles}
    for path in sorted(directory.glob("*.mca")):
        data = path.read_bytes()
        if len(data) < 8192:
            unreadable["corrupt"] += 1
            continue
        for slot in range(1024):
            entry = struct.unpack_from(">I", data, slot * 4)[0]
            sector, count = entry >> 8, entry & 255
            if sector == 0 and count == 0:
                continue
            start = sector * 4096
            if sector < 2 or count == 0 or start + 5 > len(data):
                unreadable["corrupt"] += 1
                continue
            length = struct.unpack_from(">I", data, start)[0]
            if length < 1 or start + 4 + length > min(len(data), start + count * 4096):
                unreadable["corrupt"] += 1
                continue
            compression = data[start + 4]
            if compression & 128:
                unreadable["custom"] += 1  # External .mcc payload is not resident in this region file.
                continue
            if compression == 4:
                unreadable["lz4"] += 1
                continue
            if compression not in (1, 2, 3):
                unreadable["custom"] += 1
                continue
            blob = data[start + 5:start + 4 + length]
            try:
                raw = gzip.decompress(blob) if compression == 1 else (zlib.decompress(blob) if compression == 2 else blob)
            except (OSError, EOFError, zlib.error):
                unreadable["corrupt"] += 1
                continue
            # A valid Anvil chunk stores an NBT root compound; uncompressed garbage
            # must not count as a successfully read chunk merely because it contains a needle.
            if len(raw) < 4 or raw[0] != 10:
                unreadable["corrupt"] += 1
                continue
            chunks += 1
            for needle, value in encoded.items():
                if value in raw:
                    hits[needle] += 1
            if dungeons:
                spawner = b"minecraft:spawner" in raw
                mossy = b"minecraft:mossy_cobblestone" in raw
                dungeon_hits["spawner block entity"] += spawner
                dungeon_hits["mossy cobblestone"] += mossy
                dungeon_hits["dungeons"] += spawner and mossy
                for mob in DUNGEON_NEEDLES[2:]:
                    if spawner and mob.encode("utf-8") in raw:
                        dungeon_hits["spawner chunk naming " + mob] += 1
    return chunks, hits, unreadable, dungeon_hits


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("region_dir", type=Path)
    parser.add_argument("needles", nargs="*")
    parser.add_argument("--dungeons", action="store_true", help="include the monster-room preset")
    args = parser.parse_args()
    if not args.needles and not args.dungeons:
        parser.error("supply at least one needle or --dungeons")
    if not args.region_dir.is_dir():
        parser.error("region directory does not exist: %s" % args.region_dir)
    needles = list(dict.fromkeys(args.needles + (list(DUNGEON_NEEDLES) if args.dungeons else [])))
    if any(not needle for needle in needles):
        parser.error("needles must not be empty")
    try:
        chunks, hits, unreadable, dungeon_hits = probe(args.region_dir, needles, args.dungeons)
    except OSError as exc:
        print("unreadable region file: %s" % exc, file=sys.stderr)
        return 2
    print("chunks read: %d" % chunks)
    for needle, count in hits.items():
        print("%s: %d" % (needle, count))
    if args.dungeons:
        for label, count in dungeon_hits.items():
            print("%s: %d" % (label, count))
    print("unreadable: %d (lz4=%d, custom=%d, corrupt=%d)" %
          (sum(unreadable.values()), unreadable["lz4"], unreadable["custom"], unreadable["corrupt"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
