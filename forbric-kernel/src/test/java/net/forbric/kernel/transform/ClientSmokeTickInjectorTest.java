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
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.KernelClientSmoke;

/**
 * Pins the property that matters about the smoke harness: with its flag off it must emit NO bytecode at all.
 *
 * <p>This transformer exists only so an unattended gate run can end by itself, and it is registered
 * unconditionally in the chain. Its own javadoc states the rule — "a gate harness must not be able to change what
 * a normal launch executes, and the cheapest guarantee of that is emitting no bytecode at all". That is the same
 * shape as {@link ForbricMixinDowngrade}'s scope: a deliberate hole that is only safe while it cannot leak. If it
 * ever armed itself by default, every ordinary player's {@code Minecraft.tick} would gain a call into the gate
 * harness's controller, and the tests that were supposed to observe the game would be changing it.
 *
 * <p>So the first test asserts the input is handed back by IDENTITY, not merely unchanged in content.
 */
class ClientSmokeTickInjectorTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();

	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelClientSmoke";

	private String before;

	@org.junit.jupiter.api.BeforeEach
	void rememberProperty() {
		before = System.getProperty(KernelClientSmoke.ENABLED);
	}

	@AfterEach
	void restoreProperty() {
		if (before == null) System.clearProperty(KernelClientSmoke.ENABLED);
		else System.setProperty(KernelClientSmoke.ENABLED, before);
	}

	/** THE property: unarmed, it must not touch a single byte of the class a normal launch runs. */
	@Test
	void withTheFlagOffTheClassIsHandedBackByIdentity() {
		System.clearProperty(KernelClientSmoke.ENABLED);

		byte[] in = minecraftWithTick();
		assertSame(in, new ClientSmokeTickInjector().transform(MINECRAFT, in, ctx()),
				"the smoke harness is registered unconditionally, so with its flag off it must emit nothing — "
						+ "otherwise every ordinary launch gains a call into the gate controller");
	}

	@Test
	void withTheFlagOnTheHookIsInsertedOncePerTickAtTheHead() {
		System.setProperty(KernelClientSmoke.ENABLED, "true");

		byte[] out = new ClientSmokeTickInjector().transform(MINECRAFT, minecraftWithTick(), ctx());
		MethodNode tick = method(parse(out), "tick", "()V");

		assertEquals(1, hookCalls(tick), "the hook goes at the HEAD once — hooking each return would call the "
				+ "controller more than once on the paths that have several");

		AbstractInsnNode first = tick.instructions.getFirst();
		assertTrue(first instanceof VarInsnNode load && load.var == 0 && load.getOpcode() == Opcodes.ALOAD,
				"tick() is an INSTANCE method, so slot 0 is the Minecraft the controller needs");
		assertTrue(tick.instructions.get(1) instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner));
	}

	/** On the real merged base too, since that is the class the gates actually transform. */
	@Test
	void theRealMinecraftGainsExactlyOneHook() throws Exception {
		byte[] real = mergedBaseMinecraft();
		assumeTrue(real != null, "staged merged base absent — skipping real-bytecode check");
		System.setProperty(KernelClientSmoke.ENABLED, "true");

		byte[] out = new ClientSmokeTickInjector().transform(MINECRAFT, real, ctx());
		assertTrue(out != real, "the real Minecraft.tick was not hooked");
		assertEquals(1, hookCalls(method(parse(out), "tick", "()V")));
	}

	/** No tick to hook: warn and hand it back, rather than arming something that will never fire. */
	@Test
	void aMinecraftWithoutTickIsReturnedUnchanged() {
		System.setProperty(KernelClientSmoke.ENABLED, "true");

		byte[] in = minecraftWithoutTick();
		assertSame(in, new ClientSmokeTickInjector().transform(MINECRAFT, in, ctx()));
	}

	@Test
	void anyOtherClassIsUntouchedEvenWhenArmed() {
		System.setProperty(KernelClientSmoke.ENABLED, "true");

		byte[] in = minecraftWithTick();
		assertSame(in, new ClientSmokeTickInjector().transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static int hookCalls(MethodNode m) {
		int n = 0;
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)) n++;
		}
		return n;
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
		throw new AssertionError("no method " + name + desc);
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

	/** {@code void tick()} with SEVERAL returns — the shape the head-injection rationale is about. */
	private static byte[] minecraftWithTick() {
		return minecraft(true);
	}

	private static byte[] minecraftWithoutTick() {
		return minecraft(false);
	}

	private static byte[] minecraft(boolean withTick) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);

		if (withTick) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
			mv.visitCode();
			org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitJumpInsn(Opcodes.IFNULL, skip);
			mv.visitInsn(Opcodes.RETURN);        // one early return
			mv.visitLabel(skip);
			mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			mv.visitInsn(Opcodes.RETURN);        // and the normal one
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}
}
