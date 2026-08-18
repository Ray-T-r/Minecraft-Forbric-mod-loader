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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.KernelClientSmoke;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives {@link KernelClientSmoke} a tick to run on, so an unattended client run can end by itself.
 *
 * <p>Registered unconditionally but inert unless {@code -Dforbric.clientSmoke=true}: a gate harness must not be
 * able to change what a normal launch executes, and the cheapest guarantee of that is emitting no bytecode at all.
 *
 * <p>A COREMOD transform rather than a kernel-owned mixin. The kernel already transforms {@code Minecraft} for the
 * client-setup window, so this needs no new machinery — no mixin config to register, no ordering against guest
 * mixins to reason about, and nothing that shows up in the mixin config list a gate asserts on.
 *
 * <p>Injected at the HEAD of {@code tick()V}, which is exactly once per tick with no frame to author: the method
 * has several returns and hooking each one would call the controller more than once on some paths. The
 * controller reads a tick behind as a result, which for counting ticks spent in a world is not a distinction.
 */
public final class ClientSmokeTickInjector implements ClassTransformer {
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String TICK = "tick";
	private static final String VOID = "()V";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelClientSmoke";
	private static final String HOOK_NAME = "onClientTick";
	private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

	/** The chain runs again to produce Mixin's pre-weave bytes, so the class is transformed more than once. */
	private boolean announced;

	@Override
	public String name() {
		return "forbric-client-smoke-tick";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!MINECRAFT.equals(className) || !KernelClientSmoke.enabled()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode tick = null;
		for (MethodNode m : node.methods) {
			if (TICK.equals(m.name) && VOID.equals(m.desc)) {
				tick = m;
				break;
			}
		}
		if (tick == null) {
			ForbricLog.warn("[Forbric/ClientSmoke] no Minecraft.tick()V to hook — an unattended run cannot end "
					+ "itself and the gate driving it will time out instead");
			return classBytes;
		}

		InsnList prologue = new InsnList();
		prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		tick.instructions.insert(prologue);
		tick.maxStack = Math.max(tick.maxStack, 1);

		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/ClientSmoke] armed on Minecraft.tick — this run will enter a world, live in "
					+ "it, disconnect and stop by itself");
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
