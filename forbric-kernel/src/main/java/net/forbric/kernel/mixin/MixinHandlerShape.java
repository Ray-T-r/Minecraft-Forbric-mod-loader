/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
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
 */
final class MixinHandlerShape {
	static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	static final String RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";
	private static final String LOCAL = SUGAR + "Local;", SHARE = SUGAR + "Share;", CANCELLABLE = SUGAR + "Cancellable;";

	/** What an extra asks for. {@code CAPTURED}: a local Mixin's {@code locals} capture fills; {@code ARGUMENT}: a target argument appended unannotated. */
	enum Role { LOCAL, SHARE, CANCELLABLE, SUGAR, CAPTURED, ARGUMENT }

	/** One extra: the handler parameter, its type, what it asks for, and the sugar annotation that says so (null for none). */
	record Extra(int parameter, Type type, Role role, AnnotationNode sugar) { }

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

	/** The shape of an injector handler; null for a method without an injector. */
	static MixinHandlerShape of(MethodNode handler) {
		AnnotationNode injector = handler == null ? null : MixinFit.injectorOf(handler);
		if (injector == null) return null;
		String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		int sugar = parameters.length;
		for (int i = 0; i < parameters.length; i++) if (MixinFit.sugar(handler, i)) { sugar = i; break; }
		int end = switch (kind) {
			case "Inject" -> after(parameters, CALLBACK, RETURNABLE);
			case "WrapOperation", "WrapMethod" -> after(parameters, OPERATION, null);
			default -> sugar;
		};
		end = Math.min(end < 0 ? sugar : end, sugar);
		List<Extra> extras = new ArrayList<>();
		for (int i = end; i < parameters.length; i++) {
			AnnotationNode annotation = annotation(handler, i);
			Role role = annotation == null ? ("Inject".equals(kind) ? Role.CAPTURED : Role.ARGUMENT)
					: LOCAL.equals(annotation.desc) ? Role.LOCAL : SHARE.equals(annotation.desc) ? Role.SHARE
					: CANCELLABLE.equals(annotation.desc) ? Role.CANCELLABLE : Role.SUGAR;
			extras.add(new Extra(i, parameters[i], role, annotation));
		}
		return new MixinHandlerShape(handler, kind, Type.getReturnType(handler.desc),
				List.of(Arrays.copyOf(parameters, end)), List.copyOf(extras));
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

	/** The {@code @Local} extras, in declaration order. */
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

	/** The index just past the first parameter of descriptor {@code a} or {@code b}; -1 when there is none. */
	private static int after(Type[] parameters, String a, String b) {
		for (int i = 0; i < parameters.length; i++) {
			String descriptor = parameters[i].getDescriptor();
			if (descriptor.equals(a) || descriptor.equals(b)) return i + 1;
		}
		return -1;
	}

	private static AnnotationNode annotation(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (table == null || parameter >= table.length || table[parameter] == null) continue;
			for (AnnotationNode annotation : table[parameter]) if (annotation.desc.startsWith(SUGAR)) return annotation;
		}
		return null;
	}
}
