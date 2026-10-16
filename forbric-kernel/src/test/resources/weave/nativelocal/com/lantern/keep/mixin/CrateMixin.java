package com.lantern.keep.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import fixture.nativelocal.Trace;
import java.util.function.Consumer;
import org.quarry.yard.Bundle;
import org.quarry.yard.Crate;
import org.quarry.yard.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Crate.class)
public abstract class CrateMixin {
	@Inject(method = "each", at = @At(value = "INVOKE", target = "Lorg/quarry/yard/Bundle;forEach(Lorg/quarry/yard/Slot;Ljava/util/function/Consumer;)V"))
	private void keep$seen(Slot slot, Consumer<String> consumer, CallbackInfo ci, @Local Bundle bundle) {
		Trace.add("seen:" + bundle.name + "/" + slot.name().toLowerCase());
	}
}
