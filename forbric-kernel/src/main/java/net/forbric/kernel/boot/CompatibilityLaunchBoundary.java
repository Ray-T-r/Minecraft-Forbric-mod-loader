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

	private static int stopped() {
		System.err.println("[Forbric/Compatibility] launch stopped by compatibility policy; see .forbric-kernel/compatibility-report.json");
		return POLICY_STOP;
	}
}
