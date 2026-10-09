/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A status callback lowers the block-update notification threshold to FULL for non-ticking chunks. NeoForge moved
 * that check from setBlock to markAndNotifyBlock, also used when captured block snapshots are committed.
 * The same threshold must apply there: the handler only modifies the status argument, capturing no
 * setBlock arguments or locals. Keep the handler and its injection requirement intact.
 */
final class MixinChunkStatusRetarget {

	static final String PROPERTY = "forbric.chunkStatusRetarget";
	static final String LEVEL = "net/minecraft/world/level/Level";
	static final String STATUS = "Lnet/minecraft/server/level/FullChunkStatus;";
	static final String POSITION = "Lnet/minecraft/core/BlockPos;";
	static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	static final String ORIGINAL = "setBlock(" + POSITION + STATE + "II)Z";
	static final String HELPER = "markAndNotifyBlock(" + POSITION
			+ "Lnet/minecraft/world/level/chunk/LevelChunk;" + STATE + STATE + "II)V";
	static final String ANCHOR = STATUS + "isOrAfter(" + STATUS + ")Z";

	private MixinChunkStatusRetarget() { }

	static MixinRetarget.Rewrite plan(String mixin, MethodNode handler, AnnotationNode injector,
			List<String> selectors, ClassNode target) {
		if (!LEVEL.equals(target.name)
				|| "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"))) return null;
		if (!("(" + STATUS + ")" + STATUS).equals(handler.desc)
				|| (handler.access & Opcodes.ACC_STATIC) != 0
				|| !"Lorg/spongepowered/asm/mixin/injection/ModifyArg;".equals(injector.desc)
				|| !List.of(ORIGINAL).equals(selectors)) return null;
		if (MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "target") != null
				|| annotatedParameters(handler.visibleParameterAnnotations)
				|| annotatedParameters(handler.invisibleParameterAnnotations)) return null;
		if (grouped(handler.visibleAnnotations) || grouped(handler.invisibleAnnotations)) return null;
		Object index = MixinFit.value(injector, "index");
		if (index != null && !Integer.valueOf(-1).equals(index) && !Integer.valueOf(0).equals(index)) return null;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return null;
		AnnotationNode at = points.getFirst();
		if (!"INVOKE".equals(MixinFit.value(at, "value")) || !ANCHOR.equals(MixinFit.value(at, "target"))
				|| MixinFit.value(at, "shift") != null || MixinFit.value(at, "args") != null) return null;
		Object ordinal = MixinFit.value(at, "ordinal");
		if (ordinal != null && !Integer.valueOf(-1).equals(ordinal) && !Integer.valueOf(0).equals(ordinal)) return null;
		MethodNode original = method(target, ORIGINAL), helper = method(target, HELPER);
		if (original == null || helper == null || (helper.access & Opcodes.ACC_STATIC) != 0
				|| CarrierHelpers.occurrences(original, ANCHOR) != 0
				|| CarrierHelpers.occurrences(original, "L" + LEVEL + ";" + HELPER) != 1
				|| CarrierHelpers.occurrences(helper, ANCHOR) != 1) return null;
		return new MixinRetarget.Rewrite(handler.name, MixinRetarget.Element.SELECTOR, ORIGINAL, HELPER,
				"The chunk notification threshold callback follows the check into markAndNotifyBlock");
	}

	private static MethodNode method(ClassNode target, String selector) {
		return target.methods.stream().filter(m -> selector.equals(m.name + m.desc)).findFirst().orElse(null);
	}

	private static boolean grouped(List<AnnotationNode> annotations) {
		return annotations != null && annotations.stream().anyMatch(a -> MixinRetarget.GROUP.equals(a.desc));
	}

	private static boolean annotatedParameters(List<AnnotationNode>[] parameters) {
		if (parameters != null) for (List<AnnotationNode> annotations : parameters) {
			if (annotations != null && !annotations.isEmpty()) return true;
		}
		return false;
	}
}
