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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The block-breaking overlay's model-data lookup. On the merged base the level offers only NeoForge's model-data
 * manager, so MinecraftForge's accessor falls through to an interface default that returns null and the render
 * frame dies the moment any block is being broken. The lookup must be gone, and what it produced must be
 * MinecraftForge's own empty model data.
 */
class MergedBaseBlockBreakingOverlayTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final String EXTRACTOR = "net/minecraft/client/renderer/extract/LevelExtractor";
	private static final String FORGE_MANAGER = "net/minecraftforge/client/model/data/ModelDataManager";
	private static final String FORGE_MODEL_DATA = "net/minecraftforge/client/model/data/ModelData";

	@Test
	void theOverlayNoLongerAsksForAManagerThatIsAlwaysNull() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(EXTRACTOR + ".class");
		assertTrue(callsForgeManager(parse(in)), "the merged base must still carry the crash — if not, re-derive this");

		byte[] out = transform(in);
		assertTrue(out != in);
		ClassNode after = parse(out);
		assertFalse(callsForgeManager(after), "nothing may dereference MinecraftForge's model-data manager");

		MethodNode extract = method(after, "extractBlockDestroyAnimation");
		int empties = 0;
		for (AbstractInsnNode insn : extract.instructions) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& FORGE_MODEL_DATA.equals(field.owner) && "EMPTY".equals(field.name)) {
				empties++;
			}
		}
		assertEquals(1, empties, "the lookup is replaced by MinecraftForge's own empty model data, once");
		new Analyzer<>(new BasicVerifier()).analyze(after.name, extract);
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] once = transform(readClass(EXTRACTOR + ".class"));
		assertSame(once, transform(once), "a class with no such lookup left is coherent and must not be touched");
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static boolean callsForgeManager(ClassNode node) {
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && FORGE_MANAGER.equals(call.owner)) return true;
			}
		}
		return false;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(EXTRACTOR.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		throw new AssertionError("no method " + name + " in " + node.name);
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
