package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;
import org.junit.jupiter.api.Test;

/** The packaged data, not just its source, must connect modifier -> placed feature -> configured feature. */
class WorldgenCanaryDataTest {
	static final Path LOADER = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"));

	@Test
	void theTwoCarriersPlaceDifferentNonOverworldBlocks() throws Exception {
		String forge = inspect("forge", "forbriclive", "livemod-src", "forge-runtime");
		String neo = inspect("neoforge", "forbricneolive", "livemod-src-neoforge", "neoforge-runtime");
		assertEquals("minecraft:end_stone", forge);
		assertEquals("minecraft:purpur_block", neo);
		assertNotEquals(forge, neo, "one family's success must never satisfy the other's probe");
	}

	static String inspect(String family, String namespace, String source, String runtime) throws Exception {
		Path jar = LOADER.resolve("run/" + runtime + "/" + namespace + ".jar");
		assumeTrue(Files.isRegularFile(jar), "build canaries with forbric-loader/run/build-testmods.sh");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			String base = "data/" + namespace + "/";
			UnmodifiableConfig modifier = json(zip, base + family + "/biome_modifier/probe.json", source);
			assertEquals(family + ":add_features", modifier.get("type"));
			assertEquals("#minecraft:is_overworld", modifier.get("biomes"));
			assertEquals("underground_ores", modifier.get("step"));
			String placedId = modifier.get("features");
			UnmodifiableConfig placed = json(zip, entry("worldgen/placed_feature", placedId), source);
			List<UnmodifiableConfig> placement = placed.get("placement");
			assertTrue(placement.stream().anyMatch(p -> "minecraft:biome".equals(p.get("type"))));
			assertTrue(placement.stream().anyMatch(p -> "minecraft:count".equals(p.get("type"))
					&& ((Number) p.get("count")).intValue() >= 1));
			String configuredId = placed.get("feature");
			UnmodifiableConfig configured = json(zip, entry("worldgen/configured_feature", configuredId), source);
			assertEquals("minecraft:ore", configured.get("type"));
			assertTrue(((Number) configured.get("config.size")).intValue() >= 1);
			List<UnmodifiableConfig> targets = configured.get("config.targets");
			assertEquals(1, targets.size());
			assertEquals("minecraft:tag_match", targets.getFirst().get("target.predicate_type"));
			assertEquals("minecraft:stone_ore_replaceables", targets.getFirst().get("target.tag"));
			return targets.getFirst().get("state.Name");
		}
	}

	private static String entry(String registry, String id) {
		String[] parts = id.split(":", 2);
		assertEquals(2, parts.length, "feature reference must carry its canary namespace");
		return "data/" + parts[0] + "/" + registry + "/" + parts[1] + ".json";
	}

	private static UnmodifiableConfig json(ZipFile zip, String name, String source) throws Exception {
		var entry = zip.getEntry(name);
		assertNotNull(entry, "missing canary datapack entry " + name);
		try (var input = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);
				var expected = Files.newBufferedReader(LOADER.resolve("run/" + source + "/" + name))) {
			UnmodifiableConfig packaged = JsonFormat.minimalInstance().createParser().parse(input);
			UnmodifiableConfig original = JsonFormat.minimalInstance().createParser().parse(expected);
			assertEquals(original, packaged, "canary jar is stale: " + name);
			return packaged;
		}
	}
}
