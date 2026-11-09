package net.minecraft.world.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/** Fixture stand-in: a mob's own answer to a player's use, vanilla's default passing it on. */
public class Mob extends LivingEntity {
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		return InteractionResult.PASS;
	}
}
