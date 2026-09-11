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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.service.IPropertyKey;

/**
 * Covers Mixin's blackboard.
 *
 * <p>One invariant carries this class, and it is not visible from the code that uses it: a key resolved twice must
 * address the same slot. Mixin resolves its keys independently in different components — the transformer stores
 * {@code mixin.initialised}, the environment reads it back from its own {@code resolveKey} call — so a key whose
 * identity came from the OBJECT rather than the name would give every reader an empty blackboard. Nothing would
 * throw: Mixin would simply re-initialise, re-select configs, and behave as if it had never run.
 */
class ForbricGlobalPropertyServiceTest {
	@Test
	void aKeyResolvedTwiceAddressesTheSameSlot() {
		ForbricGlobalPropertyService service = new ForbricGlobalPropertyService();
		service.setProperty(service.resolveKey("mixin.initialised"), "yes");

		assertEquals("yes", service.getProperty(service.resolveKey("mixin.initialised")),
				"a second resolveKey for the same name must reach what the first one stored");
	}

	@Test
	void differentNamesDoNotShareASlot() {
		ForbricGlobalPropertyService service = new ForbricGlobalPropertyService();
		service.setProperty(service.resolveKey("a"), 1);
		service.setProperty(service.resolveKey("b"), 2);

		assertEquals(Integer.valueOf(1), service.<Integer>getProperty(service.resolveKey("a")));
		assertEquals(Integer.valueOf(2), service.<Integer>getProperty(service.resolveKey("b")));
	}

	@Test
	void defaultsApplyOnlyWhenNothingWasStored() {
		ForbricGlobalPropertyService service = new ForbricGlobalPropertyService();
		IPropertyKey key = service.resolveKey("side");

		assertEquals("CLIENT", service.getProperty(key, "CLIENT"));
		assertEquals("fallback", service.getPropertyString(key, "fallback"));

		service.setProperty(key, "SERVER");
		assertEquals("SERVER", service.getProperty(key, "CLIENT"));
		assertEquals("SERVER", service.getPropertyString(key, "fallback"));
	}

	/**
	 * Storing {@code null} removes the entry rather than parking a null in the map, so a later
	 * {@code getProperty(key, default)} still yields the default. A stored null would make the default
	 * unreachable — Mixin reads several of its switches that way.
	 */
	@Test
	void storingNullClearsTheSlotRatherThanParkingANull() {
		ForbricGlobalPropertyService service = new ForbricGlobalPropertyService();
		IPropertyKey key = service.resolveKey("switch");
		service.setProperty(key, "on");
		service.setProperty(key, null);

		assertNull(service.getProperty(key));
		assertEquals("default", service.getProperty(key, "default"));
	}

	@Test
	void aNonStringValueStillRendersForGetPropertyString() {
		ForbricGlobalPropertyService service = new ForbricGlobalPropertyService();
		service.setProperty(service.resolveKey("count"), 7);
		assertEquals("7", service.getPropertyString(service.resolveKey("count"), "none"));
	}
}
