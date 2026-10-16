package com.hollow.tint.mixin;

import org.atelier.scene.Panel;
import org.spongepowered.asm.mixin.Mixin;

/** Present so the config has a second mixin that anchors on nothing the inlines touch. */
@Mixin(Panel.class)
public abstract class FieldMixin {
}
