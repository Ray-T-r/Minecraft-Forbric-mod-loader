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

    STAMP = "mc=26.2 neoforge=26.2.0.93 nfrt=2.0.18 result=gameJarNoRecomp tools=0000"

    def release_artifacts(self, neo_version="26.2.0.93", sidecar_base=None, sidecar_vanilla=None, staged=True):
        """A complete, consistent release set: pinned readings, staged build inputs with DevPrepare's build stamps beside
        the base and the carrier, a sidecar measured against the attested base and vanilla jar (or against the SHA-1s
        given)."""
        game = {"version.json": json.dumps({"id": "26.2"})}
        artifacts = {name: self.jar(name + ".jar", dict(game, **{"marker": name})) for name in ("vanilla", "neo-patched")}
        artifacts["neo-runtime"] = self.jar("neo-runtime.jar", self.carrier("NeoForge", neo_version))
        for name in ("kernel", "kernel-runtime"):
            artifacts[name] = self.jar(name + ".jar", {"marker": name})
        properties = (f"format=1\nvanilla-sha1={sidecar_vanilla or evidence.digest(artifacts['vanilla'], 'sha1')}\n"
                      f"base-sha1={sidecar_base or evidence.digest(artifacts['neo-patched'], 'sha1')}\n")
        artifacts["sidecar"] = self.jar("sidecar.jar", {evidence.SIDECAR_PROPERTIES: properties,
                                                        "META-INF/forbric/native-reference/FABRIC/index.tsv": "x"})
        if staged:
            for role, relative in evidence.STAGED_BUILD_INPUTS.items():
                target = self.staged / "run" / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(artifacts[role].read_bytes())
                if role in evidence.STAMPED_ROLES:
                    target.with_name(target.name + ".pins").write_text(self.STAMP + "\n")
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
        self.assertEqual({"vanilla": "26.2", "neo-patched": "26.2"}, recorded["platform"]["readings"]["minecraft"])
        self.assertEqual("26.2.0.93", recorded["platform"]["readings"]["neoforge"]["neo-runtime"])
        self.assertEqual({"minecraft", "neoforge"}, recorded["platform"]["readings"].keys())
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

    def test_the_release_roles_are_the_genuine_pair_and_the_sidecar(self):
        self.assertEqual({"vanilla", "neo-patched", "neo-runtime", "kernel", "kernel-runtime", "sidecar"},
                         set(evidence.RELEASE_ROLES))
        self.assertEqual({"minecraft": "26.2", "neoforge": "26.2.0.93"}, evidence.PINS)
        # Each release role missing on its own is named, the sidecar above all (it is the new one).
        for role in sorted(evidence.RELEASE_ROLES):
            with self.subTest(role=role):
                artifacts = self.release_artifacts()
                del artifacts[role]
                with self.assertRaisesRegex(ValueError, "release evidence missing artifacts: " + role + "$"):
                    evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)

    def test_release_rejects_a_candidate_the_build_and_its_tests_do_not_read(self):
        # Every staged input on its own: the base above all (the build compiles against it and its tests read it),
        # the carrier, and the sidecar the gates' launchers hand the kernel.
        for role in ("neo-patched", "neo-runtime", "sidecar"):
            with self.subTest(role=role):
                artifacts = self.release_artifacts()
                staged = self.staged / "run" / evidence.STAGED_BUILD_INPUTS[role]
                staged.write_bytes(b"the jar the unit tests actually read")
                with self.assertRaisesRegex(ValueError, "attests other jars than the build tests: " + role + ": "):
                    evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
                staged.unlink()
                with self.assertRaisesRegex(ValueError, role + ": .*missing"):
                    evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        # Without --release the divergence is recorded, and verify notices a staged jar replaced mid-run.
        artifacts = self.release_artifacts()
        staged = self.staged / "run" / evidence.STAGED_BUILD_INPUTS["neo-patched"]
        recorded = evidence.capture(self.source, {"kernel": self.artifact}, [self.mods], self.report)
        self.assertEqual(str(staged.resolve()), recorded["stagedBuildInputs"]["neo-patched"]["path"])
        self.assertEqual(set(evidence.STAGED_BUILD_INPUTS), recorded["stagedBuildInputs"].keys())
        staged.write_bytes(b"swapped")
        with self.assertRaisesRegex(ValueError, "staged build input changed"):
            evidence.verify(self.report)

    def test_the_staged_root_resolves_as_the_build_resolves_it(self):
        root = self.root / "checkout"
        dev = root / "forbric-kernel/.dev/staged/run"
        self.assertEqual(Path("/given/run"), evidence.staged_root(root, {"FORBRIC_OLD": "/given"}))
        # No FORBRIC_OLD and no prepared .dev stage: the build's last fallback.
        self.assertEqual(root / "forbric-loader/run", evidence.staged_root(root, {}))
        base = dev / evidence.STAGED_BUILD_INPUTS["neo-patched"]
        base.parent.mkdir(parents=True)
        base.write_bytes(b"prepared base")
        self.assertEqual(dev, evidence.staged_root(root, {}))
        self.assertEqual(Path("/given/run"), evidence.staged_root(root, {"FORBRIC_OLD": "/given"}),
                         "FORBRIC_OLD wins over a prepared .dev stage, as in build.gradle")

    def test_release_requires_the_sidecar_measured_against_the_attested_base_and_vanilla(self):
        recorded = evidence.capture(self.source, self.release_artifacts(), [self.mods], self.report, release=True)
        self.assertEqual({"baseSha1", "vanillaSha1"}, recorded["sidecarProvenance"].keys())
        evidence.verify(self.report)
        for change, message in (({"sidecar_base": "0" * 40}, "records neo-patched 0{40}, the attested neo-patched is"),
                                ({"sidecar_vanilla": "1" * 40}, "records vanilla 1{40}, the attested vanilla is")):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "not measured against the attested jars: it "
                                                                     + message):
                evidence.capture(self.source, self.release_artifacts(**change), [self.mods], self.report, release=True)
        # A sidecar with no provenance at all is not one a release can pair with anything.
        artifacts = self.release_artifacts()
        artifacts["sidecar"].write_bytes(self.jar("bare.jar", {"marker": "no properties"}).read_bytes())
        (self.staged / "run" / evidence.STAGED_BUILD_INPUTS["sidecar"]).write_bytes(artifacts["sidecar"].read_bytes())
        with self.assertRaisesRegex(ValueError, "records neo-patched nothing"):
            evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        # An ordinary capture records what the sidecar says and does not judge it.
        loose = evidence.capture(self.source, dict(self.release_artifacts(sidecar_base="2" * 40)), [self.mods], self.report)
        self.assertEqual("2" * 40, loose["sidecarProvenance"]["baseSha1"])

    def test_release_requires_the_staged_base_and_carrier_built_from_the_pinned_platform(self):
        """C6 REL review A F5: the base declares no NeoForge version (its version.json says only 26.2, and another
        NeoForge's patched game can be byte-identical), so nothing tied a release's base to NeoForge 26.2.0.93. DevPrepare's
        build stamps beside the staged base and carrier must name the pins."""
        recorded = evidence.capture(self.source, self.release_artifacts(), [self.mods], self.report, release=True)
        self.assertEqual({"neo-patched", "neo-runtime"}, recorded["stagedBuildStamps"].keys())
        self.assertEqual(self.STAMP, recorded["stagedBuildStamps"]["neo-patched"]["stamp"])
        evidence.verify(self.report)
        for role in evidence.STAMPED_ROLES:
            pins = self.staged / "run" / (evidence.STAGED_BUILD_INPUTS[role] + ".pins")
            with self.subTest(role=role, stamp="missing"):
                artifacts = self.release_artifacts()
                pins.unlink()
                with self.assertRaisesRegex(ValueError, "not built from the pinned platform: " + role + ": .*\\.pins is missing"):
                    evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
            with self.subTest(role=role, stamp="another NeoForge"):
                artifacts = self.release_artifacts()
                pins.write_text(self.STAMP.replace("26.2.0.93", "26.2.0.88"))
                with self.assertRaisesRegex(ValueError, role + ": .* says neoforge=26.2.0.88 \\(pinned 26.2.0.93\\)"):
                    evidence.capture(self.source, artifacts, [self.mods], self.report, release=True)
        # An ordinary capture records what the stamps say and does not judge them; verify notices one rewritten mid-run.
        artifacts = self.release_artifacts()
        pins = self.staged / "run" / (evidence.STAGED_BUILD_INPUTS["neo-runtime"] + ".pins")
        pins.unlink()
        loose = evidence.capture(self.source, {"kernel": self.artifact}, [self.mods], self.report)
        self.assertIsNone(loose["stagedBuildStamps"]["neo-runtime"]["stamp"])
        pins.write_text(self.STAMP)
        with self.assertRaisesRegex(ValueError, "staged build stamp changed since capture: neo-runtime"):
            evidence.verify(self.report)

    def test_verify_judges_a_release_manifests_sidecar_and_stamps_again(self):
        """C6 REL review A F6 / B-4: verify re-checks a release manifest's sidecar and stamps itself, not only capture.
        A manifest captured without --release over a sidecar measured against another base (or an unstamped carrier)
        and then marked as a release must not verify."""
        for change, message in (("sidecar", "the sidecar was not measured against the attested jars: it records neo-patched 0{40}"),
                                ("stamp", "not built from the pinned platform: neo-runtime: .*is missing")):
            with self.subTest(change=change):
                artifacts = self.release_artifacts(sidecar_base="0" * 40 if change == "sidecar" else None)
                if change == "stamp":
                    (self.staged / "run" / (evidence.STAGED_BUILD_INPUTS["neo-runtime"] + ".pins")).unlink()
                evidence.capture(self.source, artifacts, [self.mods], self.report)
                evidence.verify(self.report)
                saved = json.loads(self.report.read_text())
                saved["release"] = True
                self.report.write_text(json.dumps(saved))
                with self.assertRaisesRegex(ValueError, message):
                    evidence.verify(self.report)

    def test_a_release_manifest_without_the_new_bindings_does_not_verify(self):
        evidence.capture(self.source, self.release_artifacts(), [self.mods], self.report, release=True)
        saved = json.loads(self.report.read_text())
        for key, message in (("stagedBuildInputs", "jars the build and its tests read"),
                             ("stagedBuildStamps", "the build stamps of the staged base and carrier are not recorded"),
                             ("sidecarProvenance", "the sidecar's base and vanilla jar are not recorded")):
            with self.subTest(key=key):
                self.report.write_text(json.dumps({k: v for k, v in saved.items() if k != key}))
                with self.assertRaisesRegex(ValueError, message):
                    evidence.verify(self.report)

    def test_the_merged_era_entry_points_are_gone(self):
        for name in ("write_merge_provenance", "merged_provenance", "MERGE_TOOL_SOURCES", "PROVENANCE_INPUTS", "FORGE_ROLES"):
            self.assertFalse(hasattr(evidence, name), name)
        cli = subprocess.run([sys.executable, str(Path(evidence.__file__)), "merge-provenance", "--help"],
                             capture_output=True, text=True)
        self.assertNotEqual(0, cli.returncode)
        self.assertIn("invalid choice: 'merge-provenance'", cli.stderr)

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
        # A role nobody accepts, or one that is no release role at all (the merge tools, a jar Forbric stopped
        # shipping), is refused by name rather than compared with nothing.
        for role in ("installer", "merge-tools", "merged", "forge-runtime"):
            with self.subTest(role=role), self.assertRaisesRegex(ValueError, role + ": not a release role"):
                evidence.release_check([first], {role: rebuilt})
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
