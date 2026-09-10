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

package net.forbric.loader.impl.forge.minecraftforge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.loader.impl.forge.runtime.ForbricFabricMains;
import net.forbric.loader.impl.util.ForbricLog;

/**
 * The Fabric-content window for the REAL Forge lifecycle: invoked (reflectively) by the Forbric bridge mod's
 * {@code RegisterEvent} listener, i.e. inside Forge's genuine {@code UNFREEZE_DATA .. FREEZE_DATA} span where
 * {@code GameData.postRegisterEvents} is walking the registries. Vanilla registries are unfrozen there, but each
 * Forge {@code NamespacedWrapper} still carries its {@code locked} flag — plain {@code Registry.register} (what
 * Fabric mods call) refuses. So: unlock every wrapper, run the Fabric {@code main} entrypoints, relock.
 *
 * <p>The vanilla hook points ({@code Hooks.startClient/startServer}) skip the {@code main} entrypoints when
 * {@code -Dforbric.fabricMainDeferred=true} (substrate patch 0005) precisely so this window is their only
 * invoker. In headless-register mode this class no-ops — the headless driver runs the window itself.
 */
public final class ForbricFabricWindow {
	private static final AtomicBoolean GAME_BUS_STARTED = new AtomicBoolean();

	private ForbricFabricWindow() {
	}

