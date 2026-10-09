package net.minecraft.world.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraftforge.common.ForgeHooks;

/**
 * A stand-in for the merged base's ItemStack: on the server, useOn hands the whole placement to MinecraftForge's
 * ForgeHooks.onPlaceItemIntoWorld, which makes the Item.useOn call vanilla's useOn made itself.
 */
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
		if (!context.getLevel().isClientSide()) return ForgeHooks.onPlaceItemIntoWorld(context);
		return InteractionResult.PASS;
	}
}
