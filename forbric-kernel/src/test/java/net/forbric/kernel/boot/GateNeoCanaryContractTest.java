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

import net.forbric.kernel.TestFixtures;

/**
 * Runs the NeoForge canary's blocks of gate-m21 and gate-m26 against synthetic logs, without launching a game.
 *
 * <p>These blocks carry the shared contracts that the MinecraftForge canary used to be the only fixture for, so they
 * are what is left the day that canary is deleted. Each test feeds the block the lines the live canary prints (taken
 * from a green run), then takes one away or gives it the WRONG shape, and the block has to say which check broke. A
 * block that stays green on a log missing its own subject would let the contract go with MinecraftForge unnoticed.
 */
class GateNeoCanaryContractTest {
	private static final Path M21 = Path.of("run/gate-m21-forgesetup.sh");
	private static final Path M26 = Path.of("run/gate-m26-forgeclient.sh");
	@TempDir Path temporary;

	private static final String SCAN_HOLDER = "net.neoforged.fml.loading.modscan.ModAnnotation$EnumHolder";
	private static final String SCAN_OWN =
			"[ForbricNeoLive/SCAN] ModList found its own @NeoLiveScanned on FIELD PROBE of forbric.live.neoforge.ForbricNeoLiveMod";
	/**
	 * The default boot's lines (Stage 2: one file per mod). The canary's jar holds two {@code @Mod} classes, so the index
	 * lists it twice and ModList finds the canary's own annotation in both entries.
	 */
	private static final List<String> M21_GREEN = List.of(
			SCAN_OWN,
			SCAN_OWN,
			"[ForbricNeoLive/SCAN] ModList enum member kind is " + SCAN_HOLDER + " with value SECOND",
			"[ForbricNeoLive/SCAN] ModList string member note is probe",
			"[ForbricNeoLive/SCAN] ModList.getAllScanData(): 2 file(s), 10 annotation(s)",
			"[ForbricNeoLive/SCAN] ModList.getAllScanData() lists forbricneolive's jar 2 time(s)",
			"[ForbricNeoLive/SCAN] LoadingModList found its own @NeoLiveScanned on FIELD PROBE of forbric.live.neoforge.ForbricNeoLiveMod",
			"[ForbricNeoLive/SCAN] LoadingModList enum member kind is " + SCAN_HOLDER + " with value SECOND",
			"[ForbricNeoLive/SCAN] LoadingModList string member note is probe",
			"[ForbricNeoLive/RELOAD] AddServerReloadListenersEvent DELIVERED to a NeoForge mod",
			"[ForbricNeoLive/RELOAD] context=live net.neoforged.neoforge.common.conditions.ConditionContext",
			"[ForbricNeoLive/RELOAD] reload listener ran over 1 file(s)",
			"[ForbricNeoLive/SELF] own container during ctor: present=true fmlContainer=true eventBus=true activeNamespace=forbricneolive",
			"[ForbricNeoLive/PLUGIN] prepareConfigs saw dist=DEDICATED_SERVER",
			"[ForbricNeoLive/CFG] LOADING forbricneolive-server.toml: greeting=default loaded=true path=/srv/config/forbricneolive-server.toml");

	/** The second boot's, with {@code -Dforbric.modFilePerJar=on}: the jar is one file, listed once. */
	private static final List<String> M21_PERJAR_GREEN = List.of(
			SCAN_OWN,
			"[ForbricNeoLive/SCAN] ModList.getAllScanData(): 1 file(s), 5 annotation(s)",
			"[ForbricNeoLive/SCAN] ModList.getAllScanData() lists forbricneolive's jar 1 time(s)");

