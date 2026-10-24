"""gate-m57's judge (breath-drown.py), on synthetic results and on the committed reference.

The gate's verdicts are only as good as three things this file holds still: the rows the judge expects are the rows
the canary plays, the committed native reference measures every row the judge compares against it, and each phase's
verdict is exact both ways — an unexpected failure is red, and so is a predicted failure that did not happen, and so is
a predicted failure for another cause than the one the phase switches off.
"""
import contextlib
import copy
import importlib.util
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

COMPAT = Path(__file__).resolve().parent
KERNEL = COMPAT.parents[1]
_spec = importlib.util.spec_from_file_location('breath_drown', COMPAT / 'breath-drown.py')
bd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bd)

ROW_NAME = re.compile(r'(?:pig|bucket)\("([a-z0-9-]+)"|new Scenario\("([a-z0-9-]+)"')


def planned_rows():
    plan = (KERNEL / 'canary/forge-breath-drown/common/src/Core.java').read_text(encoding='utf-8')
    body = plan[plan.index('private static void plan()'):plan.index('public static void tick(')]
    return [a or b for a, b in ROW_NAME.findall(body)]


def view(count, notes, at=None):
    v = {'count': count, 'notes': notes}
    if at is not None: v['at'] = at
    return v


def reference():
    """A small reference: every EXPECT row measured by each family it names, with drown and breathe views, a hurt view
    for MinecraftForge's drowning rows, and the same random draws everywhere (so every 'rng-as' holds natively)."""
    families = {}
    for family in (bd.F, bd.N):
        rows = {}
        for name, (expect, checks) in bd.EXPECT.items():
            if family not in expect: rows[name] = {'skipped': 'no block'} if 'murk' in name or 'bucket' in name else {
                'air': [1], 'health': [1.0], 'rng': [1], 'views': {family: {}}}; continue
            if name.endswith('bucket'): rows[name] = {'result': ['place=' + name], 'views': {family: {}}}
            else:
                views = {'drown': view(1, [f'{family} drown x'], [1]), 'breathe': view(2, [f'{family} breathe x'])}
                if family == bd.F and 'drown' in name: views['hurt'] = view(1, ['forge hurt x'], [1])
                for c in checks:
                    if c[0] == 'none' and c[1] == family: views.pop(c[2], None)
                rows[name] = {'air': [5, 4], 'health': [10.0, 8.0], 'rng': [1, 3], 'views': {family: views}}
        families[family] = {'rows': rows}
    return {'canarySources': 'x', 'families': families}


def mixed_result(ref):
    """What Forbric reports when every row is as native and every composition check holds."""
    rows = []
    for name, (expect, checks) in bd.EXPECT.items():
        decider = sorted(expect)[0]
        native = ref['families'][decider]['rows'][name]
        row = {'name': name, **{k: copy.deepcopy(native[k]) for k in bd.STORED if k in native}, 'views': {}, 'order': {},
               'first': f'blockAtEye={bd.FORGE_MURK} y=100.00' if 'forgemurk' in name else 'blockAtEye=minecraft:water y=100.00'}
        for family in expect: row['views'][family] = copy.deepcopy(ref['families'][family]['rows'][name]['views'][family])
        for check in checks:
            if check[0] in ('sees', 'asked'):
                text = check[3] if check[0] == 'sees' else ''
                kinds = row['views'].setdefault(check[1], {})
                if check[2] in kinds: kinds[check[2]]['notes'] = [f'{check[1]} {text} -> x']
                else: kinds[check[2]] = view(1, [f'{check[1]} {text} -> x'], [1])
        rows.append(row)
    return {'driver': 'neo', 'error': None, 'rows': rows}


