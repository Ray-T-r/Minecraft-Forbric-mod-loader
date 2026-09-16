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
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * One MinecraftForge mod's {@code RegisterEvent} listener must not cost every other mod its registration.
 *
 * <p>EventBus 7's {@code post} has no exception table, so a {@code DeferredRegister} supplier that throws — an
 * unbound cross-registry {@code RegistryObject}, a config value read before its spec loaded, a
 * {@code NoClassDefFoundError} out of a bundled dependency — propagated out of the whole dispatch. The only
 * catch was the caller's, one for everything: a single WARN naming neither the mod nor the registry, after which
 * every mod later in the list, every remaining registry for all of them, and the ForgeMod baseline's own content
 * had silently not registered. It surfaced six layers away as "Registry Object not present: minecraft:empty" or a
 * player kicked with "Invalid player data".
 *
 * <p>The NeoForge twin has been isolated per bus since BUG 14. This is the same guarantee for the other family.
 */
class KernelForgeRegisterEventIsolationTest {
	/** {container, bus, post, modId} in the shape fireRegisterEvents builds. Only the id is read here. */
	private static Object[] mod(String id) {
		return new Object[] {"container:" + id, "bus:" + id, "post:" + id, id};
	}

	/** {key, vanillaRegistry, forgeRegistry} in the shape registerEventTargets builds. */
	private static Object[] registry(String key) {
		return new Object[] {key, "vanilla:" + key, "forge:" + key};
	}

	/** {@code List.of} on a single {@code Object[]} is a varargs trap, so rows are collected explicitly. */
	private static List<Object[]> rows(Object[]... entries) {
		List<Object[]> out = new ArrayList<>();
		for (Object[] entry : entries) out.add(entry);
		return out;
	}

	@Test
	void aModWhoseListenerThrowsCostsOnlyItself() {
		List<Object[]> registries = rows(registry("minecraft:block"), registry("minecraft:item"),
				registry("minecraft:creative_mode_tab"));
		List<Object[]> mods = rows(mod("forge"), mod("biomesoplenty"), mod("macawsbridges"));

		List<String> delivered = new ArrayList<>();
		int attempted = KernelForgeModContext.dispatchIsolated(registries, mods, m -> { },
				(m, target) -> {
					if ("biomesoplenty".equals(m[3])) throw new IllegalStateException("unbound RegistryObject");
					delivered.add(m[3] + "@" + target[0]);
				});

		assertEquals(9, attempted, "every (registry, mod) pair must still be attempted");
		assertEquals(6, delivered.size(),
				"the two healthy mods keep all three registries; only the failing mod loses its registrations");
		assertTrue(delivered.contains("forge@minecraft:creative_mode_tab"),
				"the ForgeMod baseline must still register after another mod threw — losing it is how "
						+ "'Registry Object not present: minecraft:empty' happens");
		assertTrue(delivered.contains("macawsbridges@minecraft:creative_mode_tab"),
				"a mod that sorts AFTER the failing one must still get every registry");
	}

	/**
	 * Registry-major, mod-minor. A {@code DeferredRegister}'s {@code RegistryObject}s bind during their OWN
	 * registry's event, so every mod must fire for BLOCK before any fires for ITEM — a mod registering an item
	 * that names another mod's block depends on it.
	 */
	@Test
	void theOrderStaysRegistryMajor() {
		List<Object[]> registries = rows(registry("block"), registry("item"));
		List<Object[]> mods = rows(mod("a"), mod("b"));

		List<String> order = new ArrayList<>();
		KernelForgeModContext.dispatchIsolated(registries, mods, m -> { },
				(m, target) -> order.add(target[0] + "/" + m[3]));

		assertEquals(List.of("block/a", "block/b", "item/a", "item/b"), order,
				"all mods fire for one registry before the next registry begins");
	}

	/** The container has to be active for the post, or the mod's id-less registrations land under someone else. */
	@Test
	void eachModIsMadeActiveBeforeItIsPostedTo() {
		List<Object[]> registries = rows(registry("block"));
		List<Object[]> mods = rows(mod("a"), mod("b"));

		List<String> trace = new ArrayList<>();
		KernelForgeModContext.dispatchIsolated(registries, mods,
				m -> trace.add("active:" + m[3]),
				(m, target) -> trace.add("post:" + m[3]));

		assertEquals(List.of("active:a", "post:a", "active:b", "post:b"), trace,
				"RegisterHelper.register(String, T) namespaces by the ACTIVE container, so it must be set for each "
						+ "post rather than once for the pass");
	}

	/** A mod that fails on one registry usually fails on the next twenty; the dispatch must not give up on it. */
	@Test
	void aModThatFailsEverywhereIsStillOfferedEveryRegistry() {
		List<Object[]> registries = rows(registry("a"), registry("b"), registry("c"));
		List<Object[]> mods = rows(mod("broken"));

		List<String> offered = new ArrayList<>();
		int attempted = KernelForgeModContext.dispatchIsolated(registries, mods, m -> { },
				(m, target) -> {
					offered.add(String.valueOf(target[0]));
					throw new IllegalStateException("still broken");
				});

		assertEquals(3, attempted);
		assertEquals(List.of("a", "b", "c"), offered,
				"a mod that throws on one registry may still be able to register into the next");
	}
}
