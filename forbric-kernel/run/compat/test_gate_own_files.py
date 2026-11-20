"""A gate's checks read only files its own run wrote: lib.sh's discard_earlier_run, and gate-m17's use of it.

A gate writes its logs under fixed names in build/, and a step that does not run writes none, so the file of the run
before stays where the step's checks look. lib.sh's readable() makes a check fail on a missing or empty log; it cannot
tell this run's log from the last run's. gate-m17 printed eighteen PASS lines that way in the acceptance sweep of
2026-11-20: its first install failed on a download, no profile was written, so no command resolved and no client
started, and sixteen checks passed on the resolved command and the client log an earlier, green run had left (the
other two said what a profile that did not exist "does not also name").

Two halves. The first runs the real lib.sh in a throwaway tree: files an earlier run left are gone before the gate
writes anything, so the same check that passed on a stale log fails with "no log to read"; a file the gate keeps on
purpose, another gate's files and the file the run's own output goes to are left alone. The second reads gate-m17 as
text: it calls discard_earlier_run before it runs anything, every file it names under build/ is one that call removes,
and the two "does not also name" lines cannot pass without a profile.
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

COMPAT = Path(__file__).resolve().parent
RUN = COMPAT.parent
LIB = RUN / 'lib.sh'
M17 = RUN / 'gate-m17-installer.sh'

# What a gate does with a log: one positive check and one absence check, on the client log of this run.
CHECKS = ('check "it entered the world" "client-ready after" "$BUILD/gate-mX-client.log"; '
          'check_absent "no client crash" "Preparing crash report" "$BUILD/gate-mX-client.log"; echo "FAIL=$FAIL"')


# The gates and lib.sh are bash scripts that run on macOS and Linux only; on Windows `bash` is WSL's launcher (no
# distribution on CI's windows-latest), so there is nothing to run there (as test_lib_guards).
@unittest.skipIf(os.name == 'nt' or not shutil.which('bash'), 'runs lib.sh, a POSIX gate harness')
class DiscardEarlierRunTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.mkdtemp(prefix='gate-own-files-')
        root = Path(self.scratch)
        self.kernel = root / 'wt' / 'forbric-kernel'
        (self.kernel / 'run').mkdir(parents=True)
        shutil.copy(LIB, self.kernel / 'run' / 'lib.sh')
        (root / 'stage' / 'run').mkdir(parents=True)  # FORBRIC_OLD
        self.build = self.kernel / 'build'
        self.build.mkdir()
        # What an earlier, green run left: its client log and its resolved command, an unpacked directory, a link,
        # a file the gate keeps across runs, and another gate's log.
        (self.build / 'gate-mX-client.log').write_text('[ClientSmoke] client-ready after 61 ticks\n')
        (self.build / 'gate-mX-command.txt').write_text('java\n--gameJar\n')
        (self.build / 'gate-mX-old-install').mkdir()
        (self.build / 'gate-mX-old-install' / 'base.jar').write_text('jar')
        (self.build / 'gate-mX-dangling').symlink_to(root / 'nowhere')
        (self.build / 'gate-mX-kept-install.log').write_text('Installed.\n')
        (self.build / 'gate-mY-client.log').write_text('[ClientSmoke] client-ready after 61 ticks\n')
        self.planted = self.names()
        self.assertEqual(6, len(self.planted))

    def tearDown(self):
        shutil.rmtree(self.scratch, ignore_errors=True)

    def gate(self, body, stdout=None):
        env = dict(os.environ, FORBRIC_OLD=str(Path(self.scratch) / 'stage'))
        for name in ('FORBRIC_CAUGHT_FILE', 'FORBRIC_CAUGHT_GATE', 'FORBRIC_CAUGHT_STRICT'):
            env.pop(name, None)
        command = ['bash', '-c', '. "$1"; ' + body, 'gate-mX', str(self.kernel / 'run' / 'lib.sh')]
        if stdout is None:
            return subprocess.run(command, cwd=self.kernel, env=env, capture_output=True, text=True, timeout=60)
        with open(stdout, 'w') as stream:
            return subprocess.run(command, cwd=self.kernel, env=env, stdout=stream, stderr=subprocess.PIPE, text=True, timeout=60)

    def names(self):
        return sorted(p.name for p in self.build.iterdir() if p.name != 'caught')

    def test_without_it_a_step_that_did_not_run_passes_on_the_earlier_runs_log(self):
        # The control: this is what gate-m17 did. No client ran here, and both checks pass.
        r = self.gate(CHECKS)
        self.assertIn('[kernel] PASS it entered the world (1)', r.stdout, r.stderr)
        self.assertIn('[kernel] PASS no client crash (absent)', r.stdout)
        self.assertIn('FAIL=0', r.stdout)

    def test_a_step_that_did_not_run_fails_its_checks(self):
        r = self.gate('discard_earlier_run gate-mX- gate-mX-kept-install.log; ' + CHECKS)
        self.assertNotIn('[kernel] PASS', r.stdout, r.stderr)
        self.assertIn('[kernel] FAIL it entered the world (no log to read: ', r.stdout)
        self.assertIn('[kernel] FAIL no client crash (no log to read: ', r.stdout)
        self.assertIn('FAIL=1', r.stdout)
        # Everything of the earlier run is gone, a directory and a dangling link included; the kept file and the other
        # gate's log are untouched.
        self.assertEqual(['gate-mX-kept-install.log', 'gate-mY-client.log'], self.names())
        self.assertEqual('Installed.\n', (self.build / 'gate-mX-kept-install.log').read_text())

    def test_a_step_that_ran_passes_on_what_it_wrote(self):
        wrote = 'echo "[ClientSmoke] client-ready after 60 ticks" > "$BUILD/gate-mX-client.log"; '
        r = self.gate('discard_earlier_run gate-mX-; ' + wrote + CHECKS)
        self.assertIn('[kernel] PASS it entered the world (1)', r.stdout, r.stderr)
        self.assertIn('[kernel] PASS no client crash (absent)', r.stdout)
        self.assertIn('FAIL=0', r.stdout)
        self.assertEqual(['gate-mX-client.log', 'gate-mY-client.log'], self.names(), 'no keep name: the kept file goes too')

    def test_the_file_the_runs_own_output_goes_to_is_not_removed(self):
        # `bash run/gate-mX.sh > build/gate-mX-installer.out` (CI writes its gates' output exactly so): the file has the
        # gate's prefix, and it is this run's. Unlinked, the run's whole output would be lost with it.
        out = self.build / 'gate-mX-installer.out'
        r = self.gate('echo before; discard_earlier_run gate-mX-; echo after; ' + CHECKS, stdout=out)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(['gate-mX-installer.out', 'gate-mY-client.log'], self.names())
        text = out.read_text()
        self.assertIn('before\nafter\n', text)
        self.assertIn('[kernel] FAIL it entered the world (no log to read: ', text)
        # The same name left by an earlier run, with this run's output going elsewhere, is an earlier run's file.
        r = self.gate('discard_earlier_run gate-mX-; echo done')
        self.assertIn('done', r.stdout, r.stderr)
        self.assertEqual(['gate-mY-client.log'], self.names())

    def test_it_needs_a_prefix_and_takes_a_missing_build_directory(self):
        r = self.gate('discard_earlier_run; echo not-reached')
        self.assertEqual(3, r.returncode)
        self.assertIn('needs the name prefix', r.stderr)
        self.assertNotIn('not-reached', r.stdout)
        self.assertEqual(self.planted, self.names(), 'nothing is removed without a prefix')
        r = self.gate('BUILD="$BUILD/none"; discard_earlier_run gate-mX-; echo fine')
        self.assertIn('fine', r.stdout, r.stderr)


class GateM17ReadsOnlyItsOwnRunTest(unittest.TestCase):
    """gate-m17 as text; runs on every system."""

    @classmethod
    def setUpClass(cls):
        cls.lines = M17.read_text(encoding='utf-8').splitlines()
        cls.code = [(number, line) for number, line in enumerate(cls.lines, 1) if not line.lstrip().startswith('#')]

    def call(self):
        found = [(number, line) for number, line in self.code if re.match(r'\s*discard_earlier_run\b', line)]
        self.assertEqual(1, len(found), 'gate-m17 removes an earlier run\'s files once')
        return found[0]

    def test_it_discards_the_earlier_runs_files_before_it_runs_anything(self):
        number, line = self.call()
        self.assertEqual(line, line.lstrip(), 'not inside a condition or a function')
        words = line.split()
        self.assertEqual('gate-m17-', words[1])
        # The first line that does something: a step, a function call that builds or installs, an exit on a missing
        # fixture. Assignments, function definitions and the lib.sh guards before it write and read nothing in build/.
        first = next(n for n, text in self.code
                     if re.match(r'(step |kernel_jar\b|if lay_out_beta2\b|\[ -d |java |python3 )', text))
        self.assertLess(number, first, 'the removal comes before anything is run')
        made = next(n for n, text in self.code if text.startswith('mkdir -p "$BUILD"'))
        self.assertLess(made, number)

    def test_every_file_it_names_in_build_is_one_the_removal_covers(self):
        _, line = self.call()
        prefix, kept = line.split()[1], set(line.split()[2:])
        named = set()
        for _, text in self.code:
            named |= set(re.findall(r'\$BUILD/([A-Za-z0-9._-]+)', text))
        self.assertGreater(len(named), 20)
        self.assertEqual(set(), {name for name in named if not name.startswith(prefix)},
                         'a file outside the prefix would survive from an earlier run')
        # What is kept on purpose is the one-time log of phase U's old install, and no check reads it.
        self.assertEqual({'gate-m17-beta2-install.log'}, kept)
        for _, text in self.code:
            if re.match(r'\s*(\[ "\$\w+" -eq 0 \] && )?(check|check_absent|check_exactly|assert_eq)\b', text):
                for name in kept:
                    self.assertNotIn(name, text)

    def test_a_missing_profile_fails_what_is_said_about_the_profile(self):
        # "The profile does not also name net.neoforge" passed on a profile that did not exist (grep fails either way).
        text = '\n'.join(text for _, text in self.code)
        loop = text[text.index("for other in 'net\\.neoforge' 'minecraftforge'; do"):]
        loop = loop[:loop.index('\ndone')]
        guard = loop.index('if [ ! -s "$PROFILE" ]; then')
        self.assertIn('FAIL the profile does not also name $other (no profile to read: $PROFILE)', loop)
        self.assertLess(guard, loop.index('grep -q "$other" "$PROFILE"'), 'the profile is looked for before it is searched')
        self.assertLess(guard, loop.index('PASS the profile does not also name $other'))


if __name__ == '__main__':
    unittest.main()
