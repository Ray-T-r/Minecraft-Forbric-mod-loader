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
 * Extracts the jars bundled inside the kernel's own jar that must live on the GAME side.
 *
 * <p>Both of them are here for the same structural reason and neither can be parent-loaded: MixinExtras
 * generates classes (the {@code LocalRef} machinery) that must share a loader with the game classes they touch,
 * and the kernel's own game-side half is pinned {@code ALWAYS_GAME} by {@code DelegationPolicy} precisely so
 * that it can name game types. Carrying them inside the boot jar keeps the kernel ONE file — no launcher
 * argument, no installer step, nothing for a profile to get wrong.
 *
 * <p>The two differ in what their absence MEANS, and that difference is carried as data rather than averaged
 * into one warning. A missing MixinExtras is a degraded run: most of fabric-api stops applying its mixins, and
 * saying so is the most the kernel can do. A missing game-side jar is a BROKEN KERNEL — it means the boot jar
 * was built on a machine with no staged artifacts, so every class the kernel itself will ask for game-side is
 * simply not there. That one names the build command, because the reader can fix it in one line.
 */
public final class KernelBundledJars {
	/**
	 * A jar carried at {@code META-INF/jars/} in the boot jar.
	 *
	 * @param fileName  the entry name inside the boot jar, and the name it is extracted under
	 * @param onMissing what to tell the reader when it is not in the boot jar — the consequence first, then the
	 *                  fix if there is one. Never a bare "not found": by the time anyone reads this line they
	 *                  already know something is missing; what they do not know is what it costs them.
	 */
	private record Bundled(String fileName, String onMissing) {
	}

	private static final Bundled[] BUNDLED = {
		new Bundled("mixinextras-fabric.jar",
				"mods using MixinExtras (most of fabric-api) will fail to apply their mixins"),
		new Bundled("forbric-kernel-runtime.jar",
				"this boot jar was built with no staged game artifacts, so the kernel's own game-side classes are "
						+ "absent and anything that needs one will fail to link — rebuild with the staged jars in "
						+ "place (../forbric-loader/run/) via: ./gradlew jar"),
	};

	private KernelBundledJars() {
	}

	/**
	 * Extracts every bundled game-side jar into {@code <gameDir>/.forbric-kernel/lib/} and returns their paths.
	 */
	public static List<Path> extract(Path gameDir) {
		Path libDir = gameDir.resolve(".forbric-kernel").resolve("lib");
		List<Path> extracted = new ArrayList<>();

		for (Bundled bundled : BUNDLED) {
			String name = bundled.fileName();
			Path target = libDir.resolve(name);

			try (InputStream in = KernelBundledJars.class.getResourceAsStream("/META-INF/jars/" + name)) {
				if (in == null) {
					ForbricLog.warn("[Forbric/Boot] bundled jar %s is missing from the kernel jar — %s",
							name, bundled.onMissing());
					continue;
				}

				Files.createDirectories(libDir);
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				extracted.add(target);
				ForbricLog.debug("[Forbric/Boot] extracted bundled game-side jar %s", name);
			} catch (Exception e) {
				ForbricLog.warn("[Forbric/Boot] could not extract bundled jar %s: %s — %s",
						name, String.valueOf(e), bundled.onMissing());
			}
		}

		return extracted;
	}
}
