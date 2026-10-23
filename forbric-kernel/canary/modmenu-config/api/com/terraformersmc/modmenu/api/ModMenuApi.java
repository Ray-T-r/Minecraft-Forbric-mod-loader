/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package com.terraformersmc.modmenu.api;

import java.util.Map;

/**
 * COMPILE-ONLY: the two members of Mod Menu 20.0.3's {@code ModMenuApi} the canaries implement, with Mod Menu's own
 * descriptors, so the canaries link against whatever {@code ModMenuApi} the game defines -- a real Mod Menu's or
 * Forbric's stand-in -- exactly as a Fabric mod compiled against Mod Menu does. Never packed into a canary jar.
 */
public interface ModMenuApi {
	default ConfigScreenFactory<?> getModConfigScreenFactory() {
		return null;
	}

	default Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
		return Map.of();
	}
}
