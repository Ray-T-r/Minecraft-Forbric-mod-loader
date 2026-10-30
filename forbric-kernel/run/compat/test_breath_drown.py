"""gate-m57's judge (breath-drown.py), on synthetic results and on the committed reference.

The gate's verdicts are only as good as three things this file holds still: the rows the judge expects are the rows
the canary plays, the committed native reference measures every row the judge compares against it, and each phase's
verdict is exact both ways — an unexpected failure is red, and so is a predicted failure that did not happen, and so is
a predicted failure for another cause than the one the phase switches off.

Since Stage 3 of dropping MinecraftForge the MinecraftForge canary is refused, so every row is judged against native
NeoForge; what is held here besides is that a MinecraftForge listener hearing a body, or one of its liquids being there,
is red, and that the documented vanilla-refill difference is pinned both ways.
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
LIQUID_SKIP = 'no block forbricbreathforge:murk'


def planned_rows():
    plan = (KERNEL / 'canary/forge-breath-drown/common/src/Core.java').read_text(encoding='utf-8')
    body = plan[plan.index('private static void plan()'):plan.index('public static void tick(')]
    return [a or b for a, b in ROW_NAME.findall(body)]


def view(count, notes, at=None):
    v = {'count': count, 'notes': notes}
    if at is not None: v['at'] = at
    return v


def refill(name):
    """The row's 'vanilla-refill' check, or None."""
    return next((c for c in bd.EXPECT[name] if c[0] == 'vanilla-refill'), None)


def reference():
    """A small reference: every EXPECT row measured by native NeoForge with drown and breathe views (its liquid rows
    skipped there), and by native MinecraftForge; a vanilla-refill row's NeoForge breathe listener is handed nothing
    natively, and a body that keeps the refill holds its air on native NeoForge and refills on native MinecraftForge."""
    families = {bd.F: {'rows': {}}, bd.N: {'rows': {}}}
    for name, checks in bd.EXPECT.items():
        families[bd.F]['rows'][name] = {'air': [9, 13], 'health': [10.0, 10.0], 'rng': [1, 3], 'views': {bd.F: {'breathe': view(2, ['forge x'])}}}
        if ('refused',) in checks: families[bd.N]['rows'][name] = {'skipped': LIQUID_SKIP}; continue
        if name.endswith('bucket'): families[bd.N]['rows'][name] = {'result': ['place=' + name], 'views': {bd.N: {}}}; continue
        views = {'drown': view(1, ['neo drown x'], [1]), 'breathe': view(2, ['neo breathe x'])}
        for c in checks:
            if c[0] == 'none' and c[1] == bd.N: views.pop(c[2], None)
        row = {'air': [5, 4], 'health': [10.0, 8.0], 'rng': [1, 3], 'views': {bd.N: views}}
        if refill(name):
            views['breathe'] = view(2, ['neo can=true consume=1 refill=0 -> can=true consume=1 refill=0'])
            row['air'] = [5, 5]
        families[bd.N]['rows'][name] = row
    return {'canarySources': 'x', 'families': families}


def neo_result(ref):
    """What Forbric reports when every row is as native NeoForge and the vanilla-refill rows show the owner's refill."""
    rows = []
    for name, checks in bd.EXPECT.items():
        native = ref['families'][bd.N]['rows'][name]
        if 'skipped' in native: rows.append({'name': name, 'skipped': native['skipped']}); continue
        row = {'name': name, **{k: copy.deepcopy(native[k]) for k in bd.STORED if k in native},
               'views': copy.deepcopy(native['views']), 'order': {}, 'first': 'blockAtEye=minecraft:water y=100.00'}
        if refill(name):
            row['views'][bd.N]['breathe']['notes'] = ['neo can=true consume=1 refill=4 -> can=true consume=1 refill=0']
            if refill(name)[1]: row['air'] = copy.deepcopy(ref['families'][bd.F]['rows'][name]['air'])
        rows.append(row)
    return {'driver': 'neo', 'error': None, 'rows': rows}


def neoforges_own_refill(ref, result):
    """The same result with the breathing hook keeping NeoForge's own refill: the vanilla-refill rows as native NeoForge."""
    out = copy.deepcopy(result)
    for row in out['rows']:
        if row['name'] in bd.VANILLA_REFILL_ROWS:
            native = ref['families'][bd.N]['rows'][row['name']]
            row['air'] = copy.deepcopy(native['air']); row['views'] = copy.deepcopy(native['views'])
    return out


