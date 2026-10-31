/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A finished conversion on the paths the merge gave MinecraftForge's lambdas: NeoForge told, once.
 *
 * <p>The merged {@code Zombie} converts through MinecraftForge's lambdas — identical to NeoForge's except that they post
 * MinecraftForge's {@code LivingConversionEvent.Post} — so NeoForge's was never posted on drowning, a husk's
 * conversion or a villager's zombification, and whatever NeoForge mods carry over on it (data attachments among them)
 * was lost. NeoConversionPostInjector sends those calls here, and NeoForge's event is posted. Forbric loads no
 * MinecraftForge mod, so nobody is left to hear MinecraftForge's.
 */
public final class KernelConversions {
	private KernelConversions() {
	}

	public static void onLivingConvert(LivingEntity from, LivingEntity to) {
		EventHooks.onLivingConvert(from, to);
	}
}
