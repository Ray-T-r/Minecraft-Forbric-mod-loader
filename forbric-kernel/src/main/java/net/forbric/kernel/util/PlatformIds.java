/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.util.Locale;
import java.util.Set;

/**
 * The mod ids the platform itself answers for: what the kernel registers or supplies, and what a carrier declares —
 * never an id only some installed mod provides.
 *
 * <ul>
 *   <li>{@code minecraft}, {@code java}, {@code fabricloader}: the builtin mods the kernel registers for the Fabric
 *       family ({@code KernelFabricEcosystem.build});</li>
 *   <li>{@code mixinextras}: the MixinExtras the kernel bundles and initialises for every family;</li>
 *   <li>{@code forge}, {@code neoforge}: the ecosystem ids the runtime carriers declare in their own mods.toml, and
 *       {@code fml}, their loader's.</li>
 * </ul>
 *
 * <p>{@code fabric} is not one of them. It was fabric-api's own {@code provides} alias in older releases; neither Fabric
 * Loader nor the kernel provides it, and a current fabric-api does not either. Listing it here made a requirement on it
 * read as met by the platform whether or not a mod providing it was installed — a nested mod depending on it was never
 * left out, and an unmet one was never reported, where native Fabric Loader refuses the pack.
 */
public final class PlatformIds {
	private static final Set<String> IDS = Set.of("minecraft", "java", "fabricloader", "mixinextras", "forge", "neoforge", "fml");

	private PlatformIds() {
	}

	/** Whether {@code id} (any case) names the platform rather than a mod that may or may not be installed. */
	public static boolean provides(String id) {
		return id != null && IDS.contains(id.toLowerCase(Locale.ROOT));
	}
}
