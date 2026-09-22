#!/usr/bin/env bash
# Self-test for MergedLinkChecker's baseline mode, on SYNTHETIC jars — no staged game artifacts needed.
#
# WHY THIS EXISTS. The link check spent its whole life behind `|| echo`, so nothing ever proved it could fail.
# A check nobody can demonstrate failing is indistinguishable from a check that cannot fail. This builds a jar
# with a deliberately dangling field reference and walks the whole state machine:
#
#   no baseline            -> exit 1, the reference is printed
#   --baseline, file absent -> exit 2 and the command that seeds it   (absence is a failure, not a fallback)
#   --write-baseline        -> exit 0, file holds the entry
#   --baseline, unchanged   -> exit 0, "new 0"                        (known does not fail)
#   --baseline, one MORE    -> exit 1, "new 1"                        (THE negative control)
#   --baseline, one REPAIRED-> [FIXED] printed so the file can shrink
#
# Usage: ./test-link-check.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
FAIL=0
WORK="$(mktemp -d "${TMPDIR:-/tmp}/forbric-linkcheck-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

ck() { # <what> <expected-exit> <actual-exit>
  if [ "$2" = "$3" ]; then echo "  PASS $1 (exit $3)"; else echo "  FAIL $1: expected exit $2, got $3"; FAIL=1; fi
}
ck_out() { # <what> <grep-ERE> <file>
  if grep -aqE "$2" "$3"; then echo "  PASS $1"; else echo "  FAIL $1: /$2/ not in $3"; FAIL=1; fi
}
ck_absent() { # <what> <grep-ERE> <file>
  if [ ! -s "$3" ]; then echo "  FAIL $1: $3 is empty, so absence proves nothing"; FAIL=1;
  elif grep -aqE "$2" "$3"; then echo "  FAIL $1: /$2/ IS in $3"; FAIL=1; else echo "  PASS $1"; fi
}

