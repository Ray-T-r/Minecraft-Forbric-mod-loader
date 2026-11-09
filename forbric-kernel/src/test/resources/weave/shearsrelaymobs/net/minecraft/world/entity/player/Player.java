package net.minecraft.world.entity.player;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Fixture stand-in: a player holding one stack. */
public class Player extends LivingEntity {
	private final ItemStack held;

	public Player(ItemStack held) {
		this.held = held;
	}

	public ItemStack getItemInHand(InteractionHand hand) {
		return held;
	}
}
