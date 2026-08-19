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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The hook that gives the kernel the SERVER datapack repository.
 *
 * <p>The load-bearing property is that it PREPENDS. The rest of {@code populatePackRepository} posts
 * {@code AddPackFindersEvent}, which is how a mod registers a builtin datapack of its own — Tectonic's terrain
 * arrives that way. Replacing the body, the way the client-side hook legitimately does to its own target, would
 * silently take that away, so the original body's survival is asserted directly.
 */
class DataPackHookInjectorTest {
	private static final String TARGET = "net/neoforged/neoforge/resource/ResourcePackLoader";
	private static final String METHOD = "populatePackRepository";
	private static final String DESC =
			"(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/PackType;Z)V";

	@Test
	void prependsTheKernelHookAndKeepsTheOriginalBody() {
		byte[] out = new DataPackHookInjector().transform(
				TARGET.replace('/', '.'), populatePackRepository(TARGET, METHOD, DESC), null);
		assertNotNull(out);

		List<AbstractInsnNode> real = realInstructions(out, METHOD, DESC);
		assertTrue(real.size() >= 4, "expected the hook plus the original body, got " + real.size());

		assertEquals(Opcodes.ALOAD, real.get(0).getOpcode());
		assertEquals(0, ((VarInsnNode) real.get(0)).var, "arg0 is the PackRepository");
		assertEquals(Opcodes.ALOAD, real.get(1).getOpcode());
		assertEquals(1, ((VarInsnNode) real.get(1)).var, "arg1 is the PackType");

		MethodInsnNode hook = (MethodInsnNode) real.get(2);
		assertEquals(Opcodes.INVOKESTATIC, hook.getOpcode());
		assertEquals("net/forbric/kernel/boot/KernelLifecycle", hook.owner);
		assertEquals("onServerDataPacks", hook.name);
		assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)V", hook.desc);
	}

	@Test
	void theAddPackFindersPostSurvives() {
		byte[] out = new DataPackHookInjector().transform(
				TARGET.replace('/', '.'), populatePackRepository(TARGET, METHOD, DESC), null);

		boolean stillPosts = realInstructions(out, METHOD, DESC).stream()
				.anyMatch(insn -> insn instanceof MethodInsnNode call && "postEvent".equals(call.name));
		assertTrue(stillPosts, "the original AddPackFindersEvent post must not be replaced");
	}

	@Test
	void leavesEveryOtherClassAlone() {
		byte[] original = populatePackRepository("some/other/Class", METHOD, DESC);
		assertArrayEquals(original,
				new DataPackHookInjector().transform("some.other.Class", original, null));
	}

	@Test
	void failsSoftWhenTheAnchorIsGone() {
		// A NeoForge that renamed or re-signed the method: the kernel must hand the class back untouched and say so,
		// not throw. The absence it guards is silent, so the warning is the only way anyone finds out.
		byte[] original = populatePackRepository(TARGET, "populatePackRepositoryV2", DESC);
		assertArrayEquals(original,
				new DataPackHookInjector().transform(TARGET.replace('/', '.'), original, null));
	}

	/** A stand-in for NeoForge's method: takes the three real arguments and posts an event. */
	private static byte[] populatePackRepository(String owner, String name, String desc) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/fml/ModLoader", "postEvent",
				"(Ljava/lang/Object;)V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 3);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static List<AbstractInsnNode> realInstructions(byte[] classBytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!name.equals(m.name) || !desc.equals(m.desc)) continue;
			List<AbstractInsnNode> real = new ArrayList<>();
			for (AbstractInsnNode insn : m.instructions) {
				if (insn.getOpcode() >= 0) real.add(insn);
			}
			return real;
		}
		throw new AssertionError(name + desc + " not found");
	}
}
