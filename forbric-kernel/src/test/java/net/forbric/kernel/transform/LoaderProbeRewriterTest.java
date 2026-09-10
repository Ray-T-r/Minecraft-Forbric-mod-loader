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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.classloading.LoaderProbePolicy;

/**
 * Pins {@link LoaderProbeRewriter}, and above all the string it bakes into guest bytecode.
 *
 * <p>The rewriter pushes {@code Family.name()} as an extra {@code LDC} argument at every {@code Class.forName}
 * site in a single-family guest class, and {@link LoaderProbePolicy#forName} compares that string back with
 * {@code proves.name().equals(askingFamily)}. The two halves are joined by nothing but an identical spelling —
 * no shared constant, no compiler check — and they live in different packages. Swap either side for a
 * display name or a lowercase id and every probe silently answers "not present", which is the branch that put a
 * Fabric mod on the Forge path and produced {@code IncompatibleClassChangeError} out of a mixin config plugin.
 *
 * <p>It had no test at all, which is why this one leads with the round trip rather than the rewrite.
 */
class LoaderProbeRewriterTest {
	private static final String OWNER = "com/example/GuestMod";

	/** THE contract: whatever is baked in must be readable back as the same Family, for every constant. */
	@Test
	void theBakedStringRoundTripsToTheSameFamilyForEveryConstant() {
		for (LoaderProbePolicy.Family family : LoaderProbePolicy.Family.values()) {
			byte[] out = rewrite(oneArgProbe(), family);
			String baked = bakedFamilyString(out);

			assertNotNull(baked, family + ": nothing was baked in");
			assertEquals(family, LoaderProbePolicy.Family.valueOf(baked),
					"the string the rewriter bakes into guest bytecode must be the one LoaderProbePolicy.forName "
							+ "compares back with Family.name() — they are joined only by spelling");
		}
	}

	@Test
	void theSingleArgOverloadGainsTheFamilyArgument() {
		MethodInsnNode call = redirectedCall(rewrite(oneArgProbe(), LoaderProbePolicy.Family.FABRIC));

		assertNotNull(call, "Class.forName(String) was not redirected");
		assertEquals("net/forbric/kernel/classloading/LoaderProbePolicy", call.owner);
		assertEquals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Class;", call.desc);
	}

	@Test
	void theThreeArgOverloadGainsTheFamilyArgument() {
		MethodInsnNode call = redirectedCall(rewrite(threeArgProbe(), LoaderProbePolicy.Family.FORGE_FAMILY));

		assertNotNull(call, "Class.forName(String, boolean, ClassLoader) was not redirected");
		assertEquals("(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;", call.desc);
	}

	/** {@code Class.forName(Module, String)} answers for a named module. It is not a platform probe. */
	@Test
	void theModuleOverloadIsLeftAlone() {
		byte[] in = moduleProbe();
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.FABRIC),
				"the Module overload is not a probe and must not be redirected");
	}

	/** A jar that declares more than one loader (or none) is unowned — it keeps seeing every probe answer yes. */
	@Test
	void aClassFromAnUnownedJarIsUntouched() {
		byte[] in = oneArgProbe();
		assertSame(in, new LoaderProbeRewriter(name -> null).transform("com.example.GuestMod", in, ctx()));
	}

	/** The fast path: no forName in the constant pool means ASM never parses the class. */
	@Test
	void aClassThatNeverProbesIsHandedBackIdentical() {
		byte[] in = noProbe();
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.FABRIC));
	}

	@Test
	void theRewrittenMethodStillVerifies() throws Exception {
		ClassNode node = parse(rewrite(threeArgProbe(), LoaderProbePolicy.Family.FORGE_FAMILY));
		new Analyzer<>(new BasicVerifier()).analyze(node.name, method(node, "probe"));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] rewrite(byte[] in, LoaderProbePolicy.Family family) {
		return new LoaderProbeRewriter(name -> family).transform("com.example.GuestMod", in, ctx());
	}

	/** The LDC the rewriter inserted immediately before the redirected call. */
	private static String bakedFamilyString(byte[] out) {
		List<String> ldcs = new ArrayList<>();
		for (MethodNode m : parse(out).methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) ldcs.add(s);
			}
		}
		// the probed class name is also an LDC, so take the one that is not it
		ldcs.remove("java.lang.Object");
		return ldcs.isEmpty() ? null : ldcs.get(ldcs.size() - 1);
	}

	private static MethodInsnNode redirectedCall(byte[] out) {
		for (MethodNode m : parse(out).methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && "forName".equals(call.name)
						&& !"java/lang/Class".equals(call.owner)) {
					return call;
				}
			}
		}
		return null;
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

	// --- synthetic guests ---------------------------------------------------------------------------------------

	/** {@code static void probe() { Class.forName("java.lang.Object"); }} */
	private static byte[] oneArgProbe() {
		return guest(mv -> {
			mv.visitLdcInsn("java.lang.Object");
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
					"(Ljava/lang/String;)Ljava/lang/Class;", false);
			mv.visitInsn(Opcodes.POP);
		});
	}

	/** {@code static void probe() { Class.forName("java.lang.Object", false, null); }} */
	private static byte[] threeArgProbe() {
		return guest(mv -> {
			mv.visitLdcInsn("java.lang.Object");
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
					"(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
			mv.visitInsn(Opcodes.POP);
		});
	}

	/** {@code Class.forName(Module, String)} — same name, different shape, not a probe. */
	private static byte[] moduleProbe() {
		return guest(mv -> {
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitLdcInsn("java.lang.Object");
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
					"(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;", false);
			mv.visitInsn(Opcodes.POP);
		});
	}

	private static byte[] noProbe() {
		return guest(mv -> mv.visitInsn(Opcodes.NOP));
	}

	private interface Body {
		void emit(org.objectweb.asm.MethodVisitor mv);
	}

	private static byte[] guest(Body body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor mv =
				cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
		mv.visitCode();
		body.emit(mv);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
