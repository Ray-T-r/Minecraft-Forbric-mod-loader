package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code run/lib.sh}'s {@code server_ready} and {@code stop_when_ready}, the stdin a gate pipes into its server.
 *
 * <p>A dedicated server builds a console command's source when its console thread READS the line, from the overworld
 * as it is then; the thread starts before the levels load, so a line read during the boot runs on the first tick with
 * no level and throws ("An unexpected error occurred while trying to execute that command" — vanilla 26.2 does the
 * same). Seventeen gates fed "stop" after a fixed sleep, so a boot slower than the sleep left the server running until
 * the gate's hammer, and the gate went red as "never announced a stop". What has to hold: the line never goes out
 * before settle seconds of ticking after "Done (", whenever Done came (before the timer ran out or after); with a
 * boot done a settle or more before the timer it goes out when the timer ends, exactly as before; and a server that
 * stops or fails without reaching Done gets no line at all.
 */
class LibStopWhenReadyContractTest {
	private static final Path LIB = Path.of("run/lib.sh");
	private static final String BOOTING = "[12:00:00] [Server thread/INFO]: Starting Minecraft server on *:25700\n";
	private static final String DONE = "[12:00:04] [Server thread/INFO]: Done (3.210s)! For help, type \"help\"\n";

	@TempDir Path temporary;

	@Test void aBootDoneASettleBeforeTheTimerEndsGetsTheLineWhenTheTimerEnds() throws Exception {
		// Done is in the log from the start, three seconds before the timer and so two more than the one-second settle:
		// the server has had its ticks, and the line goes out at the timer's own moment, as it always did.
		Path log = log(BOOTING + DONE);
		Result r = run("stop_when_ready \"" + log + "\" 3 1");
		assertEquals("stop\n", r.out(), "the line, and only the line");
		assertTrue(r.millis() >= 3000 && r.millis() < 6000,
				"nothing added to a timer that already held the settle (" + r.millis() + " ms)");
	}

	@Test void aBootThatEndsJustBeforeTheTimerStillGetsItsSettle() throws Exception {
		// gate-m3, VERIFY run 1 of C5: Done at 15:16:25 against a 22 s timer, "Stopping the server" at 15:16:26, and the
		// canary's line for its 20th ServerTickEvent never printed. The old helper slept the timer, found Done already
		// there and wrote "stop" at once: a Done in the timer's last second got no settle at all. Here Done lands half a
		// second before a two-second timer, and the line must still wait three seconds after it.
		Path log = log(BOOTING);
		Path doneAt = temporary.resolve("done-at");
		Result r = run("( sleep 1.5; python3 -c 'import time; print(time.time())' > \"" + doneAt + "\"; "
				+ "printf '%s' '" + DONE.replace("'", "'\\''") + "' >> \"" + log + "\" ) &\n"
				+ "stop_when_ready \"" + log + "\" 2 3\n"
				+ "python3 -c 'import time; print(time.time())' > \"" + temporary.resolve("stop-at") + "\"\nwait");
		assertEquals("stop\n", r.out());
		double done = Double.parseDouble(Files.readString(doneAt).trim());
		double stop = Double.parseDouble(Files.readString(temporary.resolve("stop-at")).trim());
		assertTrue(stop - done >= 3.0, "the line came " + (stop - done) + " s after a Done that beat the timer, not after"
				+ " the 3 s settle");
		assertTrue(r.millis() < 10000, "and not much later either (" + r.millis() + " ms)");
	}

	@Test void aTimerOrSettleThatIsNotWholeSecondsIsRefusedOutLoud() throws Exception {
		// The timer counts one-second sleeps; a fraction would make `[ 0 -lt 1.5 ]` an error that skips the timer.
		Path log = log(BOOTING + DONE);
		Result r = run("stop_when_ready \"" + log + "\" 1.5; echo \"rc=$?\"");
		assertTrue(r.out().contains("whole seconds") && r.out().endsWith("rc=0\n") && !r.out().contains("stop\n"),
				"no line, and a reason on stderr: " + r.out());
	}

