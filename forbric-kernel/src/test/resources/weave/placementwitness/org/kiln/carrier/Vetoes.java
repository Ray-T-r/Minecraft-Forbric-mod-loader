package org.kiln.carrier;

import fixture.placementwitness.Log;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

/** The carrier's own use event, posted at the head of the use: a listener refuses carol's use and answers a success. */
public final class Vetoes {
	private Vetoes() {
	}

	public static InteractionResult ask(UseOnContext context) {
		if (context.getPlayer() != null && context.getPlayer().name().equals("carol")) {
			Log.EVENTS.add("vetoed");
			return InteractionResult.SUCCESS;
		}
		return null;
	}
}
