package fixture.optionalpartner.mixin;

import net.minecraft.fixture.optionalpartner.Plaque;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mod alpha's compatibility mixin for mod beta: no field, no interface, one injector into the method beta's own mixin
 * adds to the game's Plaque, in a config whose injectors.defaultRequire is -1. With beta absent, native Mixin finds no
 * target, requires none, and drops the injector without a word.
 */
@Mixin(Plaque.class)
public abstract class PlaqueCompatMixin {
	@Inject(method = "beta$cacheable", at = @At("RETURN"), cancellable = true)
	private void alpha$afterCacheable(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(Boolean.FALSE);
	}
}
