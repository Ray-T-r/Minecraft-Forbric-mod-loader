package net.minecraftforge.common;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

/** A stand-in for MinecraftForge's placement transaction: vanilla's checks, then the Item.useOn call, then the stat. */
public final class ForgeHooks {
	private ForgeHooks() {
	}

	public static InteractionResult onPlaceItemIntoWorld(UseOnContext context) {
		ItemStack itemstack = context.getItemInHand();
		Level level = context.getLevel();
		Player player = context.getPlayer();
		if (player != null && !player.getAbilities().mayBuild
				&& !itemstack.canPlaceOnBlockInAdventureMode(new BlockInWorld(level, context.getClickedPos(), false))) {
			return InteractionResult.PASS;
		}
		Item item = itemstack.getItem();
		InteractionResult ret = item.useOn(context);
		if (player != null && ret instanceof InteractionResult.Success success && success.wasItemInteraction()) player.used(item);
		return ret;
	}
}
