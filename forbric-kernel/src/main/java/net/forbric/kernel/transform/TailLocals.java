/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * Gives a split method's tail back the primitive locals vanilla's tail holds.
 *
 * <p>{@link VanillaEarlyReturns} sends a folded guard's path back to a return of its own, so the tail is reached again only
 * by the paths vanilla sent there — after the body. But it keeps the tail as the fold declared it: the body's locals end
 * where the decompiler's {@code if} block ended, and the tail's frame drops them, because the guard's path used to arrive
 * without them. On vanilla those locals are in scope at the last return, and a Fabric mod's TAIL handler reads them:
 * Essential's emissive elytra layer asks for {@code @Local(ordinal = 4) int} at the tail of
 * {@code EquipmentLayerRenderer.renderLayers} (vanilla's {@code nextOrder}). On the merged tail Mixin found only the
 * three {@code int} arguments, MixinExtras dropped the target, and the optional injector went unattached without a word.
 *
 * <p>A local is given back only on evidence, all of it required: vanilla's own body (the class the platform-free mod was
 * compiled against) has a local of that name and descriptor in scope at its last return; on the split body every path
 * still into the tail leaves from inside that local's range with a value in its slot (the data flow, not the table, says
 * so); the slot is above every local the tail already declares, so what the tail held keeps its place and its ordinal;
 * and the type is primitive, so the frame names no class the verifier would have to load. The tail's frame then declares
 * it, a frame after the tail that was relative to the tail's is written out full first, and the table gets a range for it
 * over the tail. Nothing the method computes changes. {@code -Dforbric.tailLocals=off} leaves the tail as split.
 */
final class TailLocals {
	static final String PROPERTY = "forbric.tailLocals";

	private TailLocals() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Gives {@code method}'s tail the locals {@code vanilla}'s tail holds, as above; returns how many. */
	static int restore(String owner, MethodNode method, ClassNode vanilla) {
		if (!enabled() || vanilla == null || vanilla.methods == null || method.localVariables == null || method.localVariables.isEmpty()) return 0;
		MethodNode native_ = null;
		for (MethodNode candidate : vanilla.methods) if (candidate.name.equals(method.name) && candidate.desc.equals(method.desc)) native_ = candidate;
		if (native_ == null || native_.localVariables == null || native_.instructions == null) return 0;
		Set<String> held = heldAtTail(native_);
		if (held.isEmpty()) return 0;

		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
		if (tail == null) return 0;
		InsnList code = method.instructions;
		int at = code.indexOf(tail), runStart = at;
		FrameNode tailFrame = null;
		LabelNode first = null;
		for (AbstractInsnNode insn = tail.getPrevious(); insn != null && insn.getOpcode() < 0; insn = insn.getPrevious()) {
			runStart = code.indexOf(insn);
			if (insn instanceof FrameNode frame && tailFrame == null) tailFrame = frame;
			if (insn instanceof LabelNode label) first = label;
		}
		if (tailFrame == null || first == null) return 0;
		List<List<Object>> declared = VanillaEarlyReturns.stateAt(owner, method, tailFrame);
		if (declared == null) return 0;
		List<Object> slots = slots(declared.get(0));
		int highest = -1;
		for (int slot = 0; slot < slots.size(); slot++) if (slots.get(slot) != null && !Opcodes.TOP.equals(slots.get(slot))) highest = slot;

		Set<Long> edges = new HashSet<>();
		Frame<BasicValue>[] frames;
		try {
			frames = new Analyzer<>(new BasicInterpreter()) {
				@Override protected void newControlFlowEdge(int from, int to) { edges.add(((long) from << 32) | to); }
			}.analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return 0;
		}
		List<Integer> sources = new ArrayList<>();
		for (long edge : edges) {
			int from = (int) (edge >>> 32), to = (int) edge;
			if (to < runStart || to > at || (from >= runStart && from <= at)) continue;
			// The frame is the state BEFORE the instruction: one that writes a local is not read through it.
			if (frames[from] == null || code.get(from) instanceof VarInsnNode || code.get(from) instanceof IincInsnNode) return 0;
			sources.add(from);
		}
		if (sources.isEmpty()) return 0;

		TreeMap<Integer, LocalVariableNode> given = new TreeMap<>();
		Set<Integer> contested = new HashSet<>();
		for (LocalVariableNode local : method.localVariables) {
			if (local.index <= highest || !primitive(local.desc) || !held.contains(local.name + " " + local.desc)) continue;
			boolean everyPath = true;
			for (int from : sources) {
				Frame<BasicValue> frame = frames[from];
				int scoped = scopedAt(code, from);
				if (code.indexOf(local.start) > scoped || scoped >= code.indexOf(local.end) || local.index >= frame.getLocals()
						|| !sameSort(frame.getLocal(local.index), local.desc) || !declaredAt(owner, method, from, local)) {
					everyPath = false;
					break;
				}
			}
			if (!everyPath) continue;
			if (given.put(local.index, local) != null) contested.add(local.index);
		}
		for (int slot : contested) given.remove(slot);
		// Two given locals must not overlap (a long's second slot is its own).
		Integer previousEnd = null;
		for (Map.Entry<Integer, LocalVariableNode> entry : new ArrayList<>(given.entrySet())) {
			if (previousEnd != null && entry.getKey() < previousEnd) given.remove(entry.getKey());
			else previousEnd = entry.getKey() + Type.getType(entry.getValue().desc).getSize();
		}
		if (given.isEmpty()) return 0;

		// Frames after the tail may be written relative to its frame: decode them before it changes, and write them full.
		Map<FrameNode, List<List<Object>>> after = new java.util.LinkedHashMap<>();
		for (AbstractInsnNode insn = tail.getNext(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FrameNode frame) || frame.type == Opcodes.F_FULL || frame.type == Opcodes.F_NEW) continue;
			List<List<Object>> state = VanillaEarlyReturns.stateAt(owner, method, frame);
			if (state == null) return 0;
			after.put(frame, state);
		}
		for (Map.Entry<FrameNode, List<List<Object>>> entry : after.entrySet()) full(entry.getKey(), entry.getValue());

