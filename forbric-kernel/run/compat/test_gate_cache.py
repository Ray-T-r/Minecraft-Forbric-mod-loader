"""The verdict cache, the sweep's selection and its ordering (gate_cache.py, gates-parallel.py).

The cache's whole risk is a stale GREEN, so most of these are "this change MUST re-run the gate" checks, each paired
with the change that must NOT (otherwise the cache never hits and saves nothing). The last class holds the real
gates to the declarations the sweep relies on: every gate that runs test sources keys on them, and every fixture jar a
gate stages from build/ is in a root the key hashes.
"""
import importlib.util
import os
import re
import shutil
import subprocess
import sys
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
# gate-m0 runs these tests inside a sweep, whose own knobs (FORBRIC_GATE_FRESH under --release, the cache dir) must not
# reach the stub sweeps below.
INHERITED = {k: v for k, v in os.environ.items() if not k.startswith('FORBRIC_GATE_')}


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
        (self.root / '.gitignore').write_text('forbric-kernel/run/*/\nforbric-kernel/build/\ncache/\nout/\nran.txt\n')
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

    def test_a_gate_that_runs_the_tests_keys_every_gate_script_and_the_prose_but_not_the_records(self):
        # gate-m0's suite reads every gate script (GatesAllTest, test_gate_locale, one contract test per gate) and
        # PROTOCOL.md (CompatProtocolTest): editing gate-m1 or a README must re-run it. The records under reports/ are
        # read by none of its tests, and a record commit must not re-run the suite (review OPT wave 2d, lens A, B2).
        with open(self.t.root / '.gitignore', 'a') as ignore:   # run/compat/ is tracked, as in the real tree
            ignore.write('!forbric-kernel/run/compat/\n')
        m2 = self.t.key('gate-m2.sh')
        self.t.gate('gate-m1.sh', '# GATE-PARALLEL: rundirs=server-a mem=100\nexit 0 # a check renamed\n')
        self.assertNotEqual(m2, self.t.key('gate-m2.sh'), "gate-m2 runs tests that read gate-m1's script")
        m2 = self.t.key('gate-m2.sh')
        m1 = self.t.key('gate-m1.sh')
        (self.t.root / 'forbric-kernel' / 'README.md').write_text('other prose\n')
        self.assertNotEqual(m2, self.t.key('gate-m2.sh'), 'a test may read the prose (CompatProtocolTest reads PROTOCOL.md)')
        self.assertEqual(m1, self.t.key('gate-m1.sh'), 'a boot gate does not read it')
        m2 = self.t.key('gate-m2.sh')
        record = self.t.root / 'forbric-kernel' / 'run' / 'compat' / 'reports' / 'r' / 'stage.md'
        record.parent.mkdir(parents=True)
        record.write_text('a record\n')
        self.assertIn('forbric-kernel/run/compat/reports/r/stage.md', gate_cache.repo_files(self.t.root),
                      'the record must be a file git sees, or this proves nothing')
        self.assertEqual(m2, self.t.key('gate-m2.sh'), 'a record commit must not re-run the suite')
        self.assertEqual(m1, self.t.key('gate-m1.sh'))
        protocol = self.t.root / 'forbric-kernel' / 'run' / 'compat' / 'PROTOCOL.md'
        protocol.write_text('the protocol\n')
        self.assertNotEqual(m2, self.t.key('gate-m2.sh'), 'CompatProtocolTest reads PROTOCOL.md')

    def test_the_launchers_unprefixed_knobs_and_the_jars_they_name_count(self):
        # launch-kernel-*.sh take the game base, the carrier and the vanilla reference from BASE, NEO_RT and NATIVE_REF;
        # gate-m31's vanilla arm from VANILLA_JAR / VANILLA_JVM. A GREEN measured on another base is no answer for this one.
        outside = tempfile.TemporaryDirectory()   # another stage's base, outside this repository
        self.addCleanup(outside.cleanup)
        base = Path(outside.name) / 'other-base.jar'
        jar(base, {'net/minecraft/A.class': b'one'})
        for name in ('BASE', 'NEO_RT', 'NATIVE_REF', 'VANILLA_JAR'):
            self.assertNotEqual(self.base, self.t.key(env={**ENV, name: str(base)}), name)
        on_base = self.t.key(env={**ENV, 'BASE': str(base)})
        jar(base, {'net/minecraft/A.class': b'two'})
        self.assertNotEqual(on_base, self.t.key(env={**ENV, 'BASE': str(base)}), 'the jar BASE names is the input, not its path')
        self.assertNotEqual(self.base, self.t.key(env={**ENV, 'NATIVE_REF': 'none'}), 'NATIVE_REF=none boots without one')
        self.assertNotEqual(self.base, self.t.key(env={**ENV, 'VANILLA_JVM': '-Xmx1g'}))
        self.assertEqual(self.base, self.t.key(env={**ENV, 'RUNDIR': '/somewhere/else'}), 'where a gate writes is not an input')

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
            jar(self.t.run / 'fixture' / 'mods' / 'live.jar', {'META-INF/jars/lib.jar': buf.getvalue(), 'C.class': b'c'})
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

    @unittest.skipIf(os.name == 'nt', 'the stub JDKs are POSIX shell scripts, and the launchers they stand for are too')
    def test_the_jvm_the_launchers_start_counts_and_forbric_java_does_not(self):
        # The launchers `exec java` from PATH; the key must follow that java, and nothing they do not read.
        bins = {}
        for name, version in (('a', '25.0.3'), ('b', '21.0.9')):
            d = self.t.root / 'jdk' / name
            d.mkdir(parents=True)
            (d / 'java').write_text(f'#!/bin/sh\necho "openjdk version \\"{version}\\"" >&2\n')
            (d / 'java').chmod(0o755)
            bins[name] = str(d)
        on_a = self.t.key(env={**ENV, 'PATH': f"{bins['a']}:{ENV['PATH']}"})
        self.assertNotEqual(on_a, self.t.key(env={**ENV, 'PATH': f"{bins['b']}:{ENV['PATH']}"}),
                            'another JDK first on PATH is another JVM for every launcher')
        named = gate_cache._java_version({**ENV, 'PATH': f"{bins['a']}:{ENV['PATH']}", 'FORBRIC_JAVA': bins['b']})
        self.assertIn('25.0.3', named, 'no launcher reads FORBRIC_JAVA, so it must not decide which JVM the key names')
        self.assertNotIn('21.0.9', named)

    def test_a_fixture_mod_under_build_counts(self):
        fixture = self.t.root / 'forbric-kernel' / 'build' / 'compat-inputs' / 'carpet' / 'mods' / 'carpet.jar'
        jar(fixture, {'c/C.class': b'one'})
        before = self.t.key()
        jar(fixture, {'c/C.class': b'two'})
        self.assertNotEqual(before, self.t.key(), 'gate-m62 stages this jar: a new build of it is a new input')

    def test_a_canary_the_gates_build_is_their_output_and_its_sources_are_the_input(self):
        # run/canary holds only what the builders write, from sources the key already has. Counting the jars made a
        # fresh tree's first sweep (none built yet) and the sweep after it two different sets of inputs.
        built = self.t.run / 'canary' / 'forbricfabriclive.jar'
        jar(built, {'C.class': b'one'})
        self.assertEqual(self.base, self.t.key(), 'a canary a gate built is not an input')
        jar(built, {'C.class': b'two'})
        self.assertEqual(self.base, self.t.key())
        source = self.t.root / 'forbric-kernel' / 'canary' / 'fabriclive' / 'Live.java'
        source.parent.mkdir(parents=True)
        source.write_text('class Live {}\n')
        self.assertNotEqual(self.base, self.t.key(), "the canary's source is")

    def test_the_canary_sources_in_the_staged_root_count(self):
        outside = tempfile.TemporaryDirectory()   # a staged root is outside the repository, as .stage4 is to git
        self.addCleanup(outside.cleanup)
        staged = Path(outside.name)
        src = staged / 'run' / 'livemod-src-neoforge' / 'forbric' / 'Live.java'
        src.parent.mkdir(parents=True)
        src.write_text('class Live {}\n')
        env = {**ENV, 'FORBRIC_OLD': str(staged)}
        before = self.t.key(env=env)
        self.assertEqual(before, self.t.key(env=env))
        src.write_text('class Live { int changed; }\n')
        self.assertNotEqual(before, self.t.key(env=env), 'build-neolive-canary.sh compiles this into a canary gates stage')

    def test_this_trees_own_path_does_not_count(self):
        # Both spelled through Path, as the cache spells its repository (Path(git's toplevel)); on Windows that is `\`.
        other = Path('/other/tree')
        self.assertEqual(gate_cache.env_digest({'FORBRIC_OLD': str(self.t.root / 'x')}, self.t.root),
                         gate_cache.env_digest({'FORBRIC_OLD': str(other / 'x')}, other))


