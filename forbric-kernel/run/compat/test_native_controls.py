"""native-controls.py: how one server console is classified, what identifies a mod set, which stage the Forbric arm
boots, and the swap subcommand's server session."""
import importlib.util
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import zipfile

_spec = importlib.util.spec_from_file_location("native_controls", Path(__file__).with_name("native-controls.py"))
nc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nc)

BOOT = "[12:00:00] [main/INFO]: Loading Minecraft 26.2 with Fabric Loader 0.19.5\n[12:00:09] [Server thread/INFO]: Starting minecraft server version 26.2\n"
DONE = '[12:00:20] [Server thread/INFO]: Done (11.204s)! For help, type "help"\n'
TICKED = "[12:00:21] [Server thread/INFO]: The game time is 31 tick(s)\n[12:00:33] [Server thread/INFO]: The game time is 262 tick(s)\n"
STOPPED = "[12:00:33] [Server thread/INFO]: Stopping the server\n[12:00:33] [Server thread/INFO]: Stopping server\n"


class ServerOutcome(unittest.TestCase):
    def test_a_server_that_ticked_and_stopped_is_done(self):
        self.assertEqual(nc.DONE, nc.server_outcome(BOOT + DONE + TICKED + STOPPED, 200, 0))

    def test_done_followed_by_an_exception_is_a_crash(self):
        log = BOOT + DONE + "[12:00:22] [Server thread/INFO]: The game time is 40 tick(s)\n" \
            'Exception in thread "Server thread" java.lang.IllegalStateException: Not building!\n' \
            "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:1)\n"
        self.assertEqual(nc.CRASH, nc.server_outcome(log, 200, 1))
        self.assertEqual(nc.CRASH, nc.server_outcome(log, 200, None))

    def test_a_crash_report_after_done_is_a_crash_even_when_the_server_then_stops(self):
        log = BOOT + DONE + TICKED + "---- Minecraft Crash Report ----\n// Oops.\n\nDescription: Exception ticking world\n" + STOPPED
        self.assertEqual(nc.CRASH, nc.server_outcome(log, 200, 0))

    def test_an_exception_a_mod_logs_and_survives_is_not_a_crash(self):
        caught = ("[12:00:21] [Server thread/WARN]: Failed to load something optional\n"
                  "java.lang.NullPointerException: Cannot invoke \"Object.toString()\" because \"x\" is null\n"
                  "\tat example.Mod.onStart(Mod.java:3)\n")
        self.assertEqual(nc.DONE, nc.server_outcome(BOOT + DONE + caught + TICKED + STOPPED, 200, 0))

    def test_a_worker_thread_dying_is_evidence_not_an_outcome(self):
        worker = 'Exception in thread "Update Checker" java.net.UnknownHostException: example.invalid\n'
        log = BOOT + DONE + worker + TICKED + STOPPED
        self.assertEqual(nc.DONE, nc.server_outcome(log, 200, 0))
        self.assertEqual(['Exception in thread "Update Checker"'], nc.other_thread_failures(log))

    def test_a_server_that_never_reached_its_ticks_stalled(self):
        log = BOOT + DONE + "[12:00:21] [Server thread/INFO]: The game time is 31 tick(s)\n"
        self.assertEqual(nc.STALL, nc.server_outcome(log, 200, None))

    def test_a_jvm_kept_alive_after_a_clean_stop_is_still_done(self):
        # The runner kills it (exit_code None); lingeredAfterStop records that separately.
        self.assertEqual(nc.DONE, nc.server_outcome(BOOT + DONE + TICKED + STOPPED, 200, None))

    def test_a_jvm_that_vanished_after_done_crashed(self):
        self.assertEqual(nc.CRASH, nc.server_outcome(BOOT + DONE + "[12:00:21] [Server thread/INFO]: The game time is 31 tick(s)\n", 200, 134))

    def test_no_done_and_a_start_failure_failed_to_start(self):
        log = BOOT + "[12:00:10] [Server thread/ERROR]: Failed to start the minecraft server\njava.lang.RuntimeException: boom\n"
        self.assertEqual(nc.FAILED_TO_START, nc.server_outcome(log, 200, 1))
        self.assertEqual(nc.FAILED_TO_START, nc.server_outcome(log, 200, None))

    def test_an_uncaught_main_thread_exception_before_done_failed_to_start(self):
        log = BOOT + 'Exception in thread "main" net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible\n'
        self.assertEqual(nc.FAILED_TO_START, nc.server_outcome(log, 200, 1))

    def test_a_forbric_policy_stop_failed_to_start(self):
        log = BOOT + "[Forbric/Compatibility] launch stopped: required mod initialization or features are unavailable\n"
        self.assertEqual(nc.FAILED_TO_START, nc.server_outcome(log, 200, 78))

    def test_an_exit_without_done_or_marker_failed_to_start(self):
        self.assertEqual(nc.FAILED_TO_START, nc.server_outcome(BOOT, 200, 1))

    def test_a_killed_server_that_never_said_anything_stalled(self):
        self.assertEqual(nc.STALL, nc.server_outcome(BOOT, 200, None))

    def test_zero_ticks_needs_only_done_and_stop(self):
        self.assertEqual(nc.DONE, nc.server_outcome(BOOT + DONE + STOPPED, 0, 0))

    def test_every_answer_is_a_declared_outcome(self):
        logs = [BOOT, BOOT + DONE, BOOT + DONE + TICKED + STOPPED]
        for log in logs:
            for code in (None, 0, 1, 78):
                self.assertIn(nc.server_outcome(log, 200, code), nc.SERVER_OUTCOMES)


