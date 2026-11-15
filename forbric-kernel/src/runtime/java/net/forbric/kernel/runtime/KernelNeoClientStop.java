/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.io.File;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.util.NativeModuleLister;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.neoforge.client.loading.ClientModLoader;

/**
 * The client's end of a NeoForge mod-loading stop after the registry phase: what {@code ClientModLoader.finish} does with
 * the {@code ModLoadingException} its {@code load} (common setup, sided setup, registration events, IMC, load complete and
 * their deferred work) or its "Client network registry lock" task throws (javap of 26.2.0.93): a crash report for the
 * exception, stage "stage", with a "Finish mod loading" category carrying the native modules, filled by the game and saved
 * to its {@code crash-reports} folder, then {@code ClientModLoader.reportFatalError} — NeoForge's fatal-error window, which
 * exits. The kernel's {@code onNeoClientSetup} stands at that {@code finish} call inside {@code Minecraft.<init>}, so it
 * hands the stop here instead of letting it reach {@code Main}'s "Initializing game" handler, which natively never sees it.
 */
public final class KernelNeoClientStop {
	private KernelNeoClientStop() {
	}

	/** {@code ClientModLoader.finish}'s handler, statement for statement. Returns only if NeoForge's window did not exit. */
	public static void asClientModLoaderFinishDoes(Throwable stop) {
		ModLoadingException error = (ModLoadingException) stop;
		Minecraft minecraft = Minecraft.getInstance();
		File gameDirectory = minecraft.gameDirectory;
		CrashReport report = CrashReport.forThrowable(error, "stage");
		CrashReportCategory category = report.addCategory("Finish mod loading");
		NativeModuleLister.addCrashSection(category);
		minecraft.fillReport(report);
		Minecraft.saveReport(gameDirectory, report);
		ClientModLoader.reportFatalError(error, gameDirectory.toPath(), report);
	}
}
