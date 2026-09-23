#!/usr/bin/env python3
"""One owned client: observe Corpse's real completed constructor and render submission, then save/exit.
Only the test copy is opened. Explicit crash detection bounds an unresponsive crash window to five seconds.
"""
import json,os,shutil,signal,subprocess,sys,time,uuid,zipfile
from pathlib import Path
from evidence import source_record,file_record
sys.dont_write_bytecode=True
K=Path(__file__).resolve().parents[2];BASE=K/'build/corpse-render-control';BASE.mkdir(parents=True,exist_ok=True)
old=Path(os.environ['FORBRIC_OLD']);mc=Path(os.environ.get('MC_DIR',Path.home()/'Library/Application Support/minecraft'));fixture=K/'run/client-merged-pack'
compile_game=old/'run/neoforge-patched/patched-mc-neoforge-26.2.jar';boot=K/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
cp=[compile_game,boot,Path(os.environ['FORGE_RT']),Path(os.environ['NEO_RT']),mc/'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar']
for entry in json.loads((mc/'versions/26.2/26.2.json').read_text())['libraries']:
 path=entry.get('downloads',{}).get('artifact',{}).get('path')
 if path and (mc/'libraries'/path).is_file():cp.append(mc/'libraries'/path)
source_dir=K/'canary/corpse-render';classes=BASE/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['javac','-proc:none','--release','21','-cp',os.pathsep.join(map(str,cp)),'-d',str(classes),*map(str,(source_dir/'src').rglob('*.java'))],check=True)
canary=BASE/'forbric-corpse-probe.jar'
with zipfile.ZipFile(canary,'w') as output:
 for file in classes.rglob('*.class'):output.write(file,file.relative_to(classes).as_posix())
 for name in ('fabric.mod.json','corpse-probe.mixins.json'):output.write(source_dir/name,name)
token=uuid.uuid4().hex;run=BASE/token;run.mkdir();(run/'saves').mkdir()
for name in ('mods','config'):
 if (fixture/name).exists():shutil.copytree(fixture/name,run/name)
shutil.copytree(fixture/'saves/ForbricTest',run/'saves/ForbricTest');shutil.copy2(fixture/'options.txt',run/'options.txt');shutil.copy2(canary,run/'mods'/canary.name);(run/'quickPlay').mkdir();(run/'.corpse-repro-owned').write_text(token)
artifacts=[file_record(p) for p in [*cp,Path(os.environ['MERGED']),K/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar',*sorted((run/'mods').glob('*.jar'))]]
source=source_record(K.parent,set());(run/'inputs.json').write_text(json.dumps({'source':source,'artifacts':artifacts,'token':token},indent=2))
env=os.environ.copy();env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='strict',FORBRIC_JVM='-Dforbric.compatibilityPolicy=strict -Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=ForbricTest -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeScreenshots=100,200 -Dforbric.clientSmokeDisconnectTicks=300')
command=[str(K/'run/launch-kernel-client.sh'),'--quickPlayPath',str(run/'quickPlay/log.json'),'--quickPlaySingleplayer','ForbricTest'];log=run/'client.log';began=time.monotonic();crashed=None;timed_out=False
with log.open('w') as output:
 process=subprocess.Popen(command,env=env,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
 (run/'process.json').write_text(json.dumps({'pid':process.pid,'run':str(run),'command':command},indent=2));(BASE/'current.json').write_text(json.dumps({'pid':process.pid,'run':str(run)},indent=2));print('Started one corpse-render client',process.pid,str(run),flush=True)
 while process.poll() is None:
  if '#@!@# Game crashed!' in log.read_text(errors='replace') and crashed is None:crashed=time.monotonic();os.killpg(process.pid,signal.SIGTERM)
  if (crashed is not None and time.monotonic()-crashed>5) or time.monotonic()-began>240:
   timed_out=True;os.killpg(process.pid,signal.SIGKILL);process.wait();break
  time.sleep(.5)
text=log.read_text(errors='replace');report_path=run/'.forbric-kernel/compatibility-report.json';report=json.loads(report_path.read_text()) if report_path.exists() else {}
stable=source_record(K.parent,set())==source and all(file_record(p['path'])==p for p in artifacts)
shots=[file_record(p) for p in sorted((run/'screenshots').glob('*.png'))]
checks={'normalExit':process.returncode==0,'inputsUnchanged':stable,'notCrashed':crashed is None and not timed_out,'realDummyZeroDistance':'[CorpseRenderProbe] PASS actual dummy name-tag distance=0.0' in text,'realRenderSubmission':'[CorpseRenderProbe] PASS actual corpse renderer submitted' in text,'noProbeFailure':'[CorpseRenderProbe] FAIL' not in text,'normalSaveAndDisconnect':'clean disconnect observed' in text and 'All dimensions are saved' in text,'freshScreenshots':len(shots)==2,'strictReport':report.get('policy')=='STRICT' and report.get('confirmedRequired')==0}
result={'pass':all(checks.values()),'checks':checks,'exitCode':process.returncode,'run':str(run),'seconds':time.monotonic()-began,'screenshots':shots};(run/'result.json').write_text(json.dumps(result,indent=2));(BASE/'latest.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2),flush=True)
sys.exit(0 if result['pass'] else 1)