# gates-parallel.py starts every gate as `bash gate-*.sh`; the gates and lib.sh run on macOS and Linux only.
@unittest.skipIf(os.name == 'nt' or not shutil.which('bash'), 'runs the bash gate sweep')
class SweepTest(unittest.TestCase):
    """gates-parallel.py end to end against stub gates that record each run."""

    def setUp(self):
        self.t = Tree()
        self.ran = self.t.root / 'ran.txt'
        for n in (1, 2, 3):
            self.t.gate(f'gate-m{n}.sh', f'# GATE-PARALLEL: rundirs=server-{n} mem=10\n'
                                         f'echo gate-m{n} >> "{self.ran}"\necho "[kernel] PASS m{n}"\n'
                                         f'exit ${{STUB_RC_M{n}:-0}}\n')

    def tearDown(self):
        self.t.close()

    def sweep(self, *args, env=None, jobs='1'):
        out = self.t.root / 'out'
        self.ran.write_text('')
        r = subprocess.run([sys.executable, str(COMPAT / 'gates-parallel.py'), '--run-dir', str(self.t.run),
                            '--out-dir', str(out), '--jobs', jobs, '--cache-dir', str(self.t.root / 'cache'),
                            *args], capture_output=True, text=True, env={**INHERITED, **ENV, **(env or {})})
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

    def test_a_timing_gate_is_answered_from_the_cache_like_any_other(self):
        # RULE (lane OPT, D89): a gate declared `alone` -- its verdict is a timing (check_kept_up, m12's late ticks) --
        # is cached like any other. Its inputs fix the code that has to keep up; a second run of the same inputs can
        # only add the host's load to the answer, which this project already reads as no evidence about the kernel
        # (a red from a loaded run is checked against its boot times before anything is concluded). So a GREEN on
        # these inputs stands, a RED is never kept, and any input change -- the kernel included -- runs it again.
        self.t.gate('gate-m2.sh', '# GATE-PARALLEL: rundirs=server-2 mem=10 alone\n'
                                  f'echo gate-m2 >> "{self.ran}"\necho "[kernel] PASS kept up"\nexit 0\n')
        _, ran, _ = self.sweep(jobs='2')
        self.assertIn('gate-m2', ran)
        r, ran, _ = self.sweep(jobs='2')
        self.assertEqual([], ran)
        self.assertIn('RESULT gate-m2.sh GREEN (exit=0; cached: ', r.stdout)
        (self.t.root / 'forbric-kernel' / 'Kernel.java').write_text('class Kernel { int slower; }\n')
        _, ran, _ = self.sweep(jobs='2')
        self.assertIn('gate-m2', ran, 'a kernel change re-runs the timing gate')

    def test_a_gate_is_told_to_measure_its_control_arms_afresh_only_when_the_sweep_is(self):
        self.t.gate('gate-m1.sh', '# GATE-PARALLEL: rundirs=server-1 mem=10\n'
                                  f'echo "gate-m1 fresh=${{FORBRIC_GATE_FRESH:-}}" >> "{self.ran}"\nexit 0\n')
        _, ran, _ = self.sweep('--only', 'gate-m1.sh')
        self.assertEqual(['gate-m1', 'fresh='], ran)
        _, ran, _ = self.sweep('--only', 'gate-m1.sh', '--release')
        self.assertEqual(['gate-m1', 'fresh=1'], ran, 'an acceptance measures every control arm afresh')

    def test_a_release_run_ignores_the_cache(self):
        self.sweep()
        r, ran, _ = self.sweep('--release')
        self.assertEqual(['gate-m1', 'gate-m2', 'gate-m3'], ran)
        self.assertNotIn('cached', r.stdout)

    def test_only_runs_the_named_gate_and_says_why_the_rest_did_not(self):
        r, ran, _ = self.sweep('--only', 'gate-m3.sh', '--only', 'gate-m1.sh')
        self.assertEqual(['gate-m1', 'gate-m3'], ran)
        self.assertIn('RESULT gate-m2.sh SKIP (not selected by --only gate-m3.sh --only gate-m1.sh)', r.stdout)
        self.assertEqual(0, r.returncode)
        r, _, _ = self.sweep('--only', 'gate-m1.sh', '--release')
        self.assertEqual(1, r.returncode, 'a release run that did not run every gate is not a passing run')
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


