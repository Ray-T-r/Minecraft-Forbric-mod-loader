#!/usr/bin/env python3
"""Name Fabric API consumers of the surfaces pinned by MergedBaseMixinCompat.

Usage: fapi-usage.py <candidate-jar-or-mods-directory>...
Only class references are evidence; resource strings and dependency declarations
do not establish API use. Nested META-INF/jars are scanned with their parent label.
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

# MergedBaseMixinCompat.SUPPRESSED_MIXINS: loot, model loading and creative-tab duck.
API_PACKAGES = (
    "net/fabricmc/fabric/api/loot/v3/",
    "net/fabricmc/fabric/api/client/model/loading/v1/",
    "net/fabricmc/fabric/api/creativetab/v1/",
    "net/fabricmc/fabric/api/client/creativetab/v1/",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidates", nargs="+", help="candidate jar or directory of jars")
    args = parser.parse_args()
    findings = {}
    count = 0
    try:
        for candidate in args.candidates:
            for jar in abi.jar_paths(candidate):
                count += 1
                with zipfile.ZipFile(jar) as archive:
                    for label, name, refs in abi.scan_classes(archive, jar.name):
                        # A bundled API defining itself is not evidence that the candidate uses it.
                        if name.startswith("net/fabricmc/fabric/"):
                            continue
                        for ref in refs:
                            if ref.startswith(API_PACKAGES):
                                findings.setdefault(label, {}).setdefault(ref, set()).add(name)
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
