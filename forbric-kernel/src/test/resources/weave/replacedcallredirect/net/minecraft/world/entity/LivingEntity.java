package net.minecraft.world.entity;

import fixture.replacedcallredirect.Trace;
import net.minecraft.world.item.ItemStack;

/**
 * Hand-written stand-in, not game code: the merged updatingUsingItem, which asks NeoForge's canContinueUsing(in use,
 * in hand) where vanilla asked isSameItem(in hand, in use), and keeps using the item only while the stack in hand is
 * the stack in use.
 */
public class LivingEntity {
	public ItemStack useItem;
	public ItemStack inHand;

	public void updatingUsingItem() {
		ItemStack hand = this.inHand;
		if (net.neoforged.neoforge.common.CommonHooks.canContinueUsing(this.useItem, hand)) this.useItem = hand;
		if (hand == this.useItem) Trace.add("update");
		else Trace.add("stop");
	}
}
