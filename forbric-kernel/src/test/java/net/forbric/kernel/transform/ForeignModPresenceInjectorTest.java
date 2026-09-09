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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.KernelForeignMods;
import net.forbric.kernel.metadata.DiscoveredMod;

/** Both families' {@code ModList.isLoaded} gains the cross-ecosystem answer, and nothing else moves. */
class ForeignModPresenceInjectorTest {
	private static final Path STAGE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run")
			.normalize();
	private static final Path NEOFORGE = STAGE.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE = STAGE.resolve("forge-runtime/forge-runtime.jar");
	private static final String PRESENCE = "net/forbric/kernel/boot/KernelForeignMods";
	private static final String NEO_MOD_LIST = "net.neoforged.fml.ModList";
	private static final String FORGE_MOD_LIST = "net.minecraftforge.fml.ModList";

	@AfterEach
	void clearRegistry() {
		KernelForeignMods.publishForgeFamily(List.of());
		KernelForeignMods.publishFabric(List.of());
	}

	@Test
	void aSyntheticIsLoadedNowAnswersForAFabricMod() throws Exception {
		KernelForeignMods.publishFabric(List.of(fabric("sodium")));

		byte[] out = transform(NEO_MOD_LIST, syntheticModList(NEO_MOD_LIST.replace('.', '/'), false));
		Class<?> loaded = define(NEO_MOD_LIST, out);
		Object instance = loaded.getDeclaredConstructor().newInstance();

		assertTrue((boolean) loaded.getMethod("isLoaded", String.class).invoke(instance, "sodium"),
				"a Fabric mod is present, so a NeoForge mod's isLoaded must say so");
		assertFalse((boolean) loaded.getMethod("isLoaded", String.class).invoke(instance, "not-installed"),
				"and a mod that really is absent still answers no");
	}

	@Test
	void theStaticOverloadReadsTheIdFromSlotZero() throws Exception {
		KernelForeignMods.publishFabric(List.of(fabric("iris")));

		byte[] out = transform(FORGE_MOD_LIST, syntheticModList(FORGE_MOD_LIST.replace('.', '/'), true));
		Class<?> loaded = define(FORGE_MOD_LIST, out);

		// A static isLoaded takes the id in slot 0; reading slot 1 would either verify-fail or read garbage.
		assertTrue((boolean) loaded.getMethod("isLoaded", String.class).invoke(null, "iris"));
		assertFalse((boolean) loaded.getMethod("isLoaded", String.class).invoke(null, "oculus"));
	}

	@Test
	void theRealNeoForgeModListIsRewrittenAndStillVerifies() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE), "staged NeoForge runtime absent — skipping real-bytecode check");
		assertRewritten(NEO_MOD_LIST, readClass(NEOFORGE, "net/neoforged/fml/ModList.class"));
	}

	@Test
	void theRealMinecraftForgeModListIsRewrittenAndStillVerifies() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE), "staged MinecraftForge runtime absent — skipping real-bytecode check");
		assertRewritten(FORGE_MOD_LIST, readClass(FORGE, "net/minecraftforge/fml/ModList.class"));
	}

	@Test
	void everyOtherClassIsHandedBackUntouched() {
		byte[] in = syntheticModList("net/example/ModList", false);
		assertSame(in, transform("net.example.ModList", in), "only the two ModLists are rewritten");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static void assertRewritten(String className, byte[] in) throws Exception {
		byte[] out = transform(className, in);
		assertTrue(out != in, className + " was not rewritten");
		ClassNode node = parse(out);
		MethodNode isLoaded = method(node, "isLoaded");

		int returns = 0;
		int calls = 0;
		for (AbstractInsnNode insn = isLoaded.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.IRETURN) returns++;
			if (insn instanceof MethodInsnNode call && PRESENCE.equals(call.owner) && "isLoaded".equals(call.name)) {
				calls++;
			}
		}
		assertEquals(returns, calls, "one cross-ecosystem lookup per return, and no stray call");
		assertTrue(returns >= 1);
		// The point of ORing instead of short-circuiting: no new branch, so the method still verifies without a
		// hand-written frame.
		new Analyzer<>(new BasicVerifier()).analyze(node.name, isLoaded);
	}

	private static byte[] transform(String className, byte[] in) {
		return new ForeignModPresenceInjector().transform(className, in,
				new TransformContext(EnvType.CLIENT, false, "intermediary"));
	}

	private static DiscoveredMod fabric(String id) {
		return new DiscoveredMod(Ecosystem.FABRIC, id, "1.0", id, List.of(), List.of(), null, id + ".jar");
	}

	/** {@code class X { boolean isLoaded(String id) { return false; } }}, static or instance. */
	private static byte[] syntheticModList(String internalName, boolean staticMethod) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		int access = Opcodes.ACC_PUBLIC | (staticMethod ? Opcodes.ACC_STATIC : 0);
		MethodVisitor mv = cw.visitMethod(access, "isLoaded", "(Ljava/lang/String;)Z", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Class<?> define(String binaryName, byte[] bytes) {
		return new ClassLoader(ForeignModPresenceInjectorTest.class.getClassLoader()) {
			Class<?> load() {
				return defineClass(binaryName, bytes, 0, bytes.length);
			}
		}.load();
	}

	private static byte[] readClass(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assumeTrue(e != null, entry + " absent from " + jar.getFileName());
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
		throw new AssertionError("no method " + name + " on " + node.name);
	}
}