	/** Reflective entrypoint for the bridge mod. Safe to call from every RegisterEvent; runs at most once. */
	public static void openWindowAndRunFabricMains() {
		if (Boolean.getBoolean(ForbricMinecraftForgeRuntime.HEADLESS_REGISTER)) return; // headless drives mains itself
		if (!Boolean.getBoolean("forbric.fabricMainDeferred")) return; // hooks will run mains normally

		// Forbric: the bridge RegisterEvent fires during GameData.postRegisterEvents, i.e. AFTER every @Mod (incl.
		// ForgeMod -> ForgeInternalHandler.register) is constructed and its listeners are on the Forge GAME bus.
		// That bus (BusGroup.DEFAULT) is never armed under Forbric (Forge's ModLauncher/FMLLoader boot is bypassed),
		// so start it here -- BEFORE the RAN guard, so it still runs when the mains already ran at Hooks.startClient.
		startDefaultGameBus(ForbricFabricWindow.class.getClassLoader());

		// Shared, process-wide guard: on the tri-in-one merged base NeoForge's window may already have run the
		// Fabric mains (see ForbricFabricMains) — don't re-run them (double registration).
		if (ForbricFabricMains.alreadyRan()) return;

		ClassLoader cl = ForbricFabricWindow.class.getClassLoader();
		List<Object> unlocked = List.of();
		try {
			unlocked = setRegistriesLocked(cl, false);
			ForbricLog.info("[Forbric/Bridge] registration window open (" + unlocked.size()
					+ " Forge registry wrappers unlocked) - running Fabric main entrypoints");

			ForbricFabricMains.runOnce();
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Bridge] Fabric window failed", t);
		} finally {
			try {
				relock(unlocked);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Bridge] relock failed", t);
			}
		}
	}

	/**
	 * Client-hook variant of the window: keep the Forge registry wrappers unlocked across the WHOLE Fabric mod-init
	 * on the client. Fabric mods register content in both {@code "main"} and {@code "client"} entrypoints (e.g.
	 * particle types in {@code onInitializeClient}), and the Forge base locks the registries — so run the deferred
	 * {@code "main"} entrypoints (once, shared {@link #RAN} guard) and then the supplied client-init inside one
	 * unlock/relock span. A client-init failure PROPAGATES (a real mod error); the registries are always relocked.
	 */
	public static void runClientInitUnlocked(Runnable clientInit) {
		if (Boolean.getBoolean(ForbricMinecraftForgeRuntime.HEADLESS_REGISTER)
				|| !Boolean.getBoolean("forbric.fabricMainDeferred")) {
			clientInit.run(); // no Forge registration window in play — run the client stage unchanged
			return;
		}

		ClassLoader cl = ForbricFabricWindow.class.getClassLoader();
		List<Object> unlocked = List.of();
		boolean unlockedOk = false;
		try {
			unlocked = setRegistriesLocked(cl, false);
			unlockedOk = true;
			ForbricLog.info("[Forbric/Bridge] client registration window open (" + unlocked.size()
					+ " Forge registry wrappers unlocked) - running deferred Fabric main + client entrypoints");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Bridge] client window: could not unlock registries", t);
		}

		try {
			ForbricFabricMains.runOnce(); // shared guard: at most once across both ecosystems' windows
			clientInit.run(); // client entrypoints, still unlocked; genuine failures propagate to the caller
		} finally {
			if (unlockedOk) {
				try {
					relock(unlocked);
				} catch (Throwable t) {
					ForbricLog.error("[Forbric/Bridge] client window relock failed", t);
				}
			}
		}
	}

	/**
	 * Ensure Forge's own {@code ForgeInternalHandler} game-bus listeners are subscribed — runs once, from the bridge
	 * {@code RegisterEvent} window (i.e. AFTER every {@code @Mod} CONSTRUCT).
	 *
	 * <p>Root cause (verified in the real Forbric runtime): {@code ForgeInternalHandler} — which lazily builds
	 * {@code LootModifierManager} in {@code onResourceReload(AddReloadListenerEvent)} and also handles entity-join,
	 * permissions, reload listeners, etc. — is registered on the game bus from {@code ForgeMod.<init>}. When Forbric
	 * drives registration without constructing {@code ForgeMod} (the headless/registration-window path), those
	 * listeners are never subscribed, so {@code AddReloadListenerEvent} dispatches to nothing and
	 * {@code LootModifierManager} stays null → crash on the first loot roll. (Isolated testing confirmed the eventbus
	 * dispatches fine without any {@code startup()}; the missing piece is the registration itself.)
	 *
	 * <p>So: if {@code AddReloadListenerEvent.BUS} has no listeners, invoke {@code ForgeInternalHandler.register()}
	 * (self-registers via its own {@code Lookup}). Idempotent — when {@code ForgeMod} was genuinely constructed
	 * (real {@code ClientModLoader}/{@code ServerModLoader}) the bus already has the listeners and this is a no-op.
	 * The defensive {@code startup()} re-arms the bus only if something latched it dead.
	 */
	public static void startDefaultGameBus(ClassLoader cl) {
		if (!GAME_BUS_STARTED.compareAndSet(false, true)) return;

		try {
			Class<?> busGroupCls = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
			Object dflt = busGroupCls.getField("DEFAULT").get(null);
			busGroupCls.getMethod("startup").invoke(dflt); // defensive: re-arms only if a shutdown latched it dead

			Class<?> evtCls = Class.forName("net.minecraftforge.event.AddReloadListenerEvent", false, cl);
			Object bus = evtCls.getField("BUS").get(null);
			java.lang.reflect.Method hasListeners = bus.getClass().getMethod("hasListeners");
			boolean before = (Boolean) hasListeners.invoke(bus);
			ForbricLog.info("[Forbric/Bridge] Forge internal handler probe: AddReloadListenerEvent.BUS hasListeners=" + before);

			if (!before) {
				Class<?> fih = Class.forName("net.minecraftforge.common.ForgeInternalHandler", false, cl);
				java.lang.reflect.Method register = fih.getDeclaredMethod("register");
				register.setAccessible(true);
				register.invoke(null);
				boolean after = (Boolean) hasListeners.invoke(bus);
				ForbricLog.info("[Forbric/Bridge] registered ForgeInternalHandler on the Forge game bus"
						+ " (LootModifierManager/entity/permission/reload handlers) -> hasListeners=" + after);
			}
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Bridge] Forge game-bus handler setup failed", t);
		}
	}

	/**
	 * Public entry for the tri-in-one merged base: unlock every Forge {@code NamespacedWrapper.locked} gate,
	 * returning those toggled (relock them with {@link #relockForgeRegistries}). NeoForge's registration window
	 * calls this so plain {@code Registry.register} (Fabric content) succeeds on the Forge-wrapped registries even
	 * though NeoForge's own unfreeze doesn't touch Forge's {@code locked} flag. Returns an empty list off the
	 * merged base (no Forge wrappers). Never throws — a compat helper must not abort the caller's window.
	 */
	public static List<Object> unlockForgeRegistries(ClassLoader cl) {
		try {
			return setRegistriesLocked(cl, false);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Bridge] could not unlock Forge registry wrappers for the Fabric window", t);
			return List.of();
		}
	}

	/** Relock the wrappers {@link #unlockForgeRegistries} opened. */
	public static void relockForgeRegistries(List<Object> wrappers) {
		try {
			relock(wrappers);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Bridge] could not relock Forge registry wrappers after the Fabric window", t);
		}
	}

	/** Toggle {@code NamespacedWrapper.locked} on every BuiltInRegistries registry; returns those toggled. */
	private static List<Object> setRegistriesLocked(ClassLoader cl, boolean locked) throws Exception {
		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		List<Object> toggled = new ArrayList<>();

		for (Field f : builtin.getFields()) {
			if (!registryCls.isAssignableFrom(f.getType())) continue;
			Object reg = f.get(null);
			Field lockedField = findField(reg.getClass(), "locked");
			if (lockedField == null) continue; // not a Forge NamespacedWrapper
			lockedField.setAccessible(true);

			if (lockedField.getBoolean(reg) != locked) {
				lockedField.setBoolean(reg, locked);
				toggled.add(reg);
			}
		}

		return toggled;
	}

	private static void relock(List<Object> wrappers) throws Exception {
		for (Object reg : wrappers) {
			Field lockedField = findField(reg.getClass(), "locked");
			lockedField.setAccessible(true);
			lockedField.setBoolean(reg, true);
		}
	}

	private static Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				// try superclass
			}
		}

		return null;
	}
}
