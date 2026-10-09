package com.example.glassmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fixture.stagecall.Stage;

/** The look-alike injector: anchored on the same call, but it leaves the call where it is. */
@Mixin(value = Stage.class, priority = 500)
abstract class StageWatchMixin {
	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "fixture/stagecall/Paints.solid(Ljava/lang/String;)Ljava/lang/String;"), require = 0)
	private void glassmod$watch(CallbackInfoReturnable<String> cir) {
		System.out.println("[glassmod] watched the solid paint call");
	}
}
