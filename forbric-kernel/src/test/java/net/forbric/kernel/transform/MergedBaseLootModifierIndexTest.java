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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Both carriers' LootModifierManager after the repair: NeoForge's synthesized prepare, MinecraftForge's wrapped one. */
class MergedBaseLootModifierIndexTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path FORGE_RT = RUN.resolve("forge-runtime/forge-runtime.jar");
	private static final Path NEO_RT = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");

	@Test
	void neoForgesManagerGetsAPrepareThatScansThroughTheHidingView() throws Exception {
		byte[] original = bytesOf(NEO_RT, ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_NEO);
		byte[] out = transform(ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_NEO, original);
		assertNotSame(original, out);
		ClassNode node = parse(out);
		MethodNode prepare = find(node, ForbricMergedBaseCompatTransformer.PREPARE, ForbricMergedBaseCompatTransformer.PREPARE_DESC);
		assertNotNull(prepare, "a prepare(RM,PF)Map was synthesized");
		List<String> real = new ArrayList<>();
		for (AbstractInsnNode insn = prepare.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) continue;
			if (insn instanceof VarInsnNode v) real.add("ALOAD " + v.var);
			else if (insn instanceof MethodInsnNode c) real.add((c.getOpcode() == Opcodes.INVOKESTATIC ? "INVOKESTATIC " : "INVOKESPECIAL ") + c.owner + "." + c.name);
			else real.add(Integer.toString(insn.getOpcode()));
		}
		assertEquals(List.of("ALOAD 0", "ALOAD 1", "INVOKESTATIC " + ForbricMergedBaseCompatTransformer.KERNEL_LOOT_MODIFIERS + ".withoutTheLegacyIndex",
				"ALOAD 2", "INVOKESPECIAL " + ForbricMergedBaseCompatTransformer.SIMPLE_JSON_LISTENER + ".prepare", Integer.toString(Opcodes.ARETURN)), real);
		for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		assertSame(out, transform(ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_NEO, out), "second pass");
	}

	@Test
	void minecraftForgesPrepareScansThroughTheViewAndStillReadsItsOwnIndexByName() throws Exception {
		byte[] original = bytesOf(FORGE_RT, ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_FORGE);
		byte[] out = transform(ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_FORGE, original);
		assertNotSame(original, out);
		ClassNode node = parse(out);
		MethodNode prepare = find(node, ForbricMergedBaseCompatTransformer.PREPARE, ForbricMergedBaseCompatTransformer.PREPARE_DESC);
		int wraps = 0;
		boolean ownIndex = false;
		for (AbstractInsnNode insn = prepare.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode c && ForbricMergedBaseCompatTransformer.KERNEL_LOOT_MODIFIERS.equals(c.owner)) {
				wraps++;
				// …placed right after the aload_1 that feeds super.prepare, and right before the aload_2.
				AbstractInsnNode prev = insn.getPrevious(), next = insn.getNext();
				while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				assertTrue(prev instanceof VarInsnNode p && p.var == 1 && next instanceof VarInsnNode n && n.var == 2, "wraps the manager argument of super.prepare");
			}
			if (insn instanceof LdcInsnNode ldc && "loot_modifiers/global_loot_modifiers.json".equals(ldc.cst)) ownIndex = true;
		}
		assertEquals(1, wraps);
		assertTrue(ownIndex, "premise: MinecraftForge reads its own index by name, and that read is not touched");
		for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		assertSame(out, transform(ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_FORGE, out), "second pass");
	}

	private static byte[] transform(String internal, byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(internal.replace('/', '.'), bytes, null);
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(Path jar, String internal) throws Exception {
		assumeTrue(Files.isRegularFile(jar), "staged carrier absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
