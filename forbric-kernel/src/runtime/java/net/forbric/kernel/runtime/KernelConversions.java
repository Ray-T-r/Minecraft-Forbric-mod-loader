/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A finished conversion on the paths the merge gave MinecraftForge's lambdas: both families told, each once.
 *
 * <p>The merged {@code Zombie} converts through MinecraftForge's lambdas — identical to NeoForge's except that they post
 * MinecraftForge's {@code LivingConversionEvent.Post} — so NeoForge's was never posted on drowning, a husk's
 * conversion or a villager's zombification, and whatever NeoForge mods carry over on it (data attachments among them)
 * was lost. NeoConversionPostInjector sends those calls here: NeoForge's event is posted, and the conversion forward
 * hands it to MinecraftForge; with that forward not installed, MinecraftForge is told directly.
 */
public final class KernelConversions {
	private KernelConversions() {
	}

	public static void onLivingConvert(LivingEntity from, LivingEntity to) {
		EventHooks.onLivingConvert(from, to);
		if (!EventBridges.installed().contains(GameEventBridge.CONVERSION_POST)) ForgeEventFactory.onLivingConvert(from, to);
	}
}
