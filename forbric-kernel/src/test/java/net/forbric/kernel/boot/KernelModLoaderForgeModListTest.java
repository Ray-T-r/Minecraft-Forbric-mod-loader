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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Covers the two halves of getting MinecraftForge's {@code ModList} right: WHEN it is written, and that an empty
 * write is a real write.
 *
 * <p>{@code setLoadedMods} REPLACES the list, so the only way to take a failed mod's container back out is to
 * write the survivors — and when there are no survivors, skipping the write leaves the full, wrong list standing.
 * The old code had two separate "is it empty? then do nothing" guards, and a fix that patched only the first one
 * would look right and write nothing.
 */
class KernelModLoaderForgeModListTest {

	private static final class Writer implements KernelModLoader.ForgeListWriter {
		List<Object> wrote;
		int calls;

		@Override
		public void write(List<Object> containers) {
			calls++;
			wrote = new ArrayList<>(containers);
		}
	}

	@Test
	void aNonEmptyListIsWrittenInOrder() throws Exception {
		Writer w = new Writer();
		assertTrue(KernelModLoader.publishForgeContainers(List.of("libraryferret", "dungeons"), false, w));
		assertEquals(List.of("libraryferret", "dungeons"), w.wrote);
	}

	@Test
	void anEmptyListIsNotWrittenOnTheNormalPath() throws Exception {
		Writer w = new Writer();
		assertFalse(KernelModLoader.publishForgeContainers(List.of(), false, w));
		assertEquals(0, w.calls, "publishing nothing at boot must not clear a list somebody else owns");
	}

	@Test
	void anEmptyListIsWrittenOnTheWithdrawalPath() throws Exception {
		Writer w = new Writer();
		assertTrue(KernelModLoader.publishForgeContainers(List.of(), true, w),
				"every MinecraftForge mod failing must publish an EMPTY list — skipping the write leaves each "
						+ "failed mod's container in ModList, handing out a bus nothing will ever fire on");
		assertEquals(List.of(), w.wrote);
	}

	/**
	 * MinecraftForge's {@code setLoadedMods} is {@code static} where NeoForge's twin is an instance method, and it
	 * REPLACES rather than appends. Driven through the real reflective shape so the null receiver is exercised.
	 */
	@Test
	void theReflectiveWriteReplacesRatherThanAppends() throws Exception {
		Method setLoadedMods = FakeForgeModList.class.getDeclaredMethod("setLoadedMods", List.class);
		setLoadedMods.setAccessible(true);
		KernelModLoader.ForgeListWriter writer = list -> setLoadedMods.invoke(null, list);

		KernelModLoader.publishForgeContainers(List.of("a", "b"), false, writer);
		assertEquals(List.of("a", "b"), FakeForgeModList.mods);
		KernelModLoader.publishForgeContainers(List.of("a"), true, writer);
		assertEquals(List.of("a"), FakeForgeModList.mods, "the second write must replace the first, not add to it");
	}

	/** Stand-in for {@code net.minecraftforge.fml.ModList}: package-private STATIC setter, exactly as shipped. */
	static final class FakeForgeModList {
		static List<Object> mods = List.of();

		static void setLoadedMods(List<Object> containers) {
			mods = List.copyOf(containers);
		}
	}

	@Test
	void oneHandlePerModIdAndTheFactoryIsAskedOnlyOnce() {
		AtomicInteger calls = new AtomicInteger();
		var claimed = List.of(
				info("com.a.Main", "libraryferret", net.forbric.api.Ecosystem.FORGE),
				info("com.a.Client", "libraryferret", net.forbric.api.Ecosystem.FORGE),
				info("com.b.Main", "awesomedungeonocean", net.forbric.api.Ecosystem.FORGE));

		var handles = KernelModLoader.buildForgeHandles(claimed, id -> {
			calls.incrementAndGet();
			return new KernelForgeModContext.Handle(id, new Object(), new Object(), new Object());
		});

		assertEquals(List.of("libraryferret", "awesomedungeonocean"), new ArrayList<>(handles.keySet()),
				"one handle per id, in scan order");
		assertEquals(2, calls.get(),
				"the factory must not be asked twice for one id — ModList.setLoadedMods indexes by mod id and "
						+ "throws \"Duplicate key\", and each extra call also manufactures a second BusGroup under "
						+ "the same name");
	}

	@Test
	void neoForgeFamilyEntriesAreNotGivenAMinecraftForgeContext() {
		var handles = KernelModLoader.buildForgeHandles(
				List.of(info("com.n.Main", "bookshelf", net.forbric.api.Ecosystem.NEOFORGE)),
				id -> {
					throw new AssertionError("a NeoForge mod must not be handed a MinecraftForge loading context");
				});
		assertTrue(handles.isEmpty());
	}

	@Test
	void oneModsFailureDoesNotCostTheOthersTheirContainer() {
		var handles = KernelModLoader.buildForgeHandles(
				List.of(info("com.a.Main", "broken", net.forbric.api.Ecosystem.FORGE),
						info("com.b.Main", "fine", net.forbric.api.Ecosystem.FORGE)),
				id -> {
					if ("broken".equals(id)) throw new IllegalStateException("no javafmlmod");
					return new KernelForgeModContext.Handle(id, new Object(), new Object(), new Object());
				});
		assertEquals(List.of("fine"), new ArrayList<>(handles.keySet()));
	}

	/** A {@code @Mod} with no declared id keys on its class name, so two of them do not collide. */
	@Test
	void anIdlessModKeysOnItsClassName() {
		var handles = KernelModLoader.buildForgeHandles(
				List.of(info("com.a.Main", null, net.forbric.api.Ecosystem.FORGE),
						info("com.b.Main", null, net.forbric.api.Ecosystem.FORGE)),
				id -> new KernelForgeModContext.Handle(id, new Object(), new Object(), new Object()));
		assertEquals(List.of("com.a.Main", "com.b.Main"), new ArrayList<>(handles.keySet()));
	}

	private static net.forbric.kernel.discovery.ModAnnotationScanner.ModClassInfo info(
			String className, String modId, net.forbric.api.Ecosystem family) {
		return new net.forbric.kernel.discovery.ModAnnotationScanner.ModClassInfo(className, modId, family);
	}
}
