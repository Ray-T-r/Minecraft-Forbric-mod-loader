#!/usr/bin/env python3
"""Reproduce the observed Unlit Campfire cache root on native NeoForge and Forbric.
Does not patch, clear or weakly replace the mod's cache. A reproduction is evidence of a native mod issue,
not a blanket waiver for other roots or permission to call an unreviewed soak a pass.
"""
import importlib.util,json,os,shutil,subprocess,sys,time,uuid,zipfile
from pathlib import Path
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('controls',Path(__file__).with_name('native-controls.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
K=c.KERNEL;BASE=K/'build/retention-control';BASE.mkdir(parents=True,exist_ok=True)
image=c.BASE/'native/neo';upstream=K/'run/client-merged-pack/mods/unlitcampfire-neoforge-26.2-4.1.0.0.jar'
sources=list((K/'canary/retention/src').rglob('*.java'))
classes=BASE/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['javac','-proc:none','--release','21','-cp',os.pathsep.join(map(str,c.jars(image))),'-d',str(classes),*map(str,sources)],check=True)
canary=BASE/'forbricretentioncontrol.jar'
with zipfile.ZipFile(canary,'w') as z:
 for p in classes.rglob('*.class'):z.writestr(p.relative_to(classes).as_posix(),p.read_bytes())
 z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[3,)"\nlicense="Apache-2.0"\n[[mods]]\nmodId="forbricretentioncontrol"\nversion="1.0.0"\ndisplayName="Retention Control"\n')
results=[]
for engine in ('native','forbric'):
 token=uuid.uuid4().hex;run=BASE/(engine+'-'+token);run.mkdir();(run/'mods').mkdir()
 for mod in (upstream,canary):shutil.copy2(mod,run/'mods'/mod.name)
 (run/'.retention-owned').write_text(token);(run/'eula.txt').write_text('eula=true\n')
 (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port='+('25681' if engine=='native' else '25682')+'\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft\\:flat\nview-distance=3\nmax-tick-time=-1\npause-when-empty-seconds=0\n')
 args=['-Xmx2G','-Djava.awt.headless=true',f'-Dretention.root={run}',f'-Dretention.token={token}'];env=os.environ.copy()
 if engine=='native':
  shutil.copytree(image/'libraries',run/'libraries');command=c.native_command('neo',image,args)
  carriers=[c.record(p) for p in c.jars(run)]
 else:
  command=[str(K/'run/launch-kernel-server.sh')];env.update(RUNDIR=str(run),FORBRIC_COMPAT_POLICY='strict',FORBRIC_JVM=' '.join(args))
  carriers=[c.record(Path(env[key])) for key in ('MERGED','FORGE_RT','NEO_RT')]+[c.record(K/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')]
 inputs={'engine':engine,'token':token,'modSet':[c.record(p) for p in sorted((run/'mods').glob('*.jar'))],'carriers':carriers,'sources':[c.record(p) for p in sources]+[c.record(__file__)],'command':command}
 c.write_json(run/'inputs.json',inputs);began=time.monotonic();sent=False
 with (run/'server.log').open('w') as log:
  process=subprocess.Popen(command,cwd=run,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,start_new_session=True)
  while process.poll() is None:
   if not sent and (run/'prepared.json').is_file():process.stdin.write('save-all flush\nstop\n');process.stdin.flush();sent=True
   if time.monotonic()-began>180:c.terminate_owned(process);break
   time.sleep(.25)
 proof=json.loads((run/'retention.json').read_text()) if (run/'retention.json').exists() else {}
 stable=all(Path(p['path']).is_file() and c.sha(p['path'])==p['sha256'] for p in inputs['modSet']+inputs['carriers']+inputs['sources'])
 reproduced=process.returncode==0 and sent and stable and proof.get('token')==token and proof.get('stoppedNormally') is True and proof.get('campfiresReferencingStoppedServer',0)>0
 result={'engine':engine,'nativeRetentionReproduced':reproduced,'inputsUnchanged':stable,'exitCode':process.returncode,'proof':proof,'inputs':c.record(run/'inputs.json'),'log':c.record(run/'server.log')};c.write_json(run/'result.json',result);results.append(result);print(json.dumps(result),flush=True)
mod_sets=[{Path(p['path']).name:p['sha256'] for p in json.loads(Path(r['inputs']['path']).read_text())['modSet']} for r in results]
same_mods=mod_sets[0]==mod_sets[1]
c.write_json(BASE/'comparison.json',{'sameModHashes':same_mods,'arms':results,'scope':'one actual saved campfire retained after native shutdown; does not exonerate other roots'})
sys.exit(0 if same_mods and all(r['nativeRetentionReproduced'] for r in results) else 1)
