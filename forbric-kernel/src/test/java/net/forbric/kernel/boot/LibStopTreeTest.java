package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code run/lib.sh}'s {@code stop_tree} on the process shape gate-m17 starts its client in: a subshell running
 * {@code tr … | xargs -0 env … java}, so the JVM is the subshell's grandchild. The gates used to stop a client by
 * signalling the recorded pid and its direct children; there that reached the subshell, tr and xargs, and the JVM
 * lived on, re-parented to launchd, after the gate had reported "CAUGHT none" and exited -- and then wrote failures
 * into the gate's caught file that nobody reported. Here a shell stands in for the JVM.
 */
class LibStopTreeTest {
	private static final Path LIB = Path.of("run/lib.sh").toAbsolutePath();

	@TempDir Path temporary;
	private final List<Long> started = new ArrayList<>();

	@AfterEach void killWhateverATestLeftAlive() {
		for (long pid : started) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
	}

	/**
	 * {@code ( cd … && printf … | xargs -0 sh -c <body> sh <pidfile> ) &}: the stand-in records its pid, then loops.
	 * {@code stopper} is what the gate runs on the subshell's pid; the script then prints the subshell's pid and exits.
	 */
	private Path gate(String body, String stopper) throws IOException {
		Path pidFile = temporary.resolve("jvm.pid");
		String script = ". \"" + LIB + "\"\n"
				+ "( cd \"" + temporary + "\" && printf 'x\\0' | xargs -0 sh -c '" + body + "' sh \"" + pidFile + "\" ) > /dev/null 2>&1 &\n"
				+ "SUB=$!\n"
				+ "for i in $(seq 1 100); do [ -s \"" + pidFile + "\" ] && break; sleep 0.1; done\n"
				+ "sleep 0.3\n"
				+ stopper.replace("$PID", "\"$SUB\"") + "\n"
				+ "wait \"$SUB\" 2>/dev/null\n"
				+ "echo \"subshell $SUB\"\n";
		Path file = temporary.resolve("stoptree.sh");
		Files.writeString(file, script, StandardCharsets.UTF_8);
		return file;
	}

	/** The stand-in: on TERM it writes {@code <pidfile>.term} and exits, like a JVM running its shutdown hooks. */
	private static final String POLITE = "trap \"echo term > \\\"$1.term\\\"; exit 0\" TERM; echo $$ > \"$1\"; while :; do sleep 0.1; done";
	/** One that ignores TERM. */
	private static final String DEAF = "trap \"\" TERM; echo $$ > \"$1\"; while :; do sleep 0.1; done";

	@Test void theOldChildrenOnlyStopLeavesTheGrandchildRunning() throws Exception {
		// The shape this case is about, measured on the gates' old two lines: the stand-in survives them.
		String old = "for pid in $(pgrep -P $PID 2>/dev/null) $PID; do kill \"$pid\" 2>/dev/null; done\n"
				+ "sleep 2\n"
				+ "for pid in $(pgrep -P $PID 2>/dev/null) $PID; do kill -9 \"$pid\" 2>/dev/null; done";
		run(gate(POLITE, old));
		long jvm = standIn();
		assertTrue(alive(jvm), "the children-only stop left the grandchild running");
	}

	@Test void stopTreeStopsTheGrandchildAndAsksItFirst() throws Exception {
		run(gate(POLITE, "stop_tree $PID"));
		long jvm = standIn();
		assertTrue(gone(jvm), "stop_tree left the grandchild running");
		assertEquals("term", Files.readString(temporary.resolve("jvm.pid.term"), StandardCharsets.UTF_8).trim(),
				"it was asked with TERM, so a JVM's shutdown hooks run");
	}

	@Test void whatIgnoresTheRequestIsKilledAfterTheGrace() throws Exception {
		long before = System.nanoTime();
		run(gate(DEAF, "stop_tree $PID 1"));
		long jvm = standIn();
		assertTrue(gone(jvm), "stop_tree left a process that ignores TERM running");
		assertFalse(Files.exists(temporary.resolve("jvm.pid.term")));
		assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 30, "the grace is bounded");
	}

	@Test void aPidThatIsNotANumberOrIsGoneIsNothingToStop() throws Exception {
		Path file = temporary.resolve("stoptree-nothing.sh");
		Files.writeString(file, ". \"" + LIB + "\"\nstop_tree ''\nstop_tree abc\ntrue & P=$!; wait $P\nstop_tree $P\necho done\n",
				StandardCharsets.UTF_8);
		assertTrue(run(file).contains("done"));
	}

	private long standIn() throws IOException {
		long pid = Long.parseLong(Files.readString(temporary.resolve("jvm.pid"), StandardCharsets.UTF_8).trim());
		started.add(pid);
		return pid;
	}

	private static boolean alive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	/** Gone within a few seconds: an orphan is reaped by launchd, not by us. */
	private static boolean gone(long pid) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			Optional<ProcessHandle> handle = ProcessHandle.of(pid);
			if (handle.isEmpty() || !handle.get().isAlive()) return true;
			Thread.sleep(100);
		}
		return false;
	}

	private String run(Path script) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(List.of("bash", script.toString()));
		pb.directory(Path.of("").toAbsolutePath().toFile());
		pb.environment().remove("FORBRIC_CAUGHT_FILE");
		pb.environment().remove("FORBRIC_CAUGHT_GATE");
		pb.environment().remove("FORBRIC_CAUGHT_STRICT");
		Path output = temporary.resolve(script.getFileName() + ".out");
		pb.redirectErrorStream(true);
		pb.redirectOutput(output.toFile());
		Process p = pb.start();
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
			throw new IllegalStateException("the script did not finish: " + Files.readString(output, StandardCharsets.UTF_8));
		}
		String out = Files.readString(output, StandardCharsets.UTF_8);
		assertEquals(0, p.exitValue(), out);
		return out;
	}
}
