"""tools/nightly/run_nightly.py against throwaway git repositories, a stand-in dev.py / gates-all.sh and a fake gh."""
import datetime
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('run_nightly', ROOT / 'tools/nightly/run_nightly.py')
nightly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nightly)

SATURDAY, SUNDAY = '2026-10-03', '2026-10-04'

# Stands in for tools/dev.py in the tested commit: records how it was called and writes a JUnit result per suite.
FAKE_DEV = r'''
import json, os, subprocess, sys, time
from pathlib import Path
root = Path.cwd()
marker = root / 'forbric-kernel/build/left-by-an-earlier-night.txt'
entry = dict(tool='dev', argv=sys.argv[1:], cwd=str(root), FORBRIC_OLD=os.environ.get('FORBRIC_OLD'),
             MC_DIR=os.environ.get('MC_DIR'), stale=marker.exists(),
             linked=os.path.islink(root / 'forbric-kernel/run/client-merged-pack'))
with open(os.environ['FAKE_NIGHT_LOG'], 'a') as log:
    log.write(json.dumps(entry) + '\n')
marker.parent.mkdir(parents=True, exist_ok=True)
marker.write_text('x')
for suite, count in (('test', 2), ('transferTest', 1)):
    results = root / 'forbric-kernel/build/test-results' / suite
    results.mkdir(parents=True, exist_ok=True)
    cases = ''.join(f'<testcase classname="net.forbric.Fake" name="case{i}"/>' for i in range(count))
    (results / 'TEST-net.forbric.Fake.xml').write_text(
        f'<testsuite name="net.forbric.Fake" tests="{count}" skipped="0" failures="0" errors="0">{cases}</testsuite>')
if os.environ.get('FAKE_CHILD_PROOF'):
    subprocess.Popen([sys.executable, '-c', 'import sys, time; time.sleep(3); open(sys.argv[1], "w").write("alive")',
                      os.environ['FAKE_CHILD_PROOF']])
time.sleep(float(os.environ.get('FAKE_INTEGRATION_SLEEP') or 0))
sys.exit(int(os.environ.get('FAKE_INTEGRATION_EXIT') or 0))
'''

# Stands in for gates-all.sh (run with the test's Python, not bash, so this runs the same on every OS).
FAKE_GATES = r'''
import json, os, sys
from pathlib import Path
arguments = sys.argv[1:]
with open(os.environ['FAKE_NIGHT_LOG'], 'a') as log:
    log.write(json.dumps(dict(tool='gates', argv=arguments)) + '\n')
red = os.environ.get('FAKE_RED_GATE', '')
gates = ('gate-m0.sh', 'gate-m9-client.sh', 'gate-m34-soak.sh', 'gate-m58-world-upgrade.sh')
skipped = [arguments[i + 1] for i, a in enumerate(arguments) if a == '--skip']
for name in skipped:
    if name not in gates:
        # As gates-parallel.py does: an unknown --skip name stops the run before any gate starts.
        print(f'Unknown --skip gate: {name}', file=sys.stderr)
        sys.exit(2)
lines = []
for gate in gates:
    if gate in skipped:
        lines.append(f'RESULT {gate} SKIP (explicit --skip)')
    elif gate == red:
        lines.append(f'RESULT {gate} RED (exit=1)')
    else:
        lines.append(f'RESULT {gate} GREEN (exit=0)')
out = Path('forbric-kernel/build/gates')
out.mkdir(parents=True, exist_ok=True)
(out / 'summary.txt').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
sys.exit(1 if red else 0)
'''

FAKE_GH = r'''
import json, os, sys
with open(os.environ['FAKE_GH_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\n')
'''


def git(*arguments, cwd=None):
    return subprocess.run(['git'] + [str(a) for a in arguments], cwd=cwd, check=True, capture_output=True,
                          text=True).stdout.strip()


def remove_tree(path):
    # git writes its objects read-only, which Windows refuses to delete.
    for parent, directories, files in os.walk(path):
        for name in directories + files:
            target = os.path.join(parent, name)
            if not os.path.islink(target):
                os.chmod(target, stat.S_IWRITE | stat.S_IREAD | stat.S_IEXEC)
    shutil.rmtree(path, ignore_errors=True)


class NightlyTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='forbric nightly '))
        self.addCleanup(remove_tree, self.root)
        self.origin = self.root / 'origin.git'
        git('init', '--quiet', '--bare', '-b', 'main', self.origin)
        upstream = self.root / 'upstream'
        git('init', '--quiet', '-b', 'main', upstream)
        self.identify(upstream)
        self.put(upstream, 'tools/dev.py', FAKE_DEV)
        self.put(upstream, 'forbric-kernel/run/compat/gates-all.sh', FAKE_GATES)
        self.put(upstream, 'tools/junit_report.py', (ROOT / 'tools/junit_report.py').read_text(encoding='utf-8'))
        git('add', '-A', cwd=upstream)
        git('commit', '--quiet', '-m', 'first', cwd=upstream)
        git('remote', 'add', 'origin', self.origin, cwd=upstream)
        git('push', '--quiet', 'origin', 'main', cwd=upstream)

        # The developer's main checkout: on a feature branch of its own, with uncommitted work and the fixtures.
        self.repo = self.root / 'Forbric'
        git('clone', '--quiet', self.origin, self.repo)
        self.identify(self.repo)
        git('checkout', '--quiet', '-b', 'feature', cwd=self.repo)
        self.put(self.repo, 'feature.txt', 'mine\n')
        git('add', 'feature.txt', cwd=self.repo)
        git('commit', '--quiet', '-m', 'local work', cwd=self.repo)
        self.put(self.repo, 'uncommitted.txt', 'not yet\n')
        self.fixture = self.repo / 'forbric-kernel/run/client-merged-pack'
        self.put(self.fixture, 'options.txt', 'original\n')
        self.put(self.repo, 'forbric-kernel/.dev/api/fabric-api.jar', 'jar')

        # origin/main moves on after the clone: the night must test what it fetches, not the stale ref.
        self.put(upstream, 'README.md', 'second\n')
        git('add', '-A', cwd=upstream)
        git('commit', '--quiet', '-m', 'second | with a pipe', cwd=upstream)
        git('push', '--quiet', 'origin', 'main', cwd=upstream)
        self.tested = git('rev-parse', 'HEAD', cwd=upstream)

        self.work = self.root / 'Forbric-nightly'
        self.night_log = self.root / 'night.jsonl'
        self.gh_log = self.root / 'gh.jsonl'
        fake_gh = self.put(self.root, 'fake-gh.py', FAKE_GH)
        self.environment = {'FAKE_NIGHT_LOG': str(self.night_log), 'FAKE_GH_LOG': str(self.gh_log),
                            'MC_DIR': str(self.root / 'minecraft')}
        for name in ('FAKE_RED_GATE', 'FAKE_INTEGRATION_EXIT', 'FAKE_INTEGRATION_SLEEP', 'FAKE_CHILD_PROOF'):
            self.environment[name] = ''
        self.gh = (sys.executable, str(fake_gh))

    def identify(self, repository):
        git('config', 'user.name', 'Nightly Test', cwd=repository)
        git('config', 'user.email', 'nightly@example.invalid', cwd=repository)
        git('config', 'commit.gpgsign', 'false', cwd=repository)
        git('config', 'core.hooksPath', self.root / 'no-hooks', cwd=repository)

    @staticmethod
    def put(directory, relative, text):
        path = Path(directory) / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def night(self, *extra, date=SATURDAY, **environment):
        out = io.StringIO()
        env = dict(self.environment, **environment)
        with patch.dict(os.environ, env), patch.object(nightly, 'BASH', (sys.executable,)), \
                patch.object(nightly, 'GH', self.gh), patch.object(nightly, 'PYTHON', (sys.executable,)):
            code = nightly.main(['--repo', str(self.repo), '--work', str(self.work), '--date', date] + list(extra), out)
        return code, out.getvalue()

    def calls(self, tool):
        if not self.night_log.exists():
            return []
        rows = [json.loads(line) for line in self.night_log.read_text().splitlines()]
        return [row for row in rows if row['tool'] == tool]

    def statuses(self):
        return [json.loads(line) for line in self.gh_log.read_text().splitlines()] if self.gh_log.exists() else []

    def checkout_state(self):
        refs = git('for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads', cwd=self.repo).splitlines()
        return dict(head=git('symbolic-ref', 'HEAD', cwd=self.repo), sha=git('rev-parse', 'HEAD', cwd=self.repo),
                    status=git('status', '--porcelain', '--untracked-files=all', cwd=self.repo), refs=set(refs))

    def assert_checkout_untouched(self, before):
        after = self.checkout_state()
        for key in ('head', 'sha', 'status'):
            self.assertEqual(before[key], after[key], f'the main checkout\'s {key} changed')
        # The one ref a night adds is the local ci-results branch its results worktree has checked out.
        self.assertEqual(before['refs'], {r for r in after['refs'] if not r.startswith('refs/heads/ci-results ')})

    def origin_refs(self):
        return dict(line.split(' ', 1)[::-1] for line in
                    git('for-each-ref', '--format=%(objectname) %(refname)', cwd=self.origin).splitlines())

    def published(self, path):
        return git('--git-dir', self.origin, 'show', f'ci-results:{path}')

    def test_a_night_tests_fetched_origin_main_in_its_own_worktree_and_leaves_the_checkout_alone(self):
        before = self.checkout_state()
        origin_before = self.origin_refs()
        code, output = self.night()
        self.assertEqual(0, code, output)

        self.assert_checkout_untouched(before)
        self.assertIn('refs/heads/ci-results', {r.split(' ')[0] for r in self.checkout_state()['refs']})

        self.assertEqual(self.tested, git('rev-parse', 'HEAD', cwd=self.work))
        detached = subprocess.run(['git', 'symbolic-ref', '-q', 'HEAD'], cwd=self.work, capture_output=True)
        self.assertNotEqual(0, detached.returncode, 'the nightly worktree must be detached')
        link = self.work / 'forbric-kernel/run/client-merged-pack'
        self.assertTrue(link.is_symlink())
        self.assertEqual(self.fixture.resolve(), link.resolve())
        self.assertEqual('original\n', (self.fixture / 'options.txt').read_text())

        [dev] = self.calls('dev')
        self.assertEqual(['integration'], dev['argv'])
        self.assertEqual(self.work.resolve(), Path(dev['cwd']).resolve())
        self.assertEqual(str(self.repo.resolve() / 'forbric-loader'), dev['FORBRIC_OLD'])
        self.assertEqual(str(self.root / 'minecraft'), dev['MC_DIR'])
        self.assertTrue(dev['linked'])
        [gates] = self.calls('gates')
        self.assertEqual(['-j', 'auto', '--skip', 'gate-m34-soak.sh', '--skip', 'gate-m58-world-upgrade.sh'], gates['argv'])

        # Only ci-results was pushed, and it is an orphan holding the dated summary and latest.md.
        origin_after = self.origin_refs()
        self.assertEqual(origin_before, {k: v for k, v in origin_after.items() if k != 'refs/heads/ci-results'})
        self.assertEqual('', git('--git-dir', self.origin, 'log', '-1', '--format=%P', 'ci-results'))
        summary = self.published(f'results/{SATURDAY}/summary.md')
        self.assertIn(f'# Forbric nightly {SATURDAY}: PASS', summary)
        self.assertIn(self.tested[:12], summary)
        self.assertIn('second \\| with a pipe', summary)
        self.assertIn('skipped: they run on Sundays', summary)
        self.assertIn('| test | 2 | 2 | 0 | 0.0 | 0 |', summary)
        self.assertNotIn(str(self.root), summary)
        self.assertIn(f'results/{SATURDAY}/summary.md', self.published('latest.md'))

        [status] = self.statuses()
        self.assertEqual(['api', f'repos/Ray-T-r/Minecraft-Forbric-mod-loader/statuses/{self.tested}',
                          '-f', 'state=success', '-f', 'context=nightly/dev-mac'], status[:6])
        self.assertTrue(any(a.startswith('target_url=https://github.com/') and a.endswith(f'/{SATURDAY}/summary.md')
                            for a in status))

    def test_sunday_runs_the_soak_as_a_release_run_and_a_red_gate_fails_the_night(self):
        before = self.checkout_state()
        self.assertEqual(0, self.night()[0])
        first = git('--git-dir', self.origin, 'rev-parse', 'ci-results')
        code, output = self.night(date=SUNDAY, FAKE_RED_GATE='gate-m9-client.sh')
        self.assertEqual(1, code, output)

        saturday, sunday = self.calls('gates')
        self.assertNotIn('--release', saturday['argv'])
        self.assertEqual(['-j', 'auto', '--release'], sunday['argv'])
        # The second night started from a clean tree, and cleaning it did not reach through the links.
        self.assertEqual([False, False], [row['stale'] for row in self.calls('dev')])
        self.assertEqual('original\n', (self.fixture / 'options.txt').read_text())
        self.assert_checkout_untouched(before)

        self.assertEqual(first, git('--git-dir', self.origin, 'log', '-1', '--format=%P', 'ci-results'))
        summary = self.published(f'results/{SUNDAY}/summary.md')
        self.assertIn(f'# Forbric nightly {SUNDAY}: FAIL', summary)
        self.assertIn('- `gate-m9-client.sh` RED (exit=1)', summary)
        self.assertIn('run: a `--release` run, nothing skipped', summary)
        self.assertIn(f'results/{SUNDAY}/summary.md', self.published('latest.md'))
        self.assertIn(f'# Forbric nightly {SATURDAY}: PASS', self.published(f'results/{SATURDAY}/summary.md'))
        status = self.statuses()[-1]
        self.assertIn('state=failure', status)
        self.assertTrue(any(a.startswith('description=') and 'gate-m9-client.sh' in a for a in status), status)

    def test_a_step_that_overruns_is_stopped_with_what_it_started_and_reported(self):
        proof = self.root / 'grandchild-still-alive.txt'
        started = time.monotonic()
        code, output = self.night('--integration-timeout-min', '0.03', FAKE_INTEGRATION_SLEEP='60',
                                  FAKE_CHILD_PROOF=str(proof))
        self.assertLess(time.monotonic() - started, 50, 'the timeout did not stop the step')
        self.assertEqual(1, code, output)
        self.assertEqual(1, len(self.calls('gates')), 'the gates must still run after the tests overran')
        self.assertIn('| integration | TIMED OUT after', self.published(f'results/{SATURDAY}/summary.md'))
        time.sleep(4)
        self.assertFalse(proof.exists(), 'a process the timed-out step started outlived it')

    def test_stopping_a_group_that_is_already_gone_is_not_an_error(self):
        # macOS answered EPERM for the group of a step whose leader had just been reaped, and the night died there
        # instead of reporting the timeout.
        process = Mock(pid=4242)
        for refusal in (ProcessLookupError, PermissionError):
            # create=True: Windows has neither os.killpg nor signal.SIGKILL, and this path is the posix one.
            with patch.object(nightly.os, 'name', 'posix'), \
                    patch.object(nightly.os, 'killpg', side_effect=refusal, create=True), \
                    patch.object(nightly.signal, 'SIGKILL', 9, create=True):
                nightly.stop(process, grace=0)
        self.assertEqual(4, process.wait.call_count)

    def test_dry_run_prints_the_night_and_changes_nothing(self):
        before = self.checkout_state()
        origin_before = self.origin_refs()
        code, output = self.night('--dry-run')
        self.assertEqual(0, code, output)
        self.assertEqual(before, self.checkout_state())
        self.assertEqual(origin_before, self.origin_refs())
        self.assertFalse(self.work.exists())
        self.assertFalse(Path(str(self.work) + '-results').exists())
        self.assertEqual([], self.calls('dev') + self.calls('gates'))
        self.assertEqual([], self.statuses())
        for expected in ('[dry-run] git -C', 'fetch --quiet --prune origin', 'worktree add --quiet --detach',
                         'tools/dev.py integration', '--skip gate-m34-soak.sh --skip gate-m58-world-upgrade.sh',
                         'push --quiet origin ci-results',
                         'api repos/Ray-T-r/Minecraft-Forbric-mod-loader/statuses/', '# Forbric nightly 2026-10-03: DRY RUN'):
            self.assertIn(expected, output)

    def test_the_main_checkout_and_folders_in_it_are_never_used_as_the_nightly_worktree(self):
        before = self.checkout_state()
        origin_before = self.origin_refs()
        for work in (self.repo, self.repo / 'nightly'):
            with self.subTest(work=work.name):
                self.work = work
                code, output = self.night()
                self.assertEqual(2, code, output)
                self.assertIn('is the main checkout or inside it', output)
                self.assertEqual(before, self.checkout_state(), 'the main checkout changed')
                self.assertEqual(origin_before, self.origin_refs())
                self.assertFalse((self.repo / 'nightly').exists())
                self.assertEqual([], self.calls('dev'))
                self.assertEqual([], self.statuses())