class PlanAndTable(unittest.TestCase):
    def test_the_judge_expects_exactly_the_rows_the_canary_plays(self):
        self.assertEqual(sorted(planned_rows()), sorted(bd.EXPECT))

    def test_every_phase_names_only_rows_the_judge_knows(self):
        for rows in (bd.FORGE_LIQUID_ROWS, bd.FORGE_LIQUID_BODY_ROWS, bd.HANDED_NEOFORGES_ANSWER):
            self.assertLessEqual(rows, set(bd.EXPECT))
        self.assertLessEqual(bd.FORGE_LIQUID_BODY_ROWS, bd.FORGE_LIQUID_ROWS)
        for name, (_, checks) in bd.EXPECT.items():
            for check in checks:
                if check[0] == 'rng-as': self.assertIn(check[1], bd.EXPECT, name)

    def test_the_rows_in_minecraftforges_liquid_are_the_rows_the_plan_puts_in_it(self):
        plan = (KERNEL / 'canary/forge-breath-drown/common/src/Core.java').read_text(encoding='utf-8')
        in_murk = set(re.findall(r'pig\("([a-z0-9-]+)", forgeMurk', plan)) | set(re.findall(r'bucket\("([a-z0-9-]+)", forgeMurk', plan))
        self.assertEqual(in_murk, bd.FORGE_LIQUID_ROWS)
        self.assertEqual({n for n in in_murk if not n.endswith('bucket')}, bd.FORGE_LIQUID_BODY_ROWS)

    def test_the_gate_plays_every_row_and_runs_every_phase(self):
        gate = (KERNEL / 'run/gate-m57-forge-breath-drown.sh').read_text(encoding='utf-8')
        self.assertIn(f'DONE rows={len(bd.EXPECT)} error=null', gate)
        for phase in bd.PHASES:
            self.assertRegex(gate, rf'\nrun_server {phase} ', phase)
            self.assertRegex(gate, rf'\njudge {phase} ', phase)

    def test_the_committed_reference_measures_every_row_it_is_compared_on(self):
        ref = json.loads(bd.REFERENCE.read_text(encoding='utf-8'))
        for name, (expect, _) in bd.EXPECT.items():
            for family in expect:
                row = ref['families'][family]['rows'].get(name)
                self.assertIsNotNone(row, (family, name))
                self.assertNotIn('skipped', row, (family, name))
        self.assertEqual(ref['properties'], bd.PROPERTIES)
        self.assertGreaterEqual(min(f['runs'] for f in ref['families'].values()), 3)
        for name, (expect, checks) in bd.EXPECT.items():
            for check in checks:
                if check[0] != 'rng-as': continue
                for family in expect:
                    rows = ref['families'][family]['rows']
                    self.assertEqual(rows[name].get('rng'), rows[check[1]].get('rng'), (family, name, check[1]))
                    self.assertTrue(rows[name].get('rng'), (family, name))

    def test_the_committed_reference_belongs_to_the_canary_sources_in_this_tree(self):
        self.assertEqual(json.loads(bd.REFERENCE.read_text(encoding='utf-8'))['canarySources'], bd.sources_digest())

    def test_the_source_digest_does_not_see_line_endings(self):
        # A Windows checkout may hold the canary with CRLF; the reference must still be the one for these sources.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for source in sorted(p for p in bd.CANARY.rglob('*') if p.is_file() and p.suffix in ('.java', '.toml')):
                target = root / source.relative_to(bd.CANARY)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n'))
            self.assertEqual(bd.sources_digest(root), bd.sources_digest())
            (root / 'common/src/Core.java').write_bytes((root / 'common/src/Core.java').read_bytes() + b'//\r\n')
            self.assertNotEqual(bd.sources_digest(root), bd.sources_digest())

    def test_the_gate_writes_the_same_server_properties_as_the_reference_runs(self):
        gate = (KERNEL / 'run/gate-m57-forge-breath-drown.sh').read_text(encoding='utf-8')
        self.assertIn('"$TOOL" properties >> "$SERVER_DIR/server.properties"', gate)
        self.assertIn('level-seed=forbrickernel', bd.PROPERTIES)   # what seed_server_properties writes first


