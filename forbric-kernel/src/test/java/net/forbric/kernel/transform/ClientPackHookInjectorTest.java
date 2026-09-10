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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Pins {@link ClientPackHookInjector} for BOTH Forge families.
 *
 * <p>It replaces the whole body of {@code ClientModLoader.setupModResourcePacks} with a call into the kernel, so
 * the kernel owns which resource packs a client mounts. It has no warn path: if the descriptor or the owner name
 * ever stops matching, nothing is rewritten, nothing is logged, and the client simply comes up without any mod's
 * assets — the failure looks like missing textures, not like a loader error.
 *
 * <p>The load-bearing detail is that the replacement body is {@code ALOAD 0; INVOKESTATIC hook; RETURN}. Slot 0
 * holds the {@code PackRepository} only because the method is STATIC. If a carrier ever made it an instance
 * method, slot 0 would be {@code this} and the kernel would be handed a {@code ClientModLoader} where it expects a
 * repository — so that modifier is asserted against the real carriers here, not assumed.
 */
class ClientPackHookInjectorTest {
	private static final Path RUN = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
	private static final String METHOD = "setupModResourcePacks";
	private static final String DESC = "(Lnet/minecraft/server/packs/repository/PackRepository;)V";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";

	private final ClientPackHookInjector injector = new ClientPackHookInjector();

	/**
	 * The premise of {@code ALOAD 0}, and a correction to what the class javadoc claims.
	 *
	 * <p>It says the injector redirects "each ecosystem's" {@code setupModResourcePacks}. On the staged carriers
	 * that is only true of NeoForge: MinecraftForge's {@code ClientModLoader} has no such method at all — it takes
	 * the repository in {@code begin(Minecraft, PackRepository, ReloadableResourceManager)} instead — so the
	 * MinecraftForge entry in {@code OWNERS} matches nothing and is a hedge, not a live path. Asserting that
	 * explicitly means a carrier that later ADDS the method makes this test fail, which is the moment to check
	 * whether the hedge has become a second live rewrite.
	 */
	@Test
	void neoForgeDeclaresTheMethodStaticallyAndMinecraftForgeDoesNotDeclareItAtAll() throws Exception {
		byte[] neo = carrier(Ecosystem.NEOFORGE);
		assumeTrue(neo != null, "staged NeoForge carrier absent");

		MethodNode m = method(parse(neo), METHOD, DESC);
		assertNotNull(m, "NeoForge: " + METHOD + DESC + " is gone — the client pack hook silently stops applying "
				+ "and no mod's assets are mounted");
		assertTrue((m.access & Opcodes.ACC_STATIC) != 0, "NeoForge: " + METHOD + " is no longer static, so the "
				+ "injector's ALOAD 0 would hand the kernel `this` instead of the PackRepository");

		byte[] forge = carrier(Ecosystem.FORGE);
		assumeTrue(forge != null, "staged MinecraftForge carrier absent");
		assertNull(method(parse(forge), METHOD, DESC),
				"MinecraftForge's ClientModLoader now declares " + METHOD + " — the OWNERS entry for it has stopped "
						+ "being a hedge and become a live rewrite; re-check that the kernel should own both");
	}

	@Test
	void theNeoForgeCarrierIsRewrittenToCallTheKernel() throws Exception {
		byte[] real = carrier(Ecosystem.NEOFORGE);
		assumeTrue(real != null, "staged NeoForge carrier absent");

		String className = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE);
		byte[] out = injector.transform(className, real, ctx());
		assertTrue(out != real, "not rewritten");

		ClassNode node = parse(out);
		MethodNode m = method(node, METHOD, DESC);
		assertEquals(3, m.instructions.size(), "the body should be exactly ALOAD/INVOKESTATIC/RETURN");
		assertNotNull(hookCall(m), "the kernel hook call is missing");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
	}

	/** The hedge really is inert today: handed the real MinecraftForge class, the injector changes nothing. */
	@Test
	void theMinecraftForgeCarrierIsUnchangedBecauseItHasNoSuchMethod() throws Exception {
		byte[] real = carrier(Ecosystem.FORGE);
		assumeTrue(real != null, "staged MinecraftForge carrier absent");

		assertSame(real, injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.FORGE), real, ctx()));
	}

	/** A same-named method with a different descriptor is not the one, and must be left running. */
	@Test
	void aDifferentDescriptorIsNotRewritten() {
		byte[] in = synthetic("(Ljava/lang/Object;)V", true);
		assertSame(in, injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE), in, ctx()));
	}

	@Test
	void anyOtherClassIsHandedBackUntouched() {
		byte[] in = synthetic(DESC, true);
		assertSame(in, injector.transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] carrier(Ecosystem eco) throws Exception {
		Path jar = eco == Ecosystem.NEOFORGE
				? RUN.resolve("neoforge-runtime/neoforge-runtime.jar")
				: RUN.resolve("forge-runtime/forge-runtime.jar");
		if (!Files.isRegularFile(jar)) return null;

		String entry = ForeignType.CLIENT_MOD_LOADER.internal(eco) + ".class";
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
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

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static MethodInsnNode hookCall(MethodNode m) {
		for (var insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)) return call;
		}
		return null;
	}

	private static byte[] synthetic(String desc, boolean isStatic) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC,
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(
				Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), METHOD, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}
