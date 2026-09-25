/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge's {@code ScreenEvent.Opening} and {@code Closing}, posted from the merged {@code Gui.setScreen}.
 *
 * <p>The merge kept MinecraftForge's {@code setScreen}, which asks MinecraftForge's Opening and Closing and never
 * NeoForge's, so a NeoForge mod that swaps or refuses a screen did nothing: Controlling never replaced the key binds
 * screen, JEI never learned a screen opened, Balm and PuzzlesLib never saw one change. NeoScreenEventsInjector calls in
 * right after MinecraftForge's own hooks. MinecraftForge is asked first and its refusal is final; NeoForge then sees
 * MinecraftForge's choice as the new screen and decides last — a cancel, a replacement, or null to close.
 *
 * <p>The replacement is carried from the post to the assignment through one field: {@code setScreen} runs on the
 * render thread, and a listener that opens another screen re-enters and finishes inside the post, before it is set.
 */
public final class KernelScreenEvents {
	private static final Object NOTHING = new Object();
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static Object replacement = NOTHING;

	private KernelScreenEvents() {
	}

	/** After MinecraftForge's Opening: null refuses the screen, anything else goes on (NeoForge's pick is kept aside). */
	public static Screen neoForgeOpening(Screen forgeChoice, Screen current) {
		if (forgeChoice == null) return null;
		replacement = NOTHING;
		try {
			ScreenEvent.Opening event = new ScreenEvent.Opening(current, forgeChoice);
			NeoForge.EVENT_BUS.post(event);
			if (event.isCanceled()) return null;
			if (event.getNewScreen() != forgeChoice) replacement = event.getNewScreen();
		} catch (Throwable t) {
			warn("ScreenEvent.Opening", t);
		}
		return forgeChoice;
	}

	/** The screen {@code setScreen} goes on with: NeoForge's replacement (null closes), or the one it had. */
	public static Screen takeNeoForgeNewScreen(Screen screen) {
		Object picked = replacement;
		replacement = NOTHING;
		return picked == NOTHING ? screen : (Screen) picked;
	}

	/** After MinecraftForge's Closing, for the same screen. */
	public static void postNeoForgeClosing(Screen closing) {
		try {
			NeoForge.EVENT_BUS.post(new ScreenEvent.Closing(closing));
		} catch (Throwable t) {
			warn("ScreenEvent.Closing", t);
		}
	}

	private static void warn(String event, Throwable t) {
		if (WARNED.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Screens] NeoForge's " + event + " failed — the screen change went on as "
					+ "MinecraftForge decided", t);
		}
	}
}
