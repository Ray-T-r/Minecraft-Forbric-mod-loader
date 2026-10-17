/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import net.forbric.kernel.util.Caught;

/**
 * Aligns the instructions of a method as one platform compiled it with the instructions of the same method as the game
 * now runs it — the native body against the merged body, where a carrier inserted, removed or replaced code. The answer
 * is a partial map from native instructions to the merged instructions that are the same step of the same program:
 * an instruction the carrier left in place maps to itself there; one it removed or rewrote maps to nothing.
 *
 * <p>The alignment is a patience diff over what each instruction does — its opcode and the member, type or constant it
 * names, never a local slot (a carrier that adds a local renumbers the later ones) and never a label, frame or line
 * number — together with where its operands come from: each operand is described by the instruction that produced it,
 * followed through local stores and loads and stack copies, so a store of vanilla's ternary and a carrier's store of its
 * own hook's result into the same local are different steps, and so are two reads of the same getter on different
 * receivers. Instructions that occur exactly once in both stretches anchor the alignment first (the longest increasing
 * run of them), and the stretches between anchors are aligned the same way; a stretch with no unique instruction left is
 * aligned by a longest common subsequence, or left unaligned when it is too large to align that way. A run of identical
 * instructions on one side that the other side repeats is therefore matched in order, from the anchors outwards: a
 * carrier's call placed after vanilla's identical call does not take vanilla's place. A body the analyser cannot read is
 * compared by what its instructions name alone.
 *
 * <p>Nothing here knows any game, platform or mod: it compares two instruction lists.
 */
final class CodeAlignment {
	/** Above this many cells a stretch without unique anchors is left unaligned rather than compared cell by cell. */
	private static final long MAX_TABLE = 4_000_000L;

	private CodeAlignment() {
	}

	/** Native instruction -> merged instruction, for every native instruction the merged body still runs as the same step. */
	static Map<AbstractInsnNode, AbstractInsnNode> align(MethodNode nativeMethod, MethodNode mergedMethod) {
		return align(null, nativeMethod, null, mergedMethod);
	}

	/** As above, with the classes declaring each body, for the analyser that follows each operand to its producer. */
	static Map<AbstractInsnNode, AbstractInsnNode> align(String nativeOwner, MethodNode nativeMethod, String mergedOwner, MethodNode mergedMethod) {
		if (nativeMethod == null || mergedMethod == null || nativeMethod.instructions == null || mergedMethod.instructions == null) return null;
		List<AbstractInsnNode> a = real(nativeMethod), b = real(mergedMethod);
		Map<String, Integer> ids = new HashMap<>();
		Map<AbstractInsnNode, String> left = Origins.describe(nativeOwner, nativeMethod), right = Origins.describe(mergedOwner, mergedMethod);
		if (left == null || right == null) left = right = Map.of();
		int[] x = tokens(a, ids, left), y = tokens(b, ids, right);
		Map<AbstractInsnNode, AbstractInsnNode> out = new IdentityHashMap<>();
		new Patience(x, y, (i, j) -> out.put(a.get(i), b.get(j))).run(0, x.length, 0, y.length);
		return out;
	}

