package io.wren.latch.mixin;

import net.minecraft.fixture.weir.Face;
import net.minecraft.fixture.weir.Stone;
import net.minecraft.fixture.weir.Weir;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hook the rule must move but cannot: the mixin already declares a method of the name the rule's wrapper takes. */
@Mixin(Weir.class)
abstract class GateMixin {
	@Inject(method = "opens(Lnet/minecraft/fixture/weir/Stone;Lnet/minecraft/fixture/weir/Face;)Z", at = @At("HEAD"))
	private static void latch$gate(Stone stone, Face face, CallbackInfoReturnable<Boolean> info) {
		Weir.LOG.add("latchG " + stone + "/" + face);
	}

	private static void latch$gate$forbricentry() {
	}
}
