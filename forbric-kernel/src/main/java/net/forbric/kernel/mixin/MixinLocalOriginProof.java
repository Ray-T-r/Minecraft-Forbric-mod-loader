/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Matches a declared native local to one current caller slot by its actual producer, never by type alone. */
final class MixinLocalOriginProof {
	private MixinLocalOriginProof() { }

	static Map<Integer, Integer> prove(MethodNode handler, String owner, MethodNode reference, AbstractInsnNode originalPoint,
			MethodNode current, AbstractInsnNode currentPoint) {
		return prove(handler, owner, reference, originalPoint, current, currentPoint, Map.of());
	}
	static Map<Integer, Integer> prove(MethodNode handler, String owner, MethodNode reference, AbstractInsnNode originalPoint,
			MethodNode current, AbstractInsnNode currentPoint, Map<TypeInsnNode, TypeInsnNode> roles) {
		if (originalPoint == null || currentPoint == null || !reference.desc.equals(current.desc)
				|| (reference.access & Opcodes.ACC_STATIC) != (current.access & Opcodes.ACC_STATIC)) return null;
		Origins before = Origins.create(owner, reference), after = Origins.create(owner, current);
		if (before == null || after == null) return null;
		int original = reference.instructions.indexOf(originalPoint), present = current.instructions.indexOf(currentPoint);
		if (original < 0 || present < 0 || before.frames[original] == null || after.frames[present] == null) return null;
		Map<Integer, Integer> result = new LinkedHashMap<>();
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		for (int i = 0; i < parameters.length; i++) {
			AnnotationNode local = MixinStubRebind.sugar(handler, i, MixinRetarget.LOCAL_SUGAR);
			if (local == null) { if (MixinFit.sugar(handler, i)) return null; continue; }
			Type type = parameters[i];
			if (type.getSort() == Type.OBJECT && type.getInternalName().startsWith("com/llamalad7/mixinextras/sugar/ref/")) return null;
			List<Integer> slots = candidates(reference, original, type, Boolean.TRUE.equals(MixinFit.value(local, "argsOnly")));
			Object index = MixinFit.value(local, "index");
			List<String> names = MixinFit.stringList(MixinFit.value(local, "name"));
			if (index instanceof Number n && n.intValue() >= 0) slots = slots.stream().filter(s -> s == n.intValue()).toList();
			if (!names.isEmpty()) slots = slots.stream().filter(s -> reference.localVariables.stream().anyMatch(v -> v.index == s
					&& names.contains(v.name) && live(reference, v, original))).toList();
			if (MixinFit.value(local, "ordinal") instanceof Number ordinal && ordinal.intValue() >= 0) {
				int value = ordinal.intValue(); slots = value < slots.size() ? List.of(slots.get(value)) : List.of();
			}
			if (slots.size() != 1) return null;
			String origin = before.local(original, slots.getFirst());
			TypeInsnNode sourceAllocation = before.allocation(before.frames[original].getLocal(slots.getFirst()), new HashSet<>());
			if (origin == null && (sourceAllocation == null || !roles.containsKey(sourceAllocation))) return null;
			List<Integer> matched = new ArrayList<>();
			for (int slot : candidates(current, present, type, Boolean.TRUE.equals(MixinFit.value(local, "argsOnly")))) {
				if (origin != null && origin.equals(after.local(present, slot)) || sourceAllocation != null && roles.containsKey(sourceAllocation)
						&& roles.get(sourceAllocation) == after.allocation(after.frames[present].getLocal(slot), new HashSet<>())) matched.add(slot);
			}
			if (matched.size() != 1) return null;
			result.put(i, matched.getFirst());
		}
		return Map.copyOf(result);
	}

