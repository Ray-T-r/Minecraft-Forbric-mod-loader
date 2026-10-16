package com.tallyman.count.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import fixture.nativelocalpin.Trace;
import org.harbor.dock.Cargo;
import org.harbor.dock.Pier;
import org.harbor.dock.Ship;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Pier.class)
public abstract class PierMixin {
	@Inject(method = "unload", at = @At(value = "INVOKE", target = "Lorg/harbor/dock/Pier;store(Lorg/harbor/dock/Cargo;)V"))
	private void tally$count(Ship ship, CallbackInfo ci, @Local Cargo cargo) {
		Trace.add("tally:" + cargo.name);
	}
}
