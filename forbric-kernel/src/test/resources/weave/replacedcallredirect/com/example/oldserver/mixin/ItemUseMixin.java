package com.example.oldserver.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fixture.replacedcallredirect.OldServer;
import fixture.replacedcallredirect.Trace;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Synthetic guest mixin in the shape of ViaFabricPlus' 1.14.3 item-use redirect: on an old server, an item stays in
 * use only while the stack in hand is the very stack in use; otherwise vanilla's test, forwarded. Vanilla passes the
 * stack in hand first; the handler reports which stack it saw where.
 */
@Mixin(LivingEntity.class)
public class ItemUseMixin {
	@Redirect(method = "updatingUsingItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;isSameItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
	private boolean sameStack(ItemStack inHand, ItemStack inUse) {
		if (OldServer.on()) {
			Trace.add("old:" + inHand.name + "/" + inUse.name);
			return inHand == inUse;
		}
		return ItemStack.isSameItem(inHand, inUse);
	}
}
