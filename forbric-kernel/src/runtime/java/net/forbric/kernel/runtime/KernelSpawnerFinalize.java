/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.List;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.common.extensions.IOwnedSpawner;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Both event families run BEFORE the one possible Mob.finalizeSpawn call. The caller supplies the same
 * ValueInput that created this entity. Forge's updated SpawnGroupData enters the one finalization call;
 * its returned data is discarded, as in both native BaseSpawner callers.
 *
 * <p>Native cancellation has two distinct meanings: cancelling the event skips initialization, while
 * setSpawnCancelled prevents the later world insertion. The previous adapter conflated these and posted Forge
 * only after NeoForge had already initialized the mob. Calling Neo's native hook with initialize=false posts
 * its event without initializing, leaving a single finalization point after both families have decided.
 */
public final class KernelSpawnerFinalize {

	private KernelSpawnerFinalize() {
	}

	/** Old or unrecognized call sites retain Neo's native path and explicitly report the missing Forge input. */
	public static FinalizeSpawnEvent finalizeMobSpawnSpawner(Mob mob, ServerLevelAccessor level,
			DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data, IOwnedSpawner spawner,
			boolean flag) {
		finding("spawner-finalize-input", true, "The spawner call site did not supply its ValueInput; Forge finalization was not dispatched.");
		return EventHooks.finalizeMobSpawnSpawner(mob, level, difficulty, reason, data, spawner, flag);
	}

	/** The added argument is loaded from the caller's verified TagValueInput.create result. */
	public static FinalizeSpawnEvent finalizeMobSpawnSpawner(Mob mob, ServerLevelAccessor level,
			DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data, IOwnedSpawner spawner,
			boolean initialize, ValueInput input) {
		if (input == null || !(spawner instanceof BaseSpawner base) || reason != EntitySpawnReason.SPAWNER) {
			finding("spawner-finalize-input", true,
					"The spawner supplied a null ValueInput, a non-BaseSpawner owner, or a non-SPAWNER reason; Forge finalization was not dispatched.");
			return EventHooks.finalizeMobSpawnSpawner(mob, level, difficulty, reason, data, spawner, initialize);
		}
		FinalizeSpawnEvent neo = EventHooks.finalizeMobSpawnSpawner(mob, level, difficulty, reason, data, spawner, false);
		if (neo == null) {
			finding("spawner-finalize-event", true, "NeoForge's event hook returned null instead of a finalization event; no second initialization was attempted.");
			return null;
		}
		if (neo.isCanceled()) return neo;

		// Do not catch a listener failure and pretend its decisions were successfully applied.
		// Native spawnCancelled vetoes world insertion, NOT initialization. Preserve a Neo veto even if a
		// later Forge listener clears the shared mob flag; do not suppress either family's finalization event.
		boolean neoSpawnVeto = neo.isSpawnCancelled();
		MobSpawnEvent.FinalizeSpawn forge;
		try {
			forge = ForgeEventFactory.onFinalizeSpawnSpawner(mob, level, neo.getDifficulty(), neo.getSpawnData(), input, base);
		} finally {
			if (neoSpawnVeto) mob.setSpawnCancelled(true);
		}
		if (forge == null) {
			neo.setCanceled(true); // skip finalization; do not upgrade this to a veto of world insertion
			return neo;
		}
		if (forge.getSpawnTag() != input) {
			// Neither native BaseSpawner caller consumes a replacement tag after the entity has been loaded.
			finding("spawner-finalize-tag-replacement", CompatibilityFinding.Confidence.RESOLVED, false,
					"A Forge listener replaced the spawner ValueInput after entity loading; both native callers leave that replacement unused, and the bridge preserves the same behavior.");
		}
		neo.setDifficulty(forge.getDifficulty());
		neo.setSpawnData(forge.getSpawnData());
		if (initialize) {
			mob.finalizeSpawn(level, neo.getDifficulty(), neo.getSpawnType(), neo.getSpawnData());
		}
		return neo;
	}

	private static void finding(String id, boolean required, String detail) {
		finding(id, CompatibilityFinding.Confidence.CONFIRMED, required, detail);
	}

	private static void finding(String id, CompatibilityFinding.Confidence confidence, boolean required, String detail) {
		CompatibilityFindings.record(new CompatibilityFinding(id, "forbric", "Spawner finalization",
				"KernelSpawnerFinalize", confidence, required, detail,
				List.of("BaseSpawner.serverTick", detail)));
	}
}
