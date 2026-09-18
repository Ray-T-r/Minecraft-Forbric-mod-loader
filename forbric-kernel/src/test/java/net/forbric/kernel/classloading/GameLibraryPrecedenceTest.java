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

	/**
	 * The order itself, read from the source that establishes it.
	 *
	 * <p>Asserted here rather than by booting, because the thing that can regress is a line moving: the library
	 * loop sat after the mod loop for the whole life of the loader, with a comment explaining only its position
	 * relative to the merged base. Nothing would have caught it moving back.
	 */
	@Test
	void kernelBootAddsTheMcLibrariesBeforeTheModJars() throws Exception {
		Path source = Path.of(System.getProperty("user.dir"), "src", "main", "java", "net", "forbric", "kernel",
				"boot", "KernelBoot.java").normalize();
		assertTrue(Files.isRegularFile(source), "KernelBoot.java not found at " + source);
		String text = Files.readString(source);

		int libraries = text.indexOf("for (Path lib : libraryJars(libraryPath))");
		int mods = text.indexOf("for (Path jar : modJars) owned.add(");
		assertTrue(libraries > 0, "the MC library jars are no longer added to the owned set by that loop");
		assertTrue(mods > 0, "the mod jars are no longer added to the owned set by that loop");
		assertTrue(libraries < mods,
				"the MC libraries must be owned BEFORE the mod jars. After them, a mod that bundles an unshaded "
						+ "copy of a library the game already has wins, and the game dies on the older copy's API "
						+ "before any mod has loaded");
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
