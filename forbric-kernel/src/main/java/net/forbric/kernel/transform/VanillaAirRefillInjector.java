/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.ForbricSwitches;

/**
 * A body that breathes where its eyes are refills its air as in vanilla, not as the carrier's breathing hook would hold it.
 *
 * <p>The merged {@code LivingEntity.baseTick} is NeoForge's: it hands the air supply to {@code CommonHooks.onLivingBreathe},
 * which decides it its own way. Where the eye is in a fluid that is not air and the body does not drown there — water
 * breathing, conduit power, a drowned, an invulnerable player, lava, a mod's fluid it may breathe in — the hook marks the body
 * as breathing and sets the refill it hands its breathe event to zero, so the air stays where it was. Vanilla (and
 * MinecraftForge, whose own hook keeps vanilla's rule) refills it by 4 a tick up to the maximum, holding it only in water for a
 * body that Breath of the Nautilus alone keeps from drowning. The one store of zero into the refill amount in the hook's
 * protected branch becomes the refill vanilla would give ({@code KernelAirRefill.protectedRefill}); everything else stays the
 * hook's: the event is posted with that default, and a NeoForge listener still sets the amounts and decides whether the body
 * breathes, exactly as on NeoForge.
 *
 * <p>The seam is found by shape, not by name: the hook's {@code (LivingEntity, ServerLevel, int, int)} signature, the one
 * breathe event it constructs from {@code (entity, canBreathe, consume, refill)}, and the one store into the event's refill
 * local — a constant zero, right after the store of true into its can-breathe local. Anything else declines with a warning and
 * leaves the hook as it is. {@code -Dforbric.vanillaAirRefill=off} (formerly {@code forbric.foreignFluidBreathing}) keeps
 * NeoForge's rule.
 */
public final class VanillaAirRefillInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.vanillaAirRefill";
	public static final String RUNTIME = "net/forbric/kernel/runtime/KernelAirRefill";
	/** The hook's protected branch, which asks the water question itself. */
	public static final String PROTECTED = "protectedRefill";
	/** The same rule where vanilla's water branch is already known to be taken (the cross-host predicate island). */
	public static final String WATER = "waterRefill";
	private static final String TARGET = "net.neoforged.neoforge.common.CommonHooks";
	private static final String HOOK = "onLivingBreathe";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String HOOK_DESC = "(L" + LIVING + ";Lnet/minecraft/server/level/ServerLevel;II)V";
	private static final String EVENT = "net/neoforged/neoforge/event/entity/living/LivingBreatheEvent";
	private static final String EVENT_DESC = "(L" + LIVING + ";ZII)V";
	public static final String REFILL_DESC = "(L" + LIVING + ";I)I";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(ForbricSwitches.get(PROPERTY, "on"));
	}

	@Override public String name() {
		return "forbric-vanilla-air-refill";
	}

	@Override public AnchorSet anchors() {
		return enabled() ? AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a body that breathes in its fluid refills its air as in vanilla instead of holding it"))
				: AnchorSet.scanned("vanilla air refill switched off (-D" + PROPERTY + "=off)");
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(name) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] a body that breathes in its fluid refills its air as in vanilla; NeoForge's breathe event starts "
				+ "from that refill and still decides");
		return writer.toByteArray();
	}

	static boolean repair(ClassNode node) {
		MethodNode hook = null;
		for (MethodNode method : node.methods)
			if (method.name.equals(HOOK) && method.desc.equals(HOOK_DESC) && (method.access & Opcodes.ACC_STATIC) != 0) hook = method;
		if (hook == null) return declined("the breathing hook's (LivingEntity, ServerLevel, int, int) signature is absent");
		for (AbstractInsnNode insn : hook.instructions)
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return false;
		// The event the hook decides with: (entity, canBreathe, consume, refill), its last two operands read from locals.
		List<MethodInsnNode> events = new ArrayList<>();
		for (AbstractInsnNode insn : hook.instructions)
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(EVENT) && call.name.equals("<init>")
					&& call.desc.equals(EVENT_DESC)) events.add(call);
		if (events.size() != 1) return declined("the hook does not construct exactly one breathe event from (entity, canBreathe, consume, refill)");
		AbstractInsnNode refillRead = previous(events.getFirst()), consumeRead = previous(refillRead), breatheRead = previous(consumeRead);
		if (!(refillRead instanceof VarInsnNode refill && refill.getOpcode() == Opcodes.ILOAD)
				|| !(consumeRead instanceof VarInsnNode && consumeRead.getOpcode() == Opcodes.ILOAD)
				|| !(breatheRead instanceof VarInsnNode breathe && breathe.getOpcode() == Opcodes.ILOAD))
			return declined("the breathe event's can-breathe and refill operands are not plain local reads");
		// The protected branch: true into can-breathe, then the one store into the refill local — a constant zero.
		List<VarInsnNode> stores = new ArrayList<>();
		for (AbstractInsnNode insn : hook.instructions)
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE && store.var == refill.var) stores.add(store);
		if (stores.size() != 1) return declined("the refill amount is stored " + stores.size() + " times, not once");
		VarInsnNode seam = stores.getFirst();
		AbstractInsnNode zero = previous(seam), marked = previous(zero), yes = previous(marked);
		if (zero == null || zero.getOpcode() != Opcodes.ICONST_0 || !(marked instanceof VarInsnNode mark && mark.getOpcode() == Opcodes.ISTORE
				&& mark.var == breathe.var) || yes == null || yes.getOpcode() != Opcodes.ICONST_1 || hasEntry(hook, yes, seam))
			return declined("the refill amount is not zeroed right where the hook decides the body breathes");
		InsnList vanilla = new InsnList();
		vanilla.add(new VarInsnNode(Opcodes.ALOAD, 0));
		vanilla.add(new VarInsnNode(Opcodes.ILOAD, refill.var));
		vanilla.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, PROTECTED, REFILL_DESC, false));
		hook.instructions.insertBefore(zero, vanilla);
		hook.instructions.remove(zero);
		return true;
	}

	/** Whether a label between {@code from} and {@code to} is entered from elsewhere: the true, the zero and their stores are one straight run. */
	private static boolean hasEntry(MethodNode method, AbstractInsnNode from, AbstractInsnNode to) {
		for (AbstractInsnNode i = from.getNext(); i != null && i != to; i = i.getNext()) {
			if (!(i instanceof LabelNode label)) continue;
			for (AbstractInsnNode insn : method.instructions)
				if (insn instanceof JumpInsnNode jump && jump.label == label
						|| insn instanceof TableSwitchInsnNode table && (table.dflt == label || table.labels.contains(label))
						|| insn instanceof LookupSwitchInsnNode lookup && (lookup.dflt == label || lookup.labels.contains(label))) return true;
			for (TryCatchBlockNode handler : method.tryCatchBlocks) if (handler.handler == label) return true;
		}
		return false;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		for (AbstractInsnNode i = insn == null ? null : insn.getPrevious(); i != null; i = i.getPrevious()) if (i.getOpcode() >= 0) return i;
		return null;
	}

	private static boolean declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] the breathing hook keeps its own refill rule (a body that breathes in its fluid holds its air): %s", reason);
		return false;
	}
}