class PlanAndTable(unittest.TestCase):
    def test_the_judge_expects_exactly_the_rows_the_canary_plays(self):
        self.assertEqual(sorted(planned_rows()), sorted(bd.EXPECT))

    def test_every_phase_names_only_rows_the_judge_knows(self):
        for rows in (bd.FORGE_LIQUID_ROWS, bd.VANILLA_REFILL_ROWS):
            self.assertTrue(rows)
            self.assertLessEqual(rows, set(bd.EXPECT))
        self.assertFalse(bd.FORGE_LIQUID_ROWS & bd.VANILLA_REFILL_ROWS)
        for phase in bd.PHASES: self.assertLessEqual(bd.predicted_failures(phase), set(bd.EXPECT), phase)

    def test_the_rows_in_minecraftforges_liquid_are_the_rows_the_plan_puts_in_it(self):
        plan = (KERNEL / 'canary/forge-breath-drown/common/src/Core.java').read_text(encoding='utf-8')
        in_liquid = set(re.findall(r'(?:pig|bucket)\("([a-z0-9-]+)", forge(?:Murk|Calm)', plan))
        self.assertEqual(in_liquid, bd.FORGE_LIQUID_ROWS)

    def test_the_judge_reads_each_family_under_the_name_its_canary_reports(self):
        # The probe reports a view under the family its jar was built for (Core.FAMILY, the builder's package); a name the
        # judge does not read would make "no MinecraftForge listener heard a body" pass on no data.
        build = (KERNEL / 'run/build-breath-drown-canary.sh').read_text(encoding='utf-8')
        families = dict(re.findall(r"^    '([a-z]+)': \[old / 'run/([a-z]+)-patched/", build, re.M))
        self.assertEqual(families, {bd.F: 'forge', bd.N: 'neoforge'})
        core = (KERNEL / 'canary/forge-breath-drown/common/src/Core.java').read_text(encoding='utf-8')
        self.assertIn('public static final String FAMILY = "PKG";', core)
        self.assertIn("core.replace('PKG', family)", build)
        forge = (KERNEL / 'canary/forge-breath-drown/forge/src/forbric/breathdrown/forge/ForgeCanary.java').read_text(encoding='utf-8')
        self.assertTrue(re.findall(r'Core\.report\(FAMILY, "(breathe|drown)"', forge))

    def test_the_gate_plays_every_row_and_runs_every_phase(self):
        gate = (KERNEL / 'run/gate-m57-forge-breath-drown.sh').read_text(encoding='utf-8')
        self.assertIn(f'DONE rows={len(bd.EXPECT)} error=null', gate)
        for phase in bd.PHASES:
            self.assertRegex(gate, rf'\nrun_server {phase} ', phase)
            self.assertRegex(gate, rf'\njudge {phase} ', phase)
        self.assertRegex(gate, r'\nrun_server refused continue "" "\$NEO" "\$FORGE"\n')
        self.assertRegex(gate, r'\nrun_server vanilla-refill-off continue "-Dforbric\.vanillaAirRefill=off" "\$NEO"\n')

    def test_the_committed_reference_measures_every_row_it_is_compared_on(self):
        ref = json.loads(bd.REFERENCE.read_text(encoding='utf-8'))
        for name, checks in bd.EXPECT.items():
            row = ref['families'][bd.N]['rows'].get(name)
            self.assertIsNotNone(row, name)
            if ('refused',) in checks:
                self.assertTrue(row.get('skipped', '').startswith(bd.REFUSED_SKIPS), name)
                continue
            self.assertNotIn('skipped', row, name)
            if refill(name) and refill(name)[1]: self.assertTrue(ref['families'][bd.F]['rows'][name].get('air'), name)
        self.assertEqual(ref['properties'], bd.PROPERTIES)
        self.assertGreaterEqual(min(f['runs'] for f in ref['families'].values()), 3)

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

    def test_the_committed_reference_shows_every_documented_difference(self):
        ref = json.loads(bd.REFERENCE.read_text(encoding='utf-8'))
        for name in bd.VANILLA_REFILL_ROWS:
            neo = ref['families'][bd.N]['rows'][name]
            self.assertIn(bd.NEO_REFILL, neo['views'][bd.N]['breathe']['notes'][0], name)
            if refill(name)[1]: self.assertNotEqual(neo['air'], ref['families'][bd.F]['rows'][name]['air'], name)
            self.assertEqual([r for r in bd.vanilla_refill_reasons(ref, name, {}, refill(name)[1]) if 'reference' in r], [], name)

    def test_the_gate_writes_the_same_server_properties_as_the_reference_runs(self):
        gate = (KERNEL / 'run/gate-m57-forge-breath-drown.sh').read_text(encoding='utf-8')
        self.assertIn('"$TOOL" properties >> "$SERVER_DIR/server.properties"', gate)
        self.assertIn('level-seed=forbrickernel', bd.PROPERTIES)   # what seed_server_properties writes first


