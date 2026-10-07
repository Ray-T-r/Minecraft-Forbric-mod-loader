package com.example.oldclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fixture.thinnedcall.Trace;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Synthetic guest mixin in the shape of ViaFabricPlus' 1.12.2 placement hook: before vanilla's third isEmpty, the one
 * on the stack in the hand, once the block has had its turn.
 */
@Mixin(MultiPlayerGameMode.class)
public class PlaceMixin {
	@Inject(method = "performUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEmpty()Z", ordinal = 2))
	private void place(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Trace.add("old-client:" + player.getItemInHand(hand).name);
	}
}
