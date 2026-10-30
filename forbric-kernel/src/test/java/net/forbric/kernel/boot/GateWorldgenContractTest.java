package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateWorldgenContractTest {
	@TempDir Path temporary;

	@Test
	void theGateProbesExactlyTheBlocksThePackagedCanariesPlace() throws Exception {
		Path gate = Path.of("run/gate-m25-worldgen.sh");
		String script = Files.readString(gate);
		String forge = WorldgenCanaryDataTest.inspect("forge", "forbriclive", "livemod-src", "forge-runtime");
		String neo = WorldgenCanaryDataTest.inspect("neoforge", "forbricneolive", "livemod-src-neoforge", "neoforge-runtime");
		// Stage 3 of dropping MinecraftForge: the MinecraftForge canary is refused, so the gate no longer stages it, and
		// its marker is neither asserted nor probed for; the NeoForge canary's marker is the one the gate reads.
		assertTrue(script.contains("'^" + neo + ": [1-9][0-9]*'"), "missing positive gate assertion for " + neo);
		assertFalse(script.contains("'^" + forge + ": [1-9][0-9]*'"), "a positive assertion on the refused canary's marker " + forge);
		assertFalse(script.contains("forge-runtime/forbriclive.jar"), "gate-m25 still stages the refused MinecraftForge canary");

		// Run the production shell command, intercepting only Python. This observes quoting, the real region
		// directory and every actual needle; merely retaining the check's regex cannot satisfy this contract.
		String start = "# REGION_PROBE_BEGIN";
		String end = "# REGION_PROBE_END";
		int begin = script.indexOf(start);
		int finish = script.indexOf(end, begin);
		assertTrue(begin >= 0 && finish > begin, "missing executable region-probe contract");
		Path kernel = temporary.resolve("kernel with spaces");
		Path rundir = temporary.resolve("instance with spaces");
		Path capture = temporary.resolve("probe-argv.bin");
		ProcessBuilder recorder = new ProcessBuilder("bash", "-c",
				"python3() { printf '%s\\0' \"$@\" > \"$CAPTURE\"; }\n" + script.substring(begin, finish));
		recorder.environment().put("KERNEL", kernel.toString());
		recorder.environment().put("RUNDIR", rundir.toString());
		recorder.environment().put("PROBE_LOG", temporary.resolve("probe.log").toString());
		recorder.environment().put("CAPTURE", capture.toString());
		assertSuccessful(recorder);
		assertEquals(List.of(kernel.resolve("run/compat/region-probe.py").toString(),
				rundir.resolve("world/dimensions/minecraft/overworld/region").toString(), neo),
				List.of(Files.readString(capture).split("\\x00")),
				"the real probe argv must read the current overworld layout and the NeoForge canary's marker");

		assertTrue(script.contains("'^unreadable: 0"));
		assertTrue(script.contains("\"$CHUNKS\" -ge 20"));
		// The expected-red escape stays gone, and the -Dforbric.forgeWorldgen=off boot stays: since Stage 3 it shows that
		// switching the MinecraftForge worldgen bridge off takes nothing from NeoForge.
		assertFalse(script.contains("EXPECTED: RED"), "the expected-red header must go when the gap closes");
		assertFalse(script.contains("EXPECTED-RED"));
		assertTrue(script.contains("M25_NO_DATA"));
		assertTrue(script.contains("-Dforbric.forgeWorldgen=off"), "the negative-control boot must stay");
		assertTrue(script.indexOf("boot \"$CONTROL_LOG\" \"-Dforbric.forgeWorldgen=off\"") > script.indexOf("boot \"$LOG\""));
		assertSuccessful(new ProcessBuilder("bash", "-n", gate.toString()));
	}

	private static void assertSuccessful(ProcessBuilder builder) throws Exception {
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(10, TimeUnit.SECONDS), "gate contract subprocess timed out");
		assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}
}
