package io.wren.latch.mixin;

import net.minecraft.fixture.weir.Face;
import net.minecraft.fixture.weir.Kind;
import net.minecraft.fixture.weir.Stone;
import net.minecraft.fixture.weir.Weir;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A guest written against the native weir: its own latches open on every face but north, answered at the decision's entry. */
@Mixin(Weir.class)
abstract class WeirMixin {
	@Inject(method = "opens(Lnet/minecraft/fixture/weir/Stone;Lnet/minecraft/fixture/weir/Face;)Z", at = @At("HEAD"), cancellable = true)
	private static void latch$opens(Stone stone, Face face, CallbackInfoReturnable<Boolean> info) {
		Weir.LOG.add("latch " + stone + "/" + face);
		if (stone.kind() == Kind.LATCH) info.setReturnValue(face != Face.NORTH);
	}
}