class Judge(unittest.TestCase):
    def setUp(self):
        self.ref = reference()
        self.result = mixed_result(self.ref)

    def failed(self, result=None):
        return {n for n, r in bd.judge_rows(self.ref, result or self.result).items() if r}

    def row(self, name):
        return next(r for r in self.result['rows'] if r['name'] == name)

    def test_a_result_as_native_passes_every_row(self):
        self.assertEqual(self.failed(), set())

    def test_a_curve_off_by_one_tick_fails_its_row(self):
        self.row('drown-f-damage3')['health'] = [10.0, 7.0]
        self.assertEqual(self.failed(), {'drown-f-damage3'})

    def test_a_listener_view_that_differs_fails_its_row_unless_it_was_handed_neoforges_answer(self):
        self.row('drown-f-cancel')['views'][bd.F]['drown']['notes'] = ['forge drown other']
        handed = sorted(bd.HANDED_NEOFORGES_ANSWER)[0]
        self.row(handed)['views'][bd.F]['drown']['notes'] = ['forge drown other', *self.row(handed)['views'][bd.F]['drown']['notes']]
        self.assertEqual(self.failed(), {'drown-f-cancel'})
        self.row(handed)['views'][bd.F]['drown']['count'] = 2
        self.assertEqual(self.failed(), {'drown-f-cancel', handed})

    def test_a_listener_neoforge_stopped_must_not_have_been_asked(self):
        self.row('drown-n-cancel')['views'][bd.F] = {'drown': view(1, ['forge drown'], [1])}
        self.assertEqual(self.failed(), {'drown-n-cancel'})

    def test_minecraftforge_must_see_what_neoforge_handed_it(self):
        self.row('drown-n-damage3')['views'][bd.F]['drown']['notes'] = ['forge drown drowning=true damage=2.0 ']
        self.assertEqual(self.failed(), {'drown-n-damage3'})

    def test_minecraftforge_answering_before_neoforge_fails(self):
        self.row('drown-read')['order'] = {'drown': 'forge,neo'}
        self.assertEqual(self.failed(), {'drown-read'})
        self.row('drown-read')['order'] = {'drown': 'neo,forge'}
        self.assertEqual(self.failed(), set())

    def test_a_row_that_must_draw_what_another_draws_fails_when_it_does_not(self):
        name, other = next((n, c[1]) for n, (_, checks) in bd.EXPECT.items() for c in checks if c[0] == 'rng-as')
        self.row(name)['rng'] = [1, 99]
        verdicts = bd.judge_rows(self.ref, self.result)
        self.assertEqual({n for n, r in verdicts.items() if r}, {name})
        self.assertIn('tick 1: 99 vs 3', ' '.join(verdicts[name]))
        # The random draws are judged only there: another row's differing draws are not a failure.
        self.row('drown-read')['rng'] = [7, 7]
        self.assertEqual(self.failed(), {name})

    def test_a_row_that_must_draw_what_another_draws_needs_the_reference_to_show_it(self):
        name, other = next((n, c[1]) for n, (_, checks) in bd.EXPECT.items() for c in checks if c[0] == 'rng-as')
        self.ref['families'][bd.F]['rows'][name]['rng'] = [1, 99]
        verdicts = bd.judge_rows(self.ref, self.result)
        self.assertIn('reference does not show', ' '.join(verdicts[name]))

    def test_a_skipped_or_missing_row_fails(self):
        self.result['rows'] = [r for r in self.result['rows'] if r['name'] != 'neo-bucket']
        bucket = self.row('forge-bucket')
        bucket.clear(); bucket.update({'name': 'forge-bucket', 'skipped': 'setup failed'})
        self.assertEqual(self.failed(), {'neo-bucket', 'forge-bucket'})