class ScheduleAndSummaryTest(unittest.TestCase):
    def test_the_soak_runs_on_sundays_only(self):
        week = [datetime.date(2026, 9, 28) + datetime.timedelta(days=n) for n in range(7)]
        self.assertEqual([False] * 6 + [True], [nightly.soak_tonight(day) for day in week])
        self.assertTrue(all(nightly.soak_tonight(day, 'always') for day in week))
        self.assertFalse(any(nightly.soak_tonight(day, 'never') for day in week))
        self.assertEqual(['-j', '4', '--skip', 'gate-m34-soak.sh', '--skip', 'gate-m58-world-upgrade.sh'],
                         nightly.gates_arguments(False, 4))
        self.assertEqual(['-j', 'auto', '--release'], nightly.gates_arguments(True, 'auto'))

    def test_every_gate_a_weekday_skips_is_a_gate_in_this_tree(self):
        # gates-parallel.py exits 2 on a --skip naming no gate, before running any: a weekday night naming a gate that
        # is not in the tree yet would fail every gate step while the stand-in above, which knows only its own names,
        # could not tell.
        arguments = nightly.gates_arguments(False, 1)
        named = [arguments[i + 1] for i, a in enumerate(arguments) if a == '--skip']
        self.assertTrue(named)
        for gate in named:
            self.assertTrue((ROOT / 'forbric-kernel/run' / gate).is_file(), gate + ' is not a gate in this tree')

    def test_summary_from_junit_xml_and_gate_results(self):
        work = Path(tempfile.mkdtemp(prefix='forbric nightly summary '))
        self.addCleanup(remove_tree, work)
        (work / 'tools').mkdir()
        shutil.copy(ROOT / 'tools/junit_report.py', work / 'tools/junit_report.py')
        results = work / 'forbric-kernel/build/test-results'
        (results / 'test').mkdir(parents=True)
        (results / 'test/TEST-a.xml').write_text(
            '<testsuite name="a" tests="3" skipped="1" failures="1" errors="0">'
            '<testcase classname="net.forbric.A" name="passes"/>'
            '<testcase classname="net.forbric.A" name="breaks"><failure type="AssertionError" message="no"/></testcase>'
            f'<testcase classname="net.forbric.A" name="waits"><skipped message="fixture absent: {work}/x.jar"/></testcase>'
            '</testsuite>')
        gates = work / 'summary.txt'
        gates.write_text('RESULT gate-m0.sh GREEN (exit=0)\nRESULT gate-m9-client.sh RED (exit=1)\n'
                         'RESULT gate-m34-soak.sh SKIP (explicit --skip)\nnoise\n')

        night = nightly.Night(datetime.date(2026, 10, 3), False, False)
        night.sha, night.subject, night.ref = 'a' * 40, 'Do a thing', 'origin/main'
        night.integration = nightly.Step('integration', 'python3 tools/dev.py integration', ran=True, returncode=1,
                                         seconds=600)
        night.gates = nightly.Step('gates', 'bash gates-all.sh', ran=True, returncode=1, seconds=1800)
        night.junit = nightly.junit_summary(nightly.Runner(False, io.StringIO()), work)
        night.gate_results = nightly.read_gate_results(gates)
        night.missing_fixtures = ['forbric-kernel/run/client-popular']
        text = nightly.render_summary(night)

        self.assertIn('# Forbric nightly 2026-10-03: FAIL', text)
        self.assertIn('| integration | FAILED (exit 1) in 10 min |', text)
        self.assertIn('| gates | FAILED (exit 1) in 30 min: 1 GREEN, 1 RED, 1 SKIP |', text)
        self.assertIn('| test | 3 | 2 | 1 | 33.3 | 1 |', text)
        self.assertIn('Did not execute (no results): `transferTest`', text)
        self.assertIn('- `test` net.forbric.A.breaks (AssertionError)', text)
        self.assertIn('fixture absent: $ROOT/x.jar', text)
        self.assertNotIn(str(work), text)
        self.assertIn('- `gate-m9-client.sh` RED (exit=1)', text)
        self.assertIn('- `gate-m34-soak.sh` SKIP (explicit --skip)', text)
        self.assertIn('All 3 RESULT lines', text)
        self.assertIn('`forbric-kernel/run/client-popular`', text)
        self.assertEqual('integration FAILED (exit 1); gates FAILED (exit 1): 1 GREEN, 1 RED, 1 SKIP (gate-m9-client.sh)',
                         nightly.status_description(night))

        night.gate_results = [(f'gate-m{n}-with-a-long-name.sh', 'RED', '(exit=1)') for n in range(20)]
        self.assertLessEqual(len(nightly.status_description(night)), 140)


if __name__ == '__main__':
    unittest.main()
