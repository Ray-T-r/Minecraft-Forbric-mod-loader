/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.common.util.Lazy;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * {@code ChunkGenerator.refreshFeaturesPerStep()}'s body when a Fabric mod replaced the generator's feature list.
 *
 * <p>NeoForge's {@code ServerLifecycleHooks.runModifiers} calls {@code refreshFeaturesPerStep()} on every level stem's
 * generator between the biome and the structure modifiers, and NeoForge's body casts the generator's
 * {@code featuresPerStep} to its own {@code Lazy}. fabric-biome-api writes a Guava memoized supplier there when a Fabric
 * mod adds or removes a feature, so the cast threw and no structure modifier applied.
 * {@code FeaturesPerStepRefreshInjector} sends the call here instead.
 *
 * <p>No game statics: the class loads while the modifier pass runs, and nothing here may pull a registry along with it.
 */
public final class KernelFeaturesPerStep {
	private static volatile boolean reported;

	private KernelFeaturesPerStep() {
	}

	/**
	 * NeoForge's {@code Lazy} is invalidated, as NeoForge's own body does; any other supplier — fabric-biome-api's
	 * {@code Suppliers.memoize}, written by its {@code finalizeWorldGen} — is replaced with a fresh {@code Lazy} of the
	 * computation NeoForge's constructor holds, which is also what Fabric's supplier computes. Either way the next read
	 * sees the biomes as the modifiers left them.
	 *
	 * <p>Never throws: it runs inside NeoForge's modifier pass, ahead of the structure modifiers, and a throw here is what
	 * used to cost all of them. If the field cannot be written, the mod's supplier stays and the structure modifiers still
	 * apply.
	 */
	public static void refresh(Supplier<?> current, ChunkGenerator generator) {
		if (current instanceof Lazy<?> lazy) {
			lazy.invalidate();
			return;
		}
		try {
			Field field = ChunkGenerator.class.getDeclaredField("featuresPerStep");
			field.setAccessible(true);
			field.set(generator, Lazy.of(() -> FeatureSorter.buildFeaturesPerStep(
					List.copyOf(generator.getBiomeSource().possibleBiomes()),
					biome -> generator.getBiomeGenerationSettings(biome).features(), true)));
			if (!reported) {
				reported = true;
				ForbricLog.info("[Forbric/Worldgen] a mod replaced a chunk generator's feature list with its own supplier (%s; "
						+ "fabric-biome-api writes one when a Fabric mod adds a feature); NeoForge's modifier pass rebuilt it as "
						+ "NeoForge's Lazy instead of casting it, so NeoForge's structure modifiers still apply",
						current == null ? "null" : current.getClass().getName());
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Worldgen] could not rebuild the feature list a mod replaced in %s — it keeps that mod's "
					+ "supplier, which may miss NeoForge's biome modifiers if it was already read", generator.getClass().getName());
		}
	}
}
