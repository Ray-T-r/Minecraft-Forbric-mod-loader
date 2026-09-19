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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Minecraft's own libraries must out-rank a mod jar that bundles a copy of one of them.
 *
 * <h2>What this is protecting</h2>
 *
 * <p>PlayerDataSyncReloaded ships 226 {@code com.google.gson.*} classes at the UNSHADED package name, gson
 * 2.10.1, against the 2.14.0 that Minecraft 26.2 itself uses. While the MC libraries were owned LAST, that copy
 * won and the game died in {@code SharedConstants.tryDetectVersion} with
 * {@code NoSuchMethodError: JsonReader.setStrictness} — reading {@code version.json}, before a single mod had
 * loaded. The same shape had already been paid for once as a hand-written pin: {@link DelegationPolicy}'s
 * NightConfig entry exists because a CARRIER bundles an unshaded old copy.
 *
 * <p>Ordering is the general form of that pin and needs no list, which is why it is worth an invariant rather
 * than another package prefix.
 */
class GameLibraryPrecedenceTest {

	/**
	 * The mechanism the boot order relies on, stated on its own: {@code URLClassLoader} answers from the FIRST
	 * url that has the class, so "which jar goes in first" IS the policy. Both orders are exercised, because a
	 * test that only ran the right one could not tell a fixed order from an accident.
	 */
	@Test
	void theFirstJarWithTheClassIsTheOneThatDefinesIt(@TempDir Path dir) throws Exception {
		Path library = jarWith(dir.resolve("game-library.jar"), "com/example/Shared", "library");
		Path mod = jarWith(dir.resolve("some-mod.jar"), "com/example/Shared", "mod");

		assertEquals("library", markerFrom(library, mod), "library first must win");
		assertEquals("mod", markerFrom(mod, library), "and the reverse order is exactly the bug — so the order "
				+ "KernelBoot builds is not cosmetic");
	}

	/** Drive the same composer as KernelBoot, then ask the real game loader which library actually won. */
	@Test
	void kernelBootAddsTheMcLibrariesBeforeTheModJars(@TempDir Path dir) throws Exception {
		Path library = jarWith(dir.resolve("game-library.jar"), "com/example/Shared", "library");
		Path mod = jarWith(dir.resolve("some-mod.jar"), "com/example/Shared", "mod");
		Path bundled = jarWith(dir.resolve("supplied.jar"), "com/example/Shared", "bundled");
		var composer = Class.forName("net.forbric.kernel.boot.KernelOwnedClasspath")
				.getDeclaredMethod("compose", List.class, List.class, List.class, List.class, List.class);
		composer.setAccessible(true);
		String previous = System.getProperty("forbric.kernelBundledFirst");
		try {
			for (String mode : List.of("on", "off")) {
				System.setProperty("forbric.kernelBundledFirst", mode);
				List<?> owned = (List<?>) composer.invoke(null, List.of(), List.of(library),
						List.of(mod), List.of(), List.of(bundled));
				URL[] urls = owned.toArray(URL[]::new);
				try (ForbricClassLoader loader = new ForbricClassLoader(urls, getClass().getClassLoader())) {
					Class<?> shared = loader.loadClass("com.example.Shared");
					assertEquals("library", shared.getDeclaredField("FROM").get(null));
					assertEquals(library.toUri().toURL(), shared.getProtectionDomain().getCodeSource().getLocation(),
							"Minecraft's own library must win even when a supplied library is also present");
				}
			}
		} finally {
			if (previous == null) System.clearProperty("forbric.kernelBundledFirst");
			else System.setProperty("forbric.kernelBundledFirst", previous);
		}
	}

	private static String markerFrom(Path first, Path second) throws Exception {
		try (URLClassLoader loader = new URLClassLoader(
				new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
			Class<?> shared = loader.loadClass("com.example.Shared");
			return (String) shared.getDeclaredField("FROM").get(null);
		}
	}

	/** A jar holding one class whose {@code FROM} constant says which jar it came out of. */
	private static Path jarWith(Path jar, String internalName, String marker) throws IOException {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FROM",
				"Ljava/lang/String;", null, marker).visitEnd();
		cw.visitEnd();
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(internalName + ".class"));
			zip.write(cw.toByteArray());
			zip.closeEntry();
		}
		return jar;
	}
}
