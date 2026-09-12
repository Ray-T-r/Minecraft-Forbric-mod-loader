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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the mod record the whole API passes around.
 *
 * <p>It is a value type with no behaviour to speak of, which is exactly why the only things worth asserting are
 * the promises its getters make: that the lists are unmodifiable, that they are not a window onto the caller's
 * list, and that a missing one reads as empty rather than null. It is published into {@link ModPresence} and read
 * from several threads during boot, so those are load-bearing rather than decorative.
 */
class DiscoveredModTest {
	/**
	 * {@code Collections.unmodifiableList} returns a VIEW, not a copy — the caller keeps the backing list, so a
	 * record built from a list that is later added to reports members it was never constructed with. Nothing does
	 * that today; "unmodifiable" is still a promise a caller is entitled to rely on.
	 */
	@Test
	void theListsAreCopiesNotWindowsOntoTheCallersList() {
		List<String> mixins = new ArrayList<>(List.of("example.mixins.json"));
		DiscoveredMod mod = new DiscoveredMod(Ecosystem.NEOFORGE, "example", "1.0.0", "Example",
				List.of(), mixins, null, "example.jar");

		mixins.add("sneaked-in.mixins.json");
		assertEquals(List.of("example.mixins.json"), mod.getMixinConfigs());

		mixins.clear();
		assertEquals(1, mod.getMixinConfigs().size(), "and clearing it must not empty the record either");
	}

	@Test
	void theListsCannotBeModifiedThroughTheGetter() {
		DiscoveredMod mod = new DiscoveredMod(Ecosystem.FABRIC, "example", "1.0.0", "Example",
				List.of(new UnifiedDependency("sodium", "*", true)), List.of("a.json"), null,
				List.of("accesstransformer.cfg"), "example.jar");

		assertThrows(UnsupportedOperationException.class, () -> mod.getDependencies().clear());
		assertThrows(UnsupportedOperationException.class, () -> mod.getMixinConfigs().clear());
		assertThrows(UnsupportedOperationException.class, () -> mod.getAccessTransformers().clear());
	}

	/**
	 * An absent list reads as empty, never null: every caller iterates these, and one of them
	 * ({@link ModPresence}) publishes records built by three different code paths.
	 */
	@Test
	void anAbsentListReadsAsEmptyRatherThanNull() {
		DiscoveredMod mod = new DiscoveredMod(Ecosystem.FORGE, "example", "1.0.0", "Example",
				null, null, null, null, "example.jar");

		assertTrue(mod.getDependencies().isEmpty());
		assertTrue(mod.getMixinConfigs().isEmpty());
		assertTrue(mod.getAccessTransformers().isEmpty());
	}

	/**
	 * A null ELEMENT is dropped rather than thrown on, for the reason {@code ModPresence.usable} records: a
	 * throwing constructor fails inside a caller that degrades to "no mods at all", so one bad element would cost
	 * the whole list instead of itself.
	 */
	@Test
	void aNullElementCostsItselfAndNotTheWholeList() {
		DiscoveredMod mod = new DiscoveredMod(Ecosystem.NEOFORGE, "example", "1.0.0", "Example",
				List.of(), Arrays.asList("a.json", null, "b.json"), null, "example.jar");

		assertEquals(List.of("a.json", "b.json"), mod.getMixinConfigs());
	}
}
