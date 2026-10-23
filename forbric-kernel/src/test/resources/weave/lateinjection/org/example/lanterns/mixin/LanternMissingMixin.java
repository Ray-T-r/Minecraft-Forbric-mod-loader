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
 * A lantern mod written against a lamp that calls flicker() in light(), as spruceui's NeoForge GuiMixin is written
 * against a Gui.extractRenderState that calls ClientHooks.extractScreen: the call is not there, so the wrap finds
 * nothing. Its other two injectors fit: an ordinary callback and a late injector on the brightness call.
 */
@Mixin(Lamp.class)
public abstract class LanternMissingMixin {
	@Inject(method = "light", at = @At("HEAD"))
	private void lanterns$head(CallbackInfoReturnable<String> cir) {
		((Lamp) (Object) this).trail.add("lantern-head");
	}

	@ModifyExpressionValue(method = "light", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;brightness(I)I"))
	private int lanterns$brighter(int original) {
		return original + 1;
	}

	@WrapOperation(method = "light", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;flicker()V"))
	private void lanterns$steady(Lamp lamp, Operation<Void> original) {
		original.call(lamp);
	}
}
