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
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent;

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
