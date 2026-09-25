#!/usr/bin/env bash
# M37: actual effect, gliding and sleep callbacks through the unmodified upstream Fabric entity module.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
export KERNEL BUILD OLD
if [ "${1:-}" = "--execute" ]; then
 set +e
 "$KERNEL/run/launch-kernel-server.sh" </dev/null
 code=$?
 cp "$RUNDIR/.forbric-kernel/compatibility-report.json" "$M37_RESULT_COMPATIBILITY"
 [ "$code" -eq 0 ] || exit "$code"
 python3 - "$RUNDIR/probe.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]));assert p['pass'] is True,p
PY
 exit "$?"
fi
kernel_jar
bash "$KERNEL/run/build-entity-callbacks-canary.sh"
python3 - <<'PY'
import json, os, pathlib, shutil, subprocess, uuid, signal
kernel=pathlib.Path(os.environ['KERNEL']);root=kernel.parent;results=kernel/'build/verification/m37-entity';results.mkdir(parents=True,exist_ok=True)
inputs=json.loads((kernel/'run/canary/m37-build-inputs.json').read_text())
EXPECT_FAIL={'positive':set(),'tick-off':{'glide-tick'}}
ALL={'effect-add','effect-remove','effect-clear-veto','glide-deny','glide-custom','glide-boolean','glide-tick','glide-tick-native','bed-native','bed-handled','bed-nonbed','bed-custom-native','bed-custom-handled','direction-bed','direction-nonbed','nearby-monsters'}
# Fabric's flight tick anchors natively on the glider-slot choice, which a real elytra reaches: that control holds off.
EXPECT_FAIL['off']=ALL-{'glide-tick-native'}
for phase in ('positive','off','tick-off'):
 nonce=str(uuid.uuid4());run=kernel/'build/entity-callback-runs'/nonce;(run/'mods').mkdir(parents=True)
 (run/'.m37-owned').write_text(nonce);(run/'eula.txt').write_text('eula=true\n')
 (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25597\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\nlevel-seed=8035262\nmax-tick-time=-1\npause-when-empty-seconds=0\nview-distance=2\nsimulation-distance=2\n')
 for item in [inputs['mod'],*inputs['modules']]:source=pathlib.Path(item['path']);shutil.copy2(source,run/'mods'/source.name)
 env=os.environ.copy();env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='continue' if phase=='off' else 'strict',M37_RESULT_COMPATIBILITY=str(results/(phase+'-compatibility.json')),
   FORBRIC_JVM=f'-Dforbric.fabricEntityAnchors={"off" if phase=="off" else "on"} -Dforbric.entityPhase={phase} -Dforbric.entityNonce={nonce} -Dforbric.entityRoot={run}'
   # The generic renamed-body retarget (MixinRetarget R3) also moves the sleep redirect into NeoForge's lambda, and the
   # carrier-stub rebind moves fabric-api's boolean elytra check off LivingEntity's delegating canGlide()Z stub onto
   # the body, so the negative control turns both off too: every repaired case must show it depends on a repair.
   +(' -Dforbric.mixinRetarget=off -Dforbric.mixinStubRebind=off' if phase=='off' else '')
   +(' -Dforbric.fabricElytraTickAnchor=off' if phase=='tick-off' else ''))
 for key,role in [('MERGED','merged'),('FORGE_RT','forge'),('NEO_RT','neo')]:env[key]=inputs[role]['path']
 command=['python3',str(kernel/'run/compat/evidence.py'),'run','--source',str(root),'--mods',str(run/'mods'),'--output',str(results/(phase+'.json'))]
 for role,path in [('merged',env['MERGED']),('forge-interop',env['FORGE_RT']),('neo-runtime',env['NEO_RT']),('fabric-api',inputs['fabricApi']['path']),('kernel',str(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')),('kernel-runtime',str(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar'))]:command+=['--artifact',role+'='+path]
 command+=['--','bash',str(kernel/'run/gate-m37-entity-callbacks.sh'),'--execute']
 with (results/(phase+'-driver.log')).open('w') as output:
  process=subprocess.Popen(command,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=process.wait(timeout=180)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
   raise RuntimeError('M37 owned process group timed out')
 assert code==(0 if phase=='positive' else 1),(phase,'unexpected command result',code)
 proof=json.loads((run/'probe.json').read_text());shutil.copy2(run/'probe.json',results/(phase+'-probe.json'))
 assert proof['nonce']==nonce and proof['phase']==phase and len(proof['cases'])==16,proof
 failed={c['name'] for c in proof['cases'] if not c['pass']}
 assert failed==EXPECT_FAIL[phase],(phase,sorted(failed))
 outcome=json.loads((results/(phase+'.result.json')).read_text());assert outcome['inputsUnchanged'] is True,outcome
 report=json.loads((results/(phase+'-compatibility.json')).read_text())
 if phase=='off':assert report['confirmedRequired']>0,report
 else:assert report['confirmedRequired']==0,report
 text=(results/(phase+'.log')).read_text();assert 'Done (' in text and 'Preparing crash report' not in text and 'All dimensions are saved' in text
 elytra='entity callback anchor(s) in net.fabricmc.fabric.mixin.entity.event.elytra.LivingEntityMixin'
 tick="fabric-api's elytra flight tick now runs before NeoForge's empty-glider guard"
 if phase=='positive':assert 'restored 2 '+elytra in text and tick in text,'positive: the gliding decision and the flight tick both restored'
 if phase=='tick-off':assert 'restored 1 '+elytra in text and tick not in text,'tick-off: only the gliding decision restored'
 print('[M37] PASS',phase,'sixteen actual entity callback cases with expected verdicts',flush=True)
print('[M37] GATE GREEN')
PY
