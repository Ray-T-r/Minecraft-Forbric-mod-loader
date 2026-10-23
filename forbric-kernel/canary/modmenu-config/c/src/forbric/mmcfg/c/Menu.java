/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.mmcfg.c;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Canary C's Mod Menu entrypoint: its own settings screen. */
public final class Menu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CanaryCScreen::new;
	}
}
