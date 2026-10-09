package net.minecraft.client.gui;

import net.minecraft.client.DeltaTracker;

/** A stand-in for vanilla's Hud: a shown HUD draws the hotbar and decorations, which pick the contextual bar. */
public class Hud {
	private final boolean isHidden;

	public Hud(boolean isHidden) {
		this.isHidden = isHidden;
	}

	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!this.isHidden) this.extractHotbarAndDecorations(graphics, deltaTracker);
	}

	public void extractHotbarAndDecorations(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		ContextualInfo bar = this.nextContextualInfoState();
		graphics.drawn.add("bar " + bar);
	}

	public ContextualInfo nextContextualInfoState() {
		return ContextualInfo.EXPERIENCE;
	}

	public boolean isHidden() {
		return isHidden;
	}

	public enum ContextualInfo {
		EMPTY, EXPERIENCE, LOCATOR
	}
}
