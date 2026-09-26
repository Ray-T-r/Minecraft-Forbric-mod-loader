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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.util.DefaultLanguageAdapter;

import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.classloading.FabricLoaderInternals;

/**
 * SuperMartijn642's Core Lib reaches past Fabric's API into {@code FabricLoaderImpl}'s entrypoint storage and appends
 * the entrypoint that flushes every SuperMartijn642 mod's registrations. With no such class in the kernel its
 * {@code preLaunch} died on {@code NoClassDefFoundError} and that content never existed.
 *
 * <p>{@link #coreLibPreLaunch} is Core Lib's {@code onPreLaunch} (1.1.24+b), call for call, so these tests are about
 * the ABI that mod actually uses — the field names, the constructor, the casts — and not about a tidier one.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("system-properties")
class FabricEntrypointStorageTest {
	static final List<String> RAN = new ArrayList<>();

	@TempDir
	Path dir;

	private KernelFabricLoader loader;

	@BeforeEach
	void setUp() {
		KernelFabricLoader.resetForTests();
		RAN.clear();
		loader = KernelFabricLoader.create(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(getClass().getClassLoader());
		// Registration order is dependency order, as KernelFabricEcosystem.build registers: the library first.
		register("corelib", "{\"main\":[\"" + LibMain.class.getName() + "\"],\"client\":[\""
				+ LibClient.class.getName() + "\"],\"preLaunch\":[\"" + CoreLibLikePreLaunch.class.getName() + "\"]}",
				"{\"fabricloader\":\"*\"}");
		register("dependent", "{\"main\":[\"" + DependentMain.class.getName() + "\"]}", "{\"corelib\":\"*\"}");
		loader.freeze();
	}

	@AfterEach
	void tearDown() {
		KernelFabricLoader.resetForTests();
		KernelLanguageAdapters.reset();
		System.clearProperty(FabricLoaderInternals.SWITCH);
		RAN.clear();
	}

	/**
	 * The appended entrypoint runs, and runs LAST — after the mod that depends on its provider. Sorting it back into
	 * dependency order would run it before that mod, which would then register into an already-flushed library.
	 */
	@Test
	void theAppendedEntrypointRunsLastAfterItsOwnDependents() throws Exception {
		coreLibPreLaunch();

		List<String> adopted = loader.adoptFabricStorage(Set.of("preLaunch"));

		assertEquals(Set.of("main:corelib->" + Flush.class.getName(), "client:corelib->" + Flush.class.getName()),
				Set.copyOf(adopted));
		assertEquals(2, adopted.size());
		List<EntrypointContainer<ModInitializer>> main = loader.getEntrypointContainers("main", ModInitializer.class);
		assertEquals(List.of("corelib", "dependent", "corelib"),
				main.stream().map(c -> c.getProvider().getMetadata().getId()).toList());
		for (EntrypointContainer<ModInitializer> c : main) c.getEntrypoint().onInitialize();
		assertEquals(List.of("LibMain", "DependentMain", "Flush.main"), RAN);

		for (EntrypointContainer<ClientModInitializer> c
				: loader.getEntrypointContainers("client", ClientModInitializer.class)) {
			c.getEntrypoint().onInitializeClient();
		}
		assertEquals(List.of("LibMain", "DependentMain", "Flush.main", "LibClient", "Flush.client"), RAN);
		assertNotSame(main.get(2).getEntrypoint(),
				loader.getEntrypointContainers("client", ClientModInitializer.class).get(1).getEntrypoint(),
				"two NewEntry objects are two instances, as on Fabric");
	}

	/** What {@code KernelFabricEcosystem} does after {@code preLaunch}: the added entrypoint is in {@code main}. */
	@Test
	void runningPreLaunchReadsTheStorageBack() throws Exception {
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		try {
			active.set(null, loader);

			KernelFabricEcosystem.runPreLaunch();
		} finally {
			active.set(null, previous);
		}

		assertEquals(List.of(LibMain.class.getName(), DependentMain.class.getName(), Flush.class.getName()),
				definitions("main"));
	}

	/** The switch: the storage is still there to write to, but nothing written reaches the kernel — as before. */
	@Test
	void switchedOffTheAddedEntrypointNeverRuns() throws Exception {
		coreLibPreLaunch();
		System.setProperty(FabricLoaderInternals.SWITCH, "off");

		assertEquals(List.of(), loader.adoptFabricStorage(Set.of("preLaunch")));
		assertEquals(List.of(LibMain.class.getName(), DependentMain.class.getName()), definitions("main"));
	}

	@Test
	void readingTheStorageBackTwiceChangesNothing() throws Exception {
		coreLibPreLaunch();
		loader.adoptFabricStorage(Set.of("preLaunch"));
		List<EntrypointContainer<ModInitializer>> first = loader.getEntrypointContainers("main", ModInitializer.class);
		Object flush = first.get(2).getEntrypoint();

		assertEquals(List.of(), loader.adoptFabricStorage(Set.of("preLaunch")));
		List<EntrypointContainer<ModInitializer>> second = loader.getEntrypointContainers("main", ModInitializer.class);
		assertEquals(3, second.size());
		assertSame(flush, second.get(2).getEntrypoint(), "the adopted entrypoint is the same one, not a second copy");
	}

	@Test
	void anEntrypointRemovedFromTheStorageIsGone() throws Exception {
		storage().get("main").remove(1);

		assertEquals(List.of(), loader.adoptFabricStorage(Set.of("preLaunch")));
		assertEquals(List.of(LibMain.class.getName()), definitions("main"));
	}

	/** The storage starts as the kernel's own entrypoints, in the kernel's order, each backed by the kernel's. */
	@Test
	void theStorageMirrorsTheKernelsEntrypoints() throws Exception {
		List<EntrypointStorage.Entry> main = storage().get("main");

		assertEquals(List.of(LibMain.class.getName(), DependentMain.class.getName()),
				main.stream().map(EntrypointStorage.Entry::getDefinition).toList());
		assertSame(loader.getModContainer("corelib").orElseThrow(), main.get(0).getModContainer());
		assertSame(loader.getEntrypointContainers("main", ModInitializer.class).get(0).getEntrypoint(),
				main.get(0).getOrCreate(ModInitializer.class), "a stored entry is the kernel's entrypoint, not a copy");
		assertEquals(null, storage().get("server"), "a key no mod declared is absent, as on Fabric");
	}

	/**
	 * Core Lib's mixin plugin reads the legacy {@code FabricLoader.INSTANCE} before its {@code preLaunch} reads
	 * {@code FabricLoaderImpl.INSTANCE}. Fabric's own init helper, touched in that order, makes two instances.
	 */
	@Test
	void theLegacyLoaderTouchedFirstIsTheSameInstance() throws Exception {
		assertOneInstance("net.fabricmc.loader.FabricLoader");
	}

	@Test
	void theImplTouchedFirstIsTheSameInstance() throws Exception {
		assertOneInstance("net.fabricmc.loader.impl.FabricLoaderImpl");
	}

	@Test
	void theLegacyLoaderAnswersLikeTheKernelsLoader() {
		@SuppressWarnings("deprecation")
		net.fabricmc.loader.FabricLoader legacy = net.fabricmc.loader.FabricLoader.INSTANCE;

		assertSame(FabricLoaderImpl.INSTANCE, legacy);
		assertEquals(false, legacy.isDevelopmentEnvironment(),
				"Core Lib's plugin keeps its dev-only mixins out of production on this answer");
		assertTrue(legacy.isModLoaded("dependent"));
	}

	/** Every container the kernel hands out passes Core Lib's {@code map(ModContainerImpl.class::cast)}. */
	@Test
	void everyContainerIsAModContainerImpl() {
		loader.getAllMods().forEach(mod -> assertTrue(mod instanceof ModContainerImpl, mod.toString()));
		assertTrue(KernelModContainer.presence(KernelModMetadata.builtin("x", "1", "x"), null) instanceof ModContainerImpl);
	}

	// --- Core Lib, as compiled --------------------------------------------------------------------------------------

	/** {@code CoreLibPreLaunch.onPreLaunch}, the Fabric branch (bc 228-451), with RegistryEntryPoints as {@link Flush}. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static void coreLibPreLaunch() throws Exception {
		Field storageField = FabricLoaderImpl.class.getDeclaredField("entrypointStorage");
		storageField.setAccessible(true);
		Field entriesField = EntrypointStorage.class.getDeclaredField("entryMap");
		entriesField.setAccessible(true);
		Class<?> entryClass = Class.forName("net.fabricmc.loader.impl.entrypoint.EntrypointStorage$NewEntry");
		Constructor<?> entryConstructor = entryClass.getDeclaredConstructor(ModContainerImpl.class, LanguageAdapter.class,
				String.class);
		entryConstructor.setAccessible(true);
		EntrypointStorage storage = (EntrypointStorage) storageField.get(FabricLoaderImpl.INSTANCE);
		Map entries = (Map) entriesField.get(storage);
		ModContainerImpl coreLibContainer = FabricLoader.getInstance().getModContainer("corelib")
				.map(ModContainerImpl.class::cast).get();
		DefaultLanguageAdapter languageAdapter = DefaultLanguageAdapter.INSTANCE;
		Object mainEntrypoint = entryConstructor.newInstance(coreLibContainer, languageAdapter, Flush.class.getName());
		((List) entries.get("main")).add(mainEntrypoint);
		Object clientEntrypoint = entryConstructor.newInstance(coreLibContainer, languageAdapter, Flush.class.getName());
		((List) entries.get("client")).add(clientEntrypoint);
	}

	public static final class CoreLibLikePreLaunch implements PreLaunchEntrypoint {
		@Override
		public void onPreLaunch() {
			try {
				coreLibPreLaunch();
			} catch (Exception e) {
				throw new RuntimeException("Failed to apply Core Lib registry entry points!", e);
			}
		}
	}

	public static final class LibMain implements ModInitializer {
		@Override
		public void onInitialize() {
			RAN.add("LibMain");
		}
	}

	public static final class LibClient implements ClientModInitializer {
		@Override
		public void onInitializeClient() {
			RAN.add("LibClient");
		}
	}

	public static final class DependentMain implements ModInitializer {
		@Override
		public void onInitialize() {
			RAN.add("DependentMain");
		}
	}

	/** Core Lib's {@code RegistryEntryPoints}: both a main and a client entrypoint. */
	public static final class Flush implements ModInitializer, ClientModInitializer {
		@Override
		public void onInitialize() {
			RAN.add("Flush.main");
		}

		@Override
		public void onInitializeClient() {
			RAN.add("Flush.client");
		}
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private void register(String id, String entrypoints, String depends) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":" + entrypoints
				+ ",\"depends\":" + depends + "}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<EntrypointStorage.Entry>> storage() throws Exception {
		Field storageField = FabricLoaderImpl.class.getDeclaredField("entrypointStorage");
		storageField.setAccessible(true);
		Field entriesField = EntrypointStorage.class.getDeclaredField("entryMap");
		entriesField.setAccessible(true);
		return (Map<String, List<EntrypointStorage.Entry>>) entriesField.get(storageField.get(FabricLoaderImpl.INSTANCE));
	}

	private List<String> definitions(String key) {
		List<String> out = new ArrayList<>();
		for (EntrypointContainer<Object> c : loader.getEntrypointContainers(key, Object.class)) out.add(c.getDefinition());
		return out;
	}

	/** Touches {@code first} in a loader of its own, then checks both {@code INSTANCE} fields hold one object. */
	private static void assertOneInstance(String first) throws Exception {
		URL classes = FabricLoaderImpl.class.getProtectionDomain().getCodeSource().getLocation();
		try (URLClassLoader isolated = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
			Class.forName(first, true, isolated);
			Class<?> legacy = Class.forName("net.fabricmc.loader.FabricLoader", true, isolated);
			Class<?> impl = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl", true, isolated);
			assertSame(isolated, impl.getClassLoader(), "premise: a fresh copy, initialised in the order under test");

			Object legacyInstance = legacy.getField("INSTANCE").get(null);
			assertNotNull(legacyInstance);
			assertSame(impl.getField("INSTANCE").get(null), legacyInstance);
		}
	}
}