RUNS_TESTS = re.compile(r'gradlew\b.*\b(cleanTest|test|transferTest|compileTransferTestJava)\b|unittest|src/test')
COMMENT = re.compile(r'^[ \t]*#.*$', re.M)


class DeclarationTest(unittest.TestCase):
    def setUp(self):
        self.gates = scheduler.discover(RUN)
        self.assertGreater(len(self.gates), 40, 'the walk no longer sees the checked-in gates')

    def test_every_gate_that_runs_test_sources_keys_on_them(self):
        runs = {g.name for g in self.gates if RUNS_TESTS.search(COMMENT.sub('', g.source))}
        keyed = {g.name for g in self.gates if 'tests' in gate_cache.gate_cache_flags(g.source)}
        self.assertEqual(sorted(runs - keyed), [],
                         'these run test sources: add `# GATE-CACHE: tests`, or a test-only change reuses a stale GREEN')

    def test_every_fixture_jar_a_gate_reads_from_build_is_under_a_root_the_key_hashes(self):
        # build/ is not hashed as a whole: most of it is this tree's own output. The jars a gate stages from it by default
        # must be in FIXTURE_BUILD_DIRS, or a new build of one would reuse a GREEN measured on the old one.
        outputs = {'libs'}
        unhashed = set()
        for g in self.gates:
            for d in re.findall(r'\$(?:BUILD|KERNEL/build)/([A-Za-z0-9._+-]+)/[^"\s]*\.jar', COMMENT.sub('', g.source)):
                if d not in outputs and not d.startswith('gate-') and d not in gate_cache.FIXTURE_BUILD_DIRS:
                    unhashed.add(f'{g.name}: build/{d}')
        self.assertEqual(sorted(unhashed), [])

    def test_every_canary_a_gate_stages_from_run_canary_is_built_by_that_gate(self):
        # run/canary is the builders' output and not part of the key, so a gate the cache runs alone must build what it
        # stages: gate-m14 read the Fabric canary an earlier gate had left there, and went SKIP-FATAL in a tree where
        # none had.
        builders = {b.name: b.read_text() for b in RUN.glob('build-*.sh')}
        missing = []
        for g in self.gates:
            code = '\n'.join(l for l in COMMENT.sub('', g.source).splitlines()
                              if 'SKIP-FATAL' not in l and 'echo ' not in l)
            runs = set(re.findall(r'(build-[A-Za-z0-9-]+\.sh)', code))
            for name in set(re.findall(r'canary/(?:forge-refused/)?([A-Za-z0-9_-]+\.jar)', code)):
                if not any(name.removesuffix('.jar') in builders.get(b, '') for b in runs):
                    missing.append(f'{g.name}: {name}')
        self.assertEqual(sorted(missing), [])

    def test_every_variable_a_run_script_takes_from_its_environment_is_keyed_or_named_as_no_input(self):
        # The key keeps the environment by prefix plus gate_cache._ENV_INPUTS. A script that reads an unprefixed knob
        # (`${BASE:-default}`, never set by the script itself) and is in neither list would reuse a GREEN measured with
        # another value of it (review OPT wave 2d, lens A, M1: BASE, NEO_RT, NATIVE_REF, VANILLA_JAR, VANILLA_JVM).
        scripts = sorted([*RUN.glob('launch-*.sh'), RUN / 'lib.sh', RUN / 'old-kernel.sh', *RUN.glob('gate-m*.sh'),
                          *RUN.glob('build-*.sh')])
        default_read = re.compile(r'\$\{([A-Z_][A-Z0-9_]*):?[-=+?]')

        def sets(code, v):
            # an assignment of v other than its own default (V="${V:-...}"), a loop/read target, or a local
            for m in re.finditer(r'(?:^|[\s;&|(!])(?:export\s+|local\s+(?:-\w+\s+)*|readonly\s+|declare\s+(?:-\w+\s+)*)?'
                                 + v + r'\+?=(\S*)', code, re.M):
                if not re.match(r'"?\$\{' + v + r':?[-=]', m.group(1)):
                    return True
            return bool(re.search(r'\b(?:for|read(?:\s+-\w+)*)\s+(?:\w+\s+)*' + v + r'\b', code)
                        or re.search(r'\blocal\s+(?:-\w+\s+)*(?:\w+\s+)*' + v + r'\b', code))
        sourced = ''.join(COMMENT.sub('', (RUN / n).read_text()) for n in ('lib.sh', 'old-kernel.sh'))
        unkeyed = set()
        for path in scripts:
            code = COMMENT.sub('', path.read_text(errors='replace'))
            for v in set(default_read.findall(code)):
                if gate_cache._ENV_KEEP.search(v) or v in gate_cache._ENV_INPUTS or v in gate_cache._ENV_NOT_INPUT:
                    continue
                if sets(code, v) or (path.name.startswith('gate-') and sets(sourced, v)):
                    continue
                unkeyed.add(f'{path.name}: {v}')
        self.assertEqual(sorted(unkeyed), [], 'add it to gate_cache._ENV_INPUTS, or to _ENV_NOT_INPUT with the reason')

    def test_no_test_a_tests_gate_runs_reads_the_records(self):
        # The records under run/compat/reports/ stay out of even a `tests` gate's key, so none of the tests it runs may
        # read one: the kernel's unit and transfer sources, and the test_*.py modules the gate names.
        sources = [*sorted((RUN.parent / 'src' / 'test').rglob('*.java')),
                   *sorted((RUN.parent / 'src' / 'transferTest').rglob('*.java'))]
        reads = re.compile(r'(?<![\w-])reports/|["\']reports["\']')
        prose = re.compile(r'^\s*(#|//|\*|/\*)')   # comments say where things are; they read nothing
        found = []
        for g in self.gates:
            if 'tests' not in gate_cache.gate_cache_flags(g.source):
                continue
            modules = set(re.findall(r'\b(test_[a-z0-9_]+)\b', COMMENT.sub('', g.source)))
            for f in [*sources, *(COMPAT / f'{m}.py' for m in sorted(modules) if (COMPAT / f'{m}.py').is_file())]:
                if f.name == 'test_gate_cache.py':
                    continue   # this file: its records are a throwaway tree's
                for line in f.read_text(errors='replace').splitlines():
                    if not prose.match(line) and reads.search(line.replace('build/reports', '').replace('crash-reports', '')):
                        found.append(f'{g.name}: {f.name}: {line.strip()[:80]}')
        self.assertEqual(sorted(set(found)), [], 'a test that reads a record: drop _RECORDS from gate_cache.py')

    def test_the_duration_prior_names_only_real_gates(self):
        prior = scheduler.load_durations(None)
        names = {g.name for g in self.gates}
        self.assertEqual(sorted(set(prior) - names), [], 'gate-durations.tsv names a gate that no longer exists')


if __name__ == '__main__':
    unittest.main()
