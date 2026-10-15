/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Re-counts a guest's {@code @At("RETURN", ordinal = n)} on the merged body, when the merged body's returns are not the
 * native body's returns one for one.
 *
 * <p>The ordinal counts the returns of the body the guest was compiled against. A merge that keeps another family's
 * early exit gives the method one more return in front of the one the guest named (MinecraftForge's
 * {@code onBlockStartBreak} veto in {@code ServerPlayerGameMode.destroyBlock}: Apoli's {@code action_on_block_break}
 * names vanilla's last return, ordinal 4, and the merged ordinal 4 is the creative-mode exit, where the two booleans it
 * reads are not even in scope — the injector failed and the power never acted); a carrier that dropped or joined exits
 * gives it fewer. Which current return is which native return is read off both bodies ({@link ReturnAlignment}): the
 * native last return is the current last, an early return is the one the same test decides. A point it proves lands
 * there and is marked as counted over the current body ({@link CurrentBodyOrdinals}); one it cannot prove stays as
 * compiled, and is reported when the two bodies' returns differ in number.
 *
 * <p>Only injectors without a slice or a group, on a method their selector binds alone in both bodies with the same
 * descriptor; every {@code RETURN} ordinal point of one injector moves, or none of them does.
 * {@code -Dforbric.returnOrdinals=off} leaves every point as compiled.
 */
public final class MixinReturnOrdinals {
	public static final String PROPERTY = "forbric.returnOrdinals";

	private MixinReturnOrdinals() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** The source seam supplies hash-verified native bytes in production and explicit original bytes in tests. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null || references == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode current = null, original = null;
		int moved = 0;
		for (MethodNode handler : mixin.methods) {
			if (CurrentBodyOrdinals.counted(handler)) continue;
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.groupOf(handler) != null) continue;
			List<AnnotationNode> points = new java.util.ArrayList<>();
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				if ("RETURN".equals(MixinFit.asString(MixinFit.value(at, "value"))) && MixinFit.value(at, "slice") == null
						&& MixinFit.value(at, "ordinal") instanceof Integer ordinal && ordinal >= 0) points.add(at);
			}
			if (points.isEmpty()) continue;
			if (current == null) {
				current = targets.apply(owners.getFirst());
				original = references.apply(ecosystem, owners.getFirst());
				if (current == null || original == null) return moved;
			}
			MethodNode before = MixinTargetSelectors.one(handler, original), after = MixinTargetSelectors.one(handler, current);
			if (before == null || after == null || !(before.name + before.desc).equals(after.name + after.desc)) continue;
			int[] map = ReturnAlignment.map(original.name, before, current.name, after);
			List<ReturnAlignment.Exit> exits = ReturnAlignment.exits(current.name, after);
			if (map == null || exits == null) continue;
			int currentReturns = exits.size();
			// Every RETURN point of the injector moves, or none does: half a translation would count two bodies at once.
			int[] live = new int[points.size()];
			boolean proved = true, changes = false;
			for (int i = 0; i < points.size(); i++) {
				int ordinal = (Integer) MixinFit.value(points.get(i), "ordinal");
				live[i] = ordinal < map.length ? map[ordinal] : -1;
				proved &= live[i] >= 0;
				changes |= live[i] != ordinal;
			}
			if (!proved) {
				if (currentReturns != map.length) {
					ForbricLog.warn("[Forbric/Mixin] %s: %s's RETURN ordinal(s) %s on %s.%s count the native body's %d returns; not every one "
							+ "is proved to be one of the merged body's %d, so they stay as compiled", mixin.name.replace('/', '.'), handler.name,
							ordinals(points), current.name.replace('/', '.'), after.name, map.length, currentReturns);
				}
				continue;
			}
			if (!changes) continue;
			String was = ordinals(points);
			for (int i = 0; i < points.size(); i++) MixinPlayerWorldCallbackAdapter.set(points.get(i), "ordinal", live[i]);
			CurrentBodyOrdinals.mark(handler);
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s: %s's RETURN ordinal(s) %s on %s.%s count the native body's %d returns; the same ways out "
					+ "are the merged body's %s of %d", mixin.name.replace('/', '.'), handler.name, was, current.name.replace('/', '.'),
					after.name, map.length, ordinals(points), currentReturns);
		}
		return moved;
	}

	private static String ordinals(List<AnnotationNode> points) {
		List<String> out = new java.util.ArrayList<>();
		for (AnnotationNode at : points) out.add(String.valueOf(MixinFit.value(at, "ordinal")));
		return String.join(", ", out);
	}
}
