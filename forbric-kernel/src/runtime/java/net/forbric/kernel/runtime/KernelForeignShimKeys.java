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

import java.util.Collection;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Delivers {@code RegisterKeyMappingsEvent} to the buses of mods that are not NeoForge mods.
 *
 * <p>The event's normal fan-out happens once, from {@code Options.<init>} via
 * {@code ClientHooks.onRegisterKeyMappings}, over the mods published in {@code ModList}. A Fabric mod holding the
 * NeoForge build of a multi-loader library is in neither: it gets its container from
 * {@code KernelForeignShimContext} while its own entrypoints run, which is inside {@code Minecraft.<init>} but
 * AFTER {@code Options} was built. So EntityCulling's keybind was added to a bus whose only fan-out had already
 * happened — registered, never delivered, and a keybind that is in no {@code Options.keyMappings} is a keybind the
 * player cannot see in Controls or press in game.
 *
 * <p>{@code register} writes straight into the live {@code Options.keyMappings} array, so re-posting the event here
 * puts the mappings where the game reads them. The two calls after it are what make them USABLE rather than merely
 * present: {@code Options.load(true)} is the late pass that applies a binding saved in {@code options.txt} for a key
 * that did not exist during the first load (the kernel already keeps those in {@code unknownKeys} — see
 * {@link net.forbric.kernel.runtime.KernelForgeOptions}), without which the player's own rebind is silently
 * reverted to the mod's default; and {@code KeyMapping.resetMapping()} rebuilds the input→mapping index that
 * dispatch reads, without which the key is listed in Controls and does nothing when pressed.
 */
public final class KernelForeignShimKeys {
	private KernelForeignShimKeys() {
	}

	/**
	 * Posts the event at {@code buses} and returns how many mappings that added, or -1 if it could not run.
	 *
	 * @param buses {@code net.neoforged.bus.api.IEventBus} instances, handed over untyped from the boot side
	 */
	public static int deliver(Collection<Object> buses) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null || buses.isEmpty()) return -1;
		Options options = minecraft.options;
		int before = options.keyMappings.length;
		try {
			RegisterKeyMappingsEvent event = new RegisterKeyMappingsEvent(options);
			for (Object bus : buses) {
				try {
					((IEventBus) bus).post(event);
				} catch (Throwable perBus) {
					ForbricLog.warn("[Forbric/ShimContext] a foreign mod threw while registering key mappings",
							Reflect.unwrap(perBus));
				}
			}
			int added = options.keyMappings.length - before;
			if (added <= 0) return 0;

			// Order matters: the saved binding has to be applied BEFORE the index is rebuilt, or the index holds
			// the default and the player's own key does nothing until the next restart.
			options.load(true);
			KeyMapping.resetMapping();
			ForbricLog.info("[Forbric/ShimContext] delivered %d key mapping(s) registered by non-NeoForge mod(s) "
					+ "— their bus is not in ModList, so the one fan-out from Options.<init> had already run",
					added);
			return added;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ShimContext] could not deliver foreign key mappings", Reflect.unwrap(t));
			return -1;
		}
	}
}
