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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two directories a Fabric mod asks about before it writes anything.
 *
 * <p>Both answers used to be whatever the launcher passed. A RELATIVE path resolves against the process's
 * working directory, which is not the game directory for every launcher, so a mod that resolved its own files
 * against it wrote them somewhere else entirely. And a mod writing straight into the config directory failed on
 * a first run, because nothing had created it.
 */
class KernelFabricLoaderDirectoriesTest {

	@Test
	void aRelativePathIsMadeAbsolute() {
		Path made = KernelFabricLoader.absolute(Path.of("run/server"));

		assertTrue(made.isAbsolute(), "a mod resolving against this must not depend on the working directory");
		assertTrue(made.endsWith(Path.of("run/server")), "it has to still name the same place");
	}

	@Test
	void anAbsolutePathIsNormalisedButNotMoved(@TempDir Path dir) {
		Path made = KernelFabricLoader.absolute(dir.resolve("a/../b"));

		assertEquals(dir.resolve("b"), made);
	}

	@Test
	void aNullPathStaysNull() {
		assertNull(KernelFabricLoader.absolute(null));
		assertNull(KernelFabricLoader.created(null));
	}

	@Test
	void theConfigDirectoryIsCreated(@TempDir Path dir) {
		Path config = dir.resolve("config");

		assertEquals(config, KernelFabricLoader.created(config));
		assertTrue(Files.isDirectory(config), "a mod writing its config on a first run has nowhere to put it");
	}

	@Test
	void anExistingDirectoryIsLeftAsItIs(@TempDir Path dir) throws Exception {
		Path config = Files.createDirectories(dir.resolve("config"));
		Files.writeString(config.resolve("mod.json"), "{}");

		KernelFabricLoader.created(config);

		assertTrue(Files.exists(config.resolve("mod.json")), "creating must never disturb what is already there");
	}

	@Test
	void aDirectoryThatCannotBeCreatedIsStillReturned(@TempDir Path dir) throws Exception {
		// Failure here is not fatal: the mod's own write fails and says so with the mod's name on it, which beats
		// failing the boot for every other mod.
		Path blocked = Files.writeString(dir.resolve("afile"), "not a directory").resolve("config");

		assertEquals(blocked, KernelFabricLoader.created(blocked));
	}
}
