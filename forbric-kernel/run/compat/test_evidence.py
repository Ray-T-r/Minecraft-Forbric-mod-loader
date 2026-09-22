import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("evidence.py"))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.run_git("init", "-q")
        (self.source / "Main.java").write_text("class Main {}")
        self.run_git("add", "Main.java")
        self.run_git("-c", "user.name=Test", "-c", "user.email=rt.ge.jerry@gmail.com", "commit", "-qm", "fixture")
        self.artifact = self.root / "kernel.jar"
        self.artifact.write_bytes(b"compiled fixture")
        self.mods = self.root / "mods"
        self.mods.mkdir()
        self.report = self.root / "evidence.json"

    def run_git(self, *args):
        subprocess.run(["git", "-C", str(self.source), *args], check=True, capture_output=True)

    def capture(self):
        return evidence.capture(self.source, {"kernel": self.artifact}, [self.mods], self.report)

    def test_roundtrip_and_artifact_replacement_same_size(self):
        self.capture()
        evidence.verify(self.report)
        self.artifact.write_bytes(b"differentfixture")
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            evidence.verify(self.report)

    def test_source_change_and_new_untracked_source(self):
        self.capture()
        (self.source / "Added.java").write_text("class Added {}")
        with self.assertRaisesRegex(ValueError, "source revision or contents"):
            evidence.verify(self.report)

    def test_source_deletion(self):
        self.capture()
        (self.source / "Main.java").unlink()
        with self.assertRaises(ValueError):
            evidence.verify(self.report)

    def test_added_mod_is_not_invisible(self):
        self.capture()
        with zipfile.ZipFile(self.mods / "new.jar", "w") as jar:
            jar.writestr("fabric.mod.json", json.dumps({"id": "new", "version": "1.0"}))
        with self.assertRaisesRegex(ValueError, "mod inventory changed"):
            evidence.verify(self.report)

    def test_nested_mod_version_and_hash_are_recorded(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as jar:
            jar.writestr("fabric.mod.json", '{"id":"child","version":"2.0"}')
        with zipfile.ZipFile(self.mods / "parent.jar", "w") as jar:
            jar.writestr("fabric.mod.json", '{"id":"parent","version":"1.0","jars":[{"file":"META-INF/jars/child.jar"}]}')
            jar.writestr("META-INF/jars/child.jar", nested.getvalue())
        result = self.capture()
        inventory = result["mods"][0]["jars"][0]["inventory"]
        self.assertEqual(["parent", "child"], [r["declarations"][0]["id"] for r in inventory])
        self.assertEqual("2.0", inventory[1]["declarations"][0]["version"])
        evidence.verify(self.report)

    def test_missing_artifact_and_incomplete_release_fail(self):
        self.artifact.unlink()
        with self.assertRaises(FileNotFoundError):
            self.capture()
        with self.assertRaisesRegex(ValueError, "release evidence missing artifacts"):
            evidence.capture(self.source, {}, [self.mods], self.report, release=True)

    def test_output_inside_source_is_not_self_referential(self):
        self.report = self.source / "run-evidence.json"
        self.capture()
        evidence.verify(self.report)

    def test_release_requires_clean_source_including_new_files(self):
        artifacts = {name: self.artifact for name in evidence.RELEASE_ROLES}
        evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        evidence.verify(self.report)
        (self.source / "New.java").write_text("class New {}")
        with self.assertRaisesRegex(ValueError, "committed source changes"):
            evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)

    def test_green_command_with_mutated_input_is_red(self):
        result = evidence.verified_run(self.source, {"kernel": self.artifact}, [self.mods], self.report,
                                      [sys.executable, "-c", "from pathlib import Path; import sys; Path(sys.argv[1]).write_bytes(b'changed')", str(self.artifact)])
        self.assertEqual(0, result["exitCode"])
        self.assertFalse(result["inputsUnchanged"])
        self.assertFalse(result["commandPassed"])

    def test_failed_command_cannot_pass_with_unchanged_inputs(self):
        result = evidence.verified_run(self.source, {"kernel": self.artifact}, [self.mods], self.report,
                                      [sys.executable, "-c", "raise SystemExit(7)"])
        self.assertEqual(7, result["exitCode"])
        self.assertTrue(result["inputsUnchanged"])
        self.assertFalse(result["commandPassed"])

    def test_success_records_duration_and_log(self):
        result = evidence.verified_run(self.source, {"kernel": self.artifact}, [self.mods], self.report,
                                      [sys.executable, "-c", "print('observed action')"])
        self.assertTrue(result["commandPassed"])
        self.assertGreater(result["elapsedSeconds"], 0)
        self.assertIn("observed action", Path(result["log"]).read_text())


if __name__ == "__main__":
    unittest.main()
