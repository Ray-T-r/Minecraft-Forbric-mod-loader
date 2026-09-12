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

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * Re-emits NeoForge's server about-to-start on traditional MinecraftForge's, piece by piece.
 *
 * <p>MinecraftForge's own {@code handleServerAboutToStart} does three unrelated things in a row: it reads the
 * per-world SERVER configs its configuration-phase sync then pushes to joining clients, it applies Forge's biome
 * modifiers, and it posts its own {@code ServerAboutToStartEvent}. Nothing was calling it, so Forge mods' server
 * configs stayed at their defaults on both ends of every connection and no Forge mod ever received that event.
 *
 * <h2>Why the method is not forwarded whole</h2>
 *
 * <p>Because the middle step always throws here. The biome-modifier pass looks up a datapack registry
 * ({@code forge:biome_modifier}) that no baseline declares under the kernel, so forwarding the method as one
 * call would take the configs and the event down with it on every single start. Each piece gets its own guard,
 * and a Forge feature the kernel does not carry costs only itself.
 *
 * <p>The three log levels differ on purpose and are DATA, not inconsistency. Missing configs is a warning: Forge
 * mods silently keep their defaults here and on every client that joins. A missing event is a warning: mods
 * never learn the server is starting. The biome modifiers are DEBUG, because their absence is expected on this
 * kernel and a warning every boot would train the reader to skip the other two.
 *
 * <h2>What javac checks here, and what it cannot</h2>
 *
 * <p>Types, including {@code ConfigTracker.loadConfigs(ModConfig.Type, Path)} and the typed
 * {@code ServerAboutToStartEvent.BUS.post(...)} — the shape-based {@code post} lookup the boot side needed is
 * gone, because the bus field carries its own generic argument. Two members stay reflective and cannot be
 * anything else: {@code javap -p} shows {@code getServerConfigPath} and {@code runModifiers} are BOTH
 * {@code private static} on {@code ServerLifecycleHooks}. Reimplementing either would mean reproducing
 * MinecraftForge's own logic, which is the one thing this project does not do.
 */
public final class KernelGameServerAboutToStart {
	private KernelGameServerAboutToStart() {
	}

	/**
	 * @param neoBus NeoForge's {@code IEventBus}, handed over untyped from the boot side
	 */
	public static void install(Object neoBus) throws Exception {
		Method configPath = ServerLifecycleHooks.class.getDeclaredMethod("getServerConfigPath", MinecraftServer.class);
		configPath.setAccessible(true);
		Method runModifiers = ServerLifecycleHooks.class.getDeclaredMethod("runModifiers", MinecraftServer.class);
		runModifiers.setAccessible(true);

		AtomicBoolean warnedConfigs = new AtomicBoolean();
		AtomicBoolean warnedModifiers = new AtomicBoolean();
		AtomicBoolean warnedEvent = new AtomicBoolean();

		Consumer<ServerAboutToStartEvent> listener = neoEvent -> {
			MinecraftServer server;
			try {
				server = neoEvent.getServer();
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/EventMux] NeoForge's about-to-start event carried no server; "
						+ "MinecraftForge's server configs stay at their defaults", Reflect.unwrap(t));
				return;
			}

			try {
				ConfigTracker.loadConfigs(ModConfig.Type.SERVER, (Path) configPath.invoke(null, server));
			} catch (Throwable t) {
				if (warnedConfigs.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] could not load MinecraftForge's per-world SERVER configs — "
							+ "its mods keep their defaults here and on every client that joins",
							Reflect.unwrap(t));
				}
			}

			try {
				runModifiers.invoke(null, server);
			} catch (Throwable t) {
				if (warnedModifiers.compareAndSet(false, true)) {
					ForbricLog.debug("[Forbric/EventMux] MinecraftForge's biome modifiers did not apply (%s) — "
							+ "nothing declares its biome-modifier datapack registry under the kernel; the rest "
							+ "of its server start is unaffected", String.valueOf(Reflect.unwrap(t)));
				}
			}

			try {
				net.minecraftforge.event.server.ServerAboutToStartEvent.BUS.post(
						new net.minecraftforge.event.server.ServerAboutToStartEvent(server));
			} catch (Throwable t) {
				if (warnedEvent.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] MinecraftForge's ServerAboutToStartEvent did not reach its "
							+ "mods", Reflect.unwrap(t));
				}
			}
		};

		// Four-argument overload with LOWEST, as everywhere in this package.
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, ServerAboutToStartEvent.class, listener);
	}
}
