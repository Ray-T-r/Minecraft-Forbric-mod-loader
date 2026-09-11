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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the hub every "is mod X installed" question lands on.
 *
 * <p>It had no test of its own, and it is the worst possible place for that: it has no warning or error exit at
 * all, because every answer it can give is a well-formed boolean. A wrong one does not throw and does not log —
 * the mod that asked takes its other branch and the feature is simply invisible, which is exactly how Physics
 * Mod's debris and ragdolls went missing next to a live Fabric Sodium.
 */
class ModPresenceTest {
	@BeforeEach
	@AfterEach
	void clearTheStaticRegistry() {
		System.clearProperty(ModPresence.SWITCH);
		ModPresence.publishForgeFamily(List.of());
		ModPresence.publishFabric(List.of());
	}

	@Test
	void aModIsVisibleWhicheverEcosystemPublishedIt() {
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.NEOFORGE, "jade")));
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "sodium")));

		assertTrue(ModPresence.isLoaded("jade"));
		assertTrue(ModPresence.isLoaded("sodium"), "the whole point: the other family's mod answers yes");
		assertFalse(ModPresence.isLoaded("notinstalled"));
	}

	/**
	 * The two publishes are independent and arrive at different points in the boot. If the second one REPLACED the
	 * index instead of merging into it, the first ecosystem's mods would silently disappear — a registry that is
	 * correct for one instant and then quietly wrong, which reads exactly like the bug it exists to fix.
	 */
	@Test
	void publishingOneSideDoesNotEraseTheOther() {
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.NEOFORGE, "jade")));
		assertTrue(ModPresence.isLoaded("jade"));

		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "sodium")));
		assertTrue(ModPresence.isLoaded("jade"), "the Forge-family publish must survive the Fabric one");
		assertTrue(ModPresence.isLoaded("sodium"));

		// And re-publishing a side replaces only that side.
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.FORGE, "journeymap")));
		assertFalse(ModPresence.isLoaded("jade"), "that side was replaced");
		assertTrue(ModPresence.isLoaded("journeymap"));
		assertTrue(ModPresence.isLoaded("sodium"), "the other side was not");
	}

	/**
	 * The negative control. gate-m18 runs the same instance with the switch off and asserts the compatibility
	 * branch flips back — a branch that is supposed to flip has to be shown flipping, so "off" must restore
	 * per-ecosystem blindness completely, lists included.
	 */
	@Test
	void theSwitchRestoresPerEcosystemBlindness() {
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.NEOFORGE, "jade")));
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "sodium")));

		System.setProperty(ModPresence.SWITCH, "off");
		assertFalse(ModPresence.isLoaded("sodium"));
		assertFalse(ModPresence.isLoaded("jade"));
		assertEquals(List.of(), ModPresence.fabricMods());
		assertEquals(List.of(), ModPresence.forgeFamilyMods());

		System.setProperty(ModPresence.SWITCH, "on");
		assertTrue(ModPresence.isLoaded("sodium"), "and back on again, in the same JVM");
	}

	/**
	 * Called from injected game bytecode, where an exception would surface as a crash inside someone else's mod.
	 * "A presence check that can fail is worse than one that says no."
	 */
	@Test
	void isLoadedAnswersRatherThanThrowingForEveryShapeOfNothing() {
		assertFalse(ModPresence.isLoaded(null));
        assertFalse(ModPresence.isLoaded(""), "no publish has happened yet either");

		ModPresence.publishForgeFamily(null);
		ModPresence.publishFabric(null);
		assertFalse(ModPresence.isLoaded("anything"));
	}

	/**
	 * A mod with no usable id contributes nothing, and the same id from both sides is still one id.
	 *
	 * <p>A {@code null} ENTRY is dropped rather than thrown on. It used to throw: {@code List.copyOf} rejects
	 * nulls, so {@code add}'s null guard could never run, and since both callers publish inside a
	 * {@code catch (Throwable)} that degrades to "no presence at all", one null would have cost every
	 * cross-ecosystem answer.
	 */
	@Test
	void blankIdsAreDroppedAndDuplicatesCollapse() {
		List<DiscoveredMod> forge = new ArrayList<>();
		forge.add(mod(Ecosystem.NEOFORGE, "sodium"));
		forge.add(mod(Ecosystem.NEOFORGE, "  "));
		forge.add(mod(Ecosystem.NEOFORGE, null));
		forge.add(null);
		ModPresence.publishForgeFamily(forge);
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "sodium")));

		assertTrue(ModPresence.isLoaded("sodium"));
		assertFalse(ModPresence.isLoaded("  "));
		assertTrue(ModPresence.summary().contains("3 Forge-family + 1 Fabric"),
				"a null entry is dropped at the publish, not counted and not thrown on: " + ModPresence.summary());
	}

	/** The lists are what the seeders read; they must survive publication unchanged and unmodifiable. */
	@Test
	void thePublishedListsAreReadBackAsGiven() {
		DiscoveredMod sodium = mod(Ecosystem.FABRIC, "sodium");
		List<DiscoveredMod> given = new ArrayList<>(List.of(sodium));
		ModPresence.publishFabric(given);

		given.clear(); // the caller's list must not be the registry's
		assertEquals(1, ModPresence.fabricMods().size());
		assertEquals("sodium", ModPresence.fabricMods().get(0).getId());
	}

	private static DiscoveredMod mod(Ecosystem ecosystem, String id) {
		return new DiscoveredMod(ecosystem, id, "1.0.0", id, List.of(), List.of(), null, id + ".jar");
	}
}
