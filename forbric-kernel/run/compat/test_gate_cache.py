"""The verdict cache, the sweep's selection and its ordering (gate_cache.py, gates-parallel.py).

The cache's whole risk is a stale GREEN, so most of these are "this change MUST re-run the gate" checks, each paired
with the change that must NOT (otherwise the cache never hits and saves nothing). The last two classes hold the real
gates to the declarations the sweep relies on: every gate that reads output which follows the language is in the zh
subset, and every gate that runs test sources keys on them.
"""
import importlib.util
import os
import re
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

COMPAT = Path(__file__).resolve().parent
RUN = COMPAT.parent

_spec = importlib.util.spec_from_file_location('gates_parallel', COMPAT / 'gates-parallel.py')
scheduler = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scheduler)
import gate_cache  # noqa: E402  (gates-parallel put COMPAT on sys.path)

ENV = {'HOME': '/nonexistent', 'MC_DIR': '/nonexistent', 'PATH': os.environ.get('PATH', '')}


def jar(path, entries, stamp=(2020, 1, 1, 0, 0, 0)):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as z:
        for name, data in entries.items():
            z.writestr(zipfile.ZipInfo(name, stamp), data)


class Tree:
    """A throwaway repository shaped like this one: forbric-kernel/run/ with gates, a source file, a jar."""

    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.run = self.root / 'forbric-kernel' / 'run'
        self.run.mkdir(parents=True)
        (self.root / '.gitignore').write_text('forbric-kernel/run/*/\ncache/\nout/\nran.txt\n')
        (self.root / 'forbric-kernel' / 'Kernel.java').write_text('class Kernel {}\n')
        (self.root / 'forbric-kernel' / 'README.md').write_text('prose\n')
        (self.root / 'forbric-kernel' / 'src' / 'test').mkdir(parents=True)
        (self.root / 'forbric-kernel' / 'src' / 'test' / 'KernelTest.java').write_text('class KernelTest {}\n')
        self.gate('gate-m1.sh', '# GATE-PARALLEL: rundirs=server-a mem=100\nexit 0\n')
        self.gate('gate-m2.sh', '# GATE-PARALLEL: rundirs=server-b mem=100\n# GATE-CACHE: tests\nexit 0\n')
        jar(self.run / 'fixture' / 'mods' / 'a.jar', {'a/A.class': b'one'})
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)

    def gate(self, name, body):
        (self.run / name).write_text('#!/usr/bin/env bash\n' + body)

    def key(self, gate='gate-m1.sh', env=None, scratch=frozenset({'server-a', 'server-b'})):
        cache = gate_cache.Cache(self.root / 'cache', self.run, dict(env or ENV), scratch_dirs=set(scratch))
        return cache.key(gate, (self.run / gate).read_text())

    def close(self):
        self.tmp.cleanup()


