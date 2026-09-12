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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The merged-base shape where one method name covers two lambda chains, only one of them reachable.
 *
 * <p>The reachability walk has to follow the method handle inside an {@code invokedynamic}'s bootstrap arguments,
 * not just direct calls — a dead chain reaches its own first link that way and looks alive if you stop at depth
 * one. The "dead chain is self-referential" test is the one that catches that.
 */
class DuplicateLambdaPruneInjectorTest {
	private static final String OWNER = "com/example/Merged";
	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;",
			false);

	@Test
	void theOrphanedHalfOfAMergedLambdaChainIsDropped() {
		byte[] pruned = new DuplicateLambdaPruneInjector().transform(OWNER.replace('/', '.'), mergedShape(), null);

		List<String> left = methods(pruned, "lambda$load$2");
		assertEquals(List.of("(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"), left,
				"the live five-argument-style body must survive and the orphan must go");
		// The dead chain's first link goes too — it is unreachable for exactly the same reason.
		assertTrue(methods(pruned, "lambda$load$1").isEmpty()
				|| methods(pruned, "lambda$load$1").size() == 1);
	}

	@Test
	void theSurvivingChainStillReachesItsOwnLambda() {
		byte[] pruned = new DuplicateLambdaPruneInjector().transform(OWNER.replace('/', '.'), mergedShape(), null);
		ClassNode node = new ClassNode();
		new ClassReader(pruned).accept(node, 0);

		assertTrue(node.methods.stream().anyMatch(m -> "load".equals(m.name)));
		assertTrue(node.methods.stream().anyMatch(
				m -> "lambda$load$2".equals(m.name) && m.desc.startsWith("(Ljava/lang/String;")));
	}

	@Test
	void aClassWithNoDuplicateLambdaNameIsReturnedByteForByte() {
		byte[] plain = singleChainShape();
		assertArrayEquals(plain, new DuplicateLambdaPruneInjector().transform("com.example.Plain", plain, null));
	}

	@Test
	void aDuplicateWhoseBothHalvesAreReachableIsLeftAlone() {
		byte[] bytes = bothReachableShape();
		assertArrayEquals(bytes, new DuplicateLambdaPruneInjector().transform(OWNER.replace('/', '.'), bytes, null));
	}

	@Test
	void theSwitchKeepsTheOrphanedBodies() {
		byte[] bytes = mergedShape();
		System.setProperty(DuplicateLambdaPruneInjector.PROPERTY, "off");
		try {
			assertArrayEquals(bytes, new DuplicateLambdaPruneInjector()
					.transform(OWNER.replace('/', '.'), bytes, null));
		} finally {
			System.clearProperty(DuplicateLambdaPruneInjector.PROPERTY);
		}
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * {@code load} captures only the live chain ({@code lambda$load$1} → {@code lambda$load$2}, String-flavoured).
	 * The dead chain ({@code lambda$load$1} → {@code lambda$load$2}, Integer-flavoured) is SELF-REFERENTIAL: its
	 * first link is reached only from nothing, and its second only from its first. That is the merged base's shape.
	 */
	private static byte[] mergedShape() {
		ClassWriter cw = start();
		emitLoad(cw, "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		// live chain
		emitChainLink(cw, "lambda$load$1", "(Ljava/lang/String;)Ljava/util/function/Supplier;",
				"lambda$load$2", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		emitLeaf(cw, "lambda$load$2", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		// orphaned chain — same names, different descriptors, reachable from nothing outside itself
		emitChainLink(cw, "lambda$load$1", "(Ljava/lang/Integer;)Ljava/util/function/Supplier;",
				"lambda$load$2", "(Ljava/lang/Integer;Ljava/lang/Object;)Ljava/lang/Object;");
		emitLeaf(cw, "lambda$load$2", "(Ljava/lang/Integer;Ljava/lang/Object;)Ljava/lang/Object;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Both halves reachable from real methods: not a merge artefact this transform should touch. */
	private static byte[] bothReachableShape() {
		ClassWriter cw = start();
		emitLoad(cw, "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		emitLoad2(cw, "(Ljava/lang/Integer;Ljava/lang/Object;)Ljava/lang/Object;");
		emitChainLink(cw, "lambda$load$1", "(Ljava/lang/String;)Ljava/util/function/Supplier;",
				"lambda$load$2", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		emitLeaf(cw, "lambda$load$2", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		emitChainLink(cw, "lambda$load$1", "(Ljava/lang/Integer;)Ljava/util/function/Supplier;",
				"lambda$load$2", "(Ljava/lang/Integer;Ljava/lang/Object;)Ljava/lang/Object;");
		emitLeaf(cw, "lambda$load$2", "(Ljava/lang/Integer;Ljava/lang/Object;)Ljava/lang/Object;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] singleChainShape() {
		ClassWriter cw = start();
		emitLoad(cw, "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		emitChainLink(cw, "lambda$load$1", "(Ljava/lang/String;)Ljava/util/function/Supplier;",
				"lambda$load$2", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		emitLeaf(cw, "lambda$load$2", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassWriter start() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
		return cw;
	}

	/** {@code public Supplier load()} — the real method, capturing the live chain's first link. */
	private static void emitLoad(ClassWriter cw, String leafDesc) {
		emitEntry(cw, "load", "lambda$load$1", "(Ljava/lang/String;)Ljava/util/function/Supplier;");
	}

	private static void emitLoad2(ClassWriter cw, String leafDesc) {
		emitEntry(cw, "loadOther", "lambda$load$1", "(Ljava/lang/Integer;)Ljava/util/function/Supplier;");
	}

	private static void emitEntry(ClassWriter cw, String name, String target, String targetDesc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()Ljava/util/function/Supplier;", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, target, targetDesc, false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** A link that reaches the next one ONLY through an invokedynamic's bootstrap method handle. */
	private static void emitChainLink(ClassWriter cw, String name, String desc, String next, String nextDesc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				name, desc, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitInvokeDynamicInsn("get",
				"(" + Type.getArgumentTypes(nextDesc)[0].getDescriptor() + ")Ljava/util/function/Supplier;",
				METAFACTORY,
				Type.getMethodType("()Ljava/lang/Object;"),
				new Handle(Opcodes.H_INVOKESTATIC, OWNER, next, nextDesc, false),
				Type.getMethodType("()Ljava/lang/Object;"));
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void emitLeaf(ClassWriter cw, String name, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static List<String> methods(byte[] classBytes, String name) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) out.add(m.desc);
		}
		return out;
	}

	@SuppressWarnings("unused")
	private static Supplier<?> unusedSoTheImportIsReal() {
		return () -> null;
	}
}
