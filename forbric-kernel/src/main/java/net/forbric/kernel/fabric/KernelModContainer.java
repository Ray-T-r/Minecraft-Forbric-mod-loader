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
import net.fabricmc.loader.impl.ModContainerImpl;

import net.forbric.kernel.util.Caught;

/**
 * The kernel's {@link ModContainer} — one loaded Fabric mod, backed by its jar.
 *
 * <p>{@link #getRootPaths()} exposes the jar's contents through a zip {@code FileSystem} opened once and kept
 * open for the JVM's life (mods call {@code findPath(...)} at arbitrary times — e.g. fabric-api reading a
 * bundled resource, ModMenu reading the icon). The filesystem is opened lazily so a mod that never reads its
 * own jar costs nothing.
 *
 * <p>It extends Fabric Loader's internal {@link ModContainerImpl} — an empty type here — because that is what every
 * container is on Fabric, and a mod that casts to it (SuperMartijn642's Core Lib, to add its own entrypoint) must
 * succeed for every mod, builtins and presence identities included.
 */
public final class KernelModContainer extends ModContainerImpl {
	private final KernelModMetadata metadata;
	private final Path jar;
	private final KernelModContainer parent;
	private final List<KernelModContainer> children = new ArrayList<>();

	private volatile List<Path> rootPaths;
	/** For a presence identity only: the jar its files are read from. {@link #getJar()} stays null. */
	private final Path loadedFrom;
	/** The jar the metadata's icon came from, when that is not {@link #loadedFrom}; see {@link #presence(KernelModMetadata, Path, Path)}. */
	private final Path shownFrom;
	private volatile List<Path> shownRoots;

	/** @param jar the mod's jar, or {@code null} for a builtin (synthetic) mod such as {@code minecraft} */
	public KernelModContainer(KernelModMetadata metadata, Path jar, KernelModContainer parent) {
		this(metadata, jar, parent, null);
	}

	private KernelModContainer(KernelModMetadata metadata, Path jar, KernelModContainer parent, Path loadedFrom) {
		this(metadata, jar, parent, loadedFrom, null);
	}

	private KernelModContainer(KernelModMetadata metadata, Path jar, KernelModContainer parent, Path loadedFrom, Path shownFrom) {
		this.metadata = metadata;
		this.jar = jar;
		this.parent = parent;
		this.loadedFrom = loadedFrom;
		this.shownFrom = shownFrom;

		if (parent != null) parent.children.add(this);
	}

	/**
	 * An identity registered for presence only: the mod is loaded, from {@code loadedFrom}, by another jar or another
	 * family. It has no jar of its own, so nothing takes code, entrypoints, mixins or assets from it — but a mod
	 * reads its OWN files through its own container ({@link #findPath}), and that has to find them in the jar that
	 * did load it. LambDynamicLights, a universal jar that loads as NeoForge, asks its Fabric container (through yumi)
	 * for its default config on the first launch and stopped the game when the alias answered with nothing.
	 *
	 * @param loadedFrom the jar the mod was actually loaded from, or null when there is none to read
	 */
	public static KernelModContainer presence(KernelModMetadata metadata, Path loadedFrom) {
		return new KernelModContainer(metadata, null, null, loadedFrom);
	}

	/**
	 * As {@link #presence(KernelModMetadata, Path)}, for an identity shown as another jar shows it: a mod whose Fabric
	 * build lost, presented by that build's {@code fabric.mod.json} ({@link KernelModMetadata#presenceShownAs}). Every file
	 * is still read from {@code loadedFrom}, the jar that runs; only an icon the metadata declares and that jar lacks is
	 * read from {@code shownFrom}, the jar that declared it -- a mod list draws the icon of the metadata it shows, and a
	 * Fabric jar that lost to another family's build of the mod can be the only jar with that file at that path.
	 */
	public static KernelModContainer presence(KernelModMetadata metadata, Path loadedFrom, Path shownFrom) {
		boolean elsewhere = shownFrom != null && (loadedFrom == null
				|| !shownFrom.toAbsolutePath().normalize().equals(loadedFrom.toAbsolutePath().normalize()));
		return new KernelModContainer(metadata, null, null, loadedFrom, elsewhere ? shownFrom : null);
	}

	/** {@link ModContainer#findPath}, and for a presence identity the declared icon from the jar that declared it. */
	@Override
	public Optional<Path> findPath(String file) {
		Optional<Path> found = firstExisting(getRootPaths(), file);
		if (found.isPresent() || shownFrom == null || file == null || !metadata.declaresIcon(file)) return found;
		return firstExisting(shownRoots(), file);
	}

	private static Optional<Path> firstExisting(List<Path> roots, String file) {
		for (Path root : roots) {
			Path path = root.resolve(file.replace("/", root.getFileSystem().getSeparator()));
			if (Files.exists(path)) return Optional.of(path);
		}
		return Optional.empty();
	}

	private List<Path> shownRoots() {
		List<Path> roots = shownRoots;
		if (roots != null) return roots;
		synchronized (this) {
			if (shownRoots == null) shownRoots = open(shownFrom);
			return shownRoots;
		}
	}

	/**
	 * Takes this container out of its parent's contained mods: for a nested copy that is not registered after all, so
	 * that, as on Fabric, a mod's contained mods are only the ones that loaded.
	 */
	public void leaveParent() {
		if (parent != null) parent.children.remove(this);
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
		Path jar = this.jar != null ? this.jar : loadedFrom;
		if (jar == null) return List.of();

		List<Path> paths = rootPaths;
		if (paths != null) return paths;

		synchronized (this) {
			if (rootPaths != null) return rootPaths;
			rootPaths = open(jar);
			return rootPaths;
		}
	}

	/** The roots of {@code jar}'s contents: the directory itself, or a zip file system kept open for the JVM's life. */
	private List<Path> open(Path jar) {
		try {
			if (Files.isDirectory(jar)) return List.of(jar);
			FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null);
			List<Path> roots = new ArrayList<>();
			for (Path root : fs.getRootDirectories()) {
				roots.add(root);
			}
			return Collections.unmodifiableList(roots);
		} catch (IOException e) {
			Caught.warn(e, "[Forbric/Fabric] could not open %s for %s", jar.getFileName(), metadata.getId());
			return List.of();
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
