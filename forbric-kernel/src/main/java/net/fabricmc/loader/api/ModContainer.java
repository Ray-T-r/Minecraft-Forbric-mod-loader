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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Represents a mod. Mod-facing ABI from Fabric Loader (Apache-2.0, Copyright FabricMC). */
public interface ModContainer {
	ModMetadata getMetadata();

	List<Path> getRootPaths();

	default Optional<Path> findPath(String file) {
		for (Path root : getRootPaths()) {
			Path path = root.resolve(file.replace("/", root.getFileSystem().getSeparator()));
			if (Files.exists(path)) return Optional.of(path);
		}

		return Optional.empty();
	}

	ModOrigin getOrigin();

	Optional<ModContainer> getContainingMod();

	Collection<ModContainer> getContainedMods();

	/** @deprecated use {@link #getRootPaths()} */
	@Deprecated
	default Path getRoot() {
		return getRootPath();
	}

	/** @deprecated use {@link #getRootPaths()} */
	@Deprecated
	Path getRootPath();

	/** @deprecated use {@link #findPath} */
	@Deprecated
	Path getPath(String file);
}
