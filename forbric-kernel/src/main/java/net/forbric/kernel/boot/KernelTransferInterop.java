/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.CodeRoots;
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
	/** A hopper against Fabric storages NeoForge's cannot see; independent of the bridge switch, like Fabric's own mixin. */
	static final String HOPPER = "net.forbric.kernel.runtime.transfer.KernelFabricHopperStorage";
	/** Team Reborn Energy's public API, the one class every Fabric energy mod names. */
	static final String REBORN_API = "team/reborn/energy/api/EnergyStorage.class";
	private static volatile boolean active;
	private static volatile boolean energy;
	private static volatile boolean hopper;
	private static volatile boolean forgeFallback;
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
		// NeoForge's hopper body (ContainerOrHandler) is what fabric-transfer's hopper mixin cannot attach to; a carrier
		// without it runs Fabric's mixin as written.
		boolean hopperWanted = !"off".equalsIgnoreCase(System.getProperty("forbric.hopperFabricStorage", "on"))
				&& present(loader, "net/fabricmc/fabric/api/transfer/v1/item/ItemStorage.class")
				&& present(loader, "net/neoforged/neoforge/transfer/item/ContainerOrHandler.class");
		hopper = hopperWanted && present(loader, HOPPER.replace('.', '/') + ".class");
		if (hopperWanted && !hopper) {
			CompatibilityFindings.record(new CompatibilityFinding("transfer-hopper-component", "forbric",
					"Hoppers and Fabric item storages", "KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED,
					false, "This kernel build is missing its hopper component; hoppers ignore Fabric storages NeoForge cannot see.",
					List.of("fabric-transfer-api present", "kernel hopper runtime class absent")));
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
	/** Boot's decision whether the Forge block fallback is registered this run; the bridge looks for it only then. */
	public static void forgeFallback(boolean registered) { forgeFallback = registered; }
	/** Whether Team Reborn Energy is installed and its half of the bridge is required and will be installed. */
	public static boolean energyActive() { return energy; }
	/** Whether hoppers ask Fabric's item storage lookup where NeoForge's found nothing (HopperFabricStorageInjector). */
	public static boolean hopperActive() { return hopper; }
	static boolean ownsOptionalRuntime(String name) {
		return BRIDGE.equals(name) || ISSUES.equals(name) || TRANSACTIONS.equals(name) || ENERGY.equals(name) || HOPPER.equals(name);
	}
	/** For an optional runtime class: whether this boot needs it. */
	static boolean optionalRuntimeActive(String name) { return ENERGY.equals(name) ? energy : HOPPER.equals(name) ? hopper : active; }
	private static boolean present(ForbricClassLoader loader, String path) {
		try (var stream = loader.getGameResourceAsStream(path)) { return stream != null; }
		catch (java.io.IOException unreadable) {
			Caught.warn(unreadable, "[Forbric/Transfer] could not read game resource %s; treating it as absent", path);
			return false;
		}
	}

	/** After native providers have registered, before any world queries a foreign block inventory. */
	public static synchronized void install(ClassLoader loader) {
		if (!active || installed) return;
		try {
			Consumer<Object> reporter = issue -> recordIssue(loader, issue);
			Class.forName(ISSUES, true, loader).getMethod("setReporter", Consumer.class).invoke(null, reporter);
			Class.forName(BRIDGE, true, loader).getMethod("install", boolean.class).invoke(null, forgeFallback);
			installed = true;
			CompatibilityFindings.resolve("transfer-initialization", "forbric", "block transfer initialization completed");
			ForbricLog.info("[Forbric/Transfer] initialized cross-ecosystem block transfer after native capability registration");
		} catch (Throwable failure) {
			Throwable cause = net.forbric.kernel.util.Reflect.unwrap(failure);
			CompatibilityFindings.record(new CompatibilityFinding("transfer-initialization", "forbric",
					"Cross-ecosystem item and fluid transfer", "KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED,
					true, "The installed transfer APIs could not be connected; foreign storage is unavailable.", List.of(cause.toString())));
			Caught.error(failure, "[Forbric/Transfer] initialization failed; foreign storage will not be exposed");
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
			Caught.error(failure, "[Forbric/Transfer] Team Reborn Energy bridge failed; Fabric energy will not be exposed");
		}
	}

	/** Package-visible for a test; {@code issue} is a TransferIssues.Issue, read by its accessors across the loader boundary. */
	static void recordIssue(ClassLoader loader, Object issue) {
		try {
			String code = (String) issue.getClass().getMethod("code").invoke(issue);
			String provider = (String) issue.getClass().getMethod("providerClass").invoke(issue);
			String detail = (String) issue.getClass().getMethod("detail").invoke(issue);
			Class<?> source = (Class<?>) issue.getClass().getMethod("source").invoke(issue);
			// A finding with no provider object has no class to look up: it is the kernel's own, attributed to forbric
			// without asking a class loader for a class that does not exist.
			String shown = subject(provider, source);
			CompatibilityFindings.record(new CompatibilityFinding("transfer:" + code + ":" + shown,
					provider == null ? "forbric" : owner(loader, provider, source),
					"Cross-ecosystem storage", "transfer:" + shown, CompatibilityFinding.Confidence.CONFIRMED,
					false, detail, List.of(code, shown)));
			ForbricLog.warn("[Forbric/Transfer] %s: %s — %s", code, shown, detail);
		} catch (ReflectiveOperationException unreadable) {
			Caught.warn(unreadable, "[Forbric/Transfer] could not attribute transfer finding: %s", String.valueOf(issue));
		}
	}

	/**
	 * What a finding is about, as its id and its log line name it: the offered class, and when another class answers
	 * for it (a dynamic proxy's handler), that class too. A proxy class is shared by every handler behind the same
	 * interfaces in one class loader, so two mods' proxies carry one name; by that name alone their findings had one id.
	 */
	static String subject(String provider, Class<?> source) {
		if (provider == null) return "unknown";
		return source == null || source.getName().equals(provider) ? provider : provider + " via " + source.getName();
	}

	/**
	 * The mod whose jar holds {@code source} -- the class the runtime says answers for the provider -- or, when only a
	 * name was reported, the class that name resolves to. "forbric" when that class came from no mod's jar: a carrier or
	 * JDK class, or one generated at run time with no jar behind it (a dynamic proxy reported by name, a class defined
	 * with no protection domain). That is an answer, not a failure: there is no mod to name. Only a name that resolves
	 * to nothing is reported as a failure.
	 */
	static String owner(ClassLoader loader, String provider, Class<?> source) {
		Class<?> type = source;
		if (type == null) {
			try {
				type = Class.forName(provider, false, loader);
			} catch (ClassNotFoundException | LinkageError unknown) {
				Caught.warn(unknown, "[Forbric/Transfer] could not tell which mod ships %s; its finding is attributed to forbric",
						provider);
				return "forbric";
			}
		}
		Path root = CodeRoots.of(type);
		if (root == null || root.getFileName() == null) return "forbric";
		String file = root.getFileName().toString();
		return ModCatalog.everything().stream().filter(mod -> file.equals(mod.jar()))
				.map(ModCatalog.Entry::modId).findFirst().orElse("forbric");
	}
}
