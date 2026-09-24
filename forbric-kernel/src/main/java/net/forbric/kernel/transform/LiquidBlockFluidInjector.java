/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * {@code LiquidBlock.getFluid()} answers for vanilla water and lava again.
 *
 * <p>The merged getter is MinecraftForge's, {@code supplier.get()}; the merged constructors are NeoForge's, and the
 * one vanilla uses ({@code FlowingFluid, Properties}) sets the {@code fluid} field and never the supplier. So the
 * getter threw a NullPointerException for every vanilla liquid — for MinecraftForge's own fluid utilities and for
 * every mod that asks a liquid block for its fluid (JourneyMap's map tint, pipes). The getter now returns the field
 * when it is set and falls back to the supplier, which covers both constructors. This is a merge repair, not one of
 * NeoForge's coremods; it sits under the same master switch ({@code -Dforbric.coremodParity=off}) so that switch
 * restores everything this round changed, and {@code -Dforbric.liquidBlockFluid=off} turns it off alone.
 */
public final class LiquidBlockFluidInjector implements ClassTransformer {
	static final String PROPERTY = "forbric.liquidBlockFluid";
	static final String TARGET = "net.minecraft.world.level.block.LiquidBlock";
	static final String OWNER = "net/minecraft/world/level/block/LiquidBlock";
	static final String FLUID = "net/minecraft/world/level/material/FlowingFluid";
	static final String SUPPLIER = "java/util/function/Supplier";

	static boolean enabled() {
		return NativeCoremodParity.on(PROPERTY);
	}

	@Override public String name() { return "forbric-liquid-block-fluid"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("liquid block getter repair explicitly disabled with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"LiquidBlock.getFluid() throws for vanilla water and lava"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!hasField(node, "fluid", "L" + FLUID + ";") || !hasField(node, "supplier", "L" + SUPPLIER + ";")) return bytes;
		MethodNode getter = null;
		for (MethodNode method : node.methods) {
			if (method.name.equals("getFluid") && method.desc.equals("()L" + FLUID + ";")) getter = method;
		}
		if (getter == null || !suppliersOnly(getter)) {
			if (getter != null && !readsField(getter)) {
				ForbricLog.warn("[Forbric/Fluid] left LiquidBlock.getFluid() as merged: its body is not the reviewed shape");
			}
			return bytes;
		}
		LabelNode done = new LabelNode();
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		body.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "fluid", "L" + FLUID + ";"));
		body.add(new InsnNode(Opcodes.DUP));
		body.add(new JumpInsnNode(Opcodes.IFNONNULL, done));
		body.add(new InsnNode(Opcodes.POP));
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		body.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "supplier", "L" + SUPPLIER + ";"));
		body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, SUPPLIER, "get", "()Ljava/lang/Object;", true));
		body.add(new TypeInsnNode(Opcodes.CHECKCAST, FLUID));
		body.add(done);
		body.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { FLUID }));
		body.add(new InsnNode(Opcodes.ARETURN));
		getter.instructions.clear();
		getter.tryCatchBlocks.clear();
		getter.localVariables = null;
		getter.instructions.add(body);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] LiquidBlock.getFluid() now answers the field vanilla's constructor sets, then the "
				+ "supplier — it threw for vanilla water and lava");
		return writer.toByteArray();
	}

	/** The merged (MinecraftForge) body: {@code return (FlowingFluid) this.supplier.get();} and nothing else. */
	private static boolean suppliersOnly(MethodNode getter) {
		int real = 0;
		boolean supplier = false;
		for (AbstractInsnNode insn : getter.instructions) {
			if (insn.getOpcode() < 0) continue;
			real++;
			if (insn instanceof FieldInsnNode read && read.name.equals("supplier")) supplier = true;
			if (insn instanceof FieldInsnNode read && read.name.equals("fluid")) return false;
		}
		return supplier && real == 5;
	}

	private static boolean readsField(MethodNode getter) {
		for (AbstractInsnNode insn : getter.instructions) {
			if (insn instanceof FieldInsnNode read && read.name.equals("fluid")) return true;
		}
		return false;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return true;
		return false;
	}
}
