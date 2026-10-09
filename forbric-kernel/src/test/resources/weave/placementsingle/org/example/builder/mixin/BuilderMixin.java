package org.example.builder.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import fixture.placementsingle.Log;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Not Create, and not Create's pair: just before vanilla's Item.useOn call it notes who places (the player, not the
 * item) and shares the clicked position; at the item-interaction check after the call it reads the share back — with a
 * bare "useOn" selector and no @Local there.
 */
@Mixin(ItemStack.class)
public class BuilderMixin {
	@Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
	private void beforeUse(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir, @Local Player player, @Share("clicked") LocalRef<String> clicked) {
		Log.EVENTS.add("before " + (player == null ? "nobody" : player.name()));
		clicked.set(String.valueOf(context.getClickedPos()));
	}

	@Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/InteractionResult$Success;wasItemInteraction()Z"))
	private void afterUse(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir, @Share("clicked") LocalRef<String> clicked) {
		Log.EVENTS.add("after " + clicked.get());
	}
}
