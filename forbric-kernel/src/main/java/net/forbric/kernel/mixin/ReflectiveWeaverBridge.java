/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** The live, standard Mixin transformer slot for JECharacters' existing transformer decorator. */
public final class ReflectiveWeaverBridge {
    public static final class Holder {
        public volatile IMixinTransformer mixinTransformer;
        private Holder(IMixinTransformer current) { mixinTransformer = current; }
    }
    private ReflectiveWeaverBridge() { }
    public static Holder holder() {
        IMixinTransformer current = MixinWeaverSlot.currentOr(MixinWeaverSlot.original());
        if (current == null) throw new IllegalStateException("Mixin is not initialized");
        Holder holder = new Holder(current);
        MixinWeaverSlot.watch(() -> holder.mixinTransformer);
        return holder;
    }
    public static Field field() throws NoSuchFieldException { return Holder.class.getDeclaredField("mixinTransformer"); }
}
