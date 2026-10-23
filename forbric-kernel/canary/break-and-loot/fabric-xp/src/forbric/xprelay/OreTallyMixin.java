package forbric.xprelay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Replaces redstone ore's experience where vanilla stores it in tryDropExperience: a hundred more. Natively (Fabric) a redstone
 * ore break drops 101..105; on the merged base nothing calls tryDropExperience, so this ran only through the kernel's relay.
 */
@Mixin(Block.class)
public abstract class OreTallyMixin {
	@ModifyVariable(method = "tryDropExperience", at = @At("STORE"), ordinal = 0)
	private int forbric$tally(int value, ServerLevel level, BlockPos pos, ItemStack tool) {
		return (Object) this == Blocks.REDSTONE_ORE ? value + 100 : value;
	}
}