class Judge(unittest.TestCase):
    def setUp(self):
        self.ref = reference()
        self.result = neo_result(self.ref)

    def verdicts(self, result=None):
        return bd.judge_rows(self.ref, result or self.result)

    def failed(self, result=None):
        return {n for n, r in self.verdicts(result).items() if r}

    def row(self, name):
        return next(r for r in self.result['rows'] if r['name'] == name)

    def test_a_result_as_native_neoforge_passes_every_row(self):
        self.assertEqual(self.failed(), set())

    def test_a_curve_off_by_one_tick_fails_its_row(self):
        self.row('drown-n-damage3')['health'] = [10.0, 7.0]
        self.assertEqual(self.failed(), {'drown-n-damage3'})

    def test_an_f_tagged_row_is_judged_as_native_neoforge(self):
        # The MinecraftForge canary's listener cancelling the drowning would hold the body's air: red, as a curve is.
        self.row('drown-f-cancel')['air'] = [-16, -17]
        self.assertEqual(self.failed(), {'drown-f-cancel'})

    def test_a_neoforge_listener_view_that_differs_fails_its_row(self):
        self.row('drown-n-cancel')['views'][bd.N]['drown']['notes'] = ['neo drown other']
        self.assertEqual(self.failed(), {'drown-n-cancel'})
        self.setUp(); self.row('breathe-read')['views'][bd.N]['breathe']['count'] = 3
        self.assertEqual(self.failed(), {'breathe-read'})

    def test_a_minecraftforge_listener_that_heard_a_body_fails_its_row(self):
        self.row('drown-f-cancel')['views'][bd.F] = {'drown': view(1, ['forge drown'], [1])}
        verdicts = self.verdicts()
        self.assertEqual({n for n, r in verdicts.items() if r}, {'drown-f-cancel'})
        self.assertIn('refused MinecraftForge canary heard', ' '.join(verdicts['drown-f-cancel']))
        # ...in a vanilla-refill row as well, where NeoForge's notes are set aside.
        self.setUp(); self.row('drown-read-waterbreathing')['views'][bd.F] = {'breathe': view(2, ['forge breathe'])}
        self.assertEqual(self.failed(), {'drown-read-waterbreathing'})

    def test_a_listener_that_must_not_be_asked_fails_when_it_was(self):
        self.row('drown-read-waterbreathing')['views'][bd.N]['drown'] = view(1, ['neo drown x'], [1])
        self.assertIn('neo drown was asked 1x', ' '.join(self.verdicts()['drown-read-waterbreathing']))

    def test_a_row_in_minecraftforges_liquid_must_be_skipped_for_its_missing_block(self):
        name = sorted(bd.FORGE_LIQUID_ROWS)[0]
        played = next(r for r in self.result['rows'] if r['name'] == 'drown-read')
        result = copy.deepcopy(self.result)
        result['rows'] = [r if r['name'] != name else {**copy.deepcopy(played), 'name': name} for r in result['rows']]
        verdicts = self.verdicts(result)
        self.assertEqual({n for n, r in verdicts.items() if r}, {name})
        self.assertIn('the row was played', ' '.join(verdicts[name]))
        self.row(name)['skipped'] = 'setup failed: java.lang.NullPointerException'
        self.assertIn('skipped for another cause', ' '.join(self.verdicts()[name]))
        self.setUp(); self.ref['families'][bd.N]['rows'][name] = {'air': [1], 'views': {bd.N: {}}}
        self.assertIn('regenerate the reference', ' '.join(self.verdicts()[name]))

    def test_the_vanilla_refill_row_must_differ_from_native_neoforge_as_documented(self):
        name = next(n for n in bd.VANILLA_REFILL_ROWS if refill(n)[1])
        # As native NeoForge (air held, its listener handed nothing): the deliberate difference is gone, which is red.
        self.assertEqual(self.failed(neoforges_own_refill(self.ref, self.result)), bd.VANILLA_REFILL_ROWS)
        self.row(name)['air'] = copy.deepcopy(self.ref['families'][bd.N]['rows'][name]['air'])
        self.assertIn('not the vanilla refill', ' '.join(self.verdicts()[name]))
        self.setUp(); self.row(name)['views'][bd.N]['breathe']['notes'] = ['neo can=true consume=1 refill=0 -> x']
        self.assertIn(f"neo breathe first note must contain {bd.VANILLA_REFILL!r}", ' '.join(self.verdicts()[name]))
        # Its health and NeoForge's counts are still native.
        self.setUp(); self.row(name)['health'] = [10.0, 7.0]
        self.assertEqual(self.failed(), {name})
        self.setUp(); self.row(name)['views'][bd.N]['breathe']['count'] = 3
        self.assertEqual(self.failed(), {name})
        # A reference in which native NeoForge refills there too, or hands its listener the refill, no longer documents it.
        self.setUp(); self.ref['families'][bd.N]['rows'][name]['air'] = copy.deepcopy(self.ref['families'][bd.F]['rows'][name]['air'])
        self.assertIn('does not show the documented difference', ' '.join(self.verdicts()[name]))
        self.setUp(); self.ref['families'][bd.N]['rows'][name]['views'][bd.N]['breathe']['notes'] = ['neo refill=4 -> x']
        self.assertIn('does not show the documented difference', ' '.join(self.verdicts()[name]))

    def test_a_vanilla_refill_row_whose_listener_takes_the_refill_keeps_native_neoforges_air(self):
        name = next(n for n in bd.VANILLA_REFILL_ROWS if not refill(n)[1])
        self.row(name)['air'] = [9, 13]
        self.assertEqual(self.failed(), {name})

    def test_a_skipped_or_missing_row_fails(self):
        self.result['rows'] = [r for r in self.result['rows'] if r['name'] != 'neo-bucket']
        bucket = self.row('water-bucket')
        bucket.clear(); bucket.update({'name': 'water-bucket', 'skipped': 'setup failed'})
        self.assertEqual(self.failed(), {'neo-bucket', 'water-bucket'})


