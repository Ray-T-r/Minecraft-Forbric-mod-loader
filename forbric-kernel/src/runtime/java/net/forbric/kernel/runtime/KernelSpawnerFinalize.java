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

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.common.extensions.IOwnedSpawner;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Lets a MinecraftForge mod see, and refuse, a mob a spawner is about to finish.
 *
 * <h2>What the merge did here</h2>
 *
 * <p>Both ecosystems patched {@code BaseSpawner.serverTick} and NeoForge's body won, so the merged base calls
 * {@code EventHooks.finalizeMobSpawnSpawner} and MinecraftForge's {@code onFinalizeSpawnSpawner} is called from
 * nowhere. {@code collective}, in the test pack, subscribes to {@code MobSpawnEvent$FinalizeSpawn}.
 *
 * <p>Restoring MinecraftForge's own call would mean putting its instruction run back into a body that is
 * NeoForge's, with NeoForge's local variable numbering — the three-way merge this tree does not have. Redirecting
 * the surviving call costs nothing of the sort: the kernel method takes NeoForge's exact signature, so the
 * rewrite is an owner and a name and the stack is untouched.
 *
 * <h2>What carries, and what does not</h2>
 *
 * <p>A refusal carries: MinecraftForge's hook returns null when a mod cancelled the spawn, and that becomes
 * {@code setSpawnCancelled(true)} on NeoForge's event, which the call site already honours.
 *
 * <p>A CHANGED spawn group data does not. MinecraftForge's event offers {@code setSpawnData} and the value the
 * call site uses is NeoForge's, so a mod that rewrites the data rather than refusing the spawn gets one line
 * saying so rather than having its change silently dropped — the failure this whole seam exists to end, which
 * would otherwise arrive one level in.
 *
 * <p>MinecraftForge's hook also wants a {@code ValueInput} that NeoForge's signature does not carry, so it is
 * passed null. If that turns out to matter, the call throws, the throw is caught, and the result is exactly
 * today's behaviour plus one warning — the downside is bounded at "no worse than not asking".
 */
public final class KernelSpawnerFinalize {
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static final AtomicBoolean SAID_DATA = new AtomicBoolean();
	private static final AtomicBoolean PROVED = new AtomicBoolean();

	private KernelSpawnerFinalize() {
	}

	/** NeoForge's signature exactly, so the redirect is an owner and a name. */
	public static FinalizeSpawnEvent finalizeMobSpawnSpawner(Mob mob, ServerLevelAccessor level,
			DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data, IOwnedSpawner spawner,
			boolean flag) {
		FinalizeSpawnEvent neo = EventHooks.finalizeMobSpawnSpawner(mob, level, difficulty, reason, data, spawner, flag);
		try {
			MobSpawnEvent.FinalizeSpawn forge = ForgeEventFactory.onFinalizeSpawnSpawner(
					mob, level, difficulty, data, null, spawner instanceof BaseSpawner base ? base : null);
			if (forge == null) {
				if (neo != null) neo.setSpawnCancelled(true);
			} else if (forge.getSpawnData() != data && SAID_DATA.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Spawner] a MinecraftForge mod rewrote the spawn data for a spawner mob; "
						+ "the merged call site uses NeoForge's value, so the refusal would carry and this "
						+ "rewrite does not");
			}
			if (PROVED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Spawner] MinecraftForge now sees spawner mobs being finalised — the "
						+ "merge kept only NeoForge's hook, so MobSpawnEvent$FinalizeSpawn was posted nowhere");
			}
		} catch (Throwable t) {
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Spawner] MinecraftForge's finalize-spawn hook failed — mods on that "
						+ "side cannot see or refuse mobs a spawner produces", Reflect.unwrap(t));
			}
		}
		return neo;
	}
}
