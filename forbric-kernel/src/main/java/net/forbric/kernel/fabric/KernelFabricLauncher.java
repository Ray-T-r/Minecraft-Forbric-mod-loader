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

import java.nio.file.Path;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's answer to {@code FabricLauncherBase.getLauncher()} — what a mod reaches for when it needs to put a
 * jar it just unpacked onto the running classpath.
 *
 * <p>Under Fabric that call lands on Knot. There is no Knot here, so it lands on the sovereign loader instead:
 * the jar becomes an owned URL and its classes go through the full pipeline (access tweakers, the compat chain,
 * then Mixin), which is what the mod expects and what Fabric's own {@code addToClassPath} provides.
 *
 * <p>{@code allowedPrefixes} is accepted and ignored: it exists so Knot can keep a jar's packages from shadowing
 * another's, and the kernel has one flat loader with no per-jar package filter to express that against.
 */
public final class KernelFabricLauncher implements FabricLauncher {
	private final ForbricClassLoader loader;
	private final EnvType envType;

	private KernelFabricLauncher(ForbricClassLoader loader, EnvType envType) {
		this.loader = loader;
		this.envType = envType;
	}

	/** Builds the launcher view and publishes it. Call once, before any mod class loads. */
	public static void install(ForbricClassLoader loader, EnvType envType) {
		FabricLauncherBase.setLauncher(new KernelFabricLauncher(loader, envType));
	}

	@Override
	public void addToClassPath(Path path, String... allowedPrefixes) {
		try {
			loader.addURL(path.toUri().toURL());
			ForbricLog.info("[Forbric/Fabric] a mod added %s to the classpath at runtime", path.getFileName());
		} catch (Throwable t) {
			throw new RuntimeException("could not add " + path + " to the classpath", t);
		}
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return loader;
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isDevelopment() {
		return false;
	}
}
