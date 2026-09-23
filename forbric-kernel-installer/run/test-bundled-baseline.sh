#!/usr/bin/env bash
# The installer must ship the reviewed link baseline that is in the tree, not whichever one it bundled last.
#
# WHY THIS EXISTS. The baseline rides inside the merge-tools jar but lives under forbric-loader/src/test/resources,
# which buildMergeTools did not declare as an input: a baseline-only change left it and bundleTools UP-TO-DATE, and
# every player's install then judged the merge against the previous list. This builds the installer's tools twice
# in a throwaway copy of the tracked sources -- changing only the baseline in between -- and reads the baseline
# back out of the jar the installer would carry. It also checks that bundleTools refuses a stale tools jar.
#
# Usage: ./test-bundled-baseline.sh      (needs ../fabric-loader, as the loader build does, and a warm gradle cache)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SUBSTRATE="${FABRIC_LOADER_DIR:-$ROOT/fabric-loader}"
[ -d "$SUBSTRATE/src" ] || { echo "[test-bundled-baseline] no fabric-loader substrate at $SUBSTRATE" >&2; exit 3; }
WORK="$(mktemp -d "${TMPDIR:-/tmp}/forbric-bundled-baseline-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAIL=0
(cd "$ROOT" && git ls-files -z forbric-loader forbric-kernel-installer | xargs -0 tar -cf - | tar -xf - -C "$WORK") || exit 3
ln -s "$(cd "$SUBSTRATE" && pwd)" "$WORK/fabric-loader"
BASELINE="$WORK/forbric-loader/src/test/resources/merge/link-check-baseline.txt"
BUNDLED="$WORK/forbric-kernel-installer/build/generated-tools/forbric/tools/forbric-merge-tools.jar"
MERGETOOLS="$WORK/forbric-loader/build/libs/forbric-merge-tools-0.1.0.jar"
bundle() { (cd "$WORK/forbric-kernel-installer" && ./gradlew --offline -q bundleTools) > "$WORK/$1.log" 2>&1; }
packed() { unzip -p "$1" net/forbric/tools/link-check-baseline.txt 2>/dev/null; }
same() { # <what> <jar>
  if packed "$2" | cmp -s - "$BASELINE"; then echo "  PASS $1"; else echo "  FAIL $1"; FAIL=1; fi
}

echo "[test-bundled-baseline] 1/3 first bundle carries the tracked baseline"
bundle first || { echo "  FAIL bundleTools failed: $(tail -5 "$WORK/first.log")"; exit 1; }
same "the bundled jar holds the tracked baseline" "$BUNDLED"

echo "[test-bundled-baseline] 2/3 a baseline-only change reaches the bundled jar"
printf '# reviewed: a baseline-only change\n' >> "$BASELINE"
bundle second || { echo "  FAIL bundleTools failed: $(tail -5 "$WORK/second.log")"; FAIL=1; }
same "the rebuilt bundle holds the CHANGED baseline" "$BUNDLED"

echo "[test-bundled-baseline] 3/3 NEGATIVE CONTROL: a stale tools jar is refused, not bundled"
printf '# reviewed: a second change\n' >> "$BASELINE"
touch -r "$BUNDLED" "$MERGETOOLS"
(cd "$WORK/forbric-kernel-installer" && ./gradlew --offline -q bundleTools -x buildMergeTools -x buildLoaderJars) \
    > "$WORK/stale.log" 2>&1
if [ $? -ne 0 ] && grep -aq "different link baseline" "$WORK/stale.log"; then
  echo "  PASS bundleTools refuses a jar whose baseline is not the tracked one"
else
  echo "  FAIL a stale tools jar was bundled: $(tail -5 "$WORK/stale.log")"; FAIL=1
fi

echo
if [ "$FAIL" = "0" ]; then echo "[test-bundled-baseline] ✅ ALL GREEN"; else echo "[test-bundled-baseline] ❌ FAILED"; fi
exit "$FAIL"
