/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;

/**
 * Fills {@code GuiRenderer.pictureInPictureRenderers} — the map the byte merge left with no writer at all.
 *
 * <p>{@code GuiRenderer} ends up carrying both ecosystems' versions of picture-in-picture: NeoForge's pooled
 * {@code pictureInPictureRendererPools}, which its constructor fills, and vanilla's plain
 * {@code Class -> PictureInPictureRenderer} map, which the merged constructor does not assign at all — the field
 * is declared, read in one place, and written nowhere. {@link net.forbric.kernel.transform
 * .ForbricMergedBaseCompatTransformer}'s repair already routes NeoForge's "no pool for this state class" miss into
 * that map; this is what puts something in it.
 *
 * <p>What goes in is what the constructor's own list carries besides NeoForge's registrations: the renderer
 * instances guest mixins add there (Physics Mod, fabric-rendering, Xaero, malilib), which are vanilla's plain-map
 * renderers and must never be owned by a NeoForge pool. On the genuine base the same map is the twin
 * VanillaMemberTwinInjector adds back, filled the same way.
 *
 * <p>An empty map is still the right answer when no mod supplies one, and it is a better answer than the null
 * the field held: the repair's fallback reads it without a null check, so the first frame that reached a state
 * class with no pool would have thrown inside the game's own render loop.
 */
public final class KernelForgePipRenderers {
	private KernelForgePipRenderers() {
	}

	/**
	 * Vanilla mixins (including Physics Mod) append renderer instances to the constructor's List.
	 * NeoForge uses the same erased descriptor for a list of registrations. Preserve that list for the
	 * plain map, but only pass registrations to createPools; a pool must never own a mod's singleton.
	 */
	public static List<PictureInPictureRendererRegistration<?>> poolRegistrations(List<?> mixed) {
		List<PictureInPictureRendererRegistration<?>> registrations = new ArrayList<>();
		for (Object value : mixed) {
			if (value instanceof PictureInPictureRendererRegistration<?> registration) registrations.add(registration);
			else if (!(value instanceof PictureInPictureRenderer<?>)) {
				throw new IllegalArgumentException("Unknown picture-in-picture registration: " + value);
			}
		}
		return registrations;
	}

	/** Keeps the exact renderer instances supplied by constructor mixins, in the order the list holds them. */
	public static Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> build(List<?> mixed) {
		Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> renderers = new LinkedHashMap<>();
		int direct = 0;
		for (Object value : mixed) {
			if (value instanceof PictureInPictureRenderer<?> renderer) {
				renderers.put(renderer.getRenderStateClass(), renderer);
				direct++;
			}
		}
		if (direct > 0) ForbricLog.info("[Forbric/PipRenderers] retained %d constructor-supplied renderer(s) "
				+ "in the plain map, separate from NeoForge's pools", direct);
		return renderers;
	}

	/**
	 * The plain map from the builder {@code GuiRenderer.<init>} makes where vanilla makes it — which guest injectors may
	 * have replaced, wrapped or filled — plus the constructor list's renderers
	 * ({@link #build(List)}). A state class registered twice keeps the later renderer, as {@link #build(List)} always
	 * has: never a throw inside the game's own constructor.
	 */
	public static Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> complete(
			ImmutableMap.Builder<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> builder,
			Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> registered) {
		builder.putAll(registered);
		return builder.buildKeepingLast();
	}

	/** The merged close() only closes pools; plain renderers retain vanilla's whole-GuiRenderer lifetime. */
	public static void close(Map<?, ? extends PictureInPictureRenderer<?>> renderers) {
		Set<PictureInPictureRenderer<?>> closed = Collections.newSetFromMap(new IdentityHashMap<>());
		for (PictureInPictureRenderer<?> renderer : renderers.values()) {
			if (closed.add(renderer)) renderer.close();
		}
	}

}
