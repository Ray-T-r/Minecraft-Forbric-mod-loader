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
 * A merged method that asks one fluid both families' type answers each family's clause with a type that family gave the
 * fluid itself.
 *
 * <p>Where MinecraftForge and NeoForge each patched the same vanilla method to ask the fluid's type, the merge kept both
 * clauses side by side: {@code Fluid.entityInside} puts out a body's fire when MinecraftForge's type says
 * {@code canExtinguish} and again when NeoForge's does, and {@code BucketItem.playEmptySound} plays NeoForge's type's
 * sound unless MinecraftForge's type has one. On its own loader a fluid is asked only the type that loader gave it. Here
 * every fluid answers both: a NeoForge mod's fluid answers MinecraftForge's question with the kernel's stand-in, derived
 * from its tags, and a Fabric mod's answers both with stand-ins. So a NeoForge fluid in {@code minecraft:water} whose own
 * type says {@code canExtinguish(false)} put a body's fire out — MinecraftForge's water type, its stand-in, says true —
 * and so did a Fabric mod's fluid in that tag, which vanilla never extinguishes in (only {@code WaterFluid} does), and a
 * NeoForge fluid's own bucket sound gave way to water's or lava's.
 *
 * <p>In each such method every call of the fluid's MinecraftForge getter is followed by
 * {@code KernelFluidTypes.ownForgeType(fluid, type)}, and of its NeoForge getter by {@code ownNeoType}: the type itself,
 * or that family's empty type — which claims nothing — where the kernel's stand-in answered. A MinecraftForge mod's
 * fluid keeps its own type in MinecraftForge's clause and NeoForge's view of it in NeoForge's; a NeoForge mod's keeps its
 * own type; vanilla's fluids keep both families' types. The methods are found by that shape — calls of both
 * {@code Fluid.getFluidType()} getters in one body — not by name. Not made twice. {@code -Dforbric.ownFluidTypeClauses=off}
 * leaves them as merged.
 */
public final class OwnFluidTypeClausesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.ownFluidTypeClauses";
	static final String FLUID = "net/minecraft/world/level/material/Fluid";
	static final String FORGE_TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.FORGE);
	static final String NEO_TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidTypes";
	/** The classes on today's merged base with such a method (OwnFluidTypeClausesInjectorTest re-derives the list). */
	static final List<String> KNOWN = List.of("net.minecraft.world.level.material.Fluid", "net.minecraft.world.item.BucketItem");
	private static final byte[] FORGE_BYTES = FORGE_TYPE.getBytes(StandardCharsets.UTF_8), NEO_BYTES = NEO_TYPE.getBytes(StandardCharsets.UTF_8);

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-own-fluid-type-clauses"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("each family's fluid-type clause left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(KNOWN.get(0), AnchorSet.Severity.REQUIRED, "a NeoForge or Fabric mod's fluid in minecraft:water puts out "
						+ "a body's fire however its own type answers"),
				new AnchorSet.Anchor(KNOWN.get(1), AnchorSet.Severity.REQUIRED, "a NeoForge mod's fluid poured from a bucket plays water's or "
						+ "lava's sound instead of its own"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !contains(bytes, FORGE_BYTES) || !contains(bytes, NEO_BYTES)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int edited = repair(node);
		if (edited <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] %s: %d method(s) asking a fluid both families' type ask each family only the type it gave the fluid",
				className, edited);
		return writer.toByteArray();
	}

	/** The number of methods edited; a method already edited, or asking only one family, is left alone. */
	static int repair(ClassNode node) {
		int edited = 0;
		for (MethodNode method : node.methods) {
			List<MethodInsnNode> forge = getters(method, FORGE_TYPE), neo = getters(method, NEO_TYPE);
			if (forge.isEmpty() || neo.isEmpty() || answered(method)) continue;
			for (MethodInsnNode call : forge) own(method, call, "ownForgeType", FORGE_TYPE);
			for (MethodInsnNode call : neo) own(method, call, "ownNeoType", NEO_TYPE);
			edited++;
		}
		return edited;
	}

	/** The method's calls of the fluid's getter of one family's type. */
	static List<MethodInsnNode> getters(MethodNode method, String type) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(FLUID)
					&& call.name.equals("getFluidType") && call.desc.equals("()L" + type + ";")) out.add(call);
		}
		return out;
	}

	private static boolean answered(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && (call.name.equals("ownForgeType") || call.name.equals("ownNeoType"))) return true;
		}
		return false;
	}

	/** {@code fluid.getFluidType()} becomes {@code KernelFluidTypes.<own>(fluid, fluid.getFluidType())}: dup the fluid, ask, filter. */
	private static void own(MethodNode method, MethodInsnNode call, String own, String type) {
		method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
		InsnList after = new InsnList();
		after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, own, "(L" + FLUID + ";L" + type + ";)L" + type + ";", false));
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
