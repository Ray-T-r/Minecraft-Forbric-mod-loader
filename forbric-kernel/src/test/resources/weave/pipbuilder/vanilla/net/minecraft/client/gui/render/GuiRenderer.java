package net.minecraft.client.gui.render;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.common.collect.ImmutableMap;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** A stand-in for vanilla's GuiRenderer constructor: the plain map, filled from ImmutableMap.builder(). */
public class GuiRenderer {
	private final GuiRenderState renderState;
	private final FeatureRenderDispatcher featureRenderDispatcher;
	private final Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> pictureInPictureRenderers;

	public GuiRenderer(GuiRenderState renderState, FeatureRenderDispatcher featureRenderDispatcher,
			List<PictureInPictureRenderer<?>> pictureInPictureRenderers) {
		this.renderState = renderState;
		this.featureRenderDispatcher = featureRenderDispatcher;
		ImmutableMap.Builder<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> builder = ImmutableMap.builder();
		for (PictureInPictureRenderer<?> pictureInPictureRenderer : pictureInPictureRenderers) {
			builder.put(pictureInPictureRenderer.getRenderStateClass(), pictureInPictureRenderer);
		}
		this.pictureInPictureRenderers = builder.buildOrThrow();
	}

	/** Each state class's simple name and its renderer, sorted. */
	public String renderers() {
		if (pictureInPictureRenderers == null) return "no map";
		Map<String, String> sorted = new TreeMap<>();
		pictureInPictureRenderers.forEach((state, renderer) -> sorted.put(state.getSimpleName(), String.valueOf(renderer)));
		return sorted.toString();
	}
}
