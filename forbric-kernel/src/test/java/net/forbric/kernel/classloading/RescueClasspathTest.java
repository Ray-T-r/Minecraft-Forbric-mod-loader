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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The last-resort lookup into jars that cross-jar arbitration superseded.
 *
 * <p>This is the A/B/C case made concrete: mod C ships a Fabric jar and a NeoForge jar, only one survives, and a
 * mod on the losing side is built against a class that exists ONLY in the copy that was dropped. On the two real
 * packs no such reference exists (40 platform-only classes across nine superseded jars, referenced by none of the
 * 89 surviving ones), so it is synthesised here.
 *
 * <p>The second test is the load-bearing one: rescue must never be able to shadow the winner.
 */
class RescueClasspathTest {

	/** A class whose {@code value()} returns {@code marker} — enough to tell two builds of one class apart. */
	private static byte[] classNamed(String internalName, String marker) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		MethodVisitor value = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value",
				"()Ljava/lang/String;", null, null);
		value.visitCode();
		value.visitLdcInsn(marker);
		value.visitInsn(Opcodes.ARETURN);
		value.visitMaxs(0, 0);
		value.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static URL jarWith(Path jar, String binaryName, String marker) throws Exception {
		String internal = binaryName.replace('.', '/');
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(internal + ".class"));
			zip.write(classNamed(internal, marker));
			zip.closeEntry();
		}
		return jar.toUri().toURL();
	}

	@Test
	void aClassOnlyTheSupersededJarHasIsStillLoadable(@TempDir Path dir) throws Exception {
		URL winner = jarWith(dir.resolve("c-fabric.jar"), "forbrictest.Shared", "fabric");
		URL superseded = jarWith(dir.resolve("c-neoforge.jar"), "forbrictest.OnlyOnNeoForge", "neoforge");

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {winner}, getClass().getClassLoader())) {
			loader.setRescueJars(List.of(superseded));

			Class<?> rescued = loader.loadClass("forbrictest.OnlyOnNeoForge");

			assertNotNull(rescued);
			assertEquals("neoforge", rescued.getMethod("value").invoke(null),
					"without the rescue this is a bare NoClassDefFoundError with nothing pointing at the cause");
		}
	}

	@Test
	void theSupersededJarCanNeverShadowTheWinner(@TempDir Path dir) throws Exception {
		// Both jars carry the same class NAME with different bytes — the real shape: 90 of Jade's 436 shared
		// classes differ between its two builds. The rescue is consulted only after findResource misses, so the
		// winner's copy must win every time; this is what makes the disjointness structural rather than computed.
		URL winner = jarWith(dir.resolve("c-fabric.jar"), "forbrictest.Shared", "fabric");
		URL superseded = jarWith(dir.resolve("c-neoforge.jar"), "forbrictest.Shared", "neoforge");

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {winner}, getClass().getClassLoader())) {
			loader.setRescueJars(List.of(superseded));

			Class<?> shared = loader.loadClass("forbrictest.Shared");

			assertEquals("fabric", shared.getMethod("value").invoke(null));
		}
	}

	@Test
	void withNoRescueSetTheClassIsSimplyAbsent(@TempDir Path dir) throws Exception {
		URL winner = jarWith(dir.resolve("c-fabric.jar"), "forbrictest.Shared", "fabric");

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {winner}, getClass().getClassLoader())) {
			// The default for every instance that has no duplicates: behaviour is exactly as before this existed.
			assertThrows(ClassNotFoundException.class, () -> loader.loadClass("forbrictest.OnlyOnNeoForge"));
		}
	}

	@Test
	void anEmptyOrNullRescueListInstallsNothing(@TempDir Path dir) throws Exception {
		URL winner = jarWith(dir.resolve("c-fabric.jar"), "forbrictest.Shared", "fabric");

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {winner}, getClass().getClassLoader())) {
			loader.setRescueJars(List.of());
			assertThrows(ClassNotFoundException.class, () -> loader.loadClass("forbrictest.OnlyOnNeoForge"));

			loader.setRescueJars(null);
			assertThrows(ClassNotFoundException.class, () -> loader.loadClass("forbrictest.OnlyOnNeoForge"));
		}
	}

	@Test
	void mixinSeesTheSameBytesThatWereDefined(@TempDir Path dir) throws Exception {
		// getPreMixinClassBytes feeds Mixin's bytecode provider. If it did not follow the same fallback, Mixin would
		// inspect a class it believes absent while the loader defines it from the superseded jar.
		URL winner = jarWith(dir.resolve("c-fabric.jar"), "forbrictest.Shared", "fabric");
		URL superseded = jarWith(dir.resolve("c-neoforge.jar"), "forbrictest.OnlyOnNeoForge", "neoforge");

		try (ForbricClassLoader loader =
				new ForbricClassLoader(new URL[] {winner}, getClass().getClassLoader())) {
			loader.setRescueJars(List.of(superseded));

			assertNotNull(loader.getPreMixinClassBytes("forbrictest.OnlyOnNeoForge"));
		}
	}
}
