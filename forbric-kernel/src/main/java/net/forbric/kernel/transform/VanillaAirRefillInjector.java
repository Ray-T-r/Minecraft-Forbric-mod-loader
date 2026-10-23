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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.ForbricSwitches;

/**
 * A body that breathes where its eyes are refills its air as in vanilla, not as the carrier's breathing hook would hold it.
 *
 * <p>The merged {@code LivingEntity.baseTick} is NeoForge's: it hands the air supply to {@code CommonHooks.onLivingBreathe},
 * which decides it its own way. Where the eye is in a fluid that is not air and the body does not drown there — water
 * breathing, conduit power, a drowned, an invulnerable player, lava, a mod's fluid it may breathe in — the hook marks the body
 * as breathing and sets the refill it hands its breathe event to zero, so the air stays where it was. Vanilla refills it by 4 a
 * tick up to the maximum, holding it only in its water branch for a body that Breath of the Nautilus alone keeps from drowning
 * — the eye in a fluid the body can drown in, which is how fabric-api makes a Fabric drowning fluid count as water — and
 * refilling it in lava or a fluid the body breathes in. (MinecraftForge's own hook refills like vanilla but holds that body's
 * air in any fluid that is not air.) The one store of zero into the refill amount in the hook's protected branch becomes the
 * refill vanilla would give ({@code KernelAirRefill.protectedRefill}).
 *
 * <p>Vanilla also changes the air only while it is below the maximum; the hook's air update for a body that breathes,
 * {@code setAirSupply(min(air + refill, max))}, pulls air above the maximum back to it every tick (a negative refill there, or
 * zero, still clamps). Its {@code Math.min} becomes {@code KernelAirRefill.refilledAir}, handed the refill the event started
 * from (read from the event right after it is constructed, before it is posted) and the one it ends with: air at or above
 * the maximum stays where it is unless a listener set the refill, which NeoForge applies as it always does.
 *
 * <p>Everything else stays the hook's: the event is posted with the default refill, and a NeoForge listener still sets the
 * amounts and decides whether the body breathes, as on NeoForge but for air above the maximum: there a listener that leaves
 * the refill as it started (only letting a drowning body breathe, or setting the refill to zero) leaves the air where it is,
 * where NeoForge pulls it to the maximum. The seams are found by shape: the hook's
 * {@code (LivingEntity, ServerLevel, int, int)} signature, the one breathe event it constructs from {@code (entity, canBreathe,
 * consume, refill)} and keeps in a local, the one store into the event's refill local — a constant zero, right after the store
 * of true into its can-breathe local — and the one {@code Math.min(air + event refill, max)} handed to {@code setAirSupply},
 * reading that event on every path (followed through the hook's values, so the kernel's own delivery bookkeeping around the
 * construction does not matter).
 * Anything else declines with a warning and leaves the hook as it is. {@code -Dforbric.vanillaAirRefill=off} (formerly
 * {@code forbric.foreignFluidBreathing}) keeps NeoForge's rule.
 */
