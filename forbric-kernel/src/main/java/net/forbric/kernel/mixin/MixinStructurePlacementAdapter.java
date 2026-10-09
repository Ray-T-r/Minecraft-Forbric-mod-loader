/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

/** Keep authored entity processor setup, iteration and cleanup on the same live placement call. */
public final class MixinStructurePlacementAdapter {
	public static final String PROPERTY = "forbric.structurePlacementCallbacks";
	static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	static final String OLD = "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V";
	static final String LIVE = "addEntitiesToWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/ProblemReporter;)V";
	private MixinStructurePlacementAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/**
	 * {@code references} gives the class the mod was compiled against: the merged game no longer declares the native
	 * {@code placeEntities}, so which method a bare name or pattern bound there is read off the native class
	 * ({@link MixinTargetSelectors#nativeMember}); without it only a selector spelling that descriptor names it.
	 */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!MixinCallbackShape.targets(mixin, TARGET) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))) return 0;
		ClassNode target = targets.apply(TARGET), source = references == null ? null : references.apply(MixinStubRebind.ecosystemOf(mixin.name), TARGET);
		MethodNode place = target == null ? null : MixinPlayerWorldCallbackAdapter.selector(target, LIVE);
		MethodNode set = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, "(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V") && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject") && (MixinCallbackShape.plainPoint(m, "INVOKE", "L" + TARGET + ";" + OLD) || MixinCallbackShape.plainPoint(m, "INVOKE", "L" + TARGET + ";" + LIVE))
                && MixinCallbackShape.binds(m, target, "placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z")),
                iterate = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, "(Ljava/util/List;L" + MixinWrapOperationShim.OPERATION + ";)Ljava/util/Iterator;", MixinHandlerShape.Want.local("Lnet/minecraft/world/level/ServerLevelAccessor;"))
                        && MixinCallbackShape.kind(m, "WrapOperation") && MixinCallbackShape.plainPoint(m, "INVOKE", "Ljava/util/List;iterator()Ljava/util/Iterator;") && authoredFor(m, source)),
                clear = MixinCallbackShape.unique(mixin, m -> (MixinCallbackShape.shape(m, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                        || MixinCallbackShape.shape(m, LIVE.substring(LIVE.indexOf('('), LIVE.indexOf(')'))+"Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V") && MixinCallbackShape.binds(m, target, LIVE))
                        && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.plainPoint(m, "TAIL", null) && (authoredFor(m, source) || MixinCallbackShape.binds(m, target, LIVE)));
		if (place == null || set == null || iterate == null || clear == null
				|| MixinPlayerWorldCallbackAdapter.count(place, "Ljava/util/List;iterator()Ljava/util/Iterator;") != 1) return 0;
		int callers = 0;
		for (MethodNode method : target.methods) if (method.name.equals("placeInWorld")) callers += MixinPlayerWorldCallbackAdapter.count(method, "L" + TARGET + ";" + LIVE);
		if (callers != 1) return 0;
		AnnotationNode a = MixinFit.injectorOf(set), b = MixinFit.injectorOf(iterate), c = MixinFit.injectorOf(clear);
		// MixinRetarget's R7 may already have moved the two @Injects along MergedBaseCalleeSwaps' REPLACED row (the pickup's
		// point, the TAIL's selector, behind a method of its name); the iterator wrap inside the method is this adapter's.
		boolean cleared = MixinCallbackShape.binds(clear, target, LIVE);
		if (a == null || b == null || !authoredFor(iterate, source) || !(cleared || authoredFor(clear, source))) return 0;
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

	/** Whether the handler was written for the native {@code placeEntities}, the method the merged game no longer calls. */
	private static boolean authoredFor(MethodNode handler, ClassNode source) {
		return OLD.equals(MixinTargetSelectors.nativeMember(handler, source, TARGET));
	}
}
