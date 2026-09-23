/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import net.forbric.kernel.ui.CompatibilityDecision;

/** Only the real launcher main methods turn an explicit compatibility refusal into a process exit. */
final class CompatibilityLaunchBoundary {
	static final int POLICY_STOP = 78;
	@FunctionalInterface interface Launch { void run() throws Throwable; }
	private CompatibilityLaunchBoundary() { }

	static int run(Launch launch) throws Throwable {
		try {
			launch.run();
		} catch (Throwable failure) {
			if (!CompatibilityDecision.isLaunchStop(failure)) throw failure;
			return stopped();
		}
		// A game main can catch the policy exception itself. Once it returns, retain the non-success result;
		// this does not pretend to stop/clean up a game that swallowed the refusal and is still running.
		return CompatibilityDecision.launchStopRequested() ? stopped() : 0;
	}

	/**
	 * The form a refusal must take to leave {@code Minecraft.<init>} without being reported as a crash.
	 *
	 * <p>{@code net.minecraft.client.main.SilentInitException}, created through the game loader because that is
	 * the class {@code Main.main}'s handler names, with the typed stop as its cause so {@link
	 * CompatibilityDecision#isLaunchStop} still recognises it anywhere else. Should the class ever be missing, the
	 * stop itself is returned: a crash report for a deliberate refusal is wrong, but a refusal that let the game
	 * continue would be worse.
	 */
	static RuntimeException insideClientMain(ClassLoader game, CompatibilityDecision.LaunchStopped stop) {
		try {
			Class<?> silent = Class.forName("net.minecraft.client.main.SilentInitException", false, game);
			return (RuntimeException) silent.getConstructor(String.class, Throwable.class).newInstance(stop.getMessage(), stop);
		} catch (ReflectiveOperationException | ClassCastException | LinkageError unavailable) {
			stop.addSuppressed(unavailable);
			return stop;
		}
	}

	private static int stopped() {
		System.err.println("[Forbric/Compatibility] launch stopped by compatibility policy; see .forbric-kernel/compatibility-report.json");
		return POLICY_STOP;
	}
}