public final class VanillaAirRefillInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.vanillaAirRefill";
	public static final String RUNTIME = "net/forbric/kernel/runtime/KernelAirRefill";
	/** The hook's protected branch, which asks the water question itself. */
	public static final String PROTECTED = "protectedRefill";
	/** The same rule where vanilla's water branch is already known to be taken (the cross-host predicate island). */
	public static final String WATER = "waterRefill";
	/** The air update of a body that breathes: NeoForge's clamp, but air at or above the maximum left alone when no listener set the refill. */
	public static final String UPDATE = "refilledAir";
	public static final String UPDATE_DESC = "(IIII)I";
	private static final String TARGET = "net.neoforged.neoforge.common.CommonHooks";
	private static final String HOOK = "onLivingBreathe";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String HOOK_DESC = "(L" + LIVING + ";Lnet/minecraft/server/level/ServerLevel;II)V";
	private static final String EVENT = "net/neoforged/neoforge/event/entity/living/LivingBreatheEvent";
	private static final String EVENT_DESC = "(L" + LIVING + ";ZII)V";
	public static final String REFILL_DESC = "(L" + LIVING + ";I)I";
	private static final String MATH = "java/lang/Math";

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
		ForbricLog.info("[Forbric/Fluid] a body that breathes in its fluid refills its air as in vanilla, and air above the maximum stays "
				+ "there; NeoForge's breathe event starts from that refill and still decides");
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
		// The event is kept in a local, and the air update of a body that breathes reads its refill from there:
		// setAirSupply(Math.min(getAirSupply() + event.getRefillAirAmount(), getMaxAirSupply())). Which local, and that the update
		// reads the event on every path, is followed through the hook's values (copies kept as the value they copy), so the
		// kernel's own bookkeeping of what the hook constructs (NativeDualEventInjector) may sit between the two.
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new Copies()).analyze(node.name, hook);
		} catch (AnalyzerException unreadable) {
			Caught.warn(unreadable, "[Forbric/Fluid] could not follow the breathing hook's values; it keeps its own refill rule");
			return false;
		}
		Frame<SourceValue> atConstruction = frames[hook.instructions.indexOf(events.getFirst())];
		SourceValue constructed = atConstruction == null ? null : atConstruction.getStack(atConstruction.getStackSize() - 5);
		if (constructed == null || constructed.insns.size() != 1
				|| !(constructed.insns.iterator().next() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW && allocation.desc.equals(EVENT)))
			return declined("the breathe event's construction is not one allocation");
		List<VarInsnNode> keeps = new ArrayList<>();
		for (AbstractInsnNode insn : hook.instructions) {
			Frame<SourceValue> before = frames[hook.instructions.indexOf(insn)];
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && before != null
					&& before.getStack(before.getStackSize() - 1).insns.contains(allocation)) keeps.add(store);
		}
		if (keeps.size() != 1) return declined("the breathe event is kept " + keeps.size() + " times, not in one local");
		VarInsnNode event = keeps.getFirst();
		List<MethodInsnNode> clamps = new ArrayList<>();
		for (AbstractInsnNode insn : hook.instructions)
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(MATH) && call.name.equals("min")
					&& call.desc.equals("(II)I")) clamps.add(call);
		if (clamps.size() != 1) return declined("the hook clamps " + clamps.size() + " times, not once");
		MethodInsnNode clamp = clamps.getFirst();
		AbstractInsnNode max = previous(clamp), self = previous(max), sum = previous(self), amount = previous(sum), read = previous(amount),
				air = previous(read), receiver = previous(air), sink = next(clamp);
		if (!living(max, "getMaxAirSupply", "()I") || !self(self) || sum == null || sum.getOpcode() != Opcodes.IADD
				|| !(amount instanceof MethodInsnNode getter && getter.getOpcode() == Opcodes.INVOKEVIRTUAL && getter.owner.equals(EVENT)
						&& getter.name.equals("getRefillAirAmount") && getter.desc.equals("()I"))
				|| !(read instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == event.var)
				|| !living(air, "getAirSupply", "()I") || !self(receiver) || !living(sink, "setAirSupply", "(I)V") || hasEntry(hook, receiver, sink))
			return declined("the air update of a body that breathes is not setAirSupply(min(air + the event's refill, max))");
		// The event read there is the one constructed, on every path to it (the hook uses the same local for other values
		// before the event).
		Frame<SourceValue> atRead = frames[hook.instructions.indexOf(read)];
		if (atRead == null || !atRead.getLocal(event.var).insns.equals(java.util.Set.of(allocation)))
			return declined("the air update does not read the breathe event the hook posted");
		InsnList vanilla = new InsnList();
		vanilla.add(new VarInsnNode(Opcodes.ALOAD, 0));
		vanilla.add(new VarInsnNode(Opcodes.ILOAD, refill.var));
		vanilla.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, PROTECTED, REFILL_DESC, false));
		hook.instructions.insertBefore(zero, vanilla);
		hook.instructions.remove(zero);
		// The refill the event starts from, as the event itself holds it before any listener runs (NeoForge's event clamps a
		// negative amount to zero), kept in a fresh local; the update compares the one the listeners leave with it.
		int started = hook.maxLocals;
		InsnList capture = new InsnList();
		capture.add(new VarInsnNode(Opcodes.ALOAD, event.var));
		capture.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getRefillAirAmount", "()I", false));
		capture.add(new VarInsnNode(Opcodes.ISTORE, started));
		hook.instructions.insert(event, capture);
		hook.maxLocals = started + 1;
		InsnList update = new InsnList();
		update.add(new VarInsnNode(Opcodes.ILOAD, started));
		update.add(new VarInsnNode(Opcodes.ALOAD, event.var));
		update.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getRefillAirAmount", "()I", false));
		update.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, UPDATE, UPDATE_DESC, false));
		hook.instructions.insertBefore(clamp, update);
		hook.instructions.remove(clamp);
		return true;
	}

	private static boolean living(AbstractInsnNode insn, String name, String desc) {
		return insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(LIVING) && call.name.equals(name)
				&& call.desc.equals(desc);
	}

	private static boolean self(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
	}

	/** The hook's values, a copy (a load, a store, a dup) kept as the value it copies, so a local's source is what was stored. */
	private static final class Copies extends SourceInterpreter {
		Copies() {
			super(Opcodes.ASM9);
		}

		@Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
			return value;
		}
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		for (AbstractInsnNode i = insn == null ? null : insn.getNext(); i != null; i = i.getNext()) if (i.getOpcode() >= 0) return i;
		return null;
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
