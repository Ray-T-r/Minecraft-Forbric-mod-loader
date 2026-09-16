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

package net.forbric.kernel.metadata.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import net.forbric.api.DiscoveredMod;

import org.junit.jupiter.api.Test;

/**
 * Pins Fabric's {@code provides} through the reader.
 *
 * <p>A Fabric mod may answer to ids other than its own, and LibJF names EVERY module that way: the mod id is
 * {@code libjf-base} and the id its dependents actually require is {@code libjf_base}. Dropped at the reader,
 * the aliases are gone for everything downstream — the dependency dialog a player is asked to believe, and
 * {@code ModList.isLoaded} for both Forge families, which the transform chain routes through {@code ModPresence}.
 *
 * <p>The json is LibJF 26.2.2's own, trimmed to the fields under test.
 */
class FabricModJsonProvidesTest {

	@Test
	void theAliasesAModDeclaresSurviveTheRead() {
		DiscoveredMod mod = read("""
				{"schemaVersion":1,"id":"libjf-base","provides":["libjf_base"],
				 "name":"LibJF Base","version":"26.2.2"}
				""");

		assertEquals("libjf-base", mod.getId());
		assertEquals(List.of("libjf_base"), mod.getAliases(),
				"respackopts requires libjf_base, which is an alias and not this mod's id — without it the "
						+ "audit calls an installed library missing");
	}

	/** LibJF's config module carries three, including one for the version it superseded. */
	@Test
	void everyAliasIsKept() {
		DiscoveredMod mod = read("""
				{"schemaVersion":1,"id":"libjf-config-core-v2",
				 "provides":["libjf-config-core-v1","libjf_config_core_v1","libjf_config_core_v2"],
				 "version":"26.2.2"}
				""");

		assertEquals(3, mod.getAliases().size());
		assertTrue(mod.getAliases().contains("libjf_config_core_v2"));
		assertTrue(mod.getAliases().contains("libjf-config-core-v1"), "an alias for a superseded module still "
				+ "resolves a dependency written against it");
	}

	@Test
	void aModWithoutProvidesHasNoAliasesRatherThanNull() {
		assertEquals(List.of(), read("{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"1.0\"}").getAliases());
	}

	/** Malformed entries are dropped, not propagated: an alias is only useful if it is a usable id. */
	@Test
	void nonStringAndBlankEntriesAreDropped() {
		DiscoveredMod mod = read("""
				{"schemaVersion":1,"id":"weird","version":"1.0","provides":["ok","",7]}
				""");
		assertEquals(List.of("ok"), mod.getAliases());
	}

	private static DiscoveredMod read(String json) {
		return FabricModJsonReader.read(new StringReader(json), "test.jar");
	}
}
