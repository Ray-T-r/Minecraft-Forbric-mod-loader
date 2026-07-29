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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

			int n = 0;
			n += bridgeServerTick(cl, neoBus, addListener, lowest, factory, mcServer, "Pre", "onPreServerTick");
			n += bridgeServerTick(cl, neoBus, addListener, lowest, factory, mcServer, "Post", "onPostServerTick");
			// Server-lifecycle hooks: the merged base's runServer calls only NeoForge's ServerLifecycleHooks
			// .handleServerStarted (Neo won that byte-merge); MinecraftForge's is dead. That leaves MinecraftForge's
			// login gate (ServerLifecycleHooks.handleServerLogin → `if (!allowLogins.get())`) permanently CLOSED, so
			// the local player's integrated-server connection is rejected "Server is still starting" and singleplayer
			// world-join fails. Re-emit the dropped Forge hook off NeoForge's surviving ServerStarted/Stopping events.
			n += bridgeServerLifecycle(cl, neoBus, addListener, lowest, mcServer,
					"net.neoforged.neoforge.event.server.ServerStartedEvent", "handleServerStarted", true);
			n += bridgeServerLifecycle(cl, neoBus, addListener, lowest, mcServer,
					"net.neoforged.neoforge.event.server.ServerStoppingEvent", "handleServerStopping", false);
			ForbricLog.info("[Forbric/EventMux] installed %d Neo→Forge game-event bridge(s) (single-owner, 1:1)", n);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/EventMux] only one Forge family present — no bridge needed");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not install Neo→Forge game-event bridge",
					KernelBusSupport.unwrap(t));
		}
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
	private static int bridgeServerLifecycle(ClassLoader cl, Object bus, Method addListener, Object prio,
			Class<?> mcServer, String neoEventClass, String forgeMethod, boolean openLoginGate) throws Exception {
		Class<?> evt = Class.forName(neoEventClass, false, cl);
		Method getServer = evt.getMethod("getServer");
		Class<?> forgeHooks = Class.forName("net.minecraftforge.server.ServerLifecycleHooks", false, cl);
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
			Class<?> forgeHooks = Class.forName("net.minecraftforge.server.ServerLifecycleHooks", false, cl);
			java.lang.reflect.Field f = forgeHooks.getDeclaredField("allowLogins");
			f.setAccessible(true);
			Object atomic = f.get(null);
			atomic.getClass().getMethod("set", boolean.class).invoke(atomic, true);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EventMux] could not pre-open Forge allowLogins gate: %s",
					String.valueOf(KernelBusSupport.unwrap(t)));
		}
	}
}
