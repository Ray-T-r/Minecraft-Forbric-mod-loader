#!/usr/bin/env bash
# M36: real Mixin silently skipped injection and safe late dedicated-server decisions.
# GATE-PARALLEL: rundirs=server-mixin-outcome-m36 mem=2000
set -euo pipefail
GATE_PORT="${GATE_PORT:-25596}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
export KERNEL BUILD OLD GATE_PORT
kernel_jar
bash "$KERNEL/run/build-mixin-outcome-canary.sh"
python3 - <<'PY' || { echo "[kernel] FAIL M36 — the driver stopped on the assertion above"; exit 1; }
import json, os, pathlib, shutil, subprocess, time, uuid, signal
kernel=pathlib.Path(os.environ['KERNEL']);root=kernel.parent
run=kernel/'run/server-mixin-outcome-m36';results=kernel/'build/verification/m36-outcome';results.mkdir(parents=True,exist_ok=True)
inputs=json.loads((kernel/'run/canary/m36-build-inputs.json').read_text());port=os.environ['GATE_PORT']
for phase,mode,policy,required in [('required-strict','required','strict',True),('required-continue','required','continue',True),('optional','optional','strict',False),('declined','declined','strict',False),('widened','widened','strict',False),('widened-off','widened','strict',True)]:
 if run.exists():
  if not (run/'.m36-owned').is_file():raise RuntimeError('refusing to delete unowned M36 instance')
  shutil.rmtree(run)
 (run/'mods').mkdir(parents=True);(run/'.m36-owned').write_text(str(uuid.uuid4()))
 shutil.copy2(kernel/'run/canary/forbricoutcome.jar',run/'mods/forbricoutcome.jar')
 (run/'eula.txt').write_text('eula=true\n')
 (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\nmax-tick-time=-1\npause-when-empty-seconds=0\nview-distance=2\nsimulation-distance=2\n')
 env=os.environ.copy();env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY=policy,FORBRIC_JVM=os.environ.get('FORBRIC_JVM','')+f' -Dforbric.outcomeMode={mode} -Dforbric.mixinAtWiden=' + ('off' if phase=='widened-off' else 'on'))
 # The game base (NeoForge's patched game) and its one carrier the canary was built and recorded against, handed to the
 # launcher as BASE and NEO_RT and attested under the neo-patched / neo-runtime roles. (Stage 4: no merged base, no
 # MinecraftForge carrier.)
 for key,role in [('BASE','base'),('NEO_RT','neo')]:env[key]=inputs[role]['path']
 command=['python3',str(kernel/'run/compat/evidence.py'),'run','--source',str(root),'--mods',str(run/'mods'),'--output',str(results/(phase+'.json'))]
 for role,path in [('neo-patched',env['BASE']),('neo-runtime',env['NEO_RT']),('kernel',str(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')),('kernel-runtime',str(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar'))]:command+=['--artifact',role+'='+path]
 command+=['--','bash',str(kernel/'run/launch-kernel-server.sh')]
 (results/(phase+'.log')).unlink(missing_ok=True)  # the lost-port check below must not read an earlier run's log
 with (results/(phase+'-driver.log')).open('w') as output:
  process=subprocess.Popen(command,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=process.wait(timeout=180)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
   raise RuntimeError('M36 owned process group timed out')
 log=results/(phase+'.log')
 # A lost port fails everything below for a reason that is not the kernel's (see port_was_free in lib.sh).
 assert not (log.is_file() and 'FAILED TO BIND TO PORT' in log.read_text(errors='replace')),(phase,f'the server never got port {port}: another process holds it')
 assert code==0,(phase,'server/evidence failed; inspect driver log')
 text=(results/(phase+'.log')).read_text();report=json.loads((run/'.forbric-kernel/compatibility-report.json').read_text())
 shutil.copy2(run/'.forbric-kernel/compatibility-report.json',results/(phase+'-compatibility.json'))
 assert 'Done (' in text and '[M36Outcome] first live tick complete' in text,(phase,'target was not reached in the real running server')
 losses=[f for f in report['findings'] if f['id'].startswith('mixin-injector:') and f['modId']=='forbricoutcome' and f['confidence']=='CONFIRMED' and f['required']]
 assert bool(losses)==required,(phase,report)
 strict=required and policy=='strict'
 assert ('reached third tick' in text) is (not strict),(phase,'late policy did not take effect at the next boundary')
 assert ('completed-tick boundary' in text) is strict,(phase,'safe halt evidence differs')
 assert 'All dimensions are saved' in text and 'Preparing crash report' not in text,(phase,'not a normal saved shutdown')
 if required:assert len(losses)==1 and ('change' if mode=='widened' else 'missing') in losses[0]['id'],losses
 if mode=='widened':assert ('value=changed|context' if phase=='widened' else 'value=initial|context') in text,(phase,'argument result did not match')
 assert ('required present handler ran' in text)==(mode=='required')
 assert ('optional present handler ran' in text)==(mode=='optional')
 print('[kernel] PASS M36',phase,'real defaultRequire result and completed-tick policy',flush=True)
print('[kernel] M36 GATE GREEN')
PY
