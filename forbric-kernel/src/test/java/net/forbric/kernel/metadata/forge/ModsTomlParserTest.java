/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import com.electronwill.nightconfig.core.Config;
import org.junit.jupiter.api.Test;
import net.forbric.api.UnifiedDependency;

class ModsTomlParserTest {
	private ForgeModsToml parseSample() {
		try (InputStream in = getClass().getResourceAsStream("/forge/sample.mods.toml")) {
			assertNotNull(in, "sample.mods.toml fixture missing");
			return ModsTomlParser.parse(in);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * The [modproperties.<id>] table, which is how a mod addresses ANOTHER mod rather than the loader.
	 *
	 * <p>Pinned on the exact shape that made it worth parsing: iris declares
	 * {@code [modproperties.iris] "sodium:config_api_user" = "..."}, Sodium reads that key out of
	 * {@code IModInfo.getModProperties()} to find the class that builds iris' page in Video Settings, and the
	 * kernel answered every such question with an empty map — so the page did not exist.
	 *
	 * <p>The key is the trap: it is QUOTED and contains a COLON, under a quoted section name. A lookup that goes
	 * through night-config's dotted-path {@code get(String)} splits on dots and would miss keys like it; every
	 * read in the parser uses {@code Collections.singletonList(key)} for that reason, and this table is walked by
	 * entry rather than by key at all.
	 */
	@Test
	void parsesModPropertiesIncludingAQuotedColonBearingKey() {
		String toml = """
				modLoader="javafml"
				loaderVersion="[1,)"
				[[mods]]
				modId="iris"
				version="1.11.4"
				[modproperties.iris]
				"sodium:config_api_user" = "net.irisshaders.iris.compat.sodium.config.IrisConfig"
				"fabric:provides" = ["indium"]
				"fabric-renderer-api-v1:contains_renderer" = true
				[modproperties.iris.nested]
				inner = "yes"
				[[mods]]
				modId="plain"
				version="1.0"
				""";

		ForgeModsToml parsed = ModsTomlParser.parse(toml);
		ForgeModEntry iris = parsed.getMods().get(0);
		assertEquals("iris", iris.getModId());

		Map<String, Object> properties = iris.getProperties();
		assertEquals("net.irisshaders.iris.compat.sodium.config.IrisConfig",
				properties.get("sodium:config_api_user"),
				"the colon-bearing key must survive verbatim — this exact string is what Sodium looks up");
		assertEquals(List.of("indium"), properties.get("fabric:provides"),
				"a list value stays a list; a reader that wants a String warns about it itself");
		assertEquals(Boolean.TRUE, properties.get("fabric-renderer-api-v1:contains_renderer"),
				"a boolean stays a boolean rather than being stringified");

		// A nested table stays night-config's own Config, which is what both FMLs hand a mod: the table is the
		// shallow valueMap() of [modproperties.<id>], so anything one level down is still a Config. LibJF casts
		// to exactly that type — see libjfTranslatesMigrationTableIsTheConfigConfigCoreCastsTo.
		assertInstanceOf(Config.class, properties.get("nested"));
		assertEquals("yes", ((Config) properties.get("nested")).get(List.of("inner")));

		assertTrue(parsed.getMods().get(1).getProperties().isEmpty(),
				"a mod with no table gets an empty map, never null");
	}

	/**
	 * The {@code [[mods]]} entry itself, which {@code IConfigurable.getConfigElement} answers from.
	 *
	 * <p>Pinned on iris' real declaration: it switches OFF the sodium mixin that draws the sky, because iris
	 * draws the sky itself. The kernel answered every such query with an empty Optional, so the override reached
	 * nobody — {@code Loaded configuration file for Sodium: 37 options available, 0 override(s) found}.
	 *
	 * <p>The trap is the KEY. {@code mixin.features.render.world.sky} is ONE literal key containing dots, and
	 * night-config's {@code get(String)} is a dotted-PATH lookup that would split it into five levels and find
	 * nothing. The table is walked by entry, and the value must stay a {@code Boolean} — a stringified
	 * {@code "false"} makes sodium warn about an invalid value instead of applying it.
	 */
	@Test
	void parsesTheModsEntryIncludingADottedKeyThatIsOneKey() {
		String toml = """
				modLoader="javafml"
				loaderVersion="[1,)"
				[[mods]]
				modId="iris"
				version="1.11.4"
				displayName="Iris"
				[mods."sodium:options"]
				"mixin.features.render.world.sky" = false
				[[mods]]
				modId="plain"
				version="1.0"
				""";

		ForgeModsToml parsed = ModsTomlParser.parse(toml);
		ForgeModEntry iris = parsed.getMods().get(0);
		assertEquals("iris", iris.getModId());

		Map<String, Object> entry = iris.getConfigElements();
		assertEquals("iris", entry.get("modId"), "the entry carries its own scalars");
		assertEquals("Iris", entry.get("displayName"));

		// Held as the Config the entry's own valueMap() holds; IConfigurable.getConfigElement answers it with that
		// Config's valueMap(), the way NeoForge's NightConfigWrapper does (FmlTomlShapeOracleTest pins that).
		Object sodium = entry.get("sodium:options");
		assertInstanceOf(Config.class, sodium, "the colon-bearing sub-table name survives as one key");
		assertEquals(Boolean.FALSE, ((Config) sodium).valueMap().get("mixin.features.render.world.sky"),
				"one literal dotted key, not five nested levels, and still a Boolean");
	}

	/**
	 * LibJF Translate's real {@code neoforge.mods.toml}, read the way LibJF Config Core reads it.
	 *
	 * <p>{@code DslConfigInstance.migrateFiles} runs whenever a config file does not exist yet — so on every first
	 * launch — and its lambda does, instruction for instruction: {@code (Config) getModProperties().get("libjf:config")},
	 * then {@code (List) .get("previous_names")}, then {@code (Config)} each element and {@code (String) .get("name")}.
	 * Native FML hands it a Config there, because {@code ModInfo} stores the SHALLOW {@code valueMap()} of
	 * {@code [modproperties.libjf_translate_v1]}. The kernel handed it a {@code LinkedHashMap}, the checkcast threw,
	 * {@code TranslateConfig.<clinit>} failed with it, and so did the {@code ConfigCore} constructor that loads it —
	 * LibJF Config Core was withdrawn from the mod list on every launch, server and client.
	 */
	@Test
	void libjfTranslatesMigrationTableIsTheConfigConfigCoreCastsTo() {
		Map<String, Object> properties = libjfTranslate().getProperties();

		Config libjfConfig = (Config) properties.get("libjf:config");
		List<?> previousNames = (List<?>) libjfConfig.get("previous_names");
		List<String> names = new ArrayList<>();
		for (Object previous : previousNames) names.add((String) ((Config) previous).get("name"));
		assertEquals(List.of("libjf_translate_v1"), names);
	}

	/**
	 * The other LibJF read of the same table must keep working: {@code NeoforgeEntrypointStorage.asMap} takes a
	 * {@code Config} through {@code valueMap()} and a {@code Map} as it is, and every entry point LibJF has —
	 * including the {@code libjf:config} one that registers Translate's config — is found through it.
	 */
	@Test
	void libjfsEntrypointReaderStillFindsTranslatesConfigEntrypoint() {
		Map<String, Object> entrypoints = asLibjfMap(libjfTranslate().getProperties().get("libjf:entrypoints"));
		List<?> configs = (List<?>) entrypoints.get("libjf:config");
		assertEquals(1, configs.size());
		assertEquals("dev.jfronny.libjf.translate.impl.TranslateConfig", asLibjfMap(configs.get(0)).get("value"));
	}

	@Test
	void switchedOffEveryTableIsFlattenedToAPlainMapAgain() {
		System.setProperty(ModsTomlParser.NIGHT_CONFIG_TABLES, "off");
		try {
			Map<String, Object> properties = libjfTranslate().getProperties();
			assertInstanceOf(java.util.LinkedHashMap.class, properties.get("libjf:config"), "off => the old flattening");
			assertThrows(ClassCastException.class, () -> {
				Config ignored = (Config) properties.get("libjf:config");
			}, "and with it the cast LibJF Config Core died on");
		} finally {
			System.clearProperty(ModsTomlParser.NIGHT_CONFIG_TABLES);
		}
	}

	/** The fixture is byte-for-byte the manifest LibJF ships, when the sweep's copy of the jar is here to check. */
	@Test
	void theFixtureIsTheManifestLibjfShips() throws Exception {
		Path outer = Path.of("build/compat-inputs/sweep90/mods/libjf-26.2.2+forge.jar");
		assumeTrue(Files.isRegularFile(outer), "the sweep's LibJF jar is not staged here");
		byte[] shipped;
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			byte[] nested = zip.getInputStream(zip.getEntry("META-INF/jars/libjf-translate-v1-26.2.2+forge.jar"))
					.readAllBytes();
			try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(nested))) {
				ZipEntry entry;
				byte[] found = null;
				while ((entry = in.getNextEntry()) != null) {
					if (entry.getName().equals("META-INF/neoforge.mods.toml")) found = in.readAllBytes();
				}
				shipped = found;
			}
		}
		try (InputStream fixture = getClass().getResourceAsStream(LIBJF_TRANSLATE)) {
			assertArrayEquals(shipped, fixture.readAllBytes());
		}
	}

	static final String LIBJF_TRANSLATE = "/forge/libjf-translate-v1.neoforge.mods.toml";

	private ForgeModEntry libjfTranslate() {
		try (InputStream in = getClass().getResourceAsStream(LIBJF_TRANSLATE)) {
			assertNotNull(in, LIBJF_TRANSLATE + " fixture missing");
			ForgeModEntry entry = ModsTomlParser.parse(in).getMods().get(0);
			assertEquals("libjf_translate_v1", entry.getModId());
			return entry;
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** {@code NeoforgeEntrypointStorage.asMap}, as its bytecode reads: a Config through valueMap(), else a Map. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> asLibjfMap(Object value) {
		return value instanceof Config config ? config.valueMap() : (Map<String, Object>) value;
	}

	@Test
	void parsesLoaderHeader() {
		ForgeModsToml toml = parseSample();

		assertEquals("javafml", toml.getModLoader());
		assertEquals("[47,)", toml.getLoaderVersion());
		assertEquals(1, toml.getMods().size());
	}

	@Test
	void parsesModEntry() {
		ForgeModEntry mod = parseSample().getMods().get(0);

		assertEquals("examplemod", mod.getModId());
		assertEquals("Example Mod", mod.getDisplayName());
		assertEquals("${file.jarVersion}", mod.getVersion());
		assertTrue(mod.getDescription().contains("sample Forge mod"));
	}

	@Test
	void resolvesJarVersionPlaceholder() {
		ForgeModEntry mod = parseSample().getMods().get(0);
		assertEquals("3.2.1", ModsTomlParser.resolveVersion(mod.getVersion(), "3.2.1"));
	}

	@Test
	void parsesDependenciesIncludingOptionalAndSide() {
		ForgeModEntry mod = parseSample().getMods().get(0);

		assertEquals(3, mod.getDependencies().size());

		ForgeDependency forge = mod.getDependencies().get(0);
		assertEquals("forge", forge.getModId());
		assertTrue(forge.isMandatory());
		assertEquals("[47,)", forge.getVersionRange());
		assertEquals(UnifiedDependency.SideScope.BOTH, forge.getSideScope());

		ForgeDependency jei = mod.getDependencies().get(2);
		assertEquals("jei", jei.getModId());
		assertFalse(jei.isMandatory());
		assertEquals(UnifiedDependency.Ordering.AFTER, jei.getOrdering());
		assertEquals(UnifiedDependency.SideScope.CLIENT, jei.getSideScope());
	}
}
