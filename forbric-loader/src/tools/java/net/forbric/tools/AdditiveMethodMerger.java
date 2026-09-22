/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * A deliberately small three-input merge: both sides may prepend stack-neutral, static void hook calls to
 * an otherwise EXACT vanilla body. The original body may contain branches, switches, lambdas and handlers:
 * their operands, control-flow targets and exception regions must all agree, including local-variable slots.
 * No local renumbering, arbitrary instruction splicing, constructor merging or return-value composition is
 * attempted. A refusal leaves the existing merge decision intact.
 *
 * <p>The accepted prefixes can load only unchanged method parameters and literal values. They cannot write
 * locals, create helpers or retain stack values across calls. They execute outside the original handlers,
 * base first, other second. This establishes bytecode composition invariants, NOT commutativity of mod side
 * effects: void hooks can still throw or mutate their arguments. Functional tests remain necessary.
 */
final class AdditiveMethodMerger {

	private AdditiveMethodMerger() { }

	record Result(MethodNode method, String reason, int baseCalls, int otherCalls) {
		boolean accepted() { return method != null; }
	}

	static Result merge(MethodNode vanilla, MethodNode base, MethodNode other,
			String basePackage, String otherPackage) {
		if (vanilla == null) return refused("no vanilla method");
		if (vanilla.name.startsWith("<")) return refused("constructor or class initializer");
		if (!sameHeader(vanilla, base) || !sameHeader(vanilla, other)) {
			return refused("method descriptor or execution flags differ");
		}
		List<AbstractInsnNode> original = code(vanilla);
		List<AbstractInsnNode> baseCode = code(base);
		List<AbstractInsnNode> otherCode = code(other);
		if (original.isEmpty()) return refused("no vanilla instructions");
		int baseAdded = baseCode.size() - original.size();
		int otherAdded = otherCode.size() - original.size();
		if (baseAdded <= 0 || otherAdded <= 0) return refused("both sides must add an entry prefix");
		Object expected = signature(vanilla, 0);
		if (!expected.equals(signature(base, baseAdded))) return refused("base changes vanilla operands, control flow or handlers");
		if (!expected.equals(signature(other, otherAdded))) return refused("other changes vanilla operands, control flow or handlers");
		int baseCalls = prefixCalls(base, baseCode.subList(0, baseAdded), basePackage);
		if (baseCalls == 0) return refused("base prefix is not parameter/literal-only static void hooks");
		int otherCalls = prefixCalls(other, otherCode.subList(0, otherAdded), otherPackage);
		if (otherCalls == 0) return refused("other prefix is not parameter/literal-only static void hooks");

		MethodNode merged = new MethodNode(Opcodes.ASM9, base.access, base.name, base.desc,
				base.signature, base.exceptions.toArray(String[]::new));
		base.accept(merged);
		// Insert immediately after the base prefix, BEFORE the labels opening vanilla's original regions.
		// Inserting at the first original opcode instead would accidentally move a throwing hook into a try.
		AbstractInsnNode afterPrefix = code(merged).get(baseAdded - 1);
		InsnList additions = new InsnList();
		for (AbstractInsnNode instruction : otherCode.subList(0, otherAdded)) {
			additions.add(instruction.clone(new HashMap<>()));
		}
		merged.instructions.insert(afterPrefix, additions);
		merged.maxStack = Math.max(base.maxStack, other.maxStack);
		return new Result(merged, "exact vanilla body; entry hooks ordered base then other", baseCalls, otherCalls);
	}

	private static Result refused(String reason) { return new Result(null, reason, 0, 0); }

