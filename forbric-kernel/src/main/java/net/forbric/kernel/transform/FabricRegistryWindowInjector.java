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
 *
 * <p>The same window covers {@code freeze()}'s intrusive-holder check. A client freezes the registries twice: where
 * NeoForge's registration window closes, and again at Fabric's freeze point, after the Fabric main and client entrypoints
 * ({@code KernelLifecycle.onClientEntrypoints}). Before the first, NeoForge's tail of {@code GameData.postRegisterEvents}
 * has run guest code: its attribute events walk {@code ENTITY_TYPE} through {@code DefaultAttributes.hasSupplier}, a Fabric
 * mod's mixin there reads a field of its entity class, and that class's initialiser builds an {@code EntityType} — an
 * intrusive holder — that the mod's {@code onInitialize} registers later (Better Nether's {@code naga_projectile}). The first
 * freeze threw "Some intrusive holders were not registered", and {@code GameData.freezeData} ended part-way: every registry
 * after that one left open, no frozen snapshot. On Fabric nothing checks the holders before its freeze point, so before it
 * {@code freeze()} asks {@code KernelFabricRegistryWindow.carryIntrusiveHolders(this, unregisteredIntrusiveHolders)} and
 * skips the throw on true. NeoForge's {@code freeze()} keeps the map, so the holders stay listed, the entrypoint that
 * registers one takes it off, and the freeze at Fabric's freeze point throws for whatever is left, as Fabric's does. Keyed
 * on the shape: the one read of {@code unregisteredIntrusiveHolders} in {@code freeze()} that is null-tested. Either
 * method of another shape keeps its own check, and the other is still given its question.
 */
public final class FabricRegistryWindowInjector implements ClassTransformer {
	public static final String TARGET = "net.minecraft.core.MappedRegistry";
	static final String OWNER = "net/minecraft/core/MappedRegistry";
	static final String METHOD = "validateWrite";
	static final String METHOD_DESC = "(Lnet/minecraft/resources/ResourceKey;)V";
	static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelFabricRegistryWindow";
	static final String HOOK = "admit";
	static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
	static final String FREEZE = "freeze";
	static final String FREEZE_DESC = "()Lnet/minecraft/core/Registry;";
	static final String INTRUSIVE = "unregisteredIntrusiveHolders";
	static final String CARRY_HOOK = "carryIntrusiveHolders";

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
		boolean asked = skip != null;
		if (!asked) {
			ForbricLog.debug("[Forbric/RegistrySync] MappedRegistry.validateWrite is not the shape Fabric's registry window keys on "
					+ "(one frozen read, then the branch past the throw) — left as it is");
		} else {
			InsnList ask = new InsnList();
			ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
			ask.add(new VarInsnNode(Opcodes.ALOAD, 1));
			ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK, HOOK_DESC, false));
			// Admitted: the same label the unfrozen case jumps to, whose frame (nothing on the stack) is already there.
			ask.add(new JumpInsnNode(Opcodes.IFNE, skip.label));
			validate.instructions.insert(skip, ask);
			validate.maxStack = Math.max(validate.maxStack, 2);
			ForbricLog.debug("[Forbric/RegistrySync] MappedRegistry.validateWrite asks Fabric's registry window before it throws");
		}
		boolean carried = carryIntrusiveHolders(node);
		if (!asked && !carried) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * {@code freeze()}'s intrusive-holder check asks the window first (see the class comment): right after the null test of
	 * {@code unregisteredIntrusiveHolders}, a true answer goes where a null map goes, past the throw. A {@code freeze()} of
	 * another shape keeps its check as it is.
	 */
	static boolean carryIntrusiveHolders(ClassNode node) {
		MethodNode freeze = node.methods.stream()
				.filter(m -> m.name.equals(FREEZE) && m.desc.equals(FREEZE_DESC)).findFirst().orElse(null);
		JumpInsnNode nullTest = freeze == null ? null : intrusiveNullTest(freeze);
		if (nullTest == null) {
			ForbricLog.debug("[Forbric/RegistrySync] MappedRegistry.freeze is not the shape Fabric's registry window keys on "
					+ "(one null-tested read of the unregistered intrusive holders) — its check stays as it is");
			return false;
		}
		InsnList ask = new InsnList();
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, INTRUSIVE, "Ljava/util/Map;"));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, CARRY_HOOK, HOOK_DESC, false));
		// Carried: the label a null map jumps to, past the throw, with nothing on the stack there either.
		ask.add(new JumpInsnNode(Opcodes.IFNE, nullTest.label));
		freeze.instructions.insert(nullTest, ask);
		freeze.maxStack = Math.max(freeze.maxStack, 2);
		ForbricLog.debug("[Forbric/RegistrySync] MappedRegistry.freeze asks Fabric's registry window before it throws for "
				+ "unregistered intrusive holders");
		return true;
	}

	/** The {@code IFNULL} right after a read of {@code this.unregisteredIntrusiveHolders}, or null unless there is exactly one. */
	static JumpInsnNode intrusiveNullTest(MethodNode method) {
		JumpInsnNode found = null;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || !field.owner.equals(OWNER)
					|| !field.name.equals(INTRUSIVE) || !field.desc.equals("Ljava/util/Map;")) continue;
			AbstractInsnNode next = field.getNext();
			while (next != null && next.getOpcode() < 0) next = next.getNext();
			if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNULL) continue;
			if (found != null) return null;
			found = jump;
		}
		return found;
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
