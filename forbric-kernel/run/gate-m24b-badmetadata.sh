#!/usr/bin/env bash
# M24b unreadable-metadata gate — one jar's broken manifest costs that jar, and is named.
#
# WHY THIS EXISTS. EntityCount-multiloader-0.6.1-26.2.jar ships versionRange = "[26.2,26.23" in its
# neoforge.mods.toml. In a 130-jar pack (random100, 2026-09-29) that one string stopped the boot three seconds in,
# with an IllegalArgumentException out of discovery and no mod named: discovery threw out of the whole jar, and
# the Forge-family loop caught only IOException. gate-m24 proves a mod that fails in its ENTRYPOINT stays one
# mod's failure; nothing proved the same for a mod that fails before it has an entrypoint at all.
#
# Staged next to two healthy canaries:
#   forbricbadmetaneo.jar   — its only manifest cannot be read. FML refuses it; Forbric must not load it, must name
#                             it as a mod that did not load, and must let the others load.
#   forbricbadmetamulti.jar — a universal jar: its neoforge.mods.toml cannot be read, its fabric.mod.json can.
#                             Native Fabric loads it, so Forbric must too, as Fabric, with only a note.
# Strict policy must stop the same pack before Done. A final clean control must start under strict.
# GATE-PARALLEL: rundirs=server-badmetadata mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m24b-badmetadata.log"
STRICT="$BUILD/gate-m24b-strict.log"
CONTROL="$BUILD/gate-m24b-control.log"
RUNDIR="$KERNEL/run/server-badmetadata"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
COMPAT="$RUNDIR/.forbric-kernel/compatibility-report.json"
BADNEO="$KERNEL/run/canary/forbricbadmetaneo.jar"
BADMULTI="$KERNEL/run/canary/forbricbadmetamulti.jar"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
mkdir -p "$BUILD"

step "stage two jars with an unreadable manifest and two healthy mods"
"$KERNEL/run/build-broken-metadata-canary.sh" >"$BUILD/gate-m24b-canary.log" 2>&1
"$KERNEL/run/build-fabric-canary.sh" >>"$BUILD/gate-m24b-canary.log" 2>&1
for jar in "$BADNEO" "$BADMULTI" "$FABRIC" "$NEO"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m24b-canary.log)"; exit 1; }
done

stage() { # stage <include-broken>
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
  mkdir -p "$RUNDIR/mods"
  cp "$FABRIC" "$NEO" "$RUNDIR/mods/"
  [ "$1" = "yes" ] && cp "$BADNEO" "$BADMULTI" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"
}

