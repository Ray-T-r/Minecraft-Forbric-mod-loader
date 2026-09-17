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
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link KernelModLoader#fillFileById} — the write that makes {@code ModList.getModFileById(id)} answer.
 *
 * <p>{@code javap} on NeoForge's {@code ModList} shows {@code getModFileById} is {@code fileById.get(id)} plus a
 * checkcast, and the kernel wrote that map for the baseline alone. Every other mod's id answered null, so a mod
 * resolving its own file by id NPE'd. These drive stand-ins with the shape that matters: a {@code fileById} map
 * field, containers with {@code getModInfo()}, and infos with {@code getModId()}/{@code getOwningFile()}.
 */
class KernelModLoaderFileByIdTest {

	/** Stand-in for {@code net.neoforged.fml.ModList}. */
	static final class FakeModList {
		private Map<String, Object> fileById;

		FakeModList(Map<String, Object> fileById) {
			this.fileById = fileById;
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

	/** Stand-in for {@code net.neoforged.neoforgespi.language.IModInfo}. */
	public static class FakeInfo {
		private final String id;
		private final Object file;

		FakeInfo(String id, Object file) {
			this.id = id;
			this.file = file;
		}

		public String getModId() {
			return id;
		}

		public Object getOwningFile() {
			return file;
		}
	}

	/** An info whose accessor throws, the way a half-built container's would. */
	public static class AngryInfo extends FakeInfo {
		AngryInfo() {
			super("angry", null);
		}

		@Override
		public Object getOwningFile() {
			throw new IllegalStateException("no file here");
		}
	}

	private static int fill(FakeModList list, List<Object> containers) throws Exception {
		Method getModInfo = FakeContainer.class.getMethod("getModInfo");
		Method getModId = FakeInfo.class.getMethod("getModId");
		Method getOwningFile = FakeInfo.class.getMethod("getOwningFile");
		return KernelModLoader.fillFileById(FakeModList.class, list, containers, getModInfo, getModId, getOwningFile);
	}

	private static Object container(String id, Object file) {
		return new FakeContainer(new FakeInfo(id, file));
	}

	@Test
	void everyLoadedModAnswersByItsOwnId() throws Exception {
		FakeModList list = new FakeModList(Map.of());
		Object sodiumFile = new Object();
		Object irisFile = new Object();

		int added = fill(list, List.of(container("sodium", sodiumFile), container("iris", irisFile)));

		assertEquals(2, added);
		assertSame(sodiumFile, list.fileById.get("sodium"));
		assertSame(irisFile, list.fileById.get("iris"),
				"getModFileById must answer with the mod's OWN file, not a shared one");
	}

	@Test
	void keepsWhatAnEarlierPassAlreadyPublished() throws Exception {
		// Two passes publish containers — the mod-loader one and the mod-bus one — and the second must not drop the
		// first's entries. The baseline "neoforge" entry is the one that matters: the title screen's version check
		// reads it, and losing it takes out the main menu.
		Object baselineFile = new Object();
		FakeModList list = new FakeModList(Map.of("neoforge", baselineFile));

		fill(list, List.of(container("sodium", new Object())));

		assertSame(baselineFile, list.fileById.get("neoforge"));
		assertTrue(list.fileById.containsKey("sodium"));
	}

	@Test
	void replacesAnImmutableMapRatherThanMutatingIt() throws Exception {
		// The seeded map can be immutable, so the write has to swap the reference.
		FakeModList list = new FakeModList(Map.of());
		Map<String, Object> before = list.fileById;

		fill(list, List.of(container("sodium", new Object())));

		assertTrue(before != list.fileById);
	}

	@Test
	void oneBadContainerDoesNotCostTheOthersTheirEntry() throws Exception {
		// The point of the per-container catch: a mod with a half-built container must not be what makes
		// getModFileById answer null for every OTHER mod.
		FakeModList list = new FakeModList(Map.of());

		int added = fill(list, List.of(
				new FakeContainer(new AngryInfo()),
				new FakeContainer(null),
				container(null, new Object()),
				container("", new Object()),
				container("nofile", null),
				container("sodium", new Object())));

		assertEquals(1, added);
		assertTrue(list.fileById.containsKey("sodium"));
		assertTrue(!list.fileById.containsKey("nofile"),
				"a mod with no owning file must be absent, not mapped to null");
	}

	@Test
	void aMissingFieldFailsLoudlyToTheCaller() {
		// publishFileById's caller catches and warns; the helper must not swallow, or a NeoForge version that
		// renamed the field would look like a successful publish.
		assertThrows(NoSuchFieldException.class, () -> KernelModLoader.fillFileById(
				Object.class, new Object(), List.of(), FakeContainer.class.getMethod("getModInfo"),
				FakeInfo.class.getMethod("getModId"), FakeInfo.class.getMethod("getOwningFile")));
	}
}
