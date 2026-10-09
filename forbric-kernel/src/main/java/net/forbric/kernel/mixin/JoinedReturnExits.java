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

/**
 * Where a method's native returns went, when a carrier joined several of them into one hook call placed just before
 * the current body's final return — the shape NeoForge gives {@code KeyboardHandler.keyPress}, whose exits all reach
 * {@code ClientHooks.onKeyInput(event, action)}. A guest's {@code RETURN} ordinal counts the native returns, so it names
 * a return the current body may no longer have, or a different one.
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

	private final int parameter;
	private final int[] constants;
	private final int nativeReturns;
	private final int currentReturns;
	private final BitSet finalValues;
	private final boolean finalBounded;
	private final Map<Integer, BitSet> joined;

	private JoinedReturnExits(int parameter, int[] constants, int nativeReturns, int currentReturns, BitSet finalValues,
			boolean finalBounded, Map<Integer, BitSet> joined) {
		this.parameter = parameter;
		this.constants = constants;
		this.nativeReturns = nativeReturns;
		this.currentReturns = currentReturns;
		this.finalValues = finalValues;
		this.finalBounded = finalBounded;
		this.joined = joined;
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
	 * return, with exactly one int argument loaded from a parameter neither body writes, and the native body does not
	 * call {@code hook} (a guest compiled against the joined body counts its returns already).
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
		if (originalReturns.isEmpty() || currentReturns.isEmpty() || executable(call.getNext()) != currentReturns.getLast()) return null;
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

			// The hook's own run: the pure operand loads in front of it, entered only at its first instruction.
			List<AbstractInsnNode> hookRun = run(current, call, false);
			for (AbstractInsnNode operand : hookRun) if (!(operand instanceof VarInsnNode load && load.getOpcode() <= Opcodes.ALOAD)) return null;
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
			return new JoinedReturnExits(parameter, constants, originalReturns.size(), currentReturns.size(), finalValues,
					currentFinal.isEmpty(), joined);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return null;
		}
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
