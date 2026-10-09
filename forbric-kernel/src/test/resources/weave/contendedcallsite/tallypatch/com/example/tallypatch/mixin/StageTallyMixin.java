package com.example.tallypatch.mixin;

import org.spongepowered.asm.mixin.Mixin;

import fixture.stagecall.Stage;

/** Changes nothing: it is here so Mixin hands Stage to the config's plugin. */
@Mixin(Stage.class)
abstract class StageTallyMixin {
}
