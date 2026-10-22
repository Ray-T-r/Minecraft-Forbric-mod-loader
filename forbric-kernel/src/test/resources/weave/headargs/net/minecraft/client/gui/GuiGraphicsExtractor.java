package net.minecraft.client.gui;

import java.util.List;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Fixture stand-in with the shape of the carrier-stubs.txt row for {@code tooltip}: vanilla's six-argument signature,
 * declared first, only forwards to the carrier's seven-argument overload, which carries the body; the deferred path
 * calls the body directly, as the merged {@code lambda$setTooltipForNextFrameInternal$0} does.
 */
public class GuiGraphicsExtractor {
	public String drawnAt = "-";

	public void tooltip(Font font, List<?> lines, int x, int y, ClientTooltipPositioner positioner, Identifier style) {
		tooltip(font, lines, x, y, positioner, style, ItemStack.EMPTY);
	}

	public void tooltip(Font font, List<?> lines, int x, int y, ClientTooltipPositioner positioner, Identifier style, ItemStack stack) {
		drawnAt = x + "," + y;
	}

	public void deferred(Font font, List<?> lines, int x, int y) {
		tooltip(font, lines, x, y, null, null, ItemStack.EMPTY);
	}
}
