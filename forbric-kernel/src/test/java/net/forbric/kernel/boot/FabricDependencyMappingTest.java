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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.fabric.FabricModMetadataParser;

/**
 * Covers a Fabric mod's declared dependencies reaching the unified model.
 *
 * <p>They did not. The presence list published for cross-ecosystem queries built every Fabric
 * {@code DiscoveredMod} with {@code List.of()} dependencies — not because Fabric mods declare none, but because
 * nobody filled them in. An empty list is indistinguishable from "has none", so {@link DependencyAudit} judged
 * only the Forge families and no line anywhere said that half the instance was exempt.
 */
class FabricDependencyMappingTest {
	@Test
	void dependsBecomesMandatoryAndRecommendsDoesNot() {
		Map<String, UnifiedDependency> deps = map("""
				{ "schemaVersion": 1, "id": "example", "version": "1.0.0",
				  "depends": { "fabric-api": ">=0.100.0" },
				  "recommends": { "sodium": "*" },
				  "suggests": { "iris": "*" } }
				""");

		assertTrue(deps.get("fabric-api").isMandatory());
		assertEquals(">=0.100.0", deps.get("fabric-api").getVersionConstraint());
		assertFalse(deps.get("sodium").isMandatory());
		assertFalse(deps.get("iris").isMandatory());
	}

	/**
	 * The one that would be actively harmful. {@code breaks}/{@code conflicts} say a mod must be ABSENT; carried
	 * over as dependencies they would make the audit report "requires X — not installed" about exactly the mods
	 * the author wants gone, which is a boot log accusing the user of the thing they got right.
	 */
	@Test
	void breaksAndConflictsAreNotDependencies() {
		Map<String, UnifiedDependency> deps = map("""
				{ "schemaVersion": 1, "id": "example", "version": "1.0.0",
				  "depends": { "fabric-api": "*" },
				  "breaks": { "optifabric": "*" },
				  "conflicts": { "oldmod": "<2.0.0" } }
				""");

		assertEquals(List.of("fabric-api"), List.copyOf(deps.keySet()));
	}

	/** The array form is OR, and the joined string is what the unified evaluator reads. */
	@Test
	void theArrayFormSurvivesAsAnOrJoinedPredicate() {
		Map<String, UnifiedDependency> deps = map("""
				{ "schemaVersion": 1, "id": "example", "version": "1.0.0",
				  "depends": { "libx": ["~1.2.0", ">=2.0.0"] } }
				""");

		UnifiedDependency libx = deps.get("libx");
		assertTrue(libx.isSatisfiedBy("1.2.9"), libx.getVersionConstraint());
		assertTrue(libx.isSatisfiedBy("3.0.0"), libx.getVersionConstraint());
		assertFalse(libx.isSatisfiedBy("1.3.0"), libx.getVersionConstraint());
	}

	@Test
	void aModWithNoDependenciesStillMapsToAnEmptyListRatherThanFailing() {
		assertTrue(map("{ \"schemaVersion\": 1, \"id\": \"solo\", \"version\": \"1.0.0\" }").isEmpty());
	}

	private static Map<String, UnifiedDependency> map(String json) {
		return KernelFabricEcosystem.unifiedDependencies(FabricModMetadataParser.read(new StringReader(json)))
				.stream().collect(Collectors.toMap(UnifiedDependency::getModId, Function.identity(),
						(a, b) -> a, java.util.LinkedHashMap::new));
	}
}
