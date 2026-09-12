/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.api;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * The public-facing Fabric Loader instance — the mod-facing ABI, reproduced verbatim from Fabric Loader
 * (Apache-2.0, Copyright FabricMC) because third-party mods' compiled bytecode calls exactly these signatures.
 *
 * <p>Forbric deviation: {@link #getInstance()} resolves the sovereign kernel's implementation
 * ({@code net.forbric.kernel.fabric.KernelFabricLoader}) rather than Fabric Loader's {@code FabricLoaderImpl}.
 * No {@code net.fabricmc.loader.impl} class exists in the kernel; the Fabric ecosystem is served natively.
 */
public interface FabricLoader {
	/** Returns the public-facing Fabric Loader instance (kernel-backed). */
	static FabricLoader getInstance() {
		FabricLoader ret = net.forbric.kernel.fabric.KernelFabricLoader.getInstanceOrNull();

		if (ret == null) {
			throw new RuntimeException("Accessed FabricLoader too early!");
		}

		return ret;
	}

	<T> List<T> getEntrypoints(String key, Class<T> type);

	<T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type);

	<T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker);

	ObjectShare getObjectShare();

	MappingResolver getMappingResolver();

	Optional<ModContainer> getModContainer(String id);

	Collection<ModContainer> getAllMods();

	boolean isModLoaded(String id);

	boolean isDevelopmentEnvironment();

	EnvType getEnvironmentType();

	String getRawGameVersion();

	/** @deprecated experimental; may be null before the game object exists. */
	@Deprecated
	Object getGameInstance();

	Path getGameDir();

	@Deprecated
	File getGameDirectory();

	Path getConfigDir();

	@Deprecated
	File getConfigDirectory();

	String[] getLaunchArguments(boolean sanitize);
}
