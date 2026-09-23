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

def fixture():
    rows=[];nonce='test-nonce';nano=0
    def add(kind,**fields):
        rows.append(dict(type=kind,nonce=nonce,pid=123,sequence=len(rows)+1,**fields))
    add('start',requiredSeconds=1,releaseEligible=False)
    for server in (1,2):
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
    result=dict(nonce=nonce,pid=123,status='CONTROL_PASS',actualTicks=480,activeNanos=24_000_000_000,visits=[4]*6,unloads=[4]*5+[2],reloads=[2]*6,oldServers=[dict(server=i,alive=False,stopped=True) for i in (1,2)])
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
    def test_all_loaded_forever_does_not_prove_chunk_unload(self):
        rows,result=fixture()
        for row in rows:
            if row['type']=='sample':row['loaded']=[True]*6
        with self.assertRaises(ValueError):self.check(rows,result)
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
                def __init__(self,command,cwd,**kwargs):
                    evidence=cwd/'evidence';manifest=json.loads((evidence/'manifest.json').read_text());manifests.append(manifest)
                    rows,result=fixture()
                    for row in rows:row['nonce']=manifest['nonce']
                    result['nonce']=manifest['nonce']
                    (evidence/'telemetry.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
                    (evidence/'controller-result.json').write_text(json.dumps(result))
                def wait(self,timeout=None):return 0
            with patch.object(soak,'source_record',return_value=source),patch.object(soak.subprocess,'Popen',Child),patch.object(soak.time,'monotonic',side_effect=[0,24]):
                self.assertEqual(0,soak.launch(args))
            self.assertEqual(source,manifests[0]['source'])
            self.assertFalse(json.loads((kernel/'build/verification/m34-soak/last-control.json').read_text())['acceptance']['releaseAccepted'])
if __name__=='__main__':unittest.main()
