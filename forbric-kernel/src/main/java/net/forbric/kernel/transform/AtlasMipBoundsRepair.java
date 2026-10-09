/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.util.ForbricLog;

/** Preserves the original configuration decision, then bounds the selected level by its proved image limit. */
final class AtlasMipBoundsRepair {
	private static final String LIMITS = "net/forbric/api/MipLevelLimits", STITCHER = "net/minecraft/client/renderer/texture/Stitcher";
	/** Methods that reached the data-flow analysis: only the ones that construct a Stitcher at all. */
	private static final java.util.concurrent.atomic.LongAdder ANALYZED = new java.util.concurrent.atomic.LongAdder();
	private AtlasMipBoundsRepair() { }
	static long methodsAnalyzed() { return ANALYZED.sum(); }
	static boolean apply(ClassNode node) {
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			// allocationLevels only ever reports the level of a Stitcher.<init>(IIII)V call, so a method without one
			// cannot be repaired: skip it in the linear scan that runs anyway, before the per-instruction analysis.
			boolean already = false, allocates = false;
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
				if (call.owner.equals(LIMITS)) already = true;
				else if (allocation(call)) allocates = true;
			}
			if (already || !allocates) continue;
			Set<Integer> allocated = allocationLevels(node, method); if (allocated.size() != 1) continue;
			int selected = allocated.iterator().next();
			List<Plan> plans = new ArrayList<>();
			for (var instruction : method.instructions) if (instruction instanceof JumpInsnNode comparison && comparison.getOpcode() == Opcodes.IF_ICMPGE) {
				Plan plan = plan(method, comparison, selected); if (plan != null) plans.add(plan);
			}
			if (plans.size() != 1) continue;
			Plan plan = plans.getFirst();
			int writes = 0; boolean dominates = true;
			for (var instruction : method.instructions) {
				if (instruction instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE && store.var == selected
						&& method.instructions.indexOf(instruction) > method.instructions.indexOf(plan.start)) writes++;
				if (instruction instanceof MethodInsnNode call && call.owner.equals(STITCHER) && call.name.equals("<init>") && call.desc.equals("(IIII)V"))
					dominates &= dominates(method, plan.join, call);
			}
			if (writes != 2 || !dominates) continue;
			InsnList bound = new InsnList();
			bound.add(new VarInsnNode(Opcodes.ILOAD, selected)); bound.add(new VarInsnNode(Opcodes.ILOAD, plan.maximum));
			bound.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIMITS, "bounded", "(II)I", false));
			bound.add(new VarInsnNode(Opcodes.ISTORE, selected));
			method.instructions.insertBefore(next(plan.join), bound); method.maxStack = Math.max(method.maxStack, 2); changed = true;
			// One line per bounded allocation. A proof that rejects a method is otherwise invisible, and the class
			// that must be bounded carries a REQUIRED anchor for that case; this is the positive evidence.
			ForbricLog.info("[Forbric/MergedBaseCompat] %s.%s bounds the mip level it allocates by the image-size limit "
					+ "it computed — a policy that refuses to lower the level can no longer allocate levels its smallest "
					+ "image cannot hold", node.name.replace('/', '.'), method.name);
		}
		return changed;
	}
	private record Plan(JumpInsnNode start, LabelNode join, int maximum) { }
	private static Plan plan(MethodNode method, JumpInsnNode comparison, int selected) {
		if (!(previous(comparison) instanceof VarInsnNode request) || request.getOpcode() != Opcodes.ILOAD
				|| !(previous(request) instanceof VarInsnNode limit) || limit.getOpcode() != Opcodes.ILOAD || limit.var == request.var) return null;
		// The bound is a logarithmic image-size limit, rather than an unrelated configuration integer.
		boolean derived = false;
		for (var instruction : method.instructions) if (instruction instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE && store.var == limit.var) {
			if (derived || !(previous(store) instanceof MethodInsnNode log) || log.getOpcode() != Opcodes.INVOKESTATIC
					|| !log.owner.equals("net/minecraft/util/Mth") || !log.name.equals("log2") || !log.desc.equals("(I)I")) return null;
			derived = true;
		}
		if (!derived) return null;
		AbstractInsnNode skipped = next(comparison.label);
		if (!(skipped instanceof VarInsnNode old) || old.getOpcode() != Opcodes.ILOAD || old.var != request.var
				|| !(next(old) instanceof VarInsnNode chosen) || chosen.getOpcode() != Opcodes.ISTORE || chosen.var != selected) return null;
		AbstractInsnNode cursor = chosen.getNext(); LabelNode join = null;
		while (cursor != null && cursor.getOpcode() < 0) { if (cursor instanceof LabelNode label) { join = label; break; } cursor = cursor.getNext(); }
		if (join == null) return null;
		AbstractInsnNode jump = previous(comparison.label);
		if (!(jump instanceof JumpInsnNode toJoin) || toJoin.getOpcode() != Opcodes.GOTO || toJoin.label != join
				|| !(previous(toJoin) instanceof VarInsnNode bounded) || bounded.getOpcode() != Opcodes.ISTORE || bounded.var != selected
				|| !(previous(bounded) instanceof VarInsnNode max) || max.getOpcode() != Opcodes.ILOAD || max.var != limit.var) return null;
		int decision = 0;
		for (cursor = comparison.getNext(); cursor != null && cursor != comparison.label; cursor = cursor.getNext()) {
			if (cursor instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.IFEQ && branch.label == comparison.label) decision++;
			if (cursor instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE
					&& (store.var == limit.var || store.var == request.var)) return null;
		}
		return decision == 1 ? new Plan(comparison, join, limit.var) : null;
	}
	private static boolean allocation(MethodInsnNode call) {
		return call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(STITCHER) && call.name.equals("<init>") && call.desc.equals("(IIII)V");
	}
	private static Set<Integer> allocationLevels(ClassNode owner, MethodNode method) {
		Set<Integer> levels = new HashSet<>();
		try {
			ANALYZED.increment();
			Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
			int index = 0;
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && allocation(call)) {
					Frame<SourceValue> frame = frames[index]; if (frame == null) return Set.of();
					SourceValue value = frame.getStack(frame.getStackSize() - 2);
					if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD) return Set.of();
					levels.add(load.var);
				}
				index++;
			}
		} catch (AnalyzerException invalid) { return Set.of(); }
		return levels;
	}
	private static AbstractInsnNode previous(AbstractInsnNode node) { do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node; }
	private static AbstractInsnNode next(AbstractInsnNode node) { do { node = node.getNext(); } while (node != null && node.getOpcode() < 0); return node; }
	/** No normal or exceptional entry can reach allocation without first crossing the chosen-value join. */
	private static boolean dominates(MethodNode method, LabelNode join, AbstractInsnNode allocation) {
		AbstractInsnNode[] code = method.instructions.toArray();
		int bound = method.instructions.indexOf(join), target = method.instructions.indexOf(allocation);
		Set<Integer> seen = new HashSet<>(); ArrayDeque<Integer> work = new ArrayDeque<>(); work.add(0);
		while (!work.isEmpty()) {
			int index = work.remove(); if (index < 0 || index >= code.length || index == bound || !seen.add(index)) continue;
			if (index == target) return false;
			AbstractInsnNode instruction = code[index]; int opcode = instruction.getOpcode();
			for (var handler : method.tryCatchBlocks) if (index >= method.instructions.indexOf(handler.start) && index < method.instructions.indexOf(handler.end)) work.add(method.instructions.indexOf(handler.handler));
			if (instruction instanceof JumpInsnNode jump) {
				if (opcode == Opcodes.JSR) return false;
				work.add(method.instructions.indexOf(jump.label)); if (opcode != Opcodes.GOTO) work.add(index + 1);
			} else if (instruction instanceof LookupSwitchInsnNode lookup) {
				work.add(method.instructions.indexOf(lookup.dflt)); for (var label : lookup.labels) work.add(method.instructions.indexOf(label));
			} else if (instruction instanceof TableSwitchInsnNode table) {
				work.add(method.instructions.indexOf(table.dflt)); for (var label : table.labels) work.add(method.instructions.indexOf(label));
			} else if (opcode != Opcodes.ATHROW && (opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN)) work.add(index + 1);
		}
		return true;
	}
}
