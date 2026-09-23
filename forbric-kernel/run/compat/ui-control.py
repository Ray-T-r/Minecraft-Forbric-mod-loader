#!/usr/bin/env python3
"""Exercise actual native client confirmation buttons and close behavior in an owned world copy."""
import json,os,shutil,subprocess,sys,time,uuid,zipfile
from pathlib import Path
from evidence import source_record,file_record
sys.dont_write_bytecode=True
K=Path(__file__).resolve().parents[2];BASE=K/'build/compat-ui';BASE.mkdir(parents=True,exist_ok=True)
old=Path(os.environ['FORBRIC_OLD']);mc=Path.home()/'Library/Application Support/minecraft'
compile_game=old/'run/neoforge-patched/patched-mc-neoforge-26.2.jar';boot=K/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
cp=[compile_game,boot,Path(os.environ['FORGE_RT']),Path(os.environ['NEO_RT'])]
for entry in json.loads((mc/'versions/26.2/26.2.json').read_text())['libraries']:
 path=entry.get('downloads',{}).get('artifact',{}).get('path')
 if path and (mc/'libraries'/path).is_file():cp.append(mc/'libraries'/path)
classes=BASE/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['javac','-proc:none','--release','21','-cp',os.pathsep.join(map(str,cp)),'-d',str(classes),*map(str,(K/'canary/compat-ui/src').rglob('*.java'))],check=True)
canary=BASE/'forbriccompatui.jar'
with zipfile.ZipFile(canary,'w') as z:
 for p in classes.rglob('*.class'):z.writestr(p.relative_to(classes).as_posix(),p.read_bytes())
 z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[3,)"\nlicense="Apache-2.0"\n[[mods]]\nmodId="forbriccompatui"\nversion="1.0.0"\ndisplayName="Compatibility UI Control"\n')
token=uuid.uuid4().hex;run=BASE/token;fixture=K/'run/client-merged-pack';run.mkdir();(run/'saves').mkdir()
# Snapshot first; a user's save is never passed to the child process.
for name in ('mods','config'):
 if (fixture/name).exists():shutil.copytree(fixture/name,run/name)
shutil.copytree(fixture/'saves/ForbricTest',run/'saves/CompatUiWorld');shutil.copy2(fixture/'options.txt',run/'options.txt');shutil.copy2(canary,run/'mods'/canary.name);(run/'quickPlay').mkdir();(run/'.compat-ui-owned').write_text(token)
artifacts=[file_record(p) for p in [*cp,Path(os.environ['MERGED']),K/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar',*sorted((run/'mods').glob('*.jar'))]]
source=source_record(K.parent,set());before={'token':token,'source':source,'artifacts':artifacts,'compileGame':str(compile_game)};(run/'inputs.json').write_text(json.dumps(before,indent=2))
env=os.environ.copy();env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='ask',FORBRIC_JVM=f'-Dforbric.compatibilityPolicy=ask -Dcompatui.token={token}')
command=[str(K/'run/launch-kernel-client.sh'),'--quickPlayPath',str(run/'quickPlay/log.json'),'--quickPlaySingleplayer','CompatUiWorld'];began=time.monotonic()
with (run/'client.log').open('w') as log:
 process=subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 try:code=process.wait(timeout=300)
 except subprocess.TimeoutExpired:
  os.killpg(process.pid,15)
  try:process.wait(timeout=15)
  except subprocess.TimeoutExpired:os.killpg(process.pid,9);process.wait()
  code=-1
proof=json.loads((run/'ui-proof.json').read_text()) if (run/'ui-proof.json').exists() else {}
stable=source_record(K.parent,set())==source and all(file_record(p['path'])==p for p in artifacts)
shots=[file_record(p) for p in sorted((run/'screenshots').glob('*.png'))]
passed=code==0 and stable and proof.get('token')==token and all(proof.get(k) is True for k in ('pass','continueClicked','closeDeclined','initialFocusRefuses','serverStopped')) and proof.get('requiredFindings')==2 and len(shots)==2
result={'pass':passed,'scenario':'expected losses require explicit continue; close refuses and saves','token':token,'exitCode':code,'inputsUnchanged':stable,'seconds':time.monotonic()-began,'proof':proof,'screenshots':shots,'run':str(run)};(run/'result.json').write_text(json.dumps(result,indent=2));(BASE/'latest.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2),flush=True)
sys.exit(0 if passed else 1)
