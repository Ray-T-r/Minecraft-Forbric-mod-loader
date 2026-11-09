package net.minecraft.world.entity.animal.sheep;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.IShearable;

/**
 * Fixture stand-in for NeoForge's sheep: its mobInteract reads the held stack and asks nothing about it (javap, NeoForge
 * 26.2.0.93: getItemInHand; invokespecial Animal.mobInteract; areturn); ShearsItem's entity entry shears it.
 */
public class Sheep extends Mob implements IShearable {
	private boolean sheared;

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		return super.mobInteract(player, hand);
	}

	@Override
	public boolean isShearable(ItemStack stack) {
		return !sheared;
	}

	@Override
	public void onSheared(Player player, ItemStack stack) {
		sheared = true;
	}

	public boolean sheared() {
		return sheared;
	}
}
