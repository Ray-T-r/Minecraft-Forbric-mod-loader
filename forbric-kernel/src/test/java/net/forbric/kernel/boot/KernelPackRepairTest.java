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

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The per-pack line: what mounted, what a vetoed overlay condition kept out, and that the veto ledger is consumed. */
class KernelPackRepairTest {
	@AfterEach
	void reset() {
		KernelPackRepair.resetForTests();
	}

	@Test
	void theLineNamesThePackTheMountedOverlaysAndTheVetoedOnes() {
		String line = KernelPackRepair.describe("terralith", List.of("enable.recipe_changes"),
				List.of("enable.vanilla_stone_gen ← terralith:config"));
		assertTrue(line.contains("pack 'terralith'"), line);
		assertTrue(line.contains("1 overlay(s) mounted [enable.recipe_changes]"), line);
		assertTrue(line.contains("NOT mounted: [enable.vanilla_stone_gen ← terralith:config]"), line);
		assertFalse(KernelPackRepair.describe("x", List.of("a"), List.of()).contains("NOT mounted"), "no veto, no suffix");
	}

	@Test
	void concatDrainsTheVetoLedgerSoTheNextPackStartsClean() {
		KernelPackRepair.overlayVetoed("enable.vanilla_stone_gen", "terralith:config");
		List<Object> merged = KernelPackRepair.concat(List.of("enable.recipe_changes"), List.of("fabric.extra"), new Location("terralith"));
		assertEquals(List.of("enable.recipe_changes", "fabric.extra"), merged, "the merge itself is unchanged");
		// The ledger was consumed by that concat: a second pack on the same thread sees nothing vetoed.
		assertEquals(List.of(), KernelPackRepair.drainVetoed(), "consumed by the concat that named the pack");
		KernelPackRepair.overlayVetoed("a", "t");
		assertEquals(List.of("a ← t"), KernelPackRepair.drainVetoed());
		assertEquals(List.of(), KernelPackRepair.drainVetoed(), "and a drain empties it");
		assertEquals(List.of(), KernelPackRepair.concat(List.of(), List.of()), "two-arg form still answers the merge");
	}

	@Test
	void theTwoArgFormKeepsTheOldContract() {
		assertEquals(List.of(1, 2, 3), KernelPackRepair.concat(List.of(1, 2), List.of(3)));
		assertEquals(List.of(), KernelPackRepair.concat(null, null));
	}

	/** Shaped like PackLocationInfo: a public {@code id()}. */
	public record Location(String id) {
	}
}
