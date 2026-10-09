package net.minecraft.world.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import fixture.placementsingle.Log;

/** A stand-in item: using it on a block is an item interaction. */
public class Item {
	public InteractionResult useOn(UseOnContext context) {
		Log.EVENTS.add("used");
		return InteractionResult.SUCCESS;
	}
}
