package fixture.libraryplugin.mod.mixin;

import fixture.libraryplugin.mod.Gear;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gear.class)
public abstract class GearMixin {
	@Inject(method = "value", at = @At("RETURN"), cancellable = true)
	private void mixedIn(CallbackInfoReturnable<String> result) {
		result.setReturnValue(result.getReturnValue() + "+mixin");
	}
}
