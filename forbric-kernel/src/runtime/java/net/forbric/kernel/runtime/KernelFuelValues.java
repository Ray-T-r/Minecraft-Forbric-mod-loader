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

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Lets NeoForge say how long something burns where the merge kept only MinecraftForge's question.
 *
 * <p>{@code FuelValues.burnDuration} on the merged base calls MinecraftForge's {@code getItemBurnTime} and nothing
 * else, so NeoForge's {@code FurnaceFuelBurnTimeEvent} is never posted — and {@code balm}, in the test pack, subscribes
 * to it. A listener cannot fix that: NeoForge's hook is a static call, not something to subscribe to. So the call site
 * is redirected here (ForbricMergedBaseCompatTransformer) and NeoForge's hook answers it.
 *
 * <p>MinecraftForge's own hook used to be asked first and NeoForge chained on its answer; Forbric loads no
 * MinecraftForge mod any more, so only NeoForge is asked, with the value the game computed.
 *
 * <p>NeoForge's hook takes the {@code FuelValues} the call site is inside, which is why the redirect pushes the
 * receiver: its four-argument shape cannot be reached from MinecraftForge's three-argument one.
 */
public final class KernelFuelValues {
	private static final AtomicBoolean PROVED = new AtomicBoolean();

	private KernelFuelValues() {
	}

	/**
	 * NeoForge's burn-time hook, where the merged body asked MinecraftForge's.
	 *
	 * @param base what the game's own table says, before NeoForge is consulted
	 * @param fuel the {@code FuelValues} the call site is inside; NeoForge's hook requires it
	 */
	public static int burnDuration(ItemStack stack, int base, RecipeType<?> type, FuelValues fuel) {
		try {
			int value = EventHooks.getItemBurnTime(stack, base, type, fuel);
			if (PROVED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Fuel] NeoForge now sets burn times — the merged FuelValues asked only "
						+ "MinecraftForge, so NeoForge's FurnaceFuelBurnTimeEvent was never posted");
			}
			return value;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Fuel] NeoForge's burn-time hook failed for %s — mods cannot change how long it "
					+ "burns", stack);
			return base;
		}
	}
}
