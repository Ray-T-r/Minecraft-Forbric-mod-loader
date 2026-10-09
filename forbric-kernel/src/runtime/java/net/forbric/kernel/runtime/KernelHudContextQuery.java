/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.minecraft.client.gui.Hud;import net.forbric.kernel.interop.HudContextCallbackScope;

public final class KernelHudContextQuery {
	private KernelHudContextQuery() { }
	/** The open callbacks' answer, composed; the native selection runs on the HUD the innermost one passes on. */
	public static Object next(Hud hud){return HudContextCallbackScope.compose(hud,passed->((Hud)passed).nextContextualInfoState());}
}
