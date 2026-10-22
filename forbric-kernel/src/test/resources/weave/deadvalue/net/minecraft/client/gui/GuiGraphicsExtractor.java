package net.minecraft.client.gui;

import java.util.ArrayList;
import java.util.List;

/** Fixture stand-in that records every string drawn and where. */
public class GuiGraphicsExtractor {
	public final List<String> drawn = new ArrayList<>();

	public void text(Font font, String text, int x, int y, int color) {
		drawn.add(text + "@" + x + "," + y);
	}
}
