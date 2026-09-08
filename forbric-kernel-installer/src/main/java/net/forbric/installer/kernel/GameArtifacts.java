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

package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three heavy jars a Forbric instance runs on: the byte-merged game base and one runtime per Forge family.
 *
 * <p>They are not in this installer and never will be. The merged base is Minecraft with two loaders' patches
 * applied and then merged, and the runtimes are assembled from MinecraftForge's and NeoForge's own distributions —
 * so all three embed code this project has no right to hand out. They are built on the machine that runs them,
 * which is what {@code forbric-loader/run/build-merged-base.sh} and its two {@code assemble-*-runtime.sh} siblings
 * do, and this class only finds the result.
 *
 * <p>Order of search: an explicit directory the caller names, then the checkout this installer was built from.
 * A missing artifact is reported by name and expected path rather than guessed at, because every later step —
 * the profile's game arguments above all — is a lie without it.
 */
final class GameArtifacts {
	/** Coordinate → the file name the build scripts produce. */
	private static final Map<String, String> WANTED = new LinkedHashMap<>();

	static {
		WANTED.put("net.forbric:patched-mc-merged", "patched-mc-merged-%s.jar");
		WANTED.put("net.forbric:forge-runtime", "forge-runtime.jar");
		WANTED.put("net.forbric:neoforge-runtime", "neoforge-runtime.jar");
	}

	private final Map<String, Path> found = new LinkedHashMap<>();

	private GameArtifacts() {
	}

	/**
	 * Locates all three for {@code mcVersion}. {@code explicit} may be null; the directories under it are the same
	 * ones the build scripts write into ({@code merged-base/}, {@code forge-runtime/}, {@code neoforge-runtime/}).
	 */
	static GameArtifacts locate(String mcVersion, Path explicit) throws IOException {
		GameArtifacts artifacts = new GameArtifacts();
		List<Path> roots = new ArrayList<>();
		if (explicit != null) roots.add(explicit);
		Path builtIn = developmentRunDirectory();
		if (builtIn != null) roots.add(builtIn);

		List<String> missing = new ArrayList<>();
		for (Map.Entry<String, String> wanted : WANTED.entrySet()) {
			String fileName = String.format(wanted.getValue(), mcVersion);
			Path hit = null;
			for (Path root : roots) {
				for (Path candidate : new Path[] {
						root.resolve(fileName),
						root.resolve("merged-base").resolve(fileName),
						root.resolve("forge-runtime").resolve(fileName),
						root.resolve("neoforge-runtime").resolve(fileName)}) {
					if (Files.isRegularFile(candidate)) {
						hit = candidate;
						break;
					}
				}
				if (hit != null) break;
			}
			if (hit == null) missing.add(fileName);
			else artifacts.found.put(wanted.getKey(), hit);
		}
		if (!missing.isEmpty()) {
			throw new IOException("cannot find the game artifacts this build needs: " + String.join(", ", missing)
					+ ".\nThey contain Minecraft, MinecraftForge and NeoForge code, so they are built on your own "
					+ "machine rather than shipped here. Produce them with forbric-loader/run/"
					+ "assemble-minecraftforge-runtime.sh, assemble-neoforge-runtime.sh and build-merged-base.sh, "
					+ "then point the installer at the directory holding them."
					+ (roots.isEmpty() ? "" : "\nLooked under: " + roots));
		}
		return artifacts;
	}

	/** coordinate (without version) → located file. */
	Map<String, Path> all() {
		return found;
	}

	Path get(String coordinate) {
		return found.get(coordinate);
	}

	/**
	 * The {@code run/} directory of the checkout this installer jar sits in, when it is being run from one — the
	 * ordinary case while developing, and what the installer gate uses. Null when the jar has been moved away.
	 */
	private static Path developmentRunDirectory() {
		try {
			Path jar = Path.of(GameArtifacts.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			for (Path dir = jar.getParent(); dir != null; dir = dir.getParent()) {
				Path run = dir.resolve("forbric-loader").resolve("run");
				if (Files.isDirectory(run)) return run;
			}
		} catch (Exception notFromAJarInACheckout) {
			return null;
		}
		return null;
	}
}