ASM="$(find "$HOME/.gradle/caches" -name 'asm-9*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'asm-tree*' ! -name 'asm-commons*' ! -name 'asm-analysis*' ! -name 'asm-util*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE="$(find "$HOME/.gradle/caches" -name 'asm-tree-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
[ -n "$ASM" ] && [ -n "$ASM_TREE" ] || { echo "ASM jars not found in ~/.gradle/caches (build the loader once)" >&2; exit 3; }
CP="$ASM:$ASM_TREE"

echo "[test-link-check] compiling MergedLinkChecker …"
TOOLS="$WORK/tools"; mkdir -p "$TOOLS"
javac --release 17 -cp "$CP" -d "$TOOLS" "$PROJECT/src/tools/java/net/forbric/tools/MergedLinkChecker.java" || exit 3
run() { java -cp "$TOOLS:$CP" net.forbric.tools.MergedLinkChecker "$@"; }

# --- the fixture: Caller reads Target.gone; Target is then recompiled WITHOUT it. ---
SRC="$WORK/src"; OUT="$WORK/classes"; mkdir -p "$SRC/game" "$OUT"
cat > "$SRC/game/Target.java" <<'EOF'
package game;
public class Target { public static int ok = 1; public static int gone = 2; }
EOF
cat > "$SRC/game/Caller.java" <<'EOF'
package game;
public class Caller { public static int read() { return Target.ok + Target.gone; } }
EOF
cat > "$SRC/game/Caller2.java" <<'EOF'
package game;
public class Caller2 { public static int read() { return Target.ok; } }
EOF
javac --release 17 -d "$OUT" "$SRC/game/Target.java" "$SRC/game/Caller.java" "$SRC/game/Caller2.java" || exit 3
# Now make `gone` vanish from Target only — Caller's GETSTATIC keeps pointing at it. This is exactly the shape
# the byte-merge produces when one side's field loses to the other side's while a surviving method still reads it.
cat > "$SRC/game/Target.java" <<'EOF'
package game;
public class Target { public static int ok = 1; }
EOF
javac --release 17 -d "$OUT" "$SRC/game/Target.java" || exit 3
jar cf "$WORK/merged.jar" -C "$OUT" . || exit 3

BASE="$WORK/baseline.txt"
echo "[test-link-check] 1/6 no baseline -> reports and fails"
run "$WORK/merged.jar" > "$WORK/1.log" 2>&1; ck "bare mode fails on a dangling ref" 1 $?
ck_out "bare mode names the reference" '\[DANGLING\].*game/Target\.gone' "$WORK/1.log"

echo "[test-link-check] 2/6 --baseline with no file -> refuses, and says how to seed"
run --baseline "$BASE" "$WORK/merged.jar" > "$WORK/2.log" 2>&1; ck "missing baseline is a usage failure" 2 $?
ck_out "missing baseline prints the seed command" 'write-baseline' "$WORK/2.log"

echo "[test-link-check] 3/6 --write-baseline seeds it"
run --baseline "$BASE" --write-baseline "$WORK/merged.jar" > "$WORK/3.log" 2>&1; ck "seeding succeeds" 0 $?
if [ "$(grep -acv '^#' "$BASE")" = "1" ]; then echo "  PASS baseline holds exactly the one entry"; else echo "  FAIL baseline entry count: $(grep -acv '^#' "$BASE")"; FAIL=1; fi

echo "[test-link-check] 4/6 known reference does NOT fail"
run --baseline "$BASE" "$WORK/merged.jar" > "$WORK/4.log" 2>&1; ck "a known dangling ref is green" 0 $?
ck_out "known is labelled KNOWN" '\[KNOWN\].*game/Target\.gone' "$WORK/4.log"
ck_out "summary is machine-readable" 'dangling references: 1 \(known 1, new 0\); baseline entries now fixed: 0' "$WORK/4.log"
ck_absent "nothing is labelled NEW" '\[NEW\]' "$WORK/4.log"

echo "[test-link-check] 5/6 NEGATIVE CONTROL: one more dangling ref -> red"
cat > "$SRC/game/Caller2.java" <<'EOF'
package game;
public class Caller2 { public static int read() { return Target.ok + Target.alsoGone; } }
EOF
cat > "$SRC/game/Target.java" <<'EOF'
package game;
public class Target { public static int ok = 1; public static int alsoGone = 3; }
EOF
javac --release 17 -d "$OUT" "$SRC/game/Target.java" "$SRC/game/Caller2.java" || exit 3
cat > "$SRC/game/Target.java" <<'EOF'
package game;
public class Target { public static int ok = 1; }
EOF
javac --release 17 -d "$OUT" "$SRC/game/Target.java" || exit 3
rm -f "$WORK/merged.jar"; jar cf "$WORK/merged.jar" -C "$OUT" . || exit 3
run --baseline "$BASE" "$WORK/merged.jar" > "$WORK/5.log" 2>&1; ck "a NEW dangling ref is red" 1 $?
ck_out "new is labelled NEW" '\[NEW\].*game/Target\.alsoGone' "$WORK/5.log"
ck_out "summary counts it as new" 'dangling references: 2 \(known 1, new 1\)' "$WORK/5.log"

echo "[test-link-check] 6/6 a repaired baseline entry is reported so the file can shrink"
cat > "$SRC/game/Target.java" <<'EOF'
package game;
public class Target { public static int ok = 1; public static int gone = 2; }
EOF
javac --release 17 -d "$OUT" "$SRC/game/Target.java" || exit 3
rm -f "$WORK/merged.jar"; jar cf "$WORK/merged.jar" -C "$OUT" . || exit 3
run --baseline "$BASE" "$WORK/merged.jar" > "$WORK/6.log" 2>&1
ck_out "repaired entry is labelled FIXED" '\[FIXED\].*game/Target\.gone' "$WORK/6.log"
ck_out "summary counts the fix" 'baseline entries now fixed: 1' "$WORK/6.log"
ck_out "and says to prune the file" 'prune .*baseline' "$WORK/6.log"

echo "[test-link-check] packaged baseline and missing-input negative controls"
mkdir -p "$TOOLS/test-baseline"
cp "$BASE" "$TOOLS/test-baseline/known.txt"
run --baseline-resource /test-baseline/known.txt "$WORK/merged.jar" > "$WORK/resource-new.log" 2>&1
ck "packaged baseline rejects the additional defect" 1 $?
ck_out "packaged mode retains the new-reference evidence" '\[NEW\].*game/Target\.alsoGone' "$WORK/resource-new.log"
run --baseline-resource /missing.txt "$WORK/merged.jar" > "$WORK/resource-missing.log" 2>&1
ck "missing packaged baseline fails" 1 $?
ck_out "missing resource is named" 'missing packaged link baseline' "$WORK/resource-missing.log"
mkdir -p "$WORK/empty"
jar cf "$WORK/empty.jar" -C "$WORK/empty" . || exit 3
run --baseline "$BASE" "$WORK/empty.jar" > "$WORK/empty.log" 2>&1
ck "zero scanned classes cannot be green" 2 $?
LINK_BASELINE="$WORK/missing.txt" bash "$HERE/check-merged-links.sh" "$WORK/merged.jar" > "$WORK/gate-baseline.log" 2>&1
ck "integration gate requires a baseline" 2 $?
LINK_BASELINE="$BASE" bash "$HERE/check-merged-links.sh" "$WORK/missing.jar" > "$WORK/gate-artifact.log" 2>&1
ck "integration gate requires artifacts" 2 $?
# Repair both fields: the exact same gate must now succeed, including packaged-baseline mode.
cat > "$SRC/game/Target.java" <<'EOF'
package game;
public class Target { public static int ok = 1; public static int gone = 2; public static int alsoGone = 3; }
EOF
javac --release 17 -d "$OUT" "$SRC/game/Target.java" || exit 3
jar cf "$WORK/repaired.jar" -C "$OUT" . || exit 3
run --baseline-resource /test-baseline/known.txt "$WORK/repaired.jar" > "$WORK/resource-green.log" 2>&1
ck "packaged baseline accepts repaired classes" 0 $?
LINK_BASELINE="$BASE" bash "$HERE/check-merged-links.sh" "$WORK/repaired.jar" > "$WORK/gate-green.log" 2>&1
ck "integration gate really scans the repaired jar" 0 $?
ck_out "integration gate has a nonzero denominator" 'loaded 3 classes \(3 from the merged jar\)' "$WORK/gate-green.log"

echo
if [ "$FAIL" = "0" ]; then echo "[test-link-check] ✅ ALL GREEN"; else echo "[test-link-check] ❌ FAILED"; fi
exit "$FAIL"
