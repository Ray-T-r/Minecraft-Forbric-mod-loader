/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Two water rules NeoForge's entity code has and MinecraftForge's does not, answered for a MinecraftForge mod's fluid as
 * MinecraftForge answers them.
 *
 * <p>Where vanilla asks the {@code minecraft:water} tag, NeoForge's entity code mostly asks a type's
 * {@code isWaterLike}, which the kernel's view of a MinecraftForge type answers from that tag. Two rules ask whether the
 * entity can swim in the type instead, through an {@code InFluidPredicate}:
 * <ul>
 *   <li>{@code Player.updateIsUnderwater} — vanilla and MinecraftForge: eyes in {@code minecraft:water}; NeoForge: eyes in
 *       a type the player can swim in. A player in a MinecraftForge mod's swimmable fluid outside the tag heard the
 *       underwater sounds and music there, and one in a fluid in the tag that its type calls unswimmable did not.</li>
 *   <li>{@code Entity.isInShallowWater} — vanilla and MinecraftForge: in water, eyes out; NeoForge adds "or in a type it
 *       can swim in, eyes out". MinecraftForge has no such clause.</li>
 * </ul>
 * In each rule's predicate the {@code canSwimInFluidType} call becomes {@code KernelForgeFluidTypeViews.underwaterIn} /
 * {@code shallowIn}: for a view, the MinecraftForge answer (the tag; never); for any other type, the same
 * {@code canSwimInFluidType}, so NeoForge's and vanilla's fluids are decided as merged. On the reviewed shape only: the
 * method passes one lambda of the class's own to its fluid predicate, and that lambda asks {@code canSwimInFluidType}
 * once. Not made twice. {@code -Dforbric.forgeFluidOwnType=off} (no views) leaves both as merged.
 */
public final class ForgeFluidWaterRulesInjector implements ClassTransformer {
	/** The views' own switch: without them every type is decided as merged anyway. */
	public static final String PROPERTY = "forbric.forgeFluidOwnType";
	static final String PLAYER = "net.minecraft.world.entity.player.Player";
	static final String ENTITY = "net.minecraft.world.entity.Entity";
	static final String NEO_TYPE = "L" + ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE) + ";";
	static final String CAN_SWIM = "canSwimInFluidType";
	static final String CAN_SWIM_DESC = "(" + NEO_TYPE + ")Z";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeFluidTypeViews";
	static final String RULE_DESC = "(Lnet/minecraft/world/entity/Entity;" + NEO_TYPE + ")Z";

	/** The rules: class, method, the kernel answer for its predicate's swim question. */
	static final List<String[]> RULES = List.of(
			new String[] {PLAYER, "updateIsUnderwater", "()Z", "underwaterIn"},
			new String[] {ENTITY, "isInShallowWater", "()Z", "shallowIn"});

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-fluid-water-rules"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("no NeoForge views of MinecraftForge fluid types with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(PLAYER, AnchorSet.Severity.REQUIRED, "a player hears the underwater sounds in a MinecraftForge fluid "
						+ "its own platform does not call water, and not in one it does"),
				new AnchorSet.Anchor(ENTITY, AnchorSet.Severity.REQUIRED, "a body in a MinecraftForge fluid, eyes out, counts as in shallow water"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || (!PLAYER.equals(className) && !ENTITY.equals(className))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int changed = 0;
		for (String[] rule : RULES) {
			if (!rule[0].equals(className)) continue;
			int result = repair(node, rule[1], rule[2], rule[3]);
			if (result > 0) changed++;
		}
		if (changed == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] %s: %d NeoForge water rule(s) answer a MinecraftForge fluid as MinecraftForge does", className, changed);
		return writer.toByteArray();
	}

	/** 1 when edited, 0 when already edited, -1 (logged) when the method is not the reviewed shape. */
	static int repair(ClassNode node, String name, String desc, String answer) {
		MethodNode method = find(node, name, desc);
		if (method == null) return declined(node, name, "the method is missing");
		MethodNode lambda = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle target)) continue;
			if (!target.getOwner().equals(node.name) || target.getTag() != Opcodes.H_INVOKESTATIC) continue;
			MethodNode candidate = find(node, target.getName(), target.getDesc());
			if (candidate == null || !asksCanSwim(candidate) && !answers(candidate, answer)) continue;
			if (lambda != null) return declined(node, name, "it passes more than one fluid predicate that asks whether a body can swim");
			lambda = candidate;
		}
		if (lambda == null) return declined(node, name, "it passes no lambda of its own that asks canSwimInFluidType");
		if (answers(lambda, answer)) return 0;
		MethodInsnNode ask = null;
		for (AbstractInsnNode insn : lambda.instructions) {
			if (insn instanceof MethodInsnNode call && isCanSwim(call)) {
				if (ask != null) return declined(node, name, "its predicate asks canSwimInFluidType more than once");
				ask = call;
			}
		}
		lambda.instructions.set(ask, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, answer, RULE_DESC, false));
		return 1;
	}

	private static boolean isCanSwim(MethodInsnNode call) {
		return (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE)
				&& call.name.equals(CAN_SWIM) && call.desc.equals(CAN_SWIM_DESC);
	}

	private static boolean asksCanSwim(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && isCanSwim(call)) return true;
		return false;
	}

	private static boolean answers(MethodNode method, String answer) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && call.name.equals(answer)) return true;
		}
		return false;
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static int declined(ClassNode node, String method, String reason) {
		ForbricLog.warn("[Forbric/Fluid] left %s.%s as merged: %s — a MinecraftForge fluid follows NeoForge's rule there",
				node.name.replace('/', '.'), method, reason);
		return -1;
	}
}
