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
 * Drives {@code run/lib.sh}'s {@code check_kept_up} against fixture logs.
 *
 * <p>This is the only performance assertion in the tree, and it is the kind that is easy to write wrong: the
 * interesting half is a pattern that must be ABSENT, and an absence is satisfied by a log that never had the
 * chance to contain it. So the three states each get a case — no player session (the denominator is missing and
 * the assertion must refuse to answer), a player session and no overload, and a player session with one — and
 * the first is the one that would otherwise read green forever on every gate where nobody joins.
 */
class LibKeptUpContractTest {
	private static final String FORBRIC_NARRATION =
			"[15:07:31] [main/WARN]: [Forbric/MergedBaseCompat] every block subclass died on "
					+ "IncompatibleClassChangeError until this repair\n";
	private static final String REAL_REJECTION =
			"[15:07:31] [Server thread/INFO]: Disconnecting client: Incompatible\n";
	private static final Path LIB = Path.of("run/lib.sh");
	private static final String JOIN = "[12:00:00] [Server thread/INFO]: Steve[/127.0.0.1:1] logged in with entity id 42 at (0.5, 64.0, 0.5)\n";
	private static final String OVERLOAD = "[12:00:05] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2531ms or 50 ticks behind\n";
	private static final String EARLY = "[11:59:43] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 30000ms or 600 ticks behind\n";
	private static final String DIAL = "[11:59:55] [Render thread/INFO]: Connecting to 127.0.0.1, 25604\n";
	private static final String LEAVE = "[12:00:20] [Server thread/INFO]: Steve left the game\n";
	private static final String LATE = "[12:00:25] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 4000ms or 80 ticks behind\n";
	private static final String WORSE = "[12:00:09] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 9100ms or 182 ticks behind\n";

	@TempDir Path temporary;

	@Test void aLogWithNoPlayerSessionCannotAnswerTheQuestion() throws Exception {
		Result r = check(log("[12:00:00] [Server thread/INFO]: Done (1.234s)! For help, type \"help\"\n"));
		assertEquals(1, r.exit(), r.out());
		assertTrue(r.out().contains("FAIL"), r.out());
		assertTrue(r.out().contains("never had to keep up"), r.out());
	}

	@Test void anEmptyOrMissingLogFailsRatherThanPassing() throws Exception {
		assertTrue(check(log("")).out().contains("no log to read"), "an empty log is not evidence of anything");
		Result missing = check(temporary.resolve("never-written.log"));
		assertEquals(1, missing.exit(), missing.out());
		assertTrue(missing.out().contains("no log to read"), missing.out());
	}

	@Test void aPlayerSessionWithNoOverloadIsTheOnlyGreen() throws Exception {
		Result r = check(log(JOIN + "[12:00:30] [Server thread/INFO]: Stopping server\n"));
		assertEquals(0, r.exit(), r.out());
		assertTrue(r.out().contains("PASS"), r.out());
		assertTrue(r.out().contains("1 player session(s), 0 overload warnings"), r.out());
	}

	@Test void anOverloadIsRedAndNamesTheWorstOne() throws Exception {
		Result r = check(log(JOIN + OVERLOAD + WORSE));
		assertEquals(1, r.exit(), r.out());
		assertTrue(r.out().contains("2 overload warning(s)"), r.out());
		// The worst line, not the last one: a gate report that says "one warning" without saying how far behind
		// is the same as no measurement at all.
		assertTrue(r.out().contains("Running 9100ms or 182 ticks behind"), r.out());
	}

	/**
	 * Only what comes before the client's dial is left out (C5 wave 2d, VERIFY run 2's C-1): gate-m12 went red on one
	 * overload printed 13 s before the client dialled, while the gate's own client JVM was booting beside the server. Red on
	 * 421c53cc, which counted the whole log.
	 */
	@Test void anOverloadBeforeTheClientDialledIsNotCounted() throws Exception {
		Result r = check(log(EARLY + JOIN + "[12:00:20] [Server thread/INFO]: Steve lost connection: Disconnected\n"
				+ LEAVE + "[12:00:40] [Server thread/INFO]: Stopping server\n"), client(DIAL));
		assertEquals(0, r.exit(), r.out());
		assertTrue(r.out().contains("PASS kept up (1 player session(s), 0 overload warnings from the client's dial on)"), r.out());
		// Not asserted, but not hidden either: the gate log still says what happened before the dial.
		assertTrue(r.out().contains("1 overload warning(s) before the client dialled"), r.out());
	}

