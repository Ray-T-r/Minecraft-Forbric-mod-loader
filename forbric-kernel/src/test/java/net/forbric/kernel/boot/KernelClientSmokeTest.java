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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The one property that matters here beyond what gate-m9 already proves: a diagnostic must never be able to break
 * the thing it measures. The controller reaches everything reflectively against a class it cannot name, so it has
 * to survive being handed something that looks nothing like Minecraft.
 */
class KernelClientSmokeTest {
	@AfterEach
	void reset() {
		System.clearProperty(KernelClientSmoke.ENABLED);
		KernelClientSmoke.resetForTests();
	}

	@Test
	void offByDefaultAndOnlyOnWhenTheSwitchSaysSo() {
		assertFalse(KernelClientSmoke.enabled());
		System.setProperty(KernelClientSmoke.ENABLED, "true");
		assertTrue(KernelClientSmoke.enabled());
	}

	@Test
	void doesNothingAtAllWhileDisabled() {
		assertDoesNotThrow(() -> KernelClientSmoke.onClientTick(new Object()));
	}

	@Test
	void survivesAnObjectThatIsNothingLikeMinecraft() {
		System.setProperty(KernelClientSmoke.ENABLED, "true");
		// No level, no player, no stop() — every lookup misses. It must return quietly, not throw into the tick.
		assertDoesNotThrow(() -> KernelClientSmoke.onClientTick(new Object()));
		assertDoesNotThrow(() -> KernelClientSmoke.onClientTick("not a game"));
	}

	@Test
	void aNullTickIsIgnored() {
		System.setProperty(KernelClientSmoke.ENABLED, "true");
		assertDoesNotThrow(() -> KernelClientSmoke.onClientTick(null));
	}
}
