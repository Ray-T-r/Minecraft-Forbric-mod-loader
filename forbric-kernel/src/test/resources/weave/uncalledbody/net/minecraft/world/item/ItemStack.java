package net.minecraft.world.item;

import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Hand-written stand-in with the shape of NeoForge's tooltip on the merged base, no game code: addDetailsToTooltip is
 * the dispatcher, which makes none of vanilla's component calls (NeoForge draws those from its own appenders), and
 * vanilla's body is the private addDetailsToTooltipComponents, which makes them and which nothing calls — the
 * carrier-renames.txt pair {@code addDetailsToTooltip -> addDetailsToTooltipComponents | PIECE | FABRIC | UNCALLED}.
 */
public final class ItemStack {
	public final StringBuilder trace = new StringBuilder();

	public void addDetailsToTooltip(Item.TooltipContext context, TooltipDisplay display, Player player, TooltipFlag flag,
			Consumer<String> out) {
		trace.append("dispatch;");
	}

	private void addDetailsToTooltipComponents(Item.TooltipContext context, TooltipDisplay display, Player player,
			TooltipFlag flag, Consumer<String> out) {
		addToTooltip(null, context, display, out, flag);
		trace.append("body;");
	}

	public void addToTooltip(DataComponentType<?> type, Item.TooltipContext context, TooltipDisplay display, Consumer<String> out,
			TooltipFlag flag) {
		trace.append("component;");
	}
}
