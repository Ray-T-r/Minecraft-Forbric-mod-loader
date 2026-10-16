/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Finds again, by what it is, the call a guest's sliced point picks in the method its own loader has, where the surviving
 * carrier moved that call out of the slice.
 *
 * <p>Vanilla's {@code LevelChunk.getBlockEntity} creates a missing block entity first and only then, for one that was
 * removed, takes it out of {@code blockEntities}; a mod that redirects that removal slices the method from the creation
 * call. NeoForge takes the removed block entity out first, before anything is created, so the slice holds no removal and
 * the point binds nothing — though the merged method still makes the very same call, on the same map, with the same key,
 * under the same conditions.
 *
 * <p>Nothing here knows a mod, a class or a method by name. A point is moved when its injector has one point, a call
 * ({@code INVOKE}/{@code INVOKE_ASSIGN}), one slice bounded by calls or field accesses (no {@code @Group}, no other
 * slice keys, no shift on its bounds); the method its selectors bind is the same natively (the class its ecosystem's mods
 * were compiled against) and merged; the native slice and ordinal pick exactly one call there and the merged slice picks
 * none; and the merged method makes exactly one call of that member that is handed the same operands — each the same
 * expression over the method's own values (its receiver, its parameters, fields, calls, constants, and locals by the value
 * stored in them) — and made under every branch condition the native call is made under. The point then names that call
 * by its ordinal and the slice is dropped, so it is selected however the carrier ordered the method.
 * {@code -Dforbric.nativeSliceOccurrences=off} leaves every point as compiled.
 */
public final class NativeSliceOccurrences {
	public static final String PROPERTY = "forbric.nativeSliceOccurrences";
	private static final Set<String> AT_KEYS = Set.of("value", "target", "ordinal", "remap", "shift", "slice");
	private static final Set<String> SLICE_KEYS = Set.of("from", "to", "id");
	private static final Set<String> BOUND_KEYS = Set.of("value", "target", "ordinal", "remap");
	private static final Pattern UNREAD = Pattern.compile("\\?|(^|[(,])(local|join)([),]|$)");

