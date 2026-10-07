/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.minecraft.client.gui.Hud;import net.forbric.kernel.interop.HudContextCallbackScope;

public final class KernelHudContextQuery {
	private KernelHudContextQuery() { }
	public static Object next(Hud hud){return HudContextCallbackScope.query(hud,()->hud.nextContextualInfoState());}
}
