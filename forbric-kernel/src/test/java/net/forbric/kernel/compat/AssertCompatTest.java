package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
			posted FML client setup to 2 NeoForge mods
			[Render thread/INFO]: [Forbric/Lifecycle] posted FML common setup to 2 NeoForge mods
			ran NeoForge's registration events (7 step(s), each on its own) — capabilities and data maps are registered, 11 data map type(s)
			posted FML load complete to 2 NeoForge mods
			[Forbric/Order] construction order is dependency order
			[Forbric/Order] 2 Fabric mod(s) initialise in Fabric Loader's order, by mod id, as native Fabric orders them
			[Render thread/INFO]: [Forbric/Fabric] invoked 2 Fabric main entrypoint(s) in the Minecraft.<init> window
			[Forbric/DataPacks] served 2 datapacks
			[Forbric/Lifecycle] NeoForge's GameData.postRegisterEvents posts RegisterEvent itself, every mixin on it applied: its dispatch goes to each mod in order with a failing mod contained, the Fabric main entrypoints run where its loop ends, and 4 tail step(s) each report their own failure
			""";

	private static final String SEAM_APPLIED = "postRegisterEvents carries the seam";
	private static final String SEAM_SHAPE = "postRegisterEvents is the seam's shape";

	@Test void acceptsEveryRequiredObservation() throws Exception {
		var result = execute(GREEN);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("result: ALL-GREEN"), result.output());
		assertEquals(37, result.output().lines().filter(line -> line.startsWith("PASS  ")).count());
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

	/**
	 * The seam pair that replaced the check on "fired RegisterEvent in NeoForge's registration order", a line NeoForge's
	 * method prints with or without the kernel's seam (C5 wave 2b review B-1): red without the seam's applied line, red on
	 * the seam standing down, and the method's own line alone no longer satisfies anything.
	 */
	@Test void theRegisterEventSeamPairIsRedWithoutTheSeam() throws Exception {
		String applied = GREEN.lines().filter(line -> line.contains("posts RegisterEvent itself")).findFirst().orElseThrow();
		var noSeam = execute(GREEN.replace(applied + "\n", "fired RegisterEvent in NeoForge's registration order\n"));
		assertEquals(1, noSeam.exitCode(), noSeam.output());
		assertTrue(noSeam.output().contains("FAIL  " + SEAM_APPLIED), noSeam.output());
		var stoodDown = execute(GREEN.replace(applied + "\n", "[Forbric/Lifecycle] NeoForge's GameData.postRegisterEvents is not "
				+ "the shape the kernel knows (one ModLoader.postEventWrapContainerInModOrder call, then the getSuppressed() test "
				+ "before the tail) — it runs exactly as NeoForge ships it\n"));
		assertEquals(1, stoodDown.exitCode(), stoodDown.output());
		assertTrue(stoodDown.output().contains("FAIL  " + SEAM_APPLIED), stoodDown.output());
		assertTrue(stoodDown.output().contains("FAIL  " + SEAM_SHAPE), stoodDown.output());
	}

	/** compat/assert.sh asks the same two questions of a log as gate-m1 and gate-m9 do, in the same words. */
	@Test void theSeamPatternsAreTheGatesOwn() throws Exception {
		String compat = Files.readString(Path.of("run/compat/assert.sh"));
		for (String gate : List.of("run/gate-m1.sh", "run/gate-m9-client.sh")) {
			String text = Files.readString(Path.of(gate));
			assertEquals(pattern(text, "check\\s+\"[^\"]*carries the kernel's seam\""), pattern(compat, "ck\\s+\"" + SEAM_APPLIED + "\""), gate);
			assertEquals(pattern(text, "check_absent\\s+\"[^\"]*is the shape the seam knows\""),
					pattern(compat, "abs\\s+\"" + Pattern.quote(SEAM_SHAPE) + "\""), gate);
		}
	}

	/** The pattern argument that follows {@code call} on one line (a gate may continue the line with a backslash). */
	private static String pattern(String script, String call) {
		Matcher m = Pattern.compile(call + "\\s*\\\\?\\s*\"([^\"]+)\"").matcher(script);
		assertTrue(m.find(), "no " + call);
		return m.group(1);
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
