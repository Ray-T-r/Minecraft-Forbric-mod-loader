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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Verifies the kernel's central registry claim in real bytecode: {@link RegistryHookRedirector} rewrites the
 * genuine {@code GameData.getWrapper} from the staged forge-runtime jar into an identity return, so builtin
 * registries stay plain {@code MappedRegistry}s (single freeze — the "Tags not bound" wall cannot recur).
 *
 * <p>When the staged forge-runtime jar is absent the real-bytecode test self-skips (the redirector logic is
 * still covered by the fail-loud and pass-through cases below).
 */
class RegistryHookRedirectorTest {
	// The forge-runtime jar carries net.minecraftforge.registries.GameData (a passive ABI carrier for the kernel).
	private static final Path FORGE_RUNTIME =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "forge-runtime", "forge-runtime.jar")
					.normalize();

	private final RegistryHookRedirector redirector = new RegistryHookRedirector();

	@Test
	void rewritesRealGameDataGetWrapperToIdentity() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME),
				"staged forge-runtime.jar absent — skipping real-bytecode redirect check");

		byte[] original = readClass(FORGE_RUNTIME, "net/minecraftforge/registries/GameData.class");
		assumeTrue(original != null, "GameData not found in forge-runtime.jar");

		byte[] transformed = redirector.transform(RegistryHookRedirector.GAMEDATA, original, null);
		assertTrue(transformed != original && transformed.length != original.length || transformed != original,
				"transform should have edited GameData");

		MethodNode getWrapper = findGetWrapper(transformed);
		assertEquals("(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/WritableRegistry;)"
						+ "Lnet/minecraft/core/WritableRegistry;", getWrapper.desc,
				"redirected the expected 2-arg getWrapper");

		// Body must be exactly: ALOAD 1 ; ARETURN — identity return of the plain WritableRegistry argument.
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : getWrapper.instructions.toArray()) {
			if (insn.getOpcode() >= 0) real.add(insn); // skip labels/line numbers/frames
		}
		assertEquals(2, real.size(), "identity body is two real instructions");
		assertTrue(real.get(0) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 1,
				"first insn loads arg1 (the WritableRegistry)");
		assertTrue(real.get(1) instanceof InsnNode i && i.getOpcode() == Opcodes.ARETURN,
				"second insn returns it");

		// And crucially: the redirected method no longer references NamespacedWrapper / RegistryManager.
		String body = disasmRefs(getWrapper);
		assertTrue(!body.contains("NamespacedWrapper") && !body.contains("RegistryManager"),
				"redirected getWrapper must not touch NamespacedWrapper/RegistryManager");
	}

	@Test
	void failsLoudWhenGetWrapperSeamMissing() {
		// A synthetic class named GameData but WITHOUT getWrapper — the redirect seam drifted → must fail loud (R7).
		byte[] fake = synthClass("net/minecraftforge/registries/GameData", "somethingElse", "()V");
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> redirector.transform(RegistryHookRedirector.GAMEDATA, fake, null));
		assertTrue(ex.getMessage().contains("getWrapper"), "message names the missing seam");
	}

	@Test
	void passesThroughUnrelatedClasses() {
		byte[] other = synthClass("net/minecraft/world/level/block/Block", "foo", "()V");
		byte[] out = redirector.transform("net.minecraft.world.level.block.Block", other, null);
		assertEquals(other, out, "non-GameData classes are returned unchanged (same reference)");
	}

	// --- helpers ---

	private static MethodNode findGetWrapper(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (m.name.equals("getWrapper")
					&& m.desc.equals("(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/WritableRegistry;)"
					+ "Lnet/minecraft/core/WritableRegistry;")) {
				return m;
			}
		}
		throw new AssertionError("getWrapper(ResourceKey,WritableRegistry) not present after transform");
	}

	private static String disasmRefs(MethodNode m) {
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof org.objectweb.asm.tree.MethodInsnNode mi) sb.append(mi.owner).append('.').append(mi.name);
			if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fi) sb.append(fi.owner).append('.').append(fi.name);
			if (insn instanceof org.objectweb.asm.tree.TypeInsnNode ti) sb.append(ti.desc);
		}
		return sb.toString();
	}

	private static byte[] readClass(Path jar, String entryName) throws Exception {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			ZipEntry e = zf.getEntry(entryName);
			if (e == null) return null;
			try (InputStream in = zf.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	private static byte[] synthClass(String internalName, String method, String desc) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method, desc, null, null);
		mn.instructions.add(new InsnNode(Opcodes.RETURN));
		mn.maxStack = 0;
		mn.maxLocals = 0;
		mn.accept(cw);
		return cw.toByteArray();
	}
}
