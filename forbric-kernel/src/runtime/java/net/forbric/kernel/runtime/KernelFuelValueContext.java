/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.fabricmc.fabric.api.registry.FuelValueEvents;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * The context {@link KernelFabricFuel} hands fabric-content-registries' fuel events: a value of the public
 * {@code FuelValueEvents.Context}, as the context Fabric's own wrap in {@code vanillaBurnTimes} hands them is.
 *
 * <p>It used to be a {@code java.lang.reflect.Proxy} over the interface. Nothing a listener did through the interface
 * differed, but the object did: its class was {@code $ProxyNNN}, it was equal only to itself where Fabric's context is
 * equal to any context of the same registries, features and smelt time, and an interface method added to the API later
 * — a default one included — would have reached the proxy's handler and thrown {@code UnsupportedOperationException}
 * instead of running. A record implementing the interface answers all of that the way the API's own implementation does,
 * without naming that implementation: the class is the kernel's, the contract is fabric-api's public one.
 *
 * <p>Only {@link #of} is called, and only once fabric-content-registries resolved: this class names
 * {@code FuelValueEvents.Context} as its superinterface, so it can be loaded only where fabric-api is installed.
 */
public record KernelFuelValueContext(HolderLookup.Provider registries, FeatureFlagSet enabledFeatures, int baseSmeltTime)
		implements FuelValueEvents.Context {
	/** The context for one dispatch, typed {@code Object} so the caller's own linkage never names fabric-api. */
	static Object of(HolderLookup.Provider registries, FeatureFlagSet features, int baseSmeltTime) {
		return new KernelFuelValueContext(registries, features, baseSmeltTime);
	}
}