class Phases(unittest.TestCase):
    def run_judge(self, ref, result, phase):
        with tempfile.TemporaryDirectory() as tmp:
            r, o = Path(tmp) / 'ref.json', Path(tmp) / 'result.json'
            r.write_text(json.dumps(ref), encoding='utf-8'); o.write_text(json.dumps(result), encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = bd.main(['judge', '--reference', str(r), '--result', str(o), '--phase', phase])
        return code, out.getvalue()

    def test_bridges_off_predicts_every_row_a_minecraftforge_listener_decides_or_sees(self):
        ref = reference()
        predicted = bd.predicted_failures(ref, 'bridges-off')
        for name, (expect, checks) in bd.EXPECT.items():
            sees = any(c[0] == 'sees' and c[1] == bd.F for c in checks)
            forge_events = bd.F in expect and any(ref['families'][bd.F]['rows'][name]['views'][bd.F].values())
            self.assertEqual(name in predicted, sees or forge_events, name)

    def test_seams_off_predicts_every_row_judged_on_a_minecraftforge_hurt_listener(self):
        ref = reference()
        predicted = bd.predicted_failures(ref, 'seams-off')
        self.assertTrue(predicted)
        for name, (expect, _) in bd.EXPECT.items():
            hurt = bd.F in expect and 'hurt' in ref['families'][bd.F]['rows'][name]['views'][bd.F]
            self.assertEqual(name in predicted, hurt, name)

    NPE = ('setup failed: java.lang.IllegalStateException: the forbricbreathforge:murk pool did not build: '
           'java.lang.NullPointerException: Cannot invoke "FlowingFluid.getTickDelay(LevelReader)" because "this.fluid" is null')

    def getters_off(self, good, skip):
        broken = copy.deepcopy(good)
        broken['rows'] = [r if r['name'] not in bd.FORGE_LIQUID_ROWS else {'name': r['name'], 'skipped': skip} for r in broken['rows']]
        return broken

    def test_a_phase_is_exact_both_ways(self):
        ref = reference()
        good = mixed_result(ref)
        self.assertEqual(self.run_judge(ref, good, 'positive')[0], 0)
        # getters-off predicts the liquid rows: all of them failing on the missing fluid passes the phase...
        broken = self.getters_off(good, self.NPE)
        self.assertEqual(self.run_judge(ref, broken, 'getters-off')[0], 0)
        # ...one of them holding is red, and so is the same result judged as positive.
        held = copy.deepcopy(broken)
        held['rows'] = [r for r in held['rows'] if r['name'] != 'forge-bucket'] + [next(r for r in good['rows'] if r['name'] == 'forge-bucket')]
        self.assertEqual(self.run_judge(ref, held, 'getters-off')[0], 1)
        self.assertEqual(self.run_judge(ref, broken, 'positive')[0], 1)

    def test_a_predicted_failure_for_another_cause_is_red(self):
        ref = reference()
        good = mixed_result(ref)
        # getters-off: the right rows, but not because the liquid block had no fluid.
        code, out = self.run_judge(ref, self.getters_off(good, 'setup failed: no block forbricbreathforge:murk'), 'getters-off')
        self.assertEqual(code, 1)
        self.assertIn('failed for another cause', out)
        # bridges-off: every predicted row fails on its air alone, its MinecraftForge breathe and drown views intact.
        predicted = bd.predicted_failures(ref, 'bridges-off')
        wrong = copy.deepcopy(good)
        for r in wrong['rows']:
            if r['name'] in predicted and 'air' in r: r['air'] = [0, 0]
            elif r['name'] in predicted: r['result'] = ['other']
        code, out = self.run_judge(ref, wrong, 'bridges-off')
        self.assertEqual(code, 1)
        self.assertIn('failed for another cause', out)
        # ...and the same rows without MinecraftForge's breathe and drown views pass it.
        right = copy.deepcopy(good)
        for r in right['rows']:
            for kind in bd.BRIDGED_KINDS: r['views'].get(bd.F, {}).pop(kind, None)
            if r['name'] in predicted and not any(r['views'].get(bd.F, {}).get(k) for k in bd.BRIDGED_KINDS):
                r.setdefault('views', {}).setdefault(bd.F, {})
            for c in bd.EXPECT[r['name']][1]:
                if c[0] == 'sees' and c[1] == bd.F: r['views'][bd.F].pop(c[2], None)
        self.assertEqual(self.run_judge(ref, right, 'bridges-off')[0], 0, self.run_judge(ref, right, 'bridges-off')[1][-600:])

    def test_own_type_off_needs_the_body_in_minecraftforges_liquid(self):
        ref = reference()
        good = mixed_result(ref)
        moved = copy.deepcopy(good)
        for r in moved['rows']:
            if r['name'] in bd.FORGE_LIQUID_BODY_ROWS: r['air'] = [9, 13]
        self.assertEqual(self.run_judge(ref, moved, 'own-type-off')[0], 0)
        for r in moved['rows']:
            if r['name'] in bd.FORGE_LIQUID_BODY_ROWS: r['first'] = 'blockAtEye=minecraft:air y=100.00'
        code, out = self.run_judge(ref, moved, 'own-type-off')
        self.assertEqual(code, 1)
        self.assertIn('not because the body was in', out)

    def test_a_probe_that_stopped_on_an_error_is_red(self):
        ref = reference()
        result = mixed_result(ref); result['error'] = 'java.lang.IllegalStateException: boom'
        code, out = self.run_judge(ref, result, 'positive')
        self.assertEqual(code, 1)
        self.assertIn('stopped on', out)

    def test_check_sources_names_a_stale_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = Path(tmp) / 'ref.json'
            r.write_text(json.dumps({'canarySources': '0' * 64}), encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(bd.main(['check-sources', '--reference', str(r)]), 1)
            self.assertIn('regenerate', out.getvalue())


if __name__ == '__main__':
    unittest.main()
