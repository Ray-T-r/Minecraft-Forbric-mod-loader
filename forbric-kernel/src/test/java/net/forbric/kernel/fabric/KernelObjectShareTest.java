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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The inter-mod object share: the notify-on-publish contract is the part mods actually depend on. */
class KernelObjectShareTest {
	@Test
	void putAndGetRoundTripAndPutReturnsThePreviousValue() {
		KernelObjectShare share = new KernelObjectShare();

		assertNull(share.put("mod:key", "first"));
		assertEquals("first", share.get("mod:key"));
		assertEquals("first", share.put("mod:key", "second"));
		assertEquals("second", share.get("mod:key"));
		assertEquals("second", share.remove("mod:key"));
		assertNull(share.get("mod:key"));
	}

	@Test
	void putIfAbsentIsAtomicAndDoesNotOverwrite() {
		KernelObjectShare share = new KernelObjectShare();

		assertNull(share.putIfAbsent("mod:key", "first"));
		assertEquals("first", share.putIfAbsent("mod:key", "second"));
		assertEquals("first", share.get("mod:key"));
	}

	@Test
	void whenAvailableFiresImmediatelyForAValueAlreadyPresent() {
		KernelObjectShare share = new KernelObjectShare();
		share.put("mod:key", "value");

		List<String> seen = new ArrayList<>();
		share.whenAvailable("mod:key", (k, v) -> seen.add(k + "=" + v));

		assertEquals(List.of("mod:key=value"), seen);
	}

	@Test
	void whenAvailableFiresOnceOnLaterPublish() {
		KernelObjectShare share = new KernelObjectShare();
		List<String> seen = new ArrayList<>();

		share.whenAvailable("mod:key", (k, v) -> seen.add(k + "=" + v));
		assertTrue(seen.isEmpty(), "must not fire before the value exists");

		share.put("mod:key", "value");
		assertEquals(List.of("mod:key=value"), seen);

		// The request acts once: a later overwrite must not re-fire it.
		share.put("mod:key", "changed");
		assertEquals(1, seen.size());
	}

	@Test
	void putIfAbsentNotifiesWaitersOnlyWhenItActuallyStores() {
		KernelObjectShare share = new KernelObjectShare();
		List<Object> seen = new ArrayList<>();
		share.whenAvailable("mod:key", (k, v) -> seen.add(v));

		share.putIfAbsent("mod:key", "first");
		assertEquals(List.of("first"), seen);

		// A losing putIfAbsent stores nothing, so there is nothing to announce.
		share.putIfAbsent("mod:key", "second");
		assertEquals(1, seen.size());
	}

	@Test
	void keysMustBeNamespacedAndValuesNonNull() {
		KernelObjectShare share = new KernelObjectShare();

		assertThrows(IllegalArgumentException.class, () -> share.get("nocolon"));
		assertThrows(IllegalArgumentException.class, () -> share.put(":leading", "v"));
		assertThrows(IllegalArgumentException.class, () -> share.put("trailing:", "v"));
		assertThrows(NullPointerException.class, () -> share.put("mod:key", null));
		assertThrows(NullPointerException.class, () -> share.get(null));
	}
}
