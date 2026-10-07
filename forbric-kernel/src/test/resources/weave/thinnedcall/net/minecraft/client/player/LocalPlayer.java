package net.minecraft.client.player;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;

/** Hand-written stand-in, not game code: a player with a stack in each hand. */
public final class LocalPlayer {
	private final ItemStack main;
	private final ItemStack off;
	private final ItemCooldowns cooldowns = new ItemCooldowns();

	public LocalPlayer(ItemStack main, ItemStack off) {
		this.main = main;
		this.off = off;
	}

	public ItemStack getItemInHand(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? main : off;
	}

	public ItemStack getMainHandItem() {
		return main;
	}

	public ItemStack getOffhandItem() {
		return off;
	}

	public ItemCooldowns getCooldowns() {
		return cooldowns;
	}
}
