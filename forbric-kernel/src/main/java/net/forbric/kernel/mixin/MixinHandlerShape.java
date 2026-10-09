/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A handler's parameters the way its injector reads them: first the operands the injector itself hands over — a wrapped
 * call's receiver and arguments then its {@code Operation}, a target's arguments then its {@code CallbackInfo}, a
 * redirected call's operands, the value being modified — then the extras the handler chose to ask for: MixinExtras
 * {@code @Local}, {@code @Share} and {@code @Cancellable} sugar, the locals Mixin captures after a callback, the target's
 * arguments an injector may append. Two handlers with the same operands answer the same callback contract whatever
 * extras each asks for; which value an extra receives is a fact about the target body — the slot MixinExtras resolves a
 * {@code @Local} to ({@link #localSlots}) and its producer there ({@link #proveLocals}, by MixinLocalOriginProof) —
 * never a property of the extra list one mod happened to declare.
 *
 * <p>Where the operands end is the injector's own rule (Mixin 0.8.7's {@code Injector.validateParams}, MixinExtras
 * 0.5.4's injectors): after the {@code CallbackInfo} of an {@code @Inject} and the {@code Operation} of a
 * {@code @WrapOperation}; after the one value a {@code @ModifyConstant}, {@code @ModifyVariable},
 * {@code @ModifyExpressionValue} or {@code @ModifyReturnValue} modifies and the {@code Args} of a {@code @ModifyArgs};
 * after the instruction's operands for a {@code @Redirect}, {@code @WrapWithCondition} or {@code @ModifyReceiver} (the
 * receiver, unless the access is static, then the arguments or the stored value). A {@code @ModifyArg} takes no more
 * than the call's arguments. Every unannotated value after the operands but an {@code @Inject}'s is a target argument
 * ({@link Role#ARGUMENT}): the {@code k}-th value appended is the target method's {@code k}-th parameter.
 *
 * <p>Read against the method it is written for ({@link #of(MethodNode, String, MethodNode)}), a target argument is what
 * a {@code @Local(argsOnly = true)} of its type and position would read: it becomes an implicit {@code @Local}
 * ({@link Extra#implicit}), served, proved and moved exactly as the annotated one is. Read alone ({@link #of(MethodNode)}),
 * nothing says which method it is a parameter of, so it is no {@code @Local}.
 */
final class MixinHandlerShape {
	static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	static final String RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	static final String ARGS = "Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;";
	private static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";
	static final String LOCAL = SUGAR + "Local;";
	private static final String SHARE = SUGAR + "Share;", CANCELLABLE = SUGAR + "Cancellable;";
	private static final String COERCE = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
	private static final String MODIFY_RECEIVER = "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;";

	/** What an extra asks for. {@code CAPTURED}: a local Mixin's {@code locals} capture fills; {@code ARGUMENT}: a target argument appended unannotated. */
	enum Role { LOCAL, SHARE, CANCELLABLE, SUGAR, CAPTURED, ARGUMENT }

	/**
	 * One extra: the handler parameter, its type, what it asks for, and the sugar annotation that says so (null for none).
	 * {@code implicit}: a target argument read as the {@code @Local(argsOnly = true)} it is equivalent to — role
	 * {@code LOCAL}, its {@code sugar} that equivalent annotation, which the handler itself does not carry.
	 */
	record Extra(int parameter, Type type, Role role, AnnotationNode sugar, boolean implicit) {
		Extra(int parameter, Type type, Role role, AnnotationNode sugar) { this(parameter, type, role, sugar, false); }
	}

	/** An extra an adapter can serve: a role and a type. */
	record Want(Role role, Type type) {
		static Want local(String descriptor) { return new Want(Role.LOCAL, Type.getType(descriptor)); }
		static Want share(String descriptor) { return new Want(Role.SHARE, Type.getType(descriptor)); }
		static Want captured(String descriptor) { return new Want(Role.CAPTURED, Type.getType(descriptor)); }
		static Want cancellable() { return new Want(Role.CANCELLABLE, Type.getType(CALLBACK)); }
		boolean serves(Extra extra) { return role == extra.role() && type.equals(extra.type()); }
	}

	private final MethodNode handler;
	private final String kind;
	private final Type returns;
	private final List<Type> operands;
	private final List<Extra> extras;

	private MixinHandlerShape(MethodNode handler, String kind, Type returns, List<Type> operands, List<Extra> extras) {
		this.handler = handler;
		this.kind = kind;
		this.returns = returns;
		this.operands = operands;
		this.extras = extras;
	}

	/** The shape of an injector handler; null for a method without an injector. Target arguments stay {@link Role#ARGUMENT}s. */
	static MixinHandlerShape of(MethodNode handler) {
		return of(handler, null, null, false);
	}

	/**
	 * The shape of a handler read against the method it is written for, whose descriptor is {@code targetDesc}: each target
	 * argument it appends is that method's parameter at its position, an implicit {@code @Local(argsOnly = true)}
	 * ({@link Extra#implicit}). {@code body}, when given, is a body holding the instruction the handler's point names — the
	 * written-for method or one the point is found in alike — and settles whether a redirected access is static where the
	 * types alone do not. Null for a method without an injector, and for a handler whose appended values are not that
	 * method's parameters (Mixin refuses it there).
	 */
	static MixinHandlerShape of(MethodNode handler, String targetDesc, MethodNode body) {
		return targetDesc == null ? of(handler) : of(handler, targetDesc, body, true);
	}

	private static MixinHandlerShape of(MethodNode handler, String targetDesc, MethodNode body, boolean bound) {
		AnnotationNode injector = handler == null ? null : MixinFit.injectorOf(handler);
		if (injector == null && handler != null) injector = receiverModifier(handler);
		if (injector == null) return null;
		String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		Type returns = Type.getReturnType(handler.desc);
		int sugar = parameters.length;
		for (int i = 0; i < parameters.length; i++) if (MixinFit.sugar(handler, i)) { sugar = i; break; }
		int end = switch (kind) {
			case "Inject" -> after(parameters, CALLBACK, RETURNABLE);
			case "WrapOperation", "WrapMethod" -> after(parameters, OPERATION, null);
			case "ModifyConstant", "ModifyVariable", "ModifyExpressionValue", "ModifyReturnValue" -> 1;
			case "ModifyArgs" -> after(parameters, ARGS, null);
			case "Redirect", "WrapWithCondition", "ModifyReceiver" -> instructionOperands(handler, kind, injector, parameters, sugar, returns, body);
			default -> sugar;   // @ModifyArg: one argument, or exactly the call's arguments (Mixin 0.8.7 ModifyArgInjector)
		};
		end = Math.min(end < 0 ? sugar : end, sugar);
		Type[] arguments = bound ? Type.getArgumentTypes(targetDesc) : null;
		List<Extra> extras = new ArrayList<>();
		for (int i = end; i < parameters.length; i++) {
			AnnotationNode annotation = annotation(handler, i, SUGAR);
			if (annotation != null) {
				Role role = LOCAL.equals(annotation.desc) ? Role.LOCAL : SHARE.equals(annotation.desc) ? Role.SHARE
						: CANCELLABLE.equals(annotation.desc) ? Role.CANCELLABLE : Role.SUGAR;
				extras.add(new Extra(i, parameters[i], role, annotation));
			} else if ("Inject".equals(kind)) {
				extras.add(new Extra(i, parameters[i], Role.CAPTURED, null));
			} else if (!bound) {
				extras.add(new Extra(i, parameters[i], Role.ARGUMENT, null));
			} else {
				// Mixin hands over the target's arguments in order, from its first, each of exactly the declared type
				// unless @Coerce widens it (which a typed @Local cannot read).
				int position = i - end;
				if (position >= arguments.length || !arguments[position].equals(parameters[i]) || annotation(handler, i, COERCE) != null) return null;
				extras.add(new Extra(i, parameters[i], Role.LOCAL, argumentLocal(arguments, position), true));
			}
		}
		return new MixinHandlerShape(handler, kind, returns, List.of(Arrays.copyOf(parameters, end)), List.copyOf(extras));
	}

	/**
	 * MixinExtras' {@code @ModifyReceiver}, which {@link MixinFit#injectorOf} does not read: its operands are read here
	 * like any injector's, so a callback adapter never takes one of its values for another role.
	 */
	private static AnnotationNode receiverModifier(MethodNode handler) {
		for (List<AnnotationNode> annotations : Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations))
			if (annotations != null) for (AnnotationNode annotation : annotations) if (MODIFY_RECEIVER.equals(annotation.desc)) return annotation;
		return null;
	}

	/**
	 * The {@code @Local} that reads parameter {@code position} of a method taking {@code arguments}, as MixinExtras reads
	 * one: {@code argsOnly}, and an {@code ordinal} among the parameters of its type only where there are several.
	 */
	private static AnnotationNode argumentLocal(Type[] arguments, int position) {
		AnnotationNode local = new AnnotationNode(LOCAL);
		local.values = new ArrayList<>(List.of("argsOnly", Boolean.TRUE));
		int ordinal = 0, same = 0;
		for (int i = 0; i < arguments.length; i++) if (arguments[i].equals(arguments[position])) { same++; if (i < position) ordinal++; }
		if (same > 1) { local.values.add("ordinal"); local.values.add(ordinal); }
		return local;
	}

	/**
	 * How many leading parameters a handler takes from the instruction its one point names: an instance access's receiver
	 * then the call's arguments, or the field's value for a write, or nothing more for a read. -1 where that does not
	 * settle it: a point naming no member with a descriptor, a variant with {@code args}, an access the injector does not
	 * take, a field access not known to be a read or a write, or a receiver neither the instruction ({@code body},
	 * {@code opcode}) nor the types tell apart from a first target argument.
	 *
	 * <p>Whether a field access hands over the value is what the instruction is, as Mixin and MixinExtras read it off the
	 * instruction they inject at ({@link #fieldAccess}): a {@code PUTFIELD}/{@code PUTSTATIC} hands over the value written,
	 * a {@code GETFIELD}/{@code GETSTATIC} none. The handler's return type is no witness of that: a
	 * {@code @WrapWithCondition} returns a boolean and a {@code @ModifyReceiver} its receiver whichever it wraps.
	 */
	private static int instructionOperands(MethodNode handler, String kind, AnnotationNode injector, Type[] parameters, int sugar, Type returns, MethodNode body) {
		List<AnnotationNode> ats = MixinFit.atNodes(injector);
		if (ats.size() != 1 || MixinFit.value(ats.getFirst(), "args") != null) return -1;
		AnnotationNode at = ats.getFirst();
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
		if (value == null || member == null || member.desc() == null) return -1;
		List<Type> plain;
		Boolean isStatic = null;
		if ("INVOKE".equals(value) && member.desc().startsWith("(")) {
			plain = List.of(Type.getArgumentTypes(member.desc()));
		} else if ("FIELD".equals(value) && !member.desc().startsWith("(")) {
			int[] access = fieldAccess(kind, at, returns, body);
			if (access == null) return -1;
			plain = access[0] == 1 ? List.of(Type.getType(member.desc())) : List.of();
			isStatic = access[1] < 0 ? null : access[1] == 1;
		} else return -1;
		if (isStatic == null && body != null) {
			List<AbstractInsnNode> found = MixinCallbackProofs.points(body, at);
			if (found != null) for (AbstractInsnNode instruction : found) {
				boolean one = instruction instanceof MethodInsnNode call ? call.getOpcode() == Opcodes.INVOKESTATIC
						: instruction instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC);
				if (isStatic != null && isStatic != one) { isStatic = null; break; }
				isStatic = one;
			}
		}
		boolean asStatic = leads(handler, parameters, 0, plain, sugar);
		boolean asInstance = member.owner() != null && sugar > 0 && parameters[0].equals(Type.getObjectType(member.owner()))
				&& leads(handler, parameters, 1, plain, sugar);
		if (isStatic != null) return isStatic ? (asStatic ? plain.size() : -1) : (asInstance ? plain.size() + 1 : -1);
		// A coerced first parameter may be a widened receiver: the types alone cannot say.
		if (sugar > 0 && annotation(handler, 0, COERCE) != null) return -1;
		return asStatic == asInstance ? -1 : asStatic ? plain.size() : plain.size() + 1;
	}

	/**
	 * What field access the point {@code at} of a {@code kind} injector selects: {@code {write, static}}, write 1 for a
	 * {@code PUTFIELD}/{@code PUTSTATIC} and 0 for a {@code GETFIELD}/{@code GETSTATIC}, static 1/0, or -1 where only
	 * the kind of access is known. Read off the point's {@code opcode}, else off the instructions it selects in
	 * {@code body} (all of one kind); where neither says, off what the injector itself can be: a
	 * {@code @WrapWithCondition} wraps only an instruction that leaves nothing (a write), and Mixin's {@code @Redirect}
	 * of a read returns the field's value, of a write nothing. Null where nothing settles it (a {@code @ModifyReceiver}
	 * takes a read and a write alike), the selected instructions differ, or the injector cannot take the access (a
	 * {@code @WrapWithCondition} of a read, a {@code @ModifyReceiver} of a static field).
	 */
	private static int[] fieldAccess(String kind, AnnotationNode at, Type returns, MethodNode body) {
		List<Integer> opcodes = new ArrayList<>();
		if (MixinFit.value(at, "opcode") instanceof Number opcode && opcode.intValue() >= 0) opcodes.add(opcode.intValue());
		else if (body != null) {
			List<AbstractInsnNode> found = MixinCallbackProofs.points(body, at);
			if (found != null) for (AbstractInsnNode instruction : found) if (instruction instanceof FieldInsnNode field) opcodes.add(field.getOpcode());
		}
		int write = -1, statik = -1;
		for (int opcode : opcodes) {
			int one = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC ? 1 : opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC ? 0 : -1;
			int alone = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC ? 1 : 0;
			if (one < 0 || write >= 0 && write != one || statik >= 0 && statik != alone) return null;
			write = one;
			statik = alone;
		}
		if (write < 0) write = switch (kind) {
			case "WrapWithCondition" -> 1;
			case "Redirect" -> Type.VOID_TYPE.equals(returns) ? 1 : 0;
			default -> -1;
		};
		if (write < 0 || "WrapWithCondition".equals(kind) && write == 0 || "ModifyReceiver".equals(kind) && statik == 1) return null;
		return new int[]{write, statik};
	}

	/** Whether parameters {@code from..} before {@code sugar} start with exactly {@code plain}, none of them coerced. */
	private static boolean leads(MethodNode handler, Type[] parameters, int from, List<Type> plain, int sugar) {
		if (from + plain.size() > sugar) return false;
		for (int i = 0; i < plain.size(); i++)
			if (!parameters[from + i].equals(plain.get(i)) || annotation(handler, from + i, COERCE) != null) return false;
		return true;
	}

	/** {@code "Inject"}, {@code "WrapOperation"}, … : the injector annotation's simple name. */
	String kind() { return kind; }
	Type returns() { return returns; }
	List<Type> operands() { return operands; }
	List<Extra> extras() { return extras; }

	/** Whether the operands and return type are exactly those of the method descriptor {@code descriptor}. */
	boolean operands(String descriptor) {
		try {
			return returns.equals(Type.getReturnType(descriptor)) && operands.equals(List.of(Type.getArgumentTypes(descriptor)));
		} catch (RuntimeException notAMethodDescriptor) {
			return false;
		}
	}

	/** Whether the extras are exactly {@code wants}, in order, by role and type. */
	boolean extras(Want... wants) {
		if (wants.length != extras.size()) return false;
		for (int i = 0; i < wants.length; i++) if (!wants[i].serves(extras.get(i))) return false;
		return true;
	}

	/** {@link #operands(String)} and {@link #extras(Want...)} together. */
	boolean matches(String descriptor, Want... wants) {
		return operands(descriptor) && extras(wants);
	}

	/**
	 * The extras as a subset of what an adapter can serve: extra index to the index in {@code offered} that serves it, each
	 * offer serving at most one extra, an extra the handler did not declare simply not asked for. Null when an extra has no
	 * offer, or when one role and type is offered more than once: same-typed values are told apart by the native slot each
	 * {@code @Local} names ({@link #localSlots}), never by the order one mod declared them in.
	 */
	Map<Integer, Integer> within(List<Want> offered) {
		for (int i = 0; i < offered.size(); i++) for (int k = i + 1; k < offered.size(); k++) if (offered.get(i).equals(offered.get(k))) return null;
		Map<Integer, Integer> served = new LinkedHashMap<>();
		for (int i = 0; i < extras.size(); i++) {
			int offer = -1;
			for (int k = 0; k < offered.size(); k++) if (offered.get(k).serves(extras.get(i))) offer = k;
			if (offer < 0 || served.containsValue(offer)) return null;
			served.put(i, offer);
		}
		return served;
	}

	/** The {@code @Local} extras, in declaration order: the annotated ones and, read against a method, the implicit ones. */
	List<Extra> locals() {
		return extras.stream().filter(extra -> extra.role() == Role.LOCAL).toList();
	}

	/**
	 * For each {@code @Local} extra (by handler parameter), the slot of {@code reference} it names at {@code point}, read as
	 * MixinExtras reads it ({@link MixinLocalOriginProof#slot}: {@code argsOnly}, {@code index}, {@code name}, {@code ordinal},
	 * else the one live local of its type). Null when any names no slot or more than one, or {@code point} is not in
	 * {@code reference}.
	 */
	Map<Integer, Integer> localSlots(MethodNode reference, AbstractInsnNode point) {
		int at = reference == null || point == null ? -1 : reference.instructions.indexOf(point);
		if (at < 0) return null;
		Map<Integer, Integer> slots = new HashMap<>();
		for (Extra local : locals()) {
			int slot = MixinLocalOriginProof.slot(local.sugar(), local.type(), reference, at);
			if (slot < 0) return null;
			slots.put(local.parameter(), slot);
		}
		return Map.copyOf(slots);
	}

	/**
	 * For a handler whose anchor moves from {@code point} in the native {@code reference} to {@code currentPoint} in the
	 * merged {@code current} body of the same method: each {@code @Local} extra (by handler parameter) proved, by its
	 * producer, to one current slot ({@link MixinLocalOriginProof#prove}). Null when any is not proved.
	 */
	Map<Integer, Integer> proveLocals(String owner, MethodNode reference, AbstractInsnNode point, MethodNode current, AbstractInsnNode currentPoint) {
		return MixinLocalOriginProof.prove(handler, owner, reference, point, current, currentPoint);
	}

	/**
	 * Gives each implicit {@code @Local} of {@code handler} among {@code slots} (handler parameter to slot) — a mapped
	 * parameter that carries no sugar — the annotation {@code @Local(index = slot)}: once its selector or point moves to
	 * another method, the value Mixin would append there is that method's argument, no longer the one the handler was
	 * written to read. Sugar must follow every value Mixin hands over, so the parameters from the first such one on must
	 * all be sugar or mapped; false (and nothing changed) otherwise. True when there was nothing to annotate.
	 */
	static boolean annotateImplicit(MethodNode handler, Map<Integer, Integer> slots) {
		int count = Type.getArgumentTypes(handler.desc).length;
		List<Integer> implicit = slots.keySet().stream().filter(p -> p >= 0 && p < count && annotation(handler, p, SUGAR) == null).sorted().toList();
		if (implicit.isEmpty()) return true;
		for (int p = implicit.getFirst(); p < count; p++) if (annotation(handler, p, SUGAR) == null && !slots.containsKey(p)) return false;
		if (handler.invisibleParameterAnnotations == null) {
			@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[count];
			handler.invisibleParameterAnnotations = table;
		} else if (handler.invisibleParameterAnnotations.length < count) {
			handler.invisibleParameterAnnotations = Arrays.copyOf(handler.invisibleParameterAnnotations, count);
		}
		if (handler.invisibleAnnotableParameterCount > 0) handler.invisibleAnnotableParameterCount = count;
		for (int parameter : implicit) {
			AnnotationNode local = new AnnotationNode(LOCAL);
			local.values = new ArrayList<>(List.of("index", slots.get(parameter)));
			if (handler.invisibleParameterAnnotations[parameter] == null) handler.invisibleParameterAnnotations[parameter] = new ArrayList<>();
			handler.invisibleParameterAnnotations[parameter].add(local);
		}
		return true;
	}

	/** The index just past the first parameter of descriptor {@code a} or {@code b}; -1 when there is none. */
	private static int after(Type[] parameters, String a, String b) {
		for (int i = 0; i < parameters.length; i++) {
			String descriptor = parameters[i].getDescriptor();
			if (descriptor.equals(a) || descriptor.equals(b)) return i + 1;
		}
		return -1;
	}

	/** Parameter {@code parameter}'s annotation whose descriptor starts with {@code prefix}; null for none. */
	private static AnnotationNode annotation(MethodNode handler, int parameter, String prefix) {
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (table == null || parameter >= table.length || table[parameter] == null) continue;
			for (AnnotationNode annotation : table[parameter]) if (annotation.desc.startsWith(prefix)) return annotation;
		}
		return null;
	}
}