	@Test void aTimerOrSettleWithALeadingZeroIsRefusedOutLoud() throws Exception {
		// bash arithmetic reads a leading zero as octal: "01" happens to mean 1, "010" would mean 8, and "08" is an error
		// that ends the whole shell, so in a gate the feeder died and no line was ever written (HARN review F4).
		Path log = log(BOOTING + DONE);
		Result r = run("stop_when_ready \"" + log + "\" 1 01; echo \"rc=$?\"; "
				+ "stop_when_ready \"" + log + "\" 1 08; echo \"rc=$?\"");
		assertTrue(r.out().split("leading zero", -1).length == 3 && r.out().endsWith("rc=0\n")
				&& r.out().split("rc=0\n", -1).length == 3 && !r.out().contains("stop\n"),
				"two refusals with a reason, no line, and the shell still running: " + r.out());
	}

	@Test void aBootSlowerThanTheTimerGetsTheLineOnlyAfterDoneAndTheSettle() throws Exception {
		Path log = log(BOOTING);
		Path doneAt = temporary.resolve("done-at");
		// Done lands three seconds in, two after a one-second timer: the old feeder had already written "stop" by then.
		// Done's moment is taken BEFORE the line is written, so it can only be early. Taken after, it was the append plus
		// a python start-up, and when that start-up was slower than the one stamping the stop (a loaded machine) the
		// test measured 1.999 s for a settle lib.sh had given in full (C5 G-C wave 2b, one red in a suite run).
		Result r = run("( sleep 3; python3 -c 'import time; print(time.time())' > \"" + doneAt + "\"; "
				+ "printf '%s' '" + DONE.replace("'", "'\\''") + "' >> \"" + log + "\" ) &\n"
				+ "stop_when_ready \"" + log + "\" 1 2\n"
				+ "python3 -c 'import time; print(time.time())' > \"" + temporary.resolve("stop-at") + "\"\nwait");
		assertEquals("stop\n", r.out());
		double done = Double.parseDouble(Files.readString(doneAt).trim());
		double stop = Double.parseDouble(Files.readString(temporary.resolve("stop-at")).trim());
		assertTrue(stop - done >= 2.0, "the line came " + (stop - done) + " s after Done, not after the 2 s settle");
	}

	@Test void aServerThatStopsWithoutReachingDoneGetsNoLine() throws Exception {
		// A failed boot still prints "Stopping server" on its way out; writing into its closed stdin would only make
		// the pipeline report a broken pipe, and under pipefail some gates read the pipeline's status.
		Path log = log(BOOTING + "[12:00:01] [Server thread/ERROR]: Encountered an unexpected exception\n"
				+ "[12:00:01] [Server thread/INFO]: Stopping server\n");
		Result r = run("stop_when_ready \"" + log + "\" 1; echo \"rc=$?\"; server_ready \"" + log + "\" 0; echo \"ready=$?\"");
		assertEquals("rc=0\nready=1\n", r.out(), "no stop, a clean status from the feeder, and server_ready says why");
	}

	@Test void aLauncherThatNeverStartedAGameGetsNoLine() throws Exception {
		Path log = log("[kernel-launch] FATAL: kernel jar build failed — refusing to launch a stale jar\n");
		Result r = run("stop_when_ready \"" + log + "\" 0");
		assertEquals("", r.out());
		assertTrue(r.millis() < 4000, "it noticed at once (" + r.millis() + " ms)");
	}

	@Test void aLaunchTheKernelRefusedEndsTheWaitAtOnce() throws Exception {
		// gate-m19 and gate-m24 provoke this on purpose. The gate's wait on the server's pid also waits for the feeder
		// (it is the same pipeline), so a feeder that kept polling for a Done that cannot come held the gate for the
		// whole cap: measured on gate-m19, 341 s against 186 s.
		Path log = log(BOOTING + "[12:00:01] [main/ERROR]: [Forbric/Compatibility] launch stopped: required mod "
				+ "initialization or features are unavailable; continuation was not approved (policy STRICT).\n"
				+ "[Forbric/Compatibility] launch stopped by compatibility policy; see .forbric-kernel/compatibility-report.json\n");
		Result r = run("stop_when_ready \"" + log + "\" 0");
		assertEquals("", r.out());
		assertTrue(r.millis() < 4000, "it noticed at once (" + r.millis() + " ms)");
	}

