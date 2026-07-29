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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.util.ForbricLog;

/**
 * Seeds the MINIMUM genuine-loader identity state that the merged base's patched-vanilla {@code <clinit>}s read,
 * WITHOUT running any genuine loader lifecycle.
 *
 * <p>The merged base is vanilla woven with Forge + NeoForge patches. Even a zero-mod boot trips over static
 * initializers that ask the genuine loaders "who am I?" — e.g. {@code SharedConstants.<clinit>} calls
 * {@code FMLEnvironment.isProduction()} → {@code FMLLoader.getCurrent()}, which throws
 * {@code "There is no current FML Loader"} if no {@code FMLLoader} instance exists. Normally
 * BootstrapLauncher/ModLauncher would have created one; the kernel does not run them.
 *
 * <p>This class establishes just the identity: an {@code FMLLoader} instance that answers dist / production /
 * classloader, made "current". It runs NO discovery, builds NO module layer, sorts NO mods — it only makes the
 * merged base's environment queries return sane answers. Everything is done reflectively THROUGH the kernel's
 * transforming loader so the seeded {@code FMLLoader} shares the exact class identity the game classes will read.
 *
 * <p>This is passive seeding, not lifecycle driving: it is the kernel-owned equivalent of "the environment exists",
 * the boundary the plan draws around universal jars as passive ABI carriers.
 */
public final class PassiveSeeder {
	private PassiveSeeder() {
	}

	/** Seeds every genuine-loader identity the merged base needs before the game entry runs. Best-effort per family. */
	public static void seedAll(ClassLoader gameLoader, Path gameDir, boolean production) {
		seedAll(gameLoader, gameDir, production, false);
	}

	/**
	 * {@code client} selects the seeded {@code Dist} (CLIENT vs DEDICATED_SERVER). It is load-bearing: with the wrong
	 * dist, NeoForge's client code (and the integrated server's connection handshake) treats the client as a
	 * dedicated server — e.g. the local player's MODDED connection is rejected "Server is still starting".
	 */
	public static void seedAll(ClassLoader gameLoader, Path gameDir, boolean production, boolean client) {
		seedNeoForgeLoader(gameLoader, gameDir, production, client);
		seedNeoForgeModList(gameLoader);
		seedNeoForgePaths(gameLoader, gameDir);
		seedForgeFmlLoader(gameLoader, gameDir, production);
		// NOTE: NeoForge baseline-registry registration is NOT done here — NeoForgeRegistriesSetup.<clinit> touches
		// game registries and throws "Not bootstrapped" pre-Main. It runs post-Bootstrap via KernelLifecycle
		// (the redirected ServerModLoader.load window). See KernelLifecycle.onServerModLoading.
		// Traditional-Forge FMLEnvironment (static dist/production) is seeded lazily if/when a Forge-patched
		// <clinit> demands it; added here once M1 boot surfaces that landmine.
	}