	@Test
	void m21NeoBlockIsGreenOnTheLiveCanaryLinesAndRedWithoutEachOne() throws Exception {
		String green = String.join("\n", M21_GREEN) + "\n";
		String perJar = String.join("\n", M21_PERJAR_GREEN) + "\n";
		assertGreen(m21(green, perJar));
		for (int i = 0; i < M21_GREEN.size(); i++) {
			List<String> without = new java.util.ArrayList<>(M21_GREEN);
			String line = without.remove(i);
			Result result = m21(String.join("\n", without) + "\n", perJar);
			assertEquals(1, result.exit(), "still green without: " + line + "\n" + result.output());
		}
		for (int i = 0; i < M21_PERJAR_GREEN.size(); i++) {
			List<String> without = new java.util.ArrayList<>(M21_PERJAR_GREEN);
			String line = without.remove(i);
			if (line.contains("getAllScanData(): ")) continue; // the count line is asked of the default boot only
			Result result = m21(green, String.join("\n", without) + "\n");
			assertEquals(1, result.exit(), "still green without, per jar: " + line + "\n" + result.output());
		}
	}

	/** The shape is the contract: MinecraftForge's EnumData, or a bare String that also prints SECOND, must not pass. */
	@Test
	void m21NeoBlockRefusesTheOtherFamilysEnumShapeAndASecondServerConfigLoad() throws Exception {
		String green = String.join("\n", M21_GREEN) + "\n";
		String perJar = String.join("\n", M21_PERJAR_GREEN) + "\n";
		Result forgeShape = m21(green.replace(SCAN_HOLDER, "net.minecraftforge.forgespi.language.ModFileScanData$EnumData"),
				perJar);
		assertRed(forgeShape, "an enum member has NeoForge's own EnumHolder shape");
		Result twice = m21(green + M21_GREEN.get(M21_GREEN.size() - 1) + "\n", perJar);
		assertRed(twice, "its SERVER config loads once");
		String unreadable = "[ForbricNeoLive/SCAN] could not read the LoadingModList scan index: java.lang.IllegalStateException\n";
		assertRed(m21(green + unreadable, perJar), "NeoForge: neither reader failed");
		assertRed(m21(green, perJar + unreadable), "NeoForge, per jar: neither reader failed");
	}

	/**
	 * The two shapes told apart, each in the boot that must not have it. The per-jar boot with one file per MOD (what
	 * {@code M21_PERJAR_JVM=-Dforbric.modFilePerJar=off} gives) lists the jar twice and finds the canary's annotation
	 * in both entries; the count line alone says only "2 file(s)", which the old check's {@code [1-9][0-9]*} took as
	 * green. The default boot with the jar's one file (the switch on there: the default flipped before Stage 4) lists
	 * it once.
	 */
	@Test
	void m21NeoBlockTellsOneJarFromTheSameJarListedOncePerMod() throws Exception {
		String green = String.join("\n", M21_GREEN) + "\n";
		String perJar = String.join("\n", M21_PERJAR_GREEN) + "\n";
		Result perModInThePerJarBoot = m21(green, green);
		assertRed(perModInThePerJarBoot, "per jar: the index lists the canary's jar once, as one IModFile");
		assertTrue(perModInThePerJarBoot.output().contains(
				"FAIL NeoForge (ModList), per jar: its own annotation is found in one entry"), perModInThePerJarBoot.output());

		List<String> flipped = new java.util.ArrayList<>(M21_GREEN);
		flipped.remove(SCAN_OWN);
		flipped.replaceAll(line -> line.replace("2 time(s)", "1 time(s)").replace("2 file(s), 10", "1 file(s), 5"));
		Result perJarInTheDefaultBoot = m21(String.join("\n", flipped) + "\n", perJar);
		assertRed(perJarInTheDefaultBoot, "default: the index still lists the canary's jar once per mod");
		assertTrue(perJarInTheDefaultBoot.output().contains(
				"FAIL NeoForge (ModList), default: its own annotation is found in both of the jar's entries"),
				perJarInTheDefaultBoot.output());
	}

	private static final List<String> M26_GREEN = m26Green();

