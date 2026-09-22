#!/usr/bin/env python3
"""Name the mods that actually REFERENCE a set of symbols — by default the Fabric API
surfaces pinned by MergedBaseMixinCompat.

Usage: fapi-usage.py [--preset NAME | --symbols FILE] [--list-presets] <jar-or-mods-dir>...

Only class references are evidence; resource strings and dependency declarations
do not establish API use. Nested META-INF/jars are scanned with their parent label.

WHY THE SYMBOL SET IS A PARAMETER. This script answered exactly one question --
"who consumes the four suppressed Fabric API surfaces" -- with the answer's
prefixes frozen in a constant. The same two judgements it already gets right
(constant-pool references only; a bundled API defining itself is not a use of it)
are what "which mods are actually WAITING on this Forge event" needs, and that
question has no tool at all: the event-hook censuses in this project were done by
hand with `javap` and written into javadoc, so they are true on the day they are
written and unfalsifiable afterwards. Two copies of this scanner would be two
places for the evidence rules to drift, so the prefixes move out to data and the
file keeps its name.

Symbol-file / preset format: one entry per line, `#` comments and blanks ignored.
  net/minecraftforge/event/        a reference INTO this prefix is a use
  !net/minecraftforge/             a class whose OWN name is under this prefix is
                                   DEFINING the API, not using it -- skip it

The `!` lines are explicit rather than derived from the symbol prefixes, because
guessing the owning package from the surface is exactly the kind of inference that
turns "this mod ships the API" into "this mod consumes the API".
"""
import argparse
import importlib.util
from pathlib import Path
import sys
import zipfile

# Keep the bytecode parser identical to abi-audit, including nested-jar handling.
_spec = importlib.util.spec_from_file_location("forbric_abi_audit", Path(__file__).with_name("abi-audit.py"))
abi = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(abi)

PRESETS = {
    # MergedBaseMixinCompat.SUPPRESSED_MIXINS: loot, model loading and creative-tab duck.
    "fabric-api-suppressed": (
        "net/fabricmc/fabric/api/loot/v3/",
        "net/fabricmc/fabric/api/client/model/loading/v1/",
        "net/fabricmc/fabric/api/creativetab/v1/",
        "net/fabricmc/fabric/api/client/creativetab/v1/",
        "!net/fabricmc/fabric/",
    ),
    # Traditional MinecraftForge's event surface -- the family whose hooks the byte-merge
    # drops when NeoForge patched the same method (see merge-conflicts.txt, "forge hook lost").
    "minecraftforge-events": (
        "net/minecraftforge/event/",
        "!net/minecraftforge/",
    ),
    "minecraftforge-client-events": (
        "net/minecraftforge/client/event/",
        "!net/minecraftforge/",
    ),
    "neoforge-events": (
        "net/neoforged/neoforge/event/",
        "!net/neoforged/",
    ),
    # The two halves of the one thing a tri-ecosystem loader can promise and no single loader can: a Fabric
    # mod's pipe reading a Forge mod's machine. There is no bridge between them in the kernel -- the Forge and
    # NeoForge adapters are twins of one API, not a crossing -- and no gate has ever put a mod from each side in
    # contact. These two presets are how that seam gets a number instead of an assumption.
    "fabric-transfer-lookup": (
        "net/fabricmc/fabric/api/transfer/",
        "net/fabricmc/fabric/api/lookup/",
        "!net/fabricmc/fabric/",
    ),
    "forge-capabilities": (
        "net/minecraftforge/common/capabilities/",
        "net/neoforged/neoforge/capabilities/",
        "!net/minecraftforge/",
        "!net/neoforged/",
    ),
}
DEFAULT_PRESET = "fabric-api-suppressed"


def split_symbols(entries):
    """(surfaces, definers) -- `!` marks a package whose classes DEFINE rather than use."""
    surfaces, definers = [], []
    for raw in entries:
        entry = raw.strip()
        if not entry or entry.startswith("#"):
            continue
        (definers if entry.startswith("!") else surfaces).append(entry.lstrip("!"))
    return tuple(surfaces), tuple(definers)


def read_symbol_file(path):
    return split_symbols(Path(path).read_text(encoding="utf-8").splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--preset", choices=sorted(PRESETS), default=None,
                        help="named symbol set (default: %s)" % DEFAULT_PRESET)
    parser.add_argument("--symbols", default=None, help="file of symbol prefixes, one per line")
    parser.add_argument("--list-presets", action="store_true", help="print the named symbol sets and exit")
    parser.add_argument("candidates", nargs="*", help="candidate jar or directory of jars")
    args = parser.parse_args()

    if args.list_presets:
        for name in sorted(PRESETS):
            surfaces, definers = split_symbols(PRESETS[name])
            print("%s: %d surface(s), %d definer prefix(es)" % (name, len(surfaces), len(definers)))
            for s in surfaces:
                print("    %s" % s)
            for d in definers:
                print("    !%s" % d)
        return 0

    if args.preset and args.symbols:
        print("give --preset or --symbols, not both", file=sys.stderr)
        return 2
    if not args.candidates:
        parser.print_usage(sys.stderr)
        print("no candidate jar or directory given", file=sys.stderr)
        return 2

    try:
        if args.symbols:
            surfaces, definers = read_symbol_file(args.symbols)
            source = args.symbols
        else:
            source = args.preset or DEFAULT_PRESET
            surfaces, definers = split_symbols(PRESETS[source])
    except OSError as exc:
        print("unreadable symbol file: %s" % exc, file=sys.stderr)
        return 2
    if not surfaces:
        print("the symbol set has no surfaces, so every jar would read clean", file=sys.stderr)
        return 2

    findings = {}
    count = 0
    try:
        for candidate in args.candidates:
            for jar in abi.jar_paths(candidate):
                count += 1
                with zipfile.ZipFile(jar) as archive:
                    for label, name, refs in abi.scan_classes(archive, jar.name):
                        # A bundled API defining itself is not evidence that the candidate uses it.
                        if definers and name.startswith(definers):
                            continue
                        for ref in refs:
                            if ref.startswith(surfaces):
                                findings.setdefault(label, {}).setdefault(ref, set()).add(name)
        # Say what was asked before saying what was found: a census with an unstated question
        # is the same trap as a count nobody compares.
        print("symbol set: %s (%d surface(s), %d definer prefix(es))" % (source, len(surfaces), len(definers)))
        for label in sorted(findings):
            print(label)
            for ref, users in sorted(findings[label].items()):
                print("    %s <- %s" % (ref, ", ".join(sorted(users))))
        print("scanned jars: %d; API consumer groups: %d" % (count, len(findings)))
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print("unreadable: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
