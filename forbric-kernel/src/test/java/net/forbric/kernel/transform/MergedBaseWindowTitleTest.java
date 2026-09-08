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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The window title. The merged base carries one loader's title patch, so it announced that loader on an instance
 * running all three; the brand and its separator go, the asterisk vanilla uses for a modified game stays.
 */
class MergedBaseWindowTitleTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final String MINECRAFT = "net/minecraft/client/Minecraft";

	@Test
	void noLoaderNamesTheWindowAnyMore() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(MINECRAFT + ".class");
		MethodNode before = title(parse(in));
		assertTrue(brands(before).size() == 1, "the merged base must still name one loader: " + brands(before));

		byte[] out = new ForbricMergedBaseCompatTransformer().transform(MINECRAFT.replace('/', '.'), in, null);
		assertTrue(out != in);
		ClassNode node = parse(out);
		MethodNode after = title(node);

		assertEquals(List.of(), brands(after), "no loader may be named in the title");
		assertTrue(chars(after).contains((int) '*'), "vanilla's own mark for a modified game stays");
		assertFalse(chars(after).contains((int) ' '), "…and the separator the brand arrived with goes with it");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, after);
	}

	@Test
	void aSecondPassLeavesTheRepairedTitleAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] once = new ForbricMergedBaseCompatTransformer()
				.transform(MINECRAFT.replace('/', '.'), readClass(MINECRAFT + ".class"), null);
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(MINECRAFT.replace('/', '.'), once, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static List<Object> brands(MethodNode m) {
		List<Object> found = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof LdcInsnNode ldc
					&& ("NeoForge".equals(ldc.cst) || "Forge".equals(ldc.cst) || "Fabric".equals(ldc.cst))) {
				found.add(ldc.cst);
			}
		}
		return found;
	}

	private static List<Integer> chars(MethodNode m) {
		List<Integer> found = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions) {
			if (insn.getOpcode() == Opcodes.BIPUSH) found.add(((IntInsnNode) insn).operand);
		}
		return found;
	}

	private static MethodNode title(ClassNode node) {
		for (MethodNode m : node.methods) {
			if ("createTitle".equals(m.name)) return m;
		}
		throw new AssertionError("Minecraft no longer builds its title in createTitle — re-derive this fixup");
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
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
