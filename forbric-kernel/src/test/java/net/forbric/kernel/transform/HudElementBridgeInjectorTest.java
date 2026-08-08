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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Verifies in real {@code neoforge-runtime.jar} bytecode that every {@code GuiLayer} registered with NeoForge's
 * layer manager passes through the kernel, and that the overloads which merely delegate are left alone.
 */
class HudElementBridgeInjectorTest {
	private static final Path NEOFORGE_RUNTIME =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "neoforge-runtime",
					"neoforge-runtime.jar").normalize();

	private static final String TARGET = "net.neoforged.neoforge.client.gui.GuiLayerManager";
	private static final String ENTRY = "net/neoforged/neoforge/client/gui/GuiLayerManager.class";
	private static final String LAYER = "Lnet/neoforged/neoforge/client/gui/GuiLayer;";
	private static final String HOOK = "net/forbric/kernel/boot/KernelHudBridge";

	@Test
	void routesBothGuiLayerOverloads() throws Exception {
		ClassNode node = transformed();

		int routed = 0;
		for (MethodNode m : node.methods) {
			if (!"add".equals(m.name)) continue;
			Type[] args = Type.getArgumentTypes(m.desc);
			boolean takesLayer = args.length >= 2 && LAYER.equals(args[1].getDescriptor());
			if (!takesLayer) continue;

			assertTrue(callsHook(m), "add" + m.desc + " must route its layer through KernelHudBridge.wrap");
			assertPrologue(m);
			routed++;
		}
		assertEquals(2, routed, "expected the (Identifier, GuiLayer) and (Identifier, GuiLayer, BooleanSupplier) "
				+ "overloads — the NeoForge API drifted");
	}

	@Test
	void leavesTheDelegatingOverloadsAlone() throws Exception {
		ClassNode node = transformed();

		for (MethodNode m : node.methods) {
			if (!"add".equals(m.name)) continue;
			Type[] args = Type.getArgumentTypes(m.desc);
			if (args.length >= 2 && LAYER.equals(args[1].getDescriptor())) continue;

			// The Consumer overload and add(GuiLayerManager, BooleanSupplier) both funnel into the 3-arg one, so
			// touching them here would wrap twice.
			assertTrue(!callsHook(m), "add" + m.desc + " delegates and must not be routed a second time");
		}
	}

	@Test
	void transformedMethodsAnalyseCleanly() throws Exception {
		ClassNode node = transformed();
		for (MethodNode m : node.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	@Test
	void isIdempotent() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		byte[] once = new HudElementBridgeInjector().transform(TARGET, readClass(), null);
		assertSame(once, new HudElementBridgeInjector().transform(TARGET, once, null),
				"an already-routed GuiLayerManager must be passed straight through");
	}

	@Test
	void unrelatedClassesArePassedThrough() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Unrelated", null, "java/lang/Object", null);
		writer.visitEnd();
		byte[] bytes = writer.toByteArray();
		assertSame(bytes, new HudElementBridgeInjector().transform("com.example.Unrelated", bytes, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static ClassNode transformed() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		byte[] out = new HudElementBridgeInjector().transform(TARGET, readClass(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	/** ALOAD 1; ALOAD 2; INVOKESTATIC wrap; CHECKCAST GuiLayer; ASTORE 2 — and it must come FIRST. */
	private static void assertPrologue(MethodNode m) {
		AbstractInsnNode insn = m.instructions.getFirst();
		while (insn != null && insn.getOpcode() == -1) insn = insn.getNext();

		assertEquals(Opcodes.ALOAD, insn.getOpcode());
		insn = next(insn);
		assertEquals(Opcodes.ALOAD, insn.getOpcode());
		insn = next(insn);
		assertEquals(Opcodes.INVOKESTATIC, insn.getOpcode());
		assertEquals(HOOK, ((MethodInsnNode) insn).owner);
		insn = next(insn);
		assertEquals(Opcodes.CHECKCAST, insn.getOpcode());
		insn = next(insn);
		assertEquals(Opcodes.ASTORE, insn.getOpcode());
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() == -1) n = n.getNext();
		assertNotNull(n);
		return n;
	}

	private static boolean callsHook(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK.equals(call.owner) && "wrap".equals(call.name)) return true;
		}
		return false;
	}

	private static byte[] readClass() throws Exception {
		try (ZipFile zip = new ZipFile(NEOFORGE_RUNTIME.toFile())) {
			ZipEntry found = zip.getEntry(ENTRY);
			assertNotNull(found, ENTRY + " missing from the staged neoforge-runtime.jar");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
