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

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.minecraft.Hooks;

/**
 * Which of Fabric Loader's internals mods can link against, from where, and what the switch takes away.
 *
 * <p>The probe half is the one that can quietly go wrong: these classes did not exist before, so every
 * {@code Class.forName} of them answered "no" — including the Forge-family mods'. Now that they exist, a Forge or
 * NeoForge class must still be told no, or it takes a Fabric branch it was never built for.
 */
@ResourceLock("system-properties")
class FabricLoaderInternalsTest {
	private static final String IMPL = "net.fabricmc.loader.impl.FabricLoaderImpl";
	private static final String LEGACY = "net.fabricmc.loader.FabricLoader";

	@AfterEach
	void restore() {
		System.clearProperty(FabricLoaderInternals.SWITCH);
	}

	@Test
	void aForgeFamilyClassIsStillToldTheyDoNotExist() {
		assumeTrue(LoaderProbePolicy.enabled(), "probe policy disabled by system property");
		ClassLoader here = getClass().getClassLoader();

		for (String marker : List.of(IMPL, LEGACY)) {
			assertTrue(LoaderProbePolicy.isProbe(marker), marker + " must be a probe now that it exists");
			for (LoaderProbePolicy.Family family : List.of(LoaderProbePolicy.Family.FORGE, LoaderProbePolicy.Family.NEOFORGE)) {
				assertThrows(ClassNotFoundException.class, () -> LoaderProbePolicy.forName(marker, false, here, family.name()),
						family + " asking for " + marker);
			}
		}
	}

	@Test
	void aFabricClassFindsThem() throws Exception {
		ClassLoader here = getClass().getClassLoader();

		assertSame(FabricLoaderImpl.class, LoaderProbePolicy.forName(IMPL, false, here, LoaderProbePolicy.Family.FABRIC.name()));
		assertEquals(LEGACY, LoaderProbePolicy.forName(LEGACY, false, here, LoaderProbePolicy.Family.FABRIC.name()).getName());
	}

	/** Pinned by exact name: the kernel's copy, always; a mod's shaded copy of some OTHER internal, still its own. */
	@Test
	void onlyTheShippedNamesArePinned() {
		assertTrue(DelegationPolicy.alwaysParent(IMPL));
		assertTrue(DelegationPolicy.alwaysParent(LEGACY));
		assertTrue(DelegationPolicy.alwaysParent("net.fabricmc.loader.impl.entrypoint.EntrypointStorage$NewEntry"));
		assertTrue(DelegationPolicy.alwaysParent(Hooks.class.getName()));
		assertFalse(DelegationPolicy.alwaysParent("net.fabricmc.loader.impl.util.version.SemanticVersionImpl"),
				"an internal the kernel does not ship keeps the child-first rule it always had");
	}

	/**
	 * Every class compiled into the shipped internals is pinned — a nested class added later and forgotten here would
	 * be child-first, and a mod shading Fabric Loader would then define its own copy next to the kernel's.
	 */
	@Test
	void everyCompiledInternalIsPinned() throws Exception {
		Path root = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main");
		Path internals = root.resolve("net/fabricmc/loader");
		assumeTrue(Files.isDirectory(internals), "compiled classes absent");

		List<String> unpinned;
		try (Stream<Path> files = Files.walk(internals)) {
			unpinned = files.filter(p -> p.toString().endsWith(".class"))
					.map(p -> root.relativize(p).toString().replace('\\', '/'))
					.filter(p -> !p.startsWith("net/fabricmc/loader/api/") && !p.startsWith("net/fabricmc/loader/impl/launch/"))
					.map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.'))
					.filter(name -> !FabricLoaderInternals.pinned(name))
					.toList();
		}
		assertEquals(List.of(), unpinned);
	}

	@Test
	void theClassLoaderHandsModsTheKernelsCopy() throws Exception {
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[0], getClass().getClassLoader())) {
			assertSame(FabricLoaderImpl.class, loader.loadClass(IMPL));
		}
	}

	/** {@code -Dforbric.fabricImpl=off}: not there, as before — except the hook the game itself calls. */
	@Test
	void switchedOffTheyAreNotThere() throws Exception {
		System.setProperty(FabricLoaderInternals.SWITCH, "off");

		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[0], getClass().getClassLoader())) {
			for (String name : FabricLoaderInternals.SWITCHED) {
				assertThrows(ClassNotFoundException.class, () -> loader.loadClass(name), name);
			}
			assertSame(Hooks.class, loader.loadClass(Hooks.class.getName()),
					"Hooks has its own switch; withholding it would break the game's own entry points");
		}
	}
}
