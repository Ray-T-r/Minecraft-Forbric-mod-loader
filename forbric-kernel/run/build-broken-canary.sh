#!/usr/bin/env bash
# Builds the canary mod that fails on purpose, for gate-m24-brokenmod.sh.
#
# Every gate in this tree asserts that NO mod failed — gate-m4 and gate-m7 each carry a
# check_absent "no @Mod construction failure". That is the right assertion for those gates, and it is the exact
# opposite of the one nobody had: when a mod DOES fail, does the instance carry on, and is the failure findable
# afterwards.
#
# Which is the whole cost of Forbric's posture. It loads as much as it can rather than stopping at the first
# problem, so one mod's failure has to stay one mod's failure. Until this canary existed, both halves of that
# were asserted only by unit tests over hand-built fixtures.
#
# Output: run/canary/forbricbrokencanary.jar
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SRC="$KERNEL/canary/broken"
OUT="$KERNEL/run/canary"
canary_scratch broken

step "prerequisites"
mkdir -p "$BUILD"
kernel_jar
KERNEL_JAR="$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
[ -f "$KERNEL_JAR" ] || { echo "[kernel] FAIL kernel jar not built"; exit 1; }

mkdir -p "$OUT"

step "compile the mod that fails on purpose"
# Compiled against the kernel jar only: it touches nothing but net.fabricmc.api, which is what makes it a
# realistic stand-in for any mod whose failure has nothing to do with the game's own classes.
javac -nowarn -proc:none --release 21 -cp "$KERNEL_JAR" -d "$WORK" \
      "$SRC/src/forbric/brokencanary/ForbricBrokenCanary.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/forbric/brokencanary/ForbricBrokenCanary.class" ] || {
  echo "[kernel] FAIL broken canary did not compile"; exit 1; }

cp "$SRC/fabric.mod.json" "$WORK/"
(cd "$WORK" && jar --create --file "$BUILD/forbricbrokencanary.$$.jar" .) || exit 1
publish_canary "$BUILD/forbricbrokencanary.$$.jar" "$OUT/forbricbrokencanary.jar" || exit 1

step "result"
echo "[kernel] ✅ built $OUT/forbricbrokencanary.jar"
unzip -l "$OUT/forbricbrokencanary.jar" | grep -E 'fabric.mod.json|ForbricBrokenCanary'
