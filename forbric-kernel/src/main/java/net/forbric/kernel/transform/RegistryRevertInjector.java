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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives NeoForge's disconnect-time registry revert a snapshot to put back: {@code RegistryManager.revertToFrozen()} applies
 * the kernel's own pre-connection snapshot ({@code KernelRegistryRevert}).
 *
 * <p>Leaving a world, {@code Minecraft.disconnect} calls NeoForge's {@code RegistryManager.revertToFrozen}, which re-applies
 * the snapshot {@code GameData.freezeData} takes. The kernel owns the freeze, so that snapshot is never taken, and NeoForge's
 * own body dies on it ({@code frozenSnapshot} null, a {@code NullPointerException} on every world leave). Its body becomes
 * {@code KernelRegistryRevert.revertToPreConnection}, which puts back the ids the kernel captured as the connection's first
 * remap began, or says there is nothing to revert.
 *
 * <p>This is NeoForge's class and NeoForge's lifecycle on any base, which is why it is its own transformer: it was one of the
 * edits {@link RegistrySyncParityInjector} made to the same class, beside the ones that give MinecraftForge's
 * {@code NamespacedWrapper} NeoForge's remap contract — and those go with MinecraftForge, while this must not.
 *
 * <p><b>The capture.</b> The snapshot is captured at the head of {@code applySnapshot(Map, boolean)}, NeoForge's remap entry
 * point. On a base with MinecraftForge, {@link RegistrySyncParityInjector} (registered before this) already hooks that head
 * with {@code KernelForgeWrapperSync.beginSnapshotApplication}, which resets the wrappers' staging and captures; so this
 * adds {@code KernelRegistryRevert.captureIfNeeded} only where nothing there captures yet, and on today's base stands down
 * for it, leaving {@code applySnapshot} exactly as that hook made it. Without MinecraftForge the head call is this one.
 */
public final class RegistryRevertInjector implements ClassTransformer {
	private static final String NEO_REGISTRY_MANAGER = ForeignType.REGISTRY_MANAGER.binary(Ecosystem.NEOFORGE);

	static final String REVERT_TO_FROZEN = "revertToFrozen";
	static final String APPLY_SNAPSHOT = "applySnapshot";
	static final String APPLY_SNAPSHOT_DESC = "(Ljava/util/Map;Z)Ljava/util/Set;";
	static final String REVERT_OWNER = "net/forbric/kernel/boot/KernelRegistryRevert";
	/** MinecraftForge's wrapper sync, whose head hook on {@code applySnapshot} captures as well. */
	static final String WRAPPER_SYNC_OWNER = "net/forbric/kernel/boot/KernelForgeWrapperSync";

	@Override
	public String name() {
		return "forbric-registry-revert";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(NEO_REGISTRY_MANAGER, AnchorSet.Severity.REQUIRED,
				"leaving a world would die in NeoForge's own revertToFrozen (its frozen snapshot is never taken under the "
						+ "kernel's freeze), and the kernel's own pre-connection registry snapshot would never be applied, so "
						+ "ids would not revert after a multiplayer disconnect"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !NEO_REGISTRY_MANAGER.equals(className)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;

		// revertToFrozen(): NeoForge's disconnect-time revert, which re-applies a snapshot GameData.freezeData takes
		// — a freeze the kernel owns, so the snapshot never existed and the call used to be neutered. The kernel
		// captures its own at the first remap (the head of applySnapshot) and this body now puts it back.
		for (MethodNode m : node.methods) {
			if (!m.name.equals(REVERT_TO_FROZEN) || !m.desc.equals("()V") || (m.access & Opcodes.ACC_STATIC) == 0) continue;
			InsnList body = new InsnList();
			body.add(new LdcInsnNode(Type.getObjectType(node.name)));
			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REVERT_OWNER, "revertToPreConnection", "(Ljava/lang/Class;)V", false));
			body.add(new InsnNode(Opcodes.RETURN));
			m.instructions = body;
			m.tryCatchBlocks = null;
			m.localVariables = null;
			m.maxStack = 1;
			m.maxLocals = 0;
			changed = true;
			ForbricLog.info("[Forbric/RegistrySync] %s.%s() now restores the kernel's own pre-connection snapshot", className,
					REVERT_TO_FROZEN);
		}

		for (MethodNode m : node.methods) {
			if (!m.name.equals(APPLY_SNAPSHOT) || !m.desc.equals(APPLY_SNAPSHOT_DESC) || (m.access & Opcodes.ACC_STATIC) == 0) continue;
			if (captures(m)) {
				ForbricLog.debug("[Forbric/RegistrySync] %s.%s already captures the pre-connection ids at its head — the "
						+ "revert's own capture stands down", className, APPLY_SNAPSHOT);
				continue;
			}
			InsnList head = new InsnList();
			head.add(new LdcInsnNode(Type.getObjectType(node.name))); // a game class, for its loader
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REVERT_OWNER, "captureIfNeeded", "(Ljava/lang/Class;)V", false));
			m.instructions.insert(head);
			m.maxStack = Math.max(m.maxStack, 1);
			changed = true;
			ForbricLog.info("[Forbric/RegistrySync] %s.%s now captures the pre-connection ids the disconnect puts back",
					className, APPLY_SNAPSHOT);
		}

		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether {@code applySnapshot} already calls something that captures the pre-connection ids. */
	static boolean captures(MethodNode applySnapshot) {
		for (AbstractInsnNode insn : applySnapshot.instructions) {
			if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
			if (call.owner.equals(REVERT_OWNER) && call.name.equals("captureIfNeeded")) return true;
			if (call.owner.equals(WRAPPER_SYNC_OWNER) && call.name.equals("beginSnapshotApplication")) return true;
		}
		return false;
	}
}
