/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * What a handler's body computes, read by its data flow: where each returned value and each call operand comes from (past
 * local stores and loads, stack copies and casts), which calls it makes, and whether it writes anything. An adapter that
 * proves a handler's contract this way accepts the same handler compiled any other way — through locals, with a cast, with
 * its returns split — and refuses one that also does anything the contract does not allow; an adapter that compares
 * instruction counts accepts one spelling only.
 */
final class HandlerFlow {
	final MethodNode method;
	final Frame<SourceValue>[] frames;

	private HandlerFlow(MethodNode method, Frame<SourceValue>[] frames) {
		this.method = method;
		this.frames = frames;
	}

	/** The flow of {@code method} in {@code owner}; null when the body cannot be analysed. */
	static HandlerFlow of(String owner, MethodNode method) {
		if (method == null || method.instructions == null || method.instructions.size() == 0) return null;
		try {
			return new HandlerFlow(method, new Analyzer<>(new SourceInterpreter(Opcodes.ASM9) {
				@Override public SourceValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
					return new SourceValue(type.getSize(), new Parameter(local));
				}
			}).analyze(owner, method));
		} catch (AnalyzerException | RuntimeException unreadable) {
			return null;
		}
	}

	/** A parameter's value (or {@code this}), standing for itself in the flow: {@link #slot} is its local slot. */
	static final class Parameter extends InsnNode {
		final int slot;

		Parameter(int slot) {
			super(Opcodes.NOP);
			this.slot = slot;
		}
	}

	/** Whether {@code origin} is the value of the parameter in {@code slot}. */
	static boolean isParameter(AbstractInsnNode origin, int slot) {
		return origin instanceof Parameter parameter && parameter.slot == slot;
	}

	/**
	 * The calls it makes are all ones {@code allowed} accepts, and it writes no field, array element or monitor, throws
	 * nothing, links no lambda and catches nothing.
	 */
	boolean onlyCalls(Predicate<MethodInsnNode> allowed) {
		if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return false;
		for (AbstractInsnNode instruction : method.instructions) {
			int opcode = instruction.getOpcode();
			if (instruction instanceof MethodInsnNode call) {
				if (!allowed.test(call)) return false;
			} else if (instruction instanceof InvokeDynamicInsnNode) {
				return false;
			} else if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE
					&& opcode != Opcodes.AASTORE || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
					|| opcode == Opcodes.ATHROW) {
				return false;
			}
		}
		return true;
	}

	/** Every call instruction of the body that {@code which} accepts. */
	List<MethodInsnNode> calls(Predicate<MethodInsnNode> which) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && which.test(call)) out.add(call);
		return out;
	}

	/** The origins of every value the body returns; empty when it returns nothing or a return is unreachable. */
	Set<AbstractInsnNode> returned() {
		Set<AbstractInsnNode> out = identitySet();
		for (int i = 0; i < method.instructions.size(); i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			int opcode = instruction.getOpcode();
			if (opcode < Opcodes.IRETURN || opcode > Opcodes.ARETURN) continue;
			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;
			out.addAll(origins(frame.getStack(frame.getStackSize() - 1)));
		}
		return out;
	}

	/** Whether the body has a reachable value return at all. */
	boolean returnsAValue() {
		for (int i = 0; i < method.instructions.size(); i++) {
			int opcode = method.instructions.get(i).getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN && frames[i] != null) return true;
		}
		return false;
	}

	/** The origins of operand {@code k} of {@code instruction} (a call's receiver is operand 0 of an instance call). */
	Set<AbstractInsnNode> operand(AbstractInsnNode instruction, int k, int operands) {
		int index = method.instructions.indexOf(instruction);
		Frame<SourceValue> frame = index < 0 ? null : frames[index];
		if (frame == null || frame.getStackSize() < operands) return identitySet();
		return origins(frame.getStack(frame.getStackSize() - operands + k));
	}

	/** The origins of operand {@code k} of {@code call}, counting an instance call's receiver as operand 0. */
	Set<AbstractInsnNode> argument(MethodInsnNode call, int k) {
		return operand(call, k, Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1));
	}

	/** Whether any instruction loads the local in {@code slot} (a parameter is read). */
	boolean reads(int slot) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ILOAD
					&& variable.getOpcode() <= Opcodes.ALOAD) return true;
			if (instruction instanceof org.objectweb.asm.tree.IincInsnNode increment && increment.var == slot) return true;
		}
		return false;
	}

	/** The instructions that made {@code value}, past local stores and loads, stack copies and casts. */
	Set<AbstractInsnNode> origins(SourceValue value) {
		Set<AbstractInsnNode> out = identitySet(), seen = identitySet();
		ArrayDeque<AbstractInsnNode> pending = new ArrayDeque<>(value.insns);
		while (!pending.isEmpty()) {
			AbstractInsnNode producer = pending.pop();
			if (!seen.add(producer)) continue;
			if (producer instanceof Parameter) {
				out.add(producer);
				continue;
			}
			int opcode = producer.getOpcode(), index = method.instructions.indexOf(producer);
			Frame<SourceValue> frame = index < 0 ? null : frames[index];
			boolean load = producer instanceof VarInsnNode && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD;
			boolean copy = opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE || opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST;
			if (frame == null || !load && !copy) {
				out.add(producer);
				continue;
			}
			if (load) pending.addAll(frame.getLocal(((VarInsnNode) producer).var).insns);
			else pending.addAll(frame.getStack(frame.getStackSize() - 1).insns);
		}
		return out;
	}

	private static Set<AbstractInsnNode> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}
}