	private static boolean sameHeader(MethodNode vanilla, MethodNode side) {
		int execution = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED;
		return side != null && vanilla.name.equals(side.name) && vanilla.desc.equals(side.desc)
				&& (vanilla.access & execution) == (side.access & execution)
				&& Objects.equals(vanilla.exceptions, side.exceptions);
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> result = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() >= 0) result.add(instruction);
		}
		return result;
	}

	/** Complete executable operands and label destinations; debug info and frames remain those of the base. */
	private static Object signature(MethodNode method, int skipped) {
		Map<LabelNode, Integer> positions = new IdentityHashMap<>();
		int position = -skipped;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof LabelNode label) positions.put(label, position);
			else if (instruction.getOpcode() >= 0) position++;
		}
		List<Object> instructions = new ArrayList<>();
		List<AbstractInsnNode> all = code(method);
		for (AbstractInsnNode instruction : all.subList(skipped, all.size())) {
			int op = instruction.getOpcode();
			Object operands;
			if (instruction instanceof InsnNode) operands = List.of();
			else if (instruction instanceof IntInsnNode n) operands = List.of(n.operand);
			else if (instruction instanceof VarInsnNode n) operands = List.of(n.var);
			else if (instruction instanceof TypeInsnNode n) operands = List.of(n.desc);
			else if (instruction instanceof FieldInsnNode n) operands = List.of(n.owner, n.name, n.desc);
			else if (instruction instanceof MethodInsnNode n) operands = List.of(n.owner, n.name, n.desc, n.itf);
			else if (instruction instanceof InvokeDynamicInsnNode n) {
				List<Object> args = new ArrayList<>();
				for (Object arg : n.bsmArgs) args.add(constant(arg));
				operands = List.of(n.name, n.desc, n.bsm, args);
			} else if (instruction instanceof JumpInsnNode n) operands = List.of(target(positions, n.label));
			else if (instruction instanceof LdcInsnNode n) operands = List.of(constant(n.cst));
			else if (instruction instanceof IincInsnNode n) operands = List.of(n.var, n.incr);
			else if (instruction instanceof TableSwitchInsnNode n) {
				operands = List.of(n.min, n.max, target(positions, n.dflt), targets(positions, n.labels));
			} else if (instruction instanceof LookupSwitchInsnNode n) {
				operands = List.of(n.keys, target(positions, n.dflt), targets(positions, n.labels));
			} else if (instruction instanceof MultiANewArrayInsnNode n) operands = List.of(n.desc, n.dims);
			else throw new IllegalArgumentException("unknown instruction kind " + instruction.getClass().getName());
			instructions.add(List.of(op, operands));
		}
		List<Object> handlers = new ArrayList<>();
		for (TryCatchBlockNode handler : method.tryCatchBlocks) {
			handlers.add(List.of(target(positions, handler.start), target(positions, handler.end),
					target(positions, handler.handler), handler.type == null ? "<any>" : handler.type));
		}
		return List.of(instructions, handlers);
	}

	private static Object constant(Object value) {
		// Float.equals/Double.equals canonicalize NaN; retain the actual payload too.
		if (value instanceof Float f) return List.of("float", Float.floatToRawIntBits(f));
		if (value instanceof Double d) return List.of("double", Double.doubleToRawLongBits(d));
		if (value instanceof org.objectweb.asm.ConstantDynamic dynamic) {
			List<Object> args = new ArrayList<>();
			for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
				args.add(constant(dynamic.getBootstrapMethodArgument(i)));
			}
			return List.of("dynamic", dynamic.getName(), dynamic.getDescriptor(), dynamic.getBootstrapMethod(), args);
		}
		return value;
	}

	private static int target(Map<LabelNode, Integer> positions, LabelNode label) {
		return positions.getOrDefault(label, Integer.MIN_VALUE);
	}

	private static List<Integer> targets(Map<LabelNode, Integer> positions, List<LabelNode> labels) {
		List<Integer> result = new ArrayList<>();
		for (LabelNode label : labels) result.add(target(positions, label));
		return result;
	}

	/** A tiny verifier for the accepted prefix grammar; no approximation of arbitrary JVM instructions. */
	private static int prefixCalls(MethodNode method, List<AbstractInsnNode> prefix, String hookPackage) {
		Map<Integer, Type> parameters = new HashMap<>();
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		// 'this' has no owner in MethodNode and is deliberately unsupported until owner/type validation exists.
		for (Type parameter : Type.getArgumentTypes(method.desc)) {
			parameters.put(slot, parameter);
			slot += parameter.getSize();
		}
		List<Type> stack = new ArrayList<>();
		int calls = 0;
		for (AbstractInsnNode instruction : prefix) {
			int op = instruction.getOpcode();
			if (instruction instanceof VarInsnNode variable) {
				Type parameter = parameters.get(variable.var);
				if (parameter == null || op != parameter.getOpcode(Opcodes.ILOAD)) return 0;
				stack.add(parameter);
			} else if (instruction instanceof LdcInsnNode literal) {
				if (literal.cst instanceof Integer) stack.add(Type.INT_TYPE);
				else if (literal.cst instanceof Long) stack.add(Type.LONG_TYPE);
				else if (literal.cst instanceof Float) stack.add(Type.FLOAT_TYPE);
				else if (literal.cst instanceof Double) stack.add(Type.DOUBLE_TYPE);
				else if (literal.cst instanceof String) stack.add(Type.getType(String.class));
				else return 0;
			} else if (instruction instanceof IntInsnNode && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
				stack.add(Type.INT_TYPE);
			} else if (instruction instanceof InsnNode && op == Opcodes.ACONST_NULL) stack.add(null);
			else if (instruction instanceof InsnNode && op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) stack.add(Type.INT_TYPE);
			else if (instruction instanceof InsnNode && op >= Opcodes.LCONST_0 && op <= Opcodes.LCONST_1) stack.add(Type.LONG_TYPE);
			else if (instruction instanceof InsnNode && op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) stack.add(Type.FLOAT_TYPE);
			else if (instruction instanceof InsnNode && op >= Opcodes.DCONST_0 && op <= Opcodes.DCONST_1) stack.add(Type.DOUBLE_TYPE);
			else if (instruction instanceof MethodInsnNode call && op == Opcodes.INVOKESTATIC
					&& call.owner.startsWith(hookPackage) && Type.getReturnType(call.desc).equals(Type.VOID_TYPE)) {
				Type[] arguments = Type.getArgumentTypes(call.desc);
				if (arguments.length != stack.size()) return 0;
				for (int i = 0; i < arguments.length; i++) {
					Type actual = stack.get(i), expected = arguments[i];
					boolean reference = expected.getSort() == Type.OBJECT || expected.getSort() == Type.ARRAY;
					boolean intLike = expected.getSort() >= Type.BOOLEAN && expected.getSort() <= Type.INT;
					if (!(actual == null ? reference : actual.equals(expected) || (intLike && actual.equals(Type.INT_TYPE)))) return 0;
				}
				stack.clear();
				calls++;
			} else return 0;
		}
		return stack.isEmpty() ? calls : 0;
	}
}
