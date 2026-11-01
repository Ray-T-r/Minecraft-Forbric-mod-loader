package net.minecraft.world.item;

import fixture.relocatedcall.CarrierHooks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

/**
 * ItemStack as the kernel defines it on NeoForge's game, after ItemUseOnInjector.
 *
 * <p>The carrier makes the Item.useOn call outside useOn (a hook class, as NeoForge's CommonHooks), and the kernel routes that call
 * through forbric$useOnItem, whose whole body is the call. This harness installs no pre-Mixin transform chain, so
 * that relay is written here in the exact shape MixinRelocatedCall requires as proof: aload_1, aload_2, the call,
 * areturn — and useOn itself no longer makes the call.
 */
public class ItemStack {
	private final Item item;

	public ItemStack(Item item) {
		this.item = item;
	}

	public Item getItem() {
		return item;
	}

	public InteractionResult useOn(UseOnContext context) {
		return CarrierHooks.placeItem(this, context);
	}

	public InteractionResult forbric$useOnItem(Item item, UseOnContext context) {
		return item.useOn(context);
	}
}
