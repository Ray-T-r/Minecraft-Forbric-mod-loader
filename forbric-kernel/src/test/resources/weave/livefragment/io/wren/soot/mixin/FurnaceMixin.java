package io.wren.soot.mixin;

import net.minecraft.fixture.kiln.Furnace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A guest written against the native shape: one hook at each reading of the gauge in {@code fire}. */
@Mixin(Furnace.class)
public class FurnaceMixin {
	@Inject(method = "fire", at = @At(value = "INVOKE", target = "Lnet/minecraft/fixture/kiln/Gauge;mode()I", ordinal = 0))
	private void soot$first(String fuel, CallbackInfo ci) {
		Furnace.LOG.add("first:" + fuel);
	}

	@Inject(method = "fire", at = @At(value = "INVOKE", target = "Lnet/minecraft/fixture/kiln/Gauge;mode()I", ordinal = 1))
	private void soot$second(String fuel, CallbackInfo ci) {
		Furnace.LOG.add("second:" + fuel);
	}
}
