/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Numbers an unnumbered call point with the native occurrences when the carrier added more occurrences of the call.
 *
 * <p>An {@code @At(INVOKE)} without an ordinal takes every call of its target in the method. A mod compiled against the
 * native body means the calls that body makes; when the surviving carrier added a call of the same method on another
 * path, the handler also runs there, on a path the native game does not have. NeoForge's
 * {@code ServerPlayerGameMode.useItemOn} asks {@code player.getMainHandItem().doesSneakBypassUse(...)} after vanilla's
 * {@code getMainHandItem().isEmpty()}: Create's cancellable {@code @Inject} at {@code getMainHandItem} ran twice for a
 * sneaking player with an item in hand, and its wrench, clipboard and manual-processing handling with it.
 *
 * <p>The native occurrences are found in the current body by {@link CallOccurrenceAlignment#nativeAmongAdded}: each one
 * the single current occurrence with the same operand origins and the same next operation, in the same order, every
 * occurrence analysable, and no current occurrence two native ones would both describe. The point then counts exactly
 * those: one native occurrence becomes its ordinal; several, in an injector whose {@code at} is a list, become one
 * {@code @At} each. A point in a slice, an injector in a callback group or with a slice, a handler whose ordinals an
 * earlier pass already counted over the current body ({@link CurrentBodyOrdinals}), and a target the merged body selects
 * other calls with are left as written. {@code -Dforbric.addedCallOccurrences=off} leaves every point as compiled.
 */
public final class AddedCallOccurrences {
	public static final String PROPERTY = "forbric.addedCallOccurrences";

	private AddedCallOccurrences() {
	}

	/** Numbers every eligible point of {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** The source seam supplies hash-verified native bytes in production and explicit original bytes in tests. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| targets == null || references == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode current = null, original = null;
		int numbered = 0;
		for (MethodNode handler : mixin.methods) {
			if (CurrentBodyOrdinals.counted(handler)) continue;
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.groupOf(handler) != null) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.stream().noneMatch(AddedCallOccurrences::unnumberedCall)) continue;
			if (current == null) {
				current = targets.apply(owners.getFirst());
				original = references.apply(ecosystem, owners.getFirst());
				if (current == null || original == null) return numbered;
			}
			MethodNode before = MixinTargetSelectors.one(handler, original), after = MixinTargetSelectors.one(handler, current);
			if (before == null || after == null || !before.name.equals(after.name) || !before.desc.equals(after.desc)) continue;
			for (AnnotationNode at : points) {
				if (!unnumberedCall(at)) continue;
				String member = MixinCallbackShape.invoked(at, before);
				if (member == null || !onlyCallsOf(MixinCallbackShape.calls(at, after), member)) continue;
				int[] counterparts = CallOccurrenceAlignment.nativeAmongAdded(original, before, current, after, member);
				if (counterparts == null) continue;
				if (counterparts.length == 1) {
					set(at, "ordinal", counterparts[0]);
				} else if (MixinFit.value(injector, "at") instanceof List<?> list) {
					@SuppressWarnings("unchecked") List<Object> each = (List<Object>) list;
					int index = each.indexOf(at);
					if (index < 0) continue;
					each.remove(index);
					for (int k = counterparts.length - 1; k >= 0; k--) {
						AnnotationNode copy = new AnnotationNode(at.desc);
						copy.values = new ArrayList<>(at.values);
						set(copy, "ordinal", counterparts[k]);
						each.add(index, copy);
					}
				} else {
					continue;
				}
				numbered++;
				CurrentBodyOrdinals.mark(handler);
				ForbricLog.info("[Forbric/Mixin] %s: %s's point at %s now counts only the native occurrence(s) %s of %s.%s — the "
						+ "carrier added the rest, where the native game never ran it (same operand origins and next operation)",
						mixin.name.replace('/', '.'), handler.name, member, Arrays.toString(counterparts), current.name.replace('/', '.'),
						after.name);
			}
		}
		return numbered;
	}

	private static boolean unnumberedCall(AnnotationNode at) {
		if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || MixinFit.value(at, "slice") != null) return false;
		Object ordinal = MixinFit.value(at, "ordinal");
		return ordinal == null || ordinal instanceof Integer n && n < 0;
	}

	private static boolean onlyCallsOf(List<MethodInsnNode> calls, String member) {
		if (calls == null || calls.isEmpty()) return false;
		for (MethodInsnNode call : calls) if (!CallOccurrenceAlignment.member(call).equals(member)) return false;
		return true;
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
