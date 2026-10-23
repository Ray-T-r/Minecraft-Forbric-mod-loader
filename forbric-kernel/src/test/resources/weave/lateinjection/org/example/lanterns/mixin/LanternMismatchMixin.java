package org.example.lanterns.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.lateinjection.Lamp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A lantern mod whose wrap names the brightness call without a descriptor and was written for a brightness() that takes
 * nothing: the call it binds takes the level, so @WrapOperation's own handler check fails while it injects. Its other
 * two injectors fit.
 */
@Mixin(Lamp.class)
public abstract class LanternMismatchMixin {
	@Inject(method = "light", at = @At("HEAD"))
	private void lanterns$head(CallbackInfoReturnable<String> cir) {
		((Lamp) (Object) this).trail.add("lantern-head");
	}

	@ModifyExpressionValue(method = "light", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;brightness(I)I"))
	private int lanterns$brighter(int original) {
		return original + 1;
	}

	@WrapOperation(method = "light", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;brightness"))
	private int lanterns$dim(Lamp lamp, Operation<Integer> original) {
		return original.call(lamp) - 1;
	}
}
