package net.minecraft.world.entity.animal.golem;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.IShearable;

/**
 * Fixture stand-in for NeoForge's snow golem: mobInteract reads the held stack and passes (javap, NeoForge 26.2.0.93:
 * getItemInHand; getstatic InteractionResult.PASS; areturn); ShearsItem's entity entry takes its pumpkin.
 */
public class SnowGolem extends Mob implements IShearable {
	private boolean pumpkin = true;

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		return InteractionResult.PASS;
	}

	@Override
	public boolean isShearable(ItemStack stack) {
		return pumpkin;
	}

	@Override
	public void onSheared(Player player, ItemStack stack) {
		pumpkin = false;
	}

	public boolean sheared() {
		return !pumpkin;
	}
}
