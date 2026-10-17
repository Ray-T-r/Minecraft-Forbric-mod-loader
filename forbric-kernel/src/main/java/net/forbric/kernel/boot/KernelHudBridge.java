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

package net.forbric.kernel.boot;

import net.forbric.kernel.util.ForbricLog;

/**
 * The boot-side end of NeoForge's HUD seam: where MinecraftForge's overlay stack goes onto NeoForge's
 * {@code GuiLayerManager}.
 *
 * <p>It once also rebuilt fabric-api's {@code HudElementRegistry} dispatch on NeoForge's layers, through fabric-api's
 * implementation classes: NeoForge won {@code Hud.extractRenderState} and turned the calls fabric-rendering-v1's
 * {@code HudMixin} wraps into method references, so its handlers bound nowhere. They bind now — each wrap lands in the
 * synthetic body the kernel gives a method reference to the call it names — and the bridge stood aside for every root
 * in every run it was measured in ("bridged 0 of 21"). It was retired with the names it read.
 */
public final class KernelHudBridge {
	private KernelHudBridge() {
	}

	/** Kept for {@code KernelBoot}'s bind sequence; the overlay hook needs no loader. */
	public static void bind(Object loader) {
	}

	/**
	 * The hook the transformed {@code GuiLayerManager.initModdedLayers} calls at its end.
	 *
	 * <p>Boot-side, so it cannot name {@code GuiLayerManager}; the work is game-side in
	 * {@code KernelForgeOverlayLayers}, which builds MinecraftForge's overlay stack and registers it here — the
	 * one moment where every mod is loaded and the HUD has not yet drawn a frame.
	 *
	 * <p>A failure costs only MinecraftForge's overlays, which is the state this replaces, so it never propagates
	 * into the game's own HUD initialisation.
	 */
	public static void addForgeOverlayLayers(Object layerManager) {
		if (layerManager == null) return;
		try {
			Class.forName("net.forbric.kernel.runtime.KernelForgeOverlayLayers", true,
					layerManager.getClass().getClassLoader())
					.getMethod("install", Object.class).invoke(null, layerManager);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/HudBridge] no MinecraftForge overlay adapter on this loader");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/HudBridge] could not install MinecraftForge's overlay layers — a mod's HUD "
					+ "overlay will render nothing", t);
		}
	}
}
