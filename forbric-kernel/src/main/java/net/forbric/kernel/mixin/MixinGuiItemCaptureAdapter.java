/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Capture the submitted GUI item while its render-state local is live, rather than at the empty-item join. */
public final class MixinGuiItemCaptureAdapter {
    public static final String PROPERTY = "forbric.guiItemCaptureAnchor";
    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String METHOD = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V";
    private static final String TARGET = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addItem(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;)V";
    private MixinGuiItemCaptureAdapter() { }
    public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
        if (!MixinCallbackShape.targets(mixin, GUI) || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
        ClassNode target = targets.apply(GUI);
        MethodNode capture = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
                        MixinHandlerShape.Want.captured("Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;"))
                && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.binds(m, target, METHOD) && MixinCallbackShape.plainPoint(m, "TAIL", null) && MixinCallbackShape.instance(m));
        if (capture == null || !capture.desc.endsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;)V")) return 0;
        AnnotationNode injector = MixinFit.injectorOf(capture);
        if (injector == null || !MixinCallbackShape.binds(capture, target, METHOD)) return 0;
        List<AnnotationNode> ats = MixinFit.atNodes(injector);
        if (ats.size() != 1 || !"TAIL".equals(MixinFit.value(ats.getFirst(), "value"))) return 0;
        if (target == null) return 0;
        MethodNode method = target.methods.stream().filter(m -> (m.name + m.desc).equals(METHOD)).findFirst().orElse(null);
        if (method == null) return 0;
        int submits = 0, allocations = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/renderer/state/gui/GuiRenderState")
                    && call.name.equals("addItem") && call.desc.equals("(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;)V")) submits++;
            if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("net/minecraft/client/renderer/item/TrackingItemStackRenderState")) allocations++;
        }
        if (submits != 1 || allocations != 1) return 0;
        AnnotationNode at = ats.getFirst();
        at.values = new ArrayList<>(List.of("value", "INVOKE", "target", TARGET, "shift", new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"}));
        return 1;
    }
}
