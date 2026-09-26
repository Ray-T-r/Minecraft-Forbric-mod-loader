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

package net.fabricmc.loader;

import net.fabricmc.loader.impl.FabricLoaderImpl;

/**
 * Fabric Loader's pre-0.4 entry point, still shipped by Fabric Loader and still read by mods compiled against it.
 *
 * <p>A CLASS, not the {@code net.fabricmc.loader.api.FabricLoader} interface: SuperMartijn642's Core Lib reads
 * {@code FabricLoader.INSTANCE.isDevelopmentEnvironment()} through {@code getstatic}/{@code invokevirtual} from its
 * mixin plugin's static initialiser. Without this class the plugin could not be built, and Mixin — which treats a
 * config whose plugin is missing as one that wants every mixin — applied the plugin's dev-only mixins in production.
 *
 * <p>Only {@link #INSTANCE} is declared: it is all mods are observed to use, and an unsupported call fails at link
 * time by name. The instance is {@link FabricLoaderImpl#INSTANCE}, which forwards to the kernel's loader.
 * {@code -Dforbric.fabricImpl=off} withholds this class from mods.
 */
@Deprecated
public abstract class FabricLoader implements net.fabricmc.loader.api.FabricLoader {
	/**
	 * The loader. Taken from {@link FabricLoaderImpl.InitHelper}, exactly as Fabric does, so whichever of the two
	 * classes a mod touches first, both {@code INSTANCE} fields end up holding the same object.
	 */
	@Deprecated
	public static final FabricLoader INSTANCE = FabricLoaderImpl.InitHelper.get();

	protected FabricLoader() {
	}
}
