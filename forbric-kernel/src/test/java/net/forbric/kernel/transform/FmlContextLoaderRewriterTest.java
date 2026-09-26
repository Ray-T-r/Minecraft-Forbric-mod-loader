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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.mixin.MixinWeaverSlot;

/**
 * {@link FmlContextLoaderRewriter} puts {@code KernelFmlTransformerView.contextLoader} between a NeoForge mod's
 * {@code getContextClassLoader()} and its cast to FML's {@code TransformingClassLoader} — there and nowhere else.
 *
 * <p>LibJF's mixin plugin ({@code libjf_unsafe_v0}) is the class that paid for it: that pair is its first two
 * instructions, and under the kernel the cast threw, so "Could not initialize LibJF ASM" and no {@code libjf:asm}
 * patch ever ran.
 */
class FmlContextLoaderRewriterTest {
	private static final String OWNER = "com/example/NeoPlugin";
	private static final String PLUGIN = "dev/jfronny/libjf/unsafe/MixinPlugin.class";

	@AfterEach
	void clearSwitch() {
		System.clearProperty(MixinWeaverSlot.SWITCH);
	}

	@Test
	void theCastOfTheContextLoaderInANeoForgeModGetsTheViewInBetween() {
		byte[] out = rewrite(pluginShaped(), LoaderProbePolicy.Family.NEOFORGE);

		List<AbstractInsnNode> code = instructions(out, "onLoad");
		int view = indexOfView(code);
		assertTrue(view > 0, "no call to the view was inserted");
		MethodInsnNode before = (MethodInsnNode) code.get(view - 1);
		assertEquals("getContextClassLoader", before.name, "the view takes what getContextClassLoader returned");
		TypeInsnNode cast = (TypeInsnNode) code.get(view + 1);
		assertEquals(Opcodes.CHECKCAST, cast.getOpcode());
		assertEquals(FmlContextLoaderRewriter.TRANSFORMING_LOADER, cast.desc,
				"the cast stays: with no view it must fail exactly as before");
		MethodInsnNode call = (MethodInsnNode) code.get(view);
		assertEquals("(Ljava/lang/ClassLoader;)Ljava/lang/ClassLoader;", call.desc);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
	}

