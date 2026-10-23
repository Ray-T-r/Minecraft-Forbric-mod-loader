package org.example.lanterns.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.lateinjection.Lamp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A lantern mod whose wrap names the brightness call without a descriptor and was written for brightness(int): in
 * flare() it matches brightness(3) first, which it wraps, and then brightness(), where its handler check fails. Its
 * head callback fits.
 */
@Mixin(Lamp.class)
public abstract class LanternPartialMixin {
	@Inject(method = "light", at = @At("HEAD"))
	private void lanterns$head(CallbackInfoReturnable<String> cir) {
		((Lamp) (Object) this).trail.add("lantern-head");
	}

	@WrapOperation(method = "flare", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;brightness"))
	private int lanterns$tenfold(Lamp lamp, int level, Operation<Integer> original) {
		return original.call(lamp, level) * 10;
	}
}
