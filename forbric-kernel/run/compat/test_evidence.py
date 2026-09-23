import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
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
        # The staged root the kernel build reads comes from FORBRIC_OLD; never the developer's real one here.
        self.staged = self.root / "staged"
        environment = mock.patch.dict(os.environ, {"FORBRIC_OLD": str(self.staged)})
        environment.start()
        self.addCleanup(environment.stop)

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

    def test_literal_control_characters_in_real_fabric_descriptions_preserve_identity(self):
        path = self.mods / "description.jar"
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("fabric.mod.json", '{"id":"description","version":"2.0","description":"line one\nline two\tend"}')
        expected = evidence.digest(path)
        result = self.capture()
        recorded = result["mods"][0]["jars"][0]
        self.assertEqual(expected, recorded["sha256"])
        self.assertEqual("2.0", recorded["inventory"][0]["declarations"][0]["version"])
        evidence.verify(self.report)

    def test_jarjar_custom_paths_are_inventoried_and_missing_declared_jars_fail(self):
        child = io.BytesIO()
        with zipfile.ZipFile(child, "w") as jar:
            jar.writestr("fabric.mod.json", '{"id":"child","version":"3.0"}')
        path = self.mods / "parent.jar"
        for exists in (True, False):
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("fabric.mod.json", '{"id":"parent","version":"1.0"}')
                jar.writestr("META-INF/jarjar/metadata.json", json.dumps({"jars": [{"path": "private-libs/child.jar"}]}))
                if exists:
                    jar.writestr("private-libs/child.jar", child.getvalue())
            if exists:
                entries = self.capture()["mods"][0]["jars"][0]["inventory"]
                self.assertEqual(["parent", "child"], [row["declarations"][0]["id"] for row in entries])
                self.assertEqual("3.0", entries[1]["declarations"][0]["version"])
                evidence.verify(self.report)
            else:
                with self.assertRaisesRegex(ValueError, "declared nested jar missing.*private-libs/child.jar"):
                    self.capture()

    def test_malformed_metadata_names_the_archive_and_is_not_silently_dropped(self):
        with zipfile.ZipFile(self.mods / "malformed.jar", "w") as jar:
            jar.writestr("fabric.mod.json", '{"id": broken')
        with self.assertRaisesRegex(ValueError, "malformed.jar::fabric.mod.json"):
            self.capture()

    def test_output_inside_source_is_not_self_referential(self):
        self.report = self.source / "run-evidence.json"
        self.capture()
        evidence.verify(self.report)

    def jar(self, name, entries):
        path = self.root / "artifacts" / name
        path.parent.mkdir(exist_ok=True)
        with zipfile.ZipFile(path, "w") as jar:
            for entry, data in entries.items():
                jar.writestr(entry, data)
        return path

    @staticmethod
    def carrier(title, version):
        return {"META-INF/MANIFEST.MF": f"Manifest-Version: 1.0\nImplementation-Title: {title}\n"
                                        f"Implementation-Version: {version}\n\nName: x/\nImplementation-Version: 0\n"}

    def release_artifacts(self, neo_version="26.2.0.88", provenance=True, **provenance_changes):
        """A complete, consistent release set: pinned readings, staged build inputs, merged-base provenance."""
        game = {"version.json": json.dumps({"id": "26.2"})}
        artifacts = {name: self.jar(name + ".jar", dict(game, **{"marker": name}))
                     for name in ("vanilla", "forge-patched", "neo-patched", "merged")}
        artifacts["forge-runtime"] = self.jar("forge-runtime.jar", self.carrier("MinecraftForge", "65.0.1"))
        artifacts["forge-interop"] = self.jar("forge-interop.jar", dict(self.carrier("MinecraftForge", "65.0.1"), i="1"))
        artifacts["neo-runtime"] = self.jar("neo-runtime.jar", self.carrier("NeoForge", neo_version))
        for name in ("kernel", "kernel-runtime", "merge-tools"):
            artifacts[name] = self.jar(name + ".jar", {"marker": name})
        for role, relative in evidence.STAGED_BUILD_INPUTS.items():
            target = self.staged / "run" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(artifacts[role].read_bytes())
        tool = self.source / evidence.MERGE_TOOL_SOURCES / "MergedBaseBuilder.java"
        if not tool.exists():
            tool.parent.mkdir(parents=True)
            tool.write_text("class MergedBaseBuilder {}")
            self.run_git("add", ".")
            self.run_git("-c", "user.name=Test", "-c", "user.email=rt.ge.jerry@gmail.com", "commit", "-qm", "tool")
        if provenance:
            record = {"schema": 1, "linkCheck": "enforce", "source": {"commit": "x", "dirty": False},
                      "toolSources": {"MergedBaseBuilder.java": evidence.digest(tool)},
                      "inputs": {role: {"sha256": evidence.digest(artifacts[role])} for role in evidence.PROVENANCE_INPUTS},
                      "outputs": {role: {"sha256": evidence.digest(artifacts[role])} for role in ("merged", "forge-interop")}}
            for key, value in provenance_changes.items():
                record[key] = value
            Path(str(artifacts["merged"]) + ".provenance.json").write_text(json.dumps(record))
        return artifacts

    def test_release_requires_clean_source_including_new_files(self):
        artifacts = self.release_artifacts()
        evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        evidence.verify(self.report)
        (self.source / "New.java").write_text("class New {}")
        with self.assertRaisesRegex(ValueError, "committed source changes"):
            evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)

    def test_release_reads_the_pinned_versions_out_of_the_artifacts(self):
        recorded = evidence.capture(self.source, self.release_artifacts(), [self.mods], self.report, release=True)
        self.assertEqual({"vanilla": "26.2", "forge-patched": "26.2", "neo-patched": "26.2", "merged": "26.2"},
                         recorded["platform"]["readings"]["minecraft"])
        self.assertEqual("26.2-65.0.1", recorded["platform"]["readings"]["forge"]["forge-interop"])
        self.assertEqual("26.2.0.88", recorded["platform"]["readings"]["neoforge"]["neo-runtime"])
        wrong = self.release_artifacts(neo_version="26.2.0.38-beta")
        with self.assertRaisesRegex(ValueError, "not the pinned platform: neo-runtime=26.2.0.38-beta"):
            evidence.capture(self.source, wrong, [self.mods], self.report, release=True)
        # An ordinary capture records what it read and does not judge it.
        loose = evidence.capture(self.source, {"neo-runtime": wrong["neo-runtime"], "kernel": self.artifact},
                                 [self.mods], self.report)
        self.assertEqual("26.2.0.38-beta", loose["platform"]["readings"]["neoforge"]["neo-runtime"])

    def test_an_unreadable_platform_artifact_is_not_a_pinned_one(self):
        artifacts = self.release_artifacts()
        artifacts["vanilla"] = self.artifact
        with self.assertRaisesRegex(ValueError, "vanilla=unreadable"):
            evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)

    def test_file_jar_version_is_resolved_from_the_same_archive_and_the_raw_value_kept(self):
        with zipfile.ZipFile(self.mods / "toolbelt.jar", "w") as jar:
            jar.writestr("META-INF/mods.toml", '[[mods]]\nmodId="toolbelt"\nversion="${file.jarVersion}"\n')
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 2.10.0\n")
        declaration = self.capture()["mods"][0]["jars"][0]["inventory"][0]["declarations"][0]
        self.assertEqual({"ecosystem": "FORGE", "id": "toolbelt", "version": "${file.jarVersion}",
                          "resolvedVersion": "2.10.0"}, declaration)
        evidence.verify(self.report)

    def test_release_rejects_a_candidate_the_build_and_its_tests_do_not_read(self):
        artifacts = self.release_artifacts()
        staged = self.staged / "run" / evidence.STAGED_BUILD_INPUTS["merged"]
        staged.write_bytes(b"the reference base the unit tests actually read")
        with self.assertRaisesRegex(ValueError, "attests other jars than the build tests: merged"):
            evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        staged.unlink()
        with self.assertRaisesRegex(ValueError, "merged: .*missing"):
            evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        # Without --release the divergence is recorded, and verify notices a staged jar replaced mid-run.
        staged.write_bytes(artifacts["merged"].read_bytes())
        recorded = evidence.capture(self.source, {"kernel": self.artifact}, [self.mods], self.report)
        self.assertEqual(str(staged.resolve()), recorded["stagedBuildInputs"]["merged"]["path"])
        staged.write_bytes(b"swapped")
        with self.assertRaisesRegex(ValueError, "staged build input changed"):
            evidence.verify(self.report)

    def test_release_requires_the_merged_base_provenance_to_match(self):
        with self.assertRaisesRegex(ValueError, "requires the merged base's build provenance"):
            evidence.capture(self.source, self.release_artifacts(provenance=False), [self.mods], self.report, release=True)
        for change, message in (({"linkCheck": "warn"}, "link check was 'warn'"),
                                ({"source": {"commit": "x", "dirty": True}}, "uncommitted tree"),
                                ({"toolSources": {"MergedBaseBuilder.java": "0" * 64}}, "differs from the attested source"),
                                ({"outputs": {}}, "merged is not the jar this provenance's build wrote"),
                                ({"inputs": {}}, "vanilla is not the vanilla input")):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, message):
                evidence.capture(self.source, self.release_artifacts(**change), [self.mods], self.report, release=True)

    def test_the_merge_provenance_writer_is_what_a_release_accepts(self):
        artifacts = self.release_artifacts(provenance=False)
        tool = self.source / evidence.MERGE_TOOL_SOURCES / "MergedBaseBuilder.java"
        target = Path(str(artifacts["merged"]) + ".provenance.json")
        inputs = {role: artifacts[role] for role in evidence.PROVENANCE_INPUTS}
        produced = {role: artifacts[role] for role in ("merged", "forge-interop")}
        written = evidence.write_merge_provenance(self.source, target, inputs, produced, [tool], "enforce")
        self.assertFalse(written["source"]["dirty"])
        recorded = evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        self.assertEqual("enforce", recorded["mergedProvenance"]["summary"]["linkCheck"])
        evidence.verify(self.report)
        tool.write_text("class MergedBaseBuilder { /* uncommitted */ }")
        self.assertTrue(evidence.write_merge_provenance(self.source, target, inputs, produced, [tool], "enforce")
                        ["source"]["dirty"])

    def test_a_release_manifest_without_the_new_bindings_does_not_verify(self):
        evidence.capture(self.source, self.release_artifacts(), [self.mods], self.report, release=True)
        saved = json.loads(self.report.read_text())
        for key, message in (("stagedBuildInputs", "jars the build and its tests read"),
                             ("mergedProvenance", "no merged base provenance")):
            with self.subTest(key=key):
                self.report.write_text(json.dumps({k: v for k, v in saved.items() if k != key}))
                with self.assertRaisesRegex(ValueError, message):
                    evidence.verify(self.report)

    def test_a_release_acceptance_cannot_skip_or_carry_an_expected_red_gate(self):
        artifacts = self.release_artifacts()
        with self.assertRaisesRegex(ValueError, "--skip makes it a sweep"):
            evidence.verified_run(self.source, artifacts, [self.mods], self.report,
                                  ["bash", "gates-all.sh", "--release", "--skip", "gate-m34-soak.sh"], release=True)
        with self.assertRaisesRegex(ValueError, "runs gates-all.sh --release"):
            evidence.verified_run(self.source, artifacts, [self.mods], self.report,
                                  ["bash", "forbric-kernel/run/compat/gates-all.sh"], release=True)
        lying = [sys.executable, "-c", "print('RESULT gate-m1.sh GREEN (exit=0)');"
                 "print('RESULT gate-m34-soak.sh SKIP (explicit --skip)');"
                 "print('RESULT gate-m2.sh EXPECTED_RED (exit=2)')"]
        released = evidence.verified_run(self.source, artifacts, [self.mods], self.report, lying, release=True)
        self.assertEqual(0, released["exitCode"])
        self.assertEqual(["gate-m34-soak.sh SKIP", "gate-m2.sh EXPECTED_RED"], released["unpassedGates"])
        self.assertFalse(released["commandPassed"])
        sweep = evidence.verified_run(self.source, {"kernel": self.artifact}, [self.mods], self.report, lying)
        self.assertTrue(sweep["commandPassed"], "an ordinary sweep keeps its lenient verdict")

    def release_run(self, name, artifacts, command=None):
        output = self.root / "runs" / name
        return output, evidence.verified_run(self.source, artifacts, [self.mods], output,
                                             command or [sys.executable, "-c", "print('ok')"], release=True)

    def test_release_check_binds_one_passing_candidate_to_the_published_files(self):
        artifacts = self.release_artifacts()
        first, _ = self.release_run("a.json", artifacts)
        second, _ = self.release_run("b.json", artifacts)
        accepted = evidence.release_check([first, second], {"kernel": artifacts["kernel"]})
        self.assertEqual(evidence.digest(artifacts["kernel"]), accepted["kernel"])
        rebuilt = self.jar("rebuilt-kernel.jar", {"marker": "kernel", "timestamp": "later"})
        with self.assertRaisesRegex(ValueError, "is not the accepted kernel"):
            evidence.release_check([first, second], {"kernel": rebuilt})
        with self.assertRaisesRegex(ValueError, "no accepted artifact"):
            evidence.release_check([first], {"installer": rebuilt})
        failed, _ = self.release_run("failed.json", artifacts, [sys.executable, "-c", "raise SystemExit(1)"])
        with self.assertRaisesRegex(ValueError, "did not pass"):
            evidence.release_check([first, failed], {})
        captured = self.root / "runs" / "captured.json"
        evidence.capture(self.source, artifacts, [self.mods], captured, release=True)
        with self.assertRaisesRegex(ValueError, "no run result"):
            evidence.release_check([captured], {})
        sweep = self.root / "runs" / "sweep.json"
        evidence.verified_run(self.source, {"kernel": self.artifact}, [self.mods], sweep, [sys.executable, "-c", ""])
        with self.assertRaisesRegex(ValueError, "not captured with --release"):
            evidence.release_check([first, sweep], {})

    def test_release_check_rejects_manifests_of_different_candidates(self):
        artifacts = self.release_artifacts()
        first, _ = self.release_run("a.json", artifacts)
        other = self.jar("other-kernel.jar", {"marker": "another kernel"})
        second, _ = self.release_run("b.json", dict(artifacts, kernel=other))
        with self.assertRaisesRegex(ValueError, "role kernel has 2 different hashes"):
            evidence.release_check([first, second], {})

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