	@Test
	void aContextLoaderThatIsNotCastIsLeftAlone() {
		byte[] in = contextLoaderOnly();
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.NEOFORGE),
				"every other reader of the context loader keeps getting the real one");
	}

	@Test
	void onlyNeoForgeModsAreRewritten() {
		byte[] in = pluginShaped();
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.FABRIC));
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.FORGE),
				"MinecraftForge's TransformingClassLoader is modlauncher's, another class altogether");
		assertSame(in, rewrite(in, null), "the merged base, the carriers and the kernel are never rewritten");
	}

	@Test
	void aCastThatSomethingJumpsIntoIsNotTheSameExpression() {
		byte[] in = castAtAJumpTarget();
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.NEOFORGE));
	}

	@Test
	void switchedOffNothingIsRewritten() {
		System.setProperty(MixinWeaverSlot.SWITCH, "off");
		byte[] in = pluginShaped();
		assertSame(in, rewrite(in, LoaderProbePolicy.Family.NEOFORGE));
	}

	@Test
	void theRewrittenClassStillVerifies() throws Exception {
		byte[] out = rewrite(pluginShaped(), LoaderProbePolicy.Family.NEOFORGE);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		for (MethodNode method : node.methods) {
			new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier())
					.analyze(node.name, method);
		}
	}

	/** The real class: LibJF's mixin plugin as it ships inside libjf-26.2.2+forge.jar, one rewrite and one only. */
	@Test
	void libJfsOwnMixinPluginIsRewrittenAtItsOneCast() throws Exception {
		byte[] plugin = libJfPlugin();
		assumeTrue(plugin != null, "the sweep90 pack (build/compat-inputs) is not linked into this checkout");

		byte[] out = rewrite(plugin, LoaderProbePolicy.Family.NEOFORGE);
		List<AbstractInsnNode> code = instructions(out, "onLoad");
		int views = 0;
		for (AbstractInsnNode insn : code) {
			if (insn instanceof MethodInsnNode call && FmlContextLoaderRewriter.VIEW.equals(call.owner)) views++;
		}
		assertEquals(1, views);
		assertEquals(Opcodes.CHECKCAST, code.get(indexOfView(code) + 1).getOpcode());
		// Everything else in the method is what LibJF wrote, apart from the one instruction.
		assertEquals(instructions(plugin, "onLoad").size() + 1, code.size());
		assertArrayEquals(plugin, rewrite(plugin, LoaderProbePolicy.Family.FABRIC));
	}

	private static byte[] rewrite(byte[] in, LoaderProbePolicy.Family family) {
		return new FmlContextLoaderRewriter(name -> family).transform("com.example.NeoPlugin", in, null);
	}

	private static int indexOfView(List<AbstractInsnNode> code) {
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && FmlContextLoaderRewriter.VIEW.equals(call.owner)
					&& "contextLoader".equals(call.name)) return i;
		}
		return -1;
	}

	/** The method's real instructions: no labels, line numbers or frames. */
	private static List<AbstractInsnNode> instructions(byte[] bytes, String methodName) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<AbstractInsnNode> code = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (!method.name.equals(methodName)) continue;
			for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		}
		return code;
	}

	/** {@code (TransformingClassLoader) Thread.currentThread().getContextClassLoader()}, as LibJF opens onLoad. */
	private static byte[] pluginShaped() {
		return method(mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
					"()Ljava/lang/ClassLoader;", false);
			mv.visitTypeInsn(Opcodes.CHECKCAST, FmlContextLoaderRewriter.TRANSFORMING_LOADER);
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
		});
	}

	/** The context loader, used as a plain ClassLoader; the class still names TransformingClassLoader elsewhere. */
	private static byte[] contextLoaderOnly() {
		return method(mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
					"()Ljava/lang/ClassLoader;", false);
			mv.visitInsn(Opcodes.POP);
			mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(FmlContextLoaderRewriter.TRANSFORMING_LOADER));
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
		});
	}

	/** A cast that a branch lands on: the loader on the stack there is not necessarily the context loader. */
	private static byte[] castAtAJumpTarget() {
		return method(mv -> {
			Label cast = new Label();
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
					"()Ljava/lang/ClassLoader;", false);
			mv.visitLabel(cast);
			mv.visitFrame(Opcodes.F_FULL, 2, new Object[] {OWNER, "java/lang/String"}, 1,
					new Object[] {"java/lang/ClassLoader"});
			mv.visitTypeInsn(Opcodes.CHECKCAST, FmlContextLoaderRewriter.TRANSFORMING_LOADER);
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
		});
	}

	private static byte[] method(java.util.function.Consumer<MethodVisitor> body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onLoad", "(Ljava/lang/String;)V", null, null);
		mv.visitCode();
		body.accept(mv);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** LibJF's mixin plugin out of the nested libjf-unsafe-v0 jar, or null when the sweep pack is not here. */
	private static byte[] libJfPlugin() throws Exception {
		Path outer = Path.of("build/compat-inputs/sweep90/mods/libjf-26.2.2+forge.jar");
		if (!Files.isRegularFile(outer)) return null;
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			ZipEntry nested = zip.getEntry("META-INF/jars/libjf-unsafe-v0-26.2.2+forge.jar");
			if (nested == null) return null;
			try (InputStream in = zip.getInputStream(nested); JarInputStream jar = new JarInputStream(in)) {
				for (ZipEntry e = jar.getNextEntry(); e != null; e = jar.getNextEntry()) {
					if (PLUGIN.equals(e.getName())) return jar.readAllBytes();
				}
			}
		}
		return null;
	}
}
