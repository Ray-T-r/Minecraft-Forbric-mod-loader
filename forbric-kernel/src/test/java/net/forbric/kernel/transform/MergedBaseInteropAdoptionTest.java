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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Covers the kernel adopting the interop hooks the merged base still names after the previous-generation loader.
 *
 * <p>The base is built by that loader's {@code MergedBaseBuilder}, which splices
 * {@code INVOKESTATIC net/forbric/loader/impl/compat/ForbricCustomPayloadInterop.findCodec} into the merged
 * {@code CustomPacketPayload} codec provider. Those classes now live in {@code net.forbric.kernel.interop} and the
 * old loader jars are off the boot classpath, so the baked-in owner no longer resolves. Left alone the symptom is a
 * {@code NoClassDefFoundError} inside the netty encoder on the first custom payload — i.e. every world join, with a
 * stack that names Minecraft.
 *
 * <p>This is deliberately checked against the REAL staged base rather than a hand-built class: the thing that can
 * drift is what the other repository's builder emits, and a synthetic fixture would keep passing while the artifact
 * changed underneath it.
 */
class MergedBaseInteropAdoptionTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String LEGACY = "net/forbric/loader/impl/";

	@Test
	void everyCallTheBaseMakesUnderTheOldNameIsAdoptedAndResolves() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		List<String> carriers = entriesNaming(LEGACY);
		assumeTrue(!carriers.isEmpty(),
				"this base names no old-loader hook — nothing to adopt (a rebuilt base may have stopped)");

		for (String entry : carriers) {
			byte[] in = readClass(entry);
			byte[] out = new ForbricMergedBaseCompatTransformer()
					.transform(entry.substring(0, entry.length() - 6).replace('/', '.'), in, null);
			assertTrue(out != in, entry + " names the old loader but was returned unchanged");
			assertFalse(ForbricMergedBaseCompatTransformer.stillNamesTheOldLoader(out),
					entry + " still names " + LEGACY + " after adoption");
			InteropHookAssertions.assertEveryInteropCallResolves(out);
		}
	}

	/**
	 * The Forge {@code getFluidType()} bridge the merge dropped is re-added pointing at a kernel method — the one
	 * hook that is written by the kernel rather than inherited from the base, so nothing else would catch a rename.
	 */
	@Test
	void theReAddedForgeFluidTypeBridgeResolves() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		String fluid = null;
		for (String entry : entriesUnder("net/minecraft/world/level/material/")) {
			if (contains(readClass(entry), "net/neoforged/neoforge/common/extensions/IFluidExtension")) {
				byte[] out = new ForbricMergedBaseCompatTransformer()
						.transform(entry.substring(0, entry.length() - 6).replace('/', '.'), readClass(entry), null);
				if (containsInterop(out)) {
					fluid = entry;
					InteropHookAssertions.assertEveryInteropCallResolves(out);
					break;
				}
			}
		}
		assumeTrue(fluid != null, "no merged fluid needed the Forge bridge in this base");
	}

	/**
	 * A legacy reference in a shape the adoption does not rewrite is REPORTED, not swallowed.
	 *
	 * <p>The builder only ever emits a method owner, so the rewrite only walks method owners. If it ever emits a
	 * {@code new}, a field owner or a class constant instead, the class would sail through unchanged and fail to
	 * link somewhere else entirely. The detector re-reads the finished bytes so that is said out loud.
	 */
	@Test
	void aLegacyReferenceInAnUnhandledShapeIsStillDetected() {
		byte[] typeOnly = classNewingTheLegacyOwner();
		assertTrue(ForbricMergedBaseCompatTransformer.stillNamesTheOldLoader(typeOnly));

		byte[] out = new ForbricMergedBaseCompatTransformer().transform("some.Guest", typeOnly, null);
		assertSame(typeOnly, out, "nothing this pass rewrites, so the bytes come back as they were");
		assertTrue(ForbricMergedBaseCompatTransformer.stillNamesTheOldLoader(out),
				"the detector is what makes an unhandled shape visible — it must still see it");
	}

	@Test
	void aClassThatNeverNamedTheOldLoaderIsLeftAlone() {
		byte[] plain = plainClass();
		assertFalse(ForbricMergedBaseCompatTransformer.stillNamesTheOldLoader(plain));
		assertSame(plain, new ForbricMergedBaseCompatTransformer().transform("some.Plain", plain, null));
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	private static byte[] classNewingTheLegacyOwner() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "some/Guest", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
				"()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, LEGACY + "compat/ForbricCustomPayloadInterop");
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] plainClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "some/Plain", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nothing", "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static boolean containsInterop(byte[] bytes) {
		return contains(bytes, InteropHookAssertions.INTEROP_PACKAGE);
	}

	private static boolean contains(byte[] haystack, String needle) {
		byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + n.length <= haystack.length; i++) {
			for (int j = 0; j < n.length; j++) {
				if (haystack[i + j] != n[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static List<String> entriesNaming(String needle) throws Exception {
		List<String> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					if (contains(in.readAllBytes(), needle)) out.add(entry.getName());
				}
			}
		}
		return out;
	}

	private static List<String> entriesUnder(String prefix) throws Exception {
		List<String> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				if (entry.getName().startsWith(prefix) && entry.getName().endsWith(".class")) out.add(entry.getName());
			}
		}
		return out;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			try (InputStream in = zip.getInputStream(zip.getEntry(entry))) {
				return in.readAllBytes();
			}
		}
	}
}
