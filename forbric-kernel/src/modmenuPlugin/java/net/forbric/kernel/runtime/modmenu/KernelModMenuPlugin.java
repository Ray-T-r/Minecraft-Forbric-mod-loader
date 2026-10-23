/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime.modmenu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.forbric.kernel.runtime.KernelModConfigScreens;
import net.forbric.kernel.util.Caught;
import net.minecraft.client.gui.screens.Screen;

/**
 * The kernel's own {@code "modmenu"} entrypoint, declared by the {@code fabricloader} built-in on a client.
 *
 * <h2>The failure it prevents</h2>
 *
 * <p>Mod Menu lists every mod in Fabric's registry, and on a Forbric instance that includes the mods another family
 * loads: the kernel registers each under its id for presence, so a Fabric mod's {@code isModLoaded} tells the truth.
 * Mod Menu finds a config screen in exactly one place, a {@code "modmenu"} entrypoint. A universal jar arbitrated to
 * NeoForge declared one in its Fabric half, and that half is not what runs; a NeoForge or MinecraftForge mod never had
 * one. So with Mod Menu installed every one of them was a row with no Config button -- Collective, Controlify and
 * Double Doors among them, all of which open their settings from the kernel's own Mods screen and from Mod Menu on a
 * Fabric instance.
 *
 * <p>This offers Mod Menu, under each such id, the screen the mod's own family has for it: the factories come from
 * {@link KernelModConfigScreens#foreignScreensForFabric}, which decides by shape (another family loaded the id, Fabric
 * holds it, that family has a screen) and asks nothing but that family's registry.
 *
 * <h2>Only for a real Mod Menu</h2>
 *
 * <p>Without Mod Menu the kernel's stand-in API reads this entrypoint too; there it offers nothing, because the
 * kernel's own Mods screen already opens those screens from their own family and nothing else reads what is offered
 * here. Whether Mod Menu is installed is the same question {@code ModMenuProtocolExtension} asks: whether its main
 * class is there.
 *
 * <p>{@code -Dforbric.modMenuForeignScreens=off} offers nothing: Mod Menu's list goes back to showing those mods with
 * no Config button, which is gate-m56's negative control for this.
 *
 * <h2>Cost</h2>
 *
 * <p>Mod Menu asks every entrypoint for its provided factories on every lookup, and a lookup can be per frame. The
 * answer is rebuilt at most once every {@link #REFRESH_NANOS} and handed back unchanged in between; Mod Menu keeps
 * what it was given with {@code putIfAbsent}, so a screen registered late is picked up within that interval.
 */
public final class KernelModMenuPlugin implements ModMenuApi {
	/** How long one reading of the families' registries is handed back before they are asked again. */
	static final long REFRESH_NANOS = 2_000_000_000L;
	/** {@code off}: offer Mod Menu nothing. */
	public static final String SWITCH = "forbric.modMenuForeignScreens";
	private static final String MOD_MENU = "com.terraformersmc.modmenu.ModMenu";

	private Map<String, ConfigScreenFactory<?>> offered = Map.of();
	private long readAt;
	private boolean read;
	private Boolean modMenu;

	@Override
	public synchronized Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
		if (!modMenuInstalled() || "off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) return Map.of();
		long now = System.nanoTime();
		if (read && now - readAt < REFRESH_NANOS) return offered;
		read = true;
		readAt = now;
		Map<String, ConfigScreenFactory<?>> fresh = new LinkedHashMap<>();
		try {
			for (Map.Entry<String, Function<Screen, Screen>> screen : KernelModConfigScreens.foreignScreensForFabric().entrySet()) {
				Function<Screen, Screen> open = screen.getValue();
				fresh.put(screen.getKey(), parent -> open.apply(parent));
			}
		} catch (RuntimeException | LinkageError unreadable) {
			Caught.warn(unreadable, "[Forbric/ModConfig] could not read which mods another family loads have a config screen; "
					+ "Mod Menu shows them without a Config button");
		}
		offered = Map.copyOf(fresh);
		return offered;
	}

	private boolean modMenuInstalled() {
		if (modMenu == null) {
			boolean found;
			try {
				Class.forName(MOD_MENU, false, KernelModMenuPlugin.class.getClassLoader());
				found = true;
			} catch (ClassNotFoundException | LinkageError absent) {
				Caught.expected(absent);
				found = false;
			}
			modMenu = found;
		}
		return modMenu;
	}
}
