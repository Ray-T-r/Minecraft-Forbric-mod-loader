package io.tern.sear.mixin;

import net.minecraft.fixture.kiln.Kiln;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A hook of a mod whose own game never calls vent either. */
@Mixin(Kiln.class)
abstract class KilnSearMixin {
	@Inject(method = "vent", at = @At("HEAD"))
	private void sear$mark(CallbackInfo info) {
		Kiln.LOG.add("sear");
	}
}
