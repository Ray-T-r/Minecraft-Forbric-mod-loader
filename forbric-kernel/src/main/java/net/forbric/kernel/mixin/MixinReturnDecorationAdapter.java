/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** An ignored-return callback follows an unchanged native body before its return acquires extra context. */
public final class MixinReturnDecorationAdapter {
	public static final String PROPERTY = "forbric.mixinReturnDecorations";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String EXPRESSION = "Lcom/llamalad7/mixinextras/expression/Expression;";
	private static final String DEFINITION = "Lcom/llamalad7/mixinextras/expression/Definition;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private record ReturnLocal(MethodNode method, VarInsnNode value, AnnotationNode definition) { }
	private record Move(MethodNode method, MethodInsnNode point) { }
	private MixinReturnDecorationAdapter() { }

	public static int adapt(ClassNode mixin, Ecosystem ecosystem, Function<String, ClassNode> classes) {
		return adapt(mixin, ecosystem, classes, owner -> NativeGameReferences.reference(ecosystem, owner));
	}

	static int adapt(ClassNode mixin, Ecosystem ecosystem, Function<String, ClassNode> classes,
			Function<String, ClassNode> references) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.size() != 1) return 0;
		ClassNode current = classes.apply(targets.getFirst()), original = references.apply(targets.getFirst());
		if (current == null || original == null) return 0;
		Set<MethodNode> live = MixinExecutionPathRetarget.reachable(current, ecosystem);
		int changed = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injection = MixinFit.injectorOf(handler);
			if (injection == null || !injection.desc.equals(INJECT) || Boolean.TRUE.equals(MixinFit.value(injection, "cancellable"))
					|| MixinFit.value(injection, "slice") != null || !ignoresReturn(handler)) continue;
			if (annotations(handler).stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"))) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injection);
			if (points.size() != 1 || !"MIXINEXTRAS:EXPRESSION".equals(MixinFit.value(points.getFirst(), "value"))) continue;
			ReturnLocal source = source(handler, injection, original);
			if (source == null) continue;
			// A resolving original body remains authoritative.
			if (current.methods.stream().anyMatch(m -> m.name.equals(source.method.name) && m.desc.equals(source.method.desc)
					&& MixinInstructionFingerprint.hash(m).equals(MixinInstructionFingerprint.hash(source.method)))) continue;
			List<Move> moves = new ArrayList<>();
			for (MethodNode candidate : current.methods) {
				if (!live.contains(candidate) || (candidate.access & Opcodes.ACC_STATIC) != (source.method.access & Opcodes.ACC_STATIC)
						|| !Arrays.equals(Type.getArgumentTypes(candidate.desc), Type.getArgumentTypes(source.method.desc))
						|| Type.getReturnType(candidate.desc).getSort() != Type.OBJECT
						|| Type.getReturnType(candidate.desc).equals(Type.getReturnType(source.method.desc))) continue;
				for (AbstractInsnNode instruction : candidate.instructions) {
					if (!(instruction instanceof VarInsnNode value) || value.getOpcode() != Opcodes.ALOAD || value.var != source.value.var) continue;
					Move move = move(handler, source, candidate, value);
					if (move != null) moves.add(move);
				}
			}
			if (moves.size() != 1) continue;
			Move move = moves.getFirst();
			put(injection, "method", new ArrayList<>(List.of(move.method.name + move.method.desc)));
			AnnotationNode point = points.getFirst();
			put(point, "value", "INVOKE");
			put(point, "target", member(move.point));
			stripExpression(handler);
			changed++;
			ForbricLog.info("[Forbric/Mixin] %s.%s follows the unchanged native return path into %s%s before extra return context is computed",
					mixin.name.replace('/', '.'), handler.name, move.method.name, move.method.desc);
		}
		return changed;
	}

	private static Move move(MethodNode handler, ReturnLocal source, MethodNode candidate, VarInsnNode value) {
		if (!source.method.tryCatchBlocks.isEmpty() || !candidate.tryCatchBlocks.isEmpty()) return null;
		AbstractInsnNode end = next(value);
		List<AbstractInsnNode> decoration = new ArrayList<>();
		while (end != null && end.getOpcode() != Opcodes.ARETURN) {
			if (end instanceof JumpInsnNode || end instanceof TableSwitchInsnNode || end instanceof LookupSwitchInsnNode
					|| end.getOpcode() == Opcodes.ATHROW || end.getOpcode() >= Opcodes.IRETURN && end.getOpcode() <= Opcodes.RETURN) return null;
			decoration.add(end); end = next(end);
		}
		if (end == null || decoration.isEmpty()) return null;
		MethodNode reduced = new MethodNode(candidate.access, candidate.name, source.method.desc, null, null);
		candidate.accept(reduced);
		int startIndex = candidate.instructions.indexOf(value), endIndex = candidate.instructions.indexOf(end);
		for (int index = endIndex - 1; index > startIndex; index--) reduced.instructions.remove(reduced.instructions.get(index));
		if (!MixinInstructionFingerprint.hash(reduced).equals(MixinInstructionFingerprint.hash(source.method))) return null;
		List<MethodInsnNode> anchors = new ArrayList<>();
		for (AbstractInsnNode instruction : decoration) {
			if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC
					|| Type.getArgumentTypes(call.desc).length != 0 || !(previous(call) instanceof VarInsnNode receiver)
					|| receiver.getOpcode() != Opcodes.ALOAD) continue;
			if (capturedRead(handler, source.method, source.value, candidate, call, receiver.var)) anchors.add(call);
		}
		if (anchors.size() != 1) return null;
		MethodInsnNode point = anchors.getFirst();
		// The callback must still run before any new carrier computation can alter its captured value.
		for (AbstractInsnNode instruction : decoration) {
			if (instruction == point) break;
			if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return null;
		}
		long occurrences = Arrays.stream(candidate.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
				&& member(call).equals(member(point))).count();
		return occurrences == 1 ? new Move(candidate, point) : null;
	}

	/** The new operand is read through the same captured local that the source callback already reads. */
	private static boolean capturedRead(MethodNode handler, MethodNode original, AbstractInsnNode oldPoint,
			MethodNode current, MethodInsnNode point, int receiverSlot) {
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		int slot = (handler.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		boolean read = false;
		for (int parameter = 0; parameter < parameters.length; parameter++) {
			if (parameter == 0) { slot += parameters[parameter].getSize(); continue; }
			AnnotationNode local = MixinStubRebind.sugar(handler, parameter, LOCAL);
			if (local == null || Boolean.TRUE.equals(MixinFit.value(local, "argsOnly"))
					|| MixinFit.value(local, "ordinal") instanceof Number n && n.intValue() >= 0) return false;
			List<LocalVariableNode> before = selectedLocals(original, oldPoint, parameters[parameter], local);
			List<LocalVariableNode> after = selectedLocals(current, point, parameters[parameter], local);
			if (before.size() != 1 || after.size() != 1 || before.getFirst().index != after.getFirst().index) return false;
			if (after.getFirst().index == receiverSlot) {
				for (AbstractInsnNode instruction : handler.instructions) if (instruction instanceof MethodInsnNode call
						&& member(call).equals(member(point)) && previous(call) instanceof VarInsnNode receiver
						&& receiver.getOpcode() == Opcodes.ALOAD && receiver.var == slot) read = true;
			}
			slot += parameters[parameter].getSize();
		}
		return read;
	}

	private static ReturnLocal source(MethodNode handler, AnnotationNode injection, ClassNode original) {
		List<String> expressions = annotations(handler).stream().filter(a -> a.desc.equals(EXPRESSION))
				.flatMap(a -> MixinFit.stringList(MixinFit.value(a, "value")).stream()).toList();
		if (expressions.size() != 1 || !expressions.getFirst().matches("return [A-Za-z_$][A-Za-z0-9_$]*")) return null;
		String id = expressions.getFirst().substring(7);
		List<AnnotationNode> definitions = annotations(handler).stream().filter(a -> a.desc.equals(DEFINITION)
				&& id.equals(MixinFit.value(a, "id"))).toList();
		if (definitions.size() != 1 || !(MixinFit.value(definitions.getFirst(), "local") instanceof List<?> locals)
				|| locals.size() != 1 || !(locals.getFirst() instanceof AnnotationNode local)) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injection, "method"));
		List<MethodNode> methods = original.methods.stream().filter(m -> selectors.contains(m.name) || selectors.contains(m.name + m.desc)).toList();
		if (methods.size() != 1 || Type.getReturnType(methods.getFirst().desc).getSort() != Type.OBJECT) return null;
		MethodNode method = methods.getFirst(); List<VarInsnNode> returned = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof VarInsnNode value
				&& value.getOpcode() == Opcodes.ALOAD && next(value) != null && next(value).getOpcode() == Opcodes.ARETURN) {
			List<LocalVariableNode> candidates = selectedLocals(method, value, Type.getReturnType(method.desc), local);
			if (candidates.size() == 1 && candidates.getFirst().index == value.var) returned.add(value);
		}
		return returned.size() == 1 ? new ReturnLocal(method, returned.getFirst(), local) : null;
	}

	private static List<LocalVariableNode> selectedLocals(MethodNode method, AbstractInsnNode point, Type type, AnnotationNode local) {
		if (method.localVariables == null) return List.of();
		List<String> names = MixinFit.stringList(MixinFit.value(local, "name")); Object index = MixinFit.value(local, "index");
		boolean indexed = index instanceof Number n && n.intValue() >= 0;
		if (names.isEmpty() && !indexed) return List.of();
		int position = method.instructions.indexOf(point);
		return method.localVariables.stream().filter(v -> v.desc.equals(type.getDescriptor())
				&& (!indexed || v.index == ((Number) index).intValue()) && (names.isEmpty() || names.contains(v.name))
				&& method.instructions.indexOf(v.start) <= position && method.instructions.indexOf(v.end) > position).toList();
	}

	private static boolean ignoresReturn(MethodNode handler) {
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (parameters.length == 0 || !parameters[0].getDescriptor().equals(CALLBACK)
				|| !Type.getReturnType(handler.desc).equals(Type.VOID_TYPE)) return false;
		int slot = (handler.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (AbstractInsnNode instruction : handler.instructions) if (instruction instanceof VarInsnNode variable && variable.var == slot
				|| instruction instanceof IincInsnNode increment && increment.var == slot) return false;
		return true;
	}
	private static String member(MethodInsnNode call) { return "L" + call.owner + ";" + call.name + call.desc; }
	private static AbstractInsnNode next(AbstractInsnNode node) { for (var i = node.getNext(); i != null; i = i.getNext()) if (i.getOpcode() >= 0) return i; return null; }
	private static AbstractInsnNode previous(AbstractInsnNode node) { for (var i = node.getPrevious(); i != null; i = i.getPrevious()) if (i.getOpcode() >= 0) return i; return null; }
	private static List<AnnotationNode> annotations(MethodNode method) { List<AnnotationNode> out = new ArrayList<>(); if (method.visibleAnnotations != null) out.addAll(method.visibleAnnotations); if (method.invisibleAnnotations != null) out.addAll(method.invisibleAnnotations); return out; }
	private static void stripExpression(MethodNode handler) { if (handler.visibleAnnotations != null) handler.visibleAnnotations.removeIf(a -> a.desc.equals(EXPRESSION) || a.desc.equals(DEFINITION)); if (handler.invisibleAnnotations != null) handler.invisibleAnnotations.removeIf(a -> a.desc.equals(EXPRESSION) || a.desc.equals(DEFINITION)); }
	private static void put(AnnotationNode node, String key, Object value) { for (int i = 0; i + 1 < node.values.size(); i += 2) if (key.equals(node.values.get(i))) { node.values.set(i + 1, value); return; } node.values.add(key); node.values.add(value); }
}
