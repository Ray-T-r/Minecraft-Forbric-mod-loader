"""lib.sh's control-arm cache (control_arm_dir, control_arm_store), which gate-m31 keeps its pure-vanilla arm in.

A kept arm stands in for a boot, so the risks are the cache's own: an arm reused for inputs it was not measured on, an
arm reused when the run asked for a fresh measurement, and a half-written arm read as whole.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

LIB = Path(__file__).resolve().parent.parent / 'lib.sh'


def bash(script, env):
    # lib.sh is sourced the way a helper does: no gate name, so it takes no caught-failure file of its own.
    r = subprocess.run(['bash', '-c', f'. "{LIB}"; trap - EXIT; {script}'], capture_output=True, text=True,
                       env={'PATH': '/usr/bin:/bin', 'HOME': env.pop('HOME', '/nonexistent'), **env})
    return r.returncode, r.stdout.strip(), r.stderr


class ControlArmTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.key = self.root / 'arm.key'
        self.key.write_text('vanilla jar abc\nseed forbrickernel\n')
        self.env = {'FORBRIC_GATE_CACHE': str(self.root / 'cache'), 'FORBRIC_OLD': str(self.root)}

    def tearDown(self):
        self.tmp.cleanup()

    def dir(self, **extra):
        rc, out, err = bash(f'control_arm_dir gate-mx "{self.key}"', {**self.env, **extra})
        self.assertEqual(0, rc, err)
        return out

    def test_the_directory_follows_every_line_of_the_key(self):
        first = self.dir()
        self.assertTrue(first.startswith(str(self.root / 'cache' / 'arms' / 'gate-mx') + '/'), first)
        self.assertEqual(first, self.dir(), 'the same inputs name the same arm')
        self.key.write_text('vanilla jar abc\nseed another\n')
        self.assertNotEqual(first, self.dir(), 'a changed input names another arm')

    def test_a_fresh_run_and_a_run_without_a_cache_keep_nothing(self):
        self.assertEqual('', self.dir(FORBRIC_GATE_FRESH='1'), 'an acceptance and --no-cache measure the arm afresh')
        self.assertEqual('', self.dir(FORBRIC_GATE_CACHE='0'))

    def test_an_arm_is_stored_whole_or_not_at_all(self):
        arm = Path(self.dir())
        world = self.root / 'world'
        (world / 'region').mkdir(parents=True)
        (world / 'region' / 'r.0.0.mca').write_bytes(b'chunks')
        log = self.root / 'vanilla.log'
        log.write_text('Done (\n')
        rc, _, err = bash(f'control_arm_store "{arm}" "{world}" "{log}"', dict(self.env))
        self.assertEqual(0, rc, err)
        self.assertEqual(b'chunks', (arm / 'world' / 'region' / 'r.0.0.mca').read_bytes())
        self.assertEqual('Done (\n', (arm / 'vanilla.log').read_text())
        self.assertTrue((arm / 'complete').is_file(), 'a reader requires the stamp')
        self.assertEqual([arm.name], [p.name for p in arm.parent.iterdir()], 'no part directory is left beside it')
        rc, _, _ = bash(f'control_arm_store "{arm}.b" "{self.root}/missing" "{log}"', dict(self.env))
        self.assertFalse(Path(f'{arm}.b').exists(), 'a copy that failed leaves no arm, so nothing half-written is read')


if __name__ == '__main__':
    unittest.main()
