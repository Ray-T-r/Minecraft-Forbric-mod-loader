#!/usr/bin/env python3
"""Reproduce the observed Unlit Campfire cache root on native NeoForge and Forbric.
Does not patch, clear or weakly replace the mod's cache. A reproduction is evidence of a native mod issue,
not a blanket waiver for other roots or permission to call an unreviewed soak a pass.

A release m34 soak runs this itself when build/retention-control/comparison.json is absent or no longer describes the
files on disk and the jars the soak launches (soak-run.py ensure_native_evidence). Each arm's inputs.json is what that
freshness is read from, so it records every file the arm was measured on that this script can name: its mods, what
booted them, the sources (the canary, this script, native-controls.py that builds both commands, the launcher the
Forbric arm runs with the two scripts it calls, and build.gradle, whose pins decide the Forbric arm's boot classpath)
and, for the native arm, the installed image's installation.json. Not recorded, because the launcher resolves them
itself: the Forbric arm's boot classpath jars and its vanilla reference (both derive from recorded files: build.gradle
and the game base).

Nothing runs at import: the pieces a test can hold (what is missing, what an arm records) are functions, and main()
is the measurement.
"""
import importlib.util,json,os,shutil,signal,subprocess,sys,time,uuid,zipfile
from pathlib import Path
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('controls',Path(__file__).with_name('native-controls.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
MOD='run/client-merged-pack/mods/unlitcampfire-neoforge-26.2-4.1.0.0.jar'
# What decides how an arm is booted besides its jars. native-controls.py builds the native command and resolves the
# Forbric stage; the launcher and the two scripts it calls put the Forbric arm's java line together; build.gradle pins
# the libraries on that line.
BOOTED_BY=('run/compat/native-controls.py','run/launch-kernel-server.sh','run/game-metadata-jar.sh','run/vanilla-reference-jar.sh','build.gradle')


def prerequisite_problem(image,upstream,version):
 """The two things this cannot make for itself, named before anything is compiled or run; None when both are there.
 Without the image javac used to fail on an empty classpath, which says nothing about what is missing. It is not
 installed from here: the official installer downloads NeoForge's libraries, and a measurement does not go to the
 network on its own."""
 if not (Path(image)/'installation.json').is_file():
  return f'no native NeoForge {version} server image at {image}: run `python3 run/compat/native-controls.py prepare --family neo` once in this checkout (it downloads the official installer and installs a server there)'
 if not Path(upstream).is_file():
  return f'the mod whose root this reproduces is not at {upstream}'
 return None


def measured_sources(kernel,canary_sources):
 """Every source file an arm's measurement depends on, as records: the canary, this script and BOOTED_BY."""
 return [c.record(p) for p in canary_sources]+[c.record(__file__)]+[c.record(Path(kernel)/name) for name in BOOTED_BY]


def arm_inputs(engine,token,mods,carriers,sources,command,image):
 """What one arm was measured on. soak-run.py's native_evidence_problem re-reads every file named here, and requires
 the image record of the native arm: an installation replaced since is another loader than the one measured."""
 inputs={'engine':engine,'token':token,'modSet':[c.record(p) for p in mods],'carriers':carriers,'sources':sources,'command':command}
 if engine=='native':inputs['image']=c.record(Path(image)/'installation.json')
 return inputs


# The signals that end the measurement from outside: SIGTERM and SIGHUP where both exist (macOS and Linux, where this runs),
# SIGTERM and SIGBREAK on Windows, which has no SIGHUP to name (soak-run.py's stop_signals() is the same list and says
# why; test_soak holds the two together). The measurement itself is POSIX-only all the same: its Forbric arm is a bash
# launcher and native-controls.py stops a server through its process group.
STOP_SIGNALS=tuple(getattr(signal,name) for name in ('SIGTERM','SIGHUP','SIGBREAK') if hasattr(signal,name))


def stopped_by_signal(number,frame):
 """A stop signal (STOP_SIGNALS) ends the measurement through the same path as Ctrl-C, so the server it started is
 stopped too: each server runs in a session of its own, and a signal to this script's group never reaches it."""
 raise KeyboardInterrupt(f'signal {number}')


def main():
 K=c.KERNEL;BASE=K/'build/retention-control';BASE.mkdir(parents=True,exist_ok=True)
 # The Forbric arm's stage, resolved before anything is compiled or run (C6 REL review B-7: resolved in the loop, a
 # missing or stale stage was refused only after the native arm had run a server): FORBRIC_OLD as the caller set it,
 # else the prepared .dev stage, and only a stage this tree's pins built (c.forbric_stage), else a clear refusal --
 # never forbric-loader/'s unstamped 26.2.0.88 carrier.
 STAGE=c.forbric_stage()
 image=c.BASE/'native/neo';upstream=K/MOD
 problem=prerequisite_problem(image,upstream,c.VERSIONS['neo'])
 if problem:sys.exit(f'[retention-control] FAIL {problem}; nothing was run')
 for number in STOP_SIGNALS:signal.signal(number,stopped_by_signal)
 canary_sources=list((K/'canary/retention/src').rglob('*.java'))
 classes=BASE/'classes';classes.mkdir(exist_ok=True)
 subprocess.run(['javac','-proc:none','--release','21','-cp',os.pathsep.join(map(str,c.jars(image))),'-d',str(classes),*map(str,canary_sources)],check=True)
 canary=BASE/'forbricretentioncontrol.jar'
 with zipfile.ZipFile(canary,'w') as z:
  for p in classes.rglob('*.class'):z.writestr(p.relative_to(classes).as_posix(),p.read_bytes())
  z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[3,)"\nlicense="Apache-2.0"\n[[mods]]\nmodId="forbricretentioncontrol"\nversion="1.0.0"\ndisplayName="Retention Control"\n')
 sources=measured_sources(K,canary_sources)
 results=[]
 for engine in ('native','forbric'):
  token=uuid.uuid4().hex;run=BASE/(engine+'-'+token);run.mkdir();(run/'mods').mkdir()
  for mod in (upstream,canary):shutil.copy2(mod,run/'mods'/mod.name)
  (run/'.retention-owned').write_text(token);(run/'eula.txt').write_text('eula=true\n')
  # One server at a time: both arms bind the caller's GATE_PORT (else an OS ephemeral port), as native-controls' do.
  (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port='+str(c.control_port())+'\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft\\:flat\nview-distance=3\nmax-tick-time=-1\npause-when-empty-seconds=0\n')
  args=['-Xmx2G','-Djava.awt.headless=true',f'-Dretention.root={run}',f'-Dretention.token={token}'];env=os.environ.copy()
  if engine=='native':
   shutil.copytree(image/'libraries',run/'libraries');command=c.native_command('neo',image,args)
   carriers=[c.record(p) for p in c.jars(run)]
  else:
   # The caller's FORBRIC_JVM first (a sweep's locale or debugging switch reaches this server like any other a gate
   # starts; test_gate_locale holds the rule), then this arm's own options, which win where they name the same thing.
   command=[str(K/'run/launch-kernel-server.sh')];env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='strict',FORBRIC_JVM=' '.join([os.environ.get('FORBRIC_JVM',''),*args]).strip())
   # Same stage (STAGE, above) and defaults as native-controls.py's Forbric arm and launch-kernel-server.sh (NeoForge's
   # patched game and its one carrier), exported so the launcher runs exactly the files recorded here. MERGED/FORGE_RT
   # are retired: the launcher refuses them.
   env['FORBRIC_OLD']=str(STAGE);old=STAGE/'run'
   for key,default in (('BASE',c.STAGED_BASE),('NEO_RT',c.STAGED_CARRIER)):env.setdefault(key,str(old/default))
   carriers=[c.record(Path(env[key])) for key in ('BASE','NEO_RT')]+[c.record(K/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')]
  inputs=arm_inputs(engine,token,sorted((run/'mods').glob('*.jar')),carriers,sources,command,image)
  c.write_json(run/'inputs.json',inputs);began=time.monotonic();sent=False
  with (run/'server.log').open('w') as log:
   process=subprocess.Popen(command,cwd=run,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,start_new_session=True)
   try:
    while process.poll() is None:
     if not sent and (run/'prepared.json').is_file():process.stdin.write('save-all flush\nstop\n');process.stdin.flush();sent=True
     if time.monotonic()-began>180:c.terminate_owned(process);break
     time.sleep(.25)
   finally:
    # Reached with the server still running only when this script is being stopped (Ctrl-C, SIGTERM, SIGHUP, an
    # error above): the server is in its own session, so nothing else would stop it.
    c.terminate_owned(process)
  proof=json.loads((run/'retention.json').read_text()) if (run/'retention.json').exists() else {}
  stable=all(Path(p['path']).is_file() and c.sha(p['path'])==p['sha256'] for p in inputs['modSet']+inputs['carriers']+inputs['sources']+([inputs['image']] if 'image' in inputs else []))
  reproduced=process.returncode==0 and sent and stable and proof.get('token')==token and proof.get('stoppedNormally') is True and proof.get('campfiresReferencingStoppedServer',0)>0
  result={'engine':engine,'nativeRetentionReproduced':reproduced,'inputsUnchanged':stable,'exitCode':process.returncode,'proof':proof,'inputs':c.record(run/'inputs.json'),'log':c.record(run/'server.log')};c.write_json(run/'result.json',result);results.append(result);print(json.dumps(result),flush=True)
 mod_sets=[{Path(p['path']).name:p['sha256'] for p in json.loads(Path(r['inputs']['path']).read_text())['modSet']} for r in results]
 same_mods=mod_sets[0]==mod_sets[1]
 c.write_json(BASE/'comparison.json',{'sameModHashes':same_mods,'arms':results,'scope':'one actual saved campfire retained after native shutdown; does not exonerate other roots'})
 return 0 if same_mods and all(r['nativeRetentionReproduced'] for r in results) else 1


if __name__=='__main__':
 try:sys.exit(main())
 except KeyboardInterrupt as stopped:
  sys.exit(f'[retention-control] FAIL stopped before the measurement finished ({stopped or "interrupted"}); the server it had started was stopped, and no new comparison was written')
