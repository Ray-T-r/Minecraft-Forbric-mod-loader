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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.forbric.api.Ecosystem;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;

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
			Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> prioCls = Class.forName("net.neoforged.bus.api.EventPriority", false, cl);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object lowest = Enum.valueOf((Class) prioCls, "LOWEST");
			Method addListener = busCls.getMethod("addListener", prioCls, boolean.class, Class.class, Consumer.class);

			Class<?> mcServer = Class.forName("net.minecraft.server.MinecraftServer", false, cl);
			Class<?> factory = Class.forName("net.minecraftforge.event.ForgeEventFactory", false, cl);

			// Each bridge is installed INDEPENDENTLY. They used to be five `n +=` calls in this one try, so the
			// first setup failure skipped every bridge after it — and the cost of the ones skipped (see
			// GameEventBridge.cost()) is silent: a listener on a bus nobody posts to. One failing bridge must cost
			// only itself.
			install(GameEventBridge.SERVER_TICK_PRE, () ->
					bridgeServerTick(cl, neoBus, addListener, lowest, factory, mcServer, "Pre", "onPreServerTick"));
			install(GameEventBridge.SERVER_TICK_POST, () ->
					bridgeServerTick(cl, neoBus, addListener, lowest, factory, mcServer, "Post", "onPostServerTick"));
			// Server-lifecycle hooks: the merged base's runServer calls only NeoForge's ServerLifecycleHooks
			// .handleServerStarted (Neo won that byte-merge); MinecraftForge's is dead. That leaves MinecraftForge's
			// login gate (ServerLifecycleHooks.handleServerLogin → `if (!allowLogins.get())`) permanently CLOSED, so
			// the local player's integrated-server connection is rejected "Server is still starting" and singleplayer
			// world-join fails. Re-emit the dropped Forge hook off NeoForge's surviving ServerStarted/Stopping events.
			install(GameEventBridge.SERVER_ABOUT_TO_START, () ->
					bridgeForgeServerAboutToStart(cl, neoBus, addListener, lowest, mcServer));
			install(GameEventBridge.SERVER_STARTED, () -> bridgeServerLifecycle(cl, neoBus, addListener, lowest,
					mcServer, "net.neoforged.neoforge.event.server.ServerStartedEvent", "handleServerStarted", true));
			install(GameEventBridge.SERVER_STOPPING, () -> bridgeServerLifecycle(cl, neoBus, addListener, lowest,
					mcServer, "net.neoforged.neoforge.event.server.ServerStoppingEvent", "handleServerStopping", false));

			EventBridges.verify(GameEventBridge.Pass.GAME_BUS);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install Neo→Forge game-event bridge",
					KernelBusSupport.unwrap(t));
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
			Class<?> neoEvent = Class.forName(
					"net.neoforged.neoforge.client.event.AddClientReloadListenersEvent", false, cl);
			Class<?> forgeEvent = Class.forName(
					"net.minecraftforge.client.event.RegisterClientReloadListenersEvent", false, cl);
			Class<?> rrmCls = Class.forName("net.minecraft.server.packs.resources.ReloadableResourceManager", false, cl);
			Class<?> packTypeCls = Class.forName("net.minecraft.server.packs.PackType", false, cl);
			Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", false, cl);
			Class<?> reloadListenerCls = Class.forName(
					"net.minecraft.server.packs.resources.PreparableReloadListener", false, cl);
			Class<?> eventBusCls = Class.forName("net.minecraftforge.eventbus.api.bus.EventBus", false, cl);

			Constructor<?> scratchCtor = rrmCls.getConstructor(packTypeCls);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object clientPacks = Enum.valueOf((Class) packTypeCls, "CLIENT_RESOURCES");
			// The manager's ctor seeds this with an empty ArrayList, so the field is safe to read straight after.
			Field captured = rrmCls.getDeclaredField("listeners");
			captured.setAccessible(true);

			Constructor<?> forgeEventCtor = forgeEvent.getConstructor(rrmCls);
			Object forgeBus = forgeEvent.getField("BUS").get(null);
			// EventBus<E extends Event>, so post erases to post(Event) — not post(Object). Look it up by shape so a
			// change to the bound does not silently disable the bridge.
			Method found = null;
			for (Method m : eventBusCls.getMethods()) {
				if ("post".equals(m.getName()) && m.getParameterCount() == 1) {
					found = m;
					break;
				}
			}
			if (found == null) throw new NoSuchMethodException(eventBusCls.getName() + ".post(<event>)");
			Method post = found;
			Method fromNamespaceAndPath = identifierCls.getMethod("fromNamespaceAndPath", String.class, String.class);
			Method addToGraph = neoEvent.getMethod("addListener", identifierCls, reloadListenerCls);

			Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> prioCls = Class.forName("net.neoforged.bus.api.EventPriority", false, cl);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object lowest = Enum.valueOf((Class) prioCls, "LOWEST");
			Method addListener = busCls.getMethod("addListener", prioCls, boolean.class, Class.class, Consumer.class);

			Consumer<Object> bridge = event -> {
				try {
					Object scratch = scratchCtor.newInstance(clientPacks);
					post.invoke(forgeBus, forgeEventCtor.newInstance(scratch));

					List<?> listeners = (List<?>) captured.get(scratch);
					int n = 0;
					for (Object listener : listeners) {
						Object id = fromNamespaceAndPath.invoke(null, "forbric",
								"forge/" + sanitisePath(listener.getClass().getName()) + "_" + n);
						addToGraph.invoke(event, id, listener);
						n++;
					}
					if (n > 0) {
						ForbricLog.info("[Forbric/EventMux] bridged %d Forge client reload listener(s) into "
								+ "NeoForge's sorted graph", n);
					} else {
						ForbricLog.debug("[Forbric/EventMux] no Forge mod registered a client reload listener");
					}
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/EventMux] could not bridge Forge client reload listeners",
							KernelBusSupport.unwrap(t));
				}
			};
			addListener.invoke(modBus, lowest, false, neoEvent, bridge);
			EventBridges.installed(GameEventBridge.CLIENT_RELOAD_LISTENERS);
			ForbricLog.info("[Forbric/EventMux] installed the Neo→Forge client reload-listener bridge");
			EventBridges.verify(GameEventBridge.Pass.CLIENT_MOD_BUS);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no reload-listener bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install the client reload-listener bridge",
					KernelBusSupport.unwrap(t));
		}
	}

	/** A class name reduced to the {@code [a-z0-9._/-]} an {@code Identifier} path allows. */
	private static String sanitisePath(String className) {
		StringBuilder out = new StringBuilder(className.length());
		for (char c : className.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '_');
		}
		return out.toString();
	}

	/** NeoForge {@code ServerTickEvent.Pre/Post} → Forge {@code ForgeEventFactory.onPre/PostServerTick}. */
	private static int bridgeServerTick(ClassLoader cl, Object bus, Method addListener, Object prio, Class<?> factory,
			Class<?> mcServer, String kind, String forgeMethod) throws Exception {
		Class<?> base = Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent", false, cl);
		Class<?> evt = Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent$" + kind, false, cl);
		Method hasTime = base.getMethod("hasTime");
		Method getServer = base.getMethod("getServer");
		Method fire = factory.getMethod(forgeMethod, BooleanSupplier.class, mcServer);
		AtomicBoolean warned = new AtomicBoolean();
		AtomicInteger forwarded = new AtomicInteger();
		Consumer<Object> listener = neoEvt -> {
			try {
				Object server = getServer.invoke(neoEvt);
				BooleanSupplier haveTime = () -> {
					try {
						return (Boolean) hasTime.invoke(neoEvt);
					} catch (Throwable t) {
						return true;
					}
				};
				fire.invoke(null, haveTime, server);
				// Proof of the B-5 1:1 shape that works with REAL mods (which don't count ticks): this listener is
				// the NeoForge tick firing, and each forward is the MinecraftForge tick — so N forwards means both
				// families ticked N times in the same loop, once each. Log once at the threshold; it fires per tick.
				if (forwarded.incrementAndGet() == TICK_PROOF_THRESHOLD) {
					ForbricLog.info("[Forbric/EventMux] bridged %d ServerTickEvent.%s to MinecraftForge — 1:1 from "
							+ "NeoForge (both game-event families tick in one loop)", TICK_PROOF_THRESHOLD, kind);
				}
			} catch (Throwable t) {
				// Log ONCE (fires every tick) — degrade to NeoForge-only ticks rather than spam.
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + forgeMethod + " forward failed; Forge-family mods won't "
							+ "receive this tick event", KernelBusSupport.unwrap(t));
				}
			}
		};
		addListener.invoke(bus, prio, false, evt, listener);
		return 1;
	}

	/**
	 * NeoForge {@code Server{Started,Stopping}Event} → MinecraftForge {@code ServerLifecycleHooks.handle*}. The
	 * re-emission fires MinecraftForge's own lifecycle event (so Forge-family mods observe start/stop) AND flips its
	 * {@code allowLogins} gate — which the local singleplayer client's handshake ({@code handleServerLogin}) checks.
	 *
	 * <p>{@code openLoginGate}: on the {@code Started} bridge we FORCE {@code allowLogins=true} first (before calling
	 * the Forge hook), so a throwing Forge-mod {@code ServerStartedEvent} listener cannot leave the login gate closed
	 * and block world-join — the hook sets the flag as its LAST action, after posting the event. Runs on both sides;
	 * on a dedicated server it merely opens Forge's login gate and delivers the lifecycle event Forge mods expect,
	 * neither of which the current (Neo-only) merged path did.
	 */
	/**
	 * The one step earlier in the start sequence, forwarded piece by piece rather than whole.
	 *
	 * <p>MinecraftForge's {@code handleServerAboutToStart} does three unrelated things in a row: it reads the
	 * per-world SERVER configs that its configuration-phase sync then pushes to joining clients, it applies Forge's
	 * biome modifiers, and it posts its own {@code ServerAboutToStartEvent}. Nothing had been calling it, so Forge
	 * mods' server configs stayed at their defaults on both ends of every connection and no Forge mod had ever
	 * received that event.
	 *
	 * <p>Forwarding the method whole does not work here: the biome-modifier pass looks up a datapack registry
	 * ({@code forge:biome_modifier}) that no baseline declares under the kernel, so it always throws — and being in
	 * the middle, it would take the event with it every time. Each piece therefore gets its own guard, so a Forge
	 * feature the kernel does not carry costs only itself.
	 */
	private static int bridgeForgeServerAboutToStart(ClassLoader cl, Object bus, Method addListener, Object prio,
			Class<?> mcServer) throws Exception {
		Class<?> evt = Class.forName(ForeignType.SERVER_ABOUT_TO_START_EVENT.binary(Ecosystem.NEOFORGE), false, cl);
		Method getServer = evt.getMethod("getServer");
		Class<?> forgeHooks = Class.forName(ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.FORGE), false, cl);
		Class<?> tracker = Class.forName(ForeignType.CONFIG_TRACKER.binary(Ecosystem.FORGE), false, cl);
		Class<?> typeCls = Class.forName(ForeignType.MOD_CONFIG_TYPE.binary(Ecosystem.FORGE), false, cl);
		Class<?> forgeEvent = Class.forName(ForeignType.SERVER_ABOUT_TO_START_EVENT.binary(Ecosystem.FORGE), false, cl);
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object serverConfigs = Enum.valueOf((Class) typeCls, "SERVER");
		Method configPath = forgeHooks.getDeclaredMethod("getServerConfigPath", mcServer);
		configPath.setAccessible(true);
		Method loadConfigs = tracker.getMethod("loadConfigs", typeCls, java.nio.file.Path.class);
		Method runModifiers = forgeHooks.getDeclaredMethod("runModifiers", mcServer);
		runModifiers.setAccessible(true);
		Constructor<?> forgeEventCtor = forgeEvent.getConstructor(mcServer);
		Object forgeBus = forgeEvent.getField("BUS").get(null);
		Method post = KernelBusSupport.singleArgMethod(forgeBus.getClass(), "post");

		AtomicBoolean warnedConfigs = new AtomicBoolean();
		AtomicBoolean warnedModifiers = new AtomicBoolean();
		AtomicBoolean warnedEvent = new AtomicBoolean();
		Consumer<Object> listener = neoEvt -> {
			Object server;
			try {
				server = getServer.invoke(neoEvt);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/EventMux] NeoForge's about-to-start event carried no server; MinecraftForge's "
						+ "server configs stay at their defaults", KernelBusSupport.unwrap(t));
				return;
			}
			try {
				loadConfigs.invoke(null, serverConfigs, configPath.invoke(null, server));
			} catch (Throwable t) {
				if (warnedConfigs.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] could not load MinecraftForge's per-world SERVER configs — its "
							+ "mods keep their defaults here and on every client that joins",
							KernelBusSupport.unwrap(t));
				}
			}
			try {
				runModifiers.invoke(null, server);
			} catch (Throwable t) {
				if (warnedModifiers.compareAndSet(false, true)) {
					ForbricLog.debug("[Forbric/EventMux] MinecraftForge's biome modifiers did not apply (%s) — nothing "
							+ "declares its biome-modifier datapack registry under the kernel; the rest of its "
							+ "server start is unaffected", String.valueOf(KernelBusSupport.unwrap(t)));
				}
			}
			try {
				post.invoke(forgeBus, forgeEventCtor.newInstance(server));
			} catch (Throwable t) {
				if (warnedEvent.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] MinecraftForge's ServerAboutToStartEvent did not reach its mods",
							KernelBusSupport.unwrap(t));
				}
			}
		};
		addListener.invoke(bus, prio, false, evt, listener);
		return 1;
	}

	private static int bridgeServerLifecycle(ClassLoader cl, Object bus, Method addListener, Object prio,
			Class<?> mcServer, String neoEventClass, String forgeMethod, boolean openLoginGate) throws Exception {
		Class<?> evt = Class.forName(neoEventClass, false, cl);
		Method getServer = evt.getMethod("getServer");
		Class<?> forgeHooks = Class.forName(ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.FORGE), false, cl);
		Method forgeHandle = forgeHooks.getMethod(forgeMethod, mcServer);
		AtomicBoolean warned = new AtomicBoolean();
		Consumer<Object> listener = neoEvt -> {
			Object server = null;
			try {
				server = getServer.invoke(neoEvt);
			} catch (Throwable ignored) {
				// getServer is a stable accessor; if it somehow fails, still try to open the gate below.
			}
			if (openLoginGate) {
				forceAllowLogins(cl);
			}
			try {
				forgeHandle.invoke(null, server);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + forgeMethod + " forward failed; Forge-family mods won't "
							+ "observe this server-lifecycle event", KernelBusSupport.unwrap(t));
				}
			}
		};
		addListener.invoke(bus, prio, false, evt, listener);
		return 1;
	}

	/**
	 * Force MinecraftForge's {@code ServerLifecycleHooks.allowLogins} true. The field is {@code private static final}
	 * but references a mutable {@code AtomicBoolean}, so we flip the value, not the field — no final-field surgery.
	 */
	private static void forceAllowLogins(ClassLoader cl) {
		try {
			Class<?> forgeHooks = Class.forName(ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.FORGE), false, cl);
			java.lang.reflect.Field f = forgeHooks.getDeclaredField("allowLogins");
			f.setAccessible(true);
			Object atomic = f.get(null);
			atomic.getClass().getMethod("set", boolean.class).invoke(atomic, true);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EventMux] could not pre-open Forge allowLogins gate: %s",
					String.valueOf(KernelBusSupport.unwrap(t)));
		}
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
					+ bridge.cost(), KernelBusSupport.unwrap(t));
		}
	}
}