		for (Map.Entry<Integer, LocalVariableNode> entry : given.entrySet()) {
			Type type = Type.getType(entry.getValue().desc);
			while (slots.size() < entry.getKey() + type.getSize()) slots.add(Opcodes.TOP);
			slots.set(entry.getKey(), verificationType(type));
			if (type.getSize() == 2) slots.set(entry.getKey() + 1, Opcodes.TOP);
		}
		full(tailFrame, List.of(entries(slots), declared.get(1)));
		LabelNode end = new LabelNode();
		code.insert(tail, end);
		for (LocalVariableNode local : given.values()) {
			method.localVariables.add(new LocalVariableNode(local.name, local.desc, local.signature, first, end, local.index));
		}
		return given.size();
	}

	/**
	 * Where a path into the tail is in the table's scopes: its own instruction, or, for the {@code goto} the split put
	 * after the body's last instruction (which falls into it), that instruction — the split cut the ranges that ended at
	 * the tail to end before that {@code goto}.
	 */
	private static int scopedAt(InsnList code, int from) {
		AbstractInsnNode source = code.get(from);
		if (source.getOpcode() != Opcodes.GOTO) return from;
		AbstractInsnNode previous = source.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		if (previous == null) return from;
		int opcode = previous.getOpcode();
		boolean fallsIn = !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) && opcode != Opcodes.GOTO && opcode != Opcodes.ATHROW
				&& opcode != Opcodes.TABLESWITCH && opcode != Opcodes.LOOKUPSWITCH;
		return fallsIn ? code.indexOf(previous) : from;
	}

	/**
	 * Whether the verifier's own state at {@code from} types {@code local}'s slot as the local's type: the frame declared
	 * last before {@code from} (only a branch target carries one, so none lies between it and {@code from}), and the
	 * stores between them. The data flow can hold a value in a slot the declared frame dropped — javac and the merge's
	 * frame writer both drop a local whose scope ended, and ThrownEnderpearl.tick's path into its tail arrives with only
	 * {@code this} and the level declared — and the verifier reads the declared state, not the data flow.
	 */
	private static boolean declaredAt(String owner, MethodNode method, int from, LocalVariableNode local) {
		InsnList code = method.instructions;
		FrameNode last = null;
		for (AbstractInsnNode insn = code.get(from); insn != null; insn = insn.getPrevious()) {
			if (insn instanceof FrameNode frame) {
				last = frame;
				break;
			}
		}
		List<Object> slots;
		AbstractInsnNode start;
		if (last == null) {
			slots = slots(initialLocals(owner, method));
			start = code.getFirst();
		} else {
			List<List<Object>> state = VanillaEarlyReturns.stateAt(owner, method, last);
			if (state == null) return false;
			slots = slots(state.get(0));
			start = last;
		}
		for (AbstractInsnNode insn = start; insn != null && insn != code.get(from); insn = insn.getNext()) {
			if (!(insn instanceof VarInsnNode store)) continue;
			Object type = switch (store.getOpcode()) {
				case Opcodes.ISTORE -> Opcodes.INTEGER;
				case Opcodes.FSTORE -> Opcodes.FLOAT;
				case Opcodes.LSTORE -> Opcodes.LONG;
				case Opcodes.DSTORE -> Opcodes.DOUBLE;
				case Opcodes.ASTORE -> "java/lang/Object";
				default -> null;
			};
			if (type == null) continue;
			int width = wide(type) ? 2 : 1;
			while (slots.size() < store.var + width) slots.add(Opcodes.TOP);
			slots.set(store.var, type);
			if (width == 2) slots.set(store.var + 1, Opcodes.TOP);
		}
		Type type = Type.getType(local.desc);
		return local.index < slots.size() && verificationType(type).equals(slots.get(local.index));
	}

	private static List<Object> initialLocals(String owner, MethodNode method) {
		List<Object> locals = new ArrayList<>();
		if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(owner);
		for (Type parameter : Type.getArgumentTypes(method.desc)) {
			locals.add(switch (parameter.getSort()) {
				case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER;
				case Type.FLOAT -> Opcodes.FLOAT;
				case Type.LONG -> Opcodes.LONG;
				case Type.DOUBLE -> Opcodes.DOUBLE;
				default -> parameter.getSort() == Type.ARRAY ? parameter.getDescriptor() : parameter.getInternalName();
			});
		}
		return locals;
	}

	/** {@code name + " " + desc} of every local vanilla's table has in scope at its last return. */
	private static Set<String> heldAtTail(MethodNode vanilla) {
		Set<String> held = new HashSet<>();
		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(vanilla);
		if (tail == null) return held;
		int at = vanilla.instructions.indexOf(tail);
		for (LocalVariableNode local : vanilla.localVariables) {
			if (vanilla.instructions.indexOf(local.start) <= at && at < vanilla.instructions.indexOf(local.end)) held.add(local.name + " " + local.desc);
		}
		return held;
	}

	private static boolean primitive(String desc) {
		return desc.length() == 1 && "ZBCSIFJD".indexOf(desc.charAt(0)) >= 0;
	}

	private static boolean sameSort(BasicValue value, String desc) {
		if (value == null || value == BasicValue.UNINITIALIZED_VALUE) return false;
		return switch (desc.charAt(0)) {
			case 'Z', 'B', 'C', 'S', 'I' -> value == BasicValue.INT_VALUE;
			case 'F' -> value == BasicValue.FLOAT_VALUE;
			case 'J' -> value == BasicValue.LONG_VALUE;
			case 'D' -> value == BasicValue.DOUBLE_VALUE;
			default -> false;
		};
	}

	private static Object verificationType(Type type) {
		return switch (type.getSort()) {
			case Type.FLOAT -> Opcodes.FLOAT;
			case Type.LONG -> Opcodes.LONG;
			case Type.DOUBLE -> Opcodes.DOUBLE;
			default -> Opcodes.INTEGER;
		};
	}

	/** A frame's local entries, one per slot (a long or double's second slot TOP). */
	private static List<Object> slots(List<Object> entries) {
		List<Object> slots = new ArrayList<>();
		for (Object entry : entries) {
			slots.add(entry);
			if (Opcodes.LONG.equals(entry) || Opcodes.DOUBLE.equals(entry)) slots.add(Opcodes.TOP);
		}
		return slots;
	}

	/** Back from slots to a frame's entries, trailing TOPs dropped. */
	private static List<Object> entries(List<Object> slots) {
		int last = slots.size() - 1;
		while (last >= 0 && Opcodes.TOP.equals(slots.get(last)) && !(last > 0 && wide(slots.get(last - 1)))) last--;
		List<Object> entries = new ArrayList<>();
		for (int slot = 0; slot <= last; slot++) {
			Object entry = slots.get(slot);
			entries.add(entry);
			if (wide(entry)) slot++;
		}
		return entries;
	}

	private static boolean wide(Object entry) {
		return Opcodes.LONG.equals(entry) || Opcodes.DOUBLE.equals(entry);
	}

	private static void full(FrameNode frame, List<List<Object>> state) {
		frame.type = Opcodes.F_FULL;
		frame.local = new ArrayList<>(state.get(0));
		frame.stack = new ArrayList<>(state.get(1));
	}
}