	/**
	 * A platform may widen a captured object's constructor while keeping its role: the same argument of the same
	 * enclosing producer that supplies the exact native anchor's receiver/input. Pair only that def-use path, not
	 * arbitrary allocations of the same type. The enclosing constructor signature and every noncaptured input agree.
	 */
	static Map<TypeInsnNode, TypeInsnNode> constructorRoles(MethodNode handler, String owner, MethodNode reference,
			MethodInsnNode originalCall, MethodNode current, MethodInsnNode edge, MethodNode helper, MethodInsnNode helperCall) {
		Origins before = Origins.create(owner, reference), after = Origins.create(owner, current), inside = Origins.create(owner, helper);
		if (before == null || after == null || inside == null) return Map.of();
		int oldPoint = reference.instructions.indexOf(originalCall), edgePoint = current.instructions.indexOf(edge), innerPoint = helper.instructions.indexOf(helperCall);
		Frame<SourceValue> old = before.frames[oldPoint], now = after.frames[edgePoint], delegated = inside.frames[innerPoint];
		if (old == null || now == null || delegated == null) return Map.of();
		Set<TypeInsnNode> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
		Type[] captures = Type.getArgumentTypes(handler.desc);
		for (int i = 0; i < captures.length; i++) {
			AnnotationNode local = MixinStubRebind.sugar(handler, i, MixinRetarget.LOCAL_SUGAR); if (local == null) continue;
			List<Integer> slots = candidates(reference, oldPoint, captures[i], Boolean.TRUE.equals(MixinFit.value(local, "argsOnly")));
			List<String> names = MixinFit.stringList(MixinFit.value(local, "name"));
			if (!names.isEmpty()) slots = slots.stream().filter(s -> reference.localVariables.stream().anyMatch(v -> v.index == s
					&& names.contains(v.name) && live(reference, v, oldPoint))).toList();
			if (MixinFit.value(local, "index") instanceof Number n && n.intValue() >= 0) slots = slots.stream().filter(s -> s == n.intValue()).toList();
			if (MixinFit.value(local, "ordinal") instanceof Number n && n.intValue() >= 0) slots = n.intValue() < slots.size() ? List.of(slots.get(n.intValue())) : List.of();
			if (slots.size() != 1) return Map.of();
			TypeInsnNode allocation = before.allocation(old.getLocal(slots.getFirst()), new HashSet<>()); if (allocation != null) wanted.add(allocation);
		}
		Map<TypeInsnNode, TypeInsnNode> roles = new IdentityHashMap<>();
		int operands = Type.getArgumentTypes(originalCall.desc).length + (originalCall.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		int arguments = Type.getArgumentTypes(edge.desc).length;
		if (old.getStackSize() < operands || delegated.getStackSize() < operands || now.getStackSize() < arguments) return Map.of();
		for (int i = 0; i < operands; i++) {
			SourceValue nativeInput = old.getStack(old.getStackSize() - operands + i), helperInput = delegated.getStack(delegated.getStackSize() - operands + i);
			String origin = inside.value(helperInput, -1, new HashSet<>(), false);
			int slot;
			if (origin != null && origin.startsWith("parameter:")) {
				int parameter = Integer.parseInt(origin.substring(10, origin.indexOf(':', 10))); slot = now.getStackSize() - arguments + parameter;
			} else if (origin != null && origin.equals("this:" + owner) && edge.getOpcode() != Opcodes.INVOKESTATIC) {
				slot = now.getStackSize() - arguments - 1;
			} else return Map.of();
			if (!pairInputs(before, nativeInput, after, now.getStack(slot), wanted, roles, 0)) return Map.of();
		}
		return Map.copyOf(roles);
	}

	private static boolean pairInputs(Origins before, SourceValue a, Origins after, SourceValue b, Set<TypeInsnNode> wanted,
			Map<TypeInsnNode, TypeInsnNode> roles, int depth) {
		if (depth > 16) return false;
		String original = before.value(a, -1, new HashSet<>(), true), current = after.value(b, -1, new HashSet<>(), true);
		if (original != null && original.equals(current)) return true;
		TypeInsnNode old = before.allocation(a, new HashSet<>()), now = after.allocation(b, new HashSet<>());
		if (old == null || now == null || !old.desc.equals(now.desc)) return false;
		Origins.Construction oldCtor = before.construction(old), nowCtor = after.construction(now);
		if (oldCtor == null || nowCtor == null) return false;
		if (depth > 0 && wanted.contains(old)) { roles.put(old, now); return true; }
		if (!oldCtor.call().desc.equals(nowCtor.call().desc) || oldCtor.arguments().size() != nowCtor.arguments().size()) return false;
		for (int i = 0; i < oldCtor.arguments().size(); i++) if (!pairInputs(before, oldCtor.arguments().get(i), after,
				nowCtor.arguments().get(i), wanted, roles, depth + 1)) return false;
		return true;
	}

	private static List<Integer> candidates(MethodNode method, int point, Type type, boolean argsOnly) {
		Set<Integer> slots = new TreeSet<>();
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type argument : Type.getArgumentTypes(method.desc)) { if (argument.equals(type)) slots.add(slot); slot += argument.getSize(); }
		if (!argsOnly && method.localVariables != null) for (LocalVariableNode variable : method.localVariables) {
			if (variable.desc.equals(type.getDescriptor()) && live(method, variable, point)) slots.add(variable.index);
		}
		return List.copyOf(slots);
	}
	private static boolean live(MethodNode method, LocalVariableNode variable, int point) {
		return method.instructions.indexOf(variable.start) <= point && point < method.instructions.indexOf(variable.end);
	}

