#!/usr/bin/env bash
# M39: real Forge objects, shared rollback graphs, metadata and actual final watchdog replacement proof.
# GATE-PARALLEL: mem=2000
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
MERGED="${MERGED:-$RUN_OLD/merged-base/patched-mc-merged-26.2.jar}"
FORGE_RT="${FORGE_RT:-$RUN_OLD/merged-base/forge-runtime-interop.jar}"
NEO_RT="${NEO_RT:-$RUN_OLD/neoforge-runtime/neoforge-runtime.jar}"
export MERGED FORGE_RT NEO_RT
if [ "${1:-}" = --execute ]; then
  RUNDIR="$2"
  (sleep 35; echo stop) | RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY=strict "$KERNEL/run/launch-kernel-server.sh"
  exit "$?"
fi
kernel_jar
"$KERNEL/gradlew" --offline -q -p "$KERNEL" compileTransferTestJava
BASE="$BUILD/verification/m39-transfer-core"
mkdir -p "$BASE"
RUNDIR="$(mktemp -d "$BASE/run-XXXXXX")"
mkdir -p "$RUNDIR/mods"
python3 - "$KERNEL" "$RUNDIR" <<'PY'
import pathlib,sys,zipfile,shutil
kernel,run=map(pathlib.Path,sys.argv[1:]);classes=kernel/'build/classes/java/transferTest'
files=sorted(p for p in (classes/'net/forbric/kernel/transfer').glob('*.class') if p.name.startswith(('ForgeTransferCanary','ForgeTransferGameScenarios')))
assert any(p.name=='ForgeTransferCanary.class' for p in files) and any(p.name=='ForgeTransferGameScenarios.class' for p in files)
with zipfile.ZipFile(run/'mods/forbric-transfer-core.jar','w') as output:
 for file in files:output.write(file,file.relative_to(classes).as_posix())
 output.write(kernel/'src/transferTest/resources/forge-transfer-canary.fabric.mod.json','fabric.mod.json')
shutil.copy2(kernel/'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar',run/'mods')
PY
seed_server_properties "$RUNDIR"
printf '\nlevel-type=minecraft:flat\ngenerate-structures=false\n' >> "$RUNDIR/server.properties"
python3 "$KERNEL/run/compat/evidence.py" run --source "$KERNEL/.." \
  --artifact "merged=$MERGED" --artifact "forge-interop=$FORGE_RT" --artifact "neo-runtime=$NEO_RT" \
  --artifact "kernel=$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" \
  --artifact "kernel-runtime=$BUILD/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar" \
  --mods "$RUNDIR/mods" --output "$RUNDIR/inputs.json" \
  -- bash "$KERNEL/run/gate-m39-transfer-core.sh" --execute "$RUNDIR"
LOG="$RUNDIR/inputs.log"
check "all thirteen storage scenarios and the native diagnostic proof ran" 'TransferCanary\] 14/14 passed' "$LOG"
check_absent "no real carrier scenario failed" 'TransferCanary\] FAIL' "$LOG"
check "the actual server ticked and accepted stop" 'Stopping the server|commands\.stop\.stopping' "$LOG"
check "the actual world was saved" 'All dimensions are saved' "$LOG"
python3 - "$RUNDIR" <<'PY'
import json,pathlib,sys
run=pathlib.Path(sys.argv[1]);evidence=json.loads((run/'inputs.result.json').read_text());report=json.loads((run/'.forbric-kernel/compatibility-report.json').read_text())
assert evidence['inputsUnchanged'] and evidence['commandPassed'],evidence
assert report['policy']=='STRICT' and report['confirmedRequired']==0,report
assert any('ServerWatchdogMixin#printEntireThreadDump' in f['id'] and f['confidence']=='RESOLVED' for f in report['findings']),report
print('[M39] final defined watchdog renderer is proven equivalent; strict report has zero required losses')
PY
if [ "$FAIL" -eq 0 ]; then echo '[kernel] ✅ M39 CORE TRANSFER AND DIAGNOSTIC GATE GREEN'; fi
exit "$FAIL"
