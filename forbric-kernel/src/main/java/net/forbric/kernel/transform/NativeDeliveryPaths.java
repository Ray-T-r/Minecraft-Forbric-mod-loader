/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Which ways out of a merged method bypass a MinecraftForge delivery that comes after a NeoForge one, and for each,
 * whether MinecraftForge's own patched body of the same method bypasses its call on that way too.
 *
 * <h2>The question</h2>
 *
 * <p>A merged method can post NeoForge's event and, later, call MinecraftForge's hook behind checks: the friend
 * restriction and the missing player in front of MinecraftForge's system-chat hook, say. The native call is then the
 * delivery a MinecraftForge mod would get on MinecraftForge itself, exactly where and when it would get it — but only
 * if every check that can skip it is one MinecraftForge's own body has too. A check NeoForge's patch added (its own
 * early return once its event is cancelled, a guard of its own) skips the call on a way MinecraftForge's body would
 * reach it, and there the event would be lost.
 *
 * <h2>How it is read</h2>
 *
 * <ul>
 *   <li>A bypass edge: a normal control-flow edge {@code B → s} reachable after the NeoForge call, where some
 *       MinecraftForge delivery of the event is still reachable from {@code B} and none is from {@code s}. Every way
 *       out that skips the native call crosses exactly one such edge, the place it commits to skipping.</li>
 *   <li>The two bodies are aligned instruction by instruction (a longest common subsequence of opcodes and operands,
 *       locals by name), which pairs the vanilla code and the MinecraftForge code the merge carried with their own
 *       originals, and leaves NeoForge's additions unpaired.</li>
 *   <li>An edge is shared when its branch has a counterpart in MinecraftForge's body and, from the counterpart of
 *       {@code s}, no MinecraftForge delivery of the event is reachable there either. Anything else — a branch
 *       NeoForge added, or a vanilla branch whose way MinecraftForge's body continues to its call — bypasses the call
 *       only in the merged game.</li>
 * </ul>
 *
 * <p>Exception edges are left out: an exception leaves both bodies alike. Nothing here keys on a name; the two
 * bodies and the families' delivery sites are the whole input.
 */
final class NativeDeliveryPaths {
	/** The largest pair of bodies aligned, as instruction count products: the table is one byte per pair. */
	static final long MAX_CELLS = 16_000_000L;

	private NativeDeliveryPaths() {
	}

	/** How an edge leaves its branch: taken, fallen through, or one switch target. */
	enum Kind { JUMP, FALL, SWITCH }

	/**
	 * One bypass edge, by instruction-list index.
	 *
	 * @param label for {@link Kind#JUMP} and {@link Kind#SWITCH}, the label the edge goes to; null for {@link Kind#FALL}
	 */
	record Edge(int from, int to, Kind kind, LabelNode label, boolean forgeBypassesToo) { }

	/**
	 * The bypass edges of the deliveries at {@code sites} after the NeoForge call at {@code neo}, each classified against
	 * MinecraftForge's own body; null when that cannot be judged (no counterpart for a delivery site, a body too large to
	 * align, or a jump target without a frame to give a new edge). Edges are read on normal control flow.
	 *
	 * @param forgeSites instruction-list indices, in {@code forge}, of the MinecraftForge calls or posts that can deliver
	 *                   the event
	 * @param match      {@link #align} of {@code merged} with {@code forge}
	 */
	static List<Edge> judge(MethodNode merged, NativeDualEventInjector.Flow flow, int neo, Set<Integer> sites,
			MethodNode forge, NativeDualEventInjector.Flow forgeFlow, Set<Integer> forgeSites, int[] match) {
		if (match == null) return null;
		for (int site : sites) if (match[site] < 0 || !forgeSites.contains(match[site])) return null;
		// Normal edges only, on both sides: an exception edge leaves from every instruction a try block covers, whether it
		// can throw or not, and would make every way out inside one look as if it could still reach the call.
		boolean[] canMerged = reachesAny(flow.normal, merged.instructions.size(), sites);
		boolean[] canForge = reachesAny(forgeFlow.normal, forge.instructions.size(), forgeSites);
		boolean[] after = new boolean[merged.instructions.size()];
		ArrayDeque<Integer> work = new ArrayDeque<>(flow.normal.getOrDefault(neo, Set.of()));
		while (!work.isEmpty()) {
			int at = work.removeFirst();
			if (after[at]) continue;
			after[at] = true;
			work.addAll(flow.normal.getOrDefault(at, Set.of()));
		}
		List<Edge> edges = new ArrayList<>();
		for (int b = 0; b < after.length; b++) {
			if (!after[b] || !canMerged[b] || sites.contains(b) || flow.frames[b] == null) continue;
			// Over normal edges an instruction with one successor reaches what that successor reaches: only a branch can
			// be where a way out commits to bypassing the call.
			AbstractInsnNode branch = merged.instructions.get(b);
			if (!(branch instanceof JumpInsnNode) && !(branch instanceof TableSwitchInsnNode) && !(branch instanceof LookupSwitchInsnNode)) continue;
			for (Successor successor : successors(merged.instructions, branch)) {
				if (canMerged[successor.index()]) continue;
				if (successor.label() != null && frameAt(successor.label()) == null) return null;
				boolean shared = false;
				int counterpart = match[b];
				if (counterpart >= 0) {
					Integer theirs = counterpartSuccessor(forge.instructions, forge.instructions.get(counterpart), branch, successor);
					shared = theirs != null && !canForge[theirs];
				}
				edges.add(new Edge(b, successor.index(), successor.kind(), successor.label(), shared));
			}
		}
		return edges;
	}

