#!/usr/bin/env python3
"""Negative controls for M34's independent activity verifier; no game process is launched."""
import copy
import importlib.util
from pathlib import Path
import unittest
import argparse
import json
import gzip
import os
import re
import signal
import sys
import tempfile
import threading
import zipfile
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('soak_run',Path(__file__).with_name('soak-run.py'))
soak=importlib.util.module_from_spec(spec);spec.loader.exec_module(soak)
def load(name):
    """A sibling script by file name (they are named with hyphens, so not importable by name)."""
    found=importlib.util.spec_from_file_location(name.replace('-','_').removesuffix('.py'),Path(__file__).with_name(name))
    module=importlib.util.module_from_spec(found);found.loader.exec_module(module);return module

# What the controller's occupied rows say of the player and the weather (ClientSoakController.worldView/clientView).
PLAYER=dict(gameType='survival',clientGameType='survival',clientInWorld=True,flying=True,health=20.0,raining=False,thundering=False,rainingAtPlayer=False)
def fixture(sessions=2):
    rows=[];nonce='test-nonce';nano=0
    def add(kind,**fields):
        if kind in ('join','sample'):fields={**PLAYER,**fields}
        rows.append(dict(type=kind,nonce=nonce,pid=123,sequence=len(rows)+1,**fields))
    add('start',requiredSeconds=1,releaseEligible=False)
    for server in range(1,sessions+1):
        if server>1:add('open')
        tick=0
        add('join',server=server,occupied=True,paused=False,tick=tick,gameTime=tick,sampleNano=nano)
        for point in list(range(6))*2:
            add('move',server=server,point=point)
            tick+=20;nano+=1_000_000_000
            loaded=[i==point for i in range(6)]
            add('sample',server=server,occupied=True,paused=False,tick=tick,gameTime=tick,sampleNano=nano,point=point,loaded=loaded,chunks=[4,4,4])
        add('save-and-disconnect',server=server)
        add('disconnect',server=server,stopped=True,normalSaveRequested=True)
    add('finish')
    n=sessions
    result=dict(nonce=nonce,pid=123,status='CONTROL_PASS',actualTicks=240*n,activeNanos=12_000_000_000*n,visits=[2*n]*6,unloads=[2*n]*5+[n],reloads=[n]*6,oldServers=[dict(server=i,alive=False,stopped=True) for i in range(1,n+1)])
    return rows,result

