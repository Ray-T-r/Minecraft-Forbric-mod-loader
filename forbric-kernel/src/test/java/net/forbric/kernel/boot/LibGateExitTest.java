package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code run/lib.sh}'s EXIT trap ({@code _gate_exit}) on a gate that is killed, or ends, with what it started still
 * running (C5 wave 2d). Under heavy.sh the signal that reaches a gate is TERM, and bash runs the EXIT trap when an
 * untrapped TERM kills the script; until this the trap only reported, and the gate's server, client and evidence.py were
 * re-parented to launchd, a JVM on the gate's port (G-B2 measured it on gate-m39 in wave 2b). Each stand-in records its pid
 * and loops; three shapes a gate starts its JVMs in: a background child, a grandchild under a subshell, and the foreground
 * command the gate is waiting on when the TERM comes. On 421c53cc all three outlive the gate.
 */
class LibGateExitTest {
	private static final Path LIB = Path.of("run/lib.sh").toAbsolutePath();
	/** A stand-in JVM: on TERM it writes {@code <pidfile>.term} and exits, as shutdown hooks would run; else loops. */
	private static final String JVM = "sh -c 'trap \"echo term > \\\"$1.term\\\"; exit 0\" TERM; echo $$ > \"$1\"; while :; do sleep 0.1; done' sh";

	@TempDir Path temporary;
	private final List<Long> started = new ArrayList<>();

	@AfterEach void killWhateverATestLeftAlive() {
		for (long pid : started) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
	}

	@Test void aTermToTheGateStopsEverythingItStartedAndAsksFirst() throws Exception {
		Path script = gate(JVM + " \"" + pid("child") + "\" &\n"
				+ "( " + JVM + " \"" + pid("grandchild") + "\" & wait ) &\n"
				+ "for i in $(seq 1 100); do [ -s \"" + pid("child") + "\" ] && [ -s \"" + pid("grandchild") + "\" ] && break; sleep 0.1; done\n"
				+ JVM + " \"" + pid("foreground") + "\"\n"
				+ "echo 'the foreground command returned'\n");
		Process gate = start(script);
		List<Long> jvms = new ArrayList<>();
		for (String which : List.of("child", "grandchild", "foreground")) jvms.add(await(pid(which)));
		Thread.sleep(300);
		gate.destroy();   // SIGTERM, as heavy.sh forwards it
		assertTrue(gate.waitFor(30, TimeUnit.SECONDS), "the gate did not exit on TERM");
		String out = Files.readString(output(script), StandardCharsets.UTF_8);
		for (int i = 0; i < jvms.size(); i++) {
			assertTrue(gone(jvms.get(i)), List.of("child", "grandchild", "foreground").get(i) + " outlived the gate: " + out);
		}
		for (String which : List.of("child", "grandchild", "foreground")) {
			assertTrue(Files.exists(Path.of(pid(which) + ".term")), which + " was not asked with TERM first: " + out);
		}
		assertTrue(out.contains("[kernel] the gate left "), out);
		assertTrue(!out.contains("the foreground command returned"), out);
	}

	@Test void aGateThatEndsWithSomethingStillRunningStopsItAndKeepsItsExitCode() throws Exception {
		Path script = gate(JVM + " \"" + pid("child") + "\" &\n"
				+ "for i in $(seq 1 100); do [ -s \"" + pid("child") + "\" ] && break; sleep 0.1; done\n"
				+ "exit 3\n");
		Process gate = start(script);
		assertTrue(gate.waitFor(30, TimeUnit.SECONDS));
		long jvm = await(pid("child"));
		String out = Files.readString(output(script), StandardCharsets.UTF_8);
		assertEquals(3, gate.exitValue(), out);
		assertTrue(gone(jvm), "a process the gate left running outlived it: " + out);
		assertTrue(out.contains("[kernel] the gate left ") && out.contains(" process(es) running at its exit; stopping them: "), out);
	}

	@Test void aSubshellOrCommandSubstitutionEndingStopsNothing() throws Exception {
		// $$ is the gate's pid in a subshell too; the EXIT trap must not run there, or a `$(...)` would stop the server.
		Path script = gate(JVM + " \"" + pid("child") + "\" &\nC=$!\n"
				+ "for i in $(seq 1 100); do [ -s \"" + pid("child") + "\" ] && break; sleep 0.1; done\n"
				+ "( exit 0 ); x=$(echo hi); y=$( exit 4 ); { true; } | cat\n"
				+ "sleep 0.5; kill -0 \"$C\" && echo 'still running after the subshells'\n"
				+ "kill \"$C\"; wait \"$C\" 2>/dev/null\n");
		Process gate = start(script);
		assertTrue(gate.waitFor(30, TimeUnit.SECONDS));
		await(pid("child"));
		String out = Files.readString(output(script), StandardCharsets.UTF_8);
		assertTrue(out.contains("still running after the subshells"), out);
		assertTrue(!out.contains("[kernel] the gate left"), "nothing was left at the gate's own exit: " + out);
	}