	private NativeSliceOccurrences() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Re-selects every eligible sliced point of {@code mixin}; returns how many. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || MixinFit.value(injector, "slice") == null || MixinFit.groupOf(handler) != null) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				Found found = derive(original, merged, handler, injector);
				if (found == null) continue;
				AnnotationNode at = MixinFit.atNodes(injector).getFirst();
				set(at, "ordinal", found.ordinal());
				remove(at, "slice");
				remove(injector, "slice");
				moved++;
				ForbricLog.info("[Forbric/Mixin] %s.%s: the call its slice picked in %s.%s is no longer inside that slice; the merged method makes "
						+ "it as call %d of %s.%s, with the same operands under the same conditions, and the point now names that one",
						mixin.name.replace('/', '.'), handler.name, target.replace('/', '.'), found.host().name, found.ordinal(),
						found.call().owner.replace('/', '.'), found.call().name);
			}
		}
		return moved;
	}

	/** The merged call a moved point names, the method it is in, and its ordinal among the calls the point selects there. */
	record Found(MethodNode host, MethodInsnNode call, int ordinal) {
	}

	/** The merged call the native sliced point's one call is, or null. */
	static Found derive(ClassNode original, ClassNode merged, MethodNode handler, AnnotationNode injector) {
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return null;
		AnnotationNode at = points.getFirst();
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		if (!"INVOKE".equals(value) && !"INVOKE_ASSIGN".equals(value) || !keys(at, AT_KEYS) || !"".equals(orEmpty(MixinFit.value(at, "slice")))) return null;
		AnnotationNode slice = slice(injector);
		if (slice == null) return null;
		MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
		if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc)) return null;
		List<MethodInsnNode> nativeSelected = selected(at, slice, was), mergedSelected = selected(at, slice, host);
		if (nativeSelected == null || nativeSelected.size() != 1 || mergedSelected == null || !mergedSelected.isEmpty()) return null;
		MethodInsnNode call = nativeSelected.getFirst();
		NativeCallInlining.Conditions nativeValues = NativeCallInlining.Conditions.of(original.name, was, Integer.MAX_VALUE);
		NativeCallInlining.Conditions mergedValues = NativeCallInlining.Conditions.of(merged.name, host, Integer.MAX_VALUE);
		if (nativeValues == null || mergedValues == null) return null;
		List<String> operands = nativeValues.operands(call);
		if (operands == null || operands.stream().anyMatch(operand -> UNREAD.matcher(operand).find())) return null;
		Set<String> conditions = nativeValues.of(call);
		List<MethodInsnNode> candidates = MixinCallbackShape.calls(at, host);
		MethodInsnNode found = null;
		for (MethodInsnNode candidate : candidates) {
			if (!candidate.owner.equals(call.owner) || !candidate.name.equals(call.name) || !candidate.desc.equals(call.desc)) continue;
			if (!operands.equals(mergedValues.operands(candidate)) || !mergedValues.of(candidate).containsAll(conditions)) continue;
			if (found != null) return null;
			found = candidate;
		}
		return found == null ? null : new Found(host, found, candidates.indexOf(found));
	}

	/**
	 * The calls a point selects within its slice, as Mixin's {@code MethodSlice} reads it: a bound picks the first
	 * instruction its point selects (or the occurrence its ordinal names); a {@code from} that is absent or picks nothing
	 * starts the slice at the method's first instruction; a {@code to} that is absent ends it at the last, one that picks
	 * nothing ends it where it starts; both bounds are included, and the point's ordinal counts within the slice. Null where
	 * a bound is not one this reads, or the slice would be of negative size (Mixin refuses it).
	 */
	static List<MethodInsnNode> selected(AnnotationNode at, AnnotationNode slice, MethodNode method) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) code.add(instruction);
		int from = 0, to = code.size() - 1;
		Object start = MixinFit.value(slice, "from"), end = MixinFit.value(slice, "to");
		if (start != null) {
			AbstractInsnNode bound = bound(start, method);
			if (bound == NONE) return null;
			if (bound != null) from = code.indexOf(bound);
		}
		if (end != null) {
			AbstractInsnNode bound = bound(end, method);
			if (bound == NONE) return null;
			to = bound == null ? from : code.indexOf(bound);
		}
		if (from > to) return null;
		List<MethodInsnNode> within = new ArrayList<>();
		for (MethodInsnNode call : MixinCallbackShape.calls(at, method)) {
			int index = code.indexOf(call);
			if (index >= from && index <= to) within.add(call);
		}
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		if (ordinal < 0) return within;
		return ordinal < within.size() ? List.of(within.get(ordinal)) : List.of();
	}

	private static final AbstractInsnNode NONE = new org.objectweb.asm.tree.InsnNode(0);

	/** The instruction a slice bound picks (its ordinal's, else the first); null for none, {@link #NONE} if unread. */
	private static AbstractInsnNode bound(Object spec, MethodNode method) {
		if (!(spec instanceof AnnotationNode at) || !keys(at, BOUND_KEYS)) return NONE;
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		boolean invoke = "INVOKE".equals(value) || "INVOKE_ASSIGN".equals(value), field = "FIELD".equals(value);
		if (!invoke && !field || MixinFit.value(at, "target") == null) return NONE;
		List<AbstractInsnNode> picked = new ArrayList<>();
		for (AbstractInsnNode instruction : MixinCallbackShape.selected(at, method))
			if (invoke ? instruction instanceof MethodInsnNode : instruction instanceof FieldInsnNode) picked.add(instruction);
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : 0;
		return ordinal >= 0 && ordinal < picked.size() ? picked.get(ordinal) : null;
	}

	/** The injector's one slice, unnamed; null when it has none, several, or one with an id. */
	private static AnnotationNode slice(AnnotationNode injector) {
		Object value = MixinFit.value(injector, "slice");
		AnnotationNode slice = value instanceof AnnotationNode single ? single
				: value instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof AnnotationNode only ? only : null;
		if (slice == null || !keys(slice, SLICE_KEYS) || !"".equals(orEmpty(MixinFit.value(slice, "id")))) return null;
		return slice;
	}

	private static boolean keys(AnnotationNode annotation, Set<String> allowed) {
		for (int i = 0; annotation.values != null && i + 1 < annotation.values.size(); i += 2)
			if (!allowed.contains(annotation.values.get(i))) return false;
		return true;
	}

	private static Object orEmpty(Object value) {
		return value == null ? "" : value;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; }
		annotation.values.add(key);
		annotation.values.add(value);
	}

	private static void remove(AnnotationNode annotation, String key) {
		for (int i = 0; annotation.values != null && i + 1 < annotation.values.size(); i += 2)
			if (key.equals(annotation.values.get(i))) { annotation.values.remove(i + 1); annotation.values.remove(i); return; }
	}
}
