/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.kernel.util.Caught;

/**
 * Where a method's native returns went, when a carrier joined several of them into one hook call placed just before
 * the current body's final return — the shape NeoForge gives {@code KeyboardHandler.keyPress}, whose exits all reach
 * {@code ClientHooks.onKeyInput(event, action)}. A guest's {@code RETURN} ordinal counts the native returns, so it names
 * a return the current body may no longer have, or a different one.
 *
 * <p>The joined exits arrive at the JOIN: the straight-line run that ends in the hook, entered only at its first
 * instruction. Besides the hook's own operand loads it may hold other void calls fed by operand loads alone — another
 * ecosystem's twin of the hook that a merge kept in front of it — so the join may begin before the hook itself; its
 * first call ({@link #entry()}) is where "before the hook" now starts.
 *
 * <p>Everything here is read off the two bodies; no ordinal is assumed. The int parameter the hook receives is the
 * discriminator. A forward analysis of each body records, for every instruction, which of the constants that
 * parameter is compared with — or none of them — it can still equal there.
 * <ul>
 *   <li>A native return is JOINED when the straight-line run ending at it (its side effects, by operand origin) and the
 *       discriminator values reaching it occur once among the native returns and once among the edges into the
 *       hook's own run, and no other edge into that run can carry those values. Then "before the hook, with the
 *       discriminator among those values" is that return and nothing else.</li>
 *   <li>The native FINAL return is the current final return, which the joined hook now precedes. The native values
 *       reaching it bound the joined paths there; a path the carrier sends to the final return by a branch the
 *       discriminator does not separate stays unbounded.</li>
 * </ul>
 * Every other return, an opaque operand, a written discriminator or an unanalysable body maps nowhere.
 */
final class JoinedReturnExits {
	/**
	 * Which values of the method's discriminator parameter a relocated handler keeps: bit {@code i} is
	 * {@code constants[i]}, the last bit every value that is none of them.
	 */
	record Values(int parameter, int[] constants, BitSet allowed) {
		boolean all() {
			return allowed.cardinality() == constants.length + 1;
		}

		boolean other() {
			return allowed.get(constants.length);
		}

		@Override
		public String toString() {
			List<String> parts = new ArrayList<>();
			for (int i = 0; i < constants.length; i++) if (allowed.get(i)) parts.add(Integer.toString(constants[i]));
			if (other()) parts.add("any other");
			return String.join(", ", parts);
		}
	}

	/**
	 * A native early return the carrier sends into the join on a decision instead: natively the return is reached exactly when
	 * the {@code occurrence}-th call of {@code member} (the call right before the one conditional jump that leads to it) answers
	 * {@code nonZero} (true, or any non-zero int); in the current body that same call, on that same answer, jumps into the join
	 * (Minecraft.handleGlobalKeyPress in keyPress: a global key it handled returns natively, and reaches NeoForge's hook
	 * merged). The discriminator cannot tell such a path apart — it carries the very values that reach the final return.
	 */
	record DecisionExit(int ordinal, String member, int occurrence, boolean nonZero) {
	}

	private final int parameter;
	private final int[] constants;
	private final int nativeReturns;
	private final int currentReturns;
	private final BitSet finalValues;
	private final boolean finalBounded;
	private final Map<Integer, BitSet> joined;
	private final String entry;
	private final int entryOrdinal;
	private final List<DecisionExit> decisions;

	private JoinedReturnExits(int parameter, int[] constants, int nativeReturns, int currentReturns, BitSet finalValues,
			boolean finalBounded, Map<Integer, BitSet> joined, String entry, int entryOrdinal, List<DecisionExit> decisions) {
		this.decisions = List.copyOf(decisions);
		this.parameter = parameter;
		this.constants = constants;
		this.nativeReturns = nativeReturns;
		this.currentReturns = currentReturns;
		this.finalValues = finalValues;
		this.finalBounded = finalBounded;
		this.joined = joined;
		this.entry = entry;
		this.entryOrdinal = entryOrdinal;
	}

	/**
	 * The member ({@code Lowner;name(desc)}) of the join's first call: the hook itself, or a call the merge kept in front
	 * of it in the same join. A joined exit runs just before it.
	 */
	String entry() {
		return entry;
	}

