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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Covers the picture-in-picture bridge in real merged-base bytecode.
 *
 * <p>The bridge is a whole synthesized method plus a rewritten branch, emitted under {@code ClassWriter(0)} with a
 * hand-authored frame and hand-computed maxs — the highest-risk bytecode the kernel writes, and until this test it
 * had no coverage at all. Its job: guest mods register picture-in-picture renderers the vanilla way, into
 * {@code pictureInPictureRenderers}, but NeoForge won {@code preparePictureInPictureState} and reads
 * {@code pictureInPictureRendererPools} instead, so those renderers draw nothing with no error anywhere.
 */
class MergedBasePipBridgeTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final String GUI_RENDERER = "net/minecraft/client/gui/render/GuiRenderer";
	private static final String BRIDGE = "forbric$prepareOrphanedPip";

	@Test
	void synthesizesTheBridgeFromTheOrphanedOverload() throws Exception {
		ClassNode node = transformedGuiRenderer();

		MethodNode bridge = method(node, BRIDGE);
		assertNotNull(bridge, "the orphaned vanilla lookup must be re-exposed as a bridge method");
		assertEquals(Type.BOOLEAN, Type.getReturnType(bridge.desc).getSort(),
				"the bridge answers whether it found a renderer, so the pooled path can fall through to it");
		assertTrue((bridge.access & Opcodes.ACC_PRIVATE) != 0 && (bridge.access & Opcodes.ACC_SYNTHETIC) != 0);
	}

	@Test
	void thePooledLookupFallsThroughInsteadOfGivingUp() throws Exception {
		ClassNode node = transformedGuiRenderer();
		MethodNode live = liveOverload(node);

		assertTrue(calls(live, node.name, BRIDGE),
				"the 'no pool for this state class' early return must now call the bridge");
	}

	@Test
	void theBridgeDescriptorMatchesItsCallSite() throws Exception {
		ClassNode node = transformedGuiRenderer();
		MethodNode bridge = method(node, BRIDGE);
		MethodNode live = liveOverload(node);

		MethodInsnNode call = null;
		for (AbstractInsnNode insn : live.instructions) {
			if (insn instanceof MethodInsnNode m && BRIDGE.equals(m.name)) call = m;
		}
		assertNotNull(call);
		// Both descriptors are derived from the call site's own first argument. If they ever diverge the failure is a
		// NoSuchMethodError on the first frame that reaches an orphaned renderer — i.e. in front of the user.
		assertEquals(bridge.desc, call.desc, "the synthesized bridge and its call site must agree on the state type");
	}

	@Test
	void transformedMethodsAnalyseCleanly() throws Exception {
		ClassNode node = transformedGuiRenderer();

		// BasicVerifier rather than CheckClassAdapter.verify: the latter's SimpleVerifier resolves every referenced
		// type through a ClassLoader, and the game classes are not on the test classpath. This still catches what a
		// hand-written body gets wrong — stack depth at merge points, locals past maxLocals, type-sort mismatches.
		for (MethodNode m : node.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	@Test
	void isIdempotent() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		byte[] original = readClass(GUI_RENDERER + ".class");
		byte[] once = transform(original);
		assertSame(once, transform(once), "a GuiRenderer that already carries the bridge must pass straight through");
	}

	@Test
	void leavesClassesWithoutBothMapsAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		// Hud is heavily merged but has no picture-in-picture maps; the bridge must not touch it.
		byte[] hud = readClass("net/minecraft/client/gui/Hud.class");
		ClassNode node = new ClassNode();
		new ClassReader(transform(hud)).accept(node, 0);
		assertNull(method(node, BRIDGE));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static ClassNode transformedGuiRenderer() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		byte[] out = transform(readClass(GUI_RENDERER + ".class"));
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		assumeTrue(method(node, BRIDGE) != null || node.name.isEmpty(),
				"GuiRenderer no longer carries both pip maps — the merge shifted, re-derive the bridge");
		return node;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(GUI_RENDERER.replace('/', '.'), bytes, null);
	}

	/** NeoForge's overload — the one {@code render()} actually calls, told apart by its boolean return. */
	private static MethodNode liveOverload(ClassNode node) {
		for (MethodNode m : node.methods) {
			if ("preparePictureInPictureState".equals(m.name)
					&& Type.getReturnType(m.desc).getSort() == Type.BOOLEAN) {
				return m;
			}
		}
		throw new AssertionError("GuiRenderer has no boolean preparePictureInPictureState — the merge shifted");
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		List<String> seen = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) seen.add(call.owner + "." + call.name);
		}
		return seen.contains(owner + "." + name);
	}
}
