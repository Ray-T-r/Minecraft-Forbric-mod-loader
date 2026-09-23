#!/usr/bin/env bash
# M39: the transfer engine suite (required), then real Forge objects, shared rollback graphs, metadata and actual
# final watchdog replacement proof.
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
# The transfer engine suite: the only coverage of provider/notification/rollback failures, native Fabric<->NeoForge
# nested rollback, repeated simulation and re-entry across the two real transaction engines. It is required here:
# -Pforbric.requireTransfer makes an absent game side fail instead of skip, and the verdict comes from the XML, which
# must hold every @Test declared under src/transferTest, none failed, errored or skipped.
# TRANSFER_SUITE_BEGIN
TRANSFER_LOG="$BUILD/gate-m39-transfer-test.log"
transfer_rc=0
"$KERNEL/gradlew" --offline -p "$KERNEL" -Pforbric.requireTransfer=true cleanTransferTest transferTest >"$TRANSFER_LOG" 2>&1 || transfer_rc=$?
if python3 - "$KERNEL" "$transfer_rc" <<'PY'
import glob, os, re, sys, xml.etree.ElementTree as ET
kernel, rc = sys.argv[1], int(sys.argv[2])
declared = set()
for source in glob.glob(os.path.join(kernel, 'src/transferTest/java/**/*.java'), recursive=True):
    with open(source, encoding='utf-8') as stream: declared |= set(re.findall(r'@Test\s+void\s+(\w+)\s*\(', stream.read()))
ran, bad = set(), []
reports = glob.glob(os.path.join(kernel, 'build/test-results/transferTest/*.xml'))
for report in reports:
    for case in ET.parse(report).getroot().iter('testcase'):
        name = case.get('name', '').removesuffix('()'); ran.add(name)
        bad += [kind + ' ' + name for kind in ('failure', 'error', 'skipped') if case.find(kind) is not None]
problems = ([f'gradle exited {rc}'] if rc else []) + ([] if reports else ['no transferTest report'])
problems += bad + [f'declared but not run: {name}' for name in sorted(declared - ran)]
if not declared: problems.append('no @Test declared under src/transferTest')
print(f'[M39] transfer engine suite: {len(ran)} ran of {len(declared)} declared' + ('' if not problems else '; ' + '; '.join(problems)))
sys.exit(1 if problems else 0)
PY
then echo "[kernel] PASS transfer engine suite ran every declared test (see $TRANSFER_LOG)"
else echo "[kernel] FAIL transfer engine suite — see $TRANSFER_LOG"; FAIL=1
fi
# TRANSFER_SUITE_END
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
