/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transfer;

import net.fabricmc.api.ModInitializer;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * A Fabric canary for fabric-api's server watchdog mixin on the NeoForge base: the final watchdog renderer the kernel keeps
 * must dump the whole thread, not fabric-api's truncated one, and the strict report must call that mixin RESOLVED.
 *
 * <p>Nothing in it is MinecraftForge. It ran as the last scenario of the deleted MinecraftForge transfer canary, which was
 * its only runner (gate-m39); it is its own jar now so the Forge storage scenarios could go without it. Pack this class and
 * {@code src/transferTest/resources/watchdog-canary.fabric.mod.json} (as {@code fabric.mod.json}) beside fabric-api and run
 * on a real kernel server; it prints {@code [Forbric/WatchdogCanary] 1/1 passed} once the server has started. It only
 * constructs diagnostic text: it never invokes the watchdog's run or exit and writes no crash report.
 */
public final class WatchdogCanary implements ModInitializer {
	static final int DEPTH = 35;

	@Override public void onInitialize() {
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> runAll());
	}

	public static void runAll() {
		String name = "final native watchdog dump replaces Fabric truncation patch";
		int passed = 0;
		try {
			deepWatchdog(DEPTH);
			passed++;
			System.out.println("[Forbric/WatchdogCanary] PASS " + name);
		} catch (Throwable failure) {
			System.out.println("[Forbric/WatchdogCanary] FAIL " + name + ": " + failure);
			failure.printStackTrace();
		}
		System.out.println("[Forbric/WatchdogCanary] " + passed + "/1 passed");
	}

	private static void deepWatchdog(int depth) {
		if (depth > 0) { deepWatchdog(depth - 1); return; }
		var report = net.minecraft.server.dedicated.ServerWatchdog.createWatchdogCrashReport("Forbric diagnostic proof",
				Thread.currentThread().threadId());
		String text = report.getFriendlyReport(net.minecraft.ReportType.TEST);
		int dump = text.indexOf("-- Thread Dump --");
		yes(dump >= 0, "the report has a thread dump");
		yes(text.substring(dump).split("deepWatchdog", -1).length > DEPTH, "the thread dump keeps every frame");
		yes(CompatibilityFindings.all().stream().anyMatch(f -> f.id().contains("ServerWatchdogMixin#printEntireThreadDump")
				&& f.confidence() == CompatibilityFinding.Confidence.RESOLVED), "fabric-api's watchdog mixin is RESOLVED");
	}

	private static void yes(boolean value, String what) { if (!value) throw new AssertionError(what); }
}
