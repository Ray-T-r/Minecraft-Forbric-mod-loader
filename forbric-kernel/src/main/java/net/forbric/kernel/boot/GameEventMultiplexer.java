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

package net.forbric.kernel.boot;

import java.lang.reflect.Method;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Re-emits game events the merged base's byte-merge left firing on only ONE ecosystem's hook, so the other
 * ecosystem's mods still receive them — with a single owner per family (no double-fire).
 *
 * <p>On the merged base NeoForge won the tick hook: {@code MinecraftServer.tickServer} calls
 * {@code EventHooks.fireServerTickPost} (NeoForge), and Forge's {@code ForgeEventFactory.onPostServerTick} site is
 * dead. So a traditional-Forge mod's {@code ServerTickEvent} listener never fires natively. This multiplexer
 * subscribes to the NeoForge event (the surviving hook) at LOWEST priority and forwards it to the Forge side via
 * {@code ForgeEventFactory}, giving a 1:1 re-emission. This is the kernel-native successor to the old weld's
 * reflective Neo→Forge event bridge — a single forwarding pass, no genuine loader lifecycle.
 *
 * <p>The forward depends on the Forge-family {@code @EventBusSubscriber} listeners having been registered with a
 * full-power, game-side {@code MethodHandles.Lookup} (see {@link KernelGameLookup}); registering them with a
 * boot-side lookup makes Forge's EventBus resolve the event type through the app loader and this forward then
 * throws {@code NoClassDefFoundError} on the Forge event class.
 */
public final class GameEventMultiplexer {
	/** Ticks to observe before logging the 1:1 bridge proof once (mirrors the old canary's "20 server ticks"). */
	private static final int TICK_PROOF_THRESHOLD = 20;

	private GameEventMultiplexer() {
	}

