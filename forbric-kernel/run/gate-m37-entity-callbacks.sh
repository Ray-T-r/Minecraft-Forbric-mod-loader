#!/usr/bin/env bash
# M37: actual effect, gliding and sleep callbacks through the unmodified upstream Fabric entity module.
#
# Dropped case (stated, not hidden): a NeoForge mod's own bed that overrides setBedOccupied with its own property and no
# vanilla setBlock/OCCUPIED write (bed-custom-native, bed-custom-handled) no longer asks Fabric's SET_BED_OCCUPATION_STATE.
# No native Fabric game contains such a bed, so there is no native injector to reproduce there; the only way to ask the
# event for it would be to write fabric-api's handler contract for it (event, then the property check), which is reading
# one mod, not a structure. Fabric's occupancy redirect moves by CarrierDefaultChains onto the state's setBedOccupied and runs
# NeoForge's inherited default with fabric-api's own handler at the vanilla write: every bed that inherits that default
# (vanilla's beds, the stone spot ALLOW_BED turns into a bed, a mod's own BedBlock — bed-inherit-*) is exact; a bed that
# overrides it keeps its own setter, untouched, and those two cases now assert exactly that.
set -euo pipefail
GATE_PORT="${GATE_PORT:-25597}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
export KERNEL BUILD OLD GATE_PORT
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
inputs=json.loads((kernel/'run/canary/m37-build-inputs.json').read_text());port=os.environ['GATE_PORT']
# guard-off: CarrierGuardedPoints alone switched off. Fabric's elytra check then stays behind NeoForge's gliding guard (the
# carrier-stub rebind still moves it into canGlide(Z)Z, so its boolean case holds), where a Fabric veto or custom flight no
# longer decides, and the flight tick stays behind the empty-glider guard: exactly those three fail.
EXPECT_FAIL={'positive':set(),'guard-off':{'glide-deny','glide-custom','glide-tick'}}
ALL={'effect-add','effect-remove','effect-clear-veto','glide-deny','glide-custom','glide-boolean','glide-tick','glide-tick-native','bed-native','bed-handled','bed-nonbed','bed-custom-native','bed-custom-handled','bed-inherit-native','bed-inherit-handled','direction-bed','direction-nonbed','nearby-monsters','direction-veto'}
# Fabric's flight tick anchors natively on the glider-slot choice, which a real elytra reaches: that control holds off. The
# NeoForge-only bed's two cases assert that Fabric is not asked and its own setter runs, which holds off too.
EXPECT_FAIL['off']=ALL-{'glide-tick-native','bed-custom-native','bed-custom-handled'}
for phase in ('positive','off','guard-off'):
 nonce=str(uuid.uuid4());run=kernel/'build/entity-callback-runs'/nonce;(run/'mods').mkdir(parents=True)
 (run/'.m37-owned').write_text(nonce);(run/'eula.txt').write_text('eula=true\n')
 (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\nlevel-seed=8035262\nmax-tick-time=-1\npause-when-empty-seconds=0\nview-distance=2\nsimulation-distance=2\n')
 for item in [inputs['mod'],*inputs['modules']]:source=pathlib.Path(item['path']);shutil.copy2(source,run/'mods'/source.name)
 env=os.environ.copy();env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='continue' if phase=='off' else 'strict',M37_RESULT_COMPATIBILITY=str(results/(phase+'-compatibility.json')),
   FORBRIC_JVM=f'-Dforbric.entityPhase={phase} -Dforbric.entityNonce={nonce} -Dforbric.entityRoot={run}'
   # Every case is repaired by mechanisms that know no mod, each with its own switch, so the negative control turns every
   # one of them off: CarrierDefaultChains moves the bed occupancy redirect onto the state's setBedOccupied; the generic
   # renamed-body retarget (MixinRetarget R3) moves the sleep redirect into NeoForge's lambda; the carrier-stub rebind
   # moves fabric-api's boolean elytra check off LivingEntity's delegating canGlide()Z stub onto the body; the scoped-clear
   # relay puts the native activeEffects.clear() back into NeoForge's removeAllEffects so fabric-api's own wrap lands
   # there; CarrierDecisions moves the effect injectors onto the carrier's applicability question and its copy; and
   # CarrierGuardedPoints moves the elytra check and flight tick ahead of NeoForge's gliding guards; CarrierReturnedCalls moves
   # the sleeping direction wrap to NeoForge's answer.
   +(' -Dforbric.carrierDefaultChains=off -Dforbric.mixinRetarget=off -Dforbric.mixinStubRebind=off -Dforbric.scopedClearRelay=off -Dforbric.carrierDecisions=off -Dforbric.carrierGuardedPoints=off -Dforbric.carrierReturnedCalls=off' if phase=='off' else '')
   +(' -Dforbric.carrierGuardedPoints=off' if phase=='guard-off' else ''))
 # The canary builder records "forge": null when no MinecraftForge carrier was staged: launch with the NeoForge carrier
 # alone (launch-kernel-server.sh finds none at the same staged paths) and attest no forge-interop jar.
 for key,role in [('MERGED','merged'),('FORGE_RT','forge'),('NEO_RT','neo')]:
  if inputs.get(role) is None:env.pop(key,None)
  else:env[key]=inputs[role]['path']
 command=['python3',str(kernel/'run/compat/evidence.py'),'run','--source',str(root),'--mods',str(run/'mods'),'--output',str(results/(phase+'.json'))]
 for role,path in [('merged',env['MERGED']),*([('forge-interop',env['FORGE_RT'])] if 'FORGE_RT' in env else []),('neo-runtime',env['NEO_RT']),('fabric-api',inputs['fabricApi']['path']),('kernel',str(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')),('kernel-runtime',str(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar'))]:command+=['--artifact',role+'='+path]
 command+=['--','bash',str(kernel/'run/gate-m37-entity-callbacks.sh'),'--execute']
 (results/(phase+'.log')).unlink(missing_ok=True)  # the lost-port check below must not read an earlier run's log
 with (results/(phase+'-driver.log')).open('w') as output:
  process=subprocess.Popen(command,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=process.wait(timeout=180)
  except subprocess.TimeoutExpired:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
   raise RuntimeError('M37 owned process group timed out')
 log=results/(phase+'.log')
 # A lost port fails everything below for a reason that is not the kernel's (see port_was_free in lib.sh).
 assert not (log.is_file() and 'FAILED TO BIND TO PORT' in log.read_text(errors='replace')),(phase,f'the server never got port {port}: another process holds it')
 assert code==(0 if phase=='positive' else 1),(phase,'unexpected command result',code)
 proof=json.loads((run/'probe.json').read_text());shutil.copy2(run/'probe.json',results/(phase+'-probe.json'))
 assert proof['nonce']==nonce and proof['phase']==phase and len(proof['cases'])==19,proof
 failed={c['name'] for c in proof['cases'] if not c['pass']}
 assert failed==EXPECT_FAIL[phase],(phase,sorted(failed))
 outcome=json.loads((results/(phase+'.result.json')).read_text());assert outcome['inputsUnchanged'] is True,outcome
 report=json.loads((results/(phase+'-compatibility.json')).read_text())
 if phase=='off':assert report['confirmedRequired']>0,report
 else:assert report['confirmedRequired']==0,report
 text=(results/(phase+'.log')).read_text();assert 'Done (' in text and 'Preparing crash report' not in text and 'All dimensions are saved' in text
 elytra='[Forbric/Mixin] net.fabricmc.fabric.mixin.entity.event.elytra.LivingEntityMixin.'
 moved=[elytra+handler+': the carrier put' in text for handler in ('injectElytraCheck','injectElytraTick')]
 assert moved==[phase=='positive']*2,(phase,'the gliding decision and the flight tick are moved ahead of the guards in the positive phase only',moved)
 effect='[Forbric/Mixin] net.fabricmc.fabric.mixin.entity.event.effect.LivingEntityMixin.'
 decided=[any(effect+handler+': ' in line and ' is no longer called in ' in line for line in text.splitlines()) for handler in ('beforeForceAddEffect','beforeRemoveAllEffects')]
 assert decided==[phase!='off']*2,(phase,'the effect injectors are moved onto the carrier\'s decisions in every phase but the negative control',decided)
 beds=('[Forbric/Mixin] net.fabricmc.fabric.mixin.entity.event.LivingEntityMixin.setOccupiedState: net.minecraft.world.level.Level.setBlock is no longer called in '
   'net.minecraft.world.entity.LivingEntity.lambda$stopSleeping$0, startSleeping; the carrier calls net.minecraft.world.level.block.state.BlockState.setBedOccupied there')
 assert (beds in text)==(phase!='off'),(phase,'the bed occupancy redirect moves onto the state\'s setBedOccupied in every phase but the negative control')
 assert 'restored 1 entity callback anchor(s)' not in text,(phase,'the retired name-keyed bed rewrite ran')
 direction='[Forbric/Mixin] net.fabricmc.fabric.mixin.entity.event.LivingEntityMixin.onGetSleepingDirection: net.minecraft.world.level.block.BedBlock.getBedOrientation is no longer called'
 assert (direction in text)==(phase!='off'),(phase,'the sleeping direction wrap moves to the answer in every phase but the negative control')
 relay='[Forbric/ScopedClear] net.minecraft.world.entity.LivingEntity: [removeAllEffects()Z(activeEffects)]'
 assert (relay in text)==(phase!='off'),(phase,'the scoped-clear relay runs in every phase but the negative control')
 print('[M37] PASS',phase,'nineteen actual entity callback cases with expected verdicts',flush=True)
print('[M37] GATE GREEN')
PY