	/**
	 * A server stopped at the gate's exit leaves no pid file naming it (HARN2 review A-3). On 63e561cc the file stayed,
	 * always naming a dead pid, for the next run's reap_stale_server to SIGKILL whatever had that number by then.
	 */
	@Test void aServerStoppedAtTheExitLeavesNoPidFileBehind() throws Exception {
		Path rundir = Files.createDirectory(temporary.resolve("rundir"));
		Path script = gate(JVM + " \"" + pid("server") + "\" &\nrecord_server_pid \"" + rundir + "\" \"$!\"\n"
				+ "for i in $(seq 1 100); do [ -s \"" + pid("server") + "\" ] && break; sleep 0.1; done\n"
				+ "[ -s \"" + rundir.resolve(".forbric-gate.pid") + "\" ] && echo 'pid file written'\nwait\n");
		Process gate = start(script);
		long jvm = await(pid("server"));
		Thread.sleep(300);
		gate.destroy();
		assertTrue(gate.waitFor(30, TimeUnit.SECONDS), "the gate did not exit on TERM");
		String out = Files.readString(output(script), StandardCharsets.UTF_8);
		assertTrue(out.contains("pid file written"), out);
		assertTrue(gone(jvm), out);
		assertTrue(!Files.exists(rundir.resolve(".forbric-gate.pid")), "the pid file outlived the server it named: " + out);
	}

	/**
	 * reap_stale_server kills only the process its file was written for (HARN2 review A-3): a process that started after
	 * the file was written has the number by reuse, and is left alone. On 63e561cc it was SIGKILLed with its tree. A real
	 * leftover, older than its file, is still reaped.
	 */
	@Test void aStaleServerIsReapedAndAReusedPidIsNot() throws Exception {
		Path rundir = Files.createDirectory(temporary.resolve("reap"));
		Path pidFile = rundir.resolve(".forbric-gate.pid");
		Process unrelated = new ProcessBuilder("sleep", "300").start();
		started.add(unrelated.pid());
		Files.writeString(pidFile, unrelated.pid() + "\n");
		Files.setLastModifiedTime(pidFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
		Path reuse = gate("reap_stale_server \"" + rundir + "\"\n");
		assertTrue(start(reuse).waitFor(30, TimeUnit.SECONDS));
		String out = Files.readString(output(reuse), StandardCharsets.UTF_8);
		assertTrue(alive(unrelated.pid()), "a process younger than the pid file was reaped: " + out);
		assertTrue(out.contains("so the pid was reused"), out);
		assertTrue(!Files.exists(pidFile), out);

		Files.writeString(pidFile, unrelated.pid() + "\n");   // written now: the process is older than its file
		Path stale = gate("reap_stale_server \"" + rundir + "\"\n");
		assertTrue(start(stale).waitFor(30, TimeUnit.SECONDS));
		String reaped = Files.readString(output(stale), StandardCharsets.UTF_8);
		assertTrue(gone(unrelated.pid()), "a real leftover was not reaped: " + reaped);
		assertTrue(reaped.contains("reaping server " + unrelated.pid()), reaped);
	}

	/**
	 * A process that takes a few seconds over its shutdown after TERM gets them (HARN2 review A-5): a server JVM saves its
	 * world in its shutdown hook. On 63e561cc it was SIGKILLed 2 s after the TERM and never wrote its marker.
	 */
	@Test void aSlowShutdownAfterTheTermIsLetFinish() throws Exception {
		String slow = "sh -c 'trap \"sleep 4; echo saved > \\\"$1.term\\\"; exit 0\" TERM; echo $$ > \"$1\"; while :; do sleep 0.1; done' sh";
		Path script = gate(slow + " \"" + pid("saver") + "\" &\n"
				+ "for i in $(seq 1 100); do [ -s \"" + pid("saver") + "\" ] && break; sleep 0.1; done\nexit 0\n");
		Process gate = start(script);
		assertTrue(gate.waitFor(30, TimeUnit.SECONDS));
		long jvm = await(pid("saver"));
		String out = Files.readString(output(script), StandardCharsets.UTF_8);
		assertTrue(gone(jvm), out);
		assertTrue(Files.exists(Path.of(pid("saver") + ".term")), "the shutdown was cut short: " + out);
	}

	private String pid(String which) {
		return temporary.resolve(which + ".pid").toString();
	}

	private Path gate(String body) throws IOException {
		Path file = temporary.resolve("gate-" + started.size() + "-" + System.nanoTime() + ".sh");
		Files.writeString(file, ". \"" + LIB + "\"\n" + body, StandardCharsets.UTF_8);
		return file;
	}

	private Path output(Path script) {
		return Path.of(script + ".out");
	}

	private Process start(Path script) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(List.of("bash", script.toString()));
		pb.directory(Path.of("").toAbsolutePath().toFile());
		pb.environment().remove("FORBRIC_CAUGHT_FILE");
		pb.environment().remove("FORBRIC_CAUGHT_GATE");
		pb.environment().remove("FORBRIC_CAUGHT_STRICT");
		pb.redirectErrorStream(true);
		pb.redirectOutput(output(script).toFile());
		Process p = pb.start();
		started.add(p.pid());
		return p;
	}

	private long await(String pidFile) throws Exception {
		Path file = Path.of(pidFile);
		for (int i = 0; i < 100 && (!Files.exists(file) || Files.size(file) == 0); i++) Thread.sleep(100);
		long pid = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
		started.add(pid);
		return pid;
	}

	/** Running: present and not a zombie. */
	private static boolean alive(long pid) throws IOException, InterruptedException {
		Process ps = new ProcessBuilder("ps", "-o", "stat=", "-p", Long.toString(pid)).redirectErrorStream(true).start();
		String state = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return ps.waitFor() == 0 && !state.isEmpty() && !state.startsWith("Z");
	}

	/** No longer running within a few seconds. */
	private static boolean gone(long pid) throws IOException, InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (!alive(pid)) return true;
			Thread.sleep(100);
		}
		return false;
	}
}
