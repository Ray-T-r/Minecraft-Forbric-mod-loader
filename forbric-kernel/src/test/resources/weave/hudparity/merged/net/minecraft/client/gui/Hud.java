package net.minecraft.client.gui;

import net.minecraft.client.DeltaTracker;

/**
 * A stand-in for the merged Hud: NeoForge's extractRenderState picks the contextual bar through
 * updateContextualBarRenderer every frame and draws it when the HUD is shown. Vanilla's extractHotbarAndDecorations is
 * still declared and still asks nextContextualInfoState, but nothing calls it.
 */
public class Hud {
	private final boolean isHidden;
	private ContextualInfo contextualInfoBar = ContextualInfo.EMPTY;

	public Hud(boolean isHidden) {
		this.isHidden = isHidden;
	}

	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		this.updateContextualBarRenderer();
		if (!this.isHidden) graphics.drawn.add("bar " + contextualInfoBar);
	}

	private void updateContextualBarRenderer() {
		this.contextualInfoBar = this.nextContextualInfoState();
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
