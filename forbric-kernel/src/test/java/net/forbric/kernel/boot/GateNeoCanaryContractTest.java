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
	private static final List<String> M21_GREEN = List.of(
			"[ForbricNeoLive/SCAN] ModList found its own @NeoLiveScanned on FIELD PROBE of forbric.live.neoforge.ForbricNeoLiveMod",
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
		assertGreen(m21(String.join("\n", M21_GREEN) + "\n"));
		for (String line : M21_GREEN) {
			List<String> without = new java.util.ArrayList<>(M21_GREEN);
			without.remove(line);
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
		Result failed = m21(green + "[ForbricNeoLive/SCAN] could not read the LoadingModList scan index: java.lang.IllegalStateException\n");
		assertRed(failed, "neither reader failed");
	}

	/**
	 * The shape the kernel had until 2026-10-30, and has again with {@code -Dforbric.modFilePerJar=off}: one file per
	 * MOD, so the canary's jar (two mods) is listed twice, with its own annotation found in both entries. The count
	 * line alone says only "2 file(s)", which the old check's {@code [1-9][0-9]*} took as green.
	 */
	@Test
	void m21NeoBlockTellsOneJarFromTheSameJarListedOncePerMod() throws Exception {
		List<String> twice = new java.util.ArrayList<>();
		for (String line : M21_GREEN) {
			if (line.contains("lists forbricneolive's jar")) {
				twice.add(line.replace("1 time(s)", "2 time(s)"));
			} else if (line.contains("getAllScanData(): ")) {
				twice.add("[ForbricNeoLive/SCAN] ModList.getAllScanData(): 2 file(s), 10 annotation(s)");
			} else {
				twice.add(line);
				if (line.startsWith("[ForbricNeoLive/SCAN] ModList found its own")) twice.add(line);
			}
		}
		Result result = m21(String.join("\n", twice) + "\n");
		assertRed(result, "the index lists the canary's jar once, as one IModFile");
		assertTrue(result.output().contains("its own annotation is found in one entry, not one per mod"), result.output());
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

	private Result m21(String log) throws Exception { return block(M21, "M21_NEO_CONTRACTS", log); }
	private Result m26Fabric(String log) throws Exception { return block(M26, "M26_FABRIC_ASSERTIONS", log); }
	private Result m26(String log) throws Exception { return block(M26, "M26_NEO_ASSERTIONS", log); }

	private Result block(Path gate, String name, String log) throws Exception {
		Path file = temporary.resolve("gate.log");
		Files.writeString(file, log);
		String script = Files.readString(gate);
		int begin = script.indexOf("# " + name + "_BEGIN");
		int end = script.indexOf("# " + name + "_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable gate contract " + name);
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", ". run/lib.sh\n" + script.substring(begin, end) + "\nexit \"$FAIL\"");
		builder.environment().put("LOG", file.toString());
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
