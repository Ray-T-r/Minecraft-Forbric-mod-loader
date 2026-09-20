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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The one insertion in the merged {@code OverlayEntry.listCodecForPackType}, and the funnel premise around it. */
class MergedBaseOverlayConditionsTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String SECTION = "net/minecraft/server/packs/OverlayMetadataSection";

	@Test
	void theOverlayListCodecIsWrappedRightAfterNeoForgesConditionalDecoder() throws Exception {
		byte[] original = bytesOf(ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY);
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY.replace('/', '.'), original, null);
		assertNotSame(original, out);
		ClassNode node = parse(out);
		MethodNode method = find(node, ForbricMergedBaseCompatTransformer.LIST_CODEC_FOR_PACK_TYPE, ForbricMergedBaseCompatTransformer.LIST_CODEC_DESC);
		int wraps = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call) || !ForbricMergedBaseCompatTransformer.KERNEL_NEO_CONDITIONS_CLASS.equals(call.owner)) continue;
			wraps++;
			assertEquals("forOverlayEntries", call.name);
			assertEquals(ForbricMergedBaseCompatTransformer.CODEC_TO_CODEC, call.desc);
			assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
			AbstractInsnNode prev = call.getPrevious();
			while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
			assertTrue(prev instanceof MethodInsnNode neo && ForbricMergedBaseCompatTransformer.CONDITIONAL_OPS_NEO.equals(neo.owner)
					&& ForbricMergedBaseCompatTransformer.DECODE_LIST_WITH_CONDITIONS.equals(neo.name), "wrapped right after NeoForge's conditional list decoder");
		}
		assertEquals(1, wraps, "exactly one wrap");
		for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY.replace('/', '.'),
				bytesOf(ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY), null);
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY.replace('/', '.'), once, null));
	}

	/** The premise: both section types read their entries through this one method, so one wrap covers both. */
	@Test
	void bothOverlaySectionsReadThroughTheWrappedMethod() throws Exception {
		ClassNode section = parse(bytesOf(SECTION));
		int callers = 0;
		boolean funnel = false, vanilla = false, neo = false;
		for (MethodNode m : section.methods) {
			if ("forPackType".equals(m.name)) vanilla = true;
			if ("forPackTypeNeoForge".equals(m.name)) neo = true;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY.equals(call.owner)
						&& ForbricMergedBaseCompatTransformer.LIST_CODEC_FOR_PACK_TYPE.equals(call.name)) funnel = true;
				// Both MetadataSectionTypes (the vanilla `overlays` and the `neoforge:overlays` one) are built in
				// <clinit> over codecForPackType — one call each.
				if (insn instanceof MethodInsnNode call && SECTION.equals(call.owner) && "codecForPackType".equals(call.name)) callers++;
			}
		}
		assertTrue(funnel, "codecForPackType calls OverlayEntry.listCodecForPackType");
		assertTrue(vanilla && neo, "both section accessors exist");
		assertEquals(2, callers, "the vanilla and the NeoForge section type both build on codecForPackType");
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		throw new AssertionError(name + desc);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
