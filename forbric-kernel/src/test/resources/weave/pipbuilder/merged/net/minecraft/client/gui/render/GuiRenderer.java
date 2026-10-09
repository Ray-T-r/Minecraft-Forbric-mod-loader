package net.minecraft.client.gui.render;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererPool;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;

/**
 * A stand-in for the merged GuiRenderer: NeoForge's constructor fills its pools and makes no ImmutableMap.builder() call;
 * vanilla's plain map is declared, read by vanilla's orphaned preparePictureInPictureState, and written nowhere.
 */
public class GuiRenderer {
	private final GuiRenderState renderState;
	private final FeatureRenderDispatcher featureRenderDispatcher;
	private final Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRendererPool<?>> pictureInPictureRendererPools;
	private Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> pictureInPictureRenderers;

	public GuiRenderer(GuiRenderState renderState, FeatureRenderDispatcher featureRenderDispatcher,
			List<PictureInPictureRendererRegistration<?>> pipRendererFactories) {
		this.renderState = renderState;
		this.featureRenderDispatcher = featureRenderDispatcher;
		this.pictureInPictureRendererPools = PictureInPictureRendererPool.createPools(pipRendererFactories);
	}

	@SuppressWarnings("unchecked")
	private <T extends PictureInPictureRenderState> void preparePictureInPictureState(T state, int scale) {
		PictureInPictureRenderer<T> renderer = (PictureInPictureRenderer<T>) this.pictureInPictureRenderers.get(state.getClass());
		if (renderer != null) renderer.prepare(state, this.renderState, this.featureRenderDispatcher, scale);
	}

	private <T extends PictureInPictureRenderState> boolean preparePictureInPictureState(T state, int scale, boolean first) {
		PictureInPictureRendererPool<?> pool = this.pictureInPictureRendererPools.get(state.getClass());
		if (pool == null) return false;
		return first;
	}

	public void close() {
		this.pictureInPictureRendererPools.clear();
	}

	/** Each state class's simple name and its renderer, sorted. */
	public String renderers() {
		if (pictureInPictureRenderers == null) return "no map";
		Map<String, String> sorted = new TreeMap<>();
		pictureInPictureRenderers.forEach((state, renderer) -> sorted.put(state.getSimpleName(), String.valueOf(renderer)));
		return sorted.toString();
	}
}
