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
 * A fluid from a Fabric or MinecraftForge mod has a NeoForge fluid type, so an entity touching it no longer crashes the
 * server.
 *
 * <p>The merged {@code Fluid.getFluidType()} is NeoForge's: cache the answer of {@code CommonHooks.getVanillaFluidType},
 * which throws "Mod fluids must override getFluidType." for every fluid that is not vanilla's or NeoForge's milk. A
 * NeoForge mod overrides the method; a Fabric mod's fluid cannot (and a MinecraftForge mod's override returns
 * MinecraftForge's type, a different method). NeoForge's entity code asks every fluid an entity touches, so the first
 * pig to walk into a Fabric mod's fluid threw "Ticking entity" and stopped the server.
 *
 * <p>Before NeoForge's lookup, the method now asks {@code KernelFluidTypes.foreignType}, which answers only for a fluid
 * NeoForge's lookup would throw on, from the fluid's tags the way vanilla decides — water, lava, or none — and is not
 * cached, since tags are bound late and rebound on reload. Every fluid NeoForge answers keeps NeoForge's cached path.
 * Only on the reviewed shape: the method caches in its own field and its only call is {@code getVanillaFluidType}.
 * {@code -Dforbric.foreignFluidTypes=off} leaves it as merged.
 */
public final class ForeignFluidTypeInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.foreignFluidTypes";
	static final String FLUID = "net.minecraft.world.level.material.Fluid";
	static final String FLUID_INTERNAL = "net/minecraft/world/level/material/Fluid";
	static final String TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidTypes";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-foreign-fluid-types"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("foreign fluids explicitly left without a NeoForge type with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(FLUID, AnchorSet.Severity.REQUIRED,
				"an entity touching a Fabric or MinecraftForge mod's fluid crashes the server"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !FLUID.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] a fluid with no NeoForge type of its own (a Fabric or MinecraftForge mod's) gets the "
				+ "one its fluid tags imply, instead of NeoForge's \"Mod fluids must override getFluidType\"");
		return writer.toByteArray();
	}

	static int repair(ClassNode fluid) {
		MethodNode method = null;
		for (MethodNode m : fluid.methods) if (m.name.equals("getFluidType") && m.desc.equals("()L" + TYPE + ";")) method = m;
		if (method == null) return declined("Fluid.getFluidType() is missing");
		JumpInsnNode cached = null;
		int lookups = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals(RUNTIME)) return 0;   // already done
				if (call.name.equals("getVanillaFluidType")) lookups++;
				else return declined("Fluid.getFluidType() calls " + call.owner + "." + call.name);
			}
			if (cached == null && insn instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNONNULL) cached = jump;
		}
		if (lookups != 1 || cached == null) return declined("Fluid.getFluidType() is not NeoForge's cached lookup");
		// Right after the cache test: ask the kernel; a non-null answer is returned uncached, null falls through.
		LabelNode native_ = new LabelNode();
		InsnList ask = new InsnList();
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "foreignType", "(L" + FLUID_INTERNAL + ";)L" + TYPE + ";", false));
		ask.add(new InsnNode(Opcodes.DUP));
		ask.add(new JumpInsnNode(Opcodes.IFNULL, native_));
		ask.add(new InsnNode(Opcodes.ARETURN));
		ask.add(native_);
		ask.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { TYPE }));
		ask.add(new InsnNode(Opcodes.POP));
		method.instructions.insert(cached, ask);
		return 1;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] left Fluid.getFluidType() as merged: %s — an entity touching a mod fluid with no "
				+ "NeoForge type crashes the server", reason);
		return -1;
	}
}
