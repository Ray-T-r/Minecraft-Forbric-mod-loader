package io.wren.latch.mixin;

import net.minecraft.fixture.weir.Face;
import net.minecraft.fixture.weir.Stone;
import net.minecraft.fixture.weir.Weir;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A second mixin on the same decision whose handler happens to share the first one's name. */
@Mixin(Weir.class)
abstract class SluiceMixin {
	@Inject(method = "opens(Lnet/minecraft/fixture/weir/Stone;Lnet/minecraft/fixture/weir/Face;)Z", at = @At("HEAD"))
	private static void latch$opens(Stone stone, Face face, CallbackInfoReturnable<Boolean> info) {
		Weir.LOG.add("latchB " + stone + "/" + face);
	}
}
