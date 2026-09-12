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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.UnifiedDependency.Ordering;
import net.forbric.api.UnifiedDependency.SideScope;
import net.forbric.kernel.metadata.forge.ForgeMetadataMapper;
import net.forbric.kernel.metadata.forge.ModsTomlParser;

/**
 * Covers the two axes {@link UnifiedDependency} names but used to drop, and the constraint it used to only carry.
 */
class UnifiedDependencyTest {
	@Test
	void theThreeArgumentFormStillMeansNoOrderingAndBothSides() {
		UnifiedDependency dep = new UnifiedDependency("sodium", ">=0.5", true);
		assertEquals(Ordering.NONE, dep.getOrdering());
		assertEquals(SideScope.BOTH, dep.getSideScope());
		assertTrue(dep.appliesOn(Side.CLIENT));
		assertTrue(dep.appliesOn(Side.DEDICATED_SERVER));
	}

	@Test
	void aSideScopedRequirementDoesNotApplyOnTheOtherSide() {
		UnifiedDependency clientOnly = new UnifiedDependency("jei", "*", true, Ordering.AFTER, SideScope.CLIENT);
		assertTrue(clientOnly.appliesOn(Side.CLIENT));
		assertFalse(clientOnly.appliesOn(Side.DEDICATED_SERVER),
				"a client-only requirement judged on a dedicated server is a false report of a missing mod");
	}

	@Test
	void anUnknownOrderingOrSideFallsBackRatherThanThrowing() {
		assertEquals(Ordering.NONE, Ordering.parse("sideways"));
		assertEquals(Ordering.NONE, Ordering.parse(null));
		assertEquals(Ordering.AFTER, Ordering.parse(" after "));
		assertEquals(SideScope.BOTH, SideScope.parse("holographic"));
		assertEquals(SideScope.CLIENT, SideScope.parse("CLIENT"));
	}

	@Test
	void theConstraintCanBeAskedWhetherAVersionSatisfiesIt() {
		UnifiedDependency dep = new UnifiedDependency("fabric-api", ">=0.100.0 <0.200.0", true);
		assertTrue(dep.isSatisfiedBy("0.130.4"));
		assertFalse(dep.isSatisfiedBy("0.99.0"));
		assertTrue(new UnifiedDependency("anything", null, true).isSatisfiedBy("whatever"));
	}

	/**
	 * End to end from the file: {@code mods.toml} says {@code ordering} and {@code side}, and both used to stop at
	 * {@code ForgeMetadataMapper}, which built the unified dependency from three of the five fields it had.
	 */
	@Test
	void theMapperCarriesOrderingAndSideOutOfTheTomlNowInsteadOfDroppingThem() {
		String toml = """
				modLoader = "javafml"
				loaderVersion = "[47,)"
				license = "MIT"
				[[mods]]
				modId = "examplemod"
				version = "1.0.0"
				[[dependencies.examplemod]]
				modId = "jei"
				mandatory = true
				versionRange = "[15,16)"
				ordering = "AFTER"
				side = "CLIENT"
				""";
		List<DiscoveredMod> mods = ForgeMetadataMapper.toDiscoveredMods(
				ModsTomlParser.parse(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8))),
				"1.0.0", "examplemod.jar");

		assertEquals(1, mods.size());
		UnifiedDependency jei = mods.get(0).getDependencies().get(0);
		assertEquals("jei", jei.getModId());
		assertEquals(Ordering.AFTER, jei.getOrdering());
		assertEquals(SideScope.CLIENT, jei.getSideScope());
		assertTrue(jei.isSatisfiedBy("15.3.0"), "and the Maven range still translated into an evaluable predicate");
		assertFalse(jei.isSatisfiedBy("16.0.0"));
	}
}
