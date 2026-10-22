package net.minecraft.client.gui;

import java.util.List;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;

/** Fixture stand-in for vanilla's class, the one the guest was written against: one {@code tooltip}, which the deferred path calls. */
public class GuiGraphicsExtractor {
	public String drawnAt = "-";

	public void tooltip(Font font, List<?> lines, int x, int y, ClientTooltipPositioner positioner, Identifier style) {
		drawnAt = x + "," + y;
	}

	public void deferred(Font font, List<?> lines, int x, int y) {
		tooltip(font, lines, x, y, null, null);
	}
}
