/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * The event half of NeoForge's {@code EventHooks.checkSpawnPositionSpawner} and {@code checkSpawnPosition}, for a
 * caller whose vanilla checks the kernel has put back (SpawnPositionCallsInjector).
 *
 * <p>NeoForge's hooks post {@code MobSpawnEvent.PositionCheck} (with the spawner, or null outside one), and only
 * when its listeners leave the result at {@code DEFAULT} do they run the vanilla checks — {@code Mob.checkSpawnRules}
 * (for a spawner, unless the spawn data carries custom rules), then {@code Mob.checkSpawnObstruction}. Those calls
 * lived inside the hooks, where a Fabric mixin aimed at the caller cannot reach them. This posts the same event the
 * same way and answers what it decided; the calls themselves now run in the caller, exactly where vanilla made them.
 */
public final class KernelSpawnPosition {
	private KernelSpawnPosition() {
	}

	/** -1 when the listeners left it to the vanilla checks, 1 when they allowed the spawn, 0 when they denied it. */
	public static int decide(Mob mob, ServerLevelAccessor level, EntitySpawnReason reason, BaseSpawner spawner) {
		MobSpawnEvent.PositionCheck event = new MobSpawnEvent.PositionCheck(mob, level, reason, spawner);
		NeoForge.EVENT_BUS.post(event);
		MobSpawnEvent.PositionCheck.Result result = event.getResult();
		if (result == MobSpawnEvent.PositionCheck.Result.DEFAULT) return -1;
		return result == MobSpawnEvent.PositionCheck.Result.SUCCEED ? 1 : 0;
	}
}