	/**
	 * Initializes NeoForge {@code FMLPaths} (GAMEDIR/CONFIGDIR/MODSDIR/…) so {@code FMLPaths.<X>.get()} returns a
	 * real path instead of null. {@code ConfigTracker.<clinit>} reads {@code FMLPaths.CONFIGDIR.get()} during the
	 * server-about-to-start hook. Pure path setup, no lifecycle.
	 */
	public static void seedNeoForgePaths(ClassLoader gameLoader, Path gameDir) {
		try {
			Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths", false, gameLoader);
			Method load = fmlPaths.getMethod("loadAbsolutePaths", Path.class);
			load.invoke(null, gameDir.toAbsolutePath());
			ForbricLog.debug("[Forbric/Seed] initialized NeoForge FMLPaths at %s", gameDir.toAbsolutePath());
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] NeoForge FMLPaths not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not init FMLPaths", unwrap(t));
		}
	}

	/**
	 * Seeds an EMPTY NeoForge {@code ModList} so {@code ModList.get()} returns an empty list instead of null. The
	 * merged base reads it early (e.g. {@code ResourcePackLoader.findResourcePacks} → {@code ModList.get().getModFiles()}
	 * when building the server pack repository). Zero mods = empty list. No-op if already present/absent.
	 */
	public static void seedNeoForgeModList(ClassLoader gameLoader) {
		try {
			Class<?> modList = Class.forName("net.neoforged.fml.ModList", false, gameLoader);
			Method get = modList.getMethod("get");
			if (get.invoke(null) != null) return;
			// of(modFiles, modInfos) constructs and installs the singleton INSTANCE.
			Method of = modList.getMethod("of", List.class, List.class);
			of.invoke(null, List.of(), List.of());
			Object instance = get.invoke(null);
			if (instance == null) {
				Field instanceField = modList.getDeclaredField("INSTANCE");
				instanceField.setAccessible(true);
				instance = of.invoke(null, List.of(), List.of());
				instanceField.set(null, instance);
			}
			// Populate mods/indexedMods/sortedContainers (null until mod loading) so iteration (e.g.
			// ModList.forEachModInOrder from ResourcePackLoader → ModLoader.postEvent) doesn't NPE. Zero mods.
			Method setLoadedMods = modList.getDeclaredMethod("setLoadedMods", List.class);
			setLoadedMods.setAccessible(true);
			setLoadedMods.invoke(instance, List.of());
			ForbricLog.debug("[Forbric/Seed] seeded empty NeoForge ModList (zero mods)");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] NeoForge ModList not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed empty ModList", unwrap(t));
		}
	}

	/**
	 * Makes a NeoForge {@code FMLLoader} "current" (dist = DEDICATED_SERVER for the server), so
	 * {@code FMLLoader.getCurrent()} / {@code FMLEnvironment.isProduction()} answer instead of throwing.
	 * No-ops if a loader is already current or the class is absent.
	 */
	public static void seedNeoForgeLoader(ClassLoader gameLoader, Path gameDir, boolean production) {
		seedNeoForgeLoader(gameLoader, gameDir, production, false);
	}

	public static void seedNeoForgeLoader(ClassLoader gameLoader, Path gameDir, boolean production, boolean client) {
		try {
			Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader", false, gameLoader);

			Method getCurrentOrNull = fmlLoader.getDeclaredMethod("getCurrentOrNull");
			getCurrentOrNull.setAccessible(true);
			if (getCurrentOrNull.invoke(null) != null) {
				ForbricLog.debug("[Forbric/Seed] NeoForge FMLLoader already current — not re-seeding");
				return;
			}

			Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", false, gameLoader);
			String distName = client ? "CLIENT" : "DEDICATED_SERVER";
			Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), distName);

			// private FMLLoader(ClassLoader, String[], Dist, boolean production, Path gameDir)
			Constructor<?> ctor = fmlLoader.getDeclaredConstructor(
					ClassLoader.class, String[].class, distClass, boolean.class, Path.class);
			ctor.setAccessible(true);
			Object loader = ctor.newInstance(gameLoader, new String[0], dist, production, gameDir);

			// The ctor may or may not self-register; makeCurrent() (guarded) ensures getCurrent() resolves.
			if (getCurrentOrNull.invoke(null) == null) {
				Method makeCurrent = fmlLoader.getDeclaredMethod("makeCurrent");
				makeCurrent.setAccessible(true);
				makeCurrent.invoke(loader);
			}

			seedEmptyLoadingModList(gameLoader, fmlLoader, loader);

			ForbricLog.info("[Forbric/Seed] NeoForge FMLLoader seeded (dist=%s, production=%s) — "
					+ "environment identity only, no lifecycle", distName, production);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] NeoForge FMLLoader not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed NeoForge FMLLoader identity", unwrap(t));
		}
	}

	/**
	 * Seeds an EMPTY {@code LoadingModList} on the FMLLoader so {@code FMLLoader.getLoadingModList()} returns an
	 * empty list instead of throwing "The loading mod list isn't built yet" — the merged base reads it from
	 * {@code FeatureFlags.<clinit>} (via {@code FeatureFlagLoader.loadModdedFlags}) during {@code Bootstrap.bootStrap}.
	 * Zero mods = empty list. This seeds data, not lifecycle.
	 */
	private static void seedEmptyLoadingModList(ClassLoader gameLoader, Class<?> fmlLoader, Object loaderInstance) {
		try {
			Field field = fmlLoader.getDeclaredField("loadingModList");
			field.setAccessible(true);
			if (field.get(loaderInstance) != null) return; // already built

			Class<?> lmlCls = Class.forName("net.neoforged.fml.loading.LoadingModList", false, gameLoader);
			// of(modFiles, gameLibraries, plugins, modInfos, issues, dependencies) — all empty for zero mods.
			Method of = lmlCls.getMethod("of", List.class, List.class, List.class, List.class, List.class, Map.class);
			Object empty = of.invoke(null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
			field.set(loaderInstance, empty);
			ForbricLog.debug("[Forbric/Seed] seeded empty NeoForge LoadingModList (zero mods)");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed empty LoadingModList", unwrap(t));
		}
	}

	/**
	 * Registers NeoForge's BASELINE registries (neoforge:fluid_type, biome_modifier serializers, …) into the ROOT
	 * registry so the merged base's NeoForge-patched vanilla code can resolve them. The merged base references
	 * these even with zero mods (e.g. worldgen resolves {@code DeferredHolder{neoforge:fluid_type/minecraft:empty}}).
	 *
	 * <p>Mechanism: {@code NeoForgeRegistriesSetup.registerRegistries(NewRegistryEvent)} is NeoForge's own handler
	 * that fills the event with all {@code NeoForgeRegistries.*}; {@code NewRegistryEvent.fill()} then registers
	 * them to the root. The kernel drives this handler directly (no bus, no mod dispatch — zero mods) at the
	 * pre-freeze window. This is the first slice of native ecosystem registration (M3), not lifecycle driving.
	 */
	public static void seedNeoForgeRegistries(ClassLoader gameLoader) {
		try {
			// Ensure the static NeoForgeRegistries.* registry objects are created first.
			Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistries", true, gameLoader);

			Class<?> setupCls = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistriesSetup", false, gameLoader);
			Class<?> eventCls = Class.forName("net.neoforged.neoforge.registries.NewRegistryEvent", false, gameLoader);

			Constructor<?> eventCtor = eventCls.getDeclaredConstructor();
			eventCtor.setAccessible(true);
			Object event = eventCtor.newInstance();

			Method registerRegistries = setupCls.getDeclaredMethod("registerRegistries", eventCls);
			registerRegistries.setAccessible(true);
			registerRegistries.invoke(null, event);

			Method fill = eventCls.getDeclaredMethod("fill");
			fill.setAccessible(true);
			fill.invoke(event);

			ForbricLog.info("[Forbric/Seed] registered NeoForge baseline registries into the root (native, no lifecycle)");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] NeoForge registry setup not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not register NeoForge baseline registries", unwrap(t));
		}
	}

	/**
	 * Seeds traditional Forge's {@code LoadingModListImpl.temp} with an empty {@code ModSorter$State} so
	 * {@code LoadingModList.get()} doesn't NPE. Forge's {@code ServerStatusPing} touches
	 * {@code ModList.<clinit>} → {@code LoadingModList.getModFiles()} right after {@code Done}. The NeoForge
	 * LoadingModList was seeded separately; this is the traditional-Forge twin (avoids the poisoned-LazyInit wall).
	 */
	public static void seedForgeLoadingModList(ClassLoader gameLoader) {
		try {
			Class<?> stateCls = Class.forName("net.minecraftforge.fml.loading.ModSorter$State", false, gameLoader);
			Constructor<?> stateCtor = stateCls.getDeclaredConstructor(List.class, List.class);
			stateCtor.setAccessible(true);
			Object emptyState = stateCtor.newInstance(List.of(), List.of());

			Class<?> lmlImpl = Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl", true, gameLoader);
			Field temp = lmlImpl.getDeclaredField("temp");
			temp.setAccessible(true);
			if (temp.get(null) == null) temp.set(null, emptyState);
			ForbricLog.debug("[Forbric/Seed] seeded empty traditional-Forge LoadingModList (zero mods)");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge LoadingModListImpl not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed traditional-Forge LoadingModList", unwrap(t));
		}

		// Traditional-Forge ModList keeps mods/indexedMods/sortedContainers as STATIC fields, null until mod
		// loading. ServerStatusPing → ModList.forEachModContainer iterates indexedMods → NPE. Seed empties.
		try {
			Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", true, gameLoader);
			setStaticIfNull(modList, "mods", List.of());
			setStaticIfNull(modList, "indexedMods", Map.of());
			setStaticIfNull(modList, "sortedContainers", List.of());
			ForbricLog.debug("[Forbric/Seed] seeded empty traditional-Forge ModList collections (zero mods)");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge ModList not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed traditional-Forge ModList collections", unwrap(t));
		}
	}

	/**
	 * Seeds traditional-Forge {@code FMLLoader}'s static identity ({@code gamePath}, {@code dist}, {@code production},
	 * {@code naming}) so its accessors answer instead of returning null. e.g. {@code UsernameCache.<clinit>} (touched
	 * by {@code MinecraftForge.initialize} during ForgeMod construction) resolves {@code FMLLoader.getGamePath()}.
	 * Identity only, no lifecycle.
	 */
	public static void seedForgeFmlLoader(ClassLoader gameLoader, Path gameDir, boolean production) {
		try {
			Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, gameLoader);
			setStaticIfNull(fmlLoader, "gamePath", gameDir.toAbsolutePath());
			setStaticIfNull(fmlLoader, "naming", "mojmap");
			Field productionField = fmlLoader.getDeclaredField("production");
			productionField.setAccessible(true);
			productionField.setBoolean(null, production);
			Field distField = fmlLoader.getDeclaredField("dist");
			distField.setAccessible(true);
			if (distField.get(null) == null) {
				Class<?> distClass = Class.forName("net.minecraftforge.api.distmarker.Dist", false, gameLoader);
				distField.set(null, Enum.valueOf(distClass.asSubclass(Enum.class), "DEDICATED_SERVER"));
			}

			// Traditional-Forge FMLPaths + FMLConfig (ForgeMod's config registration reads FMLConfig; ConfigFileType
			// Handler.<clinit> NPEs if FMLConfig.load() hasn't populated its backing file config).
			Class<?> fmlPaths = Class.forName("net.minecraftforge.fml.loading.FMLPaths", false, gameLoader);
			fmlPaths.getMethod("loadAbsolutePaths", Path.class).invoke(null, gameDir.toAbsolutePath());
			Class<?> fmlConfig = Class.forName("net.minecraftforge.fml.loading.FMLConfig", false, gameLoader);
			fmlConfig.getMethod("load").invoke(null);
			ForbricLog.debug("[Forbric/Seed] seeded traditional-Forge FMLLoader identity + FMLPaths + FMLConfig");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge FMLLoader not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed traditional-Forge FMLLoader identity", unwrap(t));
		}
	}

	private static void setStaticIfNull(Class<?> owner, String field, Object value) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		if (f.get(null) == null) f.set(null, value);
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}
}
