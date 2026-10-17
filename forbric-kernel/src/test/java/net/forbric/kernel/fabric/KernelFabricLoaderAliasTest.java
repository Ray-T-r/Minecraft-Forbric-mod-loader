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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import net.forbric.kernel.boot.KernelFabricEcosystem;

/**
 * A mod's own id outranks another mod's {@code provides} alias for it.
 *
 * <p>owo-sentinel is nested in mods that need owo-lib; it {@code provides} owo-lib's id {@code owo} and depends on
 * nothing installed, so it registers first. Its alias used to take the name: owo-lib was dropped as a "duplicate", its
 * own alias {@code owo-impl} never existed, and once language adapters were built up front the sentinel's adapter
 * found owo missing and ended the process. The shape is reproduced here with invented names.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("system-properties")
class KernelFabricLoaderAliasTest {
	static volatile Boolean libSeenByAdapter;

	@TempDir
	Path dir;

	private KernelFabricLoader loader;

	@BeforeEach
	void setUp() {
		KernelFabricLoader.resetForTests();
		libSeenByAdapter = null;
		loader = KernelFabricLoader.create(EnvType.SERVER, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(getClass().getClassLoader());
	}

	@AfterEach
	void tearDown() {
		KernelFabricLoader.resetForTests();
		KernelLanguageAdapters.reset();
	}

	@Test
	void theRealModTakesItsIdFromAnAliasRegisteredBeforeIt() {
		register("lib-sentinel", "[\"lib\"]", "{}");
		register("lib", "[\"lib-impl\"]", "{}");
		loader.freeze();

		assertEquals("lib", loader.getModContainer("lib").orElseThrow().getMetadata().getId());
		assertTrue(loader.isModLoaded("lib-impl"), "the real mod's own aliases exist");
		assertEquals(2, loader.getAllMods().size(), "both are loaded; only the name moved");
		assertEquals("lib-sentinel", loader.getModContainer("lib-sentinel").orElseThrow().getMetadata().getId());
	}

	@Test
	void anAliasRegisteredAfterTheRealModStillLeavesItAlone() {
		register("lib", "[\"lib-impl\"]", "{}");
		register("lib-sentinel", "[\"lib\"]", "{}");
		loader.freeze();

		assertEquals("lib", loader.getModContainer("lib").orElseThrow().getMetadata().getId());
	}

	@Test
	void twoModsWithOneOwnIdStillKeepTheFirst() {
		register("lib", "[\"first-alias\"]", "{}");
		register("lib", "[\"second-alias\"]", "{}");
		loader.freeze();

		assertEquals(1, loader.getAllMods().size());
		assertTrue(loader.isModLoaded("first-alias"));
		assertTrue(!loader.isModLoaded("second-alias"), "the dropped duplicate's aliases do not exist");
	}

	/** The owo shape end to end: the sentinel's adapter is built up front and asks whether the library is loaded. */
	@Test
	void aSentinelsAdapterBuiltUpFrontSeesTheLibraryIsLoaded() throws Exception {
		register("lib-sentinel", "[\"lib\"]", "{\"sentinel\":\"" + SentinelAdapter.class.getName() + "\"}");
		register("lib", "[\"lib-impl\"]", "{}");
		loader.freeze();

		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		try {
			active.set(null, loader);
			KernelFabricEcosystem.constructLanguageAdapters();
		} finally {
			active.set(null, previous);
		}

		assertEquals(Boolean.TRUE, libSeenByAdapter, "the adapter ran, and found the library its sentinel stands in for");
	}

	private void register(String id, String provides, String languageAdapters) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"provides\":" + provides
				+ ",\"languageAdapters\":" + languageAdapters + "}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}

	/** owo-sentinel's adapter in miniature: its constructor checks for the real library's implementation alias. */
	public static final class SentinelAdapter implements LanguageAdapter {
		public SentinelAdapter() {
			libSeenByAdapter = FabricLoader.getInstance().isModLoaded("lib-impl");
		}

		@Override
		public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
			return LanguageAdapter.getDefault().create(mod, value, type);
		}
	}
}
