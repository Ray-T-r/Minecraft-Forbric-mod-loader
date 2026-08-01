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

package net.fabricmc.loader.impl.launch;

import java.nio.file.Path;

import net.fabricmc.api.EnvType;

/**
 * The sliver of Fabric Loader's INTERNAL launcher that third-party mods actually call.
 *
 * <p>The kernel deliberately implements no {@code net.fabricmc.loader.impl} machinery — the Fabric ecosystem is
 * served natively, and this package is Fabric's private plumbing. But a mod that unpacks its real payload at
 * {@code preLaunch} has no public API for putting that payload on the classpath, so it reaches in here anyway:
 * CustomSkinLoader releases {@code CustomSkinLoader-Common.jar} into the game directory and adds it with
 * {@code FabricLauncherBase.getLauncher().addToClassPath(path)}. Without this it got a bare NPE from
 * {@code getLauncher()} returning null and dropped the mod's entire runtime.
 *
 * <p>Only what such mods are observed to call is declared, so an unsupported call fails at link time by name
 * instead of silently doing nothing. This surface grows on demand; it is not an attempt to reproduce the interface.
 */
public interface FabricLauncher {
	/**
	 * Adds a jar to the running mod classpath.
	 *
	 * @param path         the jar
	 * @param allowedPrefixes packages the caller wants restricted to this jar; the kernel's single sovereign loader
	 *                        has no per-jar package filter, so these are accepted and ignored
	 */
	void addToClassPath(Path path, String... allowedPrefixes);

	/** The loader this instance defines mod and game classes with. */
	ClassLoader getTargetClassLoader();

	/** Client or dedicated server. */
	EnvType getEnvironmentType();

	/** Whether this is a development launch. Always false: the kernel ships only production launches. */
	boolean isDevelopment();
}
