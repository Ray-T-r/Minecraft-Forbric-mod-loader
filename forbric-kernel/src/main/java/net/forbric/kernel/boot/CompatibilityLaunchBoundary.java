/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.InvocationTargetException;

import net.forbric.kernel.ui.CompatibilityDecision;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Only the real launcher main methods turn an explicit compatibility refusal -- or a launch whose jars cannot run --
 * into a process exit. Anything else that leaves the boot is a crash, and it is put in {@code latest.log} on its way
 * out.
 */
final class CompatibilityLaunchBoundary {
	static final int POLICY_STOP = 78;
	/** {@link LaunchInputCheck} refused the jars; the same code as a launch given no {@code --gameJar} at all. */
	static final int INPUTS_REJECTED = 2;
	@FunctionalInterface interface Launch { void run() throws Throwable; }
	private CompatibilityLaunchBoundary() { }

	static int run(Launch launch) throws Throwable {
		try {
			launch.run();
		} catch (Throwable failure) {
			if (failure instanceof LaunchInputCheck.Rejected) return rejected();
			if (!CompatibilityDecision.isLaunchStop(failure)) {
				reportEscaping(failure);
				throw failure;
			}
			Caught.expected(failure);
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
			Caught.warn(unavailable, "[Forbric/Compatibility] could not create the game's SilentInitException; the "
					+ "compatibility stop leaves the client as itself and may be reported as a crash");
			stop.addSuppressed(unavailable);
			return stop;
		}
	}

	private static int stopped() {
		System.err.println("[Forbric/Compatibility] launch stopped by compatibility policy; see .forbric-kernel/compatibility-report.json");
		return POLICY_STOP;
	}

	private static int rejected() {
		System.err.println("[Forbric/Install] launch stopped: the Forbric install is broken; the reason and the fix are in logs/latest.log");
		return INPUTS_REJECTED;
	}

	/**
	 * Logs a failure that is about to leave the launcher's main method, before it does.
	 *
	 * <p>Thrown out of {@code main}, it reaches only the JVM's default handler, which prints to stderr. Launchers do
	 * not show stderr; they show {@code logs/latest.log}, which is log4j's file, and that is the one a player attaches
	 * to a report. Issue #13's was five INFO lines for exactly this reason: the {@code NoClassDefFoundError} that ended
	 * the boot never reached it. The game's own crash handling does not cover this either -- this is the kernel's boot,
	 * before or around {@code Main.main}, not inside it. The one handler inside it that prints to stderr only, the
	 * client {@code Main}'s {@code logEarlyException}, reaches here through {@link KernelLifecycle#onEarlyStartupFailure}.
	 *
	 * <p>Logging must not replace the failure: whatever happens here, the caller rethrows the original.
	 */
	static void reportEscaping(Throwable failure) {
		try {
			Throwable shown = failure instanceof InvocationTargetException reflected
					&& reflected.getTargetException() != null ? reflected.getTargetException() : failure;
			// The (String, Throwable) overload: the message is not a format, and the exception's text may hold a '%'.
			ForbricLog.error("[Forbric/Boot] the game stopped during startup on " + shown + " — this is the error that "
					+ "ended it; the full trace follows. When reporting it, attach this whole log", failure);
		} catch (Throwable unloggable) {
			Caught.expected(unloggable);
			failure.addSuppressed(unloggable);
		}
	}
}
