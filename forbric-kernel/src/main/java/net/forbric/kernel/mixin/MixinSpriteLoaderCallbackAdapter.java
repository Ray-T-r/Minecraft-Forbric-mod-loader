/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/** Preserves An authored loader-map callback on Forge's metadata-aware atlas listing overload. */
public final class MixinSpriteLoaderCallbackAdapter {
	public static final String PROPERTY = "forbric.spriteLoaderCallbacks";
	private static final String TARGET = "net/minecraft/client/renderer/texture/atlas/SpriteSourceList";
	private static final String RESOURCE = "Lnet/minecraft/server/packs/resources/ResourceManager;";
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String OLD = "list(" + RESOURCE + ")Ljava/util/List;";
	private static final String LIVE = "list(" + RESOURCE + "Ljava/util/Set;)Ljava/util/List;";

	private MixinSpriteLoaderCallbackAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MixinCallbackShape.targets(mixin, TARGET) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"))) return 0;
		ClassNode target = targets.apply(TARGET);
		if (target == null) return 0;
		MethodNode live = target.methods.stream().filter(m -> (m.name + m.desc).equals(LIVE)).findFirst().orElse(null);
		if (live == null) return 0;
		// The callback captures the FIRST local after the live arguments: the loader map. Refuse a
		// different carrier shape instead of letting a locals capture silently receive another value.
		boolean mapAtThree = false;
		int anchors = 0;
		for (AbstractInsnNode insn : live.instructions) {
			if (insn instanceof MethodInsnNode call && "java/util/HashMap".equals(call.owner)
					&& "<init>".equals(call.name) && insn.getNext() instanceof VarInsnNode store
					&& store.getOpcode() == Opcodes.ASTORE && store.var == 3) mapAtThree = true;
			if (insn instanceof MethodInsnNode call && "com/google/common/collect/ImmutableList".equals(call.owner)
					&& "builder".equals(call.name)) anchors++;
		}
		if (!mapAtThree || anchors != 1) return 0;
		MethodNode original = MixinCallbackShape.unique(mixin, m -> ("(" + RESOURCE + CALLBACK + "Ljava/util/Map;)V").equals(m.desc)
                && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.selects(m, OLD)
                && MixinCallbackShape.plainPoint(m, "INVOKE", "Lcom/google/common/collect/ImmutableList;builder()Lcom/google/common/collect/ImmutableList$Builder;") && MixinCallbackShape.instance(m));
		if (original == null || original.visibleAnnotations == null) return 0;
		AnnotationNode inject = original.visibleAnnotations.stream()
				.filter(a -> "Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)).findFirst().orElse(null);
		if (inject == null || !List.of(OLD).equals(MixinFit.value(inject, "method"))) return 0;
		String handlerName = original.name;
		original.name += "$forbricOriginal";
		original.visibleAnnotations.remove(inject);
        MixinCallbackShape.uniqueMember(original);
		MethodNode wrapper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, handlerName,
				"(" + RESOURCE + "Ljava/util/Set;" + CALLBACK + "Ljava/util/Map;)V", null, null);
		wrapper.visibleAnnotations = new ArrayList<>(List.of(inject));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
		wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixin.name, original.name, original.desc, false));
		wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
		wrapper.maxLocals = 5;
		wrapper.maxStack = 4;
		mixin.methods.add(wrapper);
		int changed = 0;
		for (MethodNode method : mixin.methods) {
			if (method.visibleAnnotations == null) continue;
			for (AnnotationNode annotation : method.visibleAnnotations) {
				if (!List.of(OLD).equals(MixinFit.value(annotation, "method"))) continue;
				for (int i = 0; i < annotation.values.size(); i += 2) {
					if ("method".equals(annotation.values.get(i))) annotation.values.set(i + 1, List.of(LIVE));
				}
				changed++;
			}
		}
		ForbricLog.info("[Forbric/Mixin] atlas callbacks now use the metadata-aware list overload "
				+ "and capture its actual loader map (%d injector(s))", changed);
		return changed;
	}
}