	private static final class Origins {
		final String owner; final MethodNode method; final Frame<SourceValue>[] frames; final Map<Integer, String> parameters = new HashMap<>();
		Origins(String owner, MethodNode method, Frame<SourceValue>[] frames) {
			this.owner = owner; this.method = method; this.frames = frames;
			int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0, position = 0;
			if (slot == 1) parameters.put(0, "this:" + owner);
			for (Type type : Type.getArgumentTypes(method.desc)) { parameters.put(slot, "parameter:" + position++ + ":" + type.getDescriptor()); slot += type.getSize(); }
		}
		static Origins create(String owner, MethodNode method) {
			try { return new Origins(owner, method, new Analyzer<>(new SourceInterpreter()).analyze(owner, method)); }
			catch (AnalyzerException | RuntimeException invalid) { return null; }
		}
		String local(int point, int slot) {
			Frame<SourceValue> frame = frames[point];
			if (slot >= frame.getLocals()) return null;
			return value(frame.getLocal(slot), slot, new HashSet<>(), true);
		}
		TypeInsnNode allocation(SourceValue value, Set<AbstractInsnNode> seen) {
			if (value == null || value.insns.size() != 1) return null;
			AbstractInsnNode instruction = value.insns.iterator().next(); if (!seen.add(instruction)) return null;
			if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
				int count = 0; for (AbstractInsnNode other : method.instructions) if (other instanceof TypeInsnNode n && n.getOpcode() == Opcodes.NEW && n.desc.equals(type.desc)) count++;
				return count == 1 ? type : null;
			}
			Frame<SourceValue> frame = frames[method.instructions.indexOf(instruction)]; if (frame == null) return null;
			if (instruction instanceof VarInsnNode variable && instruction.getOpcode() >= Opcodes.ILOAD && instruction.getOpcode() <= Opcodes.ALOAD) return allocation(frame.getLocal(variable.var), seen);
			if (instruction instanceof VarInsnNode && instruction.getOpcode() >= Opcodes.ISTORE && instruction.getOpcode() <= Opcodes.ASTORE
					|| instruction.getOpcode() == Opcodes.DUP || instruction.getOpcode() == Opcodes.CHECKCAST) return frame.getStackSize() == 0 ? null : allocation(frame.getStack(frame.getStackSize() - 1), seen);
			return null;
		}
		record Construction(MethodInsnNode call, List<SourceValue> arguments) { }
		Construction construction(TypeInsnNode allocation) {
			List<Construction> constructors = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& call.name.equals("<init>") && call.owner.equals(allocation.desc)) {
				Frame<SourceValue> frame = frames[method.instructions.indexOf(call)]; int arguments = Type.getArgumentTypes(call.desc).length;
				if (frame == null || frame.getStackSize() < arguments + 1) continue;
				int first = frame.getStackSize() - arguments - 1;
				if (allocation(frame.getStack(first), new HashSet<>()) != allocation) continue;
				List<SourceValue> values = new ArrayList<>(); for (int i = 0; i < arguments; i++) values.add(frame.getStack(first + i + 1));
				constructors.add(new Construction(call, List.copyOf(values)));
			}
			return constructors.size() == 1 ? constructors.getFirst() : null;
		}
		String value(SourceValue value, int parameter, Set<AbstractInsnNode> seen, boolean constructors) {
			if (value == null) return null;
			if (value.insns.isEmpty()) return parameters.get(parameter);
			if (value.insns.size() != 1) return null;
			AbstractInsnNode instruction = value.insns.iterator().next();
			if (!seen.add(instruction)) return null;
			int index = method.instructions.indexOf(instruction); Frame<SourceValue> frame = index < 0 ? null : frames[index];
			if (frame == null) return null;
			int opcode = instruction.getOpcode();
			if (instruction instanceof VarInsnNode variable) {
				if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) return value(frame.getLocal(variable.var), variable.var, seen, constructors);
				if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) return top(frame, seen, constructors);
			}
			if (opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST) return top(frame, seen, constructors);
			if (instruction instanceof TypeInsnNode allocation && opcode == Opcodes.NEW) {
				int count = 0; for (AbstractInsnNode other : method.instructions) if (other instanceof TypeInsnNode n
						&& n.getOpcode() == Opcodes.NEW && n.desc.equals(allocation.desc)) count++;
				if (count != 1) return null; // Same-typed fresh values are not interchangeable.
				String identity = "allocation:" + allocation.desc;
				if (!constructors) return identity;
				List<String> calls = new ArrayList<>();
				for (AbstractInsnNode other : method.instructions) if (other instanceof MethodInsnNode call
						&& call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>") && call.owner.equals(allocation.desc)) {
					Frame<SourceValue> at = frames[method.instructions.indexOf(call)]; Type[] args = Type.getArgumentTypes(call.desc);
					if (at == null || at.getStackSize() < args.length + 1) continue;
					int base = at.getStackSize() - args.length - 1;
					if (!identity.equals(value(at.getStack(base), -1, new HashSet<>(), false))) continue;
					StringBuilder signature = new StringBuilder(identity).append(call.desc).append('[');
					for (int i = 0; i < args.length; i++) {
						String argument = value(at.getStack(base + i + 1), -1, new HashSet<>(), true);
						if (argument == null) return null; signature.append(argument).append(';');
					}
					calls.add(signature.append(']').toString());
				}
				return calls.size() == 1 ? calls.getFirst() : null;
			}
			if (instruction instanceof LdcInsnNode constant) return "constant:" + constant.cst;
			if (instruction instanceof IntInsnNode constant) return "constant:" + opcode + ":" + constant.operand;
			if (opcode == Opcodes.ACONST_NULL || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) return "constant-op:" + opcode;
			// Mutable field reads and arbitrary method results need a stronger effect/provenance model. A matching
			// descriptor or debug name is not proof that such values coincide at the two points.
			return null;
		}
		String top(Frame<SourceValue> frame, Set<AbstractInsnNode> seen, boolean constructors) {
			return frame.getStackSize() == 0 ? null : value(frame.getStack(frame.getStackSize() - 1), -1, seen, constructors);
		}
	}
}
