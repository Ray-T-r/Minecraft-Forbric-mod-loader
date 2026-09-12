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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's {@link ModContainer} — one loaded Fabric mod, backed by its jar.
 *
 * <p>{@link #getRootPaths()} exposes the jar's contents through a zip {@code FileSystem} opened once and kept
 * open for the JVM's life (mods call {@code findPath(...)} at arbitrary times — e.g. fabric-api reading a
 * bundled resource, ModMenu reading the icon). The filesystem is opened lazily so a mod that never reads its
 * own jar costs nothing.
 */
public final class KernelModContainer implements ModContainer {
	private final KernelModMetadata metadata;
	private final Path jar;
	private final KernelModContainer parent;
	private final List<KernelModContainer> children = new ArrayList<>();

	private volatile List<Path> rootPaths;

	/** @param jar the mod's jar, or {@code null} for a builtin (synthetic) mod such as {@code minecraft} */
	public KernelModContainer(KernelModMetadata metadata, Path jar, KernelModContainer parent) {
		this.metadata = metadata;
		this.jar = jar;
		this.parent = parent;

		if (parent != null) parent.children.add(this);
	}

	/** The jar this mod was loaded from (an extracted file for JiJ-nested mods), or {@code null} if builtin. */
	public Path getJar() {
		return jar;
	}

	@Override
	public KernelModMetadata getMetadata() {
		return metadata;
	}

	@Override
	public List<Path> getRootPaths() {
		if (jar == null) return List.of();

		List<Path> paths = rootPaths;
		if (paths != null) return paths;

		synchronized (this) {
			if (rootPaths != null) return rootPaths;

			List<Path> resolved;

			try {
				if (Files.isDirectory(jar)) {
					resolved = List.of(jar);
				} else {
					FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null);
					List<Path> roots = new ArrayList<>();

					for (Path root : fs.getRootDirectories()) {
						roots.add(root);
					}

					resolved = Collections.unmodifiableList(roots);
				}
			} catch (IOException e) {
				ForbricLog.warn("[Forbric/Fabric] could not open %s for %s: %s", jar.getFileName(),
						metadata.getId(), e.getMessage());
				resolved = List.of();
			}

			rootPaths = resolved;
			return resolved;
		}
	}

	@Override
	public ModOrigin getOrigin() {
		return new Origin();
	}

	@Override
	public Optional<ModContainer> getContainingMod() {
		return Optional.ofNullable(parent);
	}

	@Override
	public Collection<ModContainer> getContainedMods() {
		return Collections.unmodifiableCollection(new ArrayList<>(children));
	}

	@Override
	@Deprecated
	public Path getRootPath() {
		List<Path> roots = getRootPaths();
		if (roots.isEmpty()) throw new IllegalStateException("no root path for mod " + metadata.getId());

		return roots.get(0);
	}

	@Override
	@Deprecated
	public Path getPath(String file) {
		return findPath(file).orElseGet(() -> getRootPath().resolve(file));
	}

	@Override
	public String toString() {
		return metadata.getId() + " (" + (jar == null ? "builtin" : jar.getFileName()) + ")";
	}

	private final class Origin implements ModOrigin {
		@Override
		public Kind getKind() {
			if (jar == null) return Kind.UNKNOWN;

			return parent == null ? Kind.PATH : Kind.NESTED;
		}

		@Override
		public List<Path> getPaths() {
			if (jar == null) throw new UnsupportedOperationException("builtin mod has no path");
			if (parent != null) throw new UnsupportedOperationException("nested mod has no direct path");

			return List.of(jar);
		}

		@Override
		public String getParentModId() {
			if (parent == null) throw new UnsupportedOperationException("not a nested mod");

			return parent.metadata.getId();
		}

		@Override
		public String getParentSubLocation() {
			if (parent == null) throw new UnsupportedOperationException("not a nested mod");

			return jar.getFileName().toString();
		}
	}
}