class SoakVerifierTest(unittest.TestCase):
    def check(self,rows,result,**overrides):
        values=dict(nonce='test-nonce',seconds=1,control=True,process_seconds=24,min_sessions=2);values.update(overrides)
        return soak.validate_trace(rows,result,**values)
    def test_control_records_real_coverage_but_never_release_acceptance(self):
        out=self.check(*fixture());self.assertEqual(out['status'],'CONTROL_PASS');self.assertFalse(out['releaseAccepted'])
    def test_short_run_cannot_be_renamed_release(self):
        with self.assertRaises(ValueError):self.check(*fixture(),control=False)
    def test_tick_or_active_total_tampering_is_rejected(self):
        for name in ('actualTicks','activeNanos'):
            rows,result=fixture();result[name]+=1
            with self.assertRaises(ValueError):self.check(rows,result)
    def test_paused_world_cannot_earn_elapsed_time(self):
        rows,result=fixture()
        for row in rows:
            if row['type']=='sample':row['paused']=True
        with self.assertRaises(ValueError):self.check(rows,result)
    def test_missing_normal_disconnect_and_reentry_are_rejected(self):
        for kind in ('open','disconnect','save-and-disconnect'):
            rows,result=fixture();rows=[r for r in rows if r['type']!=kind]
            for i,r in enumerate(rows):r['sequence']=i+1
            with self.assertRaises(ValueError):self.check(rows,result)
    def test_stale_process_identity_and_noncontiguous_trace_are_rejected(self):
        for key in ('pid','sequence','nonce'):
            rows,result=fixture();rows[3][key]='wrong'
            with self.assertRaises(ValueError):self.check(rows,result)
    def test_retained_old_server_requires_review(self):
        rows,result=fixture();result['oldServers'][0]['alive']=True;result['status']='REVIEW_REQUIRED'
        with self.assertRaises(soak.RetentionReview) as caught:self.check(rows,result)
        self.assertTrue(caught.exception.activity['activityVerified'])
        self.assertEqual(480,caught.exception.activity['actualTicks'])
    def test_retention_review_does_not_bypass_activity_or_missing_observations(self):
        rows,result=fixture();result['oldServers'][0]['alive']=True;result['status']='REVIEW_REQUIRED'
        with self.assertRaises(ValueError) as caught:self.check(rows,result,seconds=60)
        self.assertNotIsInstance(caught.exception,soak.RetentionReview)
        rows,result=fixture();result['oldServers']=[]
        with self.assertRaises(ValueError):self.check(rows,result)
    ROOT='de.cech12.unlitcampfire.CommonLoader.CAMPFIRES'
    ENTRY=dict(root=ROOT,mod='unlitcampfire.jar',modJarSha256='ab'*32,nativeEvidence='build/retention-control/comparison.json',reproduce='retention-control.py')
    def retained(self,after_alive,rows_cut):
        rows,result=fixture();result['oldServers'][0]['alive']=True
        result['nativeRetentionRelease']=[dict(root=root,present=True,removedStoppedServerEntries=2) for root in rows_cut]
        result['oldServersAfterNativeRelease']=[dict(server=i,alive=(i==1 and after_alive),stopped=True) for i in (1,2)]
        result['status']='REVIEW_REQUIRED' if after_alive else 'CONTROL_PASS'
        return rows,result
    def test_retention_freed_only_by_a_reviewed_native_root_is_attributed_not_waived(self):
        out=self.check(*self.retained(False,[self.ROOT]),native_roots=[self.ENTRY])
        self.assertEqual('CONTROL_PASS',out['status'])
        self.assertEqual([dict(root=self.ROOT,removedStoppedServerEntries=2,modJarSha256='ab'*32)],out['nativeRetentionAttributed'])
    def test_a_server_still_reachable_after_the_native_cut_stays_a_review(self):
        with self.assertRaises(soak.RetentionReview):self.check(*self.retained(True,[self.ROOT]),native_roots=[self.ENTRY])
    def test_the_controller_may_not_cut_a_root_that_is_not_reviewed_for_this_run(self):
        for roots in ([],[dict(self.ENTRY,root='other.Mod.CACHE')]):
            with self.assertRaises(ValueError) as caught:self.check(*self.retained(False,[self.ROOT]),native_roots=roots)
            self.assertNotIsInstance(caught.exception,soak.RetentionReview)
    def test_cutting_without_any_retention_or_losing_a_session_is_rejected(self):
        rows,result=fixture();result['nativeRetentionRelease']=[dict(root=self.ROOT,removedStoppedServerEntries=0)]
        with self.assertRaises(ValueError):self.check(rows,result,native_roots=[self.ENTRY])
        rows,result=self.retained(False,[self.ROOT]);result['oldServersAfterNativeRelease']=result['oldServersAfterNativeRelease'][:1]
        with self.assertRaises(ValueError):self.check(rows,result,native_roots=[self.ENTRY])
    def test_only_roots_whose_exact_jar_is_frozen_in_the_run_are_offered(self):
        with tempfile.TemporaryDirectory() as temporary:
            registry=Path(temporary)/'r.json';registry.write_text(json.dumps(dict(schema=1,roots=[self.ENTRY])))
            self.assertEqual([self.ENTRY],soak.native_retention_roots(['cd'*32,'ab'*32],registry))
            self.assertEqual([],soak.native_retention_roots(['cd'*32],registry))
    def evidence(self,kernel,launched=None):
        """A comparison.json as retention-control.py leaves it, over real files: per arm a mod jar, what booted it and a
        source, each recorded with its bytes. Returns the files by name, to change them under the evidence."""
        kernel=Path(kernel);(kernel/'build/retention-control').mkdir(parents=True,exist_ok=True);files={};arms=[]
        def put(name,data):
            path=kernel/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);files[name]=path
            return dict(path=str(path),sha256=soak.digest(path))
        source=put('canary/Retention.java',b'source')
        for engine in ('native','forbric'):
            mod=put(f'{engine}/mods/unlitcampfire.jar',b'mod');mod['sha256']=self.ENTRY['modJarSha256']
            carriers=[put(f'{engine}/{name}',name.encode()) for name in ('kernel.jar','base.jar','carrier.jar')]
            record=dict(engine=engine,modSet=[mod],carriers=carriers,sources=[source])
            if engine=='native':record['image']=put('native/installation.json',b'{}')
            # Where retention-control.py writes an arm's record: in the arm's run directory, beside the comparison.
            inputs=kernel/f'build/retention-control/{engine}-run/inputs.json';inputs.parent.mkdir(exist_ok=True)
            inputs.write_text(json.dumps(record));files[f'{engine}/inputs.json']=inputs
            arms.append(dict(engine=engine,nativeRetentionReproduced=True,proof=dict(root=self.ROOT),inputs=dict(path=str(inputs),sha256=soak.digest(inputs))))
        (kernel/self.ENTRY['nativeEvidence']).write_text(json.dumps(dict(sameModHashes=True,arms=arms)))
        return files,arms
    def modded(self):
        """digest() of the stand-in mod jar is not the registry's made-up hash; read every other file for real."""
        real=soak.digest
        return patch.object(soak,'digest',side_effect=lambda path:self.ENTRY['modJarSha256'] if Path(path).name=='unlitcampfire.jar' else real(path))
    def launched(self,files):
        return {'kernel jar':soak.digest(files['forbric/kernel.jar']),'game base':soak.digest(files['forbric/base.jar']),'NeoForge carrier':soak.digest(files['forbric/carrier.jar'])}
    def test_release_rereads_the_named_native_reproduction(self):
        with tempfile.TemporaryDirectory() as temporary,self.modded():
            kernel=Path(temporary);(kernel/'build/retention-control').mkdir(parents=True)
            with self.assertRaises(ValueError) as absent:soak.verify_native_evidence(kernel,self.ENTRY)
            self.assertIn('absent',str(absent.exception))
            files,arms=self.evidence(kernel);comparison=kernel/self.ENTRY['nativeEvidence']
            self.assertEqual(self.ROOT,soak.verify_native_evidence(kernel,self.ENTRY)['root'])
            for broken in (dict(sameModHashes=False,arms=arms),dict(sameModHashes=True,arms=arms[:1]),
                           dict(sameModHashes=True,arms=[dict(arms[0],nativeRetentionReproduced=False),arms[1]])):
                comparison.write_text(json.dumps(broken))
                with self.assertRaises(ValueError):soak.verify_native_evidence(kernel,self.ENTRY)
            comparison.write_text(json.dumps(dict(sameModHashes=True,arms=arms)))
            with self.assertRaises(ValueError):soak.verify_native_evidence(kernel,dict(self.ENTRY,modJarSha256='cd'*32))
    def test_evidence_is_stale_once_anything_it_was_measured_on_changes(self):
        # Each file an arm recorded, changed or removed after the comparison: the kernel jar rebuilt, the stage's base
        # or carrier replaced, the canary or retention-control.py edited, the native image reinstalled, the arm's own
        # record rewritten. One stale file is enough, in either arm.
        for name in ('forbric/kernel.jar','forbric/base.jar','forbric/carrier.jar','native/kernel.jar','canary/Retention.java',
                     'native/installation.json','native/inputs.json','forbric/inputs.json'):
            for removed in (False,True):
                with tempfile.TemporaryDirectory() as temporary,self.modded():
                    files,_=self.evidence(temporary)
                    self.assertIsNone(soak.native_evidence_problem(temporary,self.ENTRY),'fresh as written')
                    if removed:files[name].unlink()
                    else:files[name].write_bytes(files[name].read_bytes()+b' ')
                    problem=soak.native_evidence_problem(temporary,self.ENTRY)
                    self.assertIsNotNone(problem,f'{name} {"removed" if removed else "changed"} must make the evidence stale')
                    self.assertTrue(problem.startswith('stale: '),problem)
                    with self.assertRaises(ValueError):soak.verify_native_evidence(temporary,self.ENTRY)
    def rewrite(self,kernel,change_comparison=None,change_record=None,engine='native'):
        """Change the comparison, or one arm's record (the comparison's digest of it is kept true)."""
        kernel=Path(kernel);comparison=kernel/self.ENTRY['nativeEvidence'];data=json.loads(comparison.read_text())
        if change_record:
            inputs=kernel/f'build/retention-control/{engine}-run/inputs.json';record=json.loads(inputs.read_text())
            inputs.write_text(json.dumps(change_record(record)))
            for arm in data['arms']:
                if arm['engine']==engine:arm['inputs']['sha256']=soak.digest(inputs)
        comparison.write_text(json.dumps(change_comparison(data) if change_comparison else data))
    def test_evidence_of_a_shape_that_cannot_be_read_is_unreadable_and_measured_again(self):
        # JSON, but not a comparison: a list, an arm without its record, a record that is a list or lacks its mod set,
        # a file entry without its digest. Each used to end the soak with a bare KeyError/AttributeError ("[M34] FAIL
        # 'inputs'") and no measurement; each is evidence that cannot be used, so it is named and measured again.
        def without(key):
            def change(value):
                value=dict(value);del value[key];return value
            return change
        def arm_without_record(data):
            data['arms'][0]=without('inputs')(data['arms'][0]);return data
        def source_without_digest(record):
            record['sources']=[without('sha256')(record['sources'][0])];return record
        cases=[('a list',dict(change_comparison=lambda data:[])),('arms of another shape',dict(change_comparison=lambda data:dict(data,arms=['native','forbric']))),
               ('an arm without its record',dict(change_comparison=arm_without_record)),
               ('a record that is a list',dict(change_record=lambda record:[])),('a record without its mod set',dict(change_record=without('modSet'))),
               ('a file entry without its digest',dict(change_record=source_without_digest))]
        for name,change in cases:
            with tempfile.TemporaryDirectory() as temporary,self.modded(),patch('builtins.print'):
                files,_=self.evidence(temporary);launched=self.launched(files)
                self.assertIsNone(soak.native_evidence_problem(temporary,self.ENTRY,launched),'fresh as written')
                self.rewrite(temporary,**change)
                problem=soak.native_evidence_problem(temporary,self.ENTRY,launched)
                self.assertIsNotNone(problem,name);self.assertTrue(problem.startswith('unreadable: '),f'{name}: {problem}')
                # The file that cannot be read is the one named: the comparison, or the arm's own record.
                kernel=Path(temporary).resolve();expected=kernel/'build/retention-control/native-run/inputs.json' if 'change_record' in change else kernel/self.ENTRY['nativeEvidence']
                self.assertIn(f'unreadable: {expected} is not a record',problem.replace(str(Path(temporary)),str(kernel)),name)
                with self.assertRaises(ValueError) as refused:soak.verify_native_evidence(temporary,self.ENTRY,launched)
                self.assertIn('unreadable',str(refused.exception))
                runs=[]
                def reproduce(kernel,entry,log):
                    runs.append(name);self.evidence(kernel);return 0
                record=soak.ensure_native_evidence(temporary,self.ENTRY,launched,Path(temporary)/'r.log',reproduce=reproduce)
                self.assertEqual([name],runs,'unreadable evidence is measured again, once');self.assertTrue(record['measuredForThisRun'])
    def test_a_native_arm_that_does_not_name_its_installed_image_is_stale(self):
        # What the script wrote before it recorded the image: nothing says which installation the native arm booted.
        def no_image(record):
            del record['image'];return record
        with tempfile.TemporaryDirectory() as temporary,self.modded():
            self.evidence(temporary);self.rewrite(temporary,change_record=no_image)
            problem=soak.native_evidence_problem(temporary,self.ENTRY)
            self.assertIn('does not name the installed image it booted',problem);self.assertTrue(problem.startswith('stale: '),problem)
        # The Forbric arm boots no installed image, so it has none to name.
        with tempfile.TemporaryDirectory() as temporary,self.modded():
            self.evidence(temporary);self.rewrite(temporary,change_record=lambda record:record,engine='forbric')
            self.assertIsNone(soak.native_evidence_problem(temporary,self.ENTRY))
    def test_a_comparison_measured_in_another_checkout_is_not_this_ones_evidence(self):
        # comparison.json copied from another tree: its arms' records, and every file they name, are that tree's, and
        # all of them are unchanged there. It says nothing of this checkout.
        with tempfile.TemporaryDirectory() as temporary,self.modded():
            other=Path(temporary)/'other';here=Path(temporary)/'here'
            self.evidence(other);self.assertIsNone(soak.native_evidence_problem(other,self.ENTRY))
            (here/'build/retention-control').mkdir(parents=True)
            (here/self.ENTRY['nativeEvidence']).write_bytes((other/self.ENTRY['nativeEvidence']).read_bytes())
            problem=soak.native_evidence_problem(here,self.ENTRY)
            self.assertIn('was not measured in this checkout',problem);self.assertTrue(problem.startswith('stale: '),problem)
    def test_evidence_measured_on_other_jars_than_the_soak_launches_is_stale(self):
        with tempfile.TemporaryDirectory() as temporary,self.modded():
            files,_=self.evidence(temporary);launched=self.launched(files)
            self.assertIsNone(soak.native_evidence_problem(temporary,self.ENTRY,launched))
            for name in launched:
                problem=soak.native_evidence_problem(temporary,self.ENTRY,{**launched,name:'ef'*32})
                self.assertIn(f'did not boot the {name} this soak launches',problem)
    def test_current_evidence_is_used_without_measuring_again(self):
        with tempfile.TemporaryDirectory() as temporary,self.modded(),patch('builtins.print') as said:
            files,_=self.evidence(temporary);runs=[]
            record=soak.ensure_native_evidence(temporary,self.ENTRY,self.launched(files),Path(temporary)/'r.log',reproduce=lambda *a:runs.append(a) or 0)
            self.assertEqual([],runs,'fresh evidence must not start two servers again')
            self.assertFalse(record['measuredForThisRun']);self.assertNotIn('measurement',record)
            self.assertIn('is current',said.call_args_list[0].args[0])
    def test_absent_or_stale_evidence_is_measured_again_and_then_checked_like_any_other(self):
        for start in ('absent','stale'):
            with tempfile.TemporaryDirectory() as temporary,self.modded(),patch('builtins.print') as said:
                launched=None
                if start=='stale':
                    files,_=self.evidence(temporary);launched=self.launched(files)
                    files['forbric/kernel.jar'].write_bytes(b'a kernel built since')
                    launched['kernel jar']=soak.digest(files['forbric/kernel.jar'])
                runs=[]
                def reproduce(kernel,entry,log):
                    runs.append(entry['reproduce']);Path(log).write_text('two servers ran\n')
                    fresh,_=self.evidence(kernel)
                    if start=='stale':fresh['forbric/kernel.jar'].write_bytes(b'a kernel built since');self.rerecord(kernel)
                    return 0
                if start=='absent':
                    probe,_=self.evidence(Path(temporary)/'elsewhere');launched=self.launched(probe)
                record=soak.ensure_native_evidence(temporary,self.ENTRY,launched,Path(temporary)/'r.log',reproduce=reproduce)
                self.assertEqual(['retention-control.py'],runs,start)
                self.assertTrue(record['measuredForThisRun']);self.assertEqual(0,record['measurement']['exitCode'])
                self.assertEqual(soak.digest(Path(temporary)/self.ENTRY['nativeEvidence']),record['sha256'])
                lines=[call.args[0] for call in said.call_args_list]
                self.assertIn(start,lines[0]);self.assertIn('measuring it now',lines[1]);self.assertRegex(lines[2],r'took \d+ s and exited 0')
    def rerecord(self,kernel):
        """Write the Forbric arm's record again over the files as they are now, as a real reproduction would."""
        kernel=Path(kernel);inputs=kernel/'build/retention-control/forbric-run/inputs.json';record=json.loads(inputs.read_text())
        for item in record['carriers']:item['sha256']=soak.digest(item['path'])
        inputs.write_text(json.dumps(record));comparison=kernel/self.ENTRY['nativeEvidence'];data=json.loads(comparison.read_text())
        for arm in data['arms']:
            if arm['engine']=='forbric':arm['inputs']['sha256']=soak.digest(inputs)
        comparison.write_text(json.dumps(data))
    def test_a_measurement_that_fails_or_leaves_unusable_evidence_stops_the_soak(self):
        # Exit 1 with nothing written; exit 0 with nothing written; exit 0 with evidence of other jars than the soak's;
        # exit 1 although the evidence it left is usable. None may let the soak go on.
        def nothing(code):return lambda kernel,entry,log:code
        def other_jars(kernel,entry,log):self.evidence(kernel);return 0
        def failed_but_written(kernel,entry,log):self.evidence(kernel);return 1
        for reproduce,launched,expected in ((nothing(1),None,'exited 1'),(nothing(0),None,'absent'),
                                            (other_jars,{'kernel jar':'ef'*32},'did not boot the kernel jar'),(failed_but_written,None,'exited 1')):
            with tempfile.TemporaryDirectory() as temporary,self.modded(),patch('builtins.print'):
                with self.assertRaises(ValueError) as stopped:
                    soak.ensure_native_evidence(temporary,self.ENTRY,launched,Path(temporary)/'r.log',reproduce=reproduce)
                self.assertIn('could not be produced',str(stopped.exception));self.assertIn(expected,str(stopped.exception))
        # What the reproduction said last is in the refusal, so the gate's output names the cause (a missing native image).
        def refused(kernel,entry,log):Path(log).write_text('compiling\n[retention-control] FAIL no native NeoForge image at X: run prepare\n\n');return 1
        with tempfile.TemporaryDirectory() as temporary,patch('builtins.print'):
            with self.assertRaises(ValueError) as stopped:soak.ensure_native_evidence(temporary,self.ENTRY,None,Path(temporary)/'r.log',reproduce=refused)
            self.assertIn('its last line: [retention-control] FAIL no native NeoForge image at X: run prepare',str(stopped.exception))
    def test_the_reproduction_is_the_registrys_own_command_run_in_the_kernel_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            log=Path(temporary)/'r.log'
            class Done:
                def wait(self,timeout=None):return 7
            with patch.object(soak.subprocess,'Popen',return_value=Done()) as run,patch.dict(soak.os.environ,{'FORBRIC_CAUGHT_FILE':'/gate/file.tsv','GATE_PORT':'46999','FORBRIC_JVM':'-Duser.language=zh'}):
                self.assertEqual(7,soak.run_reproduction(Path(temporary),dict(reproduce='python3 run/compat/retention-control.py'),log))
            command=run.call_args.args[0]
            self.assertEqual([soak.sys.executable,'run/compat/retention-control.py'],command,'the interpreter running the soak, not whichever python3 is on PATH')
            self.assertEqual(Path(temporary),run.call_args.kwargs['cwd']);self.assertTrue(log.is_file())
            # The caller's environment, except where the kernel writes what it caught: a file of the measurement's own.
            given=run.call_args.kwargs['env']
            self.assertEqual(('46999','-Duser.language=zh'),(given['GATE_PORT'],given['FORBRIC_JVM']))
            self.assertEqual(str(Path(temporary)/'r.caught.tsv'),given['FORBRIC_CAUGHT_FILE']);self.assertTrue(Path(given['FORBRIC_CAUGHT_FILE']).is_file())
    def test_what_the_measurements_server_caught_is_recorded_and_reaches_the_gates_file(self):
        with tempfile.TemporaryDirectory() as temporary,self.modded(),patch('builtins.print') as said:
            gate=Path(temporary)/'gate-m34-soak.1.tsv';gate.write_text('');log=Path(temporary)/'native-retention-0.log'
            def reproduce(kernel,entry,log):
                soak.reproduction_caught(log).write_text('FAILURE\tWARN\tnet.forbric.Probe.site:1\t[Forbric/Probe] in the measurement\tjava.lang.IllegalStateException\n')
                self.evidence(kernel);return 0
            with patch.dict(soak.os.environ,{'FORBRIC_CAUGHT_FILE':str(gate)}):
                record=soak.ensure_native_evidence(temporary,self.ENTRY,None,log,reproduce=reproduce)
            self.assertEqual(['net.forbric.Probe.site:1'],record['measurement']['caught']['failureSites'])
            self.assertEqual(str(Path(temporary)/'native-retention-0.caught.tsv'),record['measurement']['caught']['file'])
            self.assertIn('[Forbric/Probe] in the measurement',gate.read_text(),'the gate reports every kernel JVM it started')
            self.assertTrue(any("measurement's Forbric server caught 1 failure site(s)" in call.args[0] for call in said.call_args_list))
    def test_an_interrupted_reproduction_is_told_to_stop_and_given_time_to_stop_its_server(self):
        # The reproduction's servers run in sessions of their own, so only the reproduction can stop them. SIGTERM to
        # the soak while it measures: the reproduction gets SIGTERM (not SIGKILL) and has finished its clean-up before
        # run_reproduction lets the interruption through; the soak's own handler is back afterwards.
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);marker=root/'server-stopped';ready=root/'ready'
            script=root/'measure.py'
            script.write_text('import signal,sys,time\nfrom pathlib import Path\n'
                              f'def stop(*_):\n time.sleep(.3);Path({str(marker)!r}).write_text("stopped");sys.exit(1)\n'
                              f'signal.signal(signal.SIGTERM,stop);Path({str(ready)!r}).write_text("up");time.sleep(60)\n')
            before=signal.getsignal(signal.SIGTERM)
            def interrupt():
                for _ in range(200):
                    if ready.is_file():break
                    threading.Event().wait(.05)
                os.kill(os.getpid(),signal.SIGTERM)
            threading.Thread(target=interrupt,daemon=True).start()
            with self.assertRaises(KeyboardInterrupt):
                soak.run_reproduction(root,dict(reproduce=f'python3 {script}'),root/'r.log')
            self.assertEqual('stopped',marker.read_text(),'the reproduction was told to stop and waited for')
            self.assertIs(before,signal.getsignal(signal.SIGTERM),'the handler in force before the measurement is restored')
    def test_a_stopped_soak_is_interrupted_like_ctrl_c_and_stops_its_client(self):
        # SIGTERM while the soak runs is the interruption Ctrl-C is, for the whole run: main() turns it into one, and
        # the handler in force before is back when main() returns.
        before=signal.getsignal(signal.SIGTERM);seen=[]
        def launch(args):
            seen.append(signal.getsignal(signal.SIGTERM));os.kill(os.getpid(),signal.SIGTERM);threading.Event().wait(5);return 0
        argv=['soak-run.py','--kernel','k','--staged','s','--fixture','f','--boot-classpath','c','--control']
        with patch.object(soak,'launch',side_effect=launch),patch.object(sys,'argv',argv):
            with self.assertRaises(KeyboardInterrupt) as stopped:soak.main()
        self.assertIn('signal 15',str(stopped.exception));self.assertIsNot(before,seen[0]);self.assertIs(before,signal.getsignal(signal.SIGTERM))
        # In the client's wait that interruption stops the client's own process group (it is in a session of its own:
        # nothing else would) and the run is recorded as a failed run, not left without a verdict.
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);kernel=root/'kernel';staged=root/'staged';pack=root/'pack';mc=root/'minecraft'
            def put(path,data=b'fixture'):
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path
            def jar(path,entries):
                path.parent.mkdir(parents=True,exist_ok=True)
                with zipfile.ZipFile(path,'w') as output:
                    for key,value in entries.items():output.writestr(key,value)
                return path
            runtime=put(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar')
            jar(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar',{'bundled/forbric-kernel-runtime.jar':runtime.read_bytes()})
            jar(staged/'neoforge-patched/patched-mc-neoforge-26.2.jar',{'version.json':'{}'});put(staged/'neoforge-runtime/neoforge-runtime.jar')
            put(pack/'saves/ForbricTest/level.dat');put(pack/'mods/probe.jar')
            put(mc/'versions/26.2/26.2.json',json.dumps({'id':'26.2','assetIndex':{'id':'test'},'libraries':[]}).encode());put(mc/'assets/indexes/test.json',b'{}');put(mc/'versions/26.2/26.2-natives/probe.dylib')
            cp=put(root/'boot-classpath.txt',str(put(root/'dependency.jar')).encode())
            args=argparse.Namespace(kernel=str(kernel),staged=str(staged),fixture=str(pack),minecraft=str(mc),world_source=None,base=None,neo=None,natives=None,boot_classpath=str(cp),seconds=1,control=True,sessions=2,dwell_ticks=20,between_seconds=0,settle_seconds=0,timeout=60,policy='strict',heap='1G',java='unused-test-java',native_reference=None)
            source={'root':str(root),'commit':'test','sha256':'source-content','files':{},'dirty':False}
            signals=[]
            class Child:
                pid=4242;returncode=None
                def __init__(self,command,cwd,**kwargs):pass
                def wait(self,timeout=None):self.returncode=-15;return -15
                def poll(self):return self.returncode
            def stopped(process,log,timeout):raise KeyboardInterrupt('signal 15')
            reference=put(root/'reference/vanilla-reference.jar',b'sidecar')
            with patch.object(soak,'source_record',return_value=source),patch.object(soak,'vanilla_reference',return_value=reference),patch.object(soak.subprocess,'Popen',Child),patch.object(soak,'wait_for_client',side_effect=stopped),patch.object(soak.os,'killpg',side_effect=lambda pid,value:signals.append((pid,value))),patch('builtins.print'):
                self.assertEqual(1,soak.launch(args))
            self.assertEqual([(4242,signal.SIGTERM)],signals,'the client\'s own process group is told to stop')
            acceptance=json.loads((kernel/'build/verification/m34-soak/last-control.json').read_text())['acceptance']
            self.assertEqual(('FAIL',-1),(acceptance['status'],acceptance['exitCode']));self.assertIn('client exited abnormally: -1',acceptance['detail'])
    def test_the_reproduction_names_what_it_lacks_and_records_what_it_was_measured_on(self):
        # retention-control.py's own pieces (it runs nothing at import): the refusal that names the missing native image
        # and its one command, and an arm's record, which is what soak-run.py's freshness check reads.
        control=load('retention-control.py')
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);image=root/'native/neo';mod=root/'mods/unlitcampfire.jar';mod.parent.mkdir();mod.write_bytes(b'mod')
            problem=control.prerequisite_problem(image,mod,'26.2.0.93')
            self.assertIn(f'no native NeoForge 26.2.0.93 server image at {image}',problem)
            self.assertIn('python3 run/compat/native-controls.py prepare --family neo',problem)
            image.mkdir(parents=True);(image/'installation.json').write_text('{}')
            self.assertIsNone(control.prerequisite_problem(image,mod,'26.2.0.93'))
            mod.unlink();self.assertIn(f'the mod whose root this reproduces is not at {mod}',control.prerequisite_problem(image,mod,'26.2.0.93'))
            mod.write_bytes(b'mod');source=root/'Retention.java';source.write_text('class Retention {}')
            native=control.arm_inputs('native','t',[mod],[],[control.c.record(source)],['java'],image)
            self.assertEqual(control.c.record(image/'installation.json'),native['image'])
            self.assertEqual([control.c.record(mod)],native['modSet'])
            self.assertNotIn('image',control.arm_inputs('forbric','t',[mod],[],[],['launch'],image))
            # What soak-run.py walks is what the arm records, the image included.
            self.assertIn(native['image'],soak.recorded_files(native))
        # The sources of a measurement: the canary, the script, and everything that decides how an arm is booted. Each
        # is a file of this tree (a renamed launcher must not silently leave the record).
        kernel=Path(__file__).resolve().parents[2]
        recorded={Path(item['path']) for item in control.measured_sources(kernel,[])}
        self.assertEqual({(kernel/name).resolve() for name in control.BOOTED_BY}|{Path(control.__file__).resolve()},recorded)
        self.assertLessEqual({'run/compat/native-controls.py','run/launch-kernel-server.sh','build.gradle'},set(control.BOOTED_BY))
        # The launcher's own helpers are the ones recorded: every run/*.sh it calls by "$HERE/".
        called=set(re.findall(r'"\$HERE/([A-Za-z0-9_-]+\.sh)"',(kernel/'run/launch-kernel-server.sh').read_text()))
        self.assertEqual(called,{Path(name).name for name in control.BOOTED_BY if name.startswith('run/') and name.endswith('.sh')}-{'launch-kernel-server.sh'})
    def test_the_first_joins_weather_is_read_from_the_copied_world(self):
        snapshot=load('world-snapshot.py')
        def world(root,**data):
            path=Path(root)/'data/minecraft/weather.dat';path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(gzip.compress(snapshot.encode_root(['compound',{'DataVersion':['int',4903],'data':['compound',{name:list(value) for name,value in data.items()}]}])))
            return Path(root)
        with tempfile.TemporaryDirectory() as temporary:
            clear=soak.saved_weather(world(Path(temporary)/'clear',raining=('byte',0),thundering=('byte',0),rain_time=('int',88032),thunder_time=('int',70658),clear_weather_time=('int',0)))
            self.assertEqual((False,False,88032,70658,0),tuple(clear[k] for k in ('raining','thundering','rainTime','thunderTime','clearWeatherTime')))
            self.assertIn('saved in clear weather; its rain timer stands at 88032 tick(s), so the first session joins into clear weather',soak.describe_saved_weather(clear))
            rain=soak.saved_weather(world(Path(temporary)/'rain',raining=('byte',1),thundering=('byte',0),rain_time=('int',4000)))
            self.assertTrue(rain['raining']);self.assertIsNone(rain['thunderTime'])
            self.assertIn('saved in rain weather',soak.describe_saved_weather(rain));self.assertIn('the first session joins into rain',soak.describe_saved_weather(rain))
            storm=soak.saved_weather(world(Path(temporary)/'storm',raining=('byte',1),thundering=('byte',1),rain_time=('int',10)))
            self.assertIn('saved in thunder weather',soak.describe_saved_weather(storm))
            # A world that keeps no such file, or a file that is not NBT: said, never an error (it is not a check).
            (Path(temporary)/'none').mkdir();absent=soak.saved_weather(Path(temporary)/'none')
            self.assertIn('no such file',absent['problem']);self.assertIn('is not known',soak.describe_saved_weather(absent))
            broken=Path(temporary)/'broken/data/minecraft/weather.dat';broken.parent.mkdir(parents=True);broken.write_bytes(b'not nbt')
            self.assertIn('not readable as saved weather',soak.saved_weather(Path(temporary)/'broken')['problem'])
    def test_a_player_that_is_not_a_survival_player_is_not_a_soak(self):
        # The server's report in every occupied row: creative (what the controller made the player until 2026-11-20),
        # any other type, and no report at all (an older controller's telemetry). One such row refuses the run.
        for kind in ('join','sample'):
            for told in ('creative','adventure','spectator',None):
                rows,result=fixture();row=next(r for r in rows if r['type']==kind and r['server']==2)
                if told is None:del row['gameType']
                else:row['gameType']=told
                with self.assertRaises(ValueError) as refused:self.check(rows,result)
                self.assertNotIsInstance(refused.exception,soak.RetentionReview)
                self.assertIn('was not a survival player',str(refused.exception));self.assertIn(f"row {row['sequence']}",str(refused.exception))
        self.assertEqual('CONTROL_PASS',self.check(*fixture())['status'])
    def test_the_client_has_to_hold_a_survival_player_too(self):
        # The client's game type decides what the client draws and runs for the player, which is what the soak is for.
        for kind in ('join','sample'):
            for told in ('creative','adventure','spectator',None):
                rows,result=fixture();row=next(r for r in rows if r['type']==kind and r['server']==2)
                if told is None:del row['clientGameType']
                else:row['clientGameType']=told
                with self.assertRaises(ValueError) as refused:self.check(rows,result)
                self.assertNotIsInstance(refused.exception,soak.RetentionReview)
                self.assertIn('was not a survival player on the client',str(refused.exception));self.assertIn(f"row {row['sequence']}",str(refused.exception))
        # A sample written just after a disconnect: the client has left the world and holds no game type. Not a drift.
        rows,result=fixture();row=[r for r in rows if r['type']=='sample' and r['server']==2][-1]
        row['clientInWorld']=False;del row['clientGameType']
        self.assertEqual('CONTROL_PASS',self.check(rows,result)['status'])
        # A join is written only once the client holds a live player, so the exception is not a join's.
        rows,result=fixture();row=next(r for r in rows if r['type']=='join' and r['server']==2)
        row['clientInWorld']=False;del row['clientGameType']
        with self.assertRaises(ValueError):self.check(rows,result)
        # Rows of the controller before it wrote clientInWorld (they carry clientGameType) are read as before.
        rows,result=fixture()
        for row in rows:row.pop('clientInWorld',None)
        self.assertEqual('CONTROL_PASS',self.check(rows,result)['status'])
        # An unoccupied row has no player to hold to anything.
        rows,result=fixture(sessions=3);row=[r for r in rows if r['type']=='sample' and r['server']==2][3]
        row['occupied']=False;del row['gameType'];del row['clientGameType']
        try:self.check(rows,result,min_sessions=2)
        except ValueError as other:self.assertNotIn('survival player',str(other))
    def test_the_conditions_summary_says_when_it_rained_on_the_player_and_never_fails_a_run(self):
        rows,_=fixture()
        samples=[r for r in rows if r['type']=='sample'];joins=[r for r in rows if r['type']=='join']
        for row in samples[5:]:row['raining']=True
        samples[7]['rainingAtPlayer']=True;samples[7]['utc']='2026-11-20T18:21:15Z';samples[7]['gameTime']=163081;samples[9]['health']=17.5
        joins[1]['raining']=joins[1]['rainingAtPlayer']=True
        told=soak.conditions(rows)
        self.assertEqual(len(samples)+len(joins),told['rows']);self.assertEqual(['survival'],told['gameTypes'])
        self.assertEqual(len(samples)-5+1,told['rainingRows']);self.assertEqual(2,told['rainingAtPlayerRows'])
        self.assertEqual(samples[5]['sequence'],told['firstRaining']['sequence'])
        self.assertEqual((samples[7]['sequence'],'2026-11-20T18:21:15Z',163081),tuple(told['firstRainingAtPlayer'][k] for k in ('sequence','utc','gameTime')))
        self.assertEqual([joins[1]['sequence']],[row['sequence'] for row in told['joinsInRain']])
        self.assertEqual(17.5,told['lowestHealth']);self.assertEqual(samples[-1]['sequence'],told['last']['sequence'])
        # A row whose player stood in a chunk not loaded yet says null, not false: unknown, counted apart, never rain.
        self.assertEqual(0,told['rainingAtPlayerUnknownRows'])
        samples[2]['rainingAtPlayer']=None;samples[3]['rainingAtPlayer']=None
        told=soak.conditions(rows)
        self.assertEqual((2,2),(told['rainingAtPlayerUnknownRows'],told['rainingAtPlayerRows']))
        self.assertEqual(samples[7]['sequence'],told['firstRainingAtPlayer']['sequence'])
        # Telemetry of a controller that recorded none of it, and no telemetry: a summary of nothing, not an error.
        for old in ([{k:v for k,v in row.items() if k not in PLAYER} for row in rows],[]):
            told=soak.conditions(old)
            self.assertEqual((0,[],0,None,None,[],None,None),(told['rows'],told['gameTypes'],told['rainingRows'],told['firstRaining'],told['firstRainingAtPlayer'],told['joinsInRain'],told['lowestHealth'],told['last']))
    def test_a_crashed_clients_cut_last_telemetry_line_is_dropped_only_for_the_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            rows,_=fixture();path=Path(temporary)/'telemetry.jsonl'
            path.write_text(''.join(json.dumps(row)+'\n' for row in rows[:9])+'{"type":"sample","rain')
            self.assertEqual(rows[:9],soak.read_telemetry(path))
    def residual(self,sessions,alive,analysis):
        rows,result=fixture()
        result['oldServers']=[dict(server=i,alive=True,stopped=True) for i in (1,2)]
        result['oldServersAfterNativeRelease']=[dict(server=i,alive=i in alive,stopped=True) for i in (1,2)]
        result['status']='REVIEW_REQUIRED'
        return self.check(rows,result,mod_owned=analysis,min_sessions=sessions)
    def test_residual_retention_needs_mod_owned_paths_and_most_sessions_collected(self):
        good=dict(exitCode=0,reachable=0,unreachable=1,analysis='paths.txt',sha256='x')
        with self.assertRaises(ValueError) as caught:self.residual(2,{2},good)
        self.assertIsInstance(caught.exception,soak.RetentionReview,'one of two sessions retained is not "most collected"')
        for broken in (None,dict(good,reachable=1),dict(good,unreachable=0),dict(good,exitCode=1)):
            with self.assertRaises(soak.RetentionReview):self.residual(2,{2},broken)
    def test_mod_owned_residual_passes_when_few_of_many_sessions_remain(self):
        rows,result=fixture(4)
        for server in result['oldServers']:server['alive']=True
        result['oldServersAfterNativeRelease']=[dict(server=i,alive=i==4,stopped=True) for i in range(1,5)]
        result['status']='REVIEW_REQUIRED'
        analysis=dict(exitCode=0,reachable=0,unreachable=1,analysis='paths.txt',sha256='x')
        out=self.check(rows,result,mod_owned=analysis,min_sessions=4,process_seconds=48)
        self.assertEqual('CONTROL_PASS',out['status']);self.assertEqual(1,out['modOwnedRetention']['retained'])
        result['oldServersAfterNativeRelease'][0]['alive']=True
        with self.assertRaises(soak.RetentionReview):self.check(rows,result,mod_owned=dict(analysis,unreachable=2),min_sessions=4,process_seconds=48)
    def test_heap_paths_cuts_only_mod_owned_edges_in_a_real_heap_dump(self):
        import shutil,subprocess
        self.assertTrue(shutil.which('javac') and shutil.which('java'),'a JDK is required; this test must not be skipped')
        data=Path(__file__).with_name('testdata')
        with tempfile.TemporaryDirectory() as temporary:
            t=Path(temporary);classes=t/'classes';classes.mkdir()
            subprocess.run(['javac','-d',str(classes),*map(str,data.glob('*.java'))],check=True)
            mods=t/'mods';mods.mkdir()
            with zipfile.ZipFile(mods/'mod.jar','w') as jar:
                jar.write(classes/'ModStubs$ModHolder.class','RetentionFixture$ModHolder.class')
                jar.write(classes/'ModStubs$AddedMixin.class','mixin/AddedMixin.class')
            game=t/'game.jar'
            with zipfile.ZipFile(game,'w') as jar:
                jar.write(classes/'GameStubs$GameHolder.class','RetentionFixture$GameHolder.class')
                jar.write(classes/'GameStubs$AddedHolder.class','RetentionFixture$AddedHolder.class')
            verdicts={}
            for mode in ('mod','game','added'):
                dump=t/(mode+'.hprof')
                subprocess.run(['java','-cp',str(classes),'RetentionFixture',str(dump),mode],check=True)
                out=subprocess.run(['java',str(soak.HEAP_PATHS),str(dump),'RetentionFixture$Target','3','--mod-owned',str(mods),str(game)],check=True,capture_output=True,text=True).stdout
                verdicts[mode]=[line.split()[2] for line in out.splitlines() if line.startswith('VERDICT ')]
            self.assertEqual(['UNREACHABLE'],verdicts['mod'],'a field of a class shipped in a mod jar is mod-owned')
            self.assertEqual(['REACHABLE'],verdicts['game'],'a field the game jar declares stays a game edge even if a mod uses the same name')
            self.assertEqual(['UNREACHABLE'],verdicts['added'],'a field a mod declares and the game class lacks was added by a Mixin')
    def test_release_compatibility_requires_fresh_strict_consistent_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'report.json'
            with self.assertRaises(ValueError):soak.validate_compatibility(path,0)
            healthy=dict(policy='STRICT',confirmedRequired=0,findings=[],catalogFailures=[])
            path.write_text(json.dumps(healthy));self.assertEqual('STRICT',soak.validate_compatibility(path,0)['policy'])
            with self.assertRaises(ValueError):soak.validate_compatibility(path,path.stat().st_mtime_ns+1)
            for change in (dict(policy='CONTINUE'),dict(confirmedRequired=1),dict(findings=[dict(confidence='CONFIRMED',required=True)]),dict(catalogFailures=[dict(status='FAILED')])):
                path.write_text(json.dumps(healthy|change))
                with self.assertRaises(ValueError):soak.validate_compatibility(path,0)
    def test_detected_game_crash_stops_only_the_owned_group_and_cannot_wait_for_two_hours(self):
        with tempfile.TemporaryDirectory() as temporary:
            log=Path(temporary)/'client.log';log.write_text('#@!@# Game crashed! Crash report saved\n')
            class Child:
                pid=321
                returncode=None
                def poll(self):return self.returncode
                def wait(self):self.returncode=-9;return -9
            child=Child();clock=[0]
            # Model the POSIX driver's process-group API even when these self-tests run on Windows.
            with patch.object(soak.os,'killpg',create=True) as kill,patch.object(soak.signal,'SIGKILL',9,create=True),patch.object(soak.time,'monotonic',side_effect=lambda:clock[0]),patch.object(soak.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)):
                self.assertEqual(-9,soak.wait_for_client(child,log,7200))
                self.assertEqual([(321,soak.signal.SIGTERM),(321,soak.signal.SIGKILL)],[call.args for call in kill.call_args_list])
                self.assertLess(clock[0],7)
    def test_all_loaded_forever_does_not_prove_chunk_unload(self):
        rows,result=fixture()
        for row in rows:
            if row['type']=='sample':row['loaded']=[True]*6
        with self.assertRaises(ValueError):self.check(rows,result)
    def test_crash_signal_racing_normal_exit_preserves_exit_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            log=Path(temporary)/'client.log';log.write_text('#@!@# Game crashed!\n')
            class Child:
                pid=321
                returncode=None
                def poll(self):return self.returncode
            child=Child()
            def exited(pid,value):
                child.returncode=42
                raise ProcessLookupError()
            with patch.object(soak.os,'killpg',side_effect=exited,create=True),patch.object(soak.time,'sleep'):
                self.assertEqual(42,soak.wait_for_client(child,log,7200))
    def test_the_client_writes_its_caught_failures_into_the_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            caught=Path(temporary)/'evidence'/'caught.tsv'
            with patch.dict(soak.os.environ,{'FORBRIC_CAUGHT_FILE':str(Path(temporary)/'gate.tsv'),'KEEP':'1'}):
                environment=soak.client_environment(caught)
            self.assertEqual(str(caught),environment['FORBRIC_CAUGHT_FILE'],'the client JVM writes the run\'s own file, not the gate\'s')
            self.assertEqual('1',environment['KEEP'])
    def test_the_clients_caught_failures_reach_the_gates_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            caught=Path(temporary)/'caught.tsv';gate=Path(temporary)/'gate-m34-soak.1.tsv'
            gate.write_text('FAILURE\tWARN\tbuilder.Site:1\tbefore\tx\n')
            caught.write_text('FAILURE\tWARN\ta.B.c:7\t[Forbric/X] one\tjava.lang.IllegalStateException\n'
                              'FAILURE\tWARN\ta.B.c:7\t[Forbric/X] two\tjava.lang.IllegalStateException\n'
                              'FAILURE\tERROR\td.E.f:9\t[Forbric/Y] three\tjava.lang.RuntimeException\n'
                              'COUNT\tWARN\ta.B.c:7\t2')
            record=soak.forward_caught(caught,str(gate))
            self.assertEqual(['a.B.c:7','d.E.f:9'],record['failureSites'])
            self.assertEqual(soak.digest(caught),record['sha256'])
            lines=gate.read_text().splitlines()
            self.assertEqual('FAILURE\tWARN\tbuilder.Site:1\tbefore\tx',lines[0],'what the gate already had stays')
            self.assertEqual(caught.read_text().splitlines(),lines[1:])
            self.assertTrue(gate.read_text().endswith('\n'))
    def test_a_soak_started_without_a_gate_still_counts_what_the_client_caught(self):
        with tempfile.TemporaryDirectory() as temporary:
            caught=Path(temporary)/'caught.tsv';caught.write_text('FAILURE\tWARN\ta.B.c:7\tm\tx\n')
            self.assertEqual(['a.B.c:7'],soak.forward_caught(caught,None)['failureSites'])
            self.assertEqual([],soak.forward_caught(Path(temporary)/'never-written.tsv',None)['failureSites'])
            empty=Path(temporary)/'empty.tsv';empty.touch();gate=Path(temporary)/'gate.tsv';gate.touch()
            self.assertEqual([],soak.forward_caught(empty,str(gate))['failureSites'])
            self.assertEqual('',gate.read_text())
    def test_insufficient_measured_activity_is_rejected(self):
        with self.assertRaises(ValueError):self.check(*fixture(),seconds=60)
    def test_missing_finish_cannot_pass(self):
        rows,result=fixture();rows.pop()
        with self.assertRaises(ValueError):self.check(rows,result)
    def test_launcher_freezes_source_record_even_after_copying_native_library_paths(self):
        # Drive the real snapshot/manifest path, with only the external JVM replaced.
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);kernel=root/'kernel';staged=root/'staged';pack=root/'pack';mc=root/'minecraft'
            def put(path,data=b'fixture'):
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path
            def jar(path,entries):
                path.parent.mkdir(parents=True,exist_ok=True)
                with zipfile.ZipFile(path,'w') as output:
                    for key,value in entries.items():output.writestr(key,value)
                return path
            runtime=put(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar')
            jar(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar',{'bundled/forbric-kernel-runtime.jar':runtime.read_bytes()})
            base=jar(staged/'neoforge-patched/patched-mc-neoforge-26.2.jar',{'version.json':'{}'})
            put(staged/'neoforge-runtime/neoforge-runtime.jar')
            put(pack/'saves/ForbricTest/level.dat');put(pack/'mods/probe.jar');put(pack/'options.txt')
            metadata={'id':'26.2','assetIndex':{'id':'test'},'libraries':[{'name':'group:test:1','downloads':{'artifact':{'path':'test.jar'}}}]}
            put(mc/'versions/26.2/26.2.json',json.dumps(metadata).encode());put(mc/'libraries/test.jar');put(mc/'assets/indexes/test.json',b'{}');put(mc/'versions/26.2/26.2-natives/probe.dylib')
            cp=put(root/'boot-classpath.txt',str(put(root/'dependency.jar')).encode())
            args=argparse.Namespace(kernel=str(kernel),staged=str(staged),fixture=str(pack),minecraft=str(mc),world_source=None,base=str(base),neo=None,natives=None,boot_classpath=str(cp),seconds=1,control=True,sessions=2,dwell_ticks=20,between_seconds=0,settle_seconds=0,timeout=60,policy='strict',heap='1G',java='unused-test-java',native_reference=None)
            source={'root':str(root),'commit':'test','sha256':'source-content','files':{},'dirty':False}
            manifests=[];commands=[]
            class Child:
                pid=123
                returncode=0
                def __init__(self,command,cwd,**kwargs):
                    commands.append(command)
                    evidence=cwd/'evidence';manifest=json.loads((evidence/'manifest.json').read_text());manifests.append(manifest)
                    # What the kernel's Caught does in the real JVM: append a failure site to the file it was handed.
                    with open(kwargs['env']['FORBRIC_CAUGHT_FILE'],'a') as sink:sink.write('FAILURE\tWARN\tnet.forbric.Probe.site:1\t[Forbric/Probe] injected\tjava.lang.IllegalStateException\n')
                    rows,result=fixture()
                    for row in rows:row['nonce']=manifest['nonce']
                    result['nonce']=manifest['nonce']
                    (evidence/'telemetry.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
                    (evidence/'controller-result.json').write_text(json.dumps(result))
                def wait(self,timeout=None):return 0
                def poll(self):return 0
            gate=put(root/'gate-m34-soak.1.tsv',b'')
            reference=put(root/'reference/vanilla-reference.jar',b'sidecar')
            with patch.object(soak,'source_record',return_value=source),patch.object(soak,'vanilla_reference',return_value=reference),patch.object(soak.subprocess,'Popen',Child),patch.object(soak,'wait_for_client',return_value=0),patch.object(soak.time,'monotonic',side_effect=[0,24]),patch.dict(soak.os.environ,{'FORBRIC_CAUGHT_FILE':str(gate)}):
                self.assertEqual(0,soak.launch(args))
            self.assertEqual(source,manifests[0]['source'])
            # What the copied world's save says of the weather the first session joins into (here: it keeps no such file).
            self.assertIn('no such file',manifests[0]['sourceWorldWeather']['problem'])
            # NeoForge's patched game, frozen, and its one carrier: no MinecraftForge carrier is owned any more.
            command=commands[0]
            self.assertEqual(1,command.count('--gameJar'));self.assertEqual(1,command.count('--runtimeJar'))
            self.assertEqual('base.jar',Path(command[command.index('--gameJar')+1]).name)
            self.assertEqual('neo.jar',Path(command[command.index('--runtimeJar')+1]).name)
            # The base's sidecar, frozen with the rest and handed over under its own flag.
            self.assertEqual(1,command.count('--nativeReference'))
            self.assertEqual('vanilla-reference.jar',Path(command[command.index('--nativeReference')+1]).name)
            self.assertIn(str(reference.resolve()),[record['source'] for record in manifests[0]['artifacts']])
            self.assertIn('generated from frozen base.jar/version.json',[record['source'] for record in manifests[0]['artifacts']])
            acceptance=json.loads((kernel/'build/verification/m34-soak/last-control.json').read_text())['acceptance']
            self.assertFalse(acceptance['releaseAccepted'])
            # What the rows said of the player and the weather is in the acceptance, whatever the verdict.
            self.assertEqual(['survival'],acceptance['conditions']['gameTypes']);self.assertEqual(0,acceptance['conditions']['rainingRows'])
            # A control run leans on no native reproduction, so none is read or measured for it.
            self.assertEqual([],manifests[0]['nativeRetentionEvidence'])
            # The client's failure is in the run's evidence AND in the gate's file, which the gate's CAUGHT line reports.
            self.assertEqual(['net.forbric.Probe.site:1'],acceptance['caught']['failureSites'])
            self.assertIn('[Forbric/Probe] injected',Path(acceptance['caught']['file']).read_text())
            self.assertIn('[Forbric/Probe] injected',gate.read_text())
    def test_a_release_launch_obtains_the_native_evidence_for_the_jars_it_froze(self):
        # launch() itself, a release run: the reviewed root in the pack goes through ensure_native_evidence, with the
        # digests of the kernel jar, base and carrier it has just frozen, before any client starts; what that returns
        # is the manifest's record. (The fake client then fails release validation, which is not this test's subject.)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);kernel=root/'kernel';staged=root/'staged';pack=root/'pack';mc=root/'minecraft'
            def put(path,data=b'fixture'):
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path
            def jar(path,entries):
                path.parent.mkdir(parents=True,exist_ok=True)
                with zipfile.ZipFile(path,'w') as output:
                    for key,value in entries.items():output.writestr(key,value)
                return path
            runtime=put(kernel/'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar')
            boot=jar(kernel/'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar',{'bundled/forbric-kernel-runtime.jar':runtime.read_bytes()})
            base=jar(staged/'neoforge-patched/patched-mc-neoforge-26.2.jar',{'version.json':'{}'})
            carrier=put(staged/'neoforge-runtime/neoforge-runtime.jar',b'carrier')
            put(pack/'saves/ForbricTest/level.dat');put(pack/'mods/probe.jar')
            metadata={'id':'26.2','assetIndex':{'id':'test'},'libraries':[]}
            put(mc/'versions/26.2/26.2.json',json.dumps(metadata).encode());put(mc/'assets/indexes/test.json',b'{}');put(mc/'versions/26.2/26.2-natives/probe.dylib')
            cp=put(root/'boot-classpath.txt',str(put(root/'dependency.jar')).encode())
            def arguments(control):
                return argparse.Namespace(kernel=str(kernel),staged=str(staged),fixture=str(pack),minecraft=str(mc),world_source=None,base=None,neo=None,natives=None,boot_classpath=str(cp),seconds=1 if control else 7200,control=control,sessions=3,dwell_ticks=20,between_seconds=0,settle_seconds=0,timeout=60,policy='strict',heap='1G',java='unused-test-java',native_reference=None)
            source={'root':str(root),'commit':'test','sha256':'source-content','files':{},'dirty':False}
            started=[];asked=[]
            class Child:
                pid=123
                returncode=0
                def __init__(self,command,cwd,**kwargs):
                    started.append(json.loads((cwd/'evidence/manifest.json').read_text()))
                def wait(self,timeout=None):return 0
                def poll(self):return 0
            def ensure(kernel_given,entry,launched,log):
                self.assertEqual([],started,'the evidence is settled before any client starts')
                asked.append((Path(kernel_given),entry,launched,Path(log)));return dict(root=entry['root'],evidence='comparison.json',sha256='e'*64,measuredForThisRun=True)
            reference=put(root/'reference/vanilla-reference.jar',b'sidecar')
            with patch.object(soak,'source_record',return_value=source),patch.object(soak,'vanilla_reference',return_value=reference),patch.object(soak.subprocess,'Popen',Child),patch.object(soak,'wait_for_client',return_value=0),patch.object(soak,'native_retention_roots',return_value=[self.ENTRY]),patch.object(soak,'ensure_native_evidence',side_effect=ensure),patch('builtins.print'):
                self.assertEqual(1,soak.launch(arguments(control=False)),'the fake client wrote no telemetry: not a pass')
                self.assertEqual(1,len(asked));given,entry,launched,log=asked[0]
                self.assertEqual((kernel.resolve(),self.ENTRY),(given,entry))
                self.assertEqual({'kernel jar':soak.digest(boot),'game base':soak.digest(base),'NeoForge carrier':soak.digest(carrier)},launched)
                self.assertEqual('evidence',log.parent.name,'the measurement\'s output is kept with the run\'s evidence')
                self.assertEqual([dict(root=self.ROOT,evidence='comparison.json',sha256='e'*64,measuredForThisRun=True)],started[0]['nativeRetentionEvidence'])
                # A control run leans on no native reproduction: nothing is read, and no server is started for it.
                self.assertEqual(1,soak.launch(arguments(control=True)));self.assertEqual(1,len(asked))
                self.assertEqual([],started[1]['nativeRetentionEvidence'])
    def test_no_vanilla_reference_stops_the_soak_instead_of_launching_without_one(self):
        # NeoForge's patched game embeds no vanilla index: a client launched without --nativeReference has no FABRIC
        # references at all, so the soak must stop here rather than soak a kernel no launcher runs.
        class Result:
            def __init__(self,code,out='',err=''):self.returncode,self.stdout,self.stderr=code,out,err
        for result in (Result(1,err='no sidecar for this base'),Result(0,out='')):
            with patch.object(soak.subprocess,'run',return_value=result):
                with self.assertRaises(SystemExit) as stopped:
                    soak.vanilla_reference(Path('/kernel'),Path('/stage/base.jar'),Path('/mc'))
            self.assertIn('FATAL: no vanilla reference',str(stopped.exception))
        with patch.object(soak.subprocess,'run',return_value=Result(0,out='/build/vanilla-reference.jar\n')):
            self.assertEqual(Path('/build/vanilla-reference.jar'),soak.vanilla_reference(Path('/kernel'),Path('/stage/base.jar'),Path('/mc')))
if __name__=='__main__':unittest.main()
