/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The slot a handler's {@code @Local} capture reads once its anchor moves from the native call to the call the merged body
 * makes in its place, in the same method.
 *
 * <p>With the native body: the slot MixinExtras resolves the capture to at the native point ({@code argsOnly}, {@code index},
 * {@code name}, {@code ordinal}, or the one local of its type — {@link MixinLocalOriginProof#slot}) holds a value with a
 * producer; the merged slot is the one slot holding a value with the same producer at the merged point — first by
 * {@link MixinLocalOriginProof#prove} (parameters, constants, allocations), then by the producing expression itself
 * ({@link NativeCallChanges#localsAt}: {@code Mth.floor(this.getX())} is the same value in both bodies whatever slot either
 * stores it in). Without the native body: the slot MixinExtras itself resolves the same capture to at the merged point.
 * Never by the order in which one mod happened to declare its captures.
 */
final class MixinLocalCapture {
	private MixinLocalCapture() { }

	/**
	 * For each {@code @Local} extra of {@code handler} (by handler parameter): the slot of {@code merged} it must read at
	 * {@code mergedPoint}. Null when any capture is not proved, or another kind of sugar is present.
	 */
	static Map<Integer, Integer> slots(MethodNode handler, String owner, MethodNode nativeHost, AbstractInsnNode nativePoint,
			MethodNode merged, AbstractInsnNode mergedPoint) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		if (shape == null || merged == null || mergedPoint == null) return null;
		List<MixinHandlerShape.Extra> locals = shape.locals();
		Map<Integer, Integer> result = new LinkedHashMap<>();
		if (locals.isEmpty()) return result;
		int mergedAt = merged.instructions.indexOf(mergedPoint);
		if (mergedAt < 0) return null;
		if (nativeHost == null) {
			for (MixinHandlerShape.Extra local : locals) {
				int slot = MixinLocalOriginProof.slot(local.sugar(), local.type(), merged, mergedAt);
				if (slot < 0) return null;
				result.put(local.parameter(), slot);
			}
			return result;
		}
		int nativeAt = nativePoint == null ? -1 : nativeHost.instructions.indexOf(nativePoint);
		if (nativeAt < 0) return null;
		Map<Integer, Integer> origins = nativeHost.desc.equals(merged.desc)
				? MixinLocalOriginProof.prove(handler, owner, nativeHost, nativePoint, merged, mergedPoint) : null;
		Map<Integer, NativeCallChanges.Expr> before = null, after = null;
		for (MixinHandlerShape.Extra local : locals) {
			if (local.type().getSort() == Type.OBJECT && local.type().getInternalName().startsWith("com/llamalad7/mixinextras/sugar/ref/")) return null;
			Integer proved = origins == null ? null : origins.get(local.parameter());
			if (proved == null) {
				int slot = MixinLocalOriginProof.slot(local.sugar(), local.type(), nativeHost, nativeAt);
				if (slot < 0) return null;
				if (before == null) before = NativeCallChanges.localsAt(owner, nativeHost, nativePoint);
				if (after == null) after = NativeCallChanges.localsAt(owner, merged, mergedPoint);
				if (before == null || after == null || before.get(slot) == null) return null;
				NativeCallChanges.Expr value = before.get(slot);
				Integer found = null;
				for (var candidate : after.entrySet()) {
					if (!candidate.getValue().equals(value)) continue;
					if (found != null) return null;   // the value sits in two slots: which one is a guess
					found = candidate.getKey();
				}
				if (found == null) return null;
				// A parameter is named by its slot: the merged method's parameter there must be of the captured type.
				if (value.kind().equals("parameter") && !local.type().equals(parameterType(merged, found))) return null;
				proved = found;
			}
			result.put(local.parameter(), proved);
		}
		return result;
	}

	/** The type of the parameter in {@code slot} of {@code method} ({@code this} for slot 0 of an instance method is none). */
	private static Type parameterType(MethodNode method, int slot) {
		int at = (method.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type type : Type.getArgumentTypes(method.desc)) {
			if (at == slot) return type;
			at += type.getSize();
		}
		return null;
	}

	/** A {@code @Local} that reads exactly {@code slot}, whatever the original asked for to name it. */
	static AnnotationNode at(AnnotationNode original, int slot) {
		AnnotationNode local = new AnnotationNode(original.desc);
		local.values = new java.util.ArrayList<>(List.of("index", slot));
		return local;
	}
}
