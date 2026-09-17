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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	void readsTheSidesANeoForgeModDeclares(@TempDir Path dir) throws Exception {
		// Sodium's SodiumForgeMod is annotated @Mod(value = "sodium", dist = {Dist.CLIENT}) and was being
		// constructed on dedicated servers, where the first client type its constructor touches throws.
		Path jar = dir.resolve("sodium.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "me/jellysquid/SodiumForgeMod", null, "java/lang/Object", null);
			AnnotationVisitor av = cw.visitAnnotation(ModAnnotationScanner.MOD_DESC_NEOFORGE, true);
			av.visit("value", "sodium");
			AnnotationVisitor array = av.visitArray("dist");
			array.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "CLIENT");
			array.visitEnd();
			av.visitEnd();
			cw.visitEnd();
			write(zip, "me/jellysquid/SodiumForgeMod.class", cw.toByteArray());
		}

		ModAnnotationScanner.ModClassInfo mod = ModAnnotationScanner.scan(jar).get(0);

		assertEquals(java.util.Set.of("CLIENT"), mod.dists);
		assertTrue(mod.runsOn("CLIENT"));
		assertFalse(mod.runsOn("DEDICATED_SERVER"),
				"a client-only @Mod must not be constructed on a dedicated server");
	}

	@Test
	void aModThatDeclaresNoSideRunsOnBoth(@TempDir Path dir) throws Exception {
		// The annotation's own default, and the only honest reading of a @Mod that says nothing. Traditional
		// MinecraftForge's @Mod has no dist() at all, so every Forge-family mod lands here.
		Path jar = dir.resolve("plain.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			write(zip, "com/example/Both.class", classBytes("com/example/Both", "both"));
		}

		ModAnnotationScanner.ModClassInfo mod = ModAnnotationScanner.scan(jar).get(0);

		assertTrue(mod.dists.isEmpty());
		assertTrue(mod.runsOn("CLIENT"));
		assertTrue(mod.runsOn("DEDICATED_SERVER"));
	}

	@Test
	void readsBothSidesWhenTheModListsThem(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("two.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Two", null, "java/lang/Object", null);
			AnnotationVisitor av = cw.visitAnnotation(ModAnnotationScanner.MOD_DESC_NEOFORGE, true);
			av.visit("value", "two");
			AnnotationVisitor array = av.visitArray("dist");
			array.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "CLIENT");
			array.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "DEDICATED_SERVER");
			array.visitEnd();
			av.visitEnd();
			cw.visitEnd();
			write(zip, "com/example/Two.class", cw.toByteArray());
		}

		ModAnnotationScanner.ModClassInfo mod = ModAnnotationScanner.scan(jar).get(0);

		assertEquals(java.util.Set.of("CLIENT", "DEDICATED_SERVER"), mod.dists);
		assertTrue(mod.runsOn("DEDICATED_SERVER"));
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
