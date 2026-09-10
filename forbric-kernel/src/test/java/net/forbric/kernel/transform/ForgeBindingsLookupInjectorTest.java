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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;

/**
 * Pins {@link ForgeBindingsLookupInjector}, including the premise the whole rewrite rests on.
 *
 * <p>{@code Bindings}' class initializer resolves Forge's config events, message parser and event bus through
 * {@code ServiceLoader.load(FMLLoader.getGameLayer(), …)}. The kernel boots from a flat classpath and never fills
 * FML's module-layer manager, so that call throws and takes every user of MinecraftForge's config events with it.
 * The rewrite swaps the lookup for the classpath-keyed overload.
 *
 * <p>That is only correct because the carrier ALSO declares the provider the ordinary way, in
 * {@code META-INF/services}. If a future carrier stopped shipping that file the rewrite would still apply
 * cleanly, still verify, and still find nothing — the same silent death, now with a log line claiming success. So
 * the first test here asserts the service declaration, not the bytecode.
 */
class ForgeBindingsLookupInjectorTest {
	private static final Path FORGE_RUNTIME =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "forge-runtime", "forge-runtime.jar")
					.normalize();

	private static final String BINDINGS = "net.minecraftforge.fml.Bindings";
	private static final String SERVICE_FILE = "META-INF/services/net.minecraftforge.fml.IBindingsProvider";
	private static final String LOAD_LAYER_DESC =
			"(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;";
	private static final String LOAD_LOADER_DESC =
			"(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;";

	private final ForgeBindingsLookupInjector injector = new ForgeBindingsLookupInjector();

	/** The premise. Without this file the classpath lookup the rewrite installs resolves to nothing. */
	@Test
	void theCarrierStillDeclaresTheProviderOnTheClasspath() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged forge-runtime absent");

		byte[] decl = entry(SERVICE_FILE);
		assertNotNull(decl, SERVICE_FILE + " is gone from the carrier — the classpath-keyed ServiceLoader lookup "
				+ "this injector installs would find no provider, and Forge's config events would die exactly as "
				+ "they did before, but silently and with a log line saying it worked");

		String provider = new String(decl, StandardCharsets.UTF_8).trim();
		assertTrue(provider.startsWith("net.minecraftforge."), "unexpected provider: " + provider);
		assertNotNull(entry(provider.replace('.', '/') + ".class"), "the declared provider " + provider
				+ " is not in the carrier");
	}

	@Test
	void theRealBindingsClassIsRewrittenAndStillVerifies() throws Exception {
		byte[] in = entry(BINDINGS.replace('.', '/') + ".class");
		assumeTrue(in != null, "staged forge-runtime absent or Bindings moved");

		byte[] out = injector.transform(BINDINGS, in, ctx());
		assertTrue(out != in, "Bindings was not rewritten — it still asks for a module layer the kernel never builds");

		ClassNode node = parse(out);
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && "java/util/ServiceLoader".equals(call.owner)
						&& "load".equals(call.name)) {
					assertEquals(LOAD_LOADER_DESC, call.desc,
							"a module-layer lookup survived in " + m.name);
				}
			}
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	/** The rewrite is three instructions for three; a stack-depth change would fail the verifier above. */
	@Test
	void aSyntheticLayerLookupBecomesAClasspathLookup() throws Exception {
		ClassNode out = parse(injector.transform(BINDINGS, layerLookup(), ctx()));
		MethodInsnNode load = serviceLoaderCall(out);

		assertNotNull(load, "no ServiceLoader.load survived");
		assertEquals(LOAD_LOADER_DESC, load.desc);
		new Analyzer<>(new BasicVerifier()).analyze(out.name, method(out, "clinitLike"));
	}

	/** Only the FMLLoader.getGameLayer() shape is replaced — a genuine module layer elsewhere is left alone. */
	@Test
	void aLookupOnSomeOtherModuleLayerIsLeftAlone() {
		byte[] in = foreignLayerLookup();
		assertSame(in, injector.transform(BINDINGS, in, ctx()),
				"only Forge's own getGameLayer() pattern is the kernel's to replace");
	}

	@Test
	void everyOtherClassIsHandedBackUntouched() {
		byte[] in = layerLookup();
		assertSame(in, injector.transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] entry(String path) throws Exception {
		if (!Files.isRegularFile(FORGE_RUNTIME)) return null;
		try (ZipFile zip = new ZipFile(FORGE_RUNTIME.toFile())) {
			ZipEntry e = zip.getEntry(path);
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
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

	private static MethodInsnNode serviceLoaderCall(ClassNode node) {
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && "java/util/ServiceLoader".equals(call.owner)) return call;
			}
		}
		return null;
	}

	/** {@code ServiceLoader.load(FMLLoader.getGameLayer(), Object.class)} — the shape the injector looks for. */
	private static byte[] layerLookup() {
		return synth(mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/loading/FMLLoader", "getGameLayer",
					"()Ljava/lang/ModuleLayer;", false);
			mv.visitLdcInsn(Type.getObjectType("java/lang/Object"));
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/ServiceLoader", "load", LOAD_LAYER_DESC, false);
			mv.visitInsn(Opcodes.POP);
		});
	}

	/** The same call, but the layer came from somewhere that is not Forge's loader. */
	private static byte[] foreignLayerLookup() {
		return synth(mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Layers", "someLayer",
					"()Ljava/lang/ModuleLayer;", false);
			mv.visitLdcInsn(Type.getObjectType("java/lang/Object"));
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/ServiceLoader", "load", LOAD_LAYER_DESC, false);
			mv.visitInsn(Opcodes.POP);
		});
	}

	private interface Body {
		void emit(MethodVisitor mv);
	}

	private static byte[] synth(Body body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BINDINGS.replace('.', '/'), null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "clinitLike", "()V", null, null);
		mv.visitCode();
		body.emit(mv);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
