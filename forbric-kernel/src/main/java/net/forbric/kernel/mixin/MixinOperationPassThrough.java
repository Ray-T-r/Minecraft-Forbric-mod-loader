/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Whether a {@code @WrapOperation} handler hands its original, at given operand positions, exactly the operands it was
 * itself handed — the proof an adapter needs before it gives the handler an original that takes those operands from the
 * call site instead of from the handler ({@code KernelWrapOperations.reordered} with positions it does not map).
 *
 * <p>Proved by data flow over the handler, never by its instruction sequence: every {@code Operation.call} is made on the
 * handler's own Operation parameter, which goes nowhere else; its argument array is created for that call, with the
 * operand count as its length, filled before the call on a straight path, and passed nowhere else; and at each listed
 * position it holds the handler's parameter for that operand (boxed, for a primitive), whose slot no path reassigns —
 * javac's {@code original.call(block)}, a copy kept in a local first, a Kotlin-style array in a temporary all qualify.
 * A handler that passes another value, an Operation that escapes, or an array built anywhere else is not proved. A
 * handler that never calls its original passes nothing on and is proved trivially.
 */
final class MixinOperationPassThrough {
	private static final String OPERATION = MixinWrapOperationShim.OPERATION;

	private MixinOperationPassThrough() {
	}

	/**
	 * @param owner      the mixin class
	 * @param operation  the handler parameter (index) that is the Operation
	 * @param operands   how many operands the wrapped call has (receiver included): the length of every argument array
	 * @param fixed      the operand positions whose handler parameter (index = position) must be passed on unchanged
	 */
	static boolean proves(String owner, MethodNode handler, int operation, int operands, Set<Integer> fixed) {
		if (handler == null || handler.instructions == null) return false;
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (operation < 0 || operation >= parameters.length || !parameters[operation].getDescriptor().equals("L" + OPERATION + ";")) return false;
		for (int position : fixed) if (position < 0 || position >= operation) return false;
		Uses uses = new Uses();
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(uses).analyze(owner, handler);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return false;
		}
		int[] slots = new int[parameters.length];
		int slot = (handler.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (int i = 0; i < parameters.length; i++) { slots[i] = slot; slot += parameters[i].getSize(); }
		AbstractInsnNode op = uses.parameters.get(slots[operation]);
		if (op == null) return false;
		List<MethodInsnNode> calls = new ArrayList<>();
		for (var use : uses.values.entrySet()) {
			if (!use.getValue().insns.contains(op)) continue;
			// Kotlin's parameter null check reads the Operation and keeps nothing of it.
			if (use.getKey().insn() instanceof MethodInsnNode check && check.getOpcode() == Opcodes.INVOKESTATIC
					&& check.owner.equals("kotlin/jvm/internal/Intrinsics") && check.name.startsWith("checkNotNull")) continue;
			// The Operation goes nowhere but the receiver slot of its own call.
			if (!(use.getKey().insn() instanceof MethodInsnNode call) || use.getKey().input() != 0 || !isCall(call)) return false;
			if (use.getValue().insns.size() != 1) return false;
			calls.add(call);
		}
		for (var use : uses.values.entrySet()) {
			// Any call made on an Operation value that is not purely the parameter is not this handler's original.
			if (use.getKey().insn() instanceof MethodInsnNode call && isCall(call) && use.getKey().input() == 0 && !calls.contains(call)) return false;
		}
		for (MethodInsnNode call : calls) {
			SourceValue array = uses.values.get(new Use(call, 1));
			if (array == null || array.insns.size() != 1 || !(array.insns.iterator().next() instanceof TypeInsnNode created)
					|| created.getOpcode() != Opcodes.ANEWARRAY) return false;
			SourceValue length = uses.values.get(new Use(created, 0));
			if (length == null || length.insns.size() != 1 || !Integer.valueOf(operands).equals(constant(length.insns.iterator().next()))) return false;
			int from = handler.instructions.indexOf(created), to = handler.instructions.indexOf(call);
			if (from > to || !straight(handler, from, to)) return false;
			Map<Integer, SourceValue> stored = new HashMap<>();
			for (var use : uses.values.entrySet()) {
				if (!use.getValue().insns.contains(created)) continue;
				AbstractInsnNode consumer = use.getKey().insn();
				if (use.getValue().insns.size() != 1) return false;
				if (consumer == call && use.getKey().input() == 1) continue;
				if (consumer.getOpcode() == Opcodes.ARRAYLENGTH || consumer.getOpcode() == Opcodes.AALOAD && use.getKey().input() == 0) continue;
				if (consumer.getOpcode() != Opcodes.AASTORE || use.getKey().input() != 0) return false;   // the array escapes
				int at = handler.instructions.indexOf(consumer);
				if (at < from || at > to) return false;
				SourceValue index = uses.values.get(new Use(consumer, 1));
				Integer position = index == null || index.insns.size() != 1 ? null : constant(index.insns.iterator().next());
				if (position == null) return false;
				if (stored.put(position, uses.values.get(new Use(consumer, 2))) != null && fixed.contains(position)) return false;
			}
			for (int position : fixed) {
				SourceValue value = stored.get(position);
				if (value == null || value.insns.size() != 1) return false;
				AbstractInsnNode producer = value.insns.iterator().next();
				AbstractInsnNode parameter = uses.parameters.get(slots[position]);
				if (producer == parameter && parameters[position].getSort() >= Type.ARRAY) continue;
				// A primitive operand is boxed on its way into the array: valueOf(the parameter itself).
				if (producer instanceof MethodInsnNode box && box.getOpcode() == Opcodes.INVOKESTATIC && box.name.equals("valueOf")
						&& Type.getArgumentTypes(box.desc).length == 1 && Type.getArgumentTypes(box.desc)[0].equals(parameters[position])) {
					SourceValue boxed = uses.values.get(new Use(box, 0));
					if (boxed != null && boxed.insns.size() == 1 && boxed.insns.iterator().next() == parameter) continue;
				}
				return false;
			}
		}
		return frames != null;
	}

