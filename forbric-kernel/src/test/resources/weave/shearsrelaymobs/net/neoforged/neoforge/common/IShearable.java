package net.neoforged.neoforge.common;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Fixture stand-in for NeoForge's shearing contract (the real one also takes the level and position). */
public interface IShearable {
	boolean isShearable(ItemStack stack);

	void onSheared(Player player, ItemStack stack);
}
