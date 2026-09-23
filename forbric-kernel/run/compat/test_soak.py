#!/usr/bin/env python3
"""Negative controls for M34's independent activity verifier; no game process is launched."""
import copy
import importlib.util
from pathlib import Path
import unittest
import argparse
import json
import tempfile
import zipfile
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('soak_run',Path(__file__).with_name('soak-run.py'))
soak=importlib.util.module_from_spec(spec);spec.loader.exec_module(soak)

def fixture(sessions=2):
    rows=[];nonce='test-nonce';nano=0
    def add(kind,**fields):
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
    def test_release_rereads_the_named_native_reproduction(self):
        with tempfile.TemporaryDirectory() as temporary:
            kernel=Path(temporary);(kernel/'build/retention-control').mkdir(parents=True)
            with self.assertRaises(ValueError):soak.verify_native_evidence(kernel,self.ENTRY)
            arms=[]
            for engine in ('native','forbric'):
                inputs=kernel/f'{engine}.json';inputs.write_text(json.dumps(dict(modSet=[dict(sha256='ab'*32)])))
                arms.append(dict(engine=engine,nativeRetentionReproduced=True,proof=dict(root=self.ROOT),inputs=dict(path=str(inputs),sha256=soak.digest(inputs))))
            comparison=kernel/self.ENTRY['nativeEvidence']
            comparison.write_text(json.dumps(dict(sameModHashes=True,arms=arms)))
            self.assertEqual(self.ROOT,soak.verify_native_evidence(kernel,self.ENTRY)['root'])
            for broken in (dict(sameModHashes=False,arms=arms),dict(sameModHashes=True,arms=arms[:1]),
                           dict(sameModHashes=True,arms=[dict(arms[0],nativeRetentionReproduced=False),arms[1]])):
                comparison.write_text(json.dumps(broken))
                with self.assertRaises(ValueError):soak.verify_native_evidence(kernel,self.ENTRY)
            comparison.write_text(json.dumps(dict(sameModHashes=True,arms=arms)))
            with self.assertRaises(ValueError):soak.verify_native_evidence(kernel,dict(self.ENTRY,modJarSha256='cd'*32))
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
            with patch.object(soak.os,'killpg') as kill,patch.object(soak.time,'monotonic',side_effect=lambda:clock[0]),patch.object(soak.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)):
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
            with patch.object(soak.os,'killpg',side_effect=exited),patch.object(soak.time,'sleep'):
                self.assertEqual(42,soak.wait_for_client(child,log,7200))
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
            merged=jar(staged/'merged-base/patched-mc-merged-26.2.jar',{'version.json':'{}'})
            put(staged/'merged-base/forge-runtime-interop.jar');put(staged/'neoforge-runtime/neoforge-runtime.jar')
            put(pack/'saves/ForbricTest/level.dat');put(pack/'mods/probe.jar');put(pack/'options.txt')
            metadata={'id':'26.2','assetIndex':{'id':'test'},'libraries':[{'name':'group:test:1','downloads':{'artifact':{'path':'test.jar'}}}]}
            put(mc/'versions/26.2/26.2.json',json.dumps(metadata).encode());put(mc/'libraries/test.jar');put(mc/'assets/indexes/test.json',b'{}');put(mc/'versions/26.2/26.2-natives/probe.dylib')
            cp=put(root/'boot-classpath.txt',str(put(root/'dependency.jar')).encode())
            args=argparse.Namespace(kernel=str(kernel),staged=str(staged),fixture=str(pack),minecraft=str(mc),world_source=None,merged=str(merged),forge=None,neo=None,natives=None,boot_classpath=str(cp),seconds=1,control=True,sessions=2,dwell_ticks=20,between_seconds=0,settle_seconds=0,timeout=60,policy='strict',heap='1G',java='unused-test-java')
            source={'root':str(root),'commit':'test','sha256':'source-content','files':{},'dirty':False}
            manifests=[]
            class Child:
                pid=123
                returncode=0
                def __init__(self,command,cwd,**kwargs):
                    evidence=cwd/'evidence';manifest=json.loads((evidence/'manifest.json').read_text());manifests.append(manifest)
                    rows,result=fixture()
                    for row in rows:row['nonce']=manifest['nonce']
                    result['nonce']=manifest['nonce']
                    (evidence/'telemetry.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
                    (evidence/'controller-result.json').write_text(json.dumps(result))
                def wait(self,timeout=None):return 0
                def poll(self):return 0
            with patch.object(soak,'source_record',return_value=source),patch.object(soak.subprocess,'Popen',Child),patch.object(soak,'wait_for_client',return_value=0),patch.object(soak.time,'monotonic',side_effect=[0,24]):
                self.assertEqual(0,soak.launch(args))
            self.assertEqual(source,manifests[0]['source'])
            self.assertFalse(json.loads((kernel/'build/verification/m34-soak/last-control.json').read_text())['acceptance']['releaseAccepted'])
if __name__=='__main__':unittest.main()