	/** Which occurrence of {@link #entry()} in the current body the join's first call is (an {@code INVOKE} ordinal). */
	int entryOrdinal() {
		return entryOrdinal;
	}

	/**
	 * The native early returns the current body reaches the final return from on a decision (see {@link DecisionExit}): a
	 * handler of the native final return must not run on a path that took one. Empty where none was proved.
	 */
	List<DecisionExit> decisionExits() {
		return decisions;
	}

	/** How many returns the native body has: the count a guest's {@code RETURN} ordinal was written against. */
	int nativeReturns() {
		return nativeReturns;
	}

	/** How many returns the current body has. */
	int currentReturns() {
		return currentReturns;
	}

	/** Whether native return {@code ordinal} is the native final return (Mixin's TAIL). */
	boolean isFinal(int ordinal) {
		return ordinal == nativeReturns - 1;
	}

	/**
	 * The discriminator values that reach the native final return, when the current final return is reached with
	 * others too — the guard a handler of the native final return needs there; null when none is needed.
	 */
	Values finalGuard() {
		return finalBounded ? null : new Values(parameter, constants, (BitSet) finalValues.clone());
	}

	/** Whether any discriminator value reaches the native final return at all. */
	boolean finalReached() {
		return !finalValues.isEmpty();
	}

	/** For a native non-final return the carrier joined into the hook: the values that select it there; else null. */
	Values joined(int ordinal) {
		BitSet values = joined.get(ordinal);
		return values == null ? null : new Values(parameter, constants, (BitSet) values.clone());
	}