	private static boolean isCall(MethodInsnNode call) {
		return call.owner.equals(OPERATION) && call.name.equals("call") && call.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;");
	}

	private static Integer constant(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (insn instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
		return null;
	}

	/** Nothing between the two instructions branches, leaves, or is a place another path can enter. */
	private static boolean straight(MethodNode method, int from, int to) {
		Set<LabelNode> entered = new HashSet<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof JumpInsnNode jump) entered.add(jump.label);
			else if (insn instanceof TableSwitchInsnNode table) { entered.add(table.dflt); entered.addAll(table.labels); }
			else if (insn instanceof LookupSwitchInsnNode lookup) { entered.add(lookup.dflt); entered.addAll(lookup.labels); }
		}
		for (TryCatchBlockNode block : method.tryCatchBlocks) entered.add(block.handler);
		for (int i = from + 1; i < to; i++) {
			AbstractInsnNode insn = method.instructions.get(i);
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode
					|| insn.getOpcode() == Opcodes.ATHROW || insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN
					|| insn instanceof LabelNode label && entered.contains(label)) return false;
		}
		return true;
	}

	/** One input of one instruction. */
	private record Use(AbstractInsnNode insn, int input) {
		@Override public boolean equals(Object other) { return other instanceof Use use && use.insn == insn && use.input == input; }
		@Override public int hashCode() { return System.identityHashCode(insn) * 31 + input; }
	}

	/** Sources that see through loads, stores and copies, and the values each instruction consumed. */
	private static final class Uses extends SourceInterpreter {
		final Map<Integer, AbstractInsnNode> parameters = new HashMap<>();
		final Map<Use, SourceValue> values = new HashMap<>();

		Uses() { super(Opcodes.ASM9); }

		@Override public SourceValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
			VarInsnNode marker = new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local);
			parameters.put(local, marker);
			return new SourceValue(type.getSize(), marker);
		}
		@Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return value; }
		@Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) { record(insn, 0, value); return super.unaryOperation(insn, value); }
		@Override public SourceValue binaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b) { record(insn, 0, a); record(insn, 1, b); return super.binaryOperation(insn, a, b); }
		@Override public SourceValue ternaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b, SourceValue c) { record(insn, 0, a); record(insn, 1, b); record(insn, 2, c); return super.ternaryOperation(insn, a, b, c); }
		@Override public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> inputs) { for (int i = 0; i < inputs.size(); i++) record(insn, i, inputs.get(i)); return super.naryOperation(insn, inputs); }
		@Override public void returnOperation(AbstractInsnNode insn, SourceValue value, SourceValue expected) { record(insn, 0, value); }

		private void record(AbstractInsnNode insn, int input, SourceValue value) {
			values.merge(new Use(insn, input), value, (a, b) -> {
				Set<AbstractInsnNode> union = new HashSet<>(a.insns);
				union.addAll(b.insns);
				return new SourceValue(Math.max(a.size, b.size), union);
			});
		}
	}
}
