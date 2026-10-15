/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Which return of a method's current body is which return of the native body a guest was compiled against.
 *
 * <p>A guest's {@code @At("RETURN", ordinal = n)} counts the native body's returns. The merged body may have more (an
 * early exit a carrier's patch added: MinecraftForge's {@code onBlockStartBreak} in {@code destroyBlock}, its
 * {@code handleServerAboutToStart} veto in {@code initServer}) or fewer, so the n-th return there can be another way
 * out, where other locals are in scope and other work has been done.
 *
 * <p>A return is known by what decides that the method leaves there, read off each body alone:
 * <ul>
 *   <li>the native LAST return is the current last return — the one Mixin's {@code TAIL} names on either body, the way
 *       out after the method's work;</li>
 *   <li>an early return entered by exactly one conditional branch is known by the value that branch tests: the call or
 *       field read producing it (through one local store); it is that return when exactly one return of each body is
 *       decided by the same member;</li>
 *   <li>an early return entered otherwise is known by the calls its own straight-line run makes, when those occur once
 *       among each body's returns.</li>
 * </ul>
 * A return that nothing identifies on both sides maps to none, and two native returns never map to one current return.
 */
final class ReturnAlignment {
	/** One way out: its return instruction, whether it is the last, and what identifies it. */
	record Exit(AbstractInsnNode instruction, boolean last, String decider, List<String> calls) { }

	private ReturnAlignment() {
	}

