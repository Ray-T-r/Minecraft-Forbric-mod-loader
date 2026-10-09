package net.neoforged.neoforge.client.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** A stand-in for NeoForge's pooled renderers: one pool per registered state class. */
public class PictureInPictureRendererPool<T extends PictureInPictureRenderState> {
	public static Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRendererPool<?>> createPools(
			List<PictureInPictureRendererRegistration<?>> registrations) {
		Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRendererPool<?>> pools = new HashMap<>();
		for (PictureInPictureRendererRegistration<?> registration : registrations) pools.put(registration.stateClass(), new PictureInPictureRendererPool<>());
		return pools;
	}
}
