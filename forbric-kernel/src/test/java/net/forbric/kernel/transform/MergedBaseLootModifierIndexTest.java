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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** NeoForge's LootModifierManager after the repair: a synthesized prepare that scans through the hiding view. */
class MergedBaseLootModifierIndexTest {
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final Path NEO_RT = RUN.resolve(TestFixtures.NEO_CARRIER_JAR);

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
		TestFixtures.requireFile(Fixture.STAGED, jar, "staged carrier absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