	/** The returns of {@code method}, in instruction order, each with what identifies it; null when unanalysable. */
	static List<Exit> exits(String owner, MethodNode method) {
		if (method == null || method.instructions == null || method.instructions.size() == 0) return null;
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return null;
		}
		Map<LabelNode, List<AbstractInsnNode>> into = new IdentityHashMap<>();
		for (AbstractInsnNode at : method.instructions) {
			if (at instanceof JumpInsnNode jump) into.computeIfAbsent(jump.label, k -> new ArrayList<>()).add(at);
			else if (at instanceof TableSwitchInsnNode table) {
				into.computeIfAbsent(table.dflt, k -> new ArrayList<>()).add(at);
				for (LabelNode label : table.labels) into.computeIfAbsent(label, k -> new ArrayList<>()).add(at);
			} else if (at instanceof LookupSwitchInsnNode lookup) {
				into.computeIfAbsent(lookup.dflt, k -> new ArrayList<>()).add(at);
				for (LabelNode label : lookup.labels) into.computeIfAbsent(label, k -> new ArrayList<>()).add(at);
			}
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode block : method.tryCatchBlocks) into.computeIfAbsent(block.handler, k -> new ArrayList<>()).add(null);
		}
		List<AbstractInsnNode> returns = new ArrayList<>();
		for (AbstractInsnNode at : method.instructions) {
			int opcode = at.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN && frames[method.instructions.indexOf(at)] != null) returns.add(at);
		}
		List<Exit> out = new ArrayList<>();
		for (int i = 0; i < returns.size(); i++) {
			AbstractInsnNode exit = returns.get(i);
			List<String> calls = new ArrayList<>();
			String decider = null;
			// The run ending at the return; while the one way into it is a goto (a return block a split placed before the
			// tail, a ternary's join), the run ending at that goto too, up to the branch that decides.
			AbstractInsnNode end = exit;
			for (int hops = 0; hops < 4; hops++) {
				List<AbstractInsnNode> run = JoinedReturnExits.run(method, end, false);
				for (int k = run.size() - 1; k >= 0; k--) if (run.get(k) instanceof MethodInsnNode call) calls.add(0, CallOccurrenceAlignment.member(call));
				List<AbstractInsnNode> entries = entries(method, into, run.isEmpty() ? end : run.getFirst());
				if (entries.size() != 1 || !(entries.getFirst() instanceof JumpInsnNode branch) || branch.getOpcode() == Opcodes.JSR) break;
				if (branch.getOpcode() != Opcodes.GOTO) {
					decider = decider(method, frames, branch);
					break;
				}
				end = branch;
			}
			out.add(new Exit(exit, i == returns.size() - 1, decider, List.copyOf(calls)));
		}
		return out;
	}

	/** Every way into the instruction {@code first} starts a run at: a fall-through, and every branch to a label in front of it. */
	private static List<AbstractInsnNode> entries(MethodNode method, Map<LabelNode, List<AbstractInsnNode>> into, AbstractInsnNode first) {
		List<AbstractInsnNode> entries = new ArrayList<>();
		AbstractInsnNode before = first.getPrevious();
		while (before != null && before.getOpcode() < 0) {
			if (before instanceof LabelNode label && into.containsKey(label)) entries.addAll(into.get(label));
			before = before.getPrevious();
		}
		if (before != null && !transfersAway(before)) entries.add(before);
		return entries;
	}

	/**
	 * For each native return (by ordinal), the current return that is the same way out (by ordinal), or -1; null when
	 * either body cannot be read.
	 */
	static int[] map(String nativeOwner, MethodNode nativeMethod, String currentOwner, MethodNode currentMethod) {
		List<Exit> before = exits(nativeOwner, nativeMethod), after = exits(currentOwner, currentMethod);
		if (before == null || after == null) return null;
		int[] out = new int[before.size()];
		java.util.Arrays.fill(out, -1);
		if (before.isEmpty() || after.isEmpty()) return out;
		out[before.size() - 1] = after.size() - 1;
		for (int k = 0; k + 1 < before.size(); k++) {
			Exit exit = before.get(k);
			int found = -1;
			if (exit.decider() != null) {
				if (count(before, exit.decider(), null) == 1) found = unique(after, exit.decider(), null);
			} else if (!exit.calls().isEmpty() && count(before, null, exit.calls()) == 1) {
				found = unique(after, null, exit.calls());
			}
			out[k] = found;
		}
		// Never two native returns on one current return.
		for (int a = 0; a < out.length; a++) {
			for (int b = a + 1; b < out.length; b++) {
				if (out[a] >= 0 && out[a] == out[b]) out[a] = out[b] = -1;
			}
		}
		return out;
	}

	private static int count(List<Exit> exits, String decider, List<String> calls) {
		int found = 0;
		for (Exit exit : exits) if (!exit.last() && same(exit, decider, calls)) found++;
		return found;
	}

	private static int unique(List<Exit> exits, String decider, List<String> calls) {
		int found = -1;
		for (int i = 0; i < exits.size(); i++) {
			if (exits.get(i).last() || !same(exits.get(i), decider, calls)) continue;
			if (found >= 0) return -1;
			found = i;
		}
		return found;
	}

	private static boolean same(Exit exit, String decider, List<String> calls) {
		return decider != null ? decider.equals(exit.decider()) : exit.decider() == null && exit.calls().equals(calls);
	}

	/** What a conditional branch tests: the producers of its operands, each a call or field read (through one store). */
	private static String decider(MethodNode method, Frame<SourceValue>[] frames, JumpInsnNode branch) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(branch)];
		if (frame == null) return null;
		int operands = switch (branch.getOpcode()) {
			case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
					Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> 2;
			default -> 1;
		};
		if (frame.getStackSize() < operands) return null;
		List<String> parts = new ArrayList<>();
		for (int i = operands; i >= 1; i--) {
			String producer = producer(method, frames, frame.getStack(frame.getStackSize() - i), 2);
			if (producer == null) return null;
			parts.add(producer);
		}
		return String.join(" ? ", parts);
	}

	private static String producer(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int depth) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode producer = value.insns.iterator().next();
		if (producer instanceof MethodInsnNode call) {
			// Which call of that member it is, of how many: two tests of one predicate in a body are two different exits,
			// and a body that calls it a different number of times is not counted against the other at all.
			String member = CallOccurrenceAlignment.member(call);
			int occurrence = -1, occurrences = 0;
			for (AbstractInsnNode at : method.instructions) {
				if (at == call) occurrence = occurrences;
				if (at instanceof MethodInsnNode other && CallOccurrenceAlignment.member(other).equals(member)) occurrences++;
			}
			return "call " + member + "#" + occurrence + "/" + occurrences;
		}
		int opcode = producer.getOpcode();
		if (opcode == Opcodes.LCMP || opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG) {
			Frame<SourceValue> at = frames[method.instructions.indexOf(producer)];
			if (at == null || at.getStackSize() < 2) return null;
			String a = producer(method, frames, at.getStack(at.getStackSize() - 2), depth), b = producer(method, frames, at.getStack(at.getStackSize() - 1), depth);
			return a == null || b == null ? null : "compare(" + a + ", " + b + ")";
		}
		if (opcode >= Opcodes.LCONST_0 && opcode <= Opcodes.DCONST_1) return "const " + org.objectweb.asm.util.Printer.OPCODES[opcode];
		if (producer instanceof IntInsnNode integer && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return "const " + integer.operand;
		if (producer instanceof LdcInsnNode constant && (constant.cst instanceof Number || constant.cst instanceof String)) return "const " + constant.cst;
		if (producer instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
			return "field " + field.owner + "." + field.name + ":" + field.desc;
		}
		if (producer instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF) {
			Frame<SourceValue> at = frames[method.instructions.indexOf(producer)];
			String inner = at == null || at.getStackSize() < 1 ? null : producer(method, frames, at.getStack(at.getStackSize() - 1), depth);
			return inner == null ? null : "instanceof " + type.desc + " of " + inner;
		}
		if (producer.getOpcode() >= Opcodes.ICONST_M1 && producer.getOpcode() <= Opcodes.ICONST_5) return "const " + (producer.getOpcode() - Opcodes.ICONST_0);
		if (producer.getOpcode() == Opcodes.ACONST_NULL) return "null";
		if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD && depth > 0) {
			Frame<SourceValue> at = frames[method.instructions.indexOf(producer)];
			if (at == null) return null;
			// A parameter's slot is fixed by the descriptor on both bodies: a test of it is a test of that parameter, however
			// either body reassigned it on the way.
			if (load.var < parameterSlots(method)) return "parameter " + load.var;
			SourceValue stores = at.getLocal(load.var);
			if (stores.insns.size() != 1 || !(stores.insns.iterator().next() instanceof VarInsnNode store)) return null;
			Frame<SourceValue> before = frames[method.instructions.indexOf(store)];
			if (before == null || before.getStackSize() < 1) return null;
			return producer(method, frames, before.getStack(before.getStackSize() - 1), depth - 1);
		}
		return null;
	}

	private static int parameterSlots(MethodNode method) {
		int slots = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (org.objectweb.asm.Type type : org.objectweb.asm.Type.getArgumentTypes(method.desc)) slots += type.getSize();
		return slots;
	}

	/** An instruction control never falls through: a goto, a switch, a return or a throw. */
	private static boolean transfersAway(AbstractInsnNode at) {
		int opcode = at.getOpcode();
		return opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
				|| at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode;
	}
}
