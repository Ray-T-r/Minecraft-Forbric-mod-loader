package org.kiln.carrier;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

/** The carrier's placement transaction: vanilla's checks, then the use through the stack's own relay, then the stat. */
public final class Kiln {
	private Kiln() {
	}

	public static InteractionResult fire(UseOnContext context) {
		ItemStack stack = context.getItemInHand();
		Level level = context.getLevel();
		Player player = context.getPlayer();
		if (player != null && !player.getAbilities().mayBuild && !stack.canPlaceOnBlockInAdventureMode(new BlockInWorld(level, context.getClickedPos(), false))) {
			return InteractionResult.PASS;
		}
		Item item = stack.getItem();
		InteractionResult result = ItemStack.relayUseFor(item, context);
		if (player != null && result instanceof InteractionResult.Success success && success.wasItemInteraction()) player.used(item);
		return result;
	}
}