class KeyTest(unittest.TestCase):
    def setUp(self):
        self.t = Tree()
        self.base = self.t.key()

    def tearDown(self):
        self.t.close()

    def test_the_key_is_stable(self):
        self.assertEqual(self.base, self.t.key())

    def test_a_source_change_reruns_the_gate_and_prose_does_not(self):
        (self.t.root / 'forbric-kernel' / 'README.md').write_text('other prose\n')
        self.assertEqual(self.base, self.t.key(), 'a doc-only change must not invalidate a verdict')
        (self.t.root / 'forbric-kernel' / 'Kernel.java').write_text('class Kernel { int x; }\n')
        self.assertNotEqual(self.base, self.t.key())

    def test_an_untracked_source_file_counts(self):
        (self.t.root / 'forbric-kernel' / 'New.java').write_text('class New {}\n')
        self.assertNotEqual(self.base, self.t.key())

    def test_its_own_script_reruns_it_and_another_gates_does_not(self):
        self.t.gate('gate-m2.sh', '# GATE-PARALLEL: rundirs=server-b mem=100\n# GATE-CACHE: tests\nexit 1\n')
        self.assertEqual(self.base, self.t.key(), "editing gate-m2 must not re-run gate-m1")
        self.t.gate('gate-m1.sh', '# GATE-PARALLEL: rundirs=server-a mem=100\nexit 0 # edited\n')
        self.assertNotEqual(self.base, self.t.key())

    def test_a_gate_script_it_names_is_its_input(self):
        self.t.gate('gate-m1.sh', '# GATE-PARALLEL: rundirs=server-a mem=100\nbash "$(dirname "$0")/gate-m2.sh"\n')
        before = self.t.key()
        self.t.gate('gate-m2.sh', '# GATE-PARALLEL: rundirs=server-b mem=100\nexit 3\n')
        self.assertNotEqual(before, self.t.key())

    def test_test_sources_only_matter_to_a_gate_that_declares_them(self):
        m2 = self.t.key('gate-m2.sh')
        (self.t.root / 'forbric-kernel' / 'src' / 'test' / 'KernelTest.java').write_text('class KernelTest { }\n')
        self.assertEqual(self.base, self.t.key(), 'a test-only change must not re-run a boot gate')
        self.assertNotEqual(m2, self.t.key('gate-m2.sh'), 'gate-m2 runs the tests: it must re-run')

    def test_a_jars_content_counts_and_its_timestamps_do_not(self):
        jar(self.t.run / 'fixture' / 'mods' / 'a.jar', {'a/A.class': b'one'}, stamp=(2024, 5, 5, 5, 5, 6))
        self.assertEqual(self.base, self.t.key(), 're-zipping the same entries must not invalidate a verdict')
        jar(self.t.run / 'fixture' / 'mods' / 'a.jar', {'a/A.class': b'two'})
        self.assertNotEqual(self.base, self.t.key())

    def test_a_nested_jar_rebuilt_with_new_timestamps_does_not_count_and_its_content_does(self):
        import io
        def outer(inner_stamp, inner_data):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w') as z:
                z.writestr(zipfile.ZipInfo('lib/L.class', inner_stamp), inner_data)
            jar(self.t.run / 'canary' / 'live.jar', {'META-INF/jars/lib.jar': buf.getvalue(), 'C.class': b'c'})
        outer((2020, 1, 1, 0, 0, 0), b'lib')
        before = self.t.key()
        outer((2025, 2, 2, 2, 2, 2), b'lib')
        self.assertEqual(before, self.t.key(), 'the canary builder re-zips its nested library on every build')
        outer((2025, 2, 2, 2, 2, 2), b'lib changed')
        self.assertNotEqual(before, self.t.key())

    def test_a_new_fixture_jar_counts_and_a_gates_own_rundir_does_not(self):
        jar(self.t.run / 'server-a' / 'mods' / 'staged.jar', {'x': b'staged by the gate'})
        self.assertEqual(self.base, self.t.key(), "a gate's own rundir is its output, not an input")
        jar(self.t.run / 'fixture' / 'mods' / 'b.jar', {'b': b'new fixture'})
        self.assertNotEqual(self.base, self.t.key())

    def test_a_scratch_name_is_only_skipped_at_the_top_of_run(self):
        jar(self.t.run / 'fixture' / 'server-a' / 'deep.jar', {'d': b'fixture'})
        self.assertNotEqual(self.base, self.t.key())

    def test_the_language_and_jvm_options_count_and_ports_do_not(self):
        self.assertEqual(self.base, self.t.key(env={**ENV, 'GATE_PORT': '46800', 'M12_PORT': '46801',
                                                     'FORBRIC_GATE_RESULTS': '/tmp/x',
                                                     'FORBRIC_PREBUILT_STAMP': '1:2'}))
        self.assertNotEqual(self.base, self.t.key(env={**ENV, 'FORBRIC_JVM': '-Duser.language=zh'}))

    def test_this_trees_own_path_does_not_count(self):
        here = str(self.t.root)
        self.assertEqual(gate_cache.env_digest({'FORBRIC_OLD': f'{here}/x'}, self.t.root),
                         gate_cache.env_digest({'FORBRIC_OLD': '/other/tree/x'}, Path('/other/tree')))


