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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.neoforged.neoforge.client.gui.GuiLayerManager;

/**
 * Gives MinecraftForge's HUD overlay layers somewhere to draw.
 *
 * <p>The merged base carries ZERO references to {@code ForgeLayeredDraw} — NeoForge's {@code GuiLayerManager}
 * won that byte merge outright, so MinecraftForge's entire overlay system is inert: nothing builds the layer
 * tree, nothing posts {@code AddGuiOverlayLayersEvent}, and a mod that adds a HUD overlay registers it into an
 * object the game never renders. Waila's overlay is the one that showed it; every mod using that API had it.
 *
 * <p>The tree is built EMPTY rather than through {@code ForgeLayeredDraw.init}. Init builds MinecraftForge's own
 * copy of the vanilla HUD, and the merged base already draws the vanilla HUD through NeoForge's manager — so
 * rendering that tree would draw every vanilla element a second time. Instead the root is seeded with a no-op
 * under each of MinecraftForge's vanilla layer NAMES, which is what a mod positions against: a mod asking to sit
 * above the crosshair finds a crosshair to sit above, the ordering among the mod layers comes out right, and
 * nothing vanilla is drawn twice.
 *
 * <p><b>The accepted gap, stated rather than hidden:</b> the whole MinecraftForge stack is added as ONE NeoForge
 * layer, appended last, so it draws ABOVE the entire vanilla HUD. A mod that asked to sit BELOW a vanilla element
 * still draws above it. Overlays are the layers mods add on top, so this is right for almost all of them, and
 * "drawn slightly too high in the stack" is a different kind of problem from "not drawn at all".
 *
 * <p>{@code -Dforbric.forgeOverlayLayers=off} restores the old behaviour, which is that nothing draws.
 */
public final class KernelForgeOverlayLayers {
	static final String PROPERTY = "forbric.forgeOverlayLayers";

	/** The name the whole MinecraftForge stack is registered under in NeoForge's manager. */
	static final Identifier LAYER = Identifier.fromNamespaceAndPath("forbric", "minecraftforge_overlays");

	private static final ForgeLayer NOTHING = (extractor, delta) -> { };

	private KernelForgeOverlayLayers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Called at the end of {@code GuiLayerManager.initModdedLayers}, where NeoForge has just posted its own
	 * registration event — the one moment where every mod is loaded and the HUD has not yet drawn a frame.
	 *
	 * @param layerManager the live {@code GuiLayerManager}, pushed as {@code this} by the injector
	 */
	public static void install(Object layerManager) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/HudBridge] -D%s=off — a MinecraftForge mod's HUD overlay layers will not draw",
					PROPERTY);
			return;
		}
		try {
			ForgeLayeredDraw root = new ForgeLayeredDraw(ForgeLayeredDraw.VANILLA_ROOT);
			List<Identifier> vanilla = vanillaLayerNames();
			for (Identifier name : vanilla) root.add(name, NOTHING);

			// Posts AddGuiOverlayLayersEvent, then bakes the order the mods asked for.
			root.resolveLayers();

			((GuiLayerManager) layerManager).add(LAYER, root::extract);
			EventBridges.installed(GameEventBridge.GUI_OVERLAY_LAYERS);

			int added = modLayerCount(root, vanilla.size());
			if (added < 0) {
				ForbricLog.info("[Forbric/HudBridge] MinecraftForge's overlay stack is on NeoForge's layer manager "
						+ "(%d vanilla position(s) seeded)", vanilla.size());
			} else {
				ForbricLog.info("[Forbric/HudBridge] MinecraftForge's overlay stack is on NeoForge's layer manager — "
						+ "%d layer(s) added by mods over %d seeded vanilla position(s)", added, vanilla.size());
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/HudBridge] could not give MinecraftForge's overlay layers a place to draw — "
					+ "a mod's HUD overlay will render nothing", Reflect.unwrap(t));
		}
		EventBridges.verify(GameEventBridge.Pass.CLIENT_HUD);
	}

	/**
	 * MinecraftForge's own names for the vanilla HUD positions, read off its class rather than listed here.
	 *
	 * <p>Listing them would be a second copy to keep in step, and the failure of a stale copy is silent: a mod
	 * positioning against the name this list forgot gets MinecraftForge's "layer not present" warning and lands
	 * wherever it was added.
	 */
	static List<Identifier> vanillaLayerNames() {
		List<Identifier> names = new ArrayList<>();
		for (Field field : ForgeLayeredDraw.class.getFields()) {
			if (field.getType() != Identifier.class) continue;
			try {
				// The root's own name is the tree, not a position inside it.
				if (field.get(null) instanceof Identifier id && !id.equals(ForgeLayeredDraw.VANILLA_ROOT)) {
					names.add(id);
				}
			} catch (IllegalAccessException unreadable) {
				ForbricLog.debug("[Forbric/HudBridge] could not read %s: %s", field.getName(), unreadable);
			}
		}
		return names;
	}

	/**
	 * How many layers the mods added, or -1 when it cannot be counted.
	 *
	 * <p>{@code ForgeLayeredDraw} has no accessor for its contents, and the number is worth a private field read:
	 * it is the difference between "the stack is installed" and "the stack is installed and someone is in it",
	 * which is the only thing that says whether this bridge is doing anything on a given pack.
	 */
	private static int modLayerCount(ForgeLayeredDraw root, int seeded) {
		try {
			Field named = ForgeLayeredDraw.class.getDeclaredField("namedLayers");
			named.setAccessible(true);
			return named.get(root) instanceof Map<?, ?> layers ? Math.max(0, layers.size() - seeded) : -1;
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/HudBridge] could not count MinecraftForge's overlay layers: %s",
					String.valueOf(Reflect.unwrap(t)));
			return -1;
		}
	}
}
