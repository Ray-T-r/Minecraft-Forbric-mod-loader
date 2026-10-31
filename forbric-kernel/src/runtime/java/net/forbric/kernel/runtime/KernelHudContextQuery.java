/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import net.forbric.kernel.interop.HudContextCallbackScope;
import net.minecraft.client.gui.Hud;

public final class KernelHudContextQuery {
	private KernelHudContextQuery() { }

	/** The open callbacks' answer, composed; the native selection runs on the HUD the innermost one passes on. */
	public static Object next(Hud hud) {
		return HudContextCallbackScope.compose(hud, passed -> Native.next((Hud) passed));
	}

	/**
	 * Vanilla's {@code Hud.nextContextualInfoState()}, asked through a private lookup: NeoForge keeps it private (only
	 * MinecraftForge's access transformer made it public), and the kernel's game side has the access a class of its own
	 * loader needs. Resolved by name with no parameters, so its return type, a type of {@code Hud}'s own, is never named.
	 */
	private static final class Native {
		private static final MethodHandle NEXT = resolve();

		private static MethodHandle resolve() {
			try {
				return MethodHandles.privateLookupIn(Hud.class, MethodHandles.lookup())
						.unreflect(Hud.class.getDeclaredMethod("nextContextualInfoState"));
			} catch (ReflectiveOperationException drift) {
				throw new IllegalStateException("Hud.nextContextualInfoState() is not there to ask", drift);
			}
		}

		static Object next(Hud hud) {
			try {
				return NEXT.invoke(hud);
			} catch (RuntimeException | Error thrown) {
				throw thrown;
			} catch (Throwable checked) {
				throw new IllegalStateException(checked);
			}
		}
	}
}
