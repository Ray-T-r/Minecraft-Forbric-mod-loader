package net.minecraft.client.gui.render.pip;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** A stand-in: a picture-in-picture renderer for one render-state class. */
public abstract class PictureInPictureRenderer<T extends PictureInPictureRenderState> {
	public abstract Class<T> getRenderStateClass();

	public void prepare(T state, GuiRenderState renderState, FeatureRenderDispatcher dispatcher, int scale) {
	}

	public void close() {
	}
}
