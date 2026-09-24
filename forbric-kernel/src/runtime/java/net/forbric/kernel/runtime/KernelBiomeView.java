/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/**
 * The biome NeoForge's modifier pass starts from, so a Fabric mod's climate survives it (BiomeInfoRebaseInjector).
 *
 * <p>Natively NeoForge's coremod makes every biome read go through {@code getModifiedClimateSettings()} and
 * {@code getModifiedSpecialEffects()}, and the pass builds that modified view from the biome's ORIGINAL info — the
 * values its constructor saw. On NeoForge nothing else writes those fields afterwards. On the merged base
 * fabric-biome-api does: its weather modifications replace {@code Biome.climateSettings} at server construction,
 * before NeoForge's pass. Starting from the original would silently drop them once the reads go through the view
 * (NativeCoremodParity), so the pass starts from what the biome holds now: its current climate and effects over the
 * original generation and spawn settings (Fabric edits those in place, on the same objects).
 */
public final class KernelBiomeView {
	private static volatile Field climate, effects;
	private static volatile Method mark;
	private static volatile int rebased;

	private KernelBiomeView() {
	}

	/** {@code original}, or the same info carrying the biome's current climate and effects when they differ. */
	public static ModifiableBiomeInfo.BiomeInfo startFrom(ModifiableBiomeInfo.BiomeInfo original, Holder<Biome> holder) {
		try {
			Biome biome = holder.value();
			Biome.ClimateSettings rawClimate = (Biome.ClimateSettings) field("climateSettings").get(biome);
			BiomeSpecialEffects rawEffects = (BiomeSpecialEffects) field("specialEffects").get(biome);
			markPass(biome, rawClimate, rawEffects);
			if (rawClimate == original.climateSettings() && rawEffects == original.effects()) return original;
			if (rebased++ == 0) {
				ForbricLog.info("[Forbric/Worldgen] NeoForge's biome modifiers now start from the climate a Fabric mod "
						+ "gave %s (and every other biome it changed), not the one its constructor saw", holder.getRegisteredName());
			}
			return new ModifiableBiomeInfo.BiomeInfo(rawClimate == null ? original.climateSettings() : rawClimate,
					rawEffects == null ? original.effects() : rawEffects, original.generationSettings(),
					original.mobSpawnSettings());
		} catch (Throwable failure) {
			ForbricLog.warn("[Forbric/Worldgen] could not read the current climate of a biome; NeoForge's modifiers start "
					+ "from its original info (%s)", Reflect.unwrap(failure));
			return original;
		}
	}

	/**
	 * Tells the biome what it held at the pass, so a later replacement wins over the view (BiomeLateWriteInjector).
	 * Absent when that injector stood down; the view then simply holds.
	 */
	private static void markPass(Biome biome, Biome.ClimateSettings climate, BiomeSpecialEffects effects) {
		try {
			Method method = mark;
			if (method == null) {
				method = Biome.class.getMethod("forbric$markPass", Biome.ClimateSettings.class, BiomeSpecialEffects.class);
				mark = method;
			}
			method.invoke(biome, climate, effects);
		} catch (NoSuchMethodException absent) {
			// the late-write half is not on this base; nothing to record
		} catch (Throwable failure) {
			ForbricLog.debug("[Forbric/Worldgen] could not record a biome's pass-time climate: %s", failure);
		}
	}

	private static Field field(String name) throws NoSuchFieldException {
		Field cached = name.equals("climateSettings") ? climate : effects;
		if (cached != null) return cached;
		Field found = Biome.class.getDeclaredField(name);
		found.setAccessible(true);
		if (name.equals("climateSettings")) climate = found;
		else effects = found;
		return found;
	}
}
