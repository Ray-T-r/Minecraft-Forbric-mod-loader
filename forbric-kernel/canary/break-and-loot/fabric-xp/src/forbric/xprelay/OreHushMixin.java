package forbric.xprelay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels tryDropExperience at its start for coal and nether quartz ore, handed every argument: natively those break for no
 * experience. It cancels only when handed the provider the native caller passes, which the relay reads off that caller.
 */
@Mixin(Block.class)
public abstract class OreHushMixin {
	@Inject(method = "tryDropExperience", at = @At("HEAD"), cancellable = true)
	private void forbric$hush(ServerLevel level, BlockPos pos, ItemStack tool, IntProvider range, CallbackInfo info) {
		if (((Object) this == Blocks.COAL_ORE || (Object) this == Blocks.NETHER_QUARTZ_ORE) && range != null) info.cancel();
	}
}
