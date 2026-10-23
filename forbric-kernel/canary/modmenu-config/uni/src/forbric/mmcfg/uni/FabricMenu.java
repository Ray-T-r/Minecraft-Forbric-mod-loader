/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.mmcfg.uni;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** The universal canary's Fabric half: its Mod Menu entrypoint. Never runs on Forbric, where NeoForge wins the jar. */
public final class FabricMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return UniFabricScreen::new;
	}
}
