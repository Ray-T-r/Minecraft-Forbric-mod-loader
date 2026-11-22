/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.FabricRegistryWindow;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets the built-in registries be written before Fabric's registry freeze point, as a Fabric game with fabric-api has
 * them ({@link FabricRegistryWindow} says why). {@code MappedRegistry.validateWrite(ResourceKey)} — the check every
 * {@code register} makes first — throws "Registry is already frozen" when {@code frozen} is set; this puts one question
 * in front of that throw: {@code KernelFabricRegistryWindow.admit(this, key)}, which answers true only for a built-in
 * registry while the window is open, having reopened it. A world's datapack registries, and every registry after the
 * window, throw as before.
 *
 * <p>Keyed on the shape, not on a name the base might change: the {@code frozen} read followed by the branch that skips
 * the throw, exactly once in the method. Anything else leaves the class unchanged.
 */
public final class FabricRegistryWindowInjector implements ClassTransformer {
	public static final String TARGET = "net.minecraft.core.MappedRegistry";
	static final String OWNER = "net/minecraft/core/MappedRegistry";
	static final String METHOD = "validateWrite";
	static final String METHOD_DESC = "(Lnet/minecraft/resources/ResourceKey;)V";
	static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelFabricRegistryWindow";
	static final String HOOK = "admit";
	static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

	@Override
	public String name() {
		return "forbric-fabric-registry-window";
	}

	@Override
	public AnchorSet anchors() {
		if (!FabricRegistryWindow.enabled()) return AnchorSet.scanned("switched off by -D" + FabricRegistryWindow.PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a Fabric mod registering before the Fabric entrypoints (woken early by another mod) writes into a frozen "
						+ "registry; a new registry's key is left in NeoForge's registration order without a registry and "
						+ "GameData.postRegisterEvents stops the boot"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!FabricRegistryWindow.enabled() || bytes == null || bytes.length == 0 || !TARGET.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode validate = node.methods.stream()
				.filter(m -> m.name.equals(METHOD) && m.desc.equals(METHOD_DESC)).findFirst().orElse(null);
		JumpInsnNode skip = validate == null ? null : frozenBranch(validate);
		if (skip == null) {
			ForbricLog.debug("[Forbric/RegistrySync] MappedRegistry.validateWrite is not the shape Fabric's registry window keys on "
					+ "(one frozen read, then the branch past the throw) — left as it is");
			return bytes;
		}
		InsnList ask = new InsnList();
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new VarInsnNode(Opcodes.ALOAD, 1));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK, HOOK_DESC, false));
		// Admitted: the same label the unfrozen case jumps to, whose frame (nothing on the stack) is already there.
		ask.add(new JumpInsnNode(Opcodes.IFNE, skip.label));
		validate.instructions.insert(skip, ask);
		validate.maxStack = Math.max(validate.maxStack, 2);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.debug("[Forbric/RegistrySync] MappedRegistry.validateWrite asks Fabric's registry window before it throws");
		return writer.toByteArray();
	}

	/** The {@code IFEQ} right after the method's single {@code this.frozen} read, or null when there is not exactly one. */
	static JumpInsnNode frozenBranch(MethodNode method) {
		JumpInsnNode found = null;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || !field.owner.equals(OWNER)
					|| !field.name.equals("frozen") || !field.desc.equals("Z")) continue;
			AbstractInsnNode next = field.getNext();
			while (next != null && next.getOpcode() < 0) next = next.getNext();
			if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ || found != null) return null;
			found = jump;
		}
		return found;
	}
}
