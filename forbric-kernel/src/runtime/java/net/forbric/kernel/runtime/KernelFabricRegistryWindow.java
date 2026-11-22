/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.kernel.boot.FabricRegistryWindow;
import net.forbric.kernel.util.Caught;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The game side of {@link FabricRegistryWindow}: asked by {@code MappedRegistry.validateWrite(ResourceKey)} when the
 * registry being written is frozen ({@code FabricRegistryWindowInjector}), just before vanilla's "Registry is already
 * frozen".
 */
public final class KernelFabricRegistryWindow {
	private KernelFabricRegistryWindow() {
	}

	/**
	 * Whether the write may go on: true only for a built-in registry (the root or one registered in it) while Fabric's
	 * window is open, and then that registry has been reopened — as the kernel's own windows reopen it, keeping its bound
	 * tags — so the write lands in an open registry and the next freeze binds it like any other. False leaves vanilla's
	 * exception to be thrown.
	 */
	public static boolean admit(Object registry, Object key) {
		if (!FabricRegistryWindow.isOpen()) return false;
		try {
			if (!(registry instanceof MappedRegistry<?> frozen) || !builtIn(registry)) return false;
			frozen.unfreeze(false);
			FabricRegistryWindow.reopened(String.valueOf(key));
			return true;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/RegistrySync] could not reopen a frozen built-in registry for %s before Fabric's registry "
					+ "freeze point — vanilla's \"Registry is already frozen\" follows", key);
			return false;
		}
	}

	/**
	 * Asked by {@code MappedRegistry.freeze()} when it has a map of unregistered intrusive holders, before it throws "Some
	 * intrusive holders were not registered" for a non-empty one ({@code FabricRegistryWindowInjector}). True — skip the
	 * throw — only for a built-in registry with holders still listed while Fabric's window is open: a freeze before Fabric's
	 * registry freeze point, where a Fabric mod whose class something woke early has built a holder its entrypoint, not run
	 * yet, registers. The map is left as it is, so the freeze at Fabric's freeze point checks the same holders. False leaves
	 * vanilla's check to run.
	 */
	public static boolean carryIntrusiveHolders(Object registry, Object unregistered) {
		if (!FabricRegistryWindow.isOpen()) return false;
		try {
			if (!(unregistered instanceof java.util.Map<?, ?> holders) || holders.isEmpty()) return false;
			if (!(registry instanceof MappedRegistry<?> mapped) || !builtIn(registry)) return false;
			FabricRegistryWindow.carried(String.valueOf(mapped.key()), holders.size(), String.valueOf(holders.values()));
			return true;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/RegistrySync] could not carry a built-in registry's unregistered intrusive holders past a "
					+ "freeze before Fabric's registry freeze point — vanilla's check follows");
			return false;
		}
	}

	/** The root itself, or a registry in it; never a world's datapack registry. */
	private static boolean builtIn(Object registry) {
		if (registry == BuiltInRegistries.REGISTRY) return true;
		for (Object child : BuiltInRegistries.REGISTRY) {
			if (child == registry) return true;
		}
		return false;
	}
}
