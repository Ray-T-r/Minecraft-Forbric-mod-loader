package net.minecraft.world.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

/**
 * A stand-in for the merged ItemStack: the carrier's use event at the head (a listener may answer for the use), then on a
 * server the whole placement in the carrier's transaction, which makes vanilla's Item.useOn call through the stack's relay.
 */
public final class ItemStack {
	private final Item item;

	public ItemStack(Item item) {
		this.item = item;
	}

	public Item getItem() {
		return item;
	}

	public boolean canPlaceOnBlockInAdventureMode(BlockInWorld block) {
		return false;
	}

	public InteractionResult useOn(UseOnContext context) {
		InteractionResult answered = org.kiln.carrier.Vetoes.ask(context);
		if (answered != null) return answered;
		if (!context.getLevel().isClientSide()) return org.kiln.carrier.Kiln.fire(context);
		return InteractionResult.PASS;
	}

	public InteractionResult relayUse(Item item, UseOnContext context) {
		return item.useOn(context);
	}

	public static InteractionResult relayUseFor(Item item, UseOnContext context) {
		return context.getItemInHand().relayUse(item, context);
	}
}