class SweepTest(unittest.TestCase):
    """gates-parallel.py end to end against stub gates that record each run."""

    def setUp(self):
        self.t = Tree()
        self.ran = self.t.root / 'ran.txt'
        for n in (1, 2, 3):
            self.t.gate(f'gate-m{n}.sh', f'# GATE-PARALLEL: rundirs=server-{n} mem=10\n'
                                         f'{"# GATE-TAGS: localized   (why)" if n == 2 else ""}\n'
                                         f'echo gate-m{n} >> "{self.ran}"\necho "[kernel] PASS m{n}"\n'
                                         f'exit ${{STUB_RC_M{n}:-0}}\n')

    def tearDown(self):
        self.t.close()

    def sweep(self, *args, env=None, jobs='1'):
        out = self.t.root / 'out'
        self.ran.write_text('')
        r = subprocess.run(['python3', str(COMPAT / 'gates-parallel.py'), '--run-dir', str(self.t.run),
                            '--out-dir', str(out), '--jobs', jobs, '--cache-dir', str(self.t.root / 'cache'),
                            *args], capture_output=True, text=True, env={**os.environ, **ENV, **(env or {})})
        return r, self.ran.read_text().split(), out

    def test_a_green_gate_is_not_run_again_on_the_same_inputs(self):
        r, ran, _ = self.sweep()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(['gate-m1', 'gate-m2', 'gate-m3'], ran)
        r, ran, out = self.sweep()
        self.assertEqual([], ran)
        self.assertIn('RESULT gate-m1.sh GREEN (exit=0; cached: same inputs went GREEN at ', r.stdout)
        self.assertIn('[kernel] PASS m1', (out / 'gate-m1.sh.log').read_text(), 'the cached log is the real one')

    def test_a_red_gate_always_runs_again(self):
        self.sweep(env={'STUB_RC_M2': '1'})
        r, ran, _ = self.sweep(env={'STUB_RC_M2': '1'})
        self.assertEqual(['gate-m2'], ran)
        self.assertEqual(1, r.returncode)

    def test_a_change_reruns_only_the_gate_it_touches(self):
        self.sweep()
        self.t.gate('gate-m3.sh', '# GATE-PARALLEL: rundirs=server-3 mem=10\n'
                                  f'echo gate-m3 >> "{self.ran}"\nexit 0  # edited\n')
        _, ran, _ = self.sweep()
        self.assertEqual(['gate-m3'], ran)

    def test_a_release_run_ignores_the_cache(self):
        self.sweep()
        r, ran, _ = self.sweep('--release')
        self.assertEqual(['gate-m1', 'gate-m2', 'gate-m3'], ran)
        self.assertNotIn('cached', r.stdout)

    def test_only_tag_runs_the_tagged_gates_and_says_why_the_rest_did_not(self):
        r, ran, _ = self.sweep('--only-tag', 'localized')
        self.assertEqual(['gate-m2'], ran)
        self.assertIn('RESULT gate-m1.sh SKIP (not selected by --only-tag localized)', r.stdout)
        self.assertEqual(0, r.returncode)
        r, _, _ = self.sweep('--only-tag', 'localized', '--release')
        self.assertEqual(1, r.returncode, 'a release run that did not run every gate is not a passing run')
        r, _, _ = self.sweep('--only-tag', 'nosuchtag')
        self.assertEqual(2, r.returncode)

    def test_only_runs_the_named_gate(self):
        r, ran, _ = self.sweep('--only', 'gate-m3.sh', '--only', 'gate-m1.sh')
        self.assertEqual(['gate-m1', 'gate-m3'], ran)
        r, _, _ = self.sweep('--only', 'gate-m9.sh')
        self.assertEqual(2, r.returncode)

    def test_with_slots_the_longest_gate_starts_first_and_one_slot_keeps_numerical_order(self):
        cache = self.t.root / 'cache'
        durations = '{"gate-m3.sh": 900, "gate-m1.sh": 5, "gate-m2.sh": 50}'
        cache.mkdir(parents=True, exist_ok=True)
        (cache / 'durations.json').write_text(durations)
        _, ran, _ = self.sweep(jobs='1')
        self.assertEqual(['gate-m1', 'gate-m2', 'gate-m3'], ran)
        subprocess.run(['rm', '-rf', str(cache / 'verdicts')], check=True)   # nothing cached for the next run
        (cache / 'durations.json').write_text(durations)                     # the -j 1 run recorded its own
        _, _, out = self.sweep(jobs='3')
        starts = [l.split('START ')[1].split()[0] for l in (out / 'progress.log').read_text().splitlines()
                  if ' START ' in l]
        self.assertEqual(['gate-m3.sh', 'gate-m2.sh', 'gate-m1.sh'], starts)
        self.assertEqual(['RESULT gate-m1.sh', 'RESULT gate-m2.sh', 'RESULT gate-m3.sh'],
                         [' '.join(l.split()[:2]) for l in (out / 'summary.txt').read_text().splitlines()],
                         'summary.txt stays in numerical order')


# The output that follows the language (KernelLoadReport, MergeReport, CrashAttribution, DialogLang): a gate whose
# code reads one of these is a gate the zh sweep (`gates-all --only-tag localized`) must run.
LOCALIZED = re.compile(r'load-report|merge-report|crash-reports/|dialogLanguage|Forbric/Deps|DialogLang')
RUNS_TESTS = re.compile(r'gradlew\b.*\b(cleanTest|test|transferTest|compileTransferTestJava)\b|unittest|src/test')
COMMENT = re.compile(r'^[ \t]*#.*$', re.M)


class DeclarationTest(unittest.TestCase):
    def setUp(self):
        self.gates = scheduler.discover(RUN)
        self.assertGreater(len(self.gates), 40, 'the walk no longer sees the checked-in gates')

    def test_every_gate_that_reads_localized_output_is_in_the_zh_sweep_and_only_those(self):
        reads = {g.name for g in self.gates if LOCALIZED.search(COMMENT.sub('', g.source))}
        tagged = {g.name for g in self.gates if 'localized' in g.tags}
        self.assertEqual(sorted(reads - tagged), [],
                         'these read a report or dialog that follows the language: add `# GATE-TAGS: localized`')
        self.assertEqual(sorted(tagged - reads), [], 'tagged localized but reads no localized output')

    def test_every_gate_that_runs_test_sources_keys_on_them(self):
        runs = {g.name for g in self.gates if RUNS_TESTS.search(COMMENT.sub('', g.source))}
        keyed = {g.name for g in self.gates if 'tests' in gate_cache.gate_cache_flags(g.source)}
        self.assertEqual(sorted(runs - keyed), [],
                         'these run test sources: add `# GATE-CACHE: tests`, or a test-only change reuses a stale GREEN')

    def test_the_duration_prior_names_only_real_gates(self):
        prior = scheduler.load_durations(None)
        names = {g.name for g in self.gates}
        self.assertEqual(sorted(set(prior) - names), [], 'gate-durations.tsv names a gate that no longer exists')


if __name__ == '__main__':
    unittest.main()
