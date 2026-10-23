/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/** COMPILE-ONLY: Mod Menu 20.0.3's {@code ConfigScreenFactory}; see {@link ModMenuApi}. */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
	S create(Screen parent);
}