	/** One normal successor of an instruction: where it goes, how, and through which label. */
	private record Successor(int index, Kind kind, LabelNode label, int position) { }

	private static List<Successor> successors(InsnList code, AbstractInsnNode insn) {
		int at = code.indexOf(insn);
		List<Successor> out = new ArrayList<>(2);
		int opcode = insn.getOpcode();
		if (insn instanceof JumpInsnNode jump) {
			if (opcode == Opcodes.JSR) return List.of();
			out.add(new Successor(code.indexOf(jump.label), Kind.JUMP, jump.label, -1));
			if (opcode != Opcodes.GOTO) out.add(new Successor(at + 1, Kind.FALL, null, -1));
		} else if (insn instanceof TableSwitchInsnNode table) {
			addSwitch(code, out, table.labels, table.dflt);
		} else if (insn instanceof LookupSwitchInsnNode lookup) {
			addSwitch(code, out, lookup.labels, lookup.dflt);
		} else if (!((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW || opcode == Opcodes.RET)) {
			if (at + 1 < code.size()) out.add(new Successor(at + 1, Kind.FALL, null, -1));
		}
		return out;
	}

	private static void addSwitch(InsnList code, List<Successor> out, List<LabelNode> labels, LabelNode dflt) {
		Set<LabelNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < labels.size(); i++) {
			if (seen.add(labels.get(i))) out.add(new Successor(code.indexOf(labels.get(i)), Kind.SWITCH, labels.get(i), i));
		}
		if (seen.add(dflt)) out.add(new Successor(code.indexOf(dflt), Kind.SWITCH, dflt, -1));
	}

	/**
	 * The counterpart, in MinecraftForge's body, of where {@code ours} goes on {@code successor}: the same branch of the
	 * paired instruction. A switch case is matched by its key, the default by being the default.
	 */
	private static Integer counterpartSuccessor(InsnList theirCode, AbstractInsnNode theirs, AbstractInsnNode ours, Successor successor) {
		switch (successor.kind()) {
			case JUMP:
				return theirs instanceof JumpInsnNode jump ? theirCode.indexOf(jump.label) : null;
			case FALL: {
				int at = theirCode.indexOf(theirs);
				int opcode = theirs.getOpcode();
				if (opcode == Opcodes.GOTO || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) return null;
				return at + 1 < theirCode.size() ? at + 1 : null;
			}
			default: {
				LabelNode target = switchTarget(theirs, switchKey(ours, successor.label()));
				return target == null ? null : theirCode.indexOf(target);
			}
		}
	}

	/** The key a switch reaches {@code label} under ({@code null} for the default only). */
	private static Integer switchKey(AbstractInsnNode insn, LabelNode label) {
		if (insn instanceof TableSwitchInsnNode table) {
			for (int i = 0; i < table.labels.size(); i++) if (table.labels.get(i) == label) return table.min + i;
		} else if (insn instanceof LookupSwitchInsnNode lookup) {
			for (int i = 0; i < lookup.labels.size(); i++) if (lookup.labels.get(i) == label) return lookup.keys.get(i);
		}
		return null;
	}

	private static LabelNode switchTarget(AbstractInsnNode insn, Integer key) {
		if (insn instanceof TableSwitchInsnNode table) {
			if (key == null) return table.dflt;
			int i = key - table.min;
			return i >= 0 && i < table.labels.size() ? table.labels.get(i) : table.dflt;
		}
		if (insn instanceof LookupSwitchInsnNode lookup) {
			if (key == null) return lookup.dflt;
			int i = lookup.keys.indexOf(key);
			return i >= 0 ? lookup.labels.get(i) : lookup.dflt;
		}
		return null;
	}

	/** The frame recorded for a branch target: the first frame node before the next real instruction. */
	static FrameNode frameAt(LabelNode label) {
		for (AbstractInsnNode n = label; n != null; n = n.getNext()) {
			if (n instanceof FrameNode frame) return frame;
			if (n.getOpcode() >= 0) return null;
		}
		return null;
	}

