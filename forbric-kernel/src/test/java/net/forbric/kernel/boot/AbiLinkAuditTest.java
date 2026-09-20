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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/** Dangling Forge-family references: found, resolved against the carrier / the jar itself, scoped, and marked by jar. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class AbiLinkAuditTest {
	private static final String GONE = "net/neoforged/neoforge/event/Gone";
	private static final String PRESENT = "net/neoforged/neoforge/event/Present";
	private static final String LOADING = "net/neoforged/fml/loading/moddiscovery/ModsFolderLocator";
	private List<ModCatalog.Entry> previous;

	@AfterEach
	void forget() {
		AbiLinkAudit.reset();
		System.clearProperty(AbiLinkAudit.SWITCH);
		if (previous != null) ModCatalog.publish(previous);
	}

	@Test
	void aClassNamingAForgeFamilyClassNoJarCarriesIsAFinding(@TempDir Path dir) throws Exception {
		Path mod = jar(dir.resolve("mod.jar"), "a/b/Uses", caller("a/b/Uses", GONE));
		Path carrier = jar(dir.resolve("carrier.jar"), PRESENT, empty(PRESENT));
		List<AbiLinkAudit.Finding> findings = AbiLinkAudit.audit(List.of(mod), AbiLinkAudit.classesOf(List.of(carrier, mod)));
		assertEquals(1, findings.size());
		assertEquals("mod.jar", findings.get(0).jar());
		assertEquals("NeoForge", findings.get(0).family());
		assertEquals(List.of(GONE), findings.get(0).missing());
	}

	@Test
	void aClassTheCarrierHasIsNotAFinding(@TempDir Path dir) throws Exception {
		Path mod = jar(dir.resolve("mod.jar"), "a/b/Uses", caller("a/b/Uses", PRESENT));
		Path carrier = jar(dir.resolve("carrier.jar"), PRESENT, empty(PRESENT));
		assertTrue(AbiLinkAudit.audit(List.of(mod), AbiLinkAudit.classesOf(List.of(carrier, mod))).isEmpty());
	}

	@Test
	void aClassTheJarShipsItselfIsNotAFinding(@TempDir Path dir) throws Exception {
		Path mod = dir.resolve("port.jar");
		try (OutputStream out = Files.newOutputStream(mod); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "a/b/Uses", caller("a/b/Uses", "net/minecraftforge/common/ForgeConfigSpec"));
			put(zip, "net/minecraftforge/common/ForgeConfigSpec", empty("net/minecraftforge/common/ForgeConfigSpec"));
		}
		assertTrue(AbiLinkAudit.audit(List.of(mod), AbiLinkAudit.classesOf(List.of(mod))).isEmpty(),
				"a Fabric port of a Forge library ships the classes its dependants name");
	}

	@Test
	void theLoadingLayerTheKernelReplacesIsOutOfScope(@TempDir Path dir) throws Exception {
		Path mod = jar(dir.resolve("mod.jar"), "a/b/Uses", caller("a/b/Uses", LOADING));
		assertTrue(AbiLinkAudit.audit(List.of(mod), AbiLinkAudit.classesOf(List.of(mod))).isEmpty(),
				"CustomSkinLoader's fml/loading references are not a wrong-Forge compile");
		assertTrue(AbiLinkAudit.inScope("net/neoforged/neoforge/common/NeoForge"));
		assertTrue(AbiLinkAudit.inScope("net/minecraftforge/fml/common/Mod"));
		assertTrue(!AbiLinkAudit.inScope("net/minecraftforge/fml/relauncher/Side"));
		assertTrue(!AbiLinkAudit.inScope("net/minecraft/world/level/Level"), "vanilla is not judged");
	}

	@Test
	void anArrayOfAForgeFamilyClassIsResolvedToItsElement() throws Exception {
		Set<String> named = AbiLinkAudit.namedClasses(arrayUser("a/b/Arrays", GONE));
		assertTrue(named.contains(GONE), named.toString());
	}

	@Test
	void reportMarksEveryRowFromTheJarAndSaysSo(@TempDir Path dir) throws Exception {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "one", "One", "1", "", List.of(), "mod.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "two", "Two", "1", "", List.of(), "mod.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.FABRIC, "other", "Other", "1", "", List.of(), "other.jar", "", "")));
		Path mod = jar(dir.resolve("mod.jar"), "a/b/Uses", caller("a/b/Uses", GONE));
		AbiLinkAudit.scan(List.of(mod), List.of());
		AbiLinkAudit.report();
		assertEquals(2, ModCatalog.failures().size());
		for (ModCatalog.Entry e : ModCatalog.failures()) {
			assertEquals(ModCatalog.Status.DEGRADED, e.status());
			assertTrue(e.statusDetail().contains("different NeoForge") && e.statusDetail().contains("net.neoforged.neoforge.event.Gone"),
					e.statusDetail());
		}
	}

	@Test
	void switchedOffNothingIsScannedOrMarked(@TempDir Path dir) throws Exception {
		previous = ModCatalog.everything();
		System.setProperty(AbiLinkAudit.SWITCH, "off");
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.NEOFORGE, "one", "One", "1", "", List.of(), "mod.jar", "", "")));
		Path mod = jar(dir.resolve("mod.jar"), "a/b/Uses", caller("a/b/Uses", GONE));
		AbiLinkAudit.scan(List.of(mod), List.of());
		AbiLinkAudit.report();
		assertTrue(ModCatalog.failures().isEmpty());
	}

	private static Path jar(Path file, String internal, byte[] bytes) throws Exception {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, internal, bytes);
		}
		return file;
	}

	private static void put(ZipOutputStream zip, String internal, byte[] bytes) throws Exception {
		zip.putNextEntry(new ZipEntry(internal + ".class"));
		zip.write(bytes);
		zip.closeEntry();
	}

	/** A class whose one method calls a static on {@code target}. */
	private static byte[] caller(String internal, String target) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, target, "touch", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class whose one method allocates an array of {@code element}. */
	private static byte[] arrayUser(String internal, String element) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()[[L" + element + ";", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitTypeInsn(Opcodes.ANEWARRAY, "[L" + element + ";");
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] empty(String internal) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}
}