	private static List<String> m26Green() {
		List<String> lines = new java.util.ArrayList<>(List.of(
				"[ForbricNeoLive/CLIENT] subscribed to twelve NeoForge client registration events",
				"[ForbricNeoLive/CLIENT] registration observations completed at world tick 100",
				"[ForbricNeoLive/CLIENT] key in Options.keyMappings: true",
				"[ForbricNeoLive/CLIENT] key saved binding: key.keyboard.f9",
				"[ForbricNeoLive/CLIENT] layer forbricneolive:probe baked: true",
				"[ForbricNeoLive/CLIENT] granite probe tint present: true",
				"[ForbricNeoLive/CLIENT] reload posts=1",
				"[ForbricNeoLive/CLIENT] reload listener applies=1",
				"[ForbricNeoLive/CLIENT] tooltip factory consumed: true",
				"[ForbricNeoLive/CLIENT] gui layer DREW 20 frames — NeoForge's HUD layers reach the screen",
				"[ForbricNeoLive/BLOCKID] forbricneolive:probe_block id=32366 roundTrip=Block{forbricneolive:probe_block}",
				"[ForbricNeoLive/PLUGIN] prepareConfigs saw dist=CLIENT"));
		for (String event : List.of("RegisterKeyMappingsEvent", "EntityRenderersEvent.RegisterRenderers",
				"BuildCreativeModeTabContentsEvent: minecraft:building_blocks", "EntityRenderersEvent.RegisterLayerDefinitions",
				"RegisterParticleProvidersEvent", "RegisterColorHandlersEvent.BlockTintSources", "AddClientReloadListenersEvent",
				"RegisterClientTooltipComponentFactoriesEvent", "ModelEvent.RegisterLoaders", "RegisterPresetEditorsEvent",
				"RegisterPictureInPictureRenderersEvent", "RegisterGuiLayersEvent")) {
			String[] parts = event.split(": ", 2);
			lines.add("[ForbricNeoLive/CLIENT] " + parts[0] + " RECEIVED" + (parts.length > 1 ? ": " + parts[1] : ""));
		}
		return List.copyOf(lines);
	}

	@Test
	void m26NeoBlockIsGreenOnTheLiveCanaryLinesAndRedWithoutEachOne() throws Exception {
		assertGreen(m26(String.join("\n", M26_GREEN) + "\n"));
		for (String line : M26_GREEN) {
			List<String> without = new java.util.ArrayList<>(M26_GREEN);
			without.remove(line);
			Result result = m26(String.join("\n", without) + "\n");
			assertEquals(1, result.exit(), "still green without: " + line + "\n" + result.output());
		}
		// AIR is what a missing blockstate→id entry answers, and a second reload registration is a double post.
		String green = String.join("\n", M26_GREEN) + "\n";
		assertRed(m26(green.replace("id=32366", "id=0")), "the canary's own block has a real id");
		assertRed(m26(green.replace("reload posts=1", "reload posts=2")), "reload registration happened exactly once");
		assertRed(m26(green + "[ForbricNeoLive/CLIENT] FAILED initialization: java.lang.NoClassDefFoundError\n"),
				"client canary initialized successfully");
	}

	/** The Fabric canary's late block in gate-m26: the refill's proof that outlives the MinecraftForge canary. */
	private static final List<String> M26_FABRIC_GREEN = List.of(
			"[ForbricFabricLive/LATEBLOCK] registered forbricfabriclive:late_block from onInitializeClient at raw id 1183",
			"[12:00:00] [Render thread/INFO]: [Forbric/Lifecycle] rebuilt NeoForge blockstate→id map (32368 states, was 32367) — 1 more state(s) were registered after the first pass, and Block.getId answered 0 (AIR) for every one of them",
			"[ForbricFabricLive/LATEBLOCK] forbricfabriclive:late_block id=32367 roundTrip=Block{forbricfabriclive:late_block}");

	@Test
	void m26FabricBlockIsGreenOnTheLiveLinesAndRedOnAirOrAMissingRefill() throws Exception {
		String green = String.join("\n", M26_FABRIC_GREEN) + "\n";
		assertGreen(m26Fabric(green));
		for (String line : M26_FABRIC_GREEN) {
			List<String> without = new java.util.ArrayList<>(M26_FABRIC_GREEN);
			without.remove(line);
			assertEquals(1, m26Fabric(String.join("\n", without) + "\n").exit(), "still green without: " + line);
		}
		// -Dforbric.blockStateIdRefill=off: no rebuild of the late wave, and the late block reads as AIR.
		assertRed(m26Fabric(green.replace("id=32367 roundTrip=Block{forbricfabriclive:late_block}",
				"id=0 roundTrip=Block{minecraft:air}")), "the late block has a real id, read back in the world");
		assertRed(m26Fabric(green + "[ForbricFabricLive/LATEBLOCK] read-back FAILED: no world within six minutes\n"),
				"the late-block probe never failed");
	}

