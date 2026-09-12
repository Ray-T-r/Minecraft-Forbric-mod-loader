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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;

/**
 * Pins {@link MethodBodyNeuter}'s return synthesis for every descriptor sort.
 *
 * <p>{@code returnFor} is a seven-way switch that has to pair the right constant with the right return opcode —
 * {@code LCONST_0}/{@code LRETURN}, {@code DCONST_0}/{@code DRETURN} and so on. Getting one pair wrong is not a
 * compile error and not a transform-time exception: it produces a class that fails verification only when the JVM
 * first links that method, deep inside a boot the kernel neuters precisely because it must not run.
 *
 * <p>The neuter also has no warn path at all — a target whose descriptor no longer matches is simply skipped in
 * silence, and the method it was meant to stub out runs for real. Both properties are asserted here.
 */
class MethodBodyNeuterTest {
	private static final String OWNER = "com.example.Stubbed";

	/** Every sort the switch handles, each checked by the verifier rather than by eyeballing the opcode. */
	@Test
	void everyReturnSortProducesAVerifiableBody() throws Exception {
		String[] descriptors = {
			"()V", "()Z", "()C", "()B", "()S", "()I", "()J", "()F", "()D",
			"()Ljava/lang/String;", "()[I",
		};

		for (String desc : descriptors) {
			MethodBodyNeuter neuter = new MethodBodyNeuter()
					.add(new MethodBodyNeuter.Target(OWNER, "stubbed", desc, "test"));

			byte[] out = neuter.transform(OWNER, methodWith(desc), ctx());
			assertTrue(out != methodWith(desc), desc + " was not neutered");

			ClassNode node = parse(out);
			MethodNode m = method(node, "stubbed");
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);

			assertTrue(m.maxStack >= sizeOf(desc), desc + ": maxStack " + m.maxStack + " cannot hold the return value");
		}
	}

	/** A long or double return needs two stack slots; a one-slot maxStack would fail to link. */
	@Test
	void wideReturnsGetEnoughStack() throws Exception {
		for (String desc : new String[] {"()J", "()D"}) {
			byte[] out = new MethodBodyNeuter()
					.add(new MethodBodyNeuter.Target(OWNER, "stubbed", desc, "test"))
					.transform(OWNER, methodWith(desc), ctx());

			assertTrue(method(parse(out), "stubbed").maxStack >= 2, desc + " needs two stack slots");
		}
	}

	/** The silent path: same name, different descriptor, so the method it meant to stub keeps running. */
	@Test
	void aTargetWhoseDescriptorNoLongerMatchesIsSkipped() {
		byte[] in = methodWith("()I");
		byte[] out = new MethodBodyNeuter()
				.add(new MethodBodyNeuter.Target(OWNER, "stubbed", "()V", "descriptor drifted"))
				.transform(OWNER, in, ctx());

		assertSame(in, out, "no descriptor match means no rewrite — and, today, no warning either");
	}

	@Test
	void aClassThatOwnsNoTargetIsHandedBackUntouched() {
		byte[] in = methodWith("()V");
		assertSame(in, new MethodBodyNeuter()
				.add(new MethodBodyNeuter.Target("com.example.Other", "stubbed", "()V", "test"))
				.transform(OWNER, in, ctx()));
	}

	/** The original body must be gone, not merely prefixed — the point is that it never runs. */
	@Test
	void theOriginalBodyIsReplacedNotPrepended() {
		byte[] out = new MethodBodyNeuter()
				.add(new MethodBodyNeuter.Target(OWNER, "stubbed", "()V", "test"))
				.transform(OWNER, methodWith("()V"), ctx());

		MethodNode m = method(parse(out), "stubbed");
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			assertTrue(insn.getOpcode() != Opcodes.INVOKESTATIC,
					"the neutered body still calls something — the original code survived");
		}
		assertEquals(1, m.instructions.size(), "a void stub is exactly one RETURN");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static int sizeOf(String desc) {
		char ret = desc.charAt(desc.indexOf(')') + 1);
		return (ret == 'J' || ret == 'D') ? 2 : (ret == 'V' ? 0 : 1);
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
		throw new AssertionError("no method " + name);
	}

	/** {@code static <ret> stubbed()} whose real body calls out, so a surviving call is detectable. */
	private static byte[] methodWith(String desc) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OWNER.replace('.', '/'), null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stubbed", desc, null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
		mv.visitInsn(Opcodes.POP2);
		switch (desc.charAt(desc.indexOf(')') + 1)) {
			case 'V' -> mv.visitInsn(Opcodes.RETURN);
			case 'J' -> { mv.visitInsn(Opcodes.LCONST_1); mv.visitInsn(Opcodes.LRETURN); }
			case 'F' -> { mv.visitInsn(Opcodes.FCONST_1); mv.visitInsn(Opcodes.FRETURN); }
			case 'D' -> { mv.visitInsn(Opcodes.DCONST_1); mv.visitInsn(Opcodes.DRETURN); }
			case 'L', '[' -> { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); }
			default -> { mv.visitInsn(Opcodes.ICONST_1); mv.visitInsn(Opcodes.IRETURN); }
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}
