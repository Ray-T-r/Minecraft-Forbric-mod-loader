package fixture.overloadpin.mixin;

import fixture.overloadpin.SkyPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Written the way a mod writes it against vanilla, where {@code lambda$render$0} is unique: name only, and a handler
 * shaped for vanilla's {@code (String, StringBuilder)}. The carrier's {@code (StringBuilder, String, String)} has two
 * Strings, so MixinHandlerShim alone cannot say which one to hand over and declines. By default MixinRetarget reads the
 * handler against vanilla's lambda in the native reference and binds it, handing over the carrier's {@code sky}; the
 * handler prints the String it got, so a mapping that handed over {@code moon} shows. Only with the handler rule off
 * ({@code -Dforbric.mixinFit.handlerFit=off}) does the mixin reach Mixin unbound, Mixin rejects the descriptor, and the
 * question is whether the report says why.
 */
@Mixin(SkyPass.class)
public abstract class SkyPassMixin {
	@Inject(method = "lambda$render$0", at = @At("HEAD"))
	private void beforeSky(String sky, StringBuilder out, CallbackInfo ci) {
		out.append("[sky hook ran ").append(sky).append("] ");
	}
}
