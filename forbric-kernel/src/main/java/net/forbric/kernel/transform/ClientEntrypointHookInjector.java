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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Fires the Fabric {@code client} entrypoints from inside {@code Minecraft.<init>}, at the same window Fabric itself
 * uses — after the {@code Minecraft} singleton is set but before {@code Options} is created.
 *
 * <p>The kernel used to run client entrypoints in its pre-{@code Minecraft} registration window (with the registries
 * unfrozen). But {@code Minecraft.getInstance()} is still null there, so a client entrypoint that touches the
 * instance NPEs — e.g. Fabric keymapping registration reads {@code Minecraft.getInstance().options}, and Jade's
 * {@code JadeClient.init} crashed on it, leaving its keybinds null and taking down the client on world-load. Fabric
 * fires client entrypoints from {@code Minecraft.<init>} precisely because {@code getInstance()} must be live
 * (instance already assigned) while {@code options} must still be null (keymapping registration rejects a built
 * {@code Options} with "GameOptions has already been initialised").
 *
 * <p>The injection point is the first {@code new net/minecraft/client/Options} in the constructor: the singleton was
 * assigned earlier in the same {@code <init>}, and {@code Options} has not been constructed yet. A no-arg void
 * {@code INVOKESTATIC} inserted there is stack-neutral (adds no operands, no branch), so it needs no frame or
 * max-stack change.
 */
public final class ClientEntrypointHookInjector implements ClassTransformer {
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String OPTIONS = "net/minecraft/client/Options";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onClientEntrypoints";

	@Override
	public String name() {
		return "forbric-client-entrypoint-hook";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !MINECRAFT.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			AbstractInsnNode newOptions = firstNewOptions(m);
			if (newOptions == null) continue;
			m.instructions.insertBefore(newOptions,
					new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, "()V", false));
			changed = true;
			ForbricLog.info("[Forbric/Fabric] wired client-entrypoint hook into Minecraft.<init> (before Options) — "
					+ "Fabric client entrypoints now fire with a live Minecraft.getInstance()");
			break;
		}
		if (!changed) return classBytes;

		// ClassWriter(0): the inserted call is stack-neutral and adds no branch target, so the original frames stay
		// valid (their offsets shift, which ASM handles on write) and neither COMPUTE_FRAMES nor COMPUTE_MAXS is needed.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static AbstractInsnNode firstNewOptions(MethodNode ctor) {
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.NEW && ((TypeInsnNode) insn).desc.equals(OPTIONS)) return insn;
		}
		return null;
	}
}
