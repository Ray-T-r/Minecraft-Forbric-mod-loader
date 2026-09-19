package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssertCompatTest {
	@TempDir Path temporary;

	// Independent log fixture: removing a production assertion cannot create the evidence it expects.
	private static final String GREEN = """
			[Forbric/ClientSmoke] armed on Minecraft.tick
			[Forbric/ClientSmoke] joined world via quick-play
			[Forbric/ClientSmoke] client-ready after 100 ticks
			[Forbric/ClientSmoke] window title: Minecraft
			[Forbric/ClientSmoke] clean disconnect observed
			Player joined the game
			Loaded 12 advancements
			posted FML construct to 2 NeoForge mods
			posted FML construct to 2 traditional-Forge mods
			posted FML client setup to 2 NeoForge mods
			[Render thread/INFO]: [Forbric/Lifecycle] posted FML common setup to 2 NeoForge mods
			ran NeoForge's registration events
			posted FML load complete to 2 NeoForge mods
			[Forbric/Order] construction order is dependency order
			[Forbric/Order] 2 Fabric mod(s) initialise in dependency order
			[Forbric/DataPacks] served 2 datapacks
			[Forbric/Aliases] gave registries alias-resolving lookup
			fired RegisterEvent in NeoForge's registration order
			all 1 CLIENT_MOD_BUS bridge(s) installed
			[Forbric/EventMux] all 2 GAME_BUS bridge(s) installed
			[Forbric/EventMux] all 2 CLIENT_GAME_BUS bridge(s) installed
			""";

	@Test void acceptsEveryRequiredObservation() throws Exception {
		var result = execute(GREEN);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("result: ALL-GREEN"), result.output());
		assertEquals(40, result.output().lines().filter(line -> line.startsWith("PASS  ")).count());
	}

	@Test void aForbiddenObservationFailsTheProcess() throws Exception {
		var result = execute(GREEN + "Preparing crash report\n");
		assertEquals(1, result.exitCode(), result.output());
		assertTrue(result.output().contains("FAIL  no crash report"), result.output());
	}

	@Test void absentRequiredEvidenceFailsTheProcess() throws Exception {
		var result = execute(GREEN.replace("[Forbric/ClientSmoke] clean disconnect observed\n", ""));
		assertEquals(1, result.exitCode(), result.output());
		assertTrue(result.output().contains("FAIL  left the world cleanly"), result.output());
	}

	@Test void emptyAndMissingLogsFailBeforeAbsenceChecks() throws Exception {
		var empty = execute("");
		assertEquals(1, empty.exitCode(), empty.output());
		assertTrue(empty.output().contains("log is missing or empty"));
		var missing = CompatProbeProcess.run(temporary, "bash", "assert.sh", temporary.resolve("absent.log").toString());
		assertEquals(1, missing.exitCode(), missing.output());
	}

	@Test void packAssertionsShareTheFailureCounter() throws Exception {
		Path log = Files.writeString(temporary.resolve("green.log"), GREEN);
		Path extra = Files.writeString(temporary.resolve("extra.sh"), "ck 'pack-specific proof' 'PACK_READY'\n");
		var red = CompatProbeProcess.run(temporary, Map.of("ASSERT_EXTRA", extra.toString()),
				"bash", "assert.sh", log.toString());
		assertEquals(1, red.exitCode(), red.output());
		assertTrue(red.output().contains("FAIL  pack-specific proof"), red.output());
		Files.writeString(log, GREEN + "PACK_READY\n");
		var green = CompatProbeProcess.run(temporary, Map.of("ASSERT_EXTRA", extra.toString()),
				"bash", "assert.sh", log.toString());
		assertEquals(0, green.exitCode(), green.output());
	}

	private CompatProbeProcess.Result execute(String text) throws Exception {
		Path log = Files.writeString(temporary.resolve("client.log"), text);
		return CompatProbeProcess.run(temporary, "bash", "assert.sh", log.toString());
	}
}
