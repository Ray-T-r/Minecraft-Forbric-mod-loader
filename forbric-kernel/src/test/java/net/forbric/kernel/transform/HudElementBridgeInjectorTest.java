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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Verifies in real {@code neoforge-runtime.jar} bytecode that {@code initModdedLayers} hands the manager to MinecraftForge's
 * overlay stack before each of its returns, and that no {@code add} overload is routed any more.
 */
class HudElementBridgeInjectorTest {
	private static final Path NEOFORGE_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");

	private static final String TARGET = "net.neoforged.neoforge.client.gui.GuiLayerManager";
	private static final String ENTRY = "net/neoforged/neoforge/client/gui/GuiLayerManager.class";
	private static final String HOOK = "net/forbric/kernel/boot/KernelHudBridge";

	@Test
	void initModdedLayersHandsTheManagerOverBeforeEachReturn() throws Exception {
		ClassNode node = transformed();
		MethodNode init = node.methods.stream().filter(m -> m.name.equals("initModdedLayers") && m.desc.equals("()V")).findFirst()
				.orElseThrow(() -> new AssertionError("initModdedLayers()V drifted away"));
		int returns = 0, hooks = 0;
		for (AbstractInsnNode insn : init.instructions) {
			if (insn.getOpcode() == Opcodes.RETURN) {
				returns++;
				AbstractInsnNode before = insn.getPrevious();
				assertTrue(before instanceof MethodInsnNode call && HOOK.equals(call.owner) && "addForgeOverlayLayers".equals(call.name),
						"every return of initModdedLayers is preceded by the overlay hook");
			}
			if (insn instanceof MethodInsnNode call && HOOK.equals(call.owner)) hooks++;
		}
		assertTrue(returns > 0);
		assertEquals(returns, hooks);
	}

	@Test
	void noAddOverloadIsRouted() throws Exception {
		ClassNode node = transformed();
		for (MethodNode m : node.methods) {
			if (!"add".equals(m.name)) continue;
			assertTrue(!callsHook(m), "add" + m.desc + " must be left as NeoForge wrote it");
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
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		byte[] once = new HudElementBridgeInjector().transform(TARGET, readClass(), null);
		assertSame(once, new HudElementBridgeInjector().transform(TARGET, once, null),
				"a GuiLayerManager that already hands itself over must be passed straight through");
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
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		byte[] out = new HudElementBridgeInjector().transform(TARGET, readClass(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
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
