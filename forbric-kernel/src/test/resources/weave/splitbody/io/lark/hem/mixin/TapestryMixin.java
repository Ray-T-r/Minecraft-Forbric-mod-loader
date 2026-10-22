package io.lark.hem.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.weft.loom.Tapestry;

/** A guest written against the native shape: a vetoing start and an end of {@code weave}. */
@Mixin(Tapestry.class)
public class TapestryMixin {
	@Inject(method = "weave", at = @At("HEAD"), cancellable = true)
	private void hem$before(String g, CallbackInfo ci) {
		Tapestry.LOG.add("before:" + g);
		if ("veto".equals(g)) ci.cancel();
	}

	@Inject(method = "weave", at = @At("TAIL"))
	private void hem$after(String g, CallbackInfo ci) {
		Tapestry.LOG.add("after:" + g);
	}
}