	/** For each instruction index, whether one of {@code targets} can still run after it (or is it), over {@code edges}. */
	static boolean[] reachesAny(Map<Integer, Set<Integer>> edges, int size, Set<Integer> targets) {
		Map<Integer, List<Integer>> reverse = new HashMap<>();
		for (var entry : edges.entrySet()) {
			for (int to : entry.getValue()) reverse.computeIfAbsent(to, k -> new ArrayList<>()).add(entry.getKey());
		}
		boolean[] reach = new boolean[size];
		ArrayDeque<Integer> work = new ArrayDeque<>(targets);
		while (!work.isEmpty()) {
			int at = work.removeFirst();
			if (reach[at]) continue;
			reach[at] = true;
			for (int from : reverse.getOrDefault(at, List.of())) if (!reach[from]) work.add(from);
		}
		return reach;
	}

	// ---------------------------------------------------------------- alignment

	/**
	 * For each instruction-list index of {@code ours}, the index of its counterpart in {@code theirs}, or -1: a longest
	 * common subsequence of the real instructions under {@link #key}. Null when the pair is too large to align.
	 */
	static int[] align(MethodNode ours, MethodNode theirs) {
		List<Integer> a = real(ours), b = real(theirs);
		int n = a.size(), m = b.size();
		if ((long) (n + 1) * (m + 1) > MAX_CELLS) return null;
		String[] ka = keys(ours, a), kb = keys(theirs, b);
		// Suffix lengths in two rolling rows, the direction of each step in a byte table: 1 = both, 2 = skip ours.
		byte[][] step = new byte[n + 1][m + 1];
		int[] next = new int[m + 1], row = new int[m + 1];
		for (int i = n - 1; i >= 0; i--) {
			for (int j = m - 1; j >= 0; j--) {
				if (ka[i].equals(kb[j])) {
					row[j] = next[j + 1] + 1;
					step[i][j] = 1;
				} else if (next[j] >= row[j + 1]) {
					row[j] = next[j];
					step[i][j] = 2;
				} else {
					row[j] = row[j + 1];
					step[i][j] = 3;
				}
			}
			int[] swap = next;
			next = row;
			row = swap;
			Arrays.fill(row, 0);
		}
		int[] match = new int[ours.instructions.size()];
		Arrays.fill(match, -1);
		int i = 0, j = 0;
		while (i < n && j < m) {
			switch (step[i][j]) {
				case 1 -> match[a.get(i++)] = b.get(j++);
				case 2 -> i++;
				default -> j++;
			}
		}
		return match;
	}

	private static List<Integer> real(MethodNode method) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i).getOpcode() >= 0) out.add(i);
		return out;
	}

	private static String[] keys(MethodNode method, List<Integer> indices) {
		Map<LabelNode, Integer> labels = new IdentityHashMap<>();
		for (int i = 0; i < method.instructions.size(); i++) {
			if (method.instructions.get(i) instanceof LabelNode label) labels.put(label, i);
		}
		String[] out = new String[indices.size()];
		for (int k = 0; k < out.length; k++) out[k] = key(method, indices.get(k), labels);
		return out;
	}

	/** Opcode and operands; a local by its name where the method names it; a jump by its opcode alone. */
	static String key(MethodNode method, int index, Map<LabelNode, Integer> labels) {
		AbstractInsnNode n = method.instructions.get(index);
		StringBuilder sb = new StringBuilder().append(n.getOpcode()).append(' ');
		if (n instanceof VarInsnNode v) sb.append(local(method, v.var, index + (v.getOpcode() >= Opcodes.ISTORE ? 1 : 0), labels));
		else if (n instanceof IincInsnNode inc) sb.append(local(method, inc.var, index, labels)).append(' ').append(inc.incr);
		else if (n instanceof MethodInsnNode c) sb.append(c.owner).append('.').append(c.name).append(c.desc);
		else if (n instanceof FieldInsnNode f) sb.append(f.owner).append('.').append(f.name).append(f.desc);
		else if (n instanceof TypeInsnNode t) sb.append(t.desc);
		else if (n instanceof LdcInsnNode l) sb.append(l.cst instanceof Type t ? t.getDescriptor() : l.cst.getClass().getSimpleName() + ":" + l.cst);
		else if (n instanceof IntInsnNode i) sb.append(i.operand);
		else if (n instanceof MultiANewArrayInsnNode mn) sb.append(mn.desc).append(mn.dims);
		else if (n instanceof InvokeDynamicInsnNode indy) sb.append(indy.name).append(indy.desc).append(indy.bsm.getOwner()).append(indy.bsm.getName());
		else if (n instanceof TableSwitchInsnNode table) sb.append(table.min).append(':').append(table.max);
		else if (n instanceof LookupSwitchInsnNode lookup) sb.append(lookup.keys);
		return sb.toString();
	}

	private static String local(MethodNode method, int slot, int at, Map<LabelNode, Integer> labels) {
		if (method.localVariables != null) {
			for (LocalVariableNode lv : method.localVariables) {
				if (lv.index != slot) continue;
				Integer start = labels.get(lv.start), end = labels.get(lv.end);
				if (start != null && end != null && start <= at && at < end) return lv.name + ":" + lv.desc;
			}
		}
		return "#" + slot;
	}
}
