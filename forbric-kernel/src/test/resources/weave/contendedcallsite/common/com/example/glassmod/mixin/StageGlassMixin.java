package com.example.glassmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fixture.stagecall.Paints;
import fixture.stagecall.Stage;

/** Takes the solid-paint call in render() and answers for it, as a redirect does: the call is no longer in render(). */
@Mixin(value = Stage.class, priority = 500)
abstract class StageGlassMixin {
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "fixture/stagecall/Paints.solid(Ljava/lang/String;)Ljava/lang/String;"), require = 0)
	private String glassmod$solid(String id) {
		return "glass:" + Paints.solid(id);
	}
}
