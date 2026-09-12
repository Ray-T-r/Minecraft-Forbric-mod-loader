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

package net.forbric.kernel.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;

class ModAnnotationScannerTest {
	private static byte[] classBytes(String internalName, String modId) {
		return classBytes(internalName, modId, ModAnnotationScanner.MOD_DESC_NEOFORGE);
	}

	private static byte[] classBytes(String internalName, String modId, String modDescriptor) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		if (modId != null) {
			AnnotationVisitor av = cw.visitAnnotation(modDescriptor, true);
			av.visit("value", modId);
			av.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void findsAllModClassesSorted(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			write(zip, "com/example/BetaMod.class", classBytes("com/example/BetaMod", "beta"));
			write(zip, "com/example/AlphaMod.class", classBytes("com/example/AlphaMod", "alpha"));
			write(zip, "com/example/Plain.class", classBytes("com/example/Plain", null));
		}

		List<ModAnnotationScanner.ModClassInfo> mods = ModAnnotationScanner.scan(jar);

		assertEquals(2, mods.size());
		assertEquals("com.example.AlphaMod", mods.get(0).className);
		assertEquals("alpha", mods.get(0).modId);
		assertEquals("com.example.BetaMod", mods.get(1).className);
		assertEquals("beta", mods.get(1).modId);
	}

	@Test
	void capturesMissingModIdAsNull(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			// @Mod with no value() recorded.
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NoId", null, "java/lang/Object", null);
			cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true).visitEnd();
			cw.visitEnd();
			write(zip, "com/example/NoId.class", cw.toByteArray());
		}

		List<ModAnnotationScanner.ModClassInfo> mods = ModAnnotationScanner.scan(jar);
		assertEquals(1, mods.size());
		assertNull(mods.get(0).modId);
	}

	@Test
	void distinguishesTheTwoModAnnotationFamilies(@TempDir Path dir) throws Exception {
		// The construction ABIs diverge entirely on this bit: a MINECRAFTFORGE @Mod needs an FMLJavaModLoadingContext
		// on a BusGroup, a NEOFORGE one needs (IEventBus, Dist, ModContainer).
		Path jar = dir.resolve("mod.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			write(zip, "com/example/AForge.class",
					classBytes("com/example/AForge", "aforge", ModAnnotationScanner.MOD_DESC_MINECRAFTFORGE));
			write(zip, "com/example/BNeo.class",
					classBytes("com/example/BNeo", "bneo", ModAnnotationScanner.MOD_DESC_NEOFORGE));
		}

		List<ModAnnotationScanner.ModClassInfo> mods = ModAnnotationScanner.scan(jar);

		assertEquals(2, mods.size());
		assertEquals(Ecosystem.FORGE, mods.get(0).family);
		assertEquals(Ecosystem.NEOFORGE, mods.get(1).family);
	}

	private static void write(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}
}
