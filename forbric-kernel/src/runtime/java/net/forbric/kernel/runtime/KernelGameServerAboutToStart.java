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
import java.util.function.Consumer;

import net.forbric.kernel.util.Caught;
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
 * <p>A merged {@code initServer} that keeps both families' edits calls it natively, ahead of NeoForge's
 * about-to-start. Then everything this class reproduces has already happened — the server recorded, the SERVER
 * configs loaded (with their file watchers), the event posted — and doing it again loaded every config twice and
 * posted the event twice. The forward is a {@code DeadEventAudit.BRIDGED} row, so {@code NativeDualEventInjector}
 * scopes that NeoForge call with MinecraftForge's event and the whole listener stands down for it: there is no part
 * of this re-emission the native hook leaves undone.
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
 * gone, because the bus field carries its own generic argument. One member stays reflective and cannot be
 * anything else: {@code javap -p} shows {@code getServerConfigPath} is {@code private static} on
 * {@code ServerLifecycleHooks}. Reimplementing it would mean reproducing MinecraftForge's own logic, which is
 * the one thing this project does not do. Forge's {@code runModifiers} is not called at all — see
 * {@link KernelForgeWorldgen} for where its modifiers run instead.
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
		// MinecraftForge's own runModifiers is deliberately NOT invoked: its lambdas link against Forge-typed
		// accessors the merged Biome/Structure do not declare, and its modifiers now ride inside NeoForge's single
		// pass (KernelForgeWorldgen), phase-interleaved with NeoForge's — two passes would be two sources of truth.

		Consumer<ServerAboutToStartEvent> listener = neoEvent -> {
			MinecraftServer server;
			try {
				server = neoEvent.getServer();
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/EventMux] NeoForge's about-to-start event carried no server; "
						+ "MinecraftForge's server configs stay at their defaults");
				return;
			}

			// FIRST, and before anything that can throw. Forge's own handleServerAboutToStart writes currentServer
			// at offset 1 and calls LogicalSidedProvider.setServer at 10 — ahead of the config load, the biome
			// modifiers and the event — because everything after it may need to reach the server it just recorded.
			// The kernel re-emitted this hook piece by piece and reproduced every piece EXCEPT this one, so
			// ServerLifecycleHooks.getCurrentServer() stayed null for the whole run. That is not a cosmetic gap:
			// PacketDistributor.ALL / DIMENSION / NEAR resolve their player list through it, so a MinecraftForge
			// mod broadcasting a sync packet NPEs inside Forge's own dispatcher, and the crash report blames the
			// mod. The old weld set it; the kernel did not, which makes it a regression against the oracle.
			recordCurrentServer(server);

			try {
				ConfigTracker.loadConfigs(ModConfig.Type.SERVER, (Path) configPath.invoke(null, server));
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/EventMux] could not load MinecraftForge's per-world SERVER configs — "
						+ "its mods keep their defaults here and on every client that joins");
			}

			try {
				net.minecraftforge.event.server.ServerAboutToStartEvent.BUS.post(
						new net.minecraftforge.event.server.ServerAboutToStartEvent(server));
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/EventMux] MinecraftForge's ServerAboutToStartEvent did not reach its mods");
			}
		};

		// Four-argument overload with LOWEST, as everywhere in this package.
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, ServerAboutToStartEvent.class, listener);
	}

	/**
	 * Publishes the server on MinecraftForge's two "which server is running" seams.
	 *
	 * <p>{@code currentServer} is {@code private static} — javap confirms it, and there is no setter — so the
	 * field is written reflectively. {@code LogicalSidedProvider.setServer} is public and takes a supplier;
	 * {@link net.forbric.kernel.boot.KernelLifecycle} already installs a lazy one at boot, and this overwrites it
	 * with one that answers THIS server directly, which is what Forge's own hook does.
	 *
	 * <p>Best-effort and reported: a mod that never broadcasts will not notice, and one that does gets a
	 * line naming the cause instead of an NPE from inside Forge's packet dispatcher.
	 */
	private static void recordCurrentServer(MinecraftServer server) {
		try {
			java.lang.reflect.Field current = ServerLifecycleHooks.class.getDeclaredField("currentServer");
			current.setAccessible(true);
			current.set(null, server);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EventMux] could not publish the running server on MinecraftForge's "
					+ "ServerLifecycleHooks.currentServer — getCurrentServer() stays null, so a Forge mod's "
					+ "PacketDistributor.ALL/DIMENSION/NEAR broadcast NPEs inside Forge's own dispatcher");
		}
		try {
			net.minecraftforge.common.util.LogicalSidedProvider.setServer(() -> server);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EventMux] could not point MinecraftForge's LogicalSidedProvider at this server — "
					+ "a Forge mod asking it for the server gets the previous answer");
		}
	}

	/** Cleared when the server stops, so a second world in the same process does not see the first one. */
	public static void forgetCurrentServer() {
		try {
			java.lang.reflect.Field current = ServerLifecycleHooks.class.getDeclaredField("currentServer");
			current.setAccessible(true);
			current.set(null, null);
			net.minecraftforge.common.util.LogicalSidedProvider.setServer(() -> null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EventMux] could not clear MinecraftForge's currentServer — it keeps pointing at "
					+ "the stopped server");
		}
	}

}
