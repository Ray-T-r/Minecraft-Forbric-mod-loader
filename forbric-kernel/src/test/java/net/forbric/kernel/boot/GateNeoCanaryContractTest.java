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
	 * The boot's lines. The canary's jar holds two {@code @Mod} classes and is one file, as FML builds it (the Stage 4
	 * default), so the index lists it once and ModList finds the canary's own annotation in that one entry.
	 */
	private static final List<String> M21_GREEN = List.of(
			SCAN_OWN,
			"[ForbricNeoLive/SCAN] ModList enum member kind is " + SCAN_HOLDER + " with value SECOND",
			"[ForbricNeoLive/SCAN] ModList string member note is probe",
			"[ForbricNeoLive/SCAN] ModList.getAllScanData(): 1 file(s), 5 annotation(s)",
			"[ForbricNeoLive/SCAN] ModList.getAllScanData() lists forbricneolive's jar 1 time(s)",
			"[ForbricNeoLive/SCAN] LoadingModList found its own @NeoLiveScanned on FIELD PROBE of forbric.live.neoforge.ForbricNeoLiveMod",
			"[ForbricNeoLive/SCAN] LoadingModList enum member kind is " + SCAN_HOLDER + " with value SECOND",
			"[ForbricNeoLive/SCAN] LoadingModList string member note is probe",
			"[ForbricNeoLive/RELOAD] AddServerReloadListenersEvent DELIVERED to a NeoForge mod",
			"[ForbricNeoLive/RELOAD] context=live net.neoforged.neoforge.common.conditions.ConditionContext",
			"[ForbricNeoLive/RELOAD] reload listener ran over 1 file(s)",
			"[ForbricNeoLive/SELF] own container during ctor: present=true fmlContainer=true eventBus=true activeNamespace=forbricneolive",
			"[ForbricNeoLive/PLUGIN] prepareConfigs saw dist=DEDICATED_SERVER",
			"[ForbricNeoLive/CFG] LOADING forbricneolive-server.toml: greeting=default loaded=true path=/srv/config/forbricneolive-server.toml");

	@Test
	void m21NeoBlockIsGreenOnTheLiveCanaryLinesAndRedWithoutEachOne() throws Exception {
		String green = String.join("\n", M21_GREEN) + "\n";
		assertGreen(m21(green));
		for (int i = 0; i < M21_GREEN.size(); i++) {
			List<String> without = new java.util.ArrayList<>(M21_GREEN);
			String line = without.remove(i);
			Result result = m21(String.join("\n", without) + "\n");
			assertEquals(1, result.exit(), "still green without: " + line + "\n" + result.output());
		}
	}

	/** The shape is the contract: MinecraftForge's EnumData, or a bare String that also prints SECOND, must not pass. */
	@Test
	void m21NeoBlockRefusesTheOtherFamilysEnumShapeAndASecondServerConfigLoad() throws Exception {
		String green = String.join("\n", M21_GREEN) + "\n";
		Result forgeShape = m21(green.replace(SCAN_HOLDER, "net.minecraftforge.forgespi.language.ModFileScanData$EnumData"));
		assertRed(forgeShape, "an enum member has NeoForge's own EnumHolder shape");
		Result twice = m21(green + M21_GREEN.get(M21_GREEN.size() - 1) + "\n");
		assertRed(twice, "its SERVER config loads once");
		String unreadable = "[ForbricNeoLive/SCAN] could not read the LoadingModList scan index: java.lang.IllegalStateException\n";
		assertRed(m21(green + unreadable), "NeoForge: neither reader failed");
	}

	/**
	 * The jar's one file told apart from the same jar listed once per mod, which is what
	 * {@code M21_EXTRA_JVM=-Dforbric.modFilePerJar=off} (the shape before the Stage 4 flip) prints: the jar twice, and
	 * the canary's annotation found in both entries. The count line alone says only "2 file(s)", which the old check's
	 * {@code [1-9][0-9]*} took as green; exactly the two per-jar checks must say it.
	 */
	@Test
	void m21NeoBlockTellsOneJarFromTheSameJarListedOncePerMod() throws Exception {
		List<String> perMod = new java.util.ArrayList<>(M21_GREEN);
		perMod.add(0, SCAN_OWN);
		perMod.replaceAll(line -> line.replace("1 time(s)", "2 time(s)").replace("1 file(s), 5", "2 file(s), 10"));
		Result perModBoot = m21(String.join("\n", perMod) + "\n");
		assertRed(perModBoot, "FAIL NeoForge: the index lists the canary's jar once, as one IModFile");
		assertTrue(perModBoot.output().contains(
				"FAIL NeoForge (ModList): its own annotation is found in one entry"), perModBoot.output());
		assertEquals(2, perModBoot.output().lines().filter(line -> line.startsWith("[kernel] FAIL")).count(),
				perModBoot.output());
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

	/**
	 * The Fabric canary's late block in gate-m26. On the genuine base NeoForge's own block callback maps the late wave,
	 * so the kernel's refill line is no longer among the live lines (gate-m26 header, Stage 4 C5): the id is asserted.
	 */
	private static final List<String> M26_FABRIC_GREEN = List.of(
			"[ForbricFabricLive/LATEBLOCK] registered forbricfabriclive:late_block from onInitializeClient at raw id 1198",
			"[ForbricFabricLive/LATEBLOCK] forbricfabriclive:late_block id=64735 roundTrip=Block{forbricfabriclive:late_block}");

	@Test
	void m26FabricBlockIsGreenOnTheLiveLinesAndRedOnAir() throws Exception {
		String green = String.join("\n", M26_FABRIC_GREEN) + "\n";
		assertGreen(m26Fabric(green));
		for (String line : M26_FABRIC_GREEN) {
			List<String> without = new java.util.ArrayList<>(M26_FABRIC_GREEN);
			without.remove(line);
			assertEquals(1, m26Fabric(String.join("\n", without) + "\n").exit(), "still green without: " + line);
		}
		// A late block the blockstate→id map never received reads as AIR.
		assertRed(m26Fabric(green.replace("id=64735 roundTrip=Block{forbricfabriclive:late_block}",
				"id=0 roundTrip=Block{minecraft:air}")), "the late block has a real id, read back in the world");
		assertRed(m26Fabric(green + "[ForbricFabricLive/LATEBLOCK] read-back FAILED: no world within six minutes\n"),
				"the late-block probe never failed");
	}

	/**
	 * Phase 1c of gate-m26 (C5 wave 2b, lane K1's open item): the client's id for the NeoForge canary's block must be the
	 * one a dedicated server with the same canaries prints. A state numbered twice still round-trips, so the id checks
	 * above stay green on a client whose numbering drifted from a server's; only this comparison sees it.
	 */
	@Test
	void m26ServerIdsAreGreenOnlyWhenBothEndsPrintOneAndTheSameId() throws Exception {
		String line = "[ForbricNeoLive/BLOCKID] forbricneolive:probe_block id=%s roundTrip=Block{forbricneolive:probe_block}\n";
		String same = "the client numbers forbricneolive:probe_block as the dedicated server does";
		assertGreen(m26ServerIds(line.formatted("32366"), line.formatted("32366")));
		// A client's stdout log repeats every latest.log line, and the gate appends both: one id printed twice is one id.
		assertGreen(m26ServerIds(line.formatted("32366") + line.formatted("32366"), line.formatted("32366")));
		assertRed(m26ServerIds(line.formatted("64734"), line.formatted("32366")), same);
		assertRed(m26ServerIds(line.formatted("32366"), ""), same);
		assertRed(m26ServerIds("", line.formatted("32366")), same);
		assertRed(m26ServerIds(line.formatted("32366") + line.formatted("64734"), line.formatted("32366")), same);
		// AIR on both ends agrees and is still wrong.
		assertRed(m26ServerIds(line.formatted("0"), line.formatted("0")), same);
		// A numbering defect both ends share gives the same wrong id on each, so the comparison stays green; the kernel's
		// own invariant line after a freeze is what says so, on either end (review A-3 of C5 wave 2b).
		String caught = "[Forbric/Lifecycle] NeoForge's blockstate->id map is not one id per block state after the %s freeze\n";
		String sharedDefect = line.formatted("64733") + caught.formatted("client entrypoint window");
		assertRed(m26ServerIds(sharedDefect, line.formatted("64733") + caught.formatted("registration window")),
				"FAIL the client's blockstate->id map is one id per block state (present x1)");
		assertRed(m26ServerIds(line.formatted("32366") + caught.formatted("client entrypoint window"), line.formatted("32366")),
				"FAIL the client's blockstate->id map is one id per block state");
		assertRed(m26ServerIds(line.formatted("32366"), line.formatted("32366") + caught.formatted("registration window")),
				"FAIL the dedicated server's blockstate->id map is one id per block state");
		assertTrue(m26ServerIds(line.formatted("32366"), line.formatted("32366")).output()
				.contains("PASS the dedicated server's blockstate->id map is one id per block state (absent)"));
	}

	// --- gate-m26's options seed and session block: shared contracts that outlive every canary --------------------
	// Restored from ForgeRegistrationA2ContractTest when Stage 4 deleted the MinecraftForge canary: these three never
	// read that canary, and nothing else runs M26_OPTIONS or M26_SESSION_ASSERTIONS. C5 re-pins them with the gate.

	@Test
	void clientGateSeedsTheNeoForgeF9AndBothScriptsParse() throws Exception {
		// The options section reads the world version out of the staged base, as the gate does from BASE.
		Path base = TestFixtures.stagedBase();
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "staged base", base);
		String script = Files.readString(M26);
		Path result = temporary.resolve("options-command.log");
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", section(script, "M26_OPTIONS"));
		builder.environment().put("RUNDIR", temporary.toString());
		builder.environment().put("BASE", base.toAbsolutePath().toString());
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
		// Stage 4 C5: the merged base is gone, and with it the HUD javap that read it; the rundirs refuse a shared fixture.
		assertTrue(script.indexOf("MERGED", script.indexOf("# GATE-PARALLEL")) < 0, "gate-m26 reads nothing from a MERGED base");
		assertTrue(!script.contains("HUD_TREE=") && !script.contains("javap -"), "no javap of a merged Hud");
		assertTrue(script.contains("refuse_shared_rundir \"$RUNDIR\"") && script.contains("refuse_shared_rundir \"$PROMPT_DIR\""));
	}

	/** The kernel's PiP bridge line as it prints when the bridge also gave vanilla's orphaned map its only writer. */
	private static final String PIP_BRIDGE = "[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a "
			+ "fallback to the orphaned vanilla map — NeoForge's preparePictureInPictureState reads only its pools, so every "
			+ "guest-registered GUI element (Xaero's minimap, malilib's overlays) was registered where nothing reads, and gave "
			+ "that map its only writer";

	private static final List<String> SESSION_GREEN = List.of(
			"[ClientSmoke] joined world via quick-play", "[ClientSmoke] client-ready after 60 ticks",
			"[ClientSmoke] clean disconnect observed",
			PIP_BRIDGE,
			"[ForbricNeoLive/FLUID] water at 3 64 -2: Block{minecraft:water}");

	/**
	 * The session block: the join, the play, the clean leave, the PiP bridge's writer and the canary's water, each row
	 * load-bearing. Stage 4 C5 re-pinned it: the bridge inventories, the HudBridge lines (and the HUD_TREE javap that chose
	 * between two of them) and MinecraftForge's fluid extensions have no producer on the genuine base and went with the
	 * gate's rows. The PiP row is explicit about the WRITER (D65): the same line without its writer clause is red.
	 */
	@Test
	void theSessionBlockAssertsTheJoinThePipWriterAndTheWater() throws Exception {
		String green = String.join("\n", SESSION_GREEN) + "\n";
		assertEquals(0, session(green, 0, "").exit());
		for (String line : SESSION_GREEN) {
			assertEquals(1, session(green.replace(line + "\n", ""), 0, "").exit(), "a missing row is red: " + line);
		}
		assertEquals(1, session("", 0, "").exit());
		assertEquals(1, session(green, 1, "").exit(), "a preceding failure stays red");
		String noWriter = PIP_BRIDGE.substring(0, PIP_BRIDGE.indexOf(", and gave that map its only writer"));
		assertEquals(1, session(green.replace(PIP_BRIDGE, noWriter), 0, "").exit(),
				"the bridge without the writer leaves guest renderers unread: red");
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

	private Result m21(String log) throws Exception { return block(M21, "M21_NEO_CONTRACTS", log); }
	private Result m26Fabric(String log) throws Exception { return block(M26, "M26_FABRIC_ASSERTIONS", log); }
	private Result m26(String log) throws Exception { return block(M26, "M26_NEO_ASSERTIONS", log); }

	private Result m26ServerIds(String clientLog, String serverLog) throws Exception {
		Path server = temporary.resolve("server.log");
		Files.writeString(server, serverLog);
		return block(M26, "M26_SERVER_IDS", clientLog, java.util.Map.of("SERVER_LOG", server.toString()));
	}

	private Result block(Path gate, String name, String log) throws Exception {
		return block(gate, name, log, java.util.Map.of());
	}

	private Result block(Path gate, String name, String log, java.util.Map<String, String> env) throws Exception {
		Path file = temporary.resolve("gate.log");
		Files.writeString(file, log);
		String script = Files.readString(gate);
		int begin = script.indexOf("# " + name + "_BEGIN");
		int end = script.indexOf("# " + name + "_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable gate contract " + name);
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", ". run/lib.sh\n" + script.substring(begin, end) + "\nexit \"$FAIL\"");
		builder.environment().put("LOG", file.toString());
		builder.environment().putAll(env);
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
