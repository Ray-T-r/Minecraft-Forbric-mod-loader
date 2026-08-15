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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link KernelModLoader#fillModInfos} — the write that makes NeoForge's {@code ModList.getMods()} answer
 * with the mods the kernel loaded.
 *
 * <p>The real {@code ModList} is not on the test classpath, so these drive stand-in types with the SHAPE that
 * matters: a {@code private final List sortedList} field (final is the whole point — {@code setLoadedMods} cannot
 * touch it, which is why {@code getMods()} was empty while {@code getModContainerById} worked) and containers
 * exposing a public {@code getModInfo()}. What that does not prove is the field NAME on the live NeoForge class;
 * that is pinned by the boot log line and by gate-m7-neo actually running NeoForge mods.
 */
class KernelModLoaderModInfosTest {

	/** Stand-in for {@code net.neoforged.fml.ModList} — only the field being written has to match. */
	static final class FakeModList {
		private final List<Object> sortedList;

		FakeModList(List<Object> sortedList) {
			this.sortedList = sortedList;
		}
	}

	/** Stand-in for {@code net.neoforged.fml.ModContainer}. */
	public static class FakeContainer {
		private final Object modInfo;

		FakeContainer(Object modInfo) {
			this.modInfo = modInfo;
		}

		public Object getModInfo() {
			return modInfo;
		}
	}

	private static Method getModInfo() throws Exception {
		return FakeContainer.class.getMethod("getModInfo");
	}

	@Test
	void fillsTheFinalSortedListFieldInContainerOrder() throws Exception {
		FakeModList list = new FakeModList(List.of());
		Object sodium = "sodium-info";
		Object iris = "iris-info";

		KernelModLoader.fillModInfos(FakeModList.class, list,
				List.of(new FakeContainer(sodium), new FakeContainer(iris)), getModInfo());

		assertEquals(List.of(sodium, iris), list.sortedList, "getMods() must answer in publication order");
	}

	@Test
	void writesThroughFinalOverAnImmutableSeededList() throws Exception {
		// PassiveSeeder seeds the singleton with ModList.of(List.of(), List.of()), so the field starts out holding
		// an IMMUTABLE empty list. The write must replace the reference, never try to mutate what is there.
		FakeModList list = new FakeModList(List.of());
		List<Object> before = list.sortedList;

		KernelModLoader.fillModInfos(FakeModList.class, list, List.of(new FakeContainer("a")), getModInfo());

		assertEquals(1, list.sortedList.size());
		assertTrue(before != list.sortedList, "the immutable seeded list must be replaced, not mutated");
	}

	@Test
	void skipsAContainerWithNoModInfo() throws Exception {
		// Every consumer of getMods() dereferences getModId() off each entry; a null would NPE the whole walk and
		// take out mods that have nothing to do with the broken container.
		FakeModList list = new FakeModList(List.of());

		KernelModLoader.fillModInfos(FakeModList.class, list,
				List.of(new FakeContainer(null), new FakeContainer("real")), getModInfo());

		assertEquals(List.of("real"), list.sortedList);
	}

	@Test
	void zeroContainersLeavesAnEmptyListRatherThanNull() throws Exception {
		FakeModList list = new FakeModList(null);

		KernelModLoader.fillModInfos(FakeModList.class, list, List.of(), getModInfo());

		assertEquals(List.of(), list.sortedList, "getMods() must never return null");
	}

	@Test
	void aMissingFieldFailsLoudlyToTheCaller() {
		// publishModInfos catches this and warns; fillModInfos itself must not swallow it, or a NeoForge version
		// that renamed the field would look like a successful publish.
		Object notAModList = new Object();

		assertThrows(NoSuchFieldException.class, () -> KernelModLoader.fillModInfos(
				Object.class, notAModList, List.of(), getModInfo()));
	}

	@Test
	void theWrittenListIsTheOneTheContainersReported() throws Exception {
		// Guards against a future refactor that rebuilds infos from ids instead of asking the containers: the
		// container's own IModInfo is the object mods compare by identity (ModContainer.getModInfo() == entry).
		Object info = new Object();
		FakeModList list = new FakeModList(List.of());

		KernelModLoader.fillModInfos(FakeModList.class, list, List.of(new FakeContainer(info)), getModInfo());

		assertSame(info, list.sortedList.get(0));
	}
}
