#!/usr/bin/env bash
# M38: actual command, loot and table decisions through the unmodified Fabric item module.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
export KERNEL BUILD OLD
if [ "${1:-}" = "--execute" ]; then
 set +e
 "$KERNEL/run/launch-kernel-server.sh" </dev/null
 code=$?
 cp "$RUNDIR/.forbric-kernel/compatibility-report.json" "$M38_RESULT_COMPATIBILITY"
 [ "$code" -eq 0 ] || exit "$code"
 python3 - "$RUNDIR/probe.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]));assert p['pass'] is True,p
PY
 exit "$?"
fi
kernel_jar
bash "$KERNEL/run/build-enchantment-contracts-canary.sh"
python3 - <<'PY'
import json, os, pathlib, shutil, subprocess, uuid, signal
kernel=pathlib.Path(os.environ['KERNEL']);root=kernel.parent;results=kernel/'build/verification/m38-entity';results.mkdir(parents=True,exist_ok=True)
inputs=json.loads((kernel/'run/canary/m38-build-inputs.json').read_text())
for phase in ('positive','off'):
 nonce=str(uuid.uuid4());run=kernel/'build/enchantment-runs'/nonce;(run/'mods').mkdir(parents=True)
 (run/'.m38-owned').write_text(nonce);(run/'eula.txt').write_text('eula=true\n')
 (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25598\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\nlevel-seed=8035262\nmax-tick-time=-1\npause-when-empty-seconds=0\nview-distance=2\nsimulation-distance=2\n')
 for item in [inputs['mod'],*inputs['modules']]:source=pathlib.Path(item['path']);shutil.copy2(source,run/'mods'/source.name)
 env=os.environ.copy();env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='strict' if phase=='positive' else 'continue',M38_RESULT_COMPATIBILITY=str(results/(phase+'-compatibility.json')),
   FORBRIC_JVM=f'-Dforbric.fabricItemContracts={"on" if phase=="positive" else "off"} -Dforbric.enchantPhase={phase} -Dforbric.enchantNonce={nonce} -Dforbric.enchantRoot={run}')
 for key,role in [('MERGED','merged'),('FORGE_RT','forge'),('NEO_RT','neo')]:env[key]=inputs[role]['path']
 command=['python3',str(kernel/'run/compat/evidence.py'),'run','--source',str(root),'--mods',str(run/'mods'),'--output',str(results/(phase+'.json'))]
 for role,path in [('merged',env['MERGED']),('forge-interop',env['FORGE_RT']),('neo-runtime',env['NEO_RT']),('fabric-api',inputs['fabricApi']['path']),('kernel',str(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')),('kernel-runtime',str(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar'))]:command+=['--artifact',role+'='+path]
 command+=['--','bash',str(kernel/'run/gate-m38-enchantment-contracts.sh'),'--execute']
 with (results/(phase+'-driver.log')).open('w') as output:
  process=subprocess.Popen(command,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=process.wait(timeout=180)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
   raise RuntimeError('M38 owned process group timed out')
 assert code==(0 if phase=='positive' else 1),(phase,'unexpected command result',code)
 proof=json.loads((run/'probe.json').read_text());shutil.copy2(run/'probe.json',results/(phase+'-probe.json'))
 assert proof['nonce']==nonce and proof['phase']==phase and len(proof['cases'])==16,proof
 assert all(c['pass'] is (phase=='positive') for c in proof['cases']),proof
 outcome=json.loads((results/(phase+'.result.json')).read_text());assert outcome['inputsUnchanged'] is True,outcome
 report=json.loads((results/(phase+'-compatibility.json')).read_text())
 if phase=='positive':assert report['confirmedRequired']==0,report
 else:assert report['confirmedRequired']>0,report
 text=(results/(phase+'.log')).read_text();assert 'Done (' in text and 'Preparing crash report' not in text and 'All dimensions are saved' in text
 print('[M38] PASS',phase,'sixteen actual enchantment cases with expected verdicts',flush=True)
print('[M38] GATE GREEN')
PY
