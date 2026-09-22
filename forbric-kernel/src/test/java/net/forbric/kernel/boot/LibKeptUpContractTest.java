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

	private Result check(Path log) throws Exception {
		return run("check_kept_up \"kept up\" \"" + log + "\"");
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
