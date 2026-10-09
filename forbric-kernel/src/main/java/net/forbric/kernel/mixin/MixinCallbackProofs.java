/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * What a callback adapter proves about a handler, or about a native body and the live body it moves a handler to, in place
 * of matching the bytes of the one mod it was first written for.
 *
 * <ul>
 * <li>{@link #points}: the instructions an {@code @At} selects in a method, the way Mixin's injection points select them.
 * <li>{@link #forwardsOperands}: a {@code @WrapOperation} handler hands its own receiver and arguments to every
 * {@code original.call}, unchanged, and lets the {@code Operation} go nowhere else, so an adapter that supplies the
 * wrapped call's value may ignore what the handler passes.
 * <li>{@link #returnsOriginal}: on every path the handler returns exactly the value its {@code original.call} produced,
 * so an adapter that cannot feed the handler's answer back into the merged game drops nothing by ignoring it.
 * <li>{@link #correspondLocals}: each {@code @Local} the handler asks for names, in the live body, a slot whose value is
 * produced the way the native slot's was — by the same calls, fields, allocations and parameters — never a slot that
 * merely has the same type.
 * </ul>
 */
final class MixinCallbackProofs {
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final int MAX_STATES = 20_000;

	private MixinCallbackProofs() { }

	// ---- injection points -------------------------------------------------------------------------------------------

	/**
	 * The instructions {@code at} selects in {@code method}: {@code HEAD} (the first instruction), {@code RETURN} (every
	 * return), {@code TAIL} (the last return), {@code INVOKE}/{@code INVOKE_ASSIGN} (calls of the target member),
	 * {@code FIELD} (accesses of the target field, of {@code opcode} if given) and {@code NEW} (allocations of the target
	 * class, of the target constructor if one is named), narrowed by {@code ordinal}. Null for any other point, a point
	 * with {@code args}, {@code slice}d selection, or a target that names no member.
	 */
	static List<AbstractInsnNode> points(MethodNode method, AnnotationNode at) {
		if (method == null || method.instructions == null || at == null || MixinFit.value(at, "args") != null) return null;
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		String target = MixinFit.asString(MixinFit.value(at, "target"));
		List<AbstractInsnNode> found = new ArrayList<>();
		if (value == null) return null;
		switch (value) {
			case "HEAD" -> {
				AbstractInsnNode first = method.instructions.getFirst();
				while (first != null && first.getOpcode() < 0) first = first.getNext();
				if (first != null) found.add(first);
			}
			case "RETURN", "TAIL" -> {
				for (AbstractInsnNode instruction : method.instructions)
					if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN) found.add(instruction);
				if (value.equals("TAIL") && !found.isEmpty()) found = new ArrayList<>(List.of(found.getLast()));
			}
			case "INVOKE", "INVOKE_ASSIGN" -> {
				// As Mixin's MemberInfo matches a call: the name, and the owner and descriptor wherever the target gives them.
				MixinFit.Member member = MixinFit.parseMember(target);
				if (member == null || member.desc() != null && !member.desc().startsWith("(")) return null;
				for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call
						&& call.name.equals(member.name()) && (member.desc() == null || call.desc.equals(member.desc()))
						&& (member.owner() == null || call.owner.equals(member.owner()))) found.add(instruction);
			}
			case "FIELD" -> {
				MixinFit.Member member = MixinFit.parseMember(target);
				if (member == null) return null;
				Object opcode = MixinFit.value(at, "opcode");
				for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof FieldInsnNode field
						&& field.name.equals(member.name()) && (member.desc() == null || field.desc.equals(member.desc()))
						&& (member.owner() == null || field.owner.equals(member.owner()))
						&& (!(opcode instanceof Number n) || n.intValue() < 0 || n.intValue() == field.getOpcode())) found.add(instruction);
			}
			case "NEW" -> {
				if (target == null || target.isBlank()) return null;
				String owner, constructor = null;
				String spelled = target.replaceAll("\\s+", "");
				if (spelled.startsWith("(")) {
					Type returned = Type.getReturnType(spelled);
					if (returned.getSort() != Type.OBJECT) return null;
					owner = returned.getInternalName();
					constructor = spelled.substring(0, spelled.indexOf(')') + 1) + "V";
				} else owner = spelled.startsWith("L") && spelled.endsWith(";") ? spelled.substring(1, spelled.length() - 1) : spelled.replace('.', '/');
				Frame<SourceValue>[] frames = constructor == null ? null : sources("", method);
				if (constructor != null && frames == null) return null;
				for (AbstractInsnNode instruction : method.instructions) {
					if (!(instruction instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW || !allocation.desc.equals(owner)) continue;
					if (constructor == null || constructor.equals(constructorOf(method, frames, allocation))) found.add(instruction);
				}
			}
			default -> { return null; }
		}
		Object ordinal = MixinFit.value(at, "ordinal");
		if (ordinal instanceof Number n && n.intValue() >= 0) return n.intValue() < found.size() ? List.of(found.get(n.intValue())) : List.of();
		return List.copyOf(found);
	}

	/**
	 * Whether {@code at} finds its point in {@code live} as it did in {@code reference}, the native body a handler was
	 * written for: as many instructions in each when that body is at hand; without it, one instruction, or the method's
	 * head or tail.
	 */
	static boolean found(MethodNode reference, MethodNode live, AnnotationNode at) {
		List<AbstractInsnNode> now = points(live, at);
		if (now == null || now.isEmpty()) return false;
		if (reference != null) {
			List<AbstractInsnNode> was = points(reference, at);
			return was != null && was.size() == now.size();
		}
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return now.size() == 1 || "HEAD".equals(value) || "TAIL".equals(value);
	}

	/** Where a handler written for a native body lands in the live one: the {@code @Local}s it asks for, and the live point. */
	record Landing(Map<Integer, Integer> locals, AbstractInsnNode point) {
		/** Pins each proved {@code @Local} of {@code handler} to its slot of {@code live} ({@link #pinLocals}). */
		void pin(MethodNode handler, MethodNode live) {
			if (point != null && !locals.isEmpty()) pinLocals(handler, live, point, locals);
		}
	}

	/**
	 * Where {@code handler}, written for the native method {@code reference} (null when the class the mod was compiled
	 * against is not at hand; {@code nativeDesc} is that method's descriptor), lands when its selector is moved to
	 * {@code live}: every {@code @At} must be {@link #found} there; its extras may only be {@code @Local}s (a target
	 * argument Mixin appends counting as the {@code @Local(argsOnly = true)} it stands for), {@code @Share}s and
	 * {@code @Cancellable}s; and each {@code @Local} must be proved — by {@link #correspondLocals} at the one point of
	 * its one {@code @At} when the native body is at hand, else as an {@code argsOnly} parameter ({@link #parameterLocals}).
	 * {@code nativeParameter} maps a live parameter position to the native one it carries. Null when it does not land.
	 */
	static Landing land(MethodNode handler, String referenceOwner, MethodNode reference, String liveOwner, MethodNode live,
			IntUnaryOperator nativeParameter, String nativeDesc) {
		if (live == null) return null;
		// Read against the method it was written for: a target argument it appends is that method's, an implicit @Local.
		MixinHandlerShape shape = MixinHandlerShape.of(handler, reference != null ? reference.desc : nativeDesc, reference != null ? reference : live);
		if (shape == null) return null;
		List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(handler));
		if (ats.isEmpty() || !ats.stream().allMatch(at -> found(reference, live, at))) return null;
		if (!shape.extras().stream().allMatch(extra -> extra.role() == MixinHandlerShape.Role.LOCAL
				|| extra.role() == MixinHandlerShape.Role.SHARE || extra.role() == MixinHandlerShape.Role.CANCELLABLE)) return null;
		if (shape.locals().isEmpty()) return new Landing(Map.of(), null);
		if (ats.size() != 1) return null;
		List<AbstractInsnNode> now = points(live, ats.getFirst());
		if (now == null || now.size() != 1) return null;
		Map<Integer, Integer> locals;
		if (reference != null) {
			List<AbstractInsnNode> was = points(reference, ats.getFirst());
			locals = was == null || was.size() != 1 ? null
					: correspondLocals(handler, referenceOwner, reference, was.getFirst(), liveOwner, live, now.getFirst(), nativeParameter);
		} else locals = parameterLocals(handler, nativeDesc, live, nativeParameter);
		return locals == null ? null : new Landing(locals, now.getFirst());
	}

	/**
	 * The handlers of {@code mixin} that {@code role} accepts and that are the only handler of their injector kind at their
	 * point: a callback adapter moves a handler only when it is alone there, and leaves several as compiled. Where a
	 * handler is, is what Mixin makes of it, never how it is spelled: {@code body} gives the method its selectors bind
	 * (the body it was written for), and in it each {@code @At} is the instructions it selects ({@link #points}) and its
	 * shift — {@code "update"} and {@code "update(Lnet/…/Entity;Z)V"}, a dotted owner, a bare ordinal 0 on the only call:
	 * one point. A point not read off a body (none bound, a point this does not model, nothing selected) is its target
	 * resolved to a member ({@link MixinFit#parseMember}) and its other values, defaults written out.
	 */
	static List<MethodNode> alone(ClassNode mixin, java.util.function.Function<MethodNode, MethodNode> body, java.util.function.Predicate<MethodNode> role) {
		record Point(String kind, MethodNode bound, Object selectors, List<Object> ats) { }
		Map<Point, List<MethodNode>> byPoint = new LinkedHashMap<>();
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || !role.test(method)) continue;
			MethodNode bound = body == null ? null : body.apply(method);
			List<Object> ats = new ArrayList<>();
			for (AnnotationNode at : MixinFit.atNodes(injector)) ats.add(where(bound, at));
			Point key = new Point(injector.desc, bound, bound != null ? "" : describe(MixinTargetSelectors.selectors(method)), ats);
			byPoint.computeIfAbsent(key, k -> new ArrayList<>()).add(method);
		}
		List<MethodNode> out = new ArrayList<>();
		for (List<MethodNode> handlers : byPoint.values()) if (handlers.size() == 1) out.add(handlers.getFirst());
		return out;
	}

	/** Where {@code at} injects in {@code bound}: the instructions it selects and how it shifts from them; else its resolved spelling. */
	private static Object where(MethodNode bound, AnnotationNode at) {
		Object shift = MixinFit.asString(MixinFit.value(at, "shift")), by = MixinFit.value(at, "by");
		String moved = (shift == null ? "NONE" : shift) + "/" + (by == null ? 0 : by);
		List<AbstractInsnNode> selected = bound == null ? null : points(bound, at);
		if (selected != null && !selected.isEmpty()) {
			// Every point modelled here injects before what it selects, but INVOKE_ASSIGN, which injects after the call's result.
			List<Integer> indices = new ArrayList<>();
			for (AbstractInsnNode instruction : selected) indices.add(bound.instructions.indexOf(instruction));
			return indices + ("INVOKE_ASSIGN".equals(MixinFit.asString(MixinFit.value(at, "value"))) ? "@assign/" : "@") + moved;
		}
		StringBuilder spelled = new StringBuilder(String.valueOf(MixinFit.asString(MixinFit.value(at, "value")))).append('|');
		MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
		spelled.append(member == null ? describe(MixinFit.value(at, "target")) : member.owner() + ";" + member.name() + ";" + member.desc());
		Object ordinal = MixinFit.value(at, "ordinal"), opcode = MixinFit.value(at, "opcode");
		spelled.append('|').append(ordinal instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1)
				.append('|').append(opcode instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1)
				.append('|').append(moved).append('|').append(describe(MixinFit.value(at, "args")))
				.append('|').append(describe(MixinFit.value(at, "id"))).append('|').append(describe(MixinFit.value(at, "desc")));
		return spelled.toString();
	}

	/** An annotation value spelled by content: enum arrays, lists and nested annotations included. */
	private static String describe(Object value) {
		if (value instanceof Object[] array) return java.util.Arrays.stream(array).map(MixinCallbackProofs::describe).toList().toString();
		if (value instanceof List<?> list) return list.stream().map(MixinCallbackProofs::describe).toList().toString();
		if (value instanceof AnnotationNode annotation) return annotation.desc + describe(annotation.values);
		return String.valueOf(value);
	}

	/** The descriptor of the constructor that initialises {@code allocation}; null when not exactly one does. */
	private static String constructorOf(MethodNode method, Frame<SourceValue>[] frames, TypeInsnNode allocation) {
		String found = null;
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.name.equals("<init>")
					|| !call.owner.equals(allocation.desc)) continue;
			Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
			int arguments = Type.getArgumentTypes(call.desc).length;
			if (frame == null || frame.getStackSize() < arguments + 1) continue;
			if (root(method, frames, frame.getStack(frame.getStackSize() - arguments - 1), new HashSet<>()) != allocation) continue;
			if (found != null && !found.equals(call.desc)) return null;
			found = call.desc;
		}
		return found;
	}

	// ---- operand forwarding ----------------------------------------------------------------------------------------

	/**
	 * Whether the {@code @WrapOperation} handler's every {@code original.call} receives a fresh {@code Object[]} holding,
	 * at index {@code i}, the handler's own operand parameter {@code i} (boxed if primitive) and nothing else; whether the
	 * {@code Operation} parameter is used for nothing but those calls; and whether no operand or {@code Operation}
	 * parameter is ever reassigned. {@code operands} is how many parameters precede the {@code Operation}.
	 */
	static boolean forwardsOperands(String owner, MethodNode handler, int operands) {
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (operands < 0 || operands >= parameters.length || !parameters[operands].getInternalName().equals(OPERATION)) return false;
		int[] slots = slots(handler);
		Set<Integer> fixed = new HashSet<>();
		for (int i = 0; i <= operands; i++) fixed.add(slots[i]);
		for (AbstractInsnNode instruction : handler.instructions) {
			if (instruction instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE
					&& fixed.contains(variable.var)) return false;
			if (instruction instanceof IincInsnNode increment && fixed.contains(increment.var)) return false;
		}
		if (!handler.tryCatchBlocks.isEmpty()) return false;
		Frame<SourceValue>[] frames = sources(owner, handler);
		if (frames == null) return false;
		int operationSlot = slots[operands];
		// Every load of the Operation, and every array an original.call receives.
		Set<AbstractInsnNode> operationLoads = new HashSet<>(), arrays = new HashSet<>();
		for (AbstractInsnNode instruction : handler.instructions)
			if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == operationSlot) operationLoads.add(load);
		List<MethodInsnNode> calls = new ArrayList<>();
		for (AbstractInsnNode instruction : handler.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !isOperationCall(call)) continue;
			Frame<SourceValue> frame = frames[handler.instructions.indexOf(call)];
			if (frame == null) continue; // unreachable
			SourceValue receiver = frame.getStack(frame.getStackSize() - 2), array = frame.getStack(frame.getStackSize() - 1);
			if (receiver.insns.isEmpty() || !operationLoads.containsAll(receiver.insns)) return false;
			AbstractInsnNode allocation = root(handler, frames, array, new HashSet<>());
			if (!(allocation instanceof TypeInsnNode created) || created.getOpcode() != Opcodes.ANEWARRAY || !created.desc.equals("java/lang/Object")) return false;
			Frame<SourceValue> sized = frames[handler.instructions.indexOf(created)];
			if (sized == null || constant(sized.getStack(sized.getStackSize() - 1)) != operands) return false;
			arrays.add(created);
			calls.add(call);
		}
		if (calls.isEmpty()) return false;
		// Each array index holds its operand parameter once; the Operation and the arrays go nowhere else.
		Map<AbstractInsnNode, Set<Integer>> stored = new HashMap<>();
		Uses uses = new Uses(handler, frames, operationLoads, arrays);
		try {
			new Analyzer<>(uses).analyze(owner == null || owner.isEmpty() ? "java/lang/Object" : owner, handler);
		} catch (AnalyzerException | RuntimeException invalid) {
			return false;
		}
		if (uses.escaped) return false;
		for (var entry : uses.stores.entrySet()) {
			AbstractInsnNode store = entry.getKey();
			Frame<SourceValue> frame = frames[handler.instructions.indexOf(store)];
			if (frame == null) continue;
			int index = constant(frame.getStack(frame.getStackSize() - 2));
			if (index < 0 || index >= operands) return false;
			if (!loadsParameter(handler, frames, frame.getStack(frame.getStackSize() - 1), slots[index], parameters[index])) return false;
			if (!stored.computeIfAbsent(entry.getValue(), k -> new HashSet<>()).add(index)) return false;
		}
		for (AbstractInsnNode array : arrays) if (stored.getOrDefault(array, Set.of()).size() != operands) return false;
		return true;
	}

	static boolean isOperationCall(MethodInsnNode call) {
		return call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals(OPERATION) && call.name.equals("call")
				&& call.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;");
	}

	private static List<LabelNode> labels(AbstractInsnNode instruction) {
		if (instruction instanceof JumpInsnNode jump) return List.of(jump.label);
		List<LabelNode> out = new ArrayList<>();
		if (instruction instanceof TableSwitchInsnNode table) { out.add(table.dflt); out.addAll(table.labels); }
		if (instruction instanceof LookupSwitchInsnNode lookup) { out.add(lookup.dflt); out.addAll(lookup.labels); }
		return out;
	}

	/** Whether {@code value} is the parameter in {@code slot} (boxed by its wrapper's {@code valueOf} if primitive). */
	private static boolean loadsParameter(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int slot, Type type) {
		if (value.insns.size() != 1) return false;
		AbstractInsnNode instruction = value.insns.iterator().next();
		if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
			if (!(instruction instanceof MethodInsnNode box) || box.getOpcode() != Opcodes.INVOKESTATIC || !box.name.equals("valueOf")
					|| !box.desc.equals("(" + type.getDescriptor() + ")L" + box.owner + ";") || !box.owner.startsWith("java/lang/")) return false;
			Frame<SourceValue> frame = frames[method.instructions.indexOf(box)];
			if (frame == null) return false;
			value = frame.getStack(frame.getStackSize() - 1);
			if (value.insns.size() != 1) return false;
			instruction = value.insns.iterator().next();
		}
		return instruction instanceof VarInsnNode load && load.var == slot && load.getOpcode() == type.getOpcode(Opcodes.ILOAD);
	}

	/** Records every use of the Operation's loads and of the call arrays; anything but the allowed roles escapes. */
	private static final class Uses extends SourceInterpreter {
		final MethodNode method; final Frame<SourceValue>[] frames; final Set<AbstractInsnNode> operationLoads, arrays;
		final Map<AbstractInsnNode, AbstractInsnNode> stores = new LinkedHashMap<>();
		boolean escaped;
		Uses(MethodNode method, Frame<SourceValue>[] frames, Set<AbstractInsnNode> operationLoads, Set<AbstractInsnNode> arrays) {
			super(Opcodes.ASM9);
			this.method = method; this.frames = frames; this.operationLoads = operationLoads; this.arrays = arrays;
		}
		private boolean operation(SourceValue value) { return value.insns.stream().anyMatch(operationLoads::contains); }
		private AbstractInsnNode array(SourceValue value) {
			AbstractInsnNode root = root(method, frames, value, new HashSet<>());
			return arrays.contains(root) ? root : null;
		}
		@Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
			// The Operation is loaded straight onto the stack for its call; a copy of it could reach anything.
			if (operation(value)) escaped = true;
			return super.copyOperation(insn, value);
		}
		@Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) {
			if (operation(value) || array(value) != null) escaped = true;
			return super.unaryOperation(insn, value);
		}
		@Override public SourceValue binaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b) {
			if (operation(a) || operation(b) || array(a) != null || array(b) != null) escaped = true;
			return super.binaryOperation(insn, a, b);
		}
		@Override public SourceValue ternaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b, SourceValue c) {
			if (operation(a) || operation(b) || operation(c) || array(b) != null || array(c) != null) escaped = true;
			AbstractInsnNode target = array(a);
			if (target != null) {
				if (insn.getOpcode() != Opcodes.AASTORE) escaped = true;
				else stores.put(insn, target);
			}
			return super.ternaryOperation(insn, a, b, c);
		}
		@Override public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> values) {
			boolean call = insn instanceof MethodInsnNode invoke && isOperationCall(invoke);
			for (int i = 0; i < values.size(); i++) {
				SourceValue value = values.get(i);
				if (operation(value) && !(call && i == 0)) escaped = true;
				if (array(value) != null && !(call && i == 1)) escaped = true;
			}
			return super.naryOperation(insn, values);
		}
		@Override public void returnOperation(AbstractInsnNode insn, SourceValue value, SourceValue expected) {
			if (operation(value) || array(value) != null) escaped = true;
			super.returnOperation(insn, value, expected);
		}
	}

	// ---- returned value --------------------------------------------------------------------------------------------

	/**
	 * Whether every path through the boolean {@code @WrapOperation} handler that returns, returns the value an
	 * {@code original.call} produced on that path: that value itself, or the constant the path has just proved it equal
	 * to by branching on it ({@code if (original.call(...)) return true; ... return false;}). Paths that throw are not
	 * returns. A handler with exception handlers, subroutines, or more paths than are worth enumerating is not proved.
	 */
	static boolean returnsOriginal(String owner, MethodNode handler) {
		if (!Type.getReturnType(handler.desc).equals(Type.BOOLEAN_TYPE) || !handler.tryCatchBlocks.isEmpty()) return false;
		Kinds interpreter = new Kinds();
		Frame<Kind> start = new Frame<>(handler.maxLocals, handler.maxStack);
		int slot = 0;
		if ((handler.access & Opcodes.ACC_STATIC) == 0) start.setLocal(slot++, interpreter.newValue(Type.getObjectType(owner.isEmpty() ? "java/lang/Object" : owner)));
		for (Type parameter : Type.getArgumentTypes(handler.desc)) {
			start.setLocal(slot++, interpreter.newValue(parameter));
			if (parameter.getSize() == 2) start.setLocal(slot++, interpreter.newValue(null));
		}
		while (slot < handler.maxLocals) start.setLocal(slot++, interpreter.newValue(null));
		record State(int index, Frame<Kind> frame, int known) { }
		Deque<State> work = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		work.push(new State(0, start, -1));
		boolean returned = false;
		int explored = 0;
		try {
			while (!work.isEmpty()) {
				State state = work.pop();
				if (++explored > MAX_STATES) return false;
				if (state.index >= handler.instructions.size()) return false;
				if (!seen.add(state.index + "|" + state.known + "|" + signature(state.frame))) continue;
				AbstractInsnNode instruction = handler.instructions.get(state.index);
				int opcode = instruction.getOpcode();
				if (opcode < 0) { work.push(new State(state.index + 1, state.frame, state.known)); continue; }
				if (opcode == Opcodes.JSR || opcode == Opcodes.RET) return false;
				if (opcode == Opcodes.ATHROW) continue;
				if (opcode == Opcodes.IRETURN) {
					Kind value = state.frame.getStack(state.frame.getStackSize() - 1);
					if (!(value.kind == Kind.ORIGINAL || value.kind == Kind.CONSTANT && state.known >= 0 && value.constant == state.known)) return false;
					returned = true;
					continue;
				}
				if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) return false;
				Frame<Kind> next = new Frame<>(state.frame);
				if (instruction instanceof JumpInsnNode jump) {
					Kind tested = state.frame.getStackSize() > 0 ? state.frame.getStack(state.frame.getStackSize() - 1) : null;
					if (opcode != Opcodes.GOTO) next.execute(instruction, interpreter);
					int target = handler.instructions.indexOf(jump.label);
					if (opcode == Opcodes.GOTO) { work.push(new State(target, next, state.known)); continue; }
					if ((opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) && tested != null && tested.kind == Kind.ORIGINAL) {
						int taken = opcode == Opcodes.IFEQ ? 0 : 1, fallen = 1 - taken;
						if (state.known < 0 || state.known == taken) work.push(new State(target, next, taken));
						if (state.known < 0 || state.known == fallen) work.push(new State(state.index + 1, new Frame<>(next), fallen));
						continue;
					}
					if ((opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) && tested != null && tested.kind == Kind.CONSTANT) {
						boolean jumps = opcode == Opcodes.IFEQ ? tested.constant == 0 : tested.constant != 0;
						work.push(new State(jumps ? target : state.index + 1, next, state.known));
						continue;
					}
					work.push(new State(target, next, state.known));
					work.push(new State(state.index + 1, new Frame<>(next), state.known));
					continue;
				}
				if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) {
					next.execute(instruction, interpreter);
					for (LabelNode label : labels(instruction)) work.push(new State(handler.instructions.indexOf(label), new Frame<>(next), state.known));
					continue;
				}
				next.execute(instruction, interpreter);
				work.push(new State(state.index + 1, next, state.known));
			}
		} catch (AnalyzerException | RuntimeException invalid) {
			return false;
		}
		return returned;
	}

	private static String signature(Frame<Kind> frame) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < frame.getLocals(); i++) out.append(frame.getLocal(i)).append(',');
		out.append('|');
		for (int i = 0; i < frame.getStackSize(); i++) out.append(frame.getStack(i)).append(',');
		return out.toString();
	}

	/** A value as {@link #returnsOriginal} reads it: what Mixin's basic verifier sees, plus where it came from. */
	private record Kind(BasicValue basic, int kind, int constant) implements Value {
		static final int OTHER = 0, BOXED = 1, ORIGINAL = 2, CONSTANT = 3;
		@Override public int getSize() { return basic.getSize(); }
		@Override public String toString() { return kind == CONSTANT ? "c" + constant : kind + basic.toString(); }
	}

	private static final class Kinds extends Interpreter<Kind> {
		private final BasicInterpreter basic = new BasicInterpreter();
		Kinds() { super(Opcodes.ASM9); }
		private static Kind of(BasicValue value) { return value == null ? null : new Kind(value, Kind.OTHER, 0); }
		@Override public Kind newValue(Type type) { return of(basic.newValue(type)); }
		@Override public Kind newOperation(AbstractInsnNode insn) throws AnalyzerException {
			BasicValue value = basic.newOperation(insn);
			int opcode = insn.getOpcode();
			if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return new Kind(value, Kind.CONSTANT, opcode - Opcodes.ICONST_0);
			if (insn instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return new Kind(value, Kind.CONSTANT, push.operand);
			return of(value);
		}
		@Override public Kind copyOperation(AbstractInsnNode insn, Kind value) { return value; }
		@Override public Kind unaryOperation(AbstractInsnNode insn, Kind value) throws AnalyzerException {
			BasicValue result = basic.unaryOperation(insn, value.basic);
			if (result == null) return null;
			if (insn.getOpcode() == Opcodes.CHECKCAST && value.kind == Kind.BOXED) return new Kind(result, Kind.BOXED, 0);
			return of(result);
		}
		@Override public Kind binaryOperation(AbstractInsnNode insn, Kind a, Kind b) throws AnalyzerException {
			return of(basic.binaryOperation(insn, a.basic, b.basic));
		}
		@Override public Kind ternaryOperation(AbstractInsnNode insn, Kind a, Kind b, Kind c) throws AnalyzerException {
			return of(basic.ternaryOperation(insn, a.basic, b.basic, c.basic));
		}
		@Override public Kind naryOperation(AbstractInsnNode insn, List<? extends Kind> values) throws AnalyzerException {
			BasicValue result = basic.naryOperation(insn, values.stream().map(Kind::basic).toList());
			if (result == null) return null;
			if (insn instanceof MethodInsnNode call) {
				if (isOperationCall(call)) return new Kind(result, Kind.BOXED, 0);
				if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals("java/lang/Boolean") && call.name.equals("booleanValue")
						&& call.desc.equals("()Z") && values.getFirst().kind() == Kind.BOXED) return new Kind(result, Kind.ORIGINAL, 0);
			}
			return of(result);
		}
		@Override public void returnOperation(AbstractInsnNode insn, Kind value, Kind expected) { }
		@Override public Kind merge(Kind a, Kind b) { return a.equals(b) ? a : of(basic.merge(a.basic, b.basic)); }
	}

	// ---- locals ----------------------------------------------------------------------------------------------------

	/**
	 * For each {@code @Local} the handler asks for, the slot of {@code live} at {@code livePoint} that holds what the slot
	 * MixinExtras resolves in {@code nativeMethod} at {@code nativePoint} held: the one slot of the same type whose value is
	 * produced by the same expression — the same calls of the same members, field reads, allocations, constants and
	 * parameters, all the way down. {@code nativeParameter} maps a live parameter position to the native parameter it
	 * carries, or -1. Handler parameter to live slot; null when any {@code @Local} is not proved.
	 */
	static Map<Integer, Integer> correspondLocals(MethodNode handler, String nativeOwner, MethodNode nativeMethod, AbstractInsnNode nativePoint,
			String liveOwner, MethodNode live, AbstractInsnNode livePoint, IntUnaryOperator nativeParameter) {
		MixinHandlerShape shape = nativeMethod == null ? null : MixinHandlerShape.of(handler, nativeMethod.desc, nativeMethod);
		if (shape == null) return null;
		Map<Integer, Integer> result = new LinkedHashMap<>();
		if (shape.locals().isEmpty()) return result;
		Frame<SourceValue>[] before = sources(nativeOwner, nativeMethod), after = sources(liveOwner, live);
		if (before == null || after == null) return null;
		int was = nativeMethod.instructions.indexOf(nativePoint), now = live.instructions.indexOf(livePoint);
		if (was < 0 || now < 0 || before[was] == null || after[now] == null) return null;
		IntFunction<String> nativeNames = parameters(nativeMethod, IntUnaryOperator.identity());
		IntFunction<String> liveNames = parameters(live, nativeParameter);
		for (MixinHandlerShape.Extra local : shape.locals()) {
			int slot = MixinLocalOriginProof.slot(local.sugar(), local.type(), nativeMethod, was);
			if (slot < 0 || slot >= before[was].getLocals()) return null;
			String wanted = origin(nativeMethod, before, before[was].getLocal(slot), slot, nativeNames, 0, new HashSet<>());
			if (wanted == null) return null;
			int match = -1;
			for (int candidate : MixinLocalOriginProof.typedSlots(live, now, local.type(), Boolean.TRUE.equals(MixinFit.value(local.sugar(), "argsOnly")))) {
				if (candidate >= after[now].getLocals()) continue;
				if (!wanted.equals(origin(live, after, after[now].getLocal(candidate), candidate, liveNames, 0, new HashSet<>()))) continue;
				if (match >= 0) return null;
				match = candidate;
			}
			if (match < 0) return null;
			result.put(local.parameter(), match);
		}
		return result;
	}

	/**
	 * Whether {@code local} names, in {@code nativeMethod} at {@code nativePoint}, a slot whose value is produced the way
	 * operand {@code operand} of {@code liveCall} in {@code live} is (operand 0 is an instance call's receiver): what a
	 * callback hands over in place of the {@code @Local} is then the value MixinExtras would have read natively.
	 */
	static boolean localIsOperand(MethodNode handler, MixinHandlerShape.Extra local, String nativeOwner, MethodNode nativeMethod,
			AbstractInsnNode nativePoint, String liveOwner, MethodNode live, MethodInsnNode liveCall, int operand) {
		Frame<SourceValue>[] before = sources(nativeOwner, nativeMethod), after = sources(liveOwner, live);
		if (before == null || after == null || local.role() != MixinHandlerShape.Role.LOCAL) return false;
		int was = nativeMethod.instructions.indexOf(nativePoint), now = live.instructions.indexOf(liveCall);
		if (was < 0 || now < 0 || before[was] == null || after[now] == null) return false;
		int slot = MixinLocalOriginProof.slot(local.sugar(), local.type(), nativeMethod, was);
		if (slot < 0 || slot >= before[was].getLocals()) return false;
		int operands = Type.getArgumentTypes(liveCall.desc).length + (liveCall.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		if (operand < 0 || operand >= operands) return false;
		String wanted = origin(nativeMethod, before, before[was].getLocal(slot), slot, parameters(nativeMethod, IntUnaryOperator.identity()), 0, new HashSet<>());
		String handed = top(live, after, after[now], operands - 1 - operand, parameters(live, IntUnaryOperator.identity()), 0, new HashSet<>());
		return wanted != null && wanted.equals(handed);
	}

	/**
	 * Without the native body: each {@code @Local} must be an {@code argsOnly} one with no discriminator, whose type is one
	 * parameter of the native descriptor {@code nativeDesc} and one parameter of {@code live} — MixinExtras then reads the
	 * one parameter of that type in either method; {@code nativeParameter} (live position to native position), when
	 * given, must say it is the same argument. Handler parameter to live slot; null when any {@code @Local} is not of
	 * that form.
	 */
	static Map<Integer, Integer> parameterLocals(MethodNode handler, String nativeDesc, MethodNode live, IntUnaryOperator nativeParameter) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler, nativeDesc, live);
		if (shape == null) return null;
		Map<Integer, Integer> result = new LinkedHashMap<>();
		for (MixinHandlerShape.Extra local : shape.locals()) {
			int slot = parameterSlot(local, nativeDesc, live, nativeParameter);
			if (slot < 0) return null;
			result.put(local.parameter(), slot);
		}
		return result;
	}

	/**
	 * {@link #parameterLocals}' rule for one {@code @Local}: an {@code argsOnly} one with no discriminator, of a type that
	 * is one parameter of {@code nativeDesc} and one of {@code live} (the same one by {@code nativeParameter}, when given),
	 * is that parameter's slot of {@code live}; -1 for any other.
	 */
	private static int parameterSlot(MixinHandlerShape.Extra local, String nativeDesc, MethodNode live, IntUnaryOperator nativeParameter) {
		AnnotationNode sugar = local.sugar();
		if (!Boolean.TRUE.equals(MixinFit.value(sugar, "argsOnly")) || discriminated(sugar)) return -1;
		int nativePosition = only(Type.getArgumentTypes(nativeDesc), local.type()), livePosition = only(Type.getArgumentTypes(live.desc), local.type());
		if (nativePosition < 0 || livePosition < 0 || nativeParameter != null && nativeParameter.applyAsInt(livePosition) != nativePosition) return -1;
		return slots(live)[livePosition];
	}

	/** Whether a {@code @Local} names its slot by {@code index}, {@code ordinal} or {@code name} rather than by its type alone. */
	private static boolean discriminated(AnnotationNode sugar) {
		return MixinFit.value(sugar, "index") instanceof Number n && n.intValue() >= 0
				|| MixinFit.value(sugar, "ordinal") instanceof Number o && o.intValue() >= 0 || !MixinFit.stringList(MixinFit.value(sugar, "name")).isEmpty();
	}

	/**
	 * Which native parameter each parameter of a method of {@code liveDesc} carries, where the platform kept the native
	 * method {@code nativeDesc}'s parameters, in their order, and inserted its own among them (the same return): live
	 * position {@code j} carries native position {@code k} only where every order-keeping placement of the native
	 * parameters among the live ones puts {@code k} at {@code j} — the leftmost and the rightmost placement agree there,
	 * so no type that repeats can be paired by guess. -1 for a live parameter no placement fixes. Null when the native
	 * parameters cannot be placed at all.
	 */
	static IntUnaryOperator projection(String nativeDesc, String liveDesc) {
		Type[] source, current;
		try {
			if (!Type.getReturnType(nativeDesc).equals(Type.getReturnType(liveDesc))) return null;
			source = Type.getArgumentTypes(nativeDesc);
			current = Type.getArgumentTypes(liveDesc);
		} catch (RuntimeException invalid) {
			return null;
		}
		int[] leftmost = new int[source.length], rightmost = new int[source.length];
		for (int k = 0, j = 0; k < source.length; k++, j++) {
			while (j < current.length && !current[j].equals(source[k])) j++;
			if (j == current.length) return null;
			leftmost[k] = j;
		}
		for (int k = source.length - 1, j = current.length - 1; k >= 0; k--, j--) {
			while (!current[j].equals(source[k])) j--;
			rightmost[k] = j;
		}
		int[] carried = new int[current.length];
		java.util.Arrays.fill(carried, -1);
		for (int k = 0; k < source.length; k++) if (leftmost[k] == rightmost[k]) carried[leftmost[k]] = k;
		return j -> j >= 0 && j < carried.length ? carried[j] : -1;
	}

	/**
	 * The {@code @Local}s of {@code handler}, written for the one point {@code nativePoint} of the native method
	 * {@code reference}, read where the live method {@code live} makes, at each of {@code livePoints}, the call that took
	 * that point's place: each {@code @Local} — annotated, or a target argument Mixin appends, read against the method it
	 * was written for as the {@code @Local(argsOnly = true)} it stands for — to the one slot of {@code live} that holds its
	 * value at every live point. With the native body ({@code reference}; {@code nativeDesc} is its descriptor either way)
	 * by producer ({@link #correspondLocals}), {@code nativeParameter} telling which native parameter a live one carries.
	 * Without it nothing records what a local held natively, and an {@code ordinal}, {@code index} or {@code name}
	 * describes the native body's locals, not the live one's: an {@code argsOnly} one is its parameter
	 * ({@link #parameterSlot}); any other is the local of its type the live call is itself handed, the value the platform
	 * passes where the native call stood ({@link MixinLocalOriginProof#atCall}), and where the call is handed none, the
	 * slot MixinExtras' own reading names at the live point ({@link MixinLocalOriginProof#slot}). Handler parameter to
	 * live slot; null when the handler has an extra that is no {@code @Local}, the call is handed two locals of one
	 * captured type, or any {@code @Local} is not read so.
	 */
	static Map<Integer, Integer> replacedCallLocals(MethodNode handler, String nativeOwner, MethodNode reference, AbstractInsnNode nativePoint,
			String nativeDesc, String liveOwner, MethodNode live, List<? extends AbstractInsnNode> livePoints, IntUnaryOperator nativeParameter) {
		if (live == null || livePoints == null || livePoints.isEmpty()) return null;
		MixinHandlerShape shape = MixinHandlerShape.of(handler, reference != null ? reference.desc : nativeDesc, reference);
		if (shape == null || shape.extras().stream().anyMatch(extra -> extra.role() != MixinHandlerShape.Role.LOCAL)) return null;
		Map<Integer, Integer> result = null;
		for (AbstractInsnNode point : livePoints) {
			Map<Integer, Integer> one;
			if (reference != null) one = nativePoint == null ? null
					: correspondLocals(handler, nativeOwner, reference, nativePoint, liveOwner, live, point, nativeParameter);
			else one = unrecordedLocals(shape, nativeDesc, liveOwner, live, point, nativeParameter);
			if (one == null || result != null && !result.equals(one)) return null;   // one annotation reads one slot at every point
			result = one;
		}
		return result;
	}

	/** {@link #replacedCallLocals} without the native body, at one live point. */
	private static Map<Integer, Integer> unrecordedLocals(MixinHandlerShape shape, String nativeDesc, String liveOwner, MethodNode live,
			AbstractInsnNode point, IntUnaryOperator nativeParameter) {
		int at = live.instructions.indexOf(point);
		if (at < 0) return null;
		Map<Integer, Integer> result = new LinkedHashMap<>();
		for (MixinHandlerShape.Extra local : shape.locals()) {
			AnnotationNode sugar = local.sugar();
			int slot = -1;
			if (Boolean.TRUE.equals(MixinFit.value(sugar, "argsOnly"))) slot = parameterSlot(local, nativeDesc, live, nativeParameter);
			else {
				if (point instanceof MethodInsnNode call) for (int candidate : MixinLocalOriginProof.typedSlots(live, at, local.type(), false)) {
					MixinLocalOriginProof.CallValue handed = MixinLocalOriginProof.atCall(liveOwner, live, call, candidate);
					if (handed == null || handed.operand() < 0) continue;
					if (slot >= 0) return null;   // the call is handed two of them: which one is a guess
					slot = candidate;
				}
				if (slot < 0) slot = MixinLocalOriginProof.slot(sugar, local.type(), live, at);
			}
			if (slot < 0) return null;
			result.put(local.parameter(), slot);
		}
		return result;
	}

	private static int only(Type[] types, Type type) {
		int found = -1;
		for (int i = 0; i < types.length; i++) if (types[i].equals(type)) { if (found >= 0) return -1; found = i; }
		return found;
	}

	/**
	 * Which argument of {@code nativeCall} (made in {@code nativeCaller}) each argument of {@code liveCall} (made in
	 * {@code liveCaller}) carries: argument {@code j} of the live call is argument {@code k} of the native call when both
	 * are produced by the same expression, and no other native argument is. -1 where none or several are. The callers
	 * must take the same parameters, so a parameter is the same value in both.
	 */
	static int[] argumentCorrespondence(String nativeOwner, MethodNode nativeCaller, MethodInsnNode nativeCall, String liveOwner,
			MethodNode liveCaller, MethodInsnNode liveCall) {
		int[] none = new int[Type.getArgumentTypes(liveCall.desc).length];
		java.util.Arrays.fill(none, -1);
		if (!nativeCaller.desc.equals(liveCaller.desc) || ((nativeCaller.access ^ liveCaller.access) & Opcodes.ACC_STATIC) != 0) return none;
		Frame<SourceValue>[] before = sources(nativeOwner, nativeCaller), after = sources(liveOwner, liveCaller);
		if (before == null || after == null) return none;
		int was = nativeCaller.instructions.indexOf(nativeCall), now = liveCaller.instructions.indexOf(liveCall);
		if (was < 0 || now < 0 || before[was] == null || after[now] == null) return none;
		String[] nativeArguments = arguments(nativeCaller, before[was], nativeCall, before), liveArguments = arguments(liveCaller, after[now], liveCall, after);
		int[] mapping = none.clone();
		for (int j = 0; j < liveArguments.length; j++) {
			if (liveArguments[j] == null) continue;
			int found = -1;
			for (int k = 0; k < nativeArguments.length; k++) if (liveArguments[j].equals(nativeArguments[k])) found = found == -1 ? k : -2;
			mapping[j] = found < 0 ? -1 : found;
		}
		return mapping;
	}

	private static String[] arguments(MethodNode caller, Frame<SourceValue> frame, MethodInsnNode call, Frame<SourceValue>[] frames) {
		int count = Type.getArgumentTypes(call.desc).length;
		String[] out = new String[count];
		IntFunction<String> names = parameters(caller, IntUnaryOperator.identity());
		for (int i = 0; i < count; i++) out[i] = top(caller, frames, frame, count - 1 - i, names, 0, new HashSet<>());
		return out;
	}

	/**
	 * Points each {@code @Local} of {@code mapping} (handler parameter to slot) at its slot of {@code live} at
	 * {@code livePoint}, where MixinExtras' own reading of the annotation would name another: the slot becomes the
	 * {@code index}, and {@code ordinal} and {@code name} — which MixinExtras reads before an index — are dropped. A
	 * target argument the handler took unannotated (an implicit {@code @Local}) is given {@code @Local(index = slot)}:
	 * moved, Mixin would otherwise append the new method's argument there. Returns how many were pinned.
	 */
	static int pinLocals(MethodNode handler, MethodNode live, AbstractInsnNode livePoint, Map<Integer, Integer> mapping) {
		int at = live.instructions.indexOf(livePoint), pinned = 0;
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		// A target argument read as its @Local (MixinHandlerShape's implicit one) has no annotation to pin: it is given one.
		java.util.Set<Integer> implicit = new HashSet<>();
		for (int parameter : mapping.keySet()) if (!MixinFit.sugar(handler, parameter)) implicit.add(parameter);
		if (!MixinHandlerShape.annotateImplicit(handler, mapping)) return 0;
		pinned += implicit.size();
		for (var entry : mapping.entrySet()) {
			if (implicit.contains(entry.getKey())) continue;
			AnnotationNode local = MixinStubRebind.sugar(handler, entry.getKey(), MixinRetarget.LOCAL_SUGAR);
			if (local == null || MixinLocalOriginProof.slot(local, parameters[entry.getKey()], live, at) == entry.getValue()) continue;
			if (local.values != null) for (int i = local.values.size() - 2; i >= 0; i -= 2)
				if ("ordinal".equals(local.values.get(i)) || "name".equals(local.values.get(i))) { local.values.remove(i + 1); local.values.remove(i); }
			MixinPlayerWorldCallbackAdapter.set(local, "index", entry.getValue());
			pinned++;
		}
		return pinned;
	}

	/** Names a slot that holds a parameter on entry: {@code this}, or the native parameter position it carries. */
	private static IntFunction<String> parameters(MethodNode method, IntUnaryOperator nativeParameter) {
		Map<Integer, String> names = new HashMap<>();
		int slot = 0, position = 0;
		if ((method.access & Opcodes.ACC_STATIC) == 0) names.put(slot++, "this");
		for (Type type : Type.getArgumentTypes(method.desc)) {
			int mapped = nativeParameter.applyAsInt(position);
			names.put(slot, mapped < 0 ? "unmapped-parameter " + position : "parameter " + mapped);
			slot += type.getSize();
			position++;
		}
		return names::get;
	}

	/**
	 * The expression that produced {@code value} in {@code method}, spelled from its instructions; null when it cannot be
	 * spelled (several producers that differ, an increment, an operation this does not spell, or deeper than worth it).
	 */
	private static String origin(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int slot, IntFunction<String> parameters,
			int depth, Set<AbstractInsnNode> active) {
		if (value == null || depth > 24) return null;
		if (value.insns.isEmpty()) return slot < 0 ? null : parameters.apply(slot);
		String spelled = null;
		for (AbstractInsnNode instruction : value.insns) {
			String one = origin(method, frames, instruction, parameters, depth, active);
			if (one == null || spelled != null && !spelled.equals(one)) return null;
			spelled = one;
		}
		return spelled;
	}

	private static String origin(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode instruction, IntFunction<String> parameters,
			int depth, Set<AbstractInsnNode> active) {
		if (!active.add(instruction)) return null;
		try {
			int index = method.instructions.indexOf(instruction);
			Frame<SourceValue> frame = index < 0 ? null : frames[index];
			if (frame == null) return null;
			int opcode = instruction.getOpcode();
			if (instruction instanceof VarInsnNode variable) {
				if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD)
					return origin(method, frames, frame.getLocal(variable.var), variable.var, parameters, depth + 1, active);
				if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) return top(method, frames, frame, 0, parameters, depth, active);
				return null;
			}
			if (opcode == Opcodes.DUP) return top(method, frames, frame, 0, parameters, depth, active);
			if (instruction instanceof TypeInsnNode type) {
				if (opcode == Opcodes.CHECKCAST) {
					String inner = top(method, frames, frame, 0, parameters, depth, active);
					return inner == null ? null : "cast " + type.desc + " " + inner;
				}
				if (opcode == Opcodes.NEW) {
					String constructed = constructorOf(method, frames, type);
					return constructed == null ? null : "new " + type.desc + constructed + arguments(method, frames, type, parameters, depth, active);
				}
				return null;
			}
			if (instruction instanceof MethodInsnNode call) {
				int operands = Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
				StringBuilder out = new StringBuilder("call ").append(call.owner).append('.').append(call.name).append(call.desc).append('(');
				for (int i = operands - 1; i >= 0; i--) {
					String operand = top(method, frames, frame, i, parameters, depth, active);
					if (operand == null) return null;
					out.append(operand).append(';');
				}
				return out.append(')').toString();
			}
			if (instruction instanceof FieldInsnNode field) {
				if (opcode == Opcodes.GETSTATIC) return "static " + field.owner + '.' + field.name + field.desc;
				if (opcode == Opcodes.GETFIELD) {
					String receiver = top(method, frames, frame, 0, parameters, depth, active);
					return receiver == null ? null : "field " + field.owner + '.' + field.name + field.desc + '(' + receiver + ')';
				}
				return null;
			}
			if (instruction instanceof LdcInsnNode constant) return "ldc " + constant.cst;
			if (instruction instanceof IntInsnNode constant && opcode != Opcodes.NEWARRAY) return "int " + constant.operand;
			if (opcode == Opcodes.ACONST_NULL) return "null";
			if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) return "const " + opcode;
			if (instruction instanceof InvokeDynamicInsnNode dynamic) {
				StringBuilder out = new StringBuilder("indy ").append(dynamic.name).append(dynamic.desc).append(dynamic.bsm).append(java.util.Arrays.toString(dynamic.bsmArgs)).append('(');
				int operands = Type.getArgumentTypes(dynamic.desc).length;
				for (int i = operands - 1; i >= 0; i--) {
					String operand = top(method, frames, frame, i, parameters, depth, active);
					if (operand == null) return null;
					out.append(operand).append(';');
				}
				return out.append(')').toString();
			}
			return null;
		} finally {
			active.remove(instruction);
		}
	}

	/** The constructor arguments of the one constructor call that initialises {@code allocation}, spelled. */
	private static String arguments(MethodNode method, Frame<SourceValue>[] frames, TypeInsnNode allocation, IntFunction<String> parameters,
			int depth, Set<AbstractInsnNode> active) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.name.equals("<init>")
					|| !call.owner.equals(allocation.desc)) continue;
			Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
			int arguments = Type.getArgumentTypes(call.desc).length;
			if (frame == null || frame.getStackSize() < arguments + 1
					|| root(method, frames, frame.getStack(frame.getStackSize() - arguments - 1), new HashSet<>()) != allocation) continue;
			StringBuilder out = new StringBuilder("(");
			for (int i = arguments - 1; i >= 0; i--) {
				String argument = top(method, frames, frame, i, parameters, depth, active);
				if (argument == null) return null;
				out.append(argument).append(';');
			}
			return out.append(')').toString();
		}
		return null;
	}

	/** The origin of the stack value {@code below} entries under the top of {@code frame}. */
	private static String top(MethodNode method, Frame<SourceValue>[] frames, Frame<SourceValue> frame, int below, IntFunction<String> parameters,
			int depth, Set<AbstractInsnNode> active) {
		int at = frame.getStackSize() - 1 - below;
		return at < 0 ? null : origin(method, frames, frame.getStack(at), -1, parameters, depth + 1, active);
	}

	// ---- shared --------------------------------------------------------------------------------------------------------

	/** The instruction a value was made by, through copies, casts and the locals it was kept in; null when not one. */
	static AbstractInsnNode root(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, Set<AbstractInsnNode> seen) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode instruction = value.insns.iterator().next();
		if (!seen.add(instruction)) return null;
		int index = method.instructions.indexOf(instruction);
		Frame<SourceValue> frame = index < 0 ? null : frames[index];
		if (frame == null) return null;
		int opcode = instruction.getOpcode();
		if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD)
			return root(method, frames, frame.getLocal(variable.var), seen);
		if (instruction instanceof VarInsnNode && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE || opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST)
			return frame.getStackSize() == 0 ? null : root(method, frames, frame.getStack(frame.getStackSize() - 1), seen);
		return instruction;
	}

	/** The int constant {@code value} was pushed as; -1 when it is not one. */
	private static int constant(SourceValue value) {
		if (value == null || value.insns.size() != 1) return -1;
		AbstractInsnNode instruction = value.insns.iterator().next();
		int opcode = instruction.getOpcode();
		if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (instruction instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
		return -1;
	}

	/** The local slot of each parameter of {@code method}, receiver excluded. */
	static int[] slots(MethodNode method) {
		Type[] parameters = Type.getArgumentTypes(method.desc);
		int[] slots = new int[parameters.length];
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (int i = 0; i < parameters.length; i++) { slots[i] = slot; slot += parameters[i].getSize(); }
		return slots;
	}

	/** Whether no instruction of {@code method} loads, stores or increments {@code slot}. */
	static boolean unread(MethodNode method, int slot) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode variable && variable.var == slot) return false;
			if (instruction instanceof IincInsnNode increment && increment.var == slot) return false;
		}
		return true;
	}

	static Frame<SourceValue>[] sources(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner == null || owner.isEmpty() ? "java/lang/Object" : owner, method);
		} catch (AnalyzerException | RuntimeException invalid) {
			return null;
		}
	}
}
