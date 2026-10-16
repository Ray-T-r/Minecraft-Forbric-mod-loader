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
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Re-emits NeoForge's server start/stop on traditional MinecraftForge's hooks, where the merged game does not call
 * them itself.
 *
 * <p>A merged base whose {@code runServer} and {@code initServer} call only NeoForge's {@code ServerLifecycleHooks}
 * leaves MinecraftForge's as dead code. That costs Forge-family mods the lifecycle events they expect, and it costs
 * the SERVER something worse: MinecraftForge's login gate, which {@code handleServerLogin} reads, is never opened.
 * Every connection — including the local client's to its own integrated server — is refused with "Server is still
 * starting", so singleplayer cannot enter a world at all.
 *
 * <h2>Where the merged game calls MinecraftForge's hook itself</h2>
 *
 * <p>A base whose merge keeps both families' edits can call MinecraftForge's hook natively on the same path as
 * NeoForge's ({@code runServer} asking both families' {@code handleServerStarted} and {@code handleServerStopping},
 * {@code initServer} both {@code handleServerStarting}). Forwarding there too delivered every one of these events
 * twice to each MinecraftForge mod, PermissionAPI was initialised twice, and so on. These forwards are
 * {@code DeadEventAudit.BRIDGED} rows, so {@code NativeDualEventInjector} scopes such a NeoForge call with the
 * MinecraftForge event delivered natively there, and the forward installed through {@code GameEventMultiplexer}'s
 * guard stands down for exactly that event. What the kernel does at server start for itself, rather than for
 * MinecraftForge, is no forward and must not stand down with one: {@link #installStartedDuties} holds it, on NeoForge's
 * bus itself.
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
	/** Invoked by the fixed tick return seam, independently of optional cross-ecosystem event forwarding. */
	public static void onCompatibilityTick(Object value) {
		MinecraftServer server = (MinecraftServer) value;
		net.forbric.kernel.boot.LateServerCompatibility.tick(server, server.isDedicatedServer(), () -> server.halt(false));
	}

	private KernelGameServerLifecycle() {
	}

	/**
	 * NeoForge {@code ServerStartedEvent} → MinecraftForge {@code handleServerStarted}, gate forced open first.
	 *
	 * <p>Only the forward: it stands down where the merged game already called MinecraftForge's hook on this path.
	 *
	 * @param neoBus NeoForge's {@code IEventBus}, handed over untyped from the boot side
	 */
	public static void installStarted(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerStartedEvent.class, "handleServerStarted", true,
				server -> ServerLifecycleHooks.handleServerStarted(server));
	}

	/**
	 * What the kernel does once a server is up, whoever delivered MinecraftForge's {@code ServerStartedEvent}.
	 *
	 * <p>Registered on NeoForge's bus itself, never on the guarded view a forward gets, and after the forward, so it
	 * runs once per start whether the forward ran or stood down: the gate opened (idempotent; a native
	 * {@code handleServerStarted} opens it as its last action), the pending dead-event judgement, the load report, and
	 * on a dedicated server the compatibility decision.
	 *
	 * @param neoBus NeoForge's {@code IEventBus} itself, not a bridge's guarded view of it
	 */
	public static void installStartedDuties(Object neoBus) {
		installStartedDuties(neoBus, server -> {
			// The world is up, so whatever failed on the way there — a mixin applying to a class first loaded at world
			// creation, a deferred task on the server thread — is known now. Integrated servers post this too, so the
			// client's world load is covered.
			net.forbric.kernel.boot.DeadEventAudit.judgePending();
			net.forbric.kernel.boot.KernelLoadReport.write();
			if (!net.forbric.kernel.boot.KernelFabricEcosystem.physicalSide().isClient()
					&& !net.forbric.kernel.ui.CompatibilityDecision.check(false)) {
				net.forbric.kernel.util.ForbricLog.error("[Forbric/Compatibility] FATAL: a required feature failed during world loading; stopping the server normally");
				server.halt(false);
			}
		});
	}

	/** {@link #installStartedDuties(Object)} with the kernel's own work handed in, so a test can see it run. */
	static void installStartedDuties(Object neoBus, Consumer<MinecraftServer> duties) {
		AtomicBoolean warned = new AtomicBoolean();
		Consumer<ServerStartedEvent> listener = neoEvent -> {
			forceAllowLogins();
			try {
				duties.accept(serverOf(neoEvent));
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] the kernel's own server-started work failed", Reflect.unwrap(t));
				}
			}
		};
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, ServerStartedEvent.class, listener);
	}

	/**
	 * A dedicated server is Fabric Loader's game instance once it exists: Fabric sets it from the server's own
	 * construction, and {@code FabricLoader.getGameInstance()} answers it from then on (a client's is the Minecraft
	 * instance, set by {@code Hooks.startClient}; an integrated server never replaces it). The server entry does that
	 * itself, as Fabric patches it ({@code LifecycleHookInjector} emits {@code Hooks.setGameInstance} after the server's
	 * constructor); this listener, on NeoForge's {@code ServerAboutToStartEvent} (the earliest event that carries the
	 * server), is only the fallback for an entry where that constructor was not found or the Fabric hooks are off.
	 */
	public static void installGameInstance(Object neoBus) {
		Consumer<ServerAboutToStartEvent> listener = neoEvent -> {
			MinecraftServer server = neoEvent.getServer();
			if (server != null && server.isDedicatedServer()) net.forbric.kernel.boot.KernelFabricEcosystem.setGameInstance(server);
		};
		((IEventBus) neoBus).addListener(EventPriority.HIGHEST, false, ServerAboutToStartEvent.class, listener);
	}

	/** NeoForge {@code ServerStoppingEvent} → MinecraftForge {@code handleServerStopping}. */
	public static void installStopping(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerStoppingEvent.class, "handleServerStopping", false,
				server -> ServerLifecycleHooks.handleServerStopping(server));
	}

	/**
	 * NeoForge {@code ServerStartingEvent} → MinecraftForge {@code handleServerStarting}.
	 *
	 * <p>Three things live in that hook and none of them had any other way to happen (javap, in order):
	 * {@code LanguageHook.loadLanguagesOnServer} on a dedicated server, {@code PermissionAPI
	 * .initializePermissionAPI()}, and the {@code ServerStartingEvent} post itself. The middle one is why this is
	 * not merely a missing listener: {@code PermissionAPI.getPermission} dereferences a handler that
	 * {@code initializePermissionAPI} is the only thing that ever sets, so every MinecraftForge mod asking a
	 * permission question — spark's {@code /spark} command gate is the one that showed it — got an NPE out of
	 * Forge's own API rather than an answer.
	 */
	public static void installStarting(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerStartingEvent.class, "handleServerStarting", false,
				server -> ServerLifecycleHooks.handleServerStarting(server));
	}

	/**
	 * NeoForge {@code ServerStoppedEvent} → MinecraftForge {@code handleServerStopped}.
	 *
	 * <p>The stop half of the pair that matters across worlds in one process. Forge's hook posts
	 * {@code ServerStoppedEvent}, clears {@code currentServer} and {@code LogicalSidedProvider}, and — the part
	 * with a lasting cost — calls {@code ConfigTracker.unloadConfigs(SERVER, worldPath)}. Without it a Forge mod's
	 * per-world SERVER config is never unloaded, so opening a second singleplayer world in the same session reads
	 * the FIRST world's values, and each world stacks another file watcher that is never closed.
	 *
	 * <p>{@code currentServer} is cleared explicitly as well, because where the merged game does not call Forge's own
	 * about-to-start hook the kernel's about-to-start bridge is what sets it, and the clearing half has to match.
	 */
	public static void installStopped(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerStoppedEvent.class, "handleServerStopped", false,
				server -> {
					try {
						ServerLifecycleHooks.handleServerStopped(server);
					} finally {
						// Even if the hook threw: leaving a dead server published is worse than publishing none,
						// because a Forge mod broadcasting on the next world would reach the old one's player list.
						KernelGameServerAboutToStart.forgetCurrentServer();
					}
				});
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
