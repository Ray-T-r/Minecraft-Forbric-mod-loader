package forbric.stubmixins;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shape architectury and Collective write against vanilla: a name-only RETURN injection into getDestroySpeed that
 * captures the block state. On the merged base Mixin binds it to the carrier's getDestroySpeed(BlockState) stub, which
 * nothing calls.
 */
@Mixin(Player.class)
public abstract class PlayerSpeedMixin {
	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void forbric$sevenfoldOnSponge(BlockState state, CallbackInfoReturnable<Float> cir) {
		if (state.is(Blocks.SPONGE)) cir.setReturnValue(cir.getReturnValueF() * 7f);
	}
}