class RunSetCommandLine(unittest.TestCase):
    def test_the_policy_reaches_the_forbric_arm(self):
        seen = {}

        def fake(engine, family, mods, ticks, timeout, xmx, level_type, policy, keep):
            seen.update(engine=engine, mods=mods, ticks=ticks, policy=policy)
            return {"engine": engine, "outcome": nc.DONE, "signature": None, "modSetSha256": "x", "jars": len(mods), "seconds": 1.0, "result": "r"}
        with mock.patch.object(nc, "run_set", fake), mock.patch.object(sys, "argv", ["native-controls.py", "run-set", "--engine", "forbric",
                                                                                     "--policy", "continue", "--ticks", "40", "--mods", "a.jar", "b.jar"]), \
                mock.patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(0, nc.main())
        self.assertEqual(dict(engine="forbric", mods=["a.jar", "b.jar"], ticks=40, policy="continue"), seen)


class ModSet(unittest.TestCase):
    def test_the_set_digest_is_the_bytes_not_the_names_or_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "a.jar").write_bytes(b"one"); (tmp / "b.jar").write_bytes(b"two")
            (tmp / "renamed.jar").write_bytes(b"one"); (tmp / "c.jar").write_bytes(b"three")
            rows, first = nc.mod_set([tmp / "b.jar", tmp / "a.jar"])
            self.assertEqual(["a.jar", "b.jar"], [row["name"] for row in rows])
            self.assertEqual(first, nc.mod_set([tmp / "renamed.jar", tmp / "b.jar"])[1])
            self.assertNotEqual(first, nc.mod_set([tmp / "a.jar", tmp / "c.jar"])[1])
            self.assertTrue(all("path" not in row for row in rows))


FAKE_SERVER = r"""
import sys, time
mode = sys.argv[1]
print("Starting minecraft server version 26.2", flush=True)
if mode == "fail":
    print("Failed to start the minecraft server", flush=True); sys.exit(1)
print('Done (1.000s)! For help, type "help"', flush=True)
for line in sys.stdin:
    line = line.strip()
    print("> " + line, flush=True)
    if line == "save-all flush":
        print("Saving the game (this may take a moment!)", flush=True)
        if mode != "nosave":
            print("Saved the game", flush=True)
    elif line == "stop":
        # a real server's shutdown save says this too (forbric2/neo-reopen.log); it is not an answer to save-all
        print("Stopping the server", flush=True)
        print("ThreadedAnvilChunkStorage: All dimensions are saved", flush=True); sys.exit(0)
"""


