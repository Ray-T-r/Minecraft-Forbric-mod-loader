#!/usr/bin/env bash
# -Pforbric.stagedRoot moves every task's staged inputs, and only the TEST task needs it to name a run/ directory.
#
# WHY THIS EXISTS. build.gradle documents -Pforbric.stagedRoot=<a directory that does not exist> as the one command
# that reproduces a machine without staged artifacts, to show the boot jar still builds there. The check that the
# tests' root is a run/ directory (they resolve FORBRIC_OLD + '/run') was written straight into the eagerly
# configured test {} block, so it threw while ANY build was configured -- `jar` included -- and the documented
# recipe stopped working. This configures the recipe (a dry run: nothing is built or written), then checks that
# the test task still refuses a root that is not run/ when it runs. A run/ root is the ordinary case, exercised
# by every unit-test run (StagedRootHandoffTest).
#
# Usage: ./test-staged-root.sh      (needs a warm gradle cache; gate-m0 runs it after the unit tests)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/forbric-staged-root-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAIL=0
gradle() { "$KERNEL/gradlew" --offline -p "$KERNEL" "$@"; }

echo "[test-staged-root] 1/2 a staged root that does not exist still configures the boot-jar build"
if gradle -q --dry-run jar "-Pforbric.stagedRoot=$WORK/nothing" > "$WORK/jar.log" 2>&1; then
  echo "  PASS jar configures with -Pforbric.stagedRoot=$WORK/nothing"
else
  echo "  FAIL jar does not configure with a staged root that does not exist:"
  sed -n '/What went wrong/,/^\* Try/p' "$WORK/jar.log" | head -8; FAIL=1
fi

echo "[test-staged-root] 2/2 NEGATIVE CONTROL: the test task still refuses a root that is not a run/ directory"
# One test, so the refusal is reached in seconds; the refusal comes before the task deletes any earlier results.
gradle test --tests net.forbric.kernel.StagedRootHandoffTest "-Pforbric.stagedRoot=$WORK/nothing" \
    > "$WORK/test.log" 2>&1
if [ $? -ne 0 ] && grep -aq "forbric.stagedRoot must name a run/ directory" "$WORK/test.log"; then
  echo "  PASS the test task refuses $WORK/nothing"
else
  echo "  FAIL the test task ran against a root the tests cannot resolve:"; tail -8 "$WORK/test.log"; FAIL=1
fi

echo
if [ "$FAIL" = "0" ]; then echo "[test-staged-root] ✅ ALL GREEN"; else echo "[test-staged-root] ❌ FAILED"; fi
exit "$FAIL"
