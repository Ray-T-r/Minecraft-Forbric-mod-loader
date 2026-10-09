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
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;
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
 * <p>What goes in is MinecraftForge's own registration event, which is the only thing that would have written
 * this map on a MinecraftForge instance and which nothing on the merged base posts. So a MinecraftForge mod's
 * picture-in-picture renderer — the shape a minimap or an in-world preview uses — was registered into an event
 * that was never fired, and drew nothing: no exception, no log, the element simply absent.
 *
 * <p>An empty map is still the right answer when no mod registers one, and it is a better answer than the null
 * the field held: the repair's fallback reads it without a null check, so the first frame that reached a state
 * class with no pool would have thrown inside the game's own render loop.
 *
 * <p>{@code -Dforbric.forgePipRenderers=off} goes back to an empty map, which is the old behaviour minus that
 * latent throw.
 */
public final class KernelForgePipRenderers {
	static final String PROPERTY = "forbric.forgePipRenderers";

	private KernelForgePipRenderers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Forge/vanilla mixins (including Physics Mod) append renderer instances to the constructor's List.
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

	/** Keeps the exact renderer instances supplied by constructor mixins alongside Forge event registrations. */
	public static Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> build(List<?> mixed) {
		Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> renderers =
				new LinkedHashMap<>(build());
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
	 * have replaced, wrapped or filled — plus the constructor list's renderers and MinecraftForge's registrations
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

	/**
	 * The map {@code GuiRenderer.<init>} now assigns, built by posting MinecraftForge's registration event.
	 *
	 * <p>Never null and never throws: this runs inside the game's own constructor, and a failure here would take
	 * the whole client down over a feature that was absent a moment ago.
	 */
	public static Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> build() {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/PipRenderers] -D%s=off — a MinecraftForge mod's picture-in-picture renderers "
					+ "will not draw", PROPERTY);
			return Map.of();
		}
		try {
			List<PictureInPictureRenderer<?>> created = new ArrayList<>();
			ImmutableMap.Builder<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> byState =
					ImmutableMap.builder();
			RegisterPictureInPictureRendererEvent.BUS.post(
					new RegisterPictureInPictureRendererEvent(created, byState));

			Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> registered =
					byState.buildKeepingLast();
			if (registered.isEmpty()) {
				ForbricLog.debug("[Forbric/PipRenderers] no MinecraftForge mod registered a picture-in-picture "
						+ "renderer");
			} else {
				ForbricLog.info("[Forbric/PipRenderers] %d MinecraftForge picture-in-picture renderer(s) registered "
						+ "— the merged GuiRenderer had no writer for that map at all", registered.size());
			}
			return registered;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/PipRenderers] could not collect MinecraftForge's picture-in-picture renderers "
					+ "— a mod's in-world preview or minimap element will draw nothing", Reflect.unwrap(t));
			return Map.of();
		}
	}
}
