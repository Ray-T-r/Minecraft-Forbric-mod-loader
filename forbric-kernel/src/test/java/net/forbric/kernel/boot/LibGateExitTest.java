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
