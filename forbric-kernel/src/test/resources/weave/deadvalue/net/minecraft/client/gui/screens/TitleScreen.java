package net.minecraft.client.gui.screens;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.internal.BrandingControl;

/**
 * Fixture stand-in for the merged title screen: the version string is still built, vanilla's way, and stored for nothing;
 * the carrier's branding lines are drawn instead, through lambdas that call the same text(...). {@code nativeRender} is
 * vanilla's method, embedded by the test as the native reference: it draws the string bottom-left.
 */
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
		BrandingControl.forEachLine(true, true, (line, text) -> graphics.text(font, text, 2, height - (10 + line * 10), color));
		BrandingControl.forEachAboveCopyrightLine((line, text) -> graphics.text(font, text, width - 2, height - 10 * (line + 1), color));
	}

	public void nativeRender(GuiGraphicsExtractor graphics) {
		String version = "Minecraft " + SharedConstants.versionName();
		if (demo) version = version + " Demo";
		if (modded) version = version + I18n.get("menu.modded");
		int color = 0xFFFFFF;
		graphics.text(font, version, 2, height - 10, color);
	}
}
