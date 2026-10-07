package net.neoforged.neoforge.common;

import fixture.replacedcallredirect.Trace;
import net.minecraft.world.item.ItemStack;

/** Hand-written stand-in, not game code: NeoForge's question, whether the stack in use may go on as the one in hand. */
public final class CommonHooks {
	private CommonHooks() {
	}

	public static boolean canContinueUsing(ItemStack from, ItemStack to) {
		Trace.add("neoforge:" + from.name + "->" + to.name);
		return from.item.equals(to.item);
	}
}
