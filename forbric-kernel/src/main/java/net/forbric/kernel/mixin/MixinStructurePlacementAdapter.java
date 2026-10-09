/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.tree.*;

/** Keep authored entity processor setup, iteration and cleanup on the same live placement call. */
public final class MixinStructurePlacementAdapter {
	public static final String PROPERTY = "forbric.structurePlacementCallbacks";
	static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	static final String OLD = "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V";
	static final String LIVE = "addEntitiesToWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/ProblemReporter;)V";
	private MixinStructurePlacementAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MixinCallbackShape.targets(mixin, TARGET) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))) return 0;
		ClassNode target = targets.apply(TARGET);
		MethodNode place = target == null ? null : MixinPlayerWorldCallbackAdapter.selector(target, LIVE);
		MethodNode set = MixinCallbackShape.unique(mixin, m -> m.desc.equals("(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V") && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject") && (MixinCallbackShape.plainPoint(m, "INVOKE", "L" + TARGET + ";" + OLD) || MixinCallbackShape.plainPoint(m, "INVOKE", "L" + TARGET + ";" + LIVE))
                && MixinCallbackShape.selects(m,"placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z")),
                iterate = MixinCallbackShape.unique(mixin, m -> m.desc.equals("(Ljava/util/List;L" + MixinWrapOperationShim.OPERATION + ";Lnet/minecraft/world/level/ServerLevelAccessor;)Ljava/util/Iterator;")
                        && MixinCallbackShape.kind(m, "WrapOperation") && MixinCallbackShape.plainPoint(m, "INVOKE", "Ljava/util/List;iterator()Ljava/util/Iterator;") && MixinCallbackShape.selects(m, OLD)),
                clear = MixinCallbackShape.unique(mixin, m -> (m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                        || m.desc.equals(LIVE.substring(LIVE.indexOf('('), LIVE.indexOf(')'))+"Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V") && MixinCallbackShape.selects(m, LIVE))
                        && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.plainPoint(m, "TAIL", null) && (MixinCallbackShape.selects(m, OLD) || MixinCallbackShape.selects(m, LIVE)));
		if (place == null || set == null || iterate == null || clear == null
				|| MixinPlayerWorldCallbackAdapter.count(place, "Ljava/util/List;iterator()Ljava/util/Iterator;") != 1) return 0;
		int callers = 0;
		for (MethodNode method : target.methods) if (method.name.equals("placeInWorld")) callers += MixinPlayerWorldCallbackAdapter.count(method, "L" + TARGET + ";" + LIVE);
		if (callers != 1) return 0;
		AnnotationNode a = MixinFit.injectorOf(set), b = MixinFit.injectorOf(iterate), c = MixinFit.injectorOf(clear);
		// MixinRetarget's R7 may already have moved the two @Injects along MergedBaseCalleeSwaps' REPLACED row (the pickup's
		// point, the TAIL's selector, behind a method of its name); the iterator wrap inside the method is this adapter's.
		boolean cleared = MixinPlayerWorldCallbackAdapter.selects(c, LIVE);
		if (a == null || !MixinPlayerWorldCallbackAdapter.selects(b, OLD) || !(cleared || MixinPlayerWorldCallbackAdapter.selects(c, OLD))) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(a);
		Object point = points.size() == 1 ? MixinFit.value(points.getFirst(), "target") : null;
		boolean picked = ("L" + TARGET + ";" + LIVE).equals(point);
		if (!picked && !("L" + TARGET + ";" + OLD).equals(point)) return 0;
		// Only selectors change: Level remains the first argument, so the iterator's args-only local is unchanged.
		if (!picked) MixinPlayerWorldCallbackAdapter.set(points.getFirst(), "target", "L" + TARGET + ";" + LIVE);
		MixinPlayerWorldCallbackAdapter.set(b, "method", List.of(LIVE));
		if (!cleared) MixinPlayerWorldCallbackAdapter.set(c, "method", List.of(LIVE));
		return 1 + (picked ? 0 : 1) + (cleared ? 0 : 1);
	}
}