class Phases(unittest.TestCase):
    def run_judge(self, ref, result, phase):
        with tempfile.TemporaryDirectory() as tmp:
            r, o = Path(tmp) / 'ref.json', Path(tmp) / 'result.json'
            r.write_text(json.dumps(ref), encoding='utf-8'); o.write_text(json.dumps(result), encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = bd.main(['judge', '--reference', str(r), '--result', str(o), '--phase', phase])
        return code, out.getvalue()

    def test_only_vanilla_refill_off_predicts_failures(self):
        self.assertEqual(bd.predicted_failures('positive'), set())
        self.assertEqual(bd.predicted_failures('refused'), set())
        self.assertEqual(bd.predicted_failures('vanilla-refill-off'), bd.VANILLA_REFILL_ROWS)

    def test_a_phase_is_exact_both_ways(self):
        ref = reference()
        good = neo_result(ref)
        self.assertEqual(self.run_judge(ref, good, 'positive')[0], 0)
        self.assertEqual(self.run_judge(ref, good, 'refused')[0], 0)
        # vanilla-refill-off predicts the vanilla-refill rows: both of them as native NeoForge passes the phase...
        own = neoforges_own_refill(ref, good)
        self.assertEqual(self.run_judge(ref, own, 'vanilla-refill-off')[0], 0)
        # ...one of them holding is red, and so is the same result judged as positive or refused.
        held = copy.deepcopy(own)
        name = sorted(bd.VANILLA_REFILL_ROWS)[0]
        held['rows'] = [r if r['name'] != name else next(g for g in good['rows'] if g['name'] == name) for r in held['rows']]
        self.assertEqual(self.run_judge(ref, held, 'vanilla-refill-off')[0], 1)
        self.assertEqual(self.run_judge(ref, own, 'positive')[0], 1)
        self.assertEqual(self.run_judge(ref, own, 'refused')[0], 1)
        # A row the phase does not predict failing beside them is red too.
        extra = copy.deepcopy(own)
        next(r for r in extra['rows'] if r['name'] == 'drown-read')['health'] = [1.0, 1.0]
        self.assertEqual(self.run_judge(ref, extra, 'vanilla-refill-off')[0], 1)

    def test_a_predicted_failure_for_another_cause_is_red(self):
        ref = reference()
        wrong = neo_result(ref)
        for r in wrong['rows']:
            if r['name'] in bd.VANILLA_REFILL_ROWS: r['health'] = [1.0, 1.0]   # failing, but NeoForge's listener was handed vanilla's refill
        code, out = self.run_judge(ref, wrong, 'vanilla-refill-off')
        self.assertEqual(code, 1)
        self.assertIn('failed for another cause', out)

    def test_a_refused_jar_that_took_part_is_red_in_the_refused_phase(self):
        ref = reference()
        result = neo_result(ref)
        next(r for r in result['rows'] if r['name'] == 'breathe-f-refill2')['views'][bd.F] = {'breathe': view(30, ['forge x'])}
        code, out = self.run_judge(ref, result, 'refused')
        self.assertEqual(code, 1)
        self.assertIn("unexpected: ['breathe-f-refill2']", out)

    def test_a_probe_that_stopped_on_an_error_is_red(self):
        ref = reference()
        result = neo_result(ref); result['error'] = 'java.lang.IllegalStateException: boom'
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
