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
public final class HandlerFlow {
	public final MethodNode method;
	final Frame<SourceValue>[] frames;

	private HandlerFlow(MethodNode method, Frame<SourceValue>[] frames) {
		this.method = method;
		this.frames = frames;
	}

	/** The flow of {@code method} in {@code owner}; null when the body cannot be analysed. */
	public static HandlerFlow of(String owner, MethodNode method) {
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
	public static final class Parameter extends InsnNode {
		public final int slot;

		Parameter(int slot) {
			super(Opcodes.NOP);
			this.slot = slot;
		}
	}

	/** Whether {@code origin} is the value of the parameter in {@code slot}. */
	public static boolean isParameter(AbstractInsnNode origin, int slot) {
		return origin instanceof Parameter parameter && parameter.slot == slot;
	}

	/**
	 * The calls it makes are all ones {@code allowed} accepts, and it writes no field, array element or monitor, throws
	 * nothing, links no lambda and catches nothing.
	 */
	public boolean onlyCalls(Predicate<MethodInsnNode> allowed) {
		return onlyCalls(allowed, dynamic -> false);
	}

	/** {@link #onlyCalls(Predicate)}, linking only the lambdas {@code dynamic} accepts. */
	public boolean onlyCalls(Predicate<MethodInsnNode> allowed, Predicate<InvokeDynamicInsnNode> dynamic) {
		if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return false;
		for (AbstractInsnNode instruction : method.instructions) {
			int opcode = instruction.getOpcode();
			if (instruction instanceof MethodInsnNode call) {
				if (!allowed.test(call)) return false;
			} else if (instruction instanceof InvokeDynamicInsnNode link) {
				if (!dynamic.test(link)) return false;
			} else if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE
					&& opcode != Opcodes.AASTORE || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
					|| opcode == Opcodes.ATHROW) {
				return false;
			}
		}
		return true;
	}

