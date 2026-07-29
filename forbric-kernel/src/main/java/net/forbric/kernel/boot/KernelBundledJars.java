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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;

/**
 * Extracts the libraries bundled inside the kernel's own jar that must live on the GAME side.
 *
 * <p>Only MixinExtras so far. It cannot be parent-loaded: it generates classes (the {@code LocalRef} machinery)
 * that must share a loader with the game classes they touch, and mods reference its annotations from woven
 * bytecode. So it is bundled at {@code META-INF/jars/mixinextras-fabric.jar}, extracted here, and handed to
 * {@code ForbricClassLoader} as an owned jar — while {@code DelegationPolicy} pins
 * {@code com.llamalad7.mixinextras.} to the game side.
 */
public final class KernelBundledJars {
	private static final String[] BUNDLED = {"mixinextras-fabric.jar"};

	private KernelBundledJars() {
	}

	/**
	 * Extracts every bundled game-side jar into {@code <gameDir>/.forbric-kernel/lib/} and returns their paths.
	 * A jar already extracted at the same size is reused.
	 */
	public static List<Path> extract(Path gameDir) {
		Path libDir = gameDir.resolve(".forbric-kernel").resolve("lib");
		List<Path> extracted = new ArrayList<>();

		for (String name : BUNDLED) {
			Path target = libDir.resolve(name);

			try (InputStream in = KernelBundledJars.class.getResourceAsStream("/META-INF/jars/" + name)) {
				if (in == null) {
					ForbricLog.warn("[Forbric/Boot] bundled library %s is missing from the kernel jar — mods using "
							+ "MixinExtras (most of fabric-api) will fail to apply their mixins", name);
					continue;
				}

				Files.createDirectories(libDir);
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				extracted.add(target);
				ForbricLog.debug("[Forbric/Boot] extracted bundled game-side library %s", name);
			} catch (Exception e) {
				ForbricLog.warn("[Forbric/Boot] could not extract bundled library %s: %s", name, String.valueOf(e));
			}
		}

		return extracted;
	}
}
