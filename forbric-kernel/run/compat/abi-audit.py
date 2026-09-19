#!/usr/bin/env python3
"""Report loader classes referenced by mods but absent from the supplied carrier jars.

Usage: abi-audit.py <mods-dir-or-jar> <carrier-jar>...
CONSTANT_Class references and nested META-INF/jars are read without loading Java.
Findings are a report (exit 0); unreadable input is an incomplete audit (exit 2).
"""
import argparse
import io
from pathlib import Path
import struct
import sys
import zipfile


def class_refs(data):
    """Read CONSTANT_Class entries, shared with the Fabric API consumer probe."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack_from(">H", data, 8)[0]
    off, utf8, classes, i = 10, {}, [], 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            utf8[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag == 7:
            classes.append(struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag in (8, 16, 19, 20):
            off += 2
        elif tag == 15:
            off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        else:
            raise ValueError("unknown constant-pool tag %d" % tag)
        if off > len(data):
            raise ValueError("truncated constant pool")
        i += 1
    refs = set()
    for index in classes:
        name = utf8[index].lstrip("[")
        if name.startswith("L") and name.endswith(";"):
            name = name[1:-1]
        refs.add(name)
    return refs


def jar_paths(path):
    path = Path(path)
    if path.is_dir():
        return sorted(path.glob("*.jar"))
    if path.is_file():
        return [path]
    raise ValueError("input does not exist: %s" % path)


def scan_classes(archive, label):
    """Yield label, class entry and type references, recursively through jar-in-jar."""
    for name in sorted(archive.namelist()):
        if name.endswith(".class"):
            try:
                yield label, name, class_refs(archive.read(name))
            except (IndexError, KeyError, ValueError, struct.error) as exc:
                raise ValueError("%s :: %s: %s" % (label, name, exc)) from exc
        elif name.endswith(".jar") and name.startswith("META-INF/jars/"):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as inner:
                yield from scan_classes(inner, label + " :: " + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mods", help="directory of mods or one candidate jar")
    parser.add_argument("carriers", nargs="+", help="carrier jars (and optional merged base)")
    args = parser.parse_args()
    try:
        owned = set()
        for carrier in args.carriers:
            with zipfile.ZipFile(carrier) as archive:
                owned.update(name[:-6] for name in archive.namelist() if name.endswith(".class"))
        findings = {}
        jars = jar_paths(args.mods)
        for jar in jars:
            with zipfile.ZipFile(jar) as archive:
                for label, name, refs in scan_classes(archive, jar.name):
                    for ref in refs:
                        if ref.startswith(("net/neoforged/", "net/minecraftforge/")) and ref not in owned:
                            findings.setdefault(label, {}).setdefault(ref, set()).add(name)
        print("carrier classes: %d\n" % len(owned))
        for label in sorted(findings):
            print(label)
            for missing, entries in sorted(findings[label].items()):
                users = sorted(entries)
                suffix = " (+%d more)" % (len(users) - 1) if len(users) > 1 else ""
                print("    %-80s  <- %s%s" % (missing, users[0], suffix))
            print()
        print("scanned jars: %d; finding groups: %d" % (len(jars), len(findings)))
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print("unreadable: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
