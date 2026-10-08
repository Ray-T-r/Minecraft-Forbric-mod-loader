package net.neoforged.neoforge.client;

import java.util.Stack;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Hand-written stand-in, not NeoForge code: draws the layer stack, then the top screen, from outside Gui. */
public final class ClientHooks {
	private ClientHooks() {
	}

	public static void extractScreen(Screen top, Stack<Screen> layers, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		for (Screen layer : layers) graphics.draws.add("layer:" + layer.name());
		extractTop(top, graphics, mouseX, mouseY, partialTick);
	}
	private static void extractTop(Screen top, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		top.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick);
	}
}