	/** Installs the Neo→Forge tick bridges. No-op if either ecosystem is absent. Call after buses exist. */
	public static void install(ClassLoader cl) {
		if (!EventBridges.enabled()) {
			ForbricLog.info("[Forbric/EventMux] -D%s=off — installing no bridges; each Forge family will receive "
					+ "only the events whose hook won the byte merge", EventBridges.SWITCH_NAME);
			return;
		}
		try {
			Object neoBus = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl)
					.getField("EVENT_BUS").get(null);
			// Resolved for their ABSENCE, not their presence. Every bridge below is game-side now and names these
			// families as TYPES, so a single-family instance would fail to LINK a game-side class — which arrives
			// as a LinkageError per bridge and reads as five warnings about a broken kernel. Probing one class
			// from each family here turns that back into what it is: a ClassNotFoundException at THIS line, and
			// one debug line saying there is only one family to bridge between.
			Class.forName("net.minecraftforge.event.ForgeEventFactory", false, cl);
			Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent", false, cl);

			// Each bridge is installed INDEPENDENTLY. They used to be five `n +=` calls in this one try, so the
			// first setup failure skipped every bridge after it — and the cost of the ones skipped (see
			// GameEventBridge.cost()) is silent: a listener on a bus nobody posts to. One failing bridge must cost
			// only itself.
			install(GameEventBridge.SERVER_TICK_PRE, () -> tickBridge(cl, "installPre").invoke(null, neoBus));
			install(GameEventBridge.SERVER_TICK_POST, () -> tickBridge(cl, "installPost").invoke(null, neoBus));
			// Server-lifecycle hooks: the merged base's runServer calls only NeoForge's ServerLifecycleHooks
			// .handleServerStarted (Neo won that byte-merge); MinecraftForge's is dead. That leaves MinecraftForge's
			// login gate (ServerLifecycleHooks.handleServerLogin → `if (!allowLogins.get())`) permanently CLOSED, so
			// the local player's integrated-server connection is rejected "Server is still starting" and singleplayer
			// world-join fails. Re-emit the dropped Forge hook off NeoForge's surviving ServerStarted/Stopping events.
			install(GameEventBridge.SERVER_ABOUT_TO_START,
					() -> aboutToStartBridge(cl).invoke(null, neoBus));
			install(GameEventBridge.SERVER_STARTED, () -> lifecycleBridge(cl, "installStarted").invoke(null, neoBus));
			install(GameEventBridge.SERVER_STOPPING,
					() -> lifecycleBridge(cl, "installStopping").invoke(null, neoBus));

			EventBridges.verify(GameEventBridge.Pass.GAME_BUS);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install Neo→Forge game-event bridge",
					Reflect.unwrap(t));
		}
	}

	/**
	 * NeoForge {@code AddClientReloadListenersEvent} → MinecraftForge {@code RegisterClientReloadListenersEvent}.
	 *
	 * <p>Same shape as the tick bridge, one layer up: NeoForge won the client reload-listener path in the byte merge
	 * (the base calls {@code neoforge/client/ClientHooks} and carries ZERO references to the MinecraftForge event),
	 * so a traditional-Forge mod's {@code @SubscribeEvent RegisterClientReloadListenersEvent} handler is registered
	 * on a bus nobody ever posts to. GeckoLib's whole client model/animation cache hangs off exactly that.
	 *
	 * <p>The two events have incompatible shapes — Forge's hands out a {@code ReloadableResourceManager} and takes
	 * unnamed listeners, NeoForge's takes {@code (Identifier, listener)} pairs and sorts them in a dependency graph.
	 * {@code ReloadableResourceManager} is a class, not an interface, so it cannot be proxied; instead a THROWAWAY
	 * instance is used purely as a capture buffer ({@code registerReloadListener} delegates straight to it), and what
	 * lands in it is then fed into NeoForge's graph under a synthesised {@code forbric:forge/…} name. Going through
	 * the graph rather than registering on the real manager is deliberate: it is the officially-correct path on a
	 * NeoForge-derived base and needs no assumption about whether a late direct registration is still honoured.
	 *
	 * @param modBus the NeoForge mod bus the game posts client mod-bus events to; {@code AddClientReloadListenersEvent}
	 *               is an {@code IModBusEvent}, so the game bus would never see it
	 */
	public static void installClientReloadBridge(ClassLoader cl, Object modBus) {
		if (!EventBridges.enabled()) {
			ForbricLog.info("[Forbric/EventMux] -D%s=off — skipping the client reload-listener bridge",
					EventBridges.SWITCH_NAME);
			return;
		}
		if (modBus == null) {
			ForbricLog.debug("[Forbric/EventMux] no NeoForge mod bus — skipping the client reload-listener bridge");
			return;
		}
		try {
			Class.forName("net.forbric.kernel.runtime.KernelGameClientReload", true, cl)
					.getMethod("install", Object.class).invoke(null, modBus);
			EventBridges.installed(GameEventBridge.CLIENT_RELOAD_LISTENERS);
			ForbricLog.info("[Forbric/EventMux] installed the Neo→Forge client reload-listener bridge");
			EventBridges.verify(GameEventBridge.Pass.CLIENT_MOD_BUS);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no reload-listener bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install the client reload-listener bridge",
					Reflect.unwrap(t));
		}
	}

	/**
	 * One entry point on the game-side tick bridge, resolved by name because this file cannot name it.
	 *
	 * <p>Written as a complete string literal on purpose: {@code KernelRuntimeClassesTest} finds game-side names
	 * by scanning boot-side sources for exactly this shape, and a name assembled from a constant and a suffix
	 * would slip past it — leaving the class unregistered, the boot-time seam check blind to it, and a renamed
	 * method a mid-game {@code NoSuchMethodException} instead of one line at startup.
	 */
	private static Method tickBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameTickEvents", true, cl)
				.getMethod(entry, Object.class);
	}

	/** One entry point on the game-side server-lifecycle bridge. Complete literal, for the reason above. */
	private static Method lifecycleBridge(ClassLoader cl, String entry) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameServerLifecycle", true, cl)
				.getMethod(entry, Object.class);
	}

	/** The game-side about-to-start bridge. Complete literal, for the reason above. */
	private static Method aboutToStartBridge(ClassLoader cl) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelGameServerAboutToStart", true, cl)
				.getMethod("install", Object.class);
	}

	/** One bridge's setup, so a failure can be caught per bridge instead of taking the rest of the pass with it. */
	@FunctionalInterface
	private interface Setup {
		void run() throws Exception;
	}

	/**
	 * Runs one bridge's setup and records it only if it succeeded.
	 *
	 * <p>Swallowing here is deliberate and is the opposite of what the old code did by accident: it caught at the
	 * whole-pass level, so one failure silently cost every bridge after it. Catching per bridge means a failure
	 * costs exactly its own feature, and {@link EventBridges#verify} then names what was lost.
	 */
	private static void install(GameEventBridge bridge, Setup setup) {
		try {
			setup.run();
			EventBridges.installed(bridge);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] bridge " + bridge + " (" + bridge.event() + ") did not install — "
					+ bridge.cost(), Reflect.unwrap(t));
		}
	}
}