	/**
	 * The handshake counts (HARN2's review A-1): the server's "logged in" line comes after the configuration phase, which
	 * runs on the server thread over several ticks and is what gate-m16 exists for. Green on 63e561cc, whose window opened
	 * at the join.
	 */
	@Test void anOverloadBetweenTheDialAndTheJoinCounts() throws Exception {
		String handshake = "[11:59:58] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 5000ms or 100 ticks behind\n";
		Result r = check(log(EARLY + handshake + JOIN + LEAVE), client(DIAL));
		assertEquals(1, r.exit(), r.out());
		assertTrue(r.out().contains("FAIL kept up (1 overload warning(s) from the client's dial on; worst: Running 5000ms or 100 ticks behind)"),
				r.out());
	}

	/**
	 * The leave's own tick counts (HARN2's review A-1): vanilla warns at the start of the tick after a late one, and the
	 * leave's tick prints "left the game" before it fires the logged-out event and saves the player, so a stall there is
	 * printed after the leave. Nor does a chat or mod line that happens to say "left the game" end anything (review A-2).
	 * Both green on 63e561cc, which closed the window at the first line saying "left the game".
	 */
	@Test void anOverloadAfterTheLeaveOrAfterALineSayingItCounts() throws Exception {
		Result logout = check(log(JOIN + LEAVE + LATE + "[12:00:40] [Server thread/INFO]: Stopping server\n"), client(DIAL));
		assertEquals(1, logout.exit(), logout.out());
		assertTrue(logout.out().contains("1 overload warning(s) from the client's dial on"), logout.out());
		String chat = "[12:00:03] [Server thread/INFO]: <Steve> I left the game settings alone\n";
		Result said = check(log(JOIN + chat + OVERLOAD + LEAVE), client(DIAL));
		assertEquals(1, said.exit(), said.out());
		assertTrue(said.out().contains("1 overload warning(s) from the client's dial on"), said.out());
	}

	@Test void anOverloadAfterTheDialIsRedAndTheWorstNamedIsFromTheCountedOnes() throws Exception {
		// The 30000 ms one is before the dial: it is not the worst of the warnings the verdict counts.
		Result r = check(log(EARLY + JOIN + OVERLOAD + LEAVE), client(DIAL));
		assertEquals(1, r.exit(), r.out());
		assertTrue(r.out().contains("FAIL kept up (1 overload warning(s) from the client's dial on; worst: Running 2531ms or 50 ticks behind)"),
				r.out());
	}

	@Test void withoutAUsableDialTheWholeLogCounts() throws Exception {
		// No client log, a client log that never dialled, and one whose dial is stamped before the server's first line (a
		// stale log from an earlier run, not a run across midnight): a missing or doubtful boundary counts more, never less.
		String serverLog = "[11:59:00] [Server thread/INFO]: Starting minecraft server version 26.2\n" + EARLY + JOIN + LEAVE;
		for (Path clientLog : List.of(temporary.resolve("never-written-client.log"),
				client("[11:59:50] [Render thread/INFO]: Setting user: Steve\n"),
				client("[09:12:00] [Render thread/INFO]: Connecting to 127.0.0.1, 25604\n"))) {
			Result r = check(log(serverLog), clientLog);
			assertEquals(1, r.exit(), clientLog + ": " + r.out());
			assertTrue(r.out().contains("no client dial to open the window at"), r.out());
			assertTrue(r.out().contains("1 overload warning(s) from the client's dial on; worst: Running 30000ms"), r.out());
		}
	}

	@Test void aRunAcrossMidnightKeepsTheDialWhereItWas() throws Exception {
		String serverLog = "[23:59:30] [Server thread/INFO]: Starting minecraft server version 26.2\n"
				+ "[23:59:50] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 7000ms or 140 ticks behind\n"
				+ "[00:00:09] [Server thread/INFO]: Steve[/127.0.0.1:1] logged in with entity id 42 at (0.5, 64.0, 0.5)\n"
				+ "[00:00:11] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2100ms or 42 ticks behind\n";
		// Dialled after midnight: the 23:59:50 one is before it, the 00:00:11 one after.
		Result after = check(log(serverLog), client("[00:00:05] [Render thread/INFO]: Connecting to 127.0.0.1, 25604\n"));
		assertEquals(1, after.exit(), after.out());
		assertTrue(after.out().contains("1 overload warning(s) before the client dialled"), after.out());
		assertTrue(after.out().contains("1 overload warning(s) from the client's dial on; worst: Running 2100ms"), after.out());
		// Dialled before midnight: both are after it.
		Result before = check(log(serverLog), client("[23:59:40] [Render thread/INFO]: Connecting to 127.0.0.1, 25604\n"));
		assertTrue(before.out().contains("2 overload warning(s) from the client's dial on; worst: Running 7000ms"), before.out());
	}