	/**
	 * Reads both bodies of one method. Null unless the current body calls {@code hook} once, immediately before its final
	 * return (or a goto to it, over the return blocks a split placed in front of it), with exactly one int argument loaded
	 * from a parameter neither body writes, the join ending in it holds nothing but operand loads and void calls they
	 * alone feed, and the native body does not call {@code hook} (a guest
	 * compiled against the joined body counts its returns already).
	 */
	static JoinedReturnExits of(ClassNode originalOwner, MethodNode original, ClassNode currentOwner, MethodNode current,
			String hook) {
		if (originalOwner == null || original == null || currentOwner == null || current == null || hook == null
				|| !original.desc.equals(current.desc) || ((original.access ^ current.access) & Opcodes.ACC_STATIC) != 0
				|| calls(original, hook).size() != 0) return null;
		List<MethodInsnNode> hooks = calls(current, hook);
		if (hooks.size() != 1) return null;
		MethodInsnNode call = hooks.getFirst();
		int returnOpcode = Type.getReturnType(current.desc).getOpcode(Opcodes.IRETURN);
		List<AbstractInsnNode> originalReturns = returns(original, returnOpcode), currentReturns = returns(current, returnOpcode);
		// The hook precedes the final return: next to it, or across the blocks a split placed in front of the tail
		// (VanillaEarlyReturns gives a folded early return its own block there, which the hook's run jumps over).
		if (originalReturns.isEmpty() || currentReturns.isEmpty() || reached(call.getNext()) != currentReturns.getLast()) return null;
		try {
			Frame<SourceValue>[] currentFrames = new Analyzer<>(new SourceInterpreter()).analyze(currentOwner.name, current);
			Frame<SourceValue>[] originalFrames = new Analyzer<>(new SourceInterpreter()).analyze(originalOwner.name, original);
			int slot = discriminator(current, currentFrames, call);
			int parameter = parameterAt(current, slot);
			if (parameter < 0 || writes(original, slot) || writes(current, slot)) return null;
			TreeSet<Integer> universe = new TreeSet<>();
			compared(original, originalFrames, slot, universe);
			compared(current, currentFrames, slot, universe);
			int[] constants = universe.stream().mapToInt(Integer::intValue).toArray();
			Flow before = new Flow(original, originalFrames, slot, constants), after = new Flow(current, currentFrames, slot, constants);

			// The join: the run in front of the hook, entered only at its first instruction, holding operand loads and
			// void calls fed by them alone — nothing that computes a value or touches state before the hook but calls.
			List<AbstractInsnNode> hookRun = run(current, call, false);
			MethodInsnNode first = joinEntry(hookRun, call);
			if (first == null) return null;
			int entryOrdinal = calls(current, CallOccurrenceAlignment.member(first)).indexOf(first);
			AbstractInsnNode hookEntry = hookRun.isEmpty() ? call : hookRun.getFirst();
			Set<AbstractInsnNode> inside = Collections.newSetFromMap(new IdentityHashMap<>());
			inside.addAll(hookRun);
			record Arrival(BitSet values, List<String> effects) { }
			List<Arrival> arrivals = new ArrayList<>();
			var currentEffects = CallOccurrenceAlignment.effects(currentOwner.name, current);
			for (Flow.Edge edge : after.edges()) {
				if (edge.to() != hookEntry || inside.contains(edge.from())) continue;
				arrivals.add(new Arrival(edge.values(), currentEffects.apply(feeding(current, edge.from()))));
			}

			record Exit(BitSet values, List<String> effects) { }
			List<Exit> exits = new ArrayList<>();
			var originalEffects = CallOccurrenceAlignment.effects(originalOwner.name, original);
			for (AbstractInsnNode exit : originalReturns) {
				exits.add(new Exit(before.at(exit), originalEffects.apply(run(original, exit, false))));
			}
			Map<Integer, BitSet> joined = new HashMap<>();
			for (int ordinal = 0; ordinal + 1 < exits.size(); ordinal++) {
				Exit exit = exits.get(ordinal);
				if (exit.effects() == null || exit.values().isEmpty()) continue;
				int twins = 0;
				for (Exit other : exits) if (exit.effects().equals(other.effects()) && exit.values().equals(other.values())) twins++;
				if (twins != 1) continue;
				int matches = 0, overlapping = 0;
				for (Arrival arrival : arrivals) {
					if (arrival.values().intersects(exit.values())) overlapping++;
					if (exit.effects().equals(arrival.effects()) && exit.values().equals(arrival.values())) matches++;
				}
				// One arrival is this exit's run, and only it can carry these values to the hook.
				if (matches == 1 && overlapping == 1) joined.put(ordinal, exit.values());
			}
			BitSet finalValues = before.at(originalReturns.getLast()), currentFinal = (BitSet) after.at(currentReturns.getLast()).clone();
			currentFinal.andNot(finalValues);
			List<DecisionExit> decisions = new ArrayList<>();
			for (int ordinal = 0; ordinal + 1 < originalReturns.size(); ordinal++) {
				if (joined.containsKey(ordinal)) continue;
				DecisionExit decision = decisionExit(original, current, originalReturns.get(ordinal), ordinal, hookEntry);
				if (decision != null) decisions.add(decision);
			}
			return new JoinedReturnExits(parameter, constants, originalReturns.size(), currentReturns.size(), finalValues,
					currentFinal.isEmpty(), joined, CallOccurrenceAlignment.member(first), entryOrdinal, decisions);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
	}

	/**
	 * {@code exit} as a native return reached on one call's answer, which the current body sends into the join at
	 * {@code hookEntry} instead; null otherwise. Natively: the return alone in its block, entered only by falling past (or
	 * jumping from) one {@code IFEQ}/{@code IFNE} that directly tests a call's answer. Currently: the same occurrence of that
	 * call (both bodies make it equally often), directly tested by one {@code IFEQ}/{@code IFNE} whose way for that answer is
	 * the join's first instruction — and no backward jump in the current body, so the call is asked at most once a call.
	 */
	private static DecisionExit decisionExit(MethodNode original, MethodNode current, AbstractInsnNode exit, int ordinal, AbstractInsnNode hookEntry) {
		for (AbstractInsnNode instruction : current.instructions)
			if (instruction instanceof JumpInsnNode back && current.instructions.indexOf(back.label) <= current.instructions.indexOf(back)) return null;
		List<JumpInsnNode> into = new ArrayList<>();
		for (AbstractInsnNode instruction : original.instructions)
			if (instruction instanceof JumpInsnNode jump && executable(jump.label) == exit) into.add(jump);
		if (original.tryCatchBlocks != null) for (TryCatchBlockNode block : original.tryCatchBlocks) if (executable(block.handler) == exit) return null;
		AbstractInsnNode before = exit.getPrevious();
		while (before != null && before.getOpcode() < 0) before = before.getPrevious();
		JumpInsnNode test;
		boolean nonZero;
		if (into.isEmpty() && before instanceof JumpInsnNode fallen && (fallen.getOpcode() == Opcodes.IFEQ || fallen.getOpcode() == Opcodes.IFNE)) {
			test = fallen;
			nonZero = fallen.getOpcode() == Opcodes.IFEQ;   // falling past IFEQ: the value was not zero
		} else if (into.size() == 1 && (into.getFirst().getOpcode() == Opcodes.IFEQ || into.getFirst().getOpcode() == Opcodes.IFNE)
				&& before != null && (Flow.transfers(before) && !(before instanceof JumpInsnNode conditional && conditional.getOpcode() != Opcodes.GOTO))) {
			test = into.getFirst();
			nonZero = test.getOpcode() == Opcodes.IFNE;
		} else return null;
		AbstractInsnNode producer = test.getPrevious();
		while (producer != null && producer.getOpcode() < 0) producer = producer.getPrevious();
		if (!(producer instanceof MethodInsnNode call) || Type.getReturnType(call.desc).getSort() == Type.VOID) return null;
		String member = CallOccurrenceAlignment.member(call);
		List<MethodInsnNode> nativeCalls = calls(original, member), currentCalls = calls(current, member);
		int occurrence = nativeCalls.indexOf(call);
		if (occurrence < 0 || nativeCalls.size() != currentCalls.size()) return null;
		AbstractInsnNode next = executable(currentCalls.get(occurrence).getNext());
		if (!(next instanceof JumpInsnNode decided) || decided.getOpcode() != Opcodes.IFEQ && decided.getOpcode() != Opcodes.IFNE) return null;
		boolean jumps = (decided.getOpcode() == Opcodes.IFNE) == nonZero;
		return reached(jumps ? decided.label : decided.getNext()) == hookEntry ? new DecisionExit(ordinal, member, occurrence, nonZero) : null;
	}

	/**
	 * The first call of the join whose run in front of {@code hook} is {@code run}: {@code hook} itself when the run is its
	 * operand loads alone; else the earliest of the void calls in it, each consuming exactly the loads in front of it.
	 * Null when the run holds anything else.
	 */
	private static MethodInsnNode joinEntry(List<AbstractInsnNode> run, MethodInsnNode hook) {
		MethodInsnNode first = null;
		int pending = 0;
		for (AbstractInsnNode instruction : run) {
			if (instruction instanceof VarInsnNode load && load.getOpcode() <= Opcodes.ALOAD) {
				pending++;
			} else if (instruction instanceof MethodInsnNode call && Type.getReturnType(call.desc) == Type.VOID_TYPE
					&& pending == Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1)) {
				if (first == null) first = call;
				pending = 0;
			} else {
				return null;
			}
		}
		return first == null ? hook : first;
	}

