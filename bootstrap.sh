#!/usr/bin/env bash
# Fetch and patch the fabric-loader substrate this repository builds against.
#
# Forbric reuses the Apache-2.0 fabric-loader as its substrate: forbric-loader/build.gradle compiles
# the substrate's source roots alongside Forbric's own (see CREDITS.md / NOTICE). That checkout is NOT
# vendored here -- it stays a sibling directory, upstream and inspectable, which is what keeps the
# clean-room provenance auditable. This script puts it there.
#
#   ./bootstrap.sh              # clone (or update) ./fabric-loader and apply the 8 required patches
#   ./bootstrap.sh --check      # verify only; change nothing
#
# Environment:
#   FABRIC_LOADER_REMOTE   clone URL (default: GitHub). Set this to a mirror if github.com is slow
#                          for you, e.g. https://gitee.com/mirrors/fabric-loader.git
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUBSTRATE="$HERE/fabric-loader"
PATCHES="$HERE/forbric-loader/patches/fabric-loader"
REMOTE="${FABRIC_LOADER_REMOTE:-https://github.com/FabricMC/fabric-loader.git}"

# Single source of truth for which upstream release Forbric's patches apply to.
REF="$(sed -n 's/^fabric_loader_ref[[:space:]]*=[[:space:]]*//p' "$HERE/forbric-loader/gradle.properties" | tr -d '[:space:]')"
[ -n "$REF" ] || { echo "bootstrap: fabric_loader_ref missing from forbric-loader/gradle.properties" >&2; exit 1; }

check_only=0
[ "${1:-}" = "--check" ] && check_only=1

if [ ! -d "$SUBSTRATE/.git" ]; then
	[ "$check_only" -eq 1 ] && { echo "bootstrap: $SUBSTRATE is not a git checkout -- run ./bootstrap.sh" >&2; exit 1; }
	echo "bootstrap: cloning $REMOTE at $REF -> fabric-loader/"
	git clone --quiet --depth 1 --branch "$REF" "$REMOTE" "$SUBSTRATE"
fi

# The patches are expressed as diffs against $REF, and run/verify-substrate-patches.sh compares against
# it too, so the tag has to be resolvable in the checkout.
if ! git -C "$SUBSTRATE" rev-parse --verify --quiet "$REF^{commit}" >/dev/null; then
	[ "$check_only" -eq 1 ] && { echo "bootstrap: $REF is not present in $SUBSTRATE -- run ./bootstrap.sh" >&2; exit 1; }
	echo "bootstrap: fetching $REF"
	git -C "$SUBSTRATE" fetch --quiet --depth 1 origin "refs/tags/$REF:refs/tags/$REF"
fi

# Apply anything not already applied. A patch whose live diff already matches the reference is a no-op,
# which makes re-running this script safe.
applied=0 already=0
for patch in "$PATCHES"/*.patch; do
	if git -C "$SUBSTRATE" apply --reverse --check "$patch" >/dev/null 2>&1; then
		already=$((already + 1))
		continue
	fi
	[ "$check_only" -eq 1 ] && { echo "bootstrap: $(basename "$patch") is not applied -- run ./bootstrap.sh" >&2; exit 1; }
	git -C "$SUBSTRATE" apply "$patch"
	applied=$((applied + 1))
done

echo "bootstrap: substrate at $REF -- $applied patch(es) applied, $already already present"
exec "$HERE/forbric-loader/run/verify-substrate-patches.sh"