	@Test void aWarningBetweenTwoSessionsCounts() throws Exception {
		// The server is still serving the gate between them; the session count is the denominator, not the window.
		String alex = "[12:01:00] [Server thread/INFO]: Alex[/127.0.0.1:2] logged in with entity id 43 at (0.5, 64.0, 0.5)\n";
		String alexLeft = "[12:01:30] [Server thread/INFO]: Alex left the game\n";
		Result between = check(log(JOIN + LEAVE + LATE + alex + alexLeft), client(DIAL));
		assertEquals(1, between.exit(), between.out());
		assertTrue(between.out().contains("1 overload warning(s) from the client's dial on"), between.out());
		Result quiet = check(log(JOIN + LEAVE + alex + alexLeft), client(DIAL));
		assertEquals(0, quiet.exit(), quiet.out());
		assertTrue(quiet.out().contains("2 player session(s), 0 overload warnings"), quiet.out());
	}

	@Test void theKernelsOwnExplanationOfABugIsNotTheGameDoingIt() throws Exception {
		// gate-m12 went red on exactly this: its assertion looked for "Incompatible" in a rejection, and the
		// repair that FIXED the incompatibility explains itself using the word. Rewording the explanation is not
		// the answer, because the next explanation does it again.
		Path onlyNarration = log("a normal line\n" + FORBRIC_NARRATION);
		Result excluded = absent(onlyNarration, "Incompatible", "\\[Forbric/");
		assertEquals(0, excluded.exit(), excluded.out());
		assertTrue(excluded.out().contains("PASS"), excluded.out());

		// And without the exclusion it still catches it, so the exclusion is doing the work, not the pattern.
		Result unfiltered = absent(onlyNarration, "Incompatible", null);
		assertEquals(1, unfiltered.exit(), unfiltered.out());
	}

	@Test void arealRejectionIsStillCaughtThroughTheExclusion() throws Exception {
		Path both = log(FORBRIC_NARRATION + REAL_REJECTION);
		Result r = absent(both, "Incompatible", "\\[Forbric/");
		assertEquals(1, r.exit(), "the exclusion must not blind the assertion to the real thing: " + r.out());
		assertTrue(r.out().contains("present x1"), r.out());
	}

	private Result absent(Path log, String pattern, String except) throws Exception {
		String call = "check_absent \"rejected\" \"" + pattern + "\" \"" + log + "\""
				+ (except == null ? "" : " '" + except + "'");
		return run(call);
	}

	private Path log(String contents) throws Exception {
		Path p = temporary.resolve("server.log");
		Files.writeString(p, contents, StandardCharsets.UTF_8);
		return p;
	}

	private Path client(String contents) throws Exception {
		Path p = temporary.resolve("client-" + System.nanoTime() + ".log");
		Files.writeString(p, contents, StandardCharsets.UTF_8);
		return p;
	}

	private Result check(Path log) throws Exception {
		return run("check_kept_up \"kept up\" \"" + log + "\"");
	}

	private Result check(Path log, Path clientLog) throws Exception {
		return run("check_kept_up \"kept up\" \"" + log + "\" \"" + clientLog + "\"");
	}

	private Result run(String call) throws Exception {
		String script = ". \"" + LIB.toAbsolutePath() + "\"\nFAIL=0\n" + call + "\nexit \"$FAIL\"\n";
		Path runner = temporary.resolve("runner.sh");
		Files.writeString(runner, script, StandardCharsets.UTF_8);
		ProcessBuilder pb = new ProcessBuilder(List.of("bash", runner.toString()));
		pb.directory(Path.of("").toAbsolutePath().toFile());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IllegalStateException("check_kept_up did not finish: " + out);
		}
		return new Result(p.exitValue(), out);
	}

	private record Result(int exit, String out) {
	}
}
