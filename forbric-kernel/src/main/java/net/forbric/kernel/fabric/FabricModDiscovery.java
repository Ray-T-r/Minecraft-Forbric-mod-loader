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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.util.ForbricLog;

/**
 * Discovers Fabric mods in a {@code mods/} directory, following JiJ ({@code "jars"}) nesting.
 *
 * <p>Nested jars are extracted to a cache directory because the transforming class loader reads from real jar
 * URLs; fabric-api alone ships 43 of them. Extraction is content-addressed by size so a re-launch reuses the
 * cache, and a changed parent jar re-extracts.
 *
 * <p>Mods whose {@code environment} excludes the running side are skipped entirely (Fabric's own behaviour) —
 * their jar never joins the classpath, so a client-only fabric-api module cannot be linked against on a
 * dedicated server.
 */
public final class FabricModDiscovery {
	public static final String MANIFEST = "fabric.mod.json";

	private final EnvType envType;
	private final Path cacheDir;
	private final List<KernelModContainer> containers = new ArrayList<>();
	private final List<Path> classpathJars = new ArrayList<>();

	/**
	 * Top-level jars this scan must pretend are not installed — the losers of cross-jar mod-id arbitration.
	 * Applied only to top-level jars: a suppressed jar never gets far enough for its nested children to matter,
	 * and the winner brings its own.
	 */
	private java.util.function.Predicate<Path> skip = jar -> false;

	public FabricModDiscovery(EnvType envType, Path cacheDir) {
		this.envType = envType;
		this.cacheDir = cacheDir;
	}

	/** Sets the top-level skip test (see {@link #skip}). */
	public void setSkip(java.util.function.Predicate<Path> skip) {
		if (skip != null) this.skip = skip;
	}

	/** Every discovered mod, parents before their nested children. */
	public List<KernelModContainer> getContainers() {
		return containers;
	}

	/** Every jar that must join the game class loader, in discovery order. */
	public List<Path> getClasspathJars() {
		return classpathJars;
	}

	/** Scans {@code modsDir} for jars carrying a {@code fabric.mod.json}. Non-Fabric jars are ignored. */
	public void discover(Path modsDir) {
		if (!Files.isDirectory(modsDir)) return;

		List<Path> jars = new ArrayList<>();

		try (Stream<Path> entries = Files.list(modsDir)) {
			entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile)
					.sorted()
					.forEach(jars::add);
		} catch (IOException e) {
			ForbricLog.warn("[Forbric/Fabric] could not list %s: %s", modsDir, e.getMessage());
			return;
		}

		for (Path jar : jars) {
			if (skip.test(jar)) {
				ForbricLog.debug("[Forbric/Fabric] skipping %s — superseded by another jar's copy of the same mod",
						jar.getFileName());
				continue;
			}
			discoverJar(jar, null);
		}
	}

	/** Reads one jar; if it is a Fabric mod, registers it and recurses into its nested jars. */
	private void discoverJar(Path jar, KernelModContainer parent) {
		KernelModMetadata metadata;

		try (JarFile jarFile = new JarFile(jar.toFile())) {
			ZipEntry entry = jarFile.getEntry(MANIFEST);
			if (entry == null) return;

			try (InputStream in = jarFile.getInputStream(entry)) {
				metadata = FabricModMetadataParser.read(in);
			}
		} catch (Exception e) {
			ForbricLog.warn("[Forbric/Fabric] could not read %s: %s", jar.getFileName(), String.valueOf(e));
			return;
		}

		if (!metadata.getEnvironment().matches(envType)) {
			ForbricLog.debug("[Forbric/Fabric] skipping %s (environment=%s, running %s)",
					metadata.getId(), metadata.getEnvironment(), envType);
			return;
		}

		KernelModContainer container = new KernelModContainer(metadata, jar, parent);
		containers.add(container);
		classpathJars.add(jar);

		for (String nested : metadata.getNestedJars()) {
			Path extracted = extract(jar, nested, metadata.getId());
			if (extracted != null) discoverJar(extracted, container);
		}
	}

	/**
	 * Extracts {@code entryPath} from {@code jar} into the cache, returning the extracted file (or {@code null}
	 * if absent/unreadable). Reuses an existing extraction whose size already matches the entry.
	 */
	private Path extract(Path jar, String entryPath, String parentModId) {
		String fileName = entryPath.substring(entryPath.lastIndexOf('/') + 1);
		Path target = cacheDir.resolve(parentModId).resolve(fileName);

		try (JarFile jarFile = new JarFile(jar.toFile())) {
			ZipEntry entry = jarFile.getEntry(entryPath);

			if (entry == null) {
				ForbricLog.warn("[Forbric/Fabric] %s declares nested jar '%s' which is not in the jar",
						parentModId, entryPath);
				return null;
			}

			if (Files.isRegularFile(target) && Files.size(target) == entry.getSize()) {
				return target;
			}

			Files.createDirectories(target.getParent());

			try (InputStream in = jarFile.getInputStream(entry)) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			}

			return target;
		} catch (IOException e) {
			ForbricLog.warn("[Forbric/Fabric] could not extract '%s' from %s: %s", entryPath, jar.getFileName(),
					String.valueOf(e));
			return null;
		}
	}
}
