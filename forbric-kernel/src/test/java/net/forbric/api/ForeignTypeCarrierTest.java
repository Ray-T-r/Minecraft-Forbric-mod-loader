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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * Checks every name in {@link ForeignType} against the carrier it is supposed to name.
 *
 * <p>{@link ForeignTypeTest} pins the table against itself — that the roots are right, that the slash form is
 * the slash form. Nothing checked the table against the jars, and a wrong name here <b>does not throw</b>: the
 * transform simply never fires, the reflective lookup lands in a {@code catch (ClassNotFoundException)} written
 * to be tolerant, and the game comes up looking healthy with a feature quietly missing.
 *
 * <p>That is exactly what a NeoForge version bump does. Between 26.2.0.38-beta and 26.2.0.88, NeoForge moved
 * {@code client.gui.ModListScreen} to {@code client.gui.modlist.ModListScreen} — the class the unified mods
 * button redirects away from. Nothing in the build would have said so.
 *
 * <p>Skips when the carriers are not staged, so a fresh clone still builds.
 */
class ForeignTypeCarrierTest {
	private static final Path NEOFORGE = staged("neoforge-runtime", "neoforge-runtime.jar");
	private static final Path FORGE = staged("forge-runtime", "forge-runtime.jar");

	private static Path staged(String dir, String jar) {
		return Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", dir, jar).normalize();
	}

	@Test
	void everyNeoForgeNameIsInTheStagedNeoForgeCarrier() throws IOException {
		assertNamesResolve(Ecosystem.NEOFORGE, NEOFORGE);
	}

	@Test
	void everyMinecraftForgeNameIsInTheStagedForgeCarrier() throws IOException {
		assertNamesResolve(Ecosystem.FORGE, FORGE);
	}

	private static void assertNamesResolve(Ecosystem eco, Path carrier) throws IOException {
		assumeTrue(Files.isRegularFile(carrier), "staged carrier absent: " + carrier);

		Set<String> classes = classesIn(carrier);
		List<String> missing = new ArrayList<>();
		for (ForeignType type : ForeignType.values()) {
			String internal = type.internal(eco);
			if (internal == null) continue;
			// A nested type is carried as Outer$Nested; the table already spells it that way.
			if (!classes.contains(internal + ".class")) missing.add(type + " -> " + type.binary(eco));
		}

		assertTrue(missing.isEmpty(),
				"these ForeignType names are not in " + carrier.getFileName() + ", so every transform and "
						+ "reflective lookup keyed on them silently does nothing: " + missing);
	}

	private static Set<String> classesIn(Path jar) throws IOException {
		Set<String> names = new HashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (entry.getName().endsWith(".class")) names.add(entry.getName());
			}
		}
		return names;
	}
}
