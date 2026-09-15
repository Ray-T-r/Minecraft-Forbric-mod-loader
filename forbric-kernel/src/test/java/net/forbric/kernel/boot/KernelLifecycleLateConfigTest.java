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

import net.forbric.api.Side;

import org.junit.jupiter.api.Test;

/**
 * Pins which config types the late-open pass may touch.
 *
 * <p>The pass exists because a Fabric mod registering a config from a client entrypoint is too late for the early
 * pass, and nothing else opens a non-STARTUP config — so the mod reads a config that was registered and never
 * loaded, and gets an exception rather than a default.
 *
 * <p>SERVER's absence is the part worth a test. A SERVER config is per-world and the server-about-to-start hook
 * loads it from the world directory; opening one here would load it from the global config directory first, and
 * the carrier's warning for the collision is asserted ABSENT by gate-m13 and gate-m14. Nothing else states that
 * constraint where someone adding a type would see it.
 */
class KernelLifecycleLateConfigTest {

	@Test
	void neitherSideEverOpensAServerConfigLate() {
		for (Side side : Side.values()) {
			assertFalse(KernelLifecycle.lateConfigTypes(side).contains("SERVER"),
					side + ": a SERVER config is per-world and belongs to the server-about-to-start hook. Opening "
							+ "it from the global config directory here makes the carrier overwrite it a moment "
							+ "later, which is what \"Overwriting non-null config\" means");
		}
	}

	@Test
	void theClientAlsoOpensItsOwnType() {
		assertEquals(List.of("STARTUP", "COMMON", "CLIENT"), KernelLifecycle.lateConfigTypes(Side.CLIENT));
	}

	@Test
	void aDedicatedServerHasNoClientConfigToOpen() {
		List<String> types = KernelLifecycle.lateConfigTypes(Side.DEDICATED_SERVER);
		assertEquals(List.of("STARTUP", "COMMON"), types);
		assertTrue(!types.contains("CLIENT"));
	}

	/** The late pass must not widen past the early one, or the two disagree about what "already loaded" means. */
	@Test
	void theLatePassCoversExactlyWhatTheEarlyPassCovers() {
		assertEquals(List.of("STARTUP", "COMMON", "CLIENT"), KernelLifecycle.lateConfigTypes(Side.CLIENT),
				"loadEarlyConfigs uses STARTUP+COMMON+CLIENT on the client; the late pass is a second sweep of the "
						+ "same set, skipping whatever is already open");
	}
}
