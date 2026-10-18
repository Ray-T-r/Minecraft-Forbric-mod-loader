/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.function.Function;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Moves fabric-block-api's air-check redirects onto the call the merged base actually makes.
 *
 * <p>{@code LevelChunkSectionMixin} and {@code ChunkSectionBlockStateCounterMixin} each {@code @Redirect} the
 * {@code BlockState.isAir()} call inside a chunk-section method: {@code LevelChunkSection.setBlockState} and
 * {@code LevelChunkSection$1BlockCounter.accept} respectively. The merged base does not make that call — it makes
 * {@code BlockState.isEmpty()}, which is where NeoForge moved the same test — so both handlers bind nothing,
 * Mixin's {@code require} counts them as lost, and the mod is reported as a failed required initialization.
 *
 * <p>Nothing else about the shape changes: the handler still receives the {@code BlockState} and still answers
 * whether the state counts as air, which is exactly what {@code @Redirect} replaces. Only its {@code @At.target}
 * moves, and only when the merged target makes the {@code isEmpty()} call and makes no {@code isAir()} call at
 * all, so a class where the test is genuinely gone is left alone rather than redirected onto something else.
 * {@code -Dforbric.airCheckRedirects=off} turns it off.
 */
public final class FabricAirCheckMixinAdapter {
	public static final String PROPERTY = "forbric.airCheckRedirects";
	static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
	static final String VANILLA_CALL = "L" + BLOCK_STATE + ";isAir()Z";
	static final String MERGED_CALL = "L" + BLOCK_STATE + ";isEmpty()Z";

	/** mixin internal name, then the merged class its redirect points into. */
	private static final String[][] CASES = {
			{ "net/fabricmc/fabric/mixin/block/LevelChunkSectionMixin",
					"net/minecraft/world/level/chunk/LevelChunkSection" },
			{ "net/fabricmc/fabric/mixin/block/ChunkSectionBlockStateCounterMixin",
					"net/minecraft/world/level/chunk/LevelChunkSection$1BlockCounter" },
	};

	private FabricAirCheckMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** @return the number of {@code @At} targets moved: 1 per adapted mixin, or 0 when the shape differs */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.name == null) return 0;
		for (String[] row : CASES) {
			if (!row[0].equals(mixin.name)) continue;
			ClassNode target = targets.apply(row[1]);
			if (target == null || !makesIsEmptyAndNoIsAir(target)) return 0;
			int moved = 0;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					if (!"INVOKE".equals(MixinFit.value(at, "value"))) continue;
					if (!VANILLA_CALL.equals(MixinFit.value(at, "target"))) continue;
					if (retarget(at)) moved++;
				}
			}
			if (moved > 0) {
				ForbricLog.info("[Forbric/AirCheck] moved " + moved + " fabric-block-api air-check redirect(s) in "
						+ mixin.name + " onto " + MERGED_CALL + " — the merged base makes that call where vanilla made "
						+ VANILLA_CALL + ", so the redirect had nothing to bind to and the mod was reported failed");
			}
			return moved;
		}
		return 0;
	}

	/** The merged class must make the replacement call and make none of the vanilla one — else leave it alone. */
	static boolean makesIsEmptyAndNoIsAir(ClassNode target) {
		boolean isEmpty = false;
		for (MethodNode method : target.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !BLOCK_STATE.equals(call.owner)) continue;
				if ("isAir".equals(call.name) && "()Z".equals(call.desc)) return false;
				if ("isEmpty".equals(call.name) && "()Z".equals(call.desc)) isEmpty = true;
			}
		}
		return isEmpty;
	}

	/** Rewrites the {@code @At} annotation's {@code target} value in place. */
	static boolean retarget(AnnotationNode at) {
		if (at.values == null) return false;
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if (!"target".equals(at.values.get(i))) continue;
			if (!VANILLA_CALL.equals(at.values.get(i + 1))) return false;
			at.values.set(i + 1, MERGED_CALL);
			return true;
		}
		return false;
	}
}
