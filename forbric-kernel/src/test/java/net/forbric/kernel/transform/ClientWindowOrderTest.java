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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.fabricmc.api.EnvType;

/**
 * The two client windows must open in this order inside {@code Minecraft.<init>}:
 *
 * <pre>singleton assigned -> Fabric client entrypoints -> Options built -> NeoForge client setup</pre>
 *
 * <p>Neither injector can express that on its own, which is why this test is about the pair rather than about
 * either one. The order is not a preference: Fabric's entrypoints must run while {@code options} is still NULL,
 * because keymapping registration rejects a built {@code Options} with "GameOptions has already been initialised";
 * NeoForge's {@code FMLClientSetupEvent} must run once {@code options} EXISTS, because JourneyMap's setup validates
 * its config against {@code Minecraft.getInstance().options.renderDistance()} and NPE'd on the null, leaving every
 * JourneyMap property null and killing the render loop the moment the world drew.
 *
 * <p>Both anchors are structural — the first {@code new Options} and the call to {@code ClientModLoader.finish} —
 * so a merged base that moves either one silently reopens one of those two failures. Neither injector had a test.
 */
class ClientWindowOrderTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();

	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String OPTIONS = "net/minecraft/client/Options";
	private static final String NEO_CLIENT_MOD_LOADER = "net/neoforged/neoforge/client/loading/ClientModLoader";
	private static final String FABRIC_HOOK = "onClientEntrypoints";
	private static final String NEO_HOOK = "onNeoClientSetup";

	/** On the REAL merged base, with both injectors applied, the three landmarks come out in the right order. */
	@Test
	void onTheRealMinecraftTheWindowsOpenInTheDocumentedOrder() throws Exception {
		byte[] real = mergedBaseMinecraft();
		assumeTrue(real != null, "staged merged base absent — skipping real-bytecode check");

		assertOrder(bothInjectors(real, true));
	}

	/**
	 * And the resulting order is decided by the ANCHORS, not by which injector ran first. Both are registered in
	 * one chain; if the outcome depended on registration order, a reordering there would move a window silently.
	 */
	@Test
	void theOrderDoesNotDependOnWhichInjectorRunsFirst() throws Exception {
		byte[] real = mergedBaseMinecraft();
		assumeTrue(real != null, "staged merged base absent");

		assertOrder(bothInjectors(real, true));
		assertOrder(bothInjectors(real, false));
	}

	@Test
	void onASyntheticConstructorTheWindowsAlsoLandCorrectly() {
		assertOrder(bothInjectors(syntheticMinecraft(), true));
	}

	/** The Fabric hook goes before the FIRST new Options — a later one would be past the window. */
	@Test
	void theFabricHookPrecedesTheFirstOptionsAllocation() {
		List<String> marks = landmarks(new ClientEntrypointHookInjector()
				.transform(MINECRAFT, syntheticMinecraft(), ctx()));

		assertEquals(FABRIC_HOOK, marks.get(0));
		assertEquals("NEW " + OPTIONS, marks.get(1), "the hook must precede the first Options allocation");
	}

	/** With no ClientModLoader.finish present the Neo injector must not silently no-op: it warns and returns as-is. */
	@Test
	void aConstructorWithoutTheNeoAnchorIsReturnedUnchanged() {
		byte[] in = minecraftWithoutNeoAnchor();
		assertSame(in, new NeoClientSetupHookInjector().transform(MINECRAFT, in, ctx()),
				"nothing to anchor to — the injector returns the input and warns");
	}

	@Test
	void neitherInjectorTouchesAnyOtherClass() {
		byte[] in = syntheticMinecraft();
		assertSame(in, new ClientEntrypointHookInjector().transform("net.example.Other", in, ctx()));
		assertSame(in, new NeoClientSetupHookInjector().transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static void assertOrder(byte[] out) {
		List<String> marks = landmarks(out);
		int fabric = marks.indexOf(FABRIC_HOOK);
		int options = marks.indexOf("NEW " + OPTIONS);
		int neo = marks.indexOf(NEO_HOOK);

		assertTrue(fabric >= 0, "the Fabric client-entrypoint hook was never inserted");
		assertTrue(options >= 0, "the Options allocation anchor is gone from Minecraft.<init>");
		assertTrue(neo >= 0, "the NeoForge client-setup hook was never inserted");

		assertTrue(fabric < options, "Fabric entrypoints must fire while options is still null "
				+ "(keymapping registration rejects a built Options) — got " + marks);
		assertTrue(options < neo, "NeoForge client setup must fire after options exists "
				+ "(JourneyMap validates against options.renderDistance()) — got " + marks);
	}

	/** The landmark instructions of {@code <init>}, in bytecode order. */
	private static List<String> landmarks(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		List<String> marks = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
						&& OPTIONS.equals(type.desc) && !marks.contains("NEW " + OPTIONS)) {
					marks.add("NEW " + OPTIONS);
				} else if (insn instanceof MethodInsnNode call
						&& (FABRIC_HOOK.equals(call.name) || NEO_HOOK.equals(call.name))
						&& !marks.contains(call.name)) {
					marks.add(call.name);
				}
			}
		}
		return marks;
	}

	private static byte[] bothInjectors(byte[] in, boolean fabricFirst) {
		ClassTransformer a = fabricFirst ? new ClientEntrypointHookInjector() : new NeoClientSetupHookInjector();
		ClassTransformer b = fabricFirst ? new NeoClientSetupHookInjector() : new ClientEntrypointHookInjector();
		return b.transform(MINECRAFT, a.transform(MINECRAFT, in, ctx()), ctx());
	}

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] mergedBaseMinecraft() throws Exception {
		if (!Files.isRegularFile(MERGED_BASE)) return null;
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = zip.getEntry("net/minecraft/client/Minecraft.class");
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	/** {@code <init>} in the real shape: assign the singleton, build Options, then ClientModLoader.finish(). */
	private static byte[] syntheticMinecraft() {
		return minecraft(true);
	}

	private static byte[] minecraftWithoutNeoAnchor() {
		return minecraft(false);
	}

	private static byte[] minecraft(boolean withNeoAnchor) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		// options = new Options()
		mv.visitTypeInsn(Opcodes.NEW, OPTIONS);
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OPTIONS, "<init>", "()V", false);
		mv.visitInsn(Opcodes.POP);
		if (withNeoAnchor) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, NEO_CLIENT_MOD_LOADER, "finish", "()V", false);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}