	/** The instructions that execute: no labels, frames or line numbers. */
	static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) out.add(instruction);
		return out;
	}

	private static int[] tokens(List<AbstractInsnNode> instructions, Map<String, Integer> ids, Map<AbstractInsnNode, String> origins) {
		int[] out = new int[instructions.size()];
		for (int i = 0; i < out.length; i++) {
			AbstractInsnNode instruction = instructions.get(i);
			String origin = origins.get(instruction);
			out[i] = ids.computeIfAbsent(origin == null ? token(instruction) : token(instruction) + origin, k -> ids.size());
		}
		return out;
	}

	/**
	 * Where each instruction's operands come from: for each stack operand (and for a local load, the local it reads), the
	 * instructions that produced the value, followed through stores, loads and stack copies to the instruction that made it
	 * (a parameter is its index). Null when the body cannot be analysed.
	 */
	static final class Origins {
		private static final int MAX_CHASE = 16;

		static Map<AbstractInsnNode, String> describe(String owner, MethodNode method) {
			org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
			try {
				frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter())
						.analyze(owner == null ? "java/lang/Object" : owner, method);
			} catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException unreadable) {
				Caught.expected(unreadable);
				return null;
			}
			Map<AbstractInsnNode, String> out = new IdentityHashMap<>();
			AbstractInsnNode[] instructions = method.instructions.toArray();
			for (int i = 0; i < instructions.length; i++) {
				AbstractInsnNode instruction = instructions[i];
				var frame = frames[i];
				if (instruction.getOpcode() < 0 || frame == null) continue;
				int operands = operands(instruction);
				StringBuilder origin = new StringBuilder("(");
				if (instruction instanceof org.objectweb.asm.tree.VarInsnNode load && load.getOpcode() >= org.objectweb.asm.Opcodes.ILOAD
						&& load.getOpcode() <= org.objectweb.asm.Opcodes.ALOAD && load.var < frame.getLocals()) {
					var local = frame.getLocal(load.var);
					// A parameter's slot is fixed by the descriptor both bodies share; any other local's slot is not.
					origin.append(local.insns.isEmpty() ? "parameter:" + load.var : value(local, method, frames));
				}
				for (int k = Math.max(0, frame.getStackSize() - operands); k < frame.getStackSize(); k++) {
					origin.append(k > 0 ? "," : "").append(value(frame.getStack(k), method, frames));
				}
				out.put(instruction, origin.append(')').toString());
			}
			return out;
		}

		/** The producers of {@code value}, past copies: their tokens, sorted, so the order of a phi's sources is no difference. */
		private static String value(org.objectweb.asm.tree.analysis.SourceValue value, MethodNode method,
				org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames) {
			if (value == null) return "?";
			if (value.insns.isEmpty()) return "parameter";
			java.util.TreeSet<String> producers = new java.util.TreeSet<>();
			java.util.Set<AbstractInsnNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			java.util.ArrayDeque<AbstractInsnNode> pending = new java.util.ArrayDeque<>(value.insns);
			int steps = 0;
			while (!pending.isEmpty() && steps++ < MAX_CHASE * 4) {
				AbstractInsnNode producer = pending.pop();
				if (!seen.add(producer)) continue;
				int opcode = producer.getOpcode();
				int index = method.instructions.indexOf(producer);
				var frame = index >= 0 ? frames[index] : null;
				boolean load = producer instanceof org.objectweb.asm.tree.VarInsnNode v && opcode >= org.objectweb.asm.Opcodes.ILOAD
						&& opcode <= org.objectweb.asm.Opcodes.ALOAD;
				boolean copy = opcode >= org.objectweb.asm.Opcodes.ISTORE && opcode <= org.objectweb.asm.Opcodes.ASTORE
						|| opcode >= org.objectweb.asm.Opcodes.DUP && opcode <= org.objectweb.asm.Opcodes.SWAP;
				if (load && frame != null && ((org.objectweb.asm.tree.VarInsnNode) producer).var < frame.getLocals()) {
					int slot = ((org.objectweb.asm.tree.VarInsnNode) producer).var;
					var local = frame.getLocal(slot);
					if (local.insns.isEmpty()) producers.add("parameter:" + slot);
					else pending.addAll(local.insns);
				} else if (copy && frame != null && frame.getStackSize() > 0) {
					var top = frame.getStack(frame.getStackSize() - 1);
					if (top.insns.isEmpty()) producers.add("parameter");
					else pending.addAll(top.insns);
				} else {
					producers.add(token(producer));
				}
			}
			return producers.toString();
		}

		/** How many stack values an instruction consumes; enough to tell its operands, not a verifier. */
		private static int operands(AbstractInsnNode instruction) {
			int opcode = instruction.getOpcode();
			if (instruction instanceof MethodInsnNode call) {
				return org.objectweb.asm.Type.getArgumentTypes(call.desc).length + (opcode == org.objectweb.asm.Opcodes.INVOKESTATIC ? 0 : 1);
			}
			if (instruction instanceof InvokeDynamicInsnNode dynamic) return org.objectweb.asm.Type.getArgumentTypes(dynamic.desc).length;
			if (instruction instanceof FieldInsnNode) {
				return switch (opcode) {
					case org.objectweb.asm.Opcodes.GETSTATIC -> 0;
					case org.objectweb.asm.Opcodes.PUTSTATIC, org.objectweb.asm.Opcodes.GETFIELD -> 1;
					default -> 2;
				};
			}
			if (instruction instanceof MultiANewArrayInsnNode array) return array.dims;
			if (opcode >= org.objectweb.asm.Opcodes.ISTORE && opcode <= org.objectweb.asm.Opcodes.ASTORE) return 1;
			if (opcode >= org.objectweb.asm.Opcodes.IALOAD && opcode <= org.objectweb.asm.Opcodes.SALOAD) return 2;
			if (opcode >= org.objectweb.asm.Opcodes.IASTORE && opcode <= org.objectweb.asm.Opcodes.SASTORE) return 3;
			if (opcode >= org.objectweb.asm.Opcodes.IADD && opcode <= org.objectweb.asm.Opcodes.DREM) return 2;
			if (opcode >= org.objectweb.asm.Opcodes.ISHL && opcode <= org.objectweb.asm.Opcodes.LXOR) return 2;
			if (opcode >= org.objectweb.asm.Opcodes.INEG && opcode <= org.objectweb.asm.Opcodes.DNEG) return 1;
			if (opcode >= org.objectweb.asm.Opcodes.I2L && opcode <= org.objectweb.asm.Opcodes.I2S) return 1;
			if (opcode >= org.objectweb.asm.Opcodes.LCMP && opcode <= org.objectweb.asm.Opcodes.DCMPG) return 2;
			if (opcode >= org.objectweb.asm.Opcodes.IFEQ && opcode <= org.objectweb.asm.Opcodes.IFLE) return 1;
			if (opcode >= org.objectweb.asm.Opcodes.IF_ICMPEQ && opcode <= org.objectweb.asm.Opcodes.IF_ACMPNE) return 2;
			if (opcode == org.objectweb.asm.Opcodes.IFNULL || opcode == org.objectweb.asm.Opcodes.IFNONNULL) return 1;
			if (opcode >= org.objectweb.asm.Opcodes.IRETURN && opcode <= org.objectweb.asm.Opcodes.ARETURN) return 1;
			return switch (opcode) {
				case org.objectweb.asm.Opcodes.TABLESWITCH, org.objectweb.asm.Opcodes.LOOKUPSWITCH, org.objectweb.asm.Opcodes.ATHROW,
						org.objectweb.asm.Opcodes.ARRAYLENGTH, org.objectweb.asm.Opcodes.CHECKCAST, org.objectweb.asm.Opcodes.INSTANCEOF,
						org.objectweb.asm.Opcodes.MONITORENTER, org.objectweb.asm.Opcodes.MONITOREXIT, org.objectweb.asm.Opcodes.NEWARRAY,
						org.objectweb.asm.Opcodes.ANEWARRAY, org.objectweb.asm.Opcodes.POP -> 1;
				case org.objectweb.asm.Opcodes.POP2 -> 2;
				default -> 0;
			};
		}
	}

	/** What an instruction does: its opcode and what it names — a member, a type, a constant — never a local slot or a label. */
	static String token(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		if (instruction instanceof MethodInsnNode call) return opcode + " " + call.owner + "." + call.name + call.desc;
		if (instruction instanceof FieldInsnNode field) return opcode + " " + field.owner + "." + field.name + ":" + field.desc;
		if (instruction instanceof TypeInsnNode type) return opcode + " " + type.desc;
		if (instruction instanceof LdcInsnNode constant) return opcode + " " + constant.cst.getClass().getName() + " " + constant.cst;
		if (instruction instanceof IntInsnNode integer) return opcode + " " + integer.operand;
		if (instruction instanceof IincInsnNode increment) return opcode + " " + increment.incr;
		if (instruction instanceof MultiANewArrayInsnNode array) return opcode + " " + array.desc + " " + array.dims;
		if (instruction instanceof InvokeDynamicInsnNode dynamic) {
			StringBuilder token = new StringBuilder().append(opcode).append(' ').append(dynamic.name).append(dynamic.desc);
			for (Object argument : dynamic.bsmArgs) {
				token.append(' ');
				if (argument instanceof Handle handle) token.append(handle.getOwner()).append('.').append(handle.getName()).append(handle.getDesc());
				else token.append(argument);
			}
			return token.toString();
		}
		return Integer.toString(opcode);
	}

	@FunctionalInterface
	private interface Matches {
		void match(int i, int j);
	}

	/** Patience diff: unique common tokens anchor, the gaps recurse, and a gap with no unique token is a small LCS. */
	private static final class Patience {
		final int[] a, b;
		final Matches matches;

		Patience(int[] a, int[] b, Matches matches) {
			this.a = a;
			this.b = b;
			this.matches = matches;
		}

		void run(int aLo, int aHi, int bLo, int bHi) {
			// Common head and tail first: the bulk of a method a carrier patched in one place.
			while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) matches.match(aLo++, bLo++);
			int tail = 0;
			while (aLo < aHi - tail && bLo < bHi - tail && a[aHi - 1 - tail] == b[bHi - 1 - tail]) tail++;
			// The tail is reported after the middle, so the pairs stay in increasing order overall.
			int aMid = aHi - tail, bMid = bHi - tail;
			if (aLo < aMid && bLo < bMid) middle(aLo, aMid, bLo, bMid);
			for (int k = 0; k < tail; k++) matches.match(aMid + k, bMid + k);
		}

		private void middle(int aLo, int aHi, int bLo, int bHi) {
			// Tokens that occur exactly once in each stretch, paired, in a's order.
			Map<Integer, int[]> seen = new HashMap<>();
			for (int i = aLo; i < aHi; i++) seen.computeIfAbsent(a[i], k -> new int[] {0, -1, 0, -1}) [0]++;
			for (int i = aLo; i < aHi; i++) { int[] s = seen.get(a[i]); if (s[0] == 1) s[1] = i; }
			for (int j = bLo; j < bHi; j++) { int[] s = seen.get(b[j]); if (s != null) { s[2]++; s[3] = j; } }
			List<int[]> pairs = new ArrayList<>();
			for (int i = aLo; i < aHi; i++) {
				int[] s = seen.get(a[i]);
				if (s[0] == 1 && s[2] == 1) pairs.add(new int[] {i, s[3]});
			}
			if (pairs.isEmpty()) {
				lcs(aLo, aHi, bLo, bHi);
				return;
			}
			List<int[]> anchors = increasing(pairs);
			int pa = aLo, pb = bLo;
			for (int[] anchor : anchors) {
				run(pa, anchor[0], pb, anchor[1]);
				matches.match(anchor[0], anchor[1]);
				pa = anchor[0] + 1;
				pb = anchor[1] + 1;
			}
			run(pa, aHi, pb, bHi);
		}

		/** The longest run of pairs increasing in both positions (patience sorting with back-pointers). */
		private static List<int[]> increasing(List<int[]> pairs) {
			int n = pairs.size();
			int[] tails = new int[n], previous = new int[n];
			int length = 0;
			for (int k = 0; k < n; k++) {
				int value = pairs.get(k)[1], lo = 0, hi = length;
				while (lo < hi) {
					int mid = (lo + hi) >>> 1;
					if (pairs.get(tails[mid])[1] < value) lo = mid + 1; else hi = mid;
				}
				previous[k] = lo > 0 ? tails[lo - 1] : -1;
				tails[lo] = k;
				if (lo == length) length++;
			}
			List<int[]> out = new ArrayList<>();
			for (int k = length > 0 ? tails[length - 1] : -1; k >= 0; k = previous[k]) out.add(0, pairs.get(k));
			return out;
		}

		/** A plain longest common subsequence of a stretch with no unique anchor, when it is small enough to tabulate. */
		private void lcs(int aLo, int aHi, int bLo, int bHi) {
			int n = aHi - aLo, m = bHi - bLo;
			if ((long) n * m > MAX_TABLE) return;
			int[][] table = new int[n + 1][m + 1];
			for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
				table[i][j] = a[aLo + i] == b[bLo + j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
			int i = 0, j = 0;
			while (i < n && j < m) {
				if (a[aLo + i] == b[bLo + j]) { matches.match(aLo + i, bLo + j); i++; j++; }
				else if (table[i + 1][j] >= table[i][j + 1]) i++;
				else j++;
			}
		}
	}
}
