#!/usr/bin/env bash
# The installer must ship the link checker the tree builds, not whichever one it bundled last.
#
# WHY THIS EXISTS. The installer carries the kernel's link checker as a jar it built earlier (:bundleLinkCheck, from the
# kernel's :linkCheckJar). Its predecessor carried the merge-tools jar the same way, and a change to one of that jar's
# inputs which the build did not declare (its baseline) left the bundling UP-TO-DATE: every player's install then ran the
# previous tool. This builds the installer's checker twice in a throwaway copy of the tracked sources -- changing only a
# checker source in between -- and reads the checker back out of the jar the installer would carry, comparing it with
# the class the copy's kernel compiled.
#
# Usage: ./test-bundled-checker.sh      (needs a warm gradle cache: it builds offline)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/forbric-bundled-checker-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAIL=0
# The kernel's build files and main sources, and the whole installer: what the two builds read, nothing else.
(cd "$ROOT" && git ls-files -z forbric-kernel-installer forbric-kernel/build.gradle forbric-kernel/settings.gradle \
    forbric-kernel/gradle.properties forbric-kernel/gradlew forbric-kernel/gradle forbric-kernel/src/main \
  | xargs -0 tar -cf - | tar -xf - -C "$WORK") || { echo "[test-bundled-checker] could not copy the sources" >&2; exit 3; }
SOURCE="$WORK/forbric-kernel/src/main/java/net/forbric/kernel/linkcheck/LinkCheck.java"
COMPILED="$WORK/forbric-kernel/build/classes/java/main/net/forbric/kernel/linkcheck/LinkCheck.class"
BUNDLED="$WORK/forbric-kernel-installer/build/generated-link-check/forbric/tools/forbric-link-check.jar"
# The copy has no staged game: a FORBRIC_OLD that does not exist is the unstaged machine, which builds the boot side only.
export FORBRIC_OLD="$WORK/no-stage"
bundle() { (cd "$WORK/forbric-kernel-installer" && ./gradlew --offline -q bundleLinkCheck) > "$WORK/$1.log" 2>&1; }
packed() { unzip -p "$BUNDLED" net/forbric/kernel/linkcheck/LinkCheck.class 2>/dev/null; }
same() { # <what>
  if [ -f "$COMPILED" ] && packed | cmp -s - "$COMPILED"; then echo "  PASS $1"; else echo "  FAIL $1"; FAIL=1; fi
}

echo "[test-bundled-checker] 1/3 the first bundle carries the checker the tree compiles"
bundle first || { echo "  FAIL bundleLinkCheck failed: $(tail -5 "$WORK/first.log")"; exit 1; }
same "the bundled jar holds the kernel's LinkCheck.class"
# Listed into a variable first: `unzip -l | grep -q` under pipefail fails when grep stops reading early.
LISTING="$(unzip -l "$BUNDLED" 2>/dev/null)"
if printf '%s\n' "$LISTING" | grep -aq 'org/objectweb/asm/ClassReader.class'; then
  echo "  PASS the bundled jar carries ASM's core, so it runs on its own"
else
  echo "  FAIL the bundled jar has no ASM"; FAIL=1
fi
FIRST="$(packed | shasum | cut -d' ' -f1)"
echo "  (first bundle's LinkCheck.class: $FIRST)"

echo "[test-bundled-checker] 2/3 a checker-source-only change reaches the bundled jar"
python3 - "$SOURCE" <<'PY' || { echo "  FAIL could not change the copy's checker source"; exit 1; }
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = '"usage: LinkCheck '  # a constant of LinkCheck.class itself, not of a nested class
if old not in s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(old, '"usage (changed): LinkCheck ', 1))
PY
bundle second || { echo "  FAIL bundleLinkCheck failed: $(tail -5 "$WORK/second.log")"; FAIL=1; }
same "the rebuilt bundle holds the CHANGED LinkCheck.class"
SECOND="$(packed | shasum | cut -d' ' -f1)"
echo "  (second bundle's LinkCheck.class: $SECOND)"
if [ -n "$SECOND" ] && [ "$SECOND" != "$FIRST" ]; then
  echo "  PASS the bundled class is not the first build's"
else
  echo "  FAIL the bundled class is still the first build's"; FAIL=1
fi

echo "[test-bundled-checker] 3/3 the bundled checker runs on its own and says it ran"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$WORK/empty"
OUT="$("$JAVA" -cp "$BUNDLED" net.forbric.kernel.linkcheck.LinkCheck "$WORK/empty" 2>&1)"; RC=$?
if [ "$RC" = 2 ] && printf '%s\n' "$OUT" | grep -aq 'no classes'; then
  echo "  PASS a checker with nothing to judge refuses a verdict (exit 2) instead of passing"
else
  echo "  FAIL the bundled checker did not refuse an empty input (exit $RC): $OUT"; FAIL=1
fi

echo
if [ "$FAIL" = "0" ]; then echo "[test-bundled-checker] ✅ ALL GREEN"; else echo "[test-bundled-checker] ❌ FAILED"; fi
exit "$FAIL"
