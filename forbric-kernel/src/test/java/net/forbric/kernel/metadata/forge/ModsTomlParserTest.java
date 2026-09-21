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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

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

		// Plain JDK types all the way down: the reader branches on `instanceof Map` and the kernel ships its own
		// night-config, so handing back night-config's Config would be a class-identity mismatch inside the
		// reader's catch-all — it would look exactly like the mod declaring nothing.
		assertInstanceOf(Map.class, properties.get("nested"));
		assertFalse(properties.get("nested") instanceof com.electronwill.nightconfig.core.UnmodifiableConfig,
				"night-config types must not escape the parser");
		assertEquals("yes", ((Map<?, ?>) properties.get("nested")).get("inner"));

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

		Object sodium = entry.get("sodium:options");
		assertInstanceOf(Map.class, sodium, "the colon-bearing sub-table name survives as one key");
		assertEquals(Boolean.FALSE, ((Map<?, ?>) sodium).get("mixin.features.render.world.sky"),
				"one literal dotted key, not five nested levels, and still a Boolean");
		assertFalse(sodium instanceof com.electronwill.nightconfig.core.UnmodifiableConfig,
				"night-config types must not escape the parser");
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