	/** Every call instruction of the body that {@code which} accepts. */
	public List<MethodInsnNode> calls(Predicate<MethodInsnNode> which) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && which.test(call)) out.add(call);
		return out;
	}

	/** The origins of every value the body returns; empty when it returns nothing or a return is unreachable. */
	public Set<AbstractInsnNode> returned() {
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
	public boolean returnsAValue() {
		for (int i = 0; i < method.instructions.size(); i++) {
			int opcode = method.instructions.get(i).getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN && frames[i] != null) return true;
		}
		return false;
	}

	/** The origins of operand {@code k} of {@code instruction} (a call's receiver is operand 0 of an instance call). */
	public Set<AbstractInsnNode> operand(AbstractInsnNode instruction, int k, int operands) {
		int index = method.instructions.indexOf(instruction);
		Frame<SourceValue> frame = index < 0 ? null : frames[index];
		if (frame == null || frame.getStackSize() < operands) return identitySet();
		return origins(frame.getStack(frame.getStackSize() - operands + k));
	}

	/** The origins of operand {@code k} of {@code call}, counting an instance call's receiver as operand 0. */
	public Set<AbstractInsnNode> argument(MethodInsnNode call, int k) {
		return operand(call, k, Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1));
	}

	/** Whether any instruction loads the local in {@code slot} (a parameter is read). */
	public boolean reads(int slot) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ILOAD
					&& variable.getOpcode() <= Opcodes.ALOAD) return true;
			if (instruction instanceof org.objectweb.asm.tree.IincInsnNode increment && increment.var == slot) return true;
		}
		return false;
	}

	/** The instructions that made {@code value}, past local stores and loads, stack copies and casts. */
	public Set<AbstractInsnNode> origins(SourceValue value) {
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

	/** The single instruction in {@code origins}; null when there are none or several. */
	public static AbstractInsnNode only(Set<AbstractInsnNode> origins) {
		return origins.size() == 1 ? origins.iterator().next() : null;
	}

	/** Whether {@code origins} is exactly the parameter in {@code slot}. */
	public static boolean onlyParameter(Set<AbstractInsnNode> origins, int slot) {
		return isParameter(only(origins), slot);
	}

	/** The one instruction that pushed operand {@code k} of {@code instruction} (not looking past copies); null for several. */
	public AbstractInsnNode producer(AbstractInsnNode instruction, int k, int operands) {
		int index = method.instructions.indexOf(instruction);
		Frame<SourceValue> frame = index < 0 ? null : frames[index];
		if (frame == null || frame.getStackSize() < operands) return null;
		Set<AbstractInsnNode> direct = frame.getStack(frame.getStackSize() - operands + k).insns;
		return direct.size() == 1 ? direct.iterator().next() : null;
	}

	/** The int an instruction pushes as a constant; null when it pushes anything else. */
	public static Integer intConstant(AbstractInsnNode instruction) {
		if (instruction == null) return null;
		int opcode = instruction.getOpcode();
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (instruction instanceof org.objectweb.asm.tree.IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
		if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode constant && constant.cst instanceof Integer value) return value;
		return null;
	}

	/** One operand of one instruction: {@code operand} counts from the deepest of the values it consumes. */
	public record Use(AbstractInsnNode instruction, int operand) { }

	/**
	 * Every instruction that consumes a value {@code value} made, past the copies {@link #origins} looks through (local
	 * stores and loads, stack copies, casts), and which of its operands it is.
	 */
	public List<Use> uses(AbstractInsnNode value) {
		List<Use> out = new ArrayList<>();
		for (int i = 0; i < method.instructions.size(); i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			Frame<SourceValue> frame = frames[i];
			int operands = consumed(instruction);
			if (frame == null || operands == 0 || frame.getStackSize() < operands) continue;
			for (int k = 0; k < operands; k++) {
				if (origins(frame.getStack(frame.getStackSize() - operands + k)).contains(value)) out.add(new Use(instruction, k));
			}
		}
		return out;
	}

	/**
	 * How many times the value {@code value} pushed is taken off the stack as it was pushed — by any instruction, copies
	 * (stores, stack shuffles, casts) included. One means the instruction it is pushed for is its only reader.
	 */
	public int directUses(AbstractInsnNode value) {
		int count = 0;
		for (int i = 0; i < method.instructions.size(); i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			Frame<SourceValue> frame = frames[i];
			int taken = consumed(instruction) + switch (instruction.getOpcode()) {
				case Opcodes.DUP, Opcodes.CHECKCAST, Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> 1;
				case Opcodes.DUP_X1, Opcodes.DUP2, Opcodes.SWAP, Opcodes.POP2 -> 2;
				case Opcodes.DUP_X2, Opcodes.DUP2_X1 -> 3;
				case Opcodes.DUP2_X2 -> 4;
				default -> 0;
			};
			if (frame == null || taken == 0) continue;
			for (int k = Math.max(0, frame.getStackSize() - taken); k < frame.getStackSize(); k++) if (frame.getStack(k).insns.contains(value)) count++;
		}
		return count;
	}

	/** How many stack values an instruction consumes for good: copies (stores, stack shuffles, casts) consume none. */
	private static int consumed(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		if (instruction instanceof MethodInsnNode call) return Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
		if (instruction instanceof InvokeDynamicInsnNode dynamic) return Type.getArgumentTypes(dynamic.desc).length;
		if (instruction instanceof org.objectweb.asm.tree.MultiANewArrayInsnNode array) return array.dims;
		return switch (opcode) {
			case Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN,
					Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
					Opcodes.IFGT, Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
					Opcodes.INSTANCEOF, Opcodes.ARRAYLENGTH, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.POP -> 1;
			case Opcodes.PUTFIELD, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
					Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
					Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> 2;
			case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE,
					Opcodes.SASTORE -> 3;
			default -> 0;
		};
	}

	/**
	 * Whether {@code call} hands its receiver-operand the parameter in {@code receiver}, and as its array operand a fresh
	 * {@code Object[]} of exactly {@code slots.length} elements, element {@code i} the parameter in {@code slots[i]}, filled
	 * in one straight run between the array's creation and the call, and handed to nothing else.
	 */
	public boolean passesParameters(MethodInsnNode call, int receiver, int[] slots) {
		List<Predicate<Set<AbstractInsnNode>>> elements = new ArrayList<>();
		for (int slot : slots) elements.add(origins -> onlyParameter(origins, slot));
		return passesArray(call, receiver, elements);
	}

	/**
	 * {@link #passesParameters}, element {@code i} being any value {@code elements.get(i)} accepts the origins of: the
	 * operation of a {@code WrapOperation} called with something else than its own parameters in some place.
	 */
	public boolean passesArray(MethodInsnNode call, int receiver, List<Predicate<Set<AbstractInsnNode>>> elements) {
		if (!onlyParameter(argument(call, 0), receiver)) return false;
		AbstractInsnNode created = only(argument(call, 1));
		if (!(created instanceof org.objectweb.asm.tree.TypeInsnNode array && array.getOpcode() == Opcodes.ANEWARRAY
				&& array.desc.equals("java/lang/Object"))) return false;
		Integer length = intConstant(only(operand(created, 0, 1)));
		int from = method.instructions.indexOf(created), to = method.instructions.indexOf(call);
		if (length == null || length != elements.size() || !straight(from, to)) return false;
		boolean[] filled = new boolean[elements.size()];
		for (Use use : uses(created)) {
			if (use.instruction() == call && use.operand() == 1) continue;
			int at = method.instructions.indexOf(use.instruction());
			if (use.instruction().getOpcode() != Opcodes.AASTORE || use.operand() != 0 || at < from || at > to) return false;
			Integer index = intConstant(only(operand(use.instruction(), 1, 3)));
			if (index == null || index < 0 || index >= elements.size() || filled[index]) return false;
			if (!elements.get(index).test(operand(use.instruction(), 2, 3))) return false;
			filled[index] = true;
		}
		for (boolean element : filled) if (!element) return false;
		return true;
	}

	/**
	 * Whether the instructions from index {@code from} to {@code to} run as one straight line: nothing between them
	 * branches, returns or throws, and nothing anywhere jumps into them past {@code from}.
	 */
	public boolean straight(int from, int to) {
		if (from < 0 || to < from) return false;
		Set<AbstractInsnNode> targets = identitySet();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof org.objectweb.asm.tree.JumpInsnNode jump) targets.add(jump.label);
			if (instruction instanceof org.objectweb.asm.tree.TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			if (instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		if (method.tryCatchBlocks != null) for (var block : method.tryCatchBlocks) targets.add(block.handler);
		for (int i = from; i <= to; i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			if (i > from && targets.contains(instruction)) return false;
			if (i < to && ends(instruction)) return false;
		}
		return true;
	}

	/** Whether the flow does not simply fall through {@code instruction}: a branch, a switch, a return or a throw. */
	public static boolean ends(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		return instruction instanceof org.objectweb.asm.tree.JumpInsnNode || instruction instanceof org.objectweb.asm.tree.TableSwitchInsnNode
				|| instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
				|| opcode == Opcodes.ATHROW;
	}

	/** The instruction indices control can go to next from index {@code i} (no exception edges: callers refuse try blocks). */
	public List<Integer> successors(int i) {
		AbstractInsnNode instruction = method.instructions.get(i);
		List<Integer> out = new ArrayList<>();
		int opcode = instruction.getOpcode();
		if (instruction instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
			out.add(method.instructions.indexOf(jump.label));
			if (opcode != Opcodes.GOTO) out.add(i + 1);
		} else if (instruction instanceof org.objectweb.asm.tree.TableSwitchInsnNode table) {
			out.add(method.instructions.indexOf(table.dflt));
			for (var label : table.labels) out.add(method.instructions.indexOf(label));
		} else if (instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) {
			out.add(method.instructions.indexOf(lookup.dflt));
			for (var label : lookup.labels) out.add(method.instructions.indexOf(label));
		} else if (!(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) && i + 1 < method.instructions.size()) {
			out.add(i + 1);
		}
		return out;
	}

	/** Whether control can go from index {@code from} to an instruction {@code goal} accepts without passing {@code avoid}. */
	public boolean reaches(int from, Predicate<AbstractInsnNode> goal, Set<AbstractInsnNode> avoid) {
		boolean[] seen = new boolean[method.instructions.size()];
		ArrayDeque<Integer> pending = new ArrayDeque<>(List.of(from));
		while (!pending.isEmpty()) {
			int i = pending.pop();
			if (i < 0 || i >= seen.length || seen[i]) continue;
			seen[i] = true;
			AbstractInsnNode instruction = method.instructions.get(i);
			if (avoid.contains(instruction)) continue;
			if (goal.test(instruction)) return true;
			pending.addAll(successors(i));
		}
		return false;
	}

	/** Whether no jump goes backwards: every path runs each instruction at most once. */
	public boolean acyclic() {
		for (int i = 0; i < method.instructions.size(); i++) for (int next : successors(i)) if (next <= i) return false;
		return true;
	}

	private static Set<AbstractInsnNode> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}
}