	// --- gate-m26's options seed and session block: shared contracts that outlive every canary --------------------
	// Restored from ForgeRegistrationA2ContractTest when Stage 4 deleted the MinecraftForge canary: these three never
	// read that canary, and nothing else runs M26_OPTIONS or M26_SESSION_ASSERTIONS. C5 re-pins them with the gate.

	@Test
	void clientGateSeedsTheNeoForgeF9AndBothScriptsParse() throws Exception {
		// The options section reads the world version out of the staged base, as the gate does from MERGED.
		Path base = TestFixtures.stagedBase();
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "staged base", base);
		String script = Files.readString(M26);
		Path result = temporary.resolve("options-command.log");
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", section(script, "M26_OPTIONS"));
		builder.environment().put("RUNDIR", temporary.toString());
		builder.environment().put("MERGED", base.toAbsolutePath().toString());
		builder.environment().put("RUN_OLD", TestFixtures.stagedRoot().toAbsolutePath().toString());
		Result seededRun = execute(builder, result);
		assertEquals(0, seededRun.exit(), seededRun.output());
		String seeded = Files.readString(temporary.resolve("options.txt"));
		assertTrue(seeded.matches("version:[1-9][0-9]*\\nonboardAccessibility:false\\nkey_key\\.forbricneolive\\.probe:key\\.keyboard\\.f9\\n"), seeded);
		for (Path gate : List.of(M21, M26)) {
			Result parsed = execute(new ProcessBuilder("bash", "-n", gate.toString()), result);
			assertEquals(0, parsed.exit(), parsed.output());
		}
		assertTrue(script.contains("# WORLD_CONFIRM_BEGIN"));
		assertTrue(script.contains("# CLIENT_CANARY_STAGE_BEGIN"));
		assertTrue(script.contains("SRC_MODS=\"$RUNDIR/empty-mods\""));
	}

	private static final List<String> SESSION_GREEN = List.of(
			"[ClientSmoke] joined world via quick-play", "[ClientSmoke] client-ready after 60 ticks",
			"[ClientSmoke] clean disconnect observed",
			"[Forbric/ClientResources] client resource manager preloaded with 3 selected pack(s)",
			"[Forbric/Bridges] all 3 CLIENT_INIT bridge(s) installed", "[Forbric/Bridges] all 2 REGISTRATION bridge(s) installed",
			"[Forbric/HudBridge] MinecraftForge's overlay stack is on NeoForge's layer manager — silenced 23 vanilla leaf/leaves",
			"[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a fallback to the orphaned vanilla "
					+ "map, and gave that map its only writer",
			"[ForbricNeoLive/FLUID] water at 3 64 -2: Block{minecraft:water}",
			"[Forbric/Fluids] MinecraftForge client extensions consulted for 4 fluid(s) so far");

	/**
	 * The session block keeps every kernel line that printed with no MinecraftForge mod aboard, and each row is
	 * load-bearing. Some rows name kernel lines earlier Stage 4 lanes deleted; the block is C5's to re-pin, and until then
	 * this pins the block as the gate has it.
	 */
	@Test
	void theSessionBlockAssertsTheRewritesThatRunWithNoMinecraftForgeMod() throws Exception {
		String green = String.join("\n", SESSION_GREEN) + "\n";
		assertEquals(0, session(green, 0, "HUD_TREE=absent").exit());
		for (String line : SESSION_GREEN) {
			assertEquals(1, session(green.replace(line + "\n", ""), 0, "HUD_TREE=absent").exit(),
					"a missing row is red: " + line);
		}
		assertEquals(1, session("", 0, "HUD_TREE=absent").exit());
		assertEquals(1, session(green, 1, "HUD_TREE=absent").exit(), "a preceding failure stays red");
	}

	/**
	 * The HUD drop row follows the base the gate read before its assertions: where the base's Hud kept MinecraftForge's
	 * overlay tree the kernel's drop line is required, and where it did not the same line is red.
	 */
	@Test
	void theHudDropRowFollowsTheBaseTheGateRead() throws Exception {
		String green = String.join("\n", SESSION_GREEN) + "\n";
		String dropped = "[Forbric/HudBridge] net.minecraft.client.gui.Hud kept MinecraftForge's own overlay tree (built in 1 "
				+ "place(s), drawn instead of NeoForge's layer manager in 1) — dropped: the kernel builds that tree once "
				+ "every mod is loaded and puts it on NeoForge's manager, the one HUD pipeline\n";
		assertEquals(0, session(green + dropped, 0, "HUD_TREE=kept").exit());
		assertEquals(1, session(green, 0, "HUD_TREE=kept").exit(),
				"a base that kept the tree and no drop: both pipelines still run");
		assertEquals(0, session(green, 0, "HUD_TREE=absent").exit());
		assertEquals(1, session(green + dropped, 0, "HUD_TREE=absent").exit(),
				"a drop on a base that never carried the tree is a drop of something else");
	}

	/** gate-m26's session block over {@code log}; {@code preamble} is what the gate computes from the base first. */
	private Result session(String log, int precedingFailure, String preamble) throws Exception {
		Path input = temporary.resolve("session.log");
		Files.writeString(input, log);
		String program = ". \"$1\"\nLOG=\"$2\"\nFAIL=" + precedingFailure + "\n" + preamble + "\n"
				+ section(Files.readString(M26), "M26_SESSION_ASSERTIONS") + "\nexit \"$FAIL\"\n";
		return execute(new ProcessBuilder("bash", "-c", program, "session-contract",
				Path.of("run/lib.sh").toAbsolutePath().toString(), input.toString()), temporary.resolve("session-result.log"));
	}

	private static String section(String script, String name) {
		int start = script.indexOf("# " + name + "_BEGIN");
		assertTrue(start >= 0, "missing production fragment: " + name);
		start = script.indexOf('\n', start) + 1;
		int end = script.indexOf("# " + name + "_END", start);
		assertTrue(end > start, "missing production fragment end: " + name);
		return script.substring(start, end);
	}

	private static Result execute(ProcessBuilder builder, Path output) throws Exception {
		Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		boolean ended = process.waitFor(15, TimeUnit.SECONDS);
		if (!ended) process.destroyForcibly();
		assertTrue(ended, "offline gate fragment timed out");
		return new Result(process.exitValue(), Files.readString(output));
	}

	private Result m21(String log, String perJarLog) throws Exception {
		return block(M21, "M21_NEO_CONTRACTS", log, perJarLog);
	}
	private Result m26Fabric(String log) throws Exception { return block(M26, "M26_FABRIC_ASSERTIONS", log); }
	private Result m26(String log) throws Exception { return block(M26, "M26_NEO_ASSERTIONS", log); }

	private Result block(Path gate, String name, String log) throws Exception {
		return block(gate, name, log, null);
	}

	/** {@code perJarLog}, when given, is gate-m21's second boot's log, which its block reads as {@code LOG_PERJAR}. */
	private Result block(Path gate, String name, String log, String perJarLog) throws Exception {
		Path file = temporary.resolve("gate.log");
		Files.writeString(file, log);
		Path perJarFile = temporary.resolve("gate-perjar.log");
		Files.writeString(perJarFile, perJarLog == null ? "" : perJarLog);
		String script = Files.readString(gate);
		int begin = script.indexOf("# " + name + "_BEGIN");
		int end = script.indexOf("# " + name + "_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable gate contract " + name);
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", ". run/lib.sh\n" + script.substring(begin, end) + "\nexit \"$FAIL\"");
		builder.environment().put("LOG", file.toString());
		builder.environment().put("LOG_PERJAR", perJarFile.toString());
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(15, TimeUnit.SECONDS), "offline gate contract timed out");
		return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	private static void assertGreen(Result result) { assertEquals(0, result.exit(), result.output()); }
	private static void assertRed(Result result, String diagnostic) {
		assertEquals(1, result.exit(), result.output());
		assertTrue(result.output().contains(diagnostic), result.output());
	}
	private record Result(int exit, String output) {}
}