class ForbricStage(unittest.TestCase):
    """The Forbric arm boots FORBRIC_OLD as the caller set it (map-misc 4.1: it used to replace it with
    ORIGINAL/forbric-loader, which in a Stage 4 worktree links into the main checkout's stage)."""

    def test_the_callers_forbric_old_is_the_stage(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"FORBRIC_OLD": tmp}):
            self._stage(Path(tmp))
            self.assertEqual(Path(tmp).resolve(), nc.forbric_stage())

    def test_without_forbric_old_the_prepared_dev_stage_is_the_stage_and_never_forbric_loader(self):
        """Without FORBRIC_OLD the arm boots the stage tools/dev.py prepare writes (as build.gradle reads it), and with
        none it refuses by name. It used to boot ORIGINAL/forbric-loader, whose carrier is NeoForge 26.2.0.88 and
        unstamped (C6, critic 7): a complete decoy stage there must never be the answer."""
        env = {k: v for k, v in os.environ.items() if k != "FORBRIC_OLD"}
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, env, clear=True):
            tmp = Path(tmp)
            kernel = tmp / "checkout/forbric-kernel"
            reference = self._reference(tmp)
            with mock.patch.object(nc, "KERNEL", kernel), mock.patch.object(nc, "ORIGINAL", reference):
                with self.assertRaisesRegex(RuntimeError, r"no stage for the Forbric arm: set FORBRIC_OLD .*tools/dev.py prepare"):
                    nc.forbric_stage()
                prepared = self._stage(kernel / ".dev/staged")
                self.assertEqual(prepared.resolve(), nc.forbric_stage())

    def test_a_stage_this_trees_pins_did_not_build_is_refused_wherever_it_comes_from(self):
        """C6 REL review A F1: in a worktree .dev links to the main checkout's, whose stage holds an unstamped NeoForge
        26.2.0.88 carrier (and a base byte-identical to a .93 one), so a stage taken by its presence booted another
        NeoForge than the arm reports. Base and carrier must carry this tree's build stamp (dev.py stale_stamps), for the
        prepared stage and for FORBRIC_OLD alike, each stale stamp named."""
        stamp = nc.dev_tools().pins_stamp()
        env = {k: v for k, v in os.environ.items() if k != "FORBRIC_OLD"}
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, env, clear=True):
            tmp = Path(tmp)
            kernel = tmp / "checkout/forbric-kernel"
            with mock.patch.object(nc, "KERNEL", kernel), mock.patch.object(nc, "ORIGINAL", self._reference(tmp)):
                prepared = self._stage(kernel / ".dev/staged", stamp=None)
                with self.assertRaisesRegex(RuntimeError, r"the prepared stage .* is not a stage this tree's pins built: "
                                            r"run/neoforge-patched/patched-mc-neoforge-26.2.jar.pins, "
                                            r"run/neoforge-runtime/neoforge-runtime.jar.pins missing or not beginning"):
                    nc.forbric_stage()
                # Stamped by other pins (the carrier of another NeoForge): refused, only that stamp named.
                self._stage(prepared, stamp=stamp)
                (prepared / ("run/" + nc.STAGED_CARRIER + ".pins")).write_text(
                    stamp.replace("neoforge=", "neoforge=0.") + " tools=x\n")
                with self.assertRaises(RuntimeError) as refused:
                    nc.forbric_stage()
                self.assertIn("neoforge-runtime.jar.pins missing or not beginning with \"" + stamp + "\"", str(refused.exception))
                self.assertNotIn("patched-mc-neoforge-26.2.jar.pins", str(refused.exception))
                self._stage(prepared, stamp=stamp)
                self.assertEqual(prepared.resolve(), nc.forbric_stage())
                with mock.patch.dict(os.environ, {"FORBRIC_OLD": str(self._stage(tmp / "given", stamp=None))}):
                    with self.assertRaisesRegex(RuntimeError, "FORBRIC_OLD .*given is not a stage this tree's pins built"):
                        nc.forbric_stage()

    def _stage(self, root, stamp=True):
        """A stage with a base and a carrier; stamp True writes this tree's build stamp beside each (DevPrepare's
        Pins.stamp() followed by its tools digest), a string writes that, None writes none."""
        stamp = nc.dev_tools().pins_stamp() if stamp is True else stamp
        for rel in ("run/neoforge-patched/patched-mc-neoforge-26.2.jar", "run/neoforge-runtime/neoforge-runtime.jar"):
            (root / rel).parent.mkdir(parents=True, exist_ok=True); (root / rel).write_bytes(rel.encode())
            pins = root / (rel + ".pins")
            if stamp is None:
                pins.unlink(missing_ok=True)
            else:
                pins.write_text(stamp + " tools=0000\n")
        return root

    def _reference(self, tmp):
        """A reference checkout whose forbric-loader/ is a complete decoy stage: an arm that boots it instead of the
        caller's stage then fails on the FORBRIC_OLD assertion, not on a missing file (review A-5)."""
        self._stage(tmp / "reference/forbric-loader")
        return tmp / "reference"

    def test_run_set_hands_the_forbric_arm_the_callers_stage(self):
        seen = {}

        class Exited:
            def __init__(self, command, cwd=None, env=None, **kw):
                seen["env"] = env; self.pid = os.getpid(); self.returncode = 1; self.stdin = io.StringIO()

            def poll(self):
                return 1

            def wait(self, *a):
                return 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            stage = self._stage(tmp / "stage4")
            jar = tmp / "forbric-kernel-0.1.0-SNAPSHOT.jar"; jar.write_bytes(b"kernel")
            mod = tmp / "mod.jar"; mod.write_bytes(b"mod")
            with mock.patch.dict(os.environ, {"FORBRIC_OLD": str(stage)}), mock.patch.object(nc, "BASE", tmp / "nc"), \
                    mock.patch.object(nc, "ORIGINAL", self._reference(tmp)), \
                    mock.patch.object(nc, "kernel_jar", lambda: jar), mock.patch.object(nc.subprocess, "Popen", Exited), \
                    mock.patch.object(nc.subprocess, "check_output", return_value="java 25"):
                result = nc.run_set("forbric", "fabric", [mod], ticks=0, timeout=5)
        self.assertEqual(str(stage.resolve()), seen["env"]["FORBRIC_OLD"])
        self.assertEqual(nc.FAILED_TO_START, result["outcome"])

    def test_the_swap_forbric_arm_boots_the_callers_stage(self):
        seen = []

        class FakeSnapshots:
            def export(self, world, box=None, label=""):
                return {"schema": "s", "label": label}

            def diff(self, a, b, mode, ignore=()):
                return [], mock.Mock(used={}, violations=[], mode=mode, ignore=())

            def diff_report(self, found, rules):
                return {"count": 0, "by_facet": {}, "rules_used": {}}

        def session(command, cwd, env, log, lines, timeout=900, settle=3.0):
            seen.append((env["FORBRIC_OLD"], env["RUNDIR"], list(lines)))
            return {"outcome": nc.DONE, "saved": True, "exitCode": 0, "timedOut": False, "seconds": 1.0}
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp).resolve()
            stage = self._stage(tmp / "stage4")
            world = tmp / "old-world"; world.mkdir(); (world / "level.dat").write_bytes(b"x")
            neo = tmp / "neo.jar"; neo.write_bytes(b"neo")
            checks = tmp / "checks.txt"; checks.write_text("# a comment\nsetblock 0 80 0 example:block\n\nwait 2\n")
            config = tmp / "old-config"; config.mkdir(); (config / "mod-common.toml").write_text("mark = 3\n")
            with mock.patch.dict(os.environ, {"FORBRIC_OLD": str(stage)}), mock.patch.object(nc, "swap_session", session), \
                    mock.patch.object(nc, "ORIGINAL", self._reference(tmp)), \
                    mock.patch.object(nc, "_snapshot_module", FakeSnapshots), mock.patch("sys.stdout", new_callable=io.StringIO):
                result = nc.swap("forbric", [], [neo], tmp / "out", checks=checks, old_world=world, old_config=config)
            self.assertNotIn("error", result)
            self.assertEqual([(str(stage.resolve()), str(tmp / "out/neo-instance"), ["setblock 0 80 0 example:block", "wait 2"]),
                              (str(stage.resolve()), str(tmp / "out/neo-instance"), [])], seen)
            self.assertTrue((tmp / "out/neo-instance/world/level.dat").is_file())
            self.assertTrue((world / "level.dat").is_file())
            # The player swaps jars, not the instance: the NeoForge instance starts from the old config/.
            self.assertEqual("mark = 3\n", (tmp / "out/neo-instance/config/mod-common.toml").read_text())
            self.assertEqual(["swap", "reopen"], list(result["diffs"]))

    def test_the_swap_binds_the_port_it_is_given(self):
        # gate-m59 passes its GATE_PORT (review B-2): the port it checks is free at the end must be the one the run used,
        # not an OS ephemeral one outside the lane's block.
        class FakeSnapshots:
            def export(self, world, box=None, label=""):
                return {"schema": "s", "label": label}

            def diff(self, a, b, mode, ignore=()):
                return [], mock.Mock(used={}, violations=[], mode=mode, ignore=())

            def diff_report(self, found, rules):
                return {"count": 0, "by_facet": {}, "rules_used": {}}

        seen = []

        def session(command, cwd, env, log, lines, timeout=900, settle=3.0):
            seen.append((Path(cwd) / "server.properties").read_text())
            return {"outcome": nc.DONE, "saved": True, "exitCode": 0, "timedOut": False, "seconds": 1.0}
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp).resolve()
            stage = self._stage(tmp / "stage4")
            world = tmp / "old-world"; world.mkdir(); (world / "level.dat").write_bytes(b"x")
            neo = tmp / "neo.jar"; neo.write_bytes(b"neo")
            with mock.patch.dict(os.environ, {"FORBRIC_OLD": str(stage)}), mock.patch.object(nc, "swap_session", session), \
                    mock.patch.object(nc, "ORIGINAL", self._reference(tmp)), mock.patch.object(nc, "free_port", lambda: 1), \
                    mock.patch.object(nc, "_snapshot_module", FakeSnapshots), mock.patch("sys.stdout", new_callable=io.StringIO):
                result = nc.swap("forbric", [], [neo], tmp / "out", old_world=world, port=46799)
            self.assertNotIn("error", result)
            self.assertEqual(2, len(seen))
            for props in seen:
                self.assertIn("server-port=46799\n", props)
            self.assertEqual(46799, json.loads((tmp / "out/inputs.json").read_text())["port"])

    def test_the_native_create_alone_gets_the_extra_jvm_arguments(self):
        # forge-swap's bag agent fills a packedup backpack on the MinecraftForge create (--create-jvm); the A/A reopen and
        # the NeoForge phases must run without it, or they would measure the agent instead of the swap.
        seen = []

        class FakeSnapshots:
            def export(self, world, box=None, label=""):
                return {"schema": "s", "label": label}

            def diff(self, a, b, mode, ignore=()):
                return [], mock.Mock(used={}, violations=[], mode=mode, ignore=())

            def diff_report(self, found, rules):
                return {"count": 0, "by_facet": {}, "rules_used": {}}

        def session(command, cwd, env, log, lines, timeout=900, settle=3.0):
            seen.append((Path(log).stem, list(command)))
            return {"outcome": nc.DONE, "saved": True, "exitCode": 0, "timedOut": False, "seconds": 1.0}

        def instance(family, run_dir, mods, port):
            d = run_dir / ("%s-instance" % family); (d / "world").mkdir(parents=True, exist_ok=True)
            return d
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp).resolve()
            for family in ("forge", "neo"):
                (tmp / "base/native" / family).mkdir(parents=True)
                (tmp / "base/native" / family / "installation.json").write_text("{}")
            jar = tmp / "m.jar"; jar.write_bytes(b"m")
            agent_jar = tmp / "forge-swap-bagfill.jar"; agent_jar.write_bytes(b"agent")
            agent = "-javaagent:%s=bag=2,-60,8;source=4,-60,8" % agent_jar
            with mock.patch.object(nc, "swap_session", session), mock.patch.object(nc, "BASE", tmp / "base"), \
                    mock.patch.object(nc, "_native_instance", instance), \
                    mock.patch.object(nc, "_snapshot_module", FakeSnapshots), mock.patch("sys.stdout", new_callable=io.StringIO):
                result = nc.swap("native", [jar], [jar], tmp / "out", create_jvm=[agent])
            self.assertNotIn("error", result)
            self.assertEqual(["forge-create", "forge-reopen", "neo-open", "neo-reopen"], [phase for phase, _ in seen])
            self.assertEqual([True, False, False, False], [agent in command for _, command in seen])
            self.assertEqual([agent], json.loads((tmp / "out/inputs.json").read_text())["createJvm"])
            agents = json.loads((tmp / "out/inputs.json").read_text())["createJvmAgents"]
            self.assertEqual([hashlib.sha256(b"agent").hexdigest()], [a["sha256"] for a in agents])


