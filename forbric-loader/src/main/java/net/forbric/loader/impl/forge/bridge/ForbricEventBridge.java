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

package net.forbric.loader.impl.forge.bridge;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Tri-in-one only: forwards NeoForge game-bus events to their traditional-MinecraftForge equivalents so Forge mods
 * see events even though, on the MERGED game base, the vanilla hook that would fire the FORGE event lost the
 * byte-merge to NeoForge's. Every gameplay-critical hook site in the merged jar fires exactly ONE ecosystem's event
 * (per-method winner-takes-all), and NeoForge won most — e.g. {@code MinecraftServer.tickServer} calls
 * {@code EventHooks.fireServerTickPre} only (Forge's {@code ForgeEventFactory.onPreServerTick} was dropped,
 * merge-conflicts:527), so spark's {@code ServerTickEvent} listener never fires without this bridge.
 *
 * <p>Reflection-only, zero NeoForge/Forge compile dependency (NeoForge's {@code IEventBus.addListener(EventPriority,
 * boolean, Class, Consumer)} takes an explicit class token, so a plain {@code Consumer} subscribes without any
 * generic-signature knowledge; the Forge side is fired through {@code ForgeEventFactory}/{@code ServerLifecycleHooks}
 * statics — the same entry points the Forge-patched game jar itself calls). Installed once, from a {@code finally} at
 * the tail of whichever Forge lifecycle Forbric drives — {@link net.forbric.loader.impl.forge.runtime.ForbricDualLifecycle}
 * on the dedicated server, {@link net.forbric.loader.impl.forge.runtime.ForbricClientDualLifecycle} on the client — so
 * it is armed on BOTH sides and survives a Forge mod-loading failure. Listens at {@code LOWEST} priority with
 * {@code receiveCanceled=false} so Forge sees each event after NeoForge mods have finished mutating it, and never for
 * one NeoForge already canceled. {@link #install} is CAS-guarded, so the two call sites can never double-subscribe.
 *
 * <p><b>The stuck-static class of defect.</b> Beyond per-tick events, the bridge exists to repair a systematic merge
 * asymmetry: <em>traditional-Forge process-wide state whose only WRITER is a Forge hook that lost its byte-merge site,
 * while a Forge hook that WON its site still READS it.</em> The load-bearing instance is
 * {@code ServerLifecycleHooks.allowLogins}: its sole {@code true} writer is Forge's {@code handleServerStarted}, which
 * lost {@code MinecraftServer.runServer} to NeoForge's — but Forge's {@code handleServerLogin} won BOTH handshake sites
 * uncontested (NeoForge patches neither, and has no {@code allowLogins} at all). So the login gate reads a flag whose
 * writer is dead code, and every login — including the singleplayer {@code MemoryServerHandshakePacketListenerImpl}
 * path — is rejected with "Server is still starting!". Because the flag lives in a {@code static final AtomicBoolean}
 * holder rather than a plain mutable static, a {@code putstatic} audit misses it entirely.
 *
 * <p>The general repair: NeoForge won <em>every</em> server-lifecycle call site, and each of its hooks posts a Neo
 * event on the static {@code NeoForge.EVENT_BUS}. A listener on each Neo event is therefore a faithful, exactly-once
 * re-anchor of the lost Forge hook, on client and dedicated alike, with no vanilla patching. Each forwarder replays a
 * deliberately chosen SAFE SUBSET of the lost Forge hook (see the per-method javadoc for what is skipped and why).
 *
 * <p>Covers the events the measured real Forge mod set uses (spark's server/level ticks, TerraBlender's
 * {@code ServerAboutToStartEvent}) plus the full server-lifecycle quartet. More mappings are additive.
 */
public final class ForbricEventBridge {
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();

	private ForbricEventBridge() {
	}

	/** Installs the Neo→Forge event bridges once. No-op if NeoForge or Forge isn't present (pure single-ecosystem). */
	public static void install(ClassLoader cl) {
		if (!INSTALLED.compareAndSet(false, true)) return;
		int n = 0; // hoisted: the catch below un-poisons the CAS only if NOTHING was subscribed yet
		try {
			Object neoBus = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl)
					.getField("EVENT_BUS").get(null);
			Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> prioCls = Class.forName("net.neoforged.bus.api.EventPriority", false, cl);
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object lowest = Enum.valueOf((Class) prioCls, "LOWEST");
			Method addListener = busCls.getMethod("addListener", prioCls, boolean.class, Class.class, Consumer.class);

			Class<?> mcServer = Class.forName("net.minecraft.server.MinecraftServer", false, cl);
			Class<?> level = Class.forName("net.minecraft.world.level.Level", false, cl);
			Class<?> factory = Class.forName("net.minecraftforge.event.ForgeEventFactory", false, cl);
			Class<?> lifecycleHooks = Class.forName("net.minecraftforge.server.ServerLifecycleHooks", false, cl);

			ForgeServerState forge = new ForgeServerState(cl, lifecycleHooks, mcServer);

			n += bridgeServerTick(cl, neoBus, addListener, lowest, factory, mcServer, "Pre", "onPreServerTick");
			n += bridgeServerTick(cl, neoBus, addListener, lowest, factory, mcServer, "Post", "onPostServerTick");
			n += bridgeLevelTick(cl, neoBus, addListener, lowest, factory, level, "Pre", "onPreLevelTick");
			n += bridgeLevelTick(cl, neoBus, addListener, lowest, factory, level, "Post", "onPostLevelTick");

			// The server-lifecycle quartet+1: every Forge handleServerX lost its merge site to NeoForge's, so each is
			// re-anchored on the Neo event NeoForge's surviving hook posts. Order of subscription is irrelevant (the
			// events themselves are ordered by the server); order of side effects WITHIN each handler is not.
			n += bridgeServerAboutToStart(cl, neoBus, addListener, lowest, forge);
			n += bridgeServerStarting(cl, neoBus, addListener, lowest, forge);
			n += bridgeServerStarted(cl, neoBus, addListener, lowest, forge);
			n += bridgeServerStopping(cl, neoBus, addListener, lowest, forge);
			n += bridgeServerStopped(cl, neoBus, addListener, lowest, forge);

			ForbricLog.info("[Forbric/EventBridge] installed " + n + " Neo->Forge event bridge(s) — Forge mods now "
					+ "see server/level ticks and the full server lifecycle on the merged base; allowLogins gate armed");
		} catch (Throwable t) {
			// No NeoForge (or no Forge) on the classpath — single-ecosystem instance, nothing to bridge. If we failed
			// BEFORE subscribing anything, release the CAS so the other call site (client vs dedicated) may retry;
			// a partial install must never be re-run, or its already-added listeners would double-fire.
			if (n == 0) INSTALLED.set(false);
			ForbricLog.warn("[Forbric/EventBridge] not installed (single-ecosystem base, or a signature drifted): "
					+ t);
		}
	}

	/**
	 * The traditional-Forge server-lifecycle statics + entry points the merged base can no longer reach on its own,
	 * resolved once. All fields/methods verified present in forge-runtime 65.0.1 (see class javadoc).
	 */
	private static final class ForgeServerState {
		final Class<?> mcServer;
		final AtomicBoolean allowLogins;          // private static FINAL holder: mutate it, never re-assign the field
		final java.lang.reflect.Field currentServer;
		final Method setServer;                   // LogicalSidedProvider.setServer(Supplier<MinecraftServer>)
		final Method getServerConfigPath;         // private static Path ServerLifecycleHooks.getServerConfigPath(server)
		final Method loadConfigs;                 // ConfigTracker.loadConfigs(ModConfig$Type, Path)   [static, 2-arg]
		final Method unloadConfigs;               // ConfigTracker.unloadConfigs(ModConfig$Type, Path) [static, 2-arg]
		final Object serverConfigType;            // ModConfig$Type.SERVER
		final Class<?> hooks;

		ForgeServerState(ClassLoader cl, Class<?> hooks, Class<?> mcServer) throws Exception {
			this.hooks = hooks;
			this.mcServer = mcServer;

			java.lang.reflect.Field allow = hooks.getDeclaredField("allowLogins");
			allow.setAccessible(true);
			this.allowLogins = (AtomicBoolean) allow.get(null);

			this.currentServer = hooks.getDeclaredField("currentServer");
			this.currentServer.setAccessible(true);

			this.getServerConfigPath = hooks.getDeclaredMethod("getServerConfigPath", mcServer);
			this.getServerConfigPath.setAccessible(true);

			this.setServer = Class.forName("net.minecraftforge.common.util.LogicalSidedProvider", false, cl)
					.getMethod("setServer", Supplier.class);

			Class<?> tracker = Class.forName("net.minecraftforge.fml.config.ConfigTracker", false, cl);
			Class<?> type = Class.forName("net.minecraftforge.fml.config.ModConfig$Type", false, cl);
			this.loadConfigs = tracker.getMethod("loadConfigs", type, Path.class);
			this.unloadConfigs = tracker.getMethod("unloadConfigs", type, Path.class);
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object server = Enum.valueOf((Class) type, "SERVER");
			this.serverConfigType = server;
		}

		/** Forge's own {@code handleServerX(MinecraftServer)}, invoked wholesale. */
		void invokeHook(String name, Object server) throws Exception {
			hooks.getMethod(name, mcServer).invoke(null, server);
		}
	}

	/** NeoForge {@code ServerTickEvent.Pre/Post} → Forge {@code ForgeEventFactory.onPre/PostServerTick(BooleanSupplier, MinecraftServer)}. */
	private static int bridgeServerTick(ClassLoader cl, Object bus, Method addListener, Object prio, Class<?> factory,
			Class<?> mcServer, String kind, String forgeMethod) throws Exception {
		Class<?> base = Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent", false, cl);
		Class<?> evt = Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent$" + kind, false, cl);
		Method hasTime = base.getMethod("hasTime");
		Method getServer = base.getMethod("getServer");
		Method fire = factory.getMethod(forgeMethod, BooleanSupplier.class, mcServer);
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
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/EventBridge] " + forgeMethod + " bridge failed", t);
			}
		};
		addListener.invoke(bus, prio, false, evt, listener);
		return 1;
	}

	/** NeoForge {@code LevelTickEvent.Pre/Post} → Forge {@code ForgeEventFactory.onPre/PostLevelTick(Level, BooleanSupplier)}. */
	private static int bridgeLevelTick(ClassLoader cl, Object bus, Method addListener, Object prio, Class<?> factory,
			Class<?> level, String kind, String forgeMethod) throws Exception {
		Class<?> base = Class.forName("net.neoforged.neoforge.event.tick.LevelTickEvent", false, cl);
		Class<?> evt = Class.forName("net.neoforged.neoforge.event.tick.LevelTickEvent$" + kind, false, cl);
		Method hasTime = base.getMethod("hasTime");
		Method getLevel = base.getMethod("getLevel");
		Method fire = factory.getMethod(forgeMethod, level, BooleanSupplier.class);
		Consumer<Object> listener = neoEvt -> {
			try {
				Object lvl = getLevel.invoke(neoEvt);
				BooleanSupplier haveTime = () -> {
					try {
						return (Boolean) hasTime.invoke(neoEvt);
					} catch (Throwable t) {
						return true;
					}
				};
				fire.invoke(null, lvl, haveTime);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/EventBridge] " + forgeMethod + " bridge failed", t);
			}
		};
		addListener.invoke(bus, prio, false, evt, listener);
		return 1;
	}

	/** A side effect to replay against Forge when a NeoForge lifecycle event fires. {@code server} is the MinecraftServer. */
	@FunctionalInterface
	private interface ServerAction {
		void accept(Object server) throws Throwable;
	}

	/**
	 * Subscribe {@code action} to NeoForge's {@code net.neoforged.neoforge.event.server.<neoEvtName>}, at the same
	 * LOWEST/receiveCanceled=false anchor as every other bridge. Never lets a Forge-side throwable escape into
	 * NeoForge's bus (that would abort the server's own lifecycle for a guest-mod failure).
	 */
	private static int bridgeLifecycle(ClassLoader cl, Object bus, Method addListener, Object prio,
			String neoEvtName, ServerAction action) throws Exception {
		Class<?> neoEvt = Class.forName("net.neoforged.neoforge.event.server." + neoEvtName, false, cl);
		Method getServer = neoEvt.getMethod("getServer");
		Consumer<Object> listener = evt -> {
			try {
				action.accept(getServer.invoke(evt));
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/EventBridge] " + neoEvtName + " bridge failed", unwrap(t));
			}
		};
		addListener.invoke(bus, prio, false, neoEvt, listener);
		return 1;
	}

	/** A poster for Forge's own {@code net.minecraftforge.event.server.<name>}, resolved once. */
	private static ServerAction forgePoster(ClassLoader cl, String name, Class<?> mcServer) throws Exception {
		Class<?> evt = Class.forName("net.minecraftforge.event.server." + name, false, cl);
		java.lang.reflect.Constructor<?> ctor = evt.getConstructor(mcServer);
		Object forgeBus = evt.getField("BUS").get(null);
		Method post = findPost(forgeBus.getClass());
		return server -> {
			if (post != null) post.invoke(forgeBus, ctor.newInstance(server));
		};
	}

	/**
	 * NeoForge {@code ServerAboutToStartEvent} → Forge's {@code currentServer} + {@code LogicalSidedProvider} + Forge's
	 * SERVER configs + a fired Forge {@code ServerAboutToStartEvent}.
	 *
	 * <p>We deliberately do NOT call the full {@code ServerLifecycleHooks.handleServerAboutToStart}: it also runs
	 * Forge's biome/structure MODIFIERS ({@code runModifiers}), which resolve the {@code forge:biome_modifier} datapack
	 * registry — not wired into the merged base's per-world {@code RegistryAccess} (Forge's datapack-registry
	 * registration lost to NeoForge's in the merge; a deeper tier). So do the SAFE subset:
	 * <ul>
	 *   <li>{@code currentServer} — spark et al. read {@code ServerLifecycleHooks.getCurrentServer()} everywhere;</li>
	 *   <li>{@code LogicalSidedProvider.setServer} — otherwise its {@code WORKQUEUE.get(SERVER)} NPEs for Forge mods;</li>
	 *   <li>Forge's SERVER configs — {@code ConfigTracker.loadConfigs} is a Forge-owned static writing
	 *       {@code <world>/serverconfig/<forge-modid>-server.toml}; NeoForge's tracker is a different class, so the two
	 *       cannot collide (they'd only clash if a Forge and a NeoForge mod shared a modid). This must land BEFORE
	 *       {@code ServerStarting}, whose {@code PermissionAPI} reads {@code ForgeConfig.SERVER};</li>
	 *   <li>then fire Forge's own event so Forge mod listeners run.</li>
	 * </ul>
	 * A listener that itself needs biome modifiers is that mod's own (documented) casualty, not a bridge failure.
	 */
	private static int bridgeServerAboutToStart(ClassLoader cl, Object bus, Method addListener, Object prio,
			ForgeServerState forge) throws Exception {
		ServerAction post = forgePoster(cl, "ServerAboutToStartEvent", forge.mcServer);
		return bridgeLifecycle(cl, bus, addListener, prio, "ServerAboutToStartEvent", server -> {
			forge.currentServer.set(null, server); // Forge's getCurrentServer() now works
			forge.setServer.invoke(null, (Supplier<Object>) () -> server);
			try {
				forge.loadConfigs.invoke(null, forge.serverConfigType, forge.getServerConfigPath.invoke(null, server));
			} catch (Throwable t) {
				// Non-fatal: a Forge mod reading an unloaded SERVER config gets Forge's own error, not a dead server.
				ForbricLog.warn("[Forbric/EventBridge] Forge SERVER configs not loaded: " + unwrap(t));
			}
			post.accept(server);
		});
	}

	/**
	 * NeoForge {@code ServerStartingEvent} → Forge's {@code handleServerStarting}, WHOLESALE.
	 *
	 * <p>Wholesale is required, not stylistic: the hook calls {@code PermissionAPI.initializePermissionAPI()}, which is
	 * caller-gated on {@code StackWalker.getCallerClass() == ServerLifecycleHooks} and would silently no-op if we
	 * re-implemented it here (reflection frames are hidden from StackWalker). Its whole body is
	 * {@code LanguageHook} (dedicated only) + {@code PermissionAPI} + posting Forge's {@code ServerStartingEvent}: it
	 * touches no registries and no NeoForge-owned state. If the wholesale call fails (e.g. Forge's {@code FMLEnvironment}
	 * is uninitialised), fall back to at least posting Forge's event so mod listeners still run.
	 */
	private static int bridgeServerStarting(ClassLoader cl, Object bus, Method addListener, Object prio,
			ForgeServerState forge) throws Exception {
		ServerAction post = forgePoster(cl, "ServerStartingEvent", forge.mcServer);
		return bridgeLifecycle(cl, bus, addListener, prio, "ServerStartingEvent", server -> {
			try {
				forge.invokeHook("handleServerStarting", server);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/EventBridge] handleServerStarting failed, posting Forge's event directly: "
						+ unwrap(t));
				post.accept(server);
			}
		});
	}

	/**
	 * NeoForge {@code ServerStartedEvent} → Forge's {@code handleServerStarted} — <b>the login gate</b>.
	 *
	 * <p>Forge's whole body is {@code post(ServerStartedEvent)} then {@code allowLogins.set(true)}. On the merged base
	 * Forge's {@code handleServerLogin} still guards BOTH handshake sites (including the singleplayer in-memory one),
	 * so without this the flag is pinned false and every join is rejected with "Server is still starting!".
	 *
	 * <p>{@code allowLogins.set(true)} is repeated in a {@code finally}: the login gate is infrastructure, not
	 * mod-observable state, so a Forge mod whose {@code ServerStartedEvent} listener throws must not leave the world
	 * permanently unenterable. (Real Forge would have crashed the server outright; the throwable is still logged.)
	 */
	private static int bridgeServerStarted(ClassLoader cl, Object bus, Method addListener, Object prio,
			ForgeServerState forge) throws Exception {
		return bridgeLifecycle(cl, bus, addListener, prio, "ServerStartedEvent", server -> {
			try {
				forge.invokeHook("handleServerStarted", server);
			} finally {
				forge.allowLogins.set(true);
			}
		});
	}

	/**
	 * NeoForge {@code ServerStoppingEvent} → Forge's {@code handleServerStopping}, WHOLESALE (its body is exactly
	 * {@code allowLogins.set(false)} + posting Forge's event — wholly Forge-private). The {@code finally} re-gates a
	 * second world load in the same client JVM even if a mod listener throws.
	 */
	private static int bridgeServerStopping(ClassLoader cl, Object bus, Method addListener, Object prio,
			ForgeServerState forge) throws Exception {
		return bridgeLifecycle(cl, bus, addListener, prio, "ServerStoppingEvent", server -> {
			try {
				forge.invokeHook("handleServerStopping", server);
			} finally {
				forge.allowLogins.set(false);
			}
		});
	}

	/**
	 * NeoForge {@code ServerStoppedEvent} → a hand-rolled SAFE SUBSET of Forge's {@code handleServerStopped}.
	 *
	 * <p>Replayed: post Forge's event, clear {@code currentServer}, clear {@code LogicalSidedProvider} (matching Forge,
	 * which passes a null supplier), unload Forge's SERVER configs.
	 *
	 * <p><b>Skipped deliberately:</b>
	 * <ul>
	 *   <li>{@code GameData.revertToFrozen()} — Forge's GameData backs the LIVE merged registries, and the merged
	 *       {@code MinecraftServer} already calls NeoForge's {@code RegistryManager.revertToFrozen}. Replaying Forge's
	 *       would double-revert shared registries. (Its guard {@code !server.isDedicatedServer()} is precisely the
	 *       singleplayer path we now exercise, so this is not hypothetical.)</li>
	 *   <li>{@code exitLatch.countDown()} — Forge's latch has no awaiter here; merged {@code Minecraft} calls
	 *       NeoForge's {@code handleExit}.</li>
	 * </ul>
	 */
	private static int bridgeServerStopped(ClassLoader cl, Object bus, Method addListener, Object prio,
			ForgeServerState forge) throws Exception {
		ServerAction post = forgePoster(cl, "ServerStoppedEvent", forge.mcServer);
		return bridgeLifecycle(cl, bus, addListener, prio, "ServerStoppedEvent", server -> {
			Path configPath = null;
			try {
				configPath = (Path) forge.getServerConfigPath.invoke(null, server);
			} catch (Throwable ignored) {
				// best effort: without the path we simply skip the config unload below
			}
			post.accept(server);
			forge.currentServer.set(null, null);
			forge.setServer.invoke(null, (Object) null); // Forge itself passes a null Supplier here
			if (configPath != null) {
				try {
					forge.unloadConfigs.invoke(null, forge.serverConfigType, configPath);
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/EventBridge] Forge SERVER configs not unloaded: " + unwrap(t));
				}
			}
		});
	}

	/** The single-argument {@code post(Event)} method on a Forge EventBus 7 bus object. */
	private static Method findPost(Class<?> busClass) {
		for (Method m : busClass.getMethods()) {
			if (m.getName().equals("post") && m.getParameterCount() == 1) return m;
		}
		return null;
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}
}
