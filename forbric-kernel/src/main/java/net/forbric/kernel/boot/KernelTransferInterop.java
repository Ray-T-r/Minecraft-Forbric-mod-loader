/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Boot/game seam for the optional transfer API. No game or Fabric API implementation type crosses it.
 *
 * <p>Energy rides on the same component: Forge and NeoForge energy bridge each other whenever the transfer bridge is
 * active. The Fabric side of energy is Team Reborn Energy, an ordinary mod; only when its API is installed is the
 * Reborn half (RebornEnergyBridge) required and installed. Presence is read as a RESOURCE, so a pack without it never
 * loads a Reborn class, and no seam class names a Reborn type.
 */
public final class KernelTransferInterop {
	static final String BRIDGE = "net.forbric.kernel.runtime.transfer.BlockTransferBridge";
	static final String ISSUES = "net.forbric.kernel.runtime.transfer.TransferIssues";
	static final String TRANSACTIONS = "net.forbric.kernel.runtime.transfer.PairedTransactions";
	static final String ENERGY = "net.forbric.kernel.runtime.transfer.RebornEnergyBridge";
	/** Team Reborn Energy's public API, the one class every Fabric energy mod names. */
	static final String REBORN_API = "team/reborn/energy/api/EnergyStorage.class";
	private static volatile boolean active;
	private static volatile boolean energy;
	private static boolean installed;
	private KernelTransferInterop() { }

	public static synchronized boolean configure(ForbricClassLoader loader) {
		installed = false;
		boolean requested = !"off".equalsIgnoreCase(System.getProperty("forbric.transferBridge", "on"))
				&& present(loader, "net/fabricmc/fabric/api/transfer/v1/storage/Storage.class")
				&& present(loader, "net/neoforged/neoforge/transfer/ResourceHandler.class");
		active = requested && present(loader, BRIDGE.replace('.', '/') + ".class")
				&& present(loader, ISSUES.replace('.', '/') + ".class")
				&& present(loader, TRANSACTIONS.replace('.', '/') + ".class");
		if (requested && !active) {
			CompatibilityFindings.record(new CompatibilityFinding("transfer-component", "forbric",
					"Cross-ecosystem item and fluid transfer", "KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED,
					true, "This kernel build is missing its transfer component; native transactions remain unchanged.",
					List.of("transfer APIs present", "kernel transfer runtime classes absent")));
		}
		boolean reborn = active && present(loader, REBORN_API);
		energy = reborn && present(loader, ENERGY.replace('.', '/') + ".class");
		if (reborn && !energy) {
			// Not a necessary loss: without the Reborn half, Fabric energy mods behave exactly as they would without
			// the bridge, and Forge/NeoForge energy still bridge each other.
			CompatibilityFindings.record(new CompatibilityFinding("transfer-energy-component", "forbric",
					"Cross-ecosystem energy (Team Reborn Energy)", "KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED,
					false, "This kernel build is missing its Team Reborn Energy bridge; Fabric energy stays unconnected.",
					List.of("team_reborn_energy present", "kernel energy runtime class absent")));
		}
		return active;
	}
	public static boolean active() { return active; }
	/** Whether Team Reborn Energy is installed and its half of the bridge is required and will be installed. */
	public static boolean energyActive() { return energy; }
	static boolean ownsOptionalRuntime(String name) {
		return BRIDGE.equals(name) || ISSUES.equals(name) || TRANSACTIONS.equals(name) || ENERGY.equals(name);
	}
	/** For an optional runtime class: whether this boot needs it. */
	static boolean optionalRuntimeActive(String name) { return ENERGY.equals(name) ? energy : active; }
	private static boolean present(ForbricClassLoader loader, String path) {
		try (var stream = loader.getGameResourceAsStream(path)) { return stream != null; }
		catch (java.io.IOException unreadable) { return false; }
	}

	/** After native providers have registered, before any world queries a foreign block inventory. */
	public static synchronized void install(ClassLoader loader) {
		if (!active || installed) return;
		try {
			Consumer<Object> reporter = issue -> recordIssue(loader, issue);
			Class.forName(ISSUES, true, loader).getMethod("setReporter", Consumer.class).invoke(null, reporter);
			Class.forName(BRIDGE, true, loader).getMethod("install").invoke(null);
			installed = true;
			CompatibilityFindings.resolve("transfer-initialization", "forbric", "block transfer initialization completed");
			ForbricLog.info("[Forbric/Transfer] initialized cross-ecosystem block transfer after native capability registration");
		} catch (Throwable failure) {
			Throwable cause = net.forbric.kernel.util.Reflect.unwrap(failure);
			CompatibilityFindings.record(new CompatibilityFinding("transfer-initialization", "forbric",
					"Cross-ecosystem item and fluid transfer", "KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED,
					true, "The installed transfer APIs could not be connected; foreign storage is unavailable.", List.of(cause.toString())));
			ForbricLog.error("[Forbric/Transfer] initialization failed; foreign storage will not be exposed", cause);
			return;
		}
		if (!energy) return;
		try {
			Class.forName(ENERGY, true, loader).getMethod("install").invoke(null);
			CompatibilityFindings.resolve("transfer-energy-initialization", "forbric", "Team Reborn Energy bridge initialized");
			ForbricLog.info("[Forbric/Transfer] connected Team Reborn Energy to Forge and NeoForge block energy");
		} catch (Throwable failure) {
			Throwable cause = net.forbric.kernel.util.Reflect.unwrap(failure);
			CompatibilityFindings.record(new CompatibilityFinding("transfer-energy-initialization", "forbric",
					"Cross-ecosystem energy (Team Reborn Energy)", "KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED,
					false, "Team Reborn Energy could not be connected; Fabric energy stays unconnected, Forge/NeoForge energy is unaffected.",
					List.of(cause.toString())));
			ForbricLog.error("[Forbric/Transfer] Team Reborn Energy bridge failed; Fabric energy will not be exposed", cause);
		}
	}

	private static void recordIssue(ClassLoader loader, Object issue) {
		try {
			String code = (String) issue.getClass().getMethod("code").invoke(issue);
			String provider = (String) issue.getClass().getMethod("providerClass").invoke(issue);
			String detail = (String) issue.getClass().getMethod("detail").invoke(issue);
			CompatibilityFindings.record(new CompatibilityFinding("transfer:" + code + ":" + provider, owner(loader, provider),
					"Cross-ecosystem storage", "transfer:" + provider, CompatibilityFinding.Confidence.CONFIRMED,
					false, detail, List.of(code, provider)));
			ForbricLog.warn("[Forbric/Transfer] %s: %s — %s", code, provider, detail);
		} catch (ReflectiveOperationException unreadable) {
			ForbricLog.warn("[Forbric/Transfer] could not attribute transfer finding: %s", String.valueOf(issue));
		}
	}

	private static String owner(ClassLoader loader, String provider) {
		try {
			var source = Class.forName(provider, false, loader).getProtectionDomain().getCodeSource();
			String file = Path.of(source.getLocation().toURI()).getFileName().toString();
			return ModCatalog.everything().stream().filter(mod -> file.equals(mod.jar()))
					.map(ModCatalog.Entry::modId).findFirst().orElse("forbric");
		} catch (Exception | LinkageError unknown) { return "forbric"; }
	}
}
