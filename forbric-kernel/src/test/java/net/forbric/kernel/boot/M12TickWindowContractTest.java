package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gate-m12's tick-lateness window (C5 wave 2b and its review fixes A-1, A-2, B-2), run offline: the block between
 * {@code M12_TICK_WINDOW_BEGIN} and {@code M12_TICK_WINDOW_END} against synthetic server and client logs. The window opens
 * at the last sampler line stamped before the client's dial, so the configuration-phase handshake is in it, and closes at
 * the first sampler line after the leave (or the server's last line); the ceiling keeps the old one's density, 20 late in
 * 700 ticks. Its first cut had a "?" path and a fallback that only live control runs ever reached.
 */
class M12TickWindowContractTest {
	private static final Path M12 = Path.of("run/gate-m12-multiplayer.sh");
	private static final String COVERED = "the tick sampler covered the session";
	private static final String CEILING = "server ticks within the lateness ceiling from the client's dial to the leave";
	private static final String CEILING_RED = "server ticks late from the client's dial to the leave";

	@TempDir Path temporary;

	/** A server log: one sampler line a second, 20 ticks each, from 12:00:00; {@code seconds(n, late)} adds {@code late} to each of its lines. */
	private static final class Server {
		final List<String> lines = new ArrayList<>();
		int ticks, late, second;

		Server seconds(int n, int latePerLine) {
			for (int i = 0; i < n; i++) {
				ticks += 20;
				late += latePerLine;
				lines.add(stamp(second++) + " [Server thread/INFO]: [Forbric/Tick] " + ticks + " tick(s): mean 50.00ms (a server "
						+ "keeping up holds 50), max 60.00ms, at twice the budget or worse " + late + " (0.1%), resumes after a pause 0");
			}
			return this;
		}

		Server say(String text) {
			lines.add(stamp(second) + " [Server thread/INFO]: " + text);
			return this;
		}

		int now() { return second; }

		String text() { return String.join("\n", lines) + "\n"; }
	}

	private static String stamp(int second) {
		int s = 12 * 3600 + second;
		return "[%02d:%02d:%02d]".formatted(s / 3600 % 24, s / 60 % 60, s % 60);
	}

	private static String client(int dialSecond) {
		// The gate appends latest.log to the client's stdout, so every line is there twice.
		String dial = stamp(dialSecond) + " [Render thread/INFO]: Connecting to 127.0.0.1, 46661\n";
		return "[kernel-launch] CLIENT rundir=x\n" + stamp(dialSecond - 1) + " [Render thread/INFO]: Sound engine started\n"
				+ dial + dial;
	}

	/**
	 * Boot 30 s (the last line before the dial carries {@code lateBefore}), dial at 30, join 3 s later (the last handshake
	 * line carries {@code lateHandshake}), 8 s on (the last line carries {@code lateSession}), leave, one more line: the
	 * window runs from the 600-tick line stamped 12:00:29 to the 840-tick line after the leave, 240 ticks.
	 */
	private static Server usual(int lateBefore, int lateHandshake, int lateSession) {
		Server s = new Server().seconds(29, 0).seconds(1, lateBefore);
		s.seconds(2, 0).seconds(1, lateHandshake);
		s.say("ForbricKernel joined the game").seconds(7, 0).seconds(1, lateSession);
		return s.say("ForbricKernel left the game").seconds(1, 0).say("Stopping the server");
	}

	@Test
	void aWindowAtTheUsualLatenessIsGreenAndSaysWhatItCounted() throws Exception {
		Result r = run(usual(0, 0, 2).text(), client(30));
		assertGreen(r);
		assertTrue(r.output().contains("PASS " + COVERED + " (180 ticks between the join and the leave)"), r.output());
		// The window opens at the 600-tick line stamped 12:00:29, the last in an earlier second than the dial at 12:00:30.
		assertTrue(r.output().contains("PASS " + CEILING + " (2 of 240 <= 7)"), r.output());
	}

	@Test
	void lateTicksBeforeTheDialArePrintedButNotAsserted() throws Exception {
		Result r = run(usual(400, 0, 1).text(), client(30));
		assertGreen(r);
		assertTrue(r.output().contains("400 of 600 tick(s) before the client dialled"), r.output());
	}

	@Test
	void lateTicksInTheHandshakeCountEvenWithNoneWhileThePlayerIsOn() throws Exception {
		// Review A-1: the join comes after the configuration phase, so a window opened at the join never saw this.
		Result r = run(usual(0, 8, 0).text(), client(30));
		assertRed(r, CEILING_RED);
		assertTrue(r.output().contains("8 of 60 from the dial to the join"), r.output());
	}

	@Test
	void theCeilingKeepsTheOldDensityNotTheOldNumber() throws Exception {
		// 240 ticks: ceil(240 x 20 / 700) = 7. Seven is green, eight is red; the old ceiling of 20 allowed 20.
		assertGreen(run(usual(0, 0, 7).text(), client(30)));
		assertRed(run(usual(0, 0, 8).text(), client(30)), CEILING_RED);
		assertRed(run(usual(0, 0, 20).text(), client(30)), CEILING_RED);
	}

	@Test
	void anExplicitCeilingReplacesTheDerivedOne() throws Exception {
		assertGreen(run(usual(0, 0, 12).text(), client(30), "12"));
		assertRed(run(usual(0, 0, 13).text(), client(30), "12"), CEILING_RED);
	}

	@Test
	void aServerStoppedBeforeALineAfterTheLeaveClosesAtItsLastLine() throws Exception {
		Server s = new Server().seconds(30, 0).seconds(3, 0).say("ForbricKernel joined the game").seconds(8, 0);
		s.say("ForbricKernel left the game").say("Stopping the server");
		Result r = run(s.text(), client(30));
		assertGreen(r);
		assertTrue(r.output().contains("(160 ticks between the join and the leave)"), r.output());
	}

	@Test
	void noDialNoJoinOrNoLeaveIsRedOnBothChecks() throws Exception {
		Result noDial = run(usual(0, 0, 0).text(), "[kernel-launch] CLIENT rundir=x\n");
		assertRed(noDial, "FAIL " + COVERED);
		assertRed(noDial, CEILING_RED);
		Server noJoin = new Server().seconds(45, 0).say("Stopping the server");
		assertRed(run(noJoin.text(), client(30)), "FAIL " + COVERED);
		Server noLeave = new Server().seconds(33, 0).say("ForbricKernel joined the game").seconds(8, 0);
		assertRed(run(noLeave.text(), client(30)), "FAIL " + COVERED);
	}

	@Test
	void aSessionUnderOneHundredTicksIsRed() throws Exception {
		Server s = new Server().seconds(33, 0).say("ForbricKernel joined the game").seconds(3, 0);
		s.say("ForbricKernel left the game").seconds(1, 0);
		Result r = run(s.text(), client(30));
		assertRed(r, "FAIL " + COVERED + " (got 80;");
	}

	@Test
	void aRunAcrossMidnightStillOpensTheWindowAtTheDial() throws Exception {
		// Start 20 s before midnight: the dial is stamped 00:00:10, every line before it 23:59:4x or 00:00:0x.
		Server s = new Server();
		s.second = 12 * 3600 - 20;
		s.seconds(30, 0);
		int dial = s.now();
		s.seconds(3, 0).say("ForbricKernel joined the game").seconds(8, 0);
		s.late += 1;
		s.say("ForbricKernel left the game").seconds(1, 0);
		Result r = run(s.text(), client(dial));
		assertGreen(r);
		assertTrue(r.output().contains("(1 of 240 <= 7)"), r.output());
	}

	private Result run(String server, String client) throws Exception { return run(server, client, null); }

	private Result run(String server, String client, String ceiling) throws Exception {
		Path slog = Files.writeString(temporary.resolve("server.log"), server);
		Path clog = Files.writeString(temporary.resolve("client.log"), client);
		String script = Files.readString(M12);
		int begin = script.indexOf("# M12_TICK_WINDOW_BEGIN");
		int end = script.indexOf("# M12_TICK_WINDOW_END");
		assertTrue(begin >= 0 && end > begin, "missing executable gate contract M12_TICK_WINDOW");
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", "FAIL=0\n" + script.substring(begin, end) + "\nexit \"$FAIL\"");
		builder.environment().put("SLOG", slog.toString());
		builder.environment().put("CLOG", clog.toString());
		builder.environment().remove("M12_LATE_CEILING");
		if (ceiling != null) builder.environment().put("M12_LATE_CEILING", ceiling);
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(15, TimeUnit.SECONDS), "offline gate contract timed out");
		return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	private static void assertGreen(Result r) {
		assertEquals(0, r.exit(), r.output());
		assertTrue(r.output().contains("PASS " + COVERED) && r.output().contains("PASS " + CEILING), r.output());
	}

	private static void assertRed(Result r, String diagnostic) {
		assertEquals(1, r.exit(), r.output());
		assertTrue(r.output().contains(diagnostic), r.output());
	}

	private record Result(int exit, String output) {}
}
