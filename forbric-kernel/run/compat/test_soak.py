#!/usr/bin/env python3
"""Negative controls for M34's independent activity verifier; no game process is launched."""
import copy
import importlib.util
from pathlib import Path
import unittest
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
    result=dict(nonce=nonce,pid=123,status='CONTROL_PASS',actualTicks=480,activeNanos=24_000_000_000,visits=[4]*6,unloads=[4]*5+[2],reloads=[2]*6,oldServers=[dict(alive=False)])
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
        rows,result=fixture();result['oldServers']=[dict(alive=True)]
        with self.assertRaises(ValueError):self.check(rows,result)
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
if __name__=='__main__':unittest.main()
