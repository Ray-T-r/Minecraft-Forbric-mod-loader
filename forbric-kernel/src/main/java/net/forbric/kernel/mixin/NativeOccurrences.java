/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricSwitches;

/**
 * Hands a guest's injection points to {@link NativeOccurrencePoint}, which keeps each point as written but counts and
 * selects its occurrences over the native body of the method (the platform's own bytes, pinned by the merged-base builder)
 * and maps them onto the merged body by code alignment — so an injector runs once per native execution of its point,
 * on every path, however many occurrences of it the carriers' code added, dropped or reordered.
 *
 * <p>A point is handed over when its mixin has a platform, its target has a native body for that platform, and the point
 * selects instructions by what they are — a call, a field access, an allocation, a constant, a jump, a local load or store,
 * a MixinExtras expression — not by where the method begins or ends (HEAD, RETURN and TAIL have adapters of their own).
 * Left as written: an injector with a slice (its occurrences are counted inside the slice, which only the merged body
 * has), a point with a specifier ({@code INVOKE:LAST}), an injector an adapter before this one changed in any way (moved,
 * renumbered over the merged body, re-sliced — compared value for value with the mixin as its jar has it), and a
 * {@code @Redirect} of a field access or an allocation, which Mixin reads through the point's own type. What the point then selects is decided at weave time, from both bodies; where the bodies
 * agree in number, Mixin's own selection stands untouched.
 *
 * <p>No name of any mod, class, member or config is read: the decision is the point's kind and the bytecode.
 * {@code -Dforbric.nativeOccurrences=off} leaves every point as compiled.
 */
public final class NativeOccurrences {
	public static final String PROPERTY = "forbric.nativeOccurrences";

	private static final Set<String> POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING", "FIELD", "NEW", "CONSTANT", "JUMP",
			"LOAD", "STORE", "MIXINEXTRAS:EXPRESSION");
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

	private NativeOccurrences() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(ForbricSwitches.get(PROPERTY, "on"));
	}

	/**
	 * Hands every eligible point of {@code mixin} over; returns how many. {@code written} is the mixin's bytes as its jar has them: a
	 * point is handed over only while its injector is still exactly as written there — an adapter before this one that
	 * moved, renumbered or re-sliced it counts over the merged body, which this would read as a native count.
	 */
	public static int adapt(ClassNode mixin, byte[] written) {
		if (!enabled() || mixin == null || written == null) return 0;
		return adapt(mixin, MixinFit.parse(written), NativeGameReferences::has);
	}

	static int adapt(ClassNode mixin, ClassNode written, BiPredicate<Ecosystem, String> hasNativeBody) {
		if (!enabled() || mixin == null || mixin.methods == null || written == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		boolean anyNative = false;
		for (String target : MixinOverloadPin.targetsOf(mixin)) anyNative |= hasNativeBody.test(ecosystem, target);
		if (!anyNative) return 0;
		int handed = 0;
		for (MethodNode handler : mixin.methods) {
			if (CurrentBodyOrdinals.counted(handler)) continue;
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || !asWritten(written, handler, injector)) continue;
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				String value = MixinFit.asString(MixinFit.value(at, "value"));
				if (value == null || !POINTS.contains(value) || MixinFit.value(at, "slice") != null) continue;
				if (REDIRECT.equals(injector.desc) && ("FIELD".equals(value) || "NEW".equals(value))) continue;
				set(at, "value", NativeOccurrencePoint.VALUE);
				List<String> args = new ArrayList<>(MixinFit.stringList(MixinFit.value(at, "args")));
				args.add(NativeOccurrencePoint.POINT + "=" + value);
				set(at, "args", args);
				handed++;
			}
		}
		if (handed > 0) net.forbric.kernel.util.ForbricLog.debug("[Forbric/Mixin] %s: %d point(s) count their native occurrences",
				mixin.name.replace('/', '.'), handed);
		return handed;
	}

	/** Whether {@code written} declares {@code handler} with the same injector, value for value. */
	private static boolean asWritten(ClassNode written, MethodNode handler, AnnotationNode injector) {
		if (written.methods == null) return false;
		for (MethodNode method : written.methods) {
			if (!method.name.equals(handler.name) || !method.desc.equals(handler.desc)) continue;
			AnnotationNode original = MixinFit.injectorOf(method);
			return original != null && original.desc.equals(injector.desc) && canonical(original).equals(canonical(injector));
		}
		return false;
	}

	private static String canonical(Object value) {
		if (value instanceof AnnotationNode annotation) {
			StringBuilder out = new StringBuilder("@").append(annotation.desc).append('(');
			if (annotation.values != null) for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				out.append(annotation.values.get(i)).append('=').append(canonical(annotation.values.get(i + 1))).append(';');
			}
			return out.append(')').toString();
		}
		if (value instanceof List<?> list) {
			StringBuilder out = new StringBuilder("[");
			for (Object element : list) out.append(canonical(element)).append(',');
			return out.append(']').toString();
		}
		if (value instanceof String[] enumValue) return String.join(".", enumValue);
		return String.valueOf(value);
	}

	/** The point a handed-over {@code @At} was written as, or its value when it was not handed over. */
	static String writtenPoint(AnnotationNode at) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		if (!NativeOccurrencePoint.VALUE.equals(value)) return value;
		for (String arg : MixinFit.stringList(MixinFit.value(at, "args"))) {
			if (arg.startsWith(NativeOccurrencePoint.POINT + "=")) return arg.substring(NativeOccurrencePoint.POINT.length() + 1);
		}
		return value;
	}

	private static void set(AnnotationNode at, String key, Object value) {
		if (at.values == null) at.values = new ArrayList<>();
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if (key.equals(at.values.get(i))) {
				at.values.set(i + 1, value);
				return;
			}
		}
		at.values.add(key);
		at.values.add(value);
	}
}
