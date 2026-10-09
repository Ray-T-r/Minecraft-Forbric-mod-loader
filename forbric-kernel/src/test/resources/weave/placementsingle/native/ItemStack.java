package net.minecraft.world.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

/** The ItemStack the mod was compiled against: vanilla's useOn decides the adventure check and makes the Item.useOn call itself. */
public final class ItemStack {
	private final Item item;

	public ItemStack(Item item) {
		this.item = item;
	}

	public Item getItem() {
		return item;
	}

	public boolean canPlaceOnBlockInAdventureMode(BlockInWorld block) {
		return false;
	}

	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		BlockPos pos = context.getClickedPos();
		if (player != null && !player.getAbilities().mayBuild && !this.canPlaceOnBlockInAdventureMode(new BlockInWorld(context.getLevel(), pos, false))) {
			return InteractionResult.PASS;
		}
		Item item = this.getItem();
		InteractionResult result = item.useOn(context);
		if (player != null && result instanceof InteractionResult.Success success && success.wasItemInteraction()) {
			player.used(item);
		}
		return result;
	}
}
