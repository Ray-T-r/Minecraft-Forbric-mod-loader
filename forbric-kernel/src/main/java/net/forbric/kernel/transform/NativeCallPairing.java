/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Whether a merged method's MinecraftForge call already does, on the same object and on the same path, what a repair
 * would add beside a NeoForge call there. A repair that stands down on the MinecraftForge call's mere presence stands
 * down on bodies its own claim does not hold for: a call handed another object, or a call on another branch.
 */
final class NativeCallPairing {
	private NativeCallPairing() {
	}

	/**
	 * Whether {@code forge} pairs with a NeoForge call in {@code method}: some object argument of the MinecraftForge call
	 * is made from the same definition as argument {@code neoArg} of {@code neoValue} (the same allocation, parameter or
	 * call result, through locals, casts and lambda captures), and the MinecraftForge call runs on the path of
	 * {@code neoPath}: every normal path into that instruction passes it, or every normal path on from that instruction
	 * to a return or throw does. Exceptional exits are not paths here: a throw between the two leaves neither family's
	 * event delivered the way the other side's body would.
	 */
	static boolean pairs(String owner, MethodNode method, MethodInsnNode forge, MethodInsnNode neoValue, int neoArg, AbstractInsnNode neoPath) {
		Flow flow;
		try {
			flow = new Flow(owner, method);
		} catch (AnalyzerException | RuntimeException unanalyzable) {
			return false;
		}
		int f = method.instructions.indexOf(forge), n = method.instructions.indexOf(neoPath);
		if (f < 0 || n < 0 || f == n || !(flow.dominates(f, n) || flow.postDominates(f, n))) return false;
		Roots roots = new Roots(flow, method);
		Set<AbstractInsnNode> theirs = roots.of(argument(flow, method, neoValue, neoArg));
		if (theirs.isEmpty()) return false;
		Type[] args = Type.getArgumentTypes(forge.desc);
		for (int i = 0; i < args.length; i++) {
			if (args[i].getSort() != Type.OBJECT && args[i].getSort() != Type.ARRAY) continue;
			for (AbstractInsnNode root : roots.of(argument(flow, method, forge, i))) if (theirs.contains(root)) return true;
		}
		return false;
	}

	/** A method's source frames and its normal control flow (exception edges feed the frames but are not paths). */
	private static final class Flow {
		final Frame<SourceValue>[] frames;
		private final Map<Integer, Set<Integer>> edges = new HashMap<>();
		private final boolean[] exit;

		Flow(String owner, MethodNode method) throws AnalyzerException {
			frames = new org.objectweb.asm.tree.analysis.Analyzer<SourceValue>(new org.objectweb.asm.tree.analysis.SourceInterpreter()) {
				@Override protected void newControlFlowEdge(int from, int to) {
					edges.computeIfAbsent(from, k -> new java.util.HashSet<>()).add(to);
				}
			}.analyze(owner, method);
			exit = new boolean[method.instructions.size()];
			for (int i = 0; i < exit.length; i++) {
				int opcode = method.instructions.get(i).getOpcode();
				exit[i] = (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
			}
		}

		/** Every path from the entry to {@code n} passes {@code f}. */
		boolean dominates(int f, int n) {
			return !reaches(java.util.List.of(0), n, f);
		}

		/** Every path from {@code n} on to an exit, or round to {@code n} again, passes {@code f}. */
		boolean postDominates(int f, int n) {
			java.util.List<Integer> next = new java.util.ArrayList<>(edges.getOrDefault(n, Set.of()));
			if (reaches(next, n, f)) return false;
			java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>(next);
			Set<Integer> seen = new java.util.HashSet<>();
			while (!work.isEmpty()) {
				int at = work.removeFirst();
				if (at == f || !seen.add(at)) continue;
				if (exit[at]) return false;
				work.addAll(edges.getOrDefault(at, Set.of()));
			}
			return true;
		}

		private boolean reaches(java.util.List<Integer> from, int goal, int avoided) {
			java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>(from);
			Set<Integer> seen = new java.util.HashSet<>();
			while (!work.isEmpty()) {
				int at = work.removeFirst();
				if (at == avoided || !seen.add(at)) continue;
				if (at == goal) return true;
				work.addAll(edges.getOrDefault(at, Set.of()));
			}
			return false;
		}
	}

	/** The value argument {@code index} of {@code call} (the receiver not counted) has when the call runs, or null. */
	private static SourceValue argument(Flow flow, MethodNode method, MethodInsnNode call, int index) {
		Frame<SourceValue> frame = flow.frames[method.instructions.indexOf(call)];
		if (frame == null) return null;
		int depth = frame.getStackSize() - Type.getArgumentTypes(call.desc).length + index;
		return depth < 0 || depth >= frame.getStackSize() ? null : frame.getStack(depth);
	}

	/** The definitions values are made from: allocations, call results, constants, field reads and parameters. */
	private static final class Roots {
		private final Flow flow;
		private final MethodNode method;
		private final Map<Integer, AbstractInsnNode> parameters = new HashMap<>();

		Roots(Flow flow, MethodNode method) {
			this.flow = flow;
			this.method = method;
		}

		Set<AbstractInsnNode> of(SourceValue value) {
			Set<AbstractInsnNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
			if (value != null) walk(value, out, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
			return out;
		}

		private void walk(SourceValue value, Set<AbstractInsnNode> out, Set<AbstractInsnNode> seen, int depth) {
			for (AbstractInsnNode producer : value.insns) {
				if (depth > 24 || !seen.add(producer)) continue;
				Frame<SourceValue> frame = flow.frames[method.instructions.indexOf(producer)];
				int opcode = producer.getOpcode();
				if (producer instanceof VarInsnNode load && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
					if (frame == null) continue;
					SourceValue stored = frame.getLocal(load.var);
					if (stored.insns.isEmpty()) out.add(parameters.computeIfAbsent(load.var, slot -> new InsnNode(Opcodes.NOP)));
					else walk(stored, out, seen, depth + 1);
				} else if (producer instanceof VarInsnNode) {
					// A store: what it stored.
					if (frame != null && frame.getStackSize() > 0) walk(frame.getStack(frame.getStackSize() - 1), out, seen, depth + 1);
				} else if (producer instanceof IincInsnNode) {
					out.add(producer);
				} else if (opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST) {
					if (frame != null && frame.getStackSize() > 0) walk(frame.getStack(frame.getStackSize() - 1), out, seen, depth + 1);
				} else if (producer instanceof InvokeDynamicInsnNode capture) {
					// A lambda or method reference is made from what it captures.
					int captured = Type.getArgumentTypes(capture.desc).length;
					for (int i = 0; frame != null && i < captured; i++) walk(frame.getStack(frame.getStackSize() - captured + i), out, seen, depth + 1);
				} else {
					out.add(producer);
				}
			}
		}
	}
}