	@Test void aServerThatNeverGetsThereIsGivenUpAtTheCap() throws Exception {
		Path log = log(BOOTING);
		// Growing, so the stall rule stays out of it and the cap is what ends the wait.
		Result r = run("( for i in 1 2 3 4 5 6 7 8 9 10 11 12; do echo still booting >> \"" + log + "\"; sleep 0.5; done ) &\n"
				+ "STOP_READY_CAP=2 STOP_READY_STALL=30 stop_when_ready \"" + log + "\" 0\nwait");
		assertEquals("", r.out(), "no line for a server that never reached Done: the gate's own wait reports it");
		assertTrue(r.millis() >= 2000 && r.millis() < 9000, "bounded by the cap (" + r.millis() + " ms)");
	}

	@Test void aLogThatStopsGrowingIsGivenUpAtTheStall() throws Exception {
		// A server that died without a word: nothing more will be written, and nothing will read the line.
		Path log = log(BOOTING);
		Result r = run("STOP_READY_CAP=60 STOP_READY_STALL=2 stop_when_ready \"" + log + "\" 0");
		assertEquals("", r.out());
		assertTrue(r.millis() >= 2000 && r.millis() < 8000, "bounded by the stall, not the cap (" + r.millis() + " ms)");
	}

	@Test void aBootThatKeepsLoggingIsNotTakenForAStall() throws Exception {
		Path log = log(BOOTING);
		Result r = run("( for i in 1 2 3 4 5 6 7 8; do echo still booting >> \"" + log + "\"; sleep 0.5; done; "
				+ "printf '%s' '" + DONE + "' >> \"" + log + "\" ) &\n"
				+ "STOP_READY_STALL=2 stop_when_ready \"" + log + "\" 0 0\nwait");
		assertEquals("stop\n", r.out(), "four seconds of boot under a two-second stall rule still gets its line");
	}

	@Test void everyGateWritesItsStopThroughTheHelperOrWaitsForDoneItself() throws Exception {
		// The seventeen timers this replaced are the regression to keep out: a feeder that writes a command after a
		// bare sleep, with nothing between it and the boot that checks for "Done (".
		StringBuilder offenders = new StringBuilder();
		try (var gates = Files.newDirectoryStream(Path.of("run"), "gate-*.sh")) {
			for (Path gate : gates) {
				String text = Files.readString(gate, StandardCharsets.UTF_8);
				if (text.matches("(?s).*\\(\\s*sleep\\s+[0-9]+\\s*;\\s*(echo stop|probe_commands).*")) {
					offenders.append(gate.getFileName()).append('\n');
				}
			}
		}
		assertEquals("", offenders.toString(), "gates that write a console command on a bare timer");
	}

	private Path log(String contents) throws Exception {
		Path p = temporary.resolve("server.log");
		Files.writeString(p, contents, StandardCharsets.UTF_8);
		return p;
	}

	private Result run(String call) throws Exception {
		String script = ". \"" + LIB.toAbsolutePath() + "\"\n" + call + "\n";
		Path runner = temporary.resolve("runner.sh");
		Files.writeString(runner, script, StandardCharsets.UTF_8);
		ProcessBuilder pb = new ProcessBuilder(List.of("bash", runner.toString()));
		pb.directory(Path.of("").toAbsolutePath().toFile());
		Path output = temporary.resolve("runner.out");
		pb.redirectErrorStream(true);
		pb.redirectOutput(output.toFile());
		long start = System.nanoTime();
		Process p = pb.start();
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
			throw new IllegalStateException("lib.sh did not finish: " + Files.readString(output, StandardCharsets.UTF_8));
		}
		long millis = (System.nanoTime() - start) / 1_000_000;
		return new Result(p.exitValue(), Files.readString(output, StandardCharsets.UTF_8), millis);
	}

	private record Result(int exit, String out, long millis) {
	}
}