boot() { # boot <log> <policy> [expect-policy-stop]
  local log="$1" policy="$2" mode="${3:-normal}"
  : > "$log"
  rm -f "$log.exit"
  (
    launch() {
      RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY="$policy" \
        FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.compatibilityPolicy=$policy" \
        "$KERNEL/run/launch-kernel-server.sh"
    }
    if [ "$mode" = "expect-policy-stop" ]; then
      launch </dev/null
    else
      ( stop_when_ready "$log" 30 ) | launch
    fi
    code=$?
    printf '%s\n' "$code" > "$log.exit"
    exit "$code"
  ) > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

findings() { # the broken NeoForge-only jar is the one confirmed, required loss; the universal one is a note
  if python3 - "$COMPAT" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
required = [f for f in report["findings"] if f["confidence"] == "CONFIRMED" and f["required"]]
assert report["confirmedRequired"] == 1 and len(required) == 1, required
finding = required[0]
assert finding["modId"] == "forbricbadmetaneo", finding
assert finding["id"] == "metadata:neoforge", finding
assert "jar=forbricbadmetaneo.jar" in finding["evidence"], finding
assert "[26.2,26.23" in finding["detail"], finding
notes = [f for f in report["findings"] if f["modId"] == "forbricbadmetamulti"]
assert len(notes) == 1 and notes[0]["confidence"] == "SUSPECTED" and not notes[0]["required"], notes
assert "loads as FABRIC" in notes[0]["detail"], notes
PY
  then
    echo "[kernel] PASS the unreadable jar is the one CONFIRMED, required loss; the universal jar is only a note"
  else
    echo "[kernel] FAIL missing or misclassified metadata findings"; FAIL=1
  fi
}

stage yes
step "explicit continue with two unreadable manifests in mods/"
boot "$LOG" continue
check "explicit continue exited normally" "^0$" "$LOG.exit"
check_absent "discovery did not throw out of the boot" "Exception in thread \"main\"" "$LOG"

step "each unreadable manifest is named once, with whose range it is"
check "the NeoForge-only jar's manifest is named" \
  "could not read META-INF/neoforge.mods.toml in forbricbadmetaneo.jar: dependency neoforge of forbricbadmetaneo: unbalanced version range" "$LOG"
check "the universal jar's manifest is named" \
  "could not read META-INF/neoforge.mods.toml in forbricbadmetamulti.jar" "$LOG"
check "the universal jar is loaded by the family that can read it" \
  "forbricbadmetamulti.jar: the \[NEOFORGE\] manifest cannot be read — choosing among \[FABRIC\]" "$LOG"

step "the other mods loaded anyway, and the server still works"
check "the Fabric canary still initialised"   "\[ForbricFabricLive\] onInitialize"  "$LOG"
check "the NeoForge canary still constructed" "\[ForbricNeoLive\]"                  "$LOG"
check "server reached Done"                   "Done \("                             "$LOG"
check "server ticked (the stop command ran)"  "Stopping the server|commands\.stop\.stopping" "$LOG"
check_absent "no crash report was written"    "Preparing crash report"              "$LOG"

step "and the loss is reported as a mod that did not load"
check "the load summary names the unreadable jar's mod" \
  "Forbric/Load\] 1 mod\(s\) did not finish loading: forbricbadmetaneo" "$LOG"
check_absent "and not as a problem that belongs to no mod" "belong to no installed mod" "$LOG"
if [ -f "$REPORT" ]; then
  check "the report names the jar" "forbricbadmetaneo.jar|forbricbadmetaneo" "$REPORT"
  check "the report quotes the broken range" "\[26\.2,26\.23" "$REPORT"
else
  echo "[kernel] FAIL no load report at $REPORT"; FAIL=1
fi
[ -f "$COMPAT" ] && findings || { echo "[kernel] FAIL no compatibility report at $COMPAT"; FAIL=1; }
[ ! -f "$COMPAT" ] || cp "$COMPAT" "$BUILD/gate-m24b-continue-compatibility.json"

step "strict negative control: the same pack stops before a usable world"
stage yes
boot "$STRICT" strict expect-policy-stop
check "strict exited with the policy stop code" "^78$" "$STRICT.exit"
check "strict made an explicit compatibility decision" "Forbric/Compatibility\] launch stopped:" "$STRICT"
check_absent "strict did not expose a running server" "Done \(" "$STRICT"
check_absent "strict did not crash instead" "Exception in thread \"main\"" "$STRICT"
[ -f "$COMPAT" ] && findings || { echo "[kernel] FAIL no compatibility report at $COMPAT"; FAIL=1; }

step "negative control: the same instance without the unreadable jars"
stage no
boot "$CONTROL" strict
check "healthy strict control exited normally" "^0$" "$CONTROL.exit"
check "the control booted" "Done \(" "$CONTROL"
check_absent "nothing was reported as unreadable" "could not read META-INF" "$CONTROL"

step "M24b result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M24b UNREADABLE-METADATA GATE GREEN — one jar's manifest costs that jar, is named, and strict stops"
else
  echo "[kernel] ❌ M24b GATE RED — continue $LOG / strict $STRICT / healthy $CONTROL"
fi
exit "$FAIL"
