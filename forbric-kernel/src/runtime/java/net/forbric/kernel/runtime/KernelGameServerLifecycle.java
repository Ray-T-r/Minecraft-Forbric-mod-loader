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

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerLifecycleEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Re-emits NeoForge's server start/stop on traditional MinecraftForge's hooks.
 *
 * <p>The merged base's {@code runServer} calls only NeoForge's {@code ServerLifecycleHooks}; MinecraftForge's is
 * dead code. That costs Forge-family mods the lifecycle events they expect, and it costs the SERVER something
 * worse: MinecraftForge's login gate, which {@code handleServerLogin} reads, is never opened. Every connection —
 * including the local client's to its own integrated server — is refused with "Server is still starting", so
 * singleplayer cannot enter a world at all.
 *
 * <h2>Ordering, which is the whole point of this class</h2>
 *
 * <p>{@code javap -c} on MinecraftForge's {@code handleServerStarted}:
 *
 * <pre>
 *    0..11  ServerStartedEvent.BUS.post(new ServerStartedEvent(server))
 *   17..21  allowLogins.set(true)
 *      24   return
 * </pre>
 *
 * <p>No exception table. The gate is opened as the method's LAST action, after every Forge-family mod's
 * {@code ServerStartedEvent} listener has run, so a single listener that throws leaves offset 17 unreached and
 * the server permanently unjoinable. {@link #installStarted} therefore forces the gate open BEFORE calling the
 * hook, and that ordering must not be rearranged into something tidier: the force must not sit after an
 * unguarded call, and must not be moved inside a try that the hook's own throw would skip.
 *
 * <p>{@code handleServerStopping} needs none of this — its own bytecode sets {@code allowLogins} false FIRST
 * (offsets 0..4) and posts afterwards. The asymmetry is real and is carried as data: only the started bridge
 * pre-opens.
 */
public final class KernelGameServerLifecycle {
	private KernelGameServerLifecycle() {
	}

	/**
	 * NeoForge {@code ServerStartedEvent} → MinecraftForge {@code handleServerStarted}, gate forced open first.
	 *
	 * @param neoBus NeoForge's {@code IEventBus}, handed over untyped from the boot side
	 */
	public static void installStarted(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerStartedEvent.class, "handleServerStarted", true,
				server -> ServerLifecycleHooks.handleServerStarted(server));
	}

	/** NeoForge {@code ServerStoppingEvent} → MinecraftForge {@code handleServerStopping}. */
	public static void installStopping(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerStoppingEvent.class, "handleServerStopping", false,
				server -> ServerLifecycleHooks.handleServerStopping(server));
	}

	/** The MinecraftForge side of one lifecycle step. */
	@FunctionalInterface
	private interface ForgeHook {
		void handle(MinecraftServer server);
	}

	private static <E extends ServerLifecycleEvent> void subscribe(IEventBus bus, Class<E> event,
			String hookName, boolean openLoginGate,
			ForgeHook forge) {
		AtomicBoolean warned = new AtomicBoolean();

		Consumer<E> listener = neoEvent -> {
			MinecraftServer server = serverOf(neoEvent);

			// BEFORE the hook, never after, and never inside a try the hook's own throw would skip. See the
			// class javadoc: the hook opens this gate as its last action and has no exception table.
			if (openLoginGate) forceAllowLogins();

			try {
				forge.handle(server);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + hookName + " forward failed; Forge-family mods won't "
							+ "observe this server-lifecycle event", Reflect.unwrap(t));
				}
			}
		};

		// Four-argument overload with LOWEST, as everywhere in this package: the shorter overloads open with
		// `getstatic EventPriority.NORMAL` and would silently promote the forward.
		bus.addListener(EventPriority.LOWEST, false, event, listener);
	}

	/**
	 * The server the NeoForge event carries, or null.
	 *
	 * <p>{@code getServer()} is a plain accessor on the common supertype, so this is read defensively rather
	 * than because it is expected to fail. The reason is the caller: it opens the login gate either way, and a
	 * failure to read the server must not be what costs the world-join.
	 *
	 * <p>The boot-side version needed an instanceof chain here, because it held the event as an Object and each
	 * concrete class had to be tried in turn. Bounding the type parameter on {@code ServerLifecycleEvent}
	 * removes both the chain and the question of what happens when a third event type is added.
	 */
	private static MinecraftServer serverOf(ServerLifecycleEvent neoEvent) {
		try {
			return neoEvent.getServer();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * Forces MinecraftForge's {@code allowLogins} true.
	 *
	 * <p>Reflective on purpose and irreducibly so: {@code javap -p} shows {@code allowLogins} is
	 * {@code private static final}. The final is not fought — the field holds a mutable {@code AtomicBoolean},
	 * so the VALUE is flipped and the field is never written. The cast is checked now, which it was not before.
	 */
	private static void forceAllowLogins() {
		try {
			Field f = ServerLifecycleHooks.class.getDeclaredField("allowLogins");
			f.setAccessible(true);
			((AtomicBoolean) f.get(null)).set(true);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EventMux] could not pre-open Forge allowLogins gate: %s",
					String.valueOf(Reflect.unwrap(t)));
		}
	}
}
