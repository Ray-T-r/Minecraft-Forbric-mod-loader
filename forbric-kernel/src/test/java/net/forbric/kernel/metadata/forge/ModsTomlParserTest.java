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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

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