class SwapSession(unittest.TestCase):
    def _run(self, mode, lines):
        with tempfile.TemporaryDirectory() as tmp:
            server = Path(tmp) / "server.py"; server.write_text(FAKE_SERVER)
            log = Path(tmp) / "console.log"
            result = nc.swap_session([sys.executable, str(server), mode], tmp, os.environ.copy(), log, lines, timeout=60, settle=0,
                                     save_wait=2)
            return result, log.read_text()

    def test_lines_then_save_then_stop_in_that_order(self):
        result, log = self._run("ok", ["say one", "wait 0.5", "say two"])
        self.assertEqual((nc.DONE, True), (result["outcome"], result["saved"]))
        sent = [l[2:] for l in log.splitlines() if l.startswith("> ")]
        self.assertEqual(["say one", "say two", "save-all flush", "stop"], sent)

    def test_a_save_the_server_never_answers_is_not_saved(self):
        result, log = self._run("nosave", [])
        self.assertIn("All dimensions are saved", log)  # the stop's own save, which is not the answer
        self.assertFalse(result["saved"])

    def test_a_server_that_never_reached_done_failed_to_start(self):
        result, _ = self._run("fail", ["say never"])
        self.assertEqual((nc.FAILED_TO_START, False), (result["outcome"], result["saved"]))

    def test_console_lines_skip_comments_and_blanks(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "c.txt"; script.write_text("# head\n\n  forceload add 0 0  \nwait 1\n")
            self.assertEqual(["forceload add 0 0", "wait 1"], nc.console_lines(script))
        self.assertEqual([], nc.console_lines(None))

    def test_clone_tree_is_an_independent_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "image"; (src / "libraries").mkdir(parents=True); (src / "libraries/a.jar").write_bytes(b"a")
            dst = nc.clone_tree(src, Path(tmp) / "out/instance")
            (dst / "libraries/a.jar").write_bytes(b"changed")
            self.assertEqual(b"a", (src / "libraries/a.jar").read_bytes())
            self.assertTrue(dst.is_dir() and not dst.is_symlink())

class ControlFamilies(unittest.TestCase):
    """build and run cover the two behaviour controls; a family's build needs only that family's prepared image, and
    MinecraftForge is an install (the swap oracle, D32), never a control."""

    def test_the_controls_are_fabric_and_neoforge_and_forge_stays_an_install(self):
        self.assertEqual(("fabric", "neo"), nc.CONTROL_FAMILIES)
        self.assertIn("forge", nc.VERSIONS)
        self.assertFalse((nc.SOURCE / "forge").exists(), "the MinecraftForge canary has no control to run in")
        for action, family in (("build", "forge"), ("run", "forge")):
            with self.subTest(action=action), mock.patch.object(sys, "argv", ["native-controls.py", action, "--family", family]), \
                    mock.patch("sys.stderr", new_callable=io.StringIO) as err, self.assertRaises(SystemExit):
                nc.main()
            self.assertIn("invalid choice: 'forge'", err.getvalue())

    def _image(self, base, family):
        image = base / "native" / family
        (image / "libraries").mkdir(parents=True, exist_ok=True)
        (image / "installation.json").write_text(json.dumps({"family": family}))
        if family == "neo":
            game = image / "libraries/net/neoforged/minecraft-server-patched/26.2.0.93/minecraft-server-patched-26.2.0.93.jar"
            game.parent.mkdir(parents=True, exist_ok=True)
            game.write_bytes(b"stub game")
        return image

    def _fake_javac(self, seen):
        def run_logged(command, cwd, log, timeout=900):
            seen.append(command)
            destination = Path(command[command.index("-d") + 1])
            (destination / "nativecontrol").mkdir(parents=True, exist_ok=True)
            (destination / "nativecontrol/Compiled.class").write_bytes(b"\xca\xfe\xba\xbe")
        return run_logged

    def test_build_of_the_neoforge_control_needs_no_other_image(self):
        seen = []
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            base = tmp / "nc"
            self._image(base, "neo")
            fapi = tmp / "fabric-api-0.155.2+26.2.jar"
            fapi.write_bytes(b"not read for a NeoForge build")
            with mock.patch.object(nc, "BASE", base), mock.patch.object(nc, "fapi_source", lambda: fapi), \
                    mock.patch.object(nc, "run_logged", self._fake_javac(seen)), \
                    mock.patch.object(nc.subprocess, "check_output", return_value="javac 25"), \
                    mock.patch("sys.stdout", new_callable=io.StringIO), \
                    mock.patch.object(sys, "argv", ["native-controls.py", "build", "--family", "neo"]):
                self.assertEqual(0, nc.main())
            self.assertEqual(1, len(seen))
            self.assertIn("minecraft-server-patched-26.2.0.93.jar", seen[0][seen[0].index("-cp") + 1])
            inputs = json.loads((base / "artifacts/build-inputs.json").read_text())
            self.assertEqual(["neo"], list(inputs["families"]))
            with zipfile.ZipFile(base / "artifacts/nativecontrolneo-1.0.0.jar") as jar:
                self.assertIn("META-INF/neoforge.mods.toml", jar.namelist())
            # The whole set still names every image it lacks.
            with mock.patch.object(nc, "BASE", base), mock.patch.object(nc, "fapi_source", lambda: fapi), \
                    mock.patch.object(nc, "run_logged", self._fake_javac(seen)), \
                    self.assertRaisesRegex(RuntimeError, "prepare fabric before compiling"):
                nc.compile_canaries()

    def test_a_later_build_keeps_another_familys_entry_only_while_the_sources_are_the_same(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            base = tmp / "nc"
            for family in nc.CONTROL_FAMILIES:
                self._image(base, family)
            fapi = tmp / "fabric-api-0.155.2+26.2.jar"
            fapi.write_bytes(b"x")
            earlier = {"sources": nc.sources(), "families": {"fabric": {"mod": {"sha256": "f"}}, "forge": {"mod": {}}}}
            (base / "artifacts").mkdir(parents=True)
            (base / "artifacts/build-inputs.json").write_text(json.dumps(earlier))
            with mock.patch.object(nc, "BASE", base), mock.patch.object(nc, "fapi_source", lambda: fapi), \
                    mock.patch.object(nc, "run_logged", self._fake_javac([])), \
                    mock.patch.object(nc.subprocess, "check_output", return_value="javac 25"), \
                    mock.patch("sys.stdout", new_callable=io.StringIO):
                nc.compile_canaries(("neo",))
                self.assertEqual({"fabric", "neo"}, set(json.loads((base / "artifacts/build-inputs.json").read_text())["families"]))
                earlier["sources"] = [{"path": "changed", "bytes": 1, "sha256": "0"}]
                (base / "artifacts/build-inputs.json").write_text(json.dumps(earlier))
                nc.compile_canaries(("neo",))
                self.assertEqual({"neo"}, set(json.loads((base / "artifacts/build-inputs.json").read_text())["families"]))


class ControlPort(unittest.TestCase):
    def test_the_control_binds_the_callers_gate_port(self):
        class Exited:
            def __init__(self, command, cwd=None, env=None, **kw):
                self.pid = os.getpid(); self.returncode = 1; self.stdin = io.StringIO()

            def poll(self):
                return 1

            def wait(self, *a):
                return 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            base = tmp / "nc"
            image = base / "native/neo"
            (image / "libraries/x").mkdir(parents=True)
            (image / "libraries/x/lib.jar").write_bytes(b"lib")
            (image / "installation.json").write_text("{}")
            canary = base / "artifacts/nativecontrolneo-1.0.0.jar"
            canary.parent.mkdir(parents=True)
            canary.write_bytes(b"canary")
            (base / "artifacts/build-inputs.json").write_text(json.dumps({"families": {"neo": {"mod": {"sha256": nc.sha(canary)}}}}))
            with mock.patch.dict(os.environ, {"GATE_PORT": "46999"}), mock.patch.object(nc, "BASE", base), \
                    mock.patch.object(nc.subprocess, "Popen", Exited), \
                    mock.patch.object(nc.subprocess, "check_output", return_value="java 25"), \
                    mock.patch("sys.stdout", new_callable=io.StringIO):
                self.assertFalse(nc.run_control("neo", "native", 5))
            (instance,) = (base / "instances").iterdir()
            self.assertIn("server-port=46999\n", (instance / "server.properties").read_text())
            (result,) = (base / "results").iterdir()
            self.assertEqual(46999, json.loads((result / "inputs.json").read_text())["port"])
        env = {k: v for k, v in os.environ.items() if k != "GATE_PORT"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(nc, "free_port", return_value=40001):
            self.assertEqual(40001, nc.control_port())


if __name__ == "__main__":
    unittest.main()
