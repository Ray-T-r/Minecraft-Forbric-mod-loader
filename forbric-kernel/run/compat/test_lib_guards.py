"""lib.sh's refuse_shared_rundir keeps a gate's rundir off the fixtures every worktree shares.

A worktree links the big client packs and the downloaded-mod directories to the main checkout's, and a gate wipes and
writes its rundir. A gate pointed at one of those links -- a fixed default rundir, RUNDIR=run/client-merged-pack, or a
scratch path that resolves into one -- rewrites a fixture the main checkout and every other worktree read, and nothing
names it (Stage 4, D39/D46). The guard is called by the gates that have a fixed or default rundir; this test runs the
real lib.sh, copied into a throwaway tree laid out like a worktree beside a main checkout, so it never touches the
fixtures it is about.

The cases are chosen so that a guard reduced to `return 0` fails most of them, and so that a guard that matched names
by prefix (client-merged-pack-clone) or refused every symlink (a clone path under /tmp, which is itself a link on
macOS) fails too.
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

COMPAT = Path(__file__).resolve().parent
LIB = COMPAT.parent / 'lib.sh'

# D46: the named shared fixtures.
D46_FIXTURES = {'client-merged-pack', 'client-popular', 'client-kernel', 'client-neo-pack', 'carry-on', 'modmenu',
                'neoforge-builds', 'forge-swap'}


class RefuseSharedRundirTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.mkdtemp(prefix='lib-guards-')
        root = Path(cls.scratch)
        cls.root = root
        cls.main = root / 'main' / 'forbric-kernel'
        cls.kernel = root / 'wt' / 'forbric-kernel'
        run = cls.kernel / 'run'
        run.mkdir(parents=True)
        shutil.copy(LIB, run / 'lib.sh')
        (root / 'stage' / 'run').mkdir(parents=True)  # FORBRIC_OLD
        for name in ('client-merged-pack', 'neoforge-builds', 'carry-on', 'client-popular'):
            (cls.main / 'run' / name / 'mods').mkdir(parents=True)
        # This worktree: two fixtures linked to main's, client-popular a real local directory (the Stage 3 layout).
        (run / 'client-merged-pack').symlink_to(cls.main / 'run' / 'client-merged-pack')
        (run / 'neoforge-builds').symlink_to(cls.main / 'run' / 'neoforge-builds')
        (run / 'client-popular' / 'mods').mkdir(parents=True)
        (run / 'client-carry-m54').mkdir()
        (run / 'installed').mkdir()
        (root / 'outside').mkdir()
        (run / 'energy-api').symlink_to(root / 'outside')
        (run / 'dangling').symlink_to(root / 'nowhere')
        scratch_dir = root / 'scratch'
        scratch_dir.mkdir()
        shutil.copytree(cls.main / 'run' / 'client-merged-pack', scratch_dir / 'clone')
        (scratch_dir / 'via-link').symlink_to(cls.main / 'run' / 'client-merged-pack')
        (scratch_dir / 'clone-link').symlink_to(scratch_dir / 'clone')
        # D54: the frozen swap fixture lives outside every checkout, and a worktree cut before it existed has no
        # run/forge-swap link. HOME is a throwaway directory here, so the default home is never the real one.
        (root / 'home' / 'Forbric-backups').mkdir(parents=True)
        (root / 'swap-elsewhere' / 'mods').mkdir(parents=True)
        (scratch_dir / 'via-swap').symlink_to(root / 'swap-elsewhere')

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.scratch, ignore_errors=True)

    def guard(self, rundir, cwd=None, **extra_env):
        env = dict(os.environ, FORBRIC_OLD=str(self.root / 'stage'), FORBRIC_MAIN_KERNEL=str(self.main),
                   HOME=str(self.root / 'home'))
        env.pop('FORBRIC_CAUGHT_FILE', None)
        env.pop('FORBRIC_CAUGHT_GATE', None)
        env.pop('FORBRIC_FORGE_SWAP', None)
        env.update(extra_env)
        script = '. "$1"; refuse_shared_rundir "$2"; echo "[guard] accepted"'
        return subprocess.run(['bash', '-c', script, 'guard', str(self.kernel / 'run' / 'lib.sh'), str(rundir)],
                              cwd=cwd or self.kernel, env=env, capture_output=True, text=True, timeout=60)

    def assertRefused(self, rundir, cwd=None, **extra_env):
        r = self.guard(rundir, cwd, **extra_env)
        self.assertEqual(3, r.returncode, f'{rundir} was not refused: rc={r.returncode} out={r.stdout!r} err={r.stderr!r}')
        self.assertIn('[kernel] FAIL refusing rundir', r.stdout, rundir)
        self.assertNotIn('[guard] accepted', r.stdout, rundir)

    def assertAccepted(self, rundir, cwd=None, **extra_env):
        r = self.guard(rundir, cwd, **extra_env)
        self.assertEqual(0, r.returncode, f'{rundir} was refused: rc={r.returncode} out={r.stdout!r} err={r.stderr!r}')
        self.assertIn('[guard] accepted', r.stdout, rundir)
        self.assertNotIn('FAIL', r.stdout, rundir)

    def test_the_fixture_list_is_d46s(self):
        env = dict(os.environ, FORBRIC_OLD=str(self.root / 'stage'))
        r = subprocess.run(['bash', '-c', '. "$1"; printf "%s\\n" $SHARED_FIXTURES', 'guard',
                            str(self.kernel / 'run' / 'lib.sh')], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(D46_FIXTURES, set(r.stdout.split()))

    def test_a_linked_fixture_is_refused_by_its_worktree_name(self):
        self.assertRefused('run/client-merged-pack')
        self.assertRefused(self.kernel / 'run' / 'neoforge-builds')
        self.assertRefused('run/../run/./client-merged-pack')

    def test_a_path_inside_a_fixture_is_refused_even_before_it_exists(self):
        self.assertRefused('run/client-merged-pack/mods')
        self.assertRefused('run/client-merged-pack/saves/new-world')

    def test_the_main_checkouts_fixture_is_refused_by_its_own_path(self):
        self.assertRefused(self.main / 'run' / 'client-merged-pack')
        self.assertRefused(self.main / 'run' / 'carry-on' / 'mods')

    def test_a_scratch_path_that_resolves_into_a_fixture_is_refused(self):
        self.assertRefused(self.root / 'scratch' / 'via-link')
        self.assertRefused(self.root / 'scratch' / 'via-link' / 'saves')

    def test_a_fixture_that_is_a_real_directory_here_is_refused(self):
        self.assertRefused('run/client-popular')

    def test_a_fixture_name_is_refused_when_nothing_is_there_yet(self):
        self.assertRefused('run/forge-swap')
        self.assertRefused('run/client-kernel')

    def test_the_swap_fixtures_own_home_is_refused_without_a_worktree_link(self):
        home_swap = self.root / 'home' / 'Forbric-backups' / 'forge-swap'
        self.assertFalse((self.kernel / 'run' / 'forge-swap').exists())
        # Default home, before the fixture exists and after.
        self.assertRefused(home_swap / 'x')
        home_swap.mkdir()
        try:
            self.assertRefused(home_swap)
            self.assertRefused(home_swap / 'saves' / 'w')
        finally:
            home_swap.rmdir()
        self.assertAccepted(self.root / 'home' / 'Forbric-backups' / 'forge-swap-clone')
        # FORBRIC_FORGE_SWAP moves it: the new home and a link into it are refused, the default is free again.
        moved = {'FORBRIC_FORGE_SWAP': str(self.root / 'swap-elsewhere')}
        self.assertRefused(self.root / 'swap-elsewhere' / 'mods', **moved)
        self.assertRefused(self.root / 'scratch' / 'via-swap', **moved)
        self.assertAccepted(home_swap / 'x', **moved)

    def test_a_rundir_that_leaves_run_through_a_symlink_is_refused(self):
        self.assertRefused('run/energy-api/instance')

    def test_a_dangling_link_and_a_missing_argument_are_refused(self):
        self.assertRefused('run/dangling')
        self.assertRefused('')

    def test_relative_spellings_resolve_from_the_callers_directory(self):
        self.assertRefused('client-merged-pack', cwd=self.kernel / 'run')
        self.assertAccepted('server-kernel', cwd=self.kernel / 'run')

    def test_clones_and_own_rundirs_are_accepted(self):
        self.assertAccepted(self.root / 'scratch' / 'clone')
        self.assertAccepted(self.root / 'scratch' / 'clone-link')
        self.assertAccepted('run/client-carry-m54')
        self.assertAccepted('run/server-kernel')
        self.assertAccepted('run/installed')

    def test_a_name_that_only_starts_like_a_fixture_is_accepted(self):
        self.assertAccepted('run/client-merged-pack-clone')
        self.assertAccepted(self.main / 'run' / 'client-merged-pack-m9')


if __name__ == '__main__':
    unittest.main()
