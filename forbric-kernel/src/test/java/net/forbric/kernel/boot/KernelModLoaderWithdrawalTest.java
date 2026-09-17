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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link KernelModLoader#keepConstructed} — which containers stay in the mod list after construction.
 *
 * <p>A container left standing for a mod whose constructor threw passes {@code instanceof} and hands out an
 * event bus that nothing will ever post to, so a library mod resolving it registers into nothing and the failure
 * surfaces later somewhere that names neither mod. The MinecraftForge half withdrew; the NeoForge half did not,
 * and both now go through this.
 */
class KernelModLoaderWithdrawalTest {

	private static Map<String, String> published(String... ids) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String id : ids) out.put(id, "container:" + id);
		return out;
	}

	@Test
	void keepsOnlyWhatConstructed() {
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(
				published("jei", "broken", "jade"), Set.of("jei", "jade"), dropped);

		assertEquals(List.of("jei", "jade"), List.copyOf(kept.keySet()));
		assertEquals(List.of("broken"), dropped);
	}

	@Test
	void keepsPublicationOrder() {
		// The list is handed to setLoadedMods, and order is what decides which mod's registrations run first.
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(
				published("zeta", "alpha", "mid"), Set.of("zeta", "alpha", "mid"), dropped);

		assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(kept.keySet()));
		assertTrue(dropped.isEmpty());
	}

	@Test
	void everyModFailingLeavesNothingStanding() {
		// The case the allowEmpty flag exists for: an empty result must be published, not treated as "no change".
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(published("a", "b"), Set.of(), dropped);

		assertTrue(kept.isEmpty());
		assertEquals(List.of("a", "b"), dropped);
	}

	@Test
	void anIdThatConstructedButWasNeverPublishedChangesNothing() {
		// Presence aliases construct nothing and are re-added by the caller, never carried in here; a stray id in
		// the constructed set must not invent an entry.
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(
				published("jei"), Set.of("jei", "ghost"), dropped);

		assertEquals(List.of("jei"), List.copyOf(kept.keySet()));
		assertTrue(dropped.isEmpty());
	}

	@Test
	void theKeptValuesAreTheOnesThatWerePublished() {
		// Identity matters: the value is the live container, and rebuilding one instead of carrying it across
		// would hand mods a second container for the same mod.
		Map<String, String> input = published("jei");
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(input, Set.of("jei"), dropped);

		assertEquals(input.get("jei"), kept.get("jei"));
	}
}