	/** The local slot of the hook's one int argument that is a parameter load, or -1. */
	private static int discriminator(MethodNode method, Frame<SourceValue>[] frames, MethodInsnNode call) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
		Type[] arguments = Type.getArgumentTypes(call.desc);
		if (frame == null || frame.getStackSize() < arguments.length) return -1;
		int found = -1;
		for (int i = 0; i < arguments.length; i++) {
			if (arguments[i].getSort() != Type.INT) continue;
			SourceValue value = frame.getStack(frame.getStackSize() - arguments.length + i);
			if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD) continue;
			if (found >= 0) return -1;
			found = load.var;
		}
		return found;
	}

	/** Which parameter (0-based, receiver excluded) occupies {@code slot}, when it is an int; else -1. */
	private static int parameterAt(MethodNode method, int slot) {
		int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		Type[] parameters = Type.getArgumentTypes(method.desc);
		for (int i = 0; i < parameters.length; i++) {
			if (local == slot) return parameters[i].getSort() == Type.INT ? i : -1;
			local += parameters[i].getSize();
		}
		return -1;
	}

	private static boolean writes(MethodNode method, int slot) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode store && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE
					&& store.var == slot) return true;
			if (instruction instanceof IincInsnNode increment && increment.var == slot) return true;
		}
		return false;
	}

	/** Every constant the discriminator is tested for equality with; a zero test compares with 0. */
	private static void compared(MethodNode method, Frame<SourceValue>[] frames, int slot, Set<Integer> universe) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode branch) {
				Integer constant = Flow.tested(method, frames, branch, slot);
				if (constant != null) universe.add(constant);
			}
		}
	}

	private static List<MethodInsnNode> calls(MethodNode method, String member) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof MethodInsnNode call && CallOccurrenceAlignment.member(call).equals(member)) found.add(call);
		return found;
	}

	private static List<AbstractInsnNode> returns(MethodNode method, int opcode) {
		List<AbstractInsnNode> found = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() == opcode) found.add(instruction);
		return found;
	}

	static AbstractInsnNode executable(AbstractInsnNode node) {
		while (node != null && node.getOpcode() < 0) node = node.getNext();
		return node;
	}

	/** The instruction straight-line execution reaches from {@code node}: past labels and frames, and through gotos. */
	static AbstractInsnNode reached(AbstractInsnNode node) {
		AbstractInsnNode at = executable(node);
		for (int guard = 0; at instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO && guard < 16; guard++) at = executable(jump.label);
		return at;
	}

	/** The run that feeds an arrival at the hook: what precedes a goto, a fall-through's own run, nothing for a branch. */
	private static List<AbstractInsnNode> feeding(MethodNode method, AbstractInsnNode from) {
		if (from instanceof JumpInsnNode jump) return jump.getOpcode() == Opcodes.GOTO ? run(method, from, false) : List.of();
		return run(method, from, true);
	}

	/**
	 * The straight-line instructions ending at {@code end} ({@code end} itself when {@code inclusive}): back to the
	 * nearest branch, jump, return, throw or jump-target label, none of which it contains.
	 */
	static List<AbstractInsnNode> run(MethodNode method, AbstractInsnNode end, boolean inclusive) {
		Set<LabelNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump) targets.add(jump.label);
			else if (instruction instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			else if (instruction instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) targets.add(block.handler);
		List<AbstractInsnNode> run = new ArrayList<>();
		for (AbstractInsnNode node = inclusive ? end : end.getPrevious(); node != null; node = node.getPrevious()) {
			if (node instanceof LabelNode label) {
				if (targets.contains(label)) break;
				continue;
			}
			if (node.getOpcode() < 0) continue;
			if (Flow.transfers(node)) break;
			run.add(0, node);
		}
		return run;
	}

	/** Per instruction, the discriminator values some path can still have there. */
	private static final class Flow {
		record Edge(AbstractInsnNode from, AbstractInsnNode to, BitSet values) { }

		private final MethodNode method;
		private final Frame<SourceValue>[] frames;
		private final int slot;
		private final int[] constants;
		private final BitSet[] in;

		Flow(MethodNode method, Frame<SourceValue>[] frames, int slot, int[] constants) {
			this.method = method;
			this.frames = frames;
			this.slot = slot;
			this.constants = constants;
			this.in = new BitSet[method.instructions.size()];
			AbstractInsnNode first = executable(method.instructions.getFirst());
			if (first == null) throw new IllegalStateException("no code");
			BitSet all = new BitSet();
			all.set(0, constants.length + 1);
			in[index(first)] = all;
			Deque<AbstractInsnNode> work = new ArrayDeque<>(List.of(first));
			while (!work.isEmpty()) {
				AbstractInsnNode instruction = work.poll();
				for (Edge edge : successors(instruction, in[index(instruction)])) {
					BitSet into = in[index(edge.to())];
					if (into == null) in[index(edge.to())] = into = new BitSet();
					BitSet before = (BitSet) into.clone();
					into.or(edge.values());
					if (!into.equals(before)) work.add(edge.to());
				}
			}
		}

		/** The values with which some path reaches {@code instruction}; empty when none does. */
		BitSet at(AbstractInsnNode instruction) {
			BitSet values = in[index(instruction)];
			return values == null ? new BitSet() : values;
		}

		List<Edge> edges() {
			List<Edge> edges = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) {
				BitSet values = in[index(instruction)];
				if (values != null && instruction.getOpcode() >= 0) edges.addAll(successors(instruction, values));
			}
			return edges;
		}

		private int index(AbstractInsnNode instruction) {
			return method.instructions.indexOf(instruction);
		}

		private List<Edge> successors(AbstractInsnNode instruction, BitSet values) {
			List<Edge> edges = new ArrayList<>();
			int opcode = instruction.getOpcode();
			if (instruction instanceof JumpInsnNode jump) {
				if (opcode == Opcodes.JSR) throw new IllegalStateException("subroutine");
				if (opcode == Opcodes.GOTO) add(edges, instruction, jump.label, values);
				else {
					BitSet taken = (BitSet) values.clone(), falls = (BitSet) values.clone();
					Integer constant = tested(method, frames, jump, slot);
					if (constant != null && (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE
							|| opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE)) {
						BitSet equal = new BitSet();
						equal.set(Arrays.binarySearch(constants, constant));
						boolean whenEqual = opcode == Opcodes.IFEQ || opcode == Opcodes.IF_ICMPEQ;
						(whenEqual ? taken : falls).and(equal);
						(whenEqual ? falls : taken).andNot(equal);
					}
					add(edges, instruction, jump.label, taken);
					add(edges, instruction, jump.getNext(), falls);
				}
			} else if (instruction instanceof TableSwitchInsnNode table) {
				add(edges, instruction, table.dflt, values);
				for (LabelNode label : table.labels) add(edges, instruction, label, values);
			} else if (instruction instanceof LookupSwitchInsnNode lookup) {
				add(edges, instruction, lookup.dflt, values);
				for (LabelNode label : lookup.labels) add(edges, instruction, label, values);
			} else if (!(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW)) {
				add(edges, instruction, instruction.getNext(), values);
			}
			if (method.tryCatchBlocks != null) {
				int at = index(instruction);
				for (TryCatchBlockNode block : method.tryCatchBlocks) {
					if (index(block.start) <= at && at < index(block.end)) add(edges, instruction, block.handler, values);
				}
			}
			return edges;
		}

		private static void add(List<Edge> edges, AbstractInsnNode from, AbstractInsnNode to, BitSet values) {
			AbstractInsnNode target = executable(to);
			if (target != null && !values.isEmpty()) edges.add(new Edge(from, target, values));
		}

		static boolean transfers(AbstractInsnNode instruction) {
			int opcode = instruction.getOpcode();
			return instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
					|| instruction instanceof LookupSwitchInsnNode || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
					|| opcode == Opcodes.ATHROW;
		}

		/** The constant {@code branch} tests the discriminator for equality with, or null when it tests something else. */
		static Integer tested(MethodNode method, Frame<SourceValue>[] frames, JumpInsnNode branch, int slot) {
			Frame<SourceValue> frame = frames[method.instructions.indexOf(branch)];
			if (frame == null) return null;
			int opcode = branch.getOpcode(), top = frame.getStackSize();
			if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) return top >= 1 && loads(frame.getStack(top - 1), slot) ? 0 : null;
			if (opcode != Opcodes.IF_ICMPEQ && opcode != Opcodes.IF_ICMPNE || top < 2) return null;
			SourceValue a = frame.getStack(top - 2), b = frame.getStack(top - 1);
			if (loads(a, slot)) return constant(b);
			if (loads(b, slot)) return constant(a);
			return null;
		}

		private static boolean loads(SourceValue value, int slot) {
			return value.insns.size() == 1 && value.insns.iterator().next() instanceof VarInsnNode load
					&& load.getOpcode() == Opcodes.ILOAD && load.var == slot;
		}

		private static Integer constant(SourceValue value) {
			if (value.insns.size() != 1) return null;
			AbstractInsnNode producer = value.insns.iterator().next();
			int opcode = producer.getOpcode();
			if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
			if (producer instanceof IntInsnNode integer && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return integer.operand;
			if (producer instanceof LdcInsnNode literal && literal.cst instanceof Integer number) return number;
			return null;
		}
	}
}
