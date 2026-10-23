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
 * the gate's hammer, and the gate went red as "never announced a stop". What has to hold: with the boot inside the
 * timer the line goes out when the timer ends, exactly as before; with a slower boot it goes out only after "Done (",
 * with settle seconds of ticking; and a server that stops or fails without reaching Done gets no line at all.
 */
class LibStopWhenReadyContractTest {
	private static final Path LIB = Path.of("run/lib.sh");
	private static final String BOOTING = "[12:00:00] [Server thread/INFO]: Starting Minecraft server on *:25700\n";
	private static final String DONE = "[12:00:04] [Server thread/INFO]: Done (3.210s)! For help, type \"help\"\n";

	@TempDir Path temporary;

	@Test void aBootInsideTheTimerGetsTheLineWhenTheTimerEnds() throws Exception {
		Path log = log(BOOTING + DONE);
		Result r = run("stop_when_ready \"" + log + "\" 1 6");
		assertEquals("stop\n", r.out(), "the line, and only the line");
		assertTrue(r.millis() >= 1000 && r.millis() < 5000,
				"no settle when Done was already there — the old timer's moment (" + r.millis() + " ms)");
	}

	@Test void aBootSlowerThanTheTimerGetsTheLineOnlyAfterDoneAndTheSettle() throws Exception {
		Path log = log(BOOTING);
		Path doneAt = temporary.resolve("done-at");
		// Done lands three seconds in, two after a one-second timer: the old feeder had already written "stop" by then.
		Result r = run("( sleep 3; printf '%s' '" + DONE.replace("'", "'\\''") + "' >> \"" + log + "\"; "
				+ "python3 -c 'import time; print(time.time())' > \"" + doneAt + "\" ) &\n"
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
