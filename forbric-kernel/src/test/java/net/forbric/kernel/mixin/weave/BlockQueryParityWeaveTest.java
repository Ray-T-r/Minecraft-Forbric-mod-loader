package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinBlockQueryAdapters;

/**
 * {@code MixinBlockQueryAdapters} judged against vanilla: vanilla asks {@code state.getBlock().getFriction()}, the merged
 * classes ask NeoForge's {@code state.getFriction(level, pos, entity)} in the same places, and two unrelated mods hook the
 * vanilla query — one {@code @WrapOperation} with a bare-name selector, no captures, and the block kept in a local before
 * it is passed on; one {@code @ModifyExpressionValue} on the SECOND of two queries in a method ({@code ordinal = 1}). The
 * query names appear in no list: the replacement is read off the two bodies, and the ordinal is translated by pairing the
 * vanilla body (the native reference) with the merged one. Woven onto vanilla-shaped and onto merged-shaped classes, the
 * entity must slide and drift the same; switched off, the merged run differs and both hooks are lost.
 */
class BlockQueryParityWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/blockquery");
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final List<WeaveHarness.Config> CONFIGS = List.of(
			new WeaveHarness.Config("slick.mixins.json", "slick", Ecosystem.FABRIC),
			new WeaveHarness.Config("drifter.mixins.json", "drifter", Ecosystem.FABRIC));
	private static final String VANILLA = WeaveHarnessMain.DONE + " stone 0.3 | grease 0.49 | drift 12.6";

	@TempDir static Path work;
	private static Path merged;
	private static WeaveHarness.Result vanillaRun, mergedRun, offRun;

	@BeforeAll static void weave() throws Exception {
		Map<String, Path> configs = new LinkedHashMap<>();
		for (WeaveHarness.Config config : CONFIGS) configs.put(config.name(), SOURCES.resolve("mods").resolve(config.name()));
		List<Path> vanillaSources = new ArrayList<>(sources("common"));
		vanillaSources.addAll(sources("vanilla"));
		vanillaSources.addAll(sources("mods"));
		Path vanilla = WeaveHarness.fixture(work, "blockquery-vanilla", vanillaSources, configs, List.of("-g"));

		// The vanilla-shaped Entity, as the merged-base builder records what a Fabric mod was compiled against.
		byte[] reference = Files.readAllBytes(work.resolve("blockquery-vanilla-classes/" + ENTITY + ".class"));
		Path bin = work.resolve("entity.native.bin"), index = work.resolve("native-index.tsv");
		Files.write(bin, reference);
		Files.writeString(index, "# forbric-native-reference-v1\n" + ENTITY + "\t"
				+ HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference)) + "\n");
		Map<String, Path> mergedResources = new LinkedHashMap<>(configs);
		mergedResources.put("META-INF/forbric/native-reference/FABRIC/index.tsv", index);
		mergedResources.put("META-INF/forbric/native-reference/FABRIC/" + ENTITY + ".class.bin", bin);
		List<Path> mergedSources = new ArrayList<>(sources("common"));
		mergedSources.addAll(sources("merged"));
		mergedSources.addAll(sources("mods"));
		// The kernel's game-side Operation the wrapped handler is handed, compiled in from the production source.
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		merged = WeaveHarness.fixture(work, "blockquery-merged", mergedSources, mergedResources, List.of("-g"));

		vanillaRun = run(vanilla, "vanilla", Map.of());
		mergedRun = run(merged, "merged", Map.of());
		offRun = run(merged, "merged-off", Map.of(MixinBlockQueryAdapters.PROPERTY, "off"));
	}

	@Test void vanillaRunsBothHooks() {
		assertTrue(vanillaRun.printedLine(VANILLA), vanillaRun.describe());
		assertEquals(List.of(), losses(vanillaRun), vanillaRun.describe());
	}

	@Test void theMergedEntitySlidesAndDriftsExactlyAsVanillas() throws Exception {
		assertEquals(probeLine(vanillaRun), probeLine(mergedRun), mergedRun.describe());
		assertEquals(List.of(), losses(mergedRun), "findings " + mergedRun.findings() + "\n" + mergedRun.describe());
		WeaveHarness.assertWovenAndVerified(mergedRun, ENTITY, merged);
	}

	@Test void switchedOffBothHooksAreLost() {
		assertNotEquals(probeLine(vanillaRun), probeLine(offRun), offRun.describe());
		assertEquals(List.of("drifter", "slick"), losses(offRun).stream().map(WeaveHarness.Finding::modId).sorted().toList(), offRun.describe());
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
		return WeaveHarness.run(work, label, fixture, CONFIGS, List.of(), EnvType.SERVER, "fixture.blockquery.Probe", "probe", properties);
	}
}
