package org.example.sconces.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fixture.lateinjection.Lamp;
import org.example.sconces.Mounted;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The other mod: an interface, an ordinary callback and a late injector, all of which fit the lamp. The late one is on
 * the flare's brightness(3), the call a lantern wrap in another scenario rewrites before it fails at the next call.
 */
@Mixin(Lamp.class)
public abstract class SconceMixin implements Mounted {
	@Override
	public String mountedOn() {
		return "wall";
	}

	@Inject(method = "light", at = @At("RETURN"))
	private void sconces$lit(CallbackInfoReturnable<String> cir) {
		((Lamp) (Object) this).trail.add("sconce-return");
	}

	@ModifyExpressionValue(method = "flare", at = @At(value = "INVOKE", target = "Lfixture/lateinjection/Lamp;brightness(I)I"))
	private int sconces$bracket(int original) {
		return original + 100;
	}
}
