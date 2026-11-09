"""native-controls.py: how one server console is classified, what identifies a mod set, which stage the Forbric arm
boots, and the swap subcommand's server session."""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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
        print("Stopping the server", flush=True); sys.exit(0)
"""


class ForbricStage(unittest.TestCase):
    """The Forbric arm boots FORBRIC_OLD as the caller set it (map-misc 4.1: it used to replace it with
    ORIGINAL/forbric-loader, which in a Stage 4 worktree links into the main checkout's stage)."""

    def test_the_callers_forbric_old_is_the_stage(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"FORBRIC_OLD": tmp}):
            self.assertEqual(Path(tmp).resolve(), nc.forbric_stage())

    def test_without_forbric_old_the_reference_checkouts_loader_is_the_stage(self):
        env = {k: v for k, v in os.environ.items() if k != "FORBRIC_OLD"}
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertEqual(nc.ORIGINAL / "forbric-loader", nc.forbric_stage())

    def _stage(self, root):
        for rel in ("run/neoforge-patched/patched-mc-neoforge-26.2.jar", "run/neoforge-runtime/neoforge-runtime.jar"):
            (root / rel).parent.mkdir(parents=True, exist_ok=True); (root / rel).write_bytes(rel.encode())
        return root

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
        result, _ = self._run("nosave", [])
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


if __name__ == "__main__":
    unittest.main()
