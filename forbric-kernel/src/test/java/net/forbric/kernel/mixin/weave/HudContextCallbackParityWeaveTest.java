package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinHudContextAdapter;
import net.forbric.kernel.transform.HudContextQueryInjector;

/**
 * {@code HudContextCallbackScope} judged against vanilla: two unrelated mods wrap vanilla's contextual-bar selection, one
 * drawing a compass and letting the bar through, the other marking the frame and swapping the experience bar for the
 * locator bar. Vanilla nests the two wraps; on the merged Hud the kernel moves both around NeoForge's bar update, where
 * they nest the same way and the native selection asks the frames. The shown and the hidden frame must draw exactly
 * what vanilla draws: both mods, in vanilla's order, and the bar the outer one returns. With one frame per thread the
 * inner mod's frame would replace the outer one's; switched off, both wraps sit in the dead vanilla method.
 */
class HudContextCallbackParityWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/hudparity");
	private static final String HUD = "net/minecraft/client/gui/Hud";
	private static final List<WeaveHarness.Config> CONFIGS = List.of(
			new WeaveHarness.Config("compass.mixins.json", "compass", Ecosystem.FABRIC),
			new WeaveHarness.Config("locator.mixins.json", "locator", Ecosystem.FABRIC));
	private static final List<String> VANILLA_ORDERS = List.of(
			WeaveHarnessMain.DONE + " shown: compass, locator, bar LOCATOR | hidden: nothing",
			WeaveHarnessMain.DONE + " shown: locator, compass, bar LOCATOR | hidden: nothing");

	@TempDir static Path work;
	private static Path merged;
	private static WeaveHarness.Result vanillaRun, mergedRun, offRun;

	@BeforeAll static void weave() throws Exception {
		Map<String, Path> configs = new LinkedHashMap<>();
		for (WeaveHarness.Config config : CONFIGS) configs.put(config.name(), SOURCES.resolve("mods").resolve(config.name()));
		List<Path> vanillaSources = new ArrayList<>(sources("common"));
		vanillaSources.addAll(sources("vanilla"));
		vanillaSources.addAll(sources("mods"));
		Path vanilla = WeaveHarness.fixture(work, "hudparity-vanilla", vanillaSources, configs, List.of("-g"));
		List<Path> mergedSources = new ArrayList<>(sources("common"));
		mergedSources.addAll(sources("merged"));
		mergedSources.addAll(sources("mods"));
		// The kernel's game-side hooks the adapted code reaches; the boot-side scope resolves from source, not packed.
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelHudContextQuery.java"));
		merged = WeaveHarness.fixture(work, "hudparity-merged", mergedSources, configs, List.of("-g", "-sourcepath", "src/main/java", "-implicit:none"));
		PreMixinFixture.transform(merged, HudContextQueryInjector.TARGET, new HudContextQueryInjector(), EnvType.CLIENT);
		vanillaRun = run(vanilla, "vanilla", Map.of());
		mergedRun = run(merged, "merged", Map.of());
		offRun = run(merged, "merged-off", Map.of(MixinHudContextAdapter.PROPERTY, "off"));
	}

	@Test void vanillaNestsBothWrapsAndAHiddenHudDrawsNothing() {
		assertTrue(VANILLA_ORDERS.stream().anyMatch(vanillaRun::printedLine), vanillaRun.describe());
		assertEquals(List.of(), losses(vanillaRun), vanillaRun.describe());
	}

	@Test void theMergedHudDrawsExactlyVanillasFrames() throws Exception {
		assertEquals(probeLine(vanillaRun), probeLine(mergedRun), mergedRun.describe());
		assertEquals(List.of(), losses(mergedRun), "findings " + mergedRun.findings() + "\n" + mergedRun.describe());
		WeaveHarness.assertWovenAndVerified(mergedRun, HUD, merged);
	}

	@Test void switchedOffBothWrapsSitInTheDeadVanillaMethod() {
		assertNotEquals(probeLine(vanillaRun), probeLine(offRun), offRun.describe());
		assertEquals(List.of("compass", "locator"), losses(offRun).stream().map(WeaveHarness.Finding::modId).sorted().toList(), offRun.describe());
	}

	private static String probeLine(WeaveHarness.Result run) {
		return run.output().lines().filter(line -> line.startsWith(WeaveHarnessMain.DONE) || line.startsWith(WeaveHarnessMain.THREW))
				.findFirst().orElse("no probe line");
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && !f.confidence().equals("RESOLVED")).toList();
	}

	private static List<Path> sources(String part) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES.resolve(part))) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIGS, List.of(), EnvType.CLIENT, "fixture.hudparity.Probe", "probe", properties);
	}
}
