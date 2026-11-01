/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NeoForge's fire clause asks a fluid the NeoForge type the fluid gave itself, not the stand-in the kernel derived for a
 * Fabric mod's fluid from its tags.
 *
 * <p>Vanilla's {@code Fluid.entityInside} is empty: only {@code WaterFluid} puts out a burning body, and a fluid a mod
 * put in {@code minecraft:water} swims like water but never extinguishes. That is what a Fabric mod's fluid does on
 * Fabric. NeoForge's base replaces the empty body with {@code if (entity.canFluidExtinguish(getFluidType()))
 * apply(EXTINGUISH)}, which is right for a NeoForge mod's fluid (its own type says {@code canExtinguish}) and for
 * vanilla's (water's type says yes). A Fabric mod's fluid never declares a NeoForge type, so it answers with the
 * kernel's tag-derived stand-in (ForeignFluidTypeInjector, KernelFluidTypes): water's type for one in
 * {@code minecraft:water}, which then put the fire out (gate-m45 fire.fabricWater).
 *
 * <p>So every call of the fluid's NeoForge getter whose answer goes straight to {@code Entity.canFluidExtinguish} is
 * followed by {@code KernelFluidTypes.ownNeoType(fluid, type)}: the type itself, or NeoForge's empty type — which
 * claims nothing — where the kernel's stand-in answered. A NeoForge mod's fluid and vanilla's keep their own types. The
 * clause is found by that shape, not by method name (today it is only {@code Fluid.entityInside}). Not made twice.
 * {@code -Dforbric.ownFluidTypeClauses=off} leaves it asking the stand-in.
 *
 * <p>On the merged base this injector answered the same clause where MinecraftForge's and NeoForge's patches sat side by
 * side (and {@code BucketItem.playEmptySound}, whose NeoForge sound for a stand-in agrees with vanilla's tag rule and is
 * left alone here).
 */
public final class OwnFluidTypeClausesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.ownFluidTypeClauses";
	static final String FLUID = "net/minecraft/world/level/material/Fluid";
	static final String NEO_TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	static final String EXTINGUISH = "canFluidExtinguish";
	static final String EXTINGUISH_DESC = "(L" + NEO_TYPE + ";)Z";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidTypes";
	/** The classes on NeoForge's base with such a clause (OwnFluidTypeClausesInjectorTest re-derives the list). */
	static final List<String> KNOWN = List.of("net.minecraft.world.level.material.Fluid");
	private static final byte[] NEO_BYTES = NEO_TYPE.getBytes(StandardCharsets.UTF_8), EXTINGUISH_BYTES = EXTINGUISH.getBytes(StandardCharsets.UTF_8);

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-own-fluid-type-clauses"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's fire clause left asking a Fabric fluid's stand-in type with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(KNOWN.get(0), AnchorSet.Severity.REQUIRED, "a Fabric mod's fluid in minecraft:water "
				+ "puts out a burning body, which vanilla's (and so Fabric's) Fluid.entityInside never does"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !contains(bytes, NEO_BYTES) || !contains(bytes, EXTINGUISH_BYTES)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int edited = repair(node);
		if (edited <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] %s: %d method(s) asking whether a fluid puts out fire ask the NeoForge type the fluid "
				+ "gave itself, not a Fabric fluid's tag-derived stand-in", className, edited);
		return writer.toByteArray();
	}

	/** The number of methods edited; a method already edited, or with no such clause, is left alone. */
	static int repair(ClassNode node) {
		int edited = 0;
		for (MethodNode method : node.methods) {
			List<MethodInsnNode> clauses = clauses(method);
			if (clauses.isEmpty() || answered(method)) continue;
			for (MethodInsnNode call : clauses) own(method, call);
			edited++;
		}
		return edited;
	}

	/** The method's calls of the fluid's NeoForge getter whose answer is handed straight to {@code canFluidExtinguish}. */
	static List<MethodInsnNode> clauses(MethodNode method) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(FLUID)
					&& call.name.equals("getFluidType") && call.desc.equals("()L" + NEO_TYPE + ";")
					&& next(call) instanceof MethodInsnNode use && use.name.equals(EXTINGUISH) && use.desc.equals(EXTINGUISH_DESC)) {
				out.add(call);
			}
		}
		return out;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() < 0) n = n.getNext();
		return n;
	}

	private static boolean answered(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && call.name.equals("ownNeoType")) return true;
		}
		return false;
	}

	/** {@code fluid.getFluidType()} becomes {@code KernelFluidTypes.ownNeoType(fluid, fluid.getFluidType())}: dup the fluid, ask, filter. */
	private static void own(MethodNode method, MethodInsnNode call) {
		method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
		InsnList after = new InsnList();
		after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "ownNeoType", "(L" + FLUID + ";L" + NEO_TYPE + ";)L" + NEO_TYPE + ";", false));
		method.instructions.insert(call, after);
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
			for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
			return true;
		}
		return false;
	}
}
