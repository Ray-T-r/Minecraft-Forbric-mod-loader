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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-text pins on the runtime shim, in the {@code KernelGameTickEventsTest} shape: the runtime set compiles
 * only against the staged game jars, which are not on every machine, but the FILE always is. What matters is
 * ORDER and the one guard — NeoForge's hook first, its {@code null} respected, Fabric only for a survivor.
 */
class KernelLootBridgeTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelLootBridge.java");

	private static String bodyOf(String signature) throws Exception {
		String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
		int start = source.indexOf(signature);
		assertTrue(start >= 0, signature + " is gone from " + SOURCE + " — the injector routes a call to it by name and "
				+ "descriptor, so its absence is a NoSuchMethodError at the first datapack load, not a compile error");
		int end = source.indexOf("\n\t}", start);
		assertTrue(end > start, "could not find the end of " + signature);
		return source.substring(start, end);
	}

	@Test
	void neoForgesHookRunsFirstAndItsNullDropsTheTableBeforeFabricIsAsked() throws Exception {
		String body = bodyOf("public static LootTable loadLootTable(HolderLookup.Provider provider, Identifier id, LootTable table)");
		int neo = body.indexOf("EventHooks.loadLootTable(");
		int guard = body.indexOf("if (neo == null) return null;");
		int fabric = body.indexOf("LootTableEventDispatch.afterLoad(");
		assertTrue(neo >= 0, "NeoForge's own hook must be called, not re-implemented");
		assertTrue(guard > neo, "a null from NeoForge (EMPTY, or a cancelled event) must drop the table exactly as before");
		assertTrue(fabric > guard, "Fabric is offered only a survivor");
		assertTrue(body.contains("Registries.LOOT_TABLE"), "the ResourceKey is built for the LOOT_TABLE registry");
	}

	@Test
	void tagsLoadFirstAndAllLoadedFiresOnlyForTheLootTableRegistry() throws Exception {
		String body = bodyOf("public static void loadTagsForRegistry(ResourceManager resources, WritableRegistry<?> registry)");
		int tags = body.indexOf("TagLoader.loadTagsForRegistry(");
		int key = body.indexOf("Registries.LOOT_TABLE.equals(registry.key())");
		int all = body.indexOf("LootTableEventDispatch.allLoaded(");
		assertTrue(tags >= 0, "vanilla's tag load must still run");
		assertTrue(key > tags && all > key, "ALL_LOADED fires after the tags load, and only for the loot-table registry");
	}
}
