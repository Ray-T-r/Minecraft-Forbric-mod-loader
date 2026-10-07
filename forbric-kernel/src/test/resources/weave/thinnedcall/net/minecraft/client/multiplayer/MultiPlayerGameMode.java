package net.minecraft.client.multiplayer;

import fixture.thinnedcall.Trace;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Hand-written stand-in, not game code: the merged performUseItemOn, which asks doesSneakBypassUse of both hand stacks
 * where vanilla asked isEmpty, and keeps vanilla's third isEmpty — on the stack in the hand, before its cooldown — as
 * its only one.
 */
public class MultiPlayerGameMode {
	public InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		return performUseItemOn(player, hand, hit);
	}

	private InteractionResult performUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		ItemStack itemStack = player.getItemInHand(hand);
		boolean bypass = player.getMainHandItem().doesSneakBypassUse() && player.getOffhandItem().doesSneakBypassUse();
		Trace.add(bypass ? "bypass" : "block");
		if (!itemStack.isEmpty() && !player.getCooldowns().isOnCooldown(itemStack)) {
			Trace.add("use:" + itemStack.name);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}
}
