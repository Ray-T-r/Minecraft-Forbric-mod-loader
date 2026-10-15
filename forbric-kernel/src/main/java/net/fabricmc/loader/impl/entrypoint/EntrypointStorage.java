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

package net.fabricmc.loader.impl.entrypoint;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.impl.ModContainerImpl;

import net.forbric.kernel.fabric.KernelFabricLoader;

/**
 * Fabric Loader's entrypoint index, in the shape a mod reaching into it by reflection expects.
 *
 * <p>The names are the contract, because they are what reflection asks for: the private field {@code entryMap}
 * ({@code key -> mutable list of entries}, in invocation order), and the nested {@code NewEntry} with its
 * {@code (ModContainerImpl, LanguageAdapter, String)} constructor. SuperMartijn642's Core Lib uses exactly those to
 * append its {@code RegistryEntryPoints} to {@code main} and {@code client} — see {@code FabricLoaderImpl}.
 *
 * <p>The map is the kernel's: {@code KernelFabricLoader} fills it with one entry per entrypoint it holds, in its own
 * order, and after {@code preLaunch} takes back whatever mods did to it, in the order they left it. Nothing is
 * ordered or filtered here.
 */
public final class EntrypointStorage {
	/** Read reflectively by name ({@code getDeclaredField("entryMap")}). Shared with the kernel, which fills it. */
	private final Map<String, List<Entry>> entryMap;

	public EntrypointStorage() {
		this.entryMap = KernelFabricLoader.fabricEntryMap();
	}

	private EntrypointStorage(Map<String, List<Entry>> entryMap) {
		this.entryMap = entryMap;
	}

	/**
	 * The storage the kernel's loader exposes as its own {@code entrypointStorage} field: the same entry map, made live
	 * ({@link KernelFabricLoader#fabricEntryMap()}) only by the first {@link #add}, so a pack whose mods never reach for it
	 * never has one.
	 */
	public static EntrypointStorage deferred() {
		return new EntrypointStorage(KernelFabricLoader.fabricEntryMapIfLive());
	}

	/**
	 * Adds {@code metadata}'s entrypoint of {@code mod} under {@code key}, built by its language adapter from
	 * {@code adapterMap}: what Fabric Loader does for each declared entrypoint, and what Essential's loader does for each
	 * mod it registers at run time. The kernel takes it back between phases ({@code KernelFabricLoader.adoptFabricStorage}).
	 */
	public void add(ModContainerImpl mod, String key, net.fabricmc.loader.impl.metadata.EntrypointMetadata metadata,
			Map<String, LanguageAdapter> adapterMap) throws Exception {
		LanguageAdapter adapter = adapterMap.get(metadata.getAdapter());
		if (adapter == null) throw new IllegalArgumentException("Could not find adapter '" + metadata.getAdapter() + "' (mod "
				+ mod.getMetadata().getId() + "!)");
		Map<String, List<Entry>> live = KernelFabricLoader.fabricEntryMap();   // live from here on, seeded if the loader froze
		synchronized (live) {
			live.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new NewEntry(mod, adapter, metadata.getValue()));
		}
	}

	/**
	 * One entrypoint as Fabric Loader stores it. Package-private in Fabric Loader; public here because the kernel's
	 * own entrypoints are stored as implementations of it.
	 */
	public interface Entry {
		<T> T getOrCreate(Class<T> type) throws Exception;

		boolean isOptional();

		ModContainerImpl getModContainer();

		String getDefinition();
	}

	/**
	 * An entrypoint a mod added itself: built by its language adapter on first use, once per requested type — the
	 * same as Fabric Loader's, which is what the mod constructing it expects to happen.
	 */
	static final class NewEntry implements Entry {
		private final ModContainerImpl mod;
		private final LanguageAdapter adapter;
		private final String value;
		private final Map<Class<?>, Object> instanceMap = new IdentityHashMap<>(1);

		NewEntry(ModContainerImpl mod, LanguageAdapter adapter, String value) {
			this.mod = mod;
			this.adapter = adapter;
			this.value = value;
		}

		@Override
		public String toString() {
			return mod.getMetadata().getId() + "->(0.3.x)" + value;   // Fabric Loader's own spelling
		}

		@Override
		@SuppressWarnings("unchecked")
		public synchronized <T> T getOrCreate(Class<T> type) throws Exception {
			T created = (T) instanceMap.get(type);
			if (created == null) {
				created = adapter.create(mod, value, type);
				T raced = (T) instanceMap.putIfAbsent(type, created);
				if (raced != null) created = raced;
			}
			return created;
		}

		@Override
		public boolean isOptional() {
			return false;
		}

		@Override
		public ModContainerImpl getModContainer() {
			return mod;
		}

		@Override
		public String getDefinition() {
			return value;
		}
	}
}
