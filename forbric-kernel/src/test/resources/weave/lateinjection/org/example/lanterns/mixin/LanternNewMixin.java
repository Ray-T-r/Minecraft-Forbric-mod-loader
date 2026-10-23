package org.example.lanterns.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fixture.lateinjection.Lamp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A lantern mod whose value modifier on the new in kindle() was written for a String, not the StringBuilder the new
 * makes, so @ModifyExpressionValue's own handler check fails while it injects. That injector has a path that inserts a
 * DUP behind a new before it checks its handler; the new is inside one branch of a conditional, so anything it left
 * there would meet the other branch with the wrong stack. Its other two injectors fit.
 */
@Mixin(Lamp.class)
public abstract class LanternNewMixin {
	@Inject(method = "light", at = @At("HEAD"))
	private void lanterns$head(CallbackInfoReturnable<String> cir) {
		((Lamp) (Object) this).trail.add("lantern-head");
	}

	@ModifyExpressionValue(method = "light", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;brightness(I)I"))
	private int lanterns$brighter(int original) {
		return original + 1;
	}

	@ModifyExpressionValue(method = "kindle", at = @At(value = "NEW", target = "java/lang/StringBuilder"))
	private StringBuilder lanterns$rekindle(String original) {
		return new StringBuilder("rekindled");
	}
}
