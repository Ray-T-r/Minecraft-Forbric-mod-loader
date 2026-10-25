/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A fluid typed as NeoForge's empty type is no fluid to NeoForge's entity code, as the type says.
 *
 * <p>NeoForge's {@code EntityFluidInteraction.update} skips an empty cell and gives every other cell's type a tracker:
 * on NeoForge only the empty fluid state has the empty type, so no tracker of that type ever exists. On the merged base
 * the kernel gives the empty type to a fluid no loader types and no tag gives meaning — a Fabric mod's fluid in neither
 * {@code minecraft:water} nor {@code minecraft:lava} nor a Fabric behaviour's tag (KernelFluidTypes) — meaning "entities
 * do not interact with it", which is what vanilla does with such a fluid. But the walk made a tracker for it all the
 * same, and NeoForge's entity code reads any tracker with height as "in a fluid": {@code shouldTravelInFluid} asks
 * {@code isInFluidType(state)}, so a body in such a fluid moved as through lava (the merged {@code travelInFluid} sends
 * every fluid but water there), where on Fabric it falls through it as through air.
 *
 * <p>NeoForge's private {@code getTrackerFor(FluidType)}, which only the walk calls, answers null for the empty type —
 * the walk already passes over a cell with no tracker — so no native fluid's cell is treated differently. MinecraftForge's
 * record of the same cell (ForgeEntityFluidsInjector, which keeps MinecraftForge's "any fluid touched") is taken before
 * this lookup and is unchanged. On the reviewed shape only: one parameter, NeoForge's type, returning the tracker. Not
 * made twice. {@code -Dforbric.emptyFluidTypeTracker=off} leaves it as merged.
 */
public final class EmptyFluidTypeTrackerInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.emptyFluidTypeTracker";
	static final String INTERACTION = "net.minecraft.world.entity.EntityFluidInteraction";
	static final String OWNER = INTERACTION.replace('.', '/');
	static final String NEO_TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	/** NeoForge's lookup, as one name-and-descriptor key (the uncalled-method census reads a bare name as a call of every overload). */
	static final String LOOKUP = "getTrackerFor(L" + NEO_TYPE + ";)L" + OWNER + "$Tracker;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-empty-fluid-type-tracker"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's empty fluid type keeps a tracker with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(INTERACTION, AnchorSet.Severity.REQUIRED,
				"a body in a Fabric fluid in no tag moves as through lava instead of falling through it"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !INTERACTION.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] EntityFluidInteraction gives NeoForge's empty fluid type no tracker — a fluid typed empty is no "
				+ "fluid to NeoForge's entity code");
		return writer.toByteArray();
	}

	/** 1 when edited, 0 when already edited, -1 (logged) when the class is not the reviewed shape. */
	static int repair(ClassNode node) {
		MethodNode lookup = null;
		for (MethodNode m : node.methods) if ((m.name + m.desc).equals(LOOKUP)) lookup = m;
		if (lookup == null || (lookup.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return declined("NeoForge's tracker lookup by type is missing");
		for (AbstractInsnNode insn : lookup.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(NEO_TYPE)
					&& call.name.equals("isAir") && call.desc.equals("()Z")) return 0;
			if (insn instanceof FrameNode || insn instanceof JumpInsnNode) return declined("NeoForge's tracker lookup by type branches");
		}
		LabelNode typed = new LabelNode();
		InsnList guard = new InsnList();
		guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
		guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NEO_TYPE, "isAir", "()Z", false));
		guard.add(new JumpInsnNode(Opcodes.IFEQ, typed));
		guard.add(new InsnNode(Opcodes.ACONST_NULL));
		guard.add(new InsnNode(Opcodes.ARETURN));
		guard.add(typed);
		guard.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {node.name, NEO_TYPE}, 0, new Object[0]));
		lookup.instructions.insert(guard);
		return 1;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] left EntityFluidInteraction's tracker lookup as merged: %s — a fluid typed as NeoForge's empty type "
				+ "still counts as a fluid to NeoForge's entity code", reason);
		return -1;
	}
}
