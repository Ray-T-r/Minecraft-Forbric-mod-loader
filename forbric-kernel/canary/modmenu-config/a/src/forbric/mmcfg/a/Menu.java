/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.mmcfg.a;

import java.util.Map;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Canary A's Mod Menu entrypoint: its own screen, and -- the way a config library does for the mods built on it -- a
 * screen for canary E, which declares no entrypoint of its own.
 */
public final class Menu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CanaryAScreen::new;
	}

	@Override
	public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
		return Map.of("forbricmmcfge", CanaryEScreen::new);
	}
}
