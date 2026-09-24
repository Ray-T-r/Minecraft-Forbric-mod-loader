/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IOwnedSpawner;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Where both families' coremods send a mob's finalization: each family's event, then one {@code finalizeSpawn}
 * (NativeCoremodParity redirects the calls).
 *
 * <p>Natively NeoForge rewrites every {@code mob.finalizeSpawn(...)} in its 26 listed classes to
 * {@code EventHooks.finalizeMobSpawn}, and MinecraftForge rewrites the same calls to
 * {@code ForgeEventFactory.onFinalizeSpawn}; each posts its own event and then finalizes. Neither coremod runs on the
 * merged base, so neither event was ever posted for a natural, structure, command or conversion spawn. Chaining the
 * two native hooks would finalize the mob twice (double equipment, a second chicken jockey), so this posts NeoForge's
 * event, then MinecraftForge's with what NeoForge's listeners left, and finalizes once with what MinecraftForge's left.
 * Either family cancelling skips the finalization, as each native hook does; a NeoForge veto of the world insertion
 * ({@code setSpawnCancelled}) survives a MinecraftForge listener clearing the shared flag, as in KernelSpawnerFinalize.
 */
public final class KernelFinalizeSpawn {
	private KernelFinalizeSpawn() {
	}

	/** The redirect target of every {@code Mob.finalizeSpawn} call in NeoForge's listed classes. */
	public static SpawnGroupData finalizeMobSpawn(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty,
			EntitySpawnReason reason, SpawnGroupData data) {
		FinalizeSpawnEvent neo = new FinalizeSpawnEvent(mob, level, mob.getX(), mob.getY(), mob.getZ(), difficulty, reason,
				data, null);
		NeoForge.EVENT_BUS.post(neo);
		if (neo.isCanceled()) return null;
		MobSpawnEvent.FinalizeSpawn forge = forge(mob, level, neo);
		if (forge == null) return null;
		return mob.finalizeSpawn(level, forge.getDifficulty(), forge.getSpawnReason(), forge.getSpawnData());
	}

	/**
	 * TrialSpawner's call, which the merged base already routes to NeoForge's spawner hook. Natively MinecraftForge
	 * posts its plain event there only where vanilla initializes the mob, with no spawner and no spawn tag.
	 */
	public static FinalizeSpawnEvent finalizeTrialSpawner(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty,
			EntitySpawnReason reason, SpawnGroupData data, IOwnedSpawner spawner, boolean initialize) {
		FinalizeSpawnEvent neo = EventHooks.finalizeMobSpawnSpawner(mob, level, difficulty, reason, data, spawner, false);
		if (neo == null || neo.isCanceled() || !initialize) return neo;
		MobSpawnEvent.FinalizeSpawn forge = forge(mob, level, neo);
		if (forge == null) {
			neo.setCanceled(true);   // skip the finalization, as a MinecraftForge cancel does natively
			return neo;
		}
		neo.setDifficulty(forge.getDifficulty());
		neo.setSpawnData(forge.getSpawnData());
		mob.finalizeSpawn(level, neo.getDifficulty(), neo.getSpawnType(), neo.getSpawnData());
		return neo;
	}

	/** Posts MinecraftForge's event after NeoForge's; null when a MinecraftForge listener cancelled it. */
	private static MobSpawnEvent.FinalizeSpawn forge(Mob mob, ServerLevelAccessor level, FinalizeSpawnEvent neo) {
		boolean neoSpawnVeto = mob.isSpawnCancelled();
		MobSpawnEvent.FinalizeSpawn forge = new MobSpawnEvent.FinalizeSpawn(mob, level, mob.getX(), mob.getY(), mob.getZ(),
				neo.getDifficulty(), neo.getSpawnType(), neo.getSpawnData(), null, null);
		boolean cancelled;
		try {
			cancelled = MobSpawnEvent.FinalizeSpawn.BUS.post(forge);
		} finally {
			if (neoSpawnVeto) mob.setSpawnCancelled(true);
		}
		return cancelled ? null : forge;
	}
}
