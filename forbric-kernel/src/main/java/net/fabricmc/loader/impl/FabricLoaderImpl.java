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

package net.fabricmc.loader.impl;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;

/**
 * The name Fabric Loader's own implementation goes by, for the mods that reach past the API to it.
 *
 * <p>The kernel's loader is {@code KernelFabricLoader}; this is a facade in front of it, not a second loader. Every
 * API method forwards, at call time, to {@code FabricLoader.getInstance()}, so it holds no state of its own and
 * cannot disagree with the loader mods normally see. What it adds is the one internal a mod is observed to need:
 * {@link #entrypointStorage}, reached by reflection.
 *
 * <p>SuperMartijn642's Core Lib is that mod. Its {@code preLaunch} takes {@code FabricLoaderImpl.INSTANCE}, reads
 * the private {@code entrypointStorage} field, then {@code EntrypointStorage.entryMap}, and appends its
 * {@code RegistryEntryPoints} to the {@code main} and {@code client} lists. That appended entrypoint is the ONLY place
 * Core Lib's deferred registrations are flushed — the blocks, items and menus of every SuperMartijn642 mod — and it is
 * appended LAST on purpose, so every dependent's {@code onInitialize} has queued its entries first. With no such class
 * here the {@code preLaunch} died on {@code NoClassDefFoundError}, and none of that content ever existed. The kernel
 * reads the storage back after {@code preLaunch} ({@code KernelFabricLoader.adoptFabricStorage}).
 *
 * <p>{@code -Dforbric.fabricImpl=off} withholds this class from mods, which is the behaviour from before it existed.
 */
public final class FabricLoaderImpl extends net.fabricmc.loader.FabricLoader {
	public static final FabricLoaderImpl INSTANCE = InitHelper.get();

	/** Read reflectively by name ({@code getDeclaredField("entrypointStorage")}); the name is the contract. */
	private final EntrypointStorage entrypointStorage = new EntrypointStorage();

	private FabricLoaderImpl() {
	}

	/**
	 * Creates the one instance, whichever of {@code FabricLoaderImpl} and the legacy {@code FabricLoader} is touched
	 * first — each one's static initialiser asks here.
	 *
	 * <p>Fabric's own {@code get()} is only {@code if (instance == null) instance = new FabricLoaderImpl()}, which is
	 * safe there because Fabric always initialises {@code FabricLoaderImpl} first. Here a mod can touch the legacy
	 * class first — Core Lib's mixin plugin does, before its {@code preLaunch} touches this one — and then the
	 * {@code new} initialises this class, whose own initialiser re-enters and creates an instance, and the outer call
	 * creates a SECOND one: the two {@code INSTANCE} fields hold different objects. So this class is initialised
	 * before anything is constructed; the re-entrant call does the constructing, and the outer one finds it done.
	 */
	public static class InitHelper {
		private static FabricLoaderImpl instance;

		public static FabricLoaderImpl get() {
			if (instance == null) {
				try {
					Class.forName(FabricLoaderImpl.class.getName(), true, FabricLoaderImpl.class.getClassLoader());
				} catch (ClassNotFoundException impossible) {
					throw new IllegalStateException(impossible);
				}
				if (instance == null) instance = new FabricLoaderImpl();
			}
			return instance;
		}
	}

	private static net.fabricmc.loader.api.FabricLoader kernel() {
		return net.fabricmc.loader.api.FabricLoader.getInstance();
	}

	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		return kernel().getEntrypoints(key, type);
	}

	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return kernel().getEntrypointContainers(key, type);
	}

	@Override
	public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
		kernel().invokeEntrypoints(key, type, invoker);
	}

	@Override
	public ObjectShare getObjectShare() {
		return kernel().getObjectShare();
	}

	@Override
	public MappingResolver getMappingResolver() {
		return kernel().getMappingResolver();
	}

	@Override
	public Optional<ModContainer> getModContainer(String id) {
		return kernel().getModContainer(id);
	}

	@Override
	public Collection<ModContainer> getAllMods() {
		return kernel().getAllMods();
	}

	@Override
	public boolean isModLoaded(String id) {
		return kernel().isModLoaded(id);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return kernel().isDevelopmentEnvironment();
	}

	@Override
	public EnvType getEnvironmentType() {
		return kernel().getEnvironmentType();
	}

	@Override
	public String getRawGameVersion() {
		return kernel().getRawGameVersion();
	}

	@Override
	@Deprecated
	public Object getGameInstance() {
		return kernel().getGameInstance();
	}

	@Override
	public Path getGameDir() {
		return kernel().getGameDir();
	}

	@Override
	@Deprecated
	public File getGameDirectory() {
		return kernel().getGameDirectory();
	}

	@Override
	public Path getConfigDir() {
		return kernel().getConfigDir();
	}

	@Override
	@Deprecated
	public File getConfigDirectory() {
		return kernel().getConfigDirectory();
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		return kernel().getLaunchArguments(sanitize);
	}
}
