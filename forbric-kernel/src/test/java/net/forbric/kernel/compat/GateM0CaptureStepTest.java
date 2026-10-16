/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gate-m0's step 1b, the capture SharedFinalSourceActualTest replays, run as the gate runs it but against a stubbed
 * server: the verdict must come from what the capture holds, not from whether a server booted.
 *
 * <p>The capture is only the fixture the test asks for when fabric-api was installed: a server without it still boots
 * and still defines an Entity, the wrong one. The step used to print FAIL for a fabric-api it could not copy and then
 * carry on, boot that server and pass on its capture.
 */
class GateM0CaptureStepTest {
	private static final Path GATE = Path.of("run/gate-m0.sh");
	private static final String ENTITY = "net/minecraft/world/entity/Entity\\tabc\\n";
	private static final String FABRIC = "net/fabricmc/fabric/api/event/Event\\tdef\\n";
	@TempDir Path temp;

	@Test
	void noFabricApiToCaptureWithFailsTheStepAndStartsNoServer() throws Exception {
		Result result = run(false, ENTITY + FABRIC, true);
		assertEquals(1, result.exit(), result.output());
		assertTrue(result.output().contains("FAIL no fabric-api 0.155.2"), result.output());
		assertTrue(result.output().contains("LAUNCHED=no"), result.output());
		assertFalse(result.output().contains("PASS"), result.output());
	}

	@Test
	void aCaptureWithoutFabricApisClassesFails() throws Exception {
		Result result = run(true, ENTITY, true);
		assertEquals(1, result.exit(), result.output());
		assertTrue(result.output().contains("FAIL no complete capture with Entity and fabric-api's classes"), result.output());
		assertTrue(result.output().contains("LAUNCHED=yes"), result.output());
	}

	@Test
	void aCaptureThatIsNotIntactFails() throws Exception {
		Result result = run(true, ENTITY + FABRIC, false);
		assertEquals(1, result.exit(), result.output());
		assertTrue(result.output().contains("FAIL no complete capture"), result.output());
	}

	@Test
	void anIntactCaptureWithEntityAndFabricApiPasses() throws Exception {
		Result result = run(true, ENTITY + FABRIC, true);
		assertEquals(0, result.exit(), result.output());
		assertTrue(result.output().contains("PASS the kernel's final Entity was captured with fabric-api's classes"),
				result.output());
	}

	/** Runs the gate's own fragment with lib.sh's server helpers stubbed; the stub server records {@code defined}. */
	private Result run(boolean fabricApi, String defined, boolean intact) throws Exception {
		Path kernel = Files.createDirectories(temp.resolve("kernel"));
		Path build = Files.createDirectories(temp.resolve("build"));
		Path mods = Files.createDirectories(kernel.resolve("run/client-merged-pack/mods"));
		if (fabricApi) Files.writeString(mods.resolve("fabric-api-0.155.2+26.2.jar"), "stub");
		Path server = kernel.resolve("run/launch-kernel-server.sh");
		Files.writeString(server, "#!/usr/bin/env bash\n"
				+ "touch \"$(dirname \"$0\")/../launched\"\n"
				+ "d=\"${FORBRIC_JVM##*=}\"; mkdir -p \"$d/definitions-1\"\n"
				+ "printf '" + defined + "' > \"$d/definitions-1/definitions.tsv\"\n"
				+ (intact ? "touch \"$d/definitions-1/intact\"\n" : "")
				+ "echo 'Done (1.0s)!'\ncat > /dev/null\n");
		assertTrue(server.toFile().setExecutable(true));
		String program = "KERNEL=\"$1\"\nBUILD=\"$2\"\nFAIL=0\n"
				+ "reap_stale_server() { :; }; seed_server_properties() { :; }; record_server_pid() { :; }\n"
				+ "await_server() { wait \"$1\"; }\n"
				+ section(Files.readString(GATE), "M0_CAPTURE")
				+ "\nprintf 'LAUNCHED=%s\\n' \"$([ -f \"$KERNEL/launched\" ] && echo yes || echo no)\"\nexit \"$FAIL\"\n";
		Path log = temp.resolve("step.log");
		Process process = new ProcessBuilder(List.of("bash", "-c", program, "m0-capture-test", kernel.toString(), build.toString()))
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		boolean ended = process.waitFor(60, TimeUnit.SECONDS);
		if (!ended) process.destroyForcibly();
		assertTrue(ended, "the gate fragment timed out");
		return new Result(process.exitValue(), Files.readString(log));
	}

	private static String section(String gate, String name) {
		int start = gate.indexOf("# " + name + "_BEGIN");
		assertTrue(start >= 0, "production fragment missing: " + name);
		start = gate.indexOf('\n', start) + 1;
		int end = gate.indexOf("# " + name + "_END", start);
		assertTrue(end > start, "production fragment end missing: " + name);
		return gate.substring(start, end);
	}

	private record Result(int exit, String output) {}
}
