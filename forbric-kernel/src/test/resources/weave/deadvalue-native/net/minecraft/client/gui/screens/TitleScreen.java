package net.minecraft.client.gui.screens;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;

/** Fixture stand-in for vanilla's title screen, the one the guest was written against: it draws its version string. */
public class TitleScreen {
	public boolean demo;
	public boolean modded = true;
	public int width = 200, height = 100;
	private final Font font = new Font();

	public void extractRenderState(GuiGraphicsExtractor graphics) {
		String version = "Minecraft " + SharedConstants.versionName();
		if (demo) version = version + " Demo";
		if (modded) version = version + I18n.get("menu.modded");
		int color = 0xFFFFFF;
		graphics.text(font, version, 2, height - 10, color);
	}
}
