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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import net.forbric.kernel.fabric.FabricModDiscovery;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.mixin.MergedBaseMixinCompat;
import net.forbric.kernel.util.ForbricLog;

/**
 * Drives the Fabric ecosystem natively: discovery &rarr; {@link KernelFabricLoader} &rarr; entrypoints.
 *
 * <p>No Fabric Loader code runs. The kernel discovers {@code fabric.mod.json}s itself, builds the mod-facing
 * {@code FabricLoader} view, and invokes {@code ModInitializer.onInitialize()} at the point the registries are
 * writable — the same native registration window in which {@link KernelLifecycle} fires the Forge families'
 * {@code RegisterEvent}s. A Fabric mod's {@code onInitialize} calls {@code Registry.register(...)} directly, so
 * it must run inside that unfreeze/freeze span; running it before {@code Bootstrap.bootStrap} would touch
 * registries that do not exist yet, and after the freeze would throw.
 *
 * <p>The {@code main} entrypoints are guarded to run at most once per process, mirroring the old weld's shared
 * {@code ForbricFabricMains} guard: once the tri-in-one client/server paths both open registration windows, only
 * the first may run them.
 */
public final class KernelFabricEcosystem {
	/**
	 * The Fabric Loader API level the kernel implements. Vendored from the fabric-loader 0.19.3 API sources; the
	 * shipped ecosystem requires {@code >=0.18.4}. This is NOT a claim that Fabric Loader is present — no
	 * {@code net.fabricmc.loader.impl} class exists in this process.
	 */
	public static final String FABRIC_LOADER_API_LEVEL = "0.19.3";

	private static final AtomicBoolean MAINS_RAN = new AtomicBoolean();
	private static final AtomicBoolean CLIENTS_RAN = new AtomicBoolean();

	private static volatile KernelFabricLoader loader;

	private KernelFabricEcosystem() {
	}

	/** Whether any Fabric mod was discovered (so the rest of the kernel can skip Fabric work entirely). */
	public static boolean active() {
		return loader != null && !loader.getAllMods().isEmpty();
	}

	/**
	 * Discovers Fabric mods under {@code modsDir}, creates the process-wide {@code FabricLoader}, and returns the
	 * jars (mods + their extracted JiJ children) that must join the game class loader.
	 */
	public static List<Path> discover(EnvType envType, Path gameDir, String gameVersion, String[] launchArgs) {
		Path cacheDir = gameDir.resolve(".forbric-kernel").resolve("jij");
		FabricModDiscovery discovery = new FabricModDiscovery(envType, cacheDir);
		discovery.discover(gameDir.resolve("mods"));

		KernelFabricLoader fabric = KernelFabricLoader.create(envType, gameDir, gameDir.resolve("config"),
				launchArgs, gameVersion);

		fabric.register(new KernelModContainer(
				KernelModMetadata.builtin("minecraft", gameVersion, "Minecraft"), null, null));
		fabric.register(new KernelModContainer(
				KernelModMetadata.builtin("java", String.valueOf(Runtime.version().feature()), "Java"), null, null));
		fabric.register(new KernelModContainer(
				KernelModMetadata.builtin("fabricloader", FABRIC_LOADER_API_LEVEL, "Fabric Loader (Forbric kernel)"),
				null, null));

		// A universal jar also ships a fabric.mod.json; register it as a Fabric mod only if Fabric OWNS the jar,
		// otherwise the same mod runs its Fabric entrypoints on top of the Forge/NeoForge @Mod that already claimed
		// it (see MultiLoaderArbiter). The jar stays on the classpath either way — the owning family needs its
		// classes; only the Fabric-side registration (and with it the entrypoints) is skipped.
		int suppressed = 0;
		for (KernelModContainer container : discovery.getContainers()) {
			Path jar = container.getJar();
			if (jar != null && MultiLoaderArbiter.suppressedFor(jar, MultiLoaderArbiter.Ecosystem.FABRIC)) {
				suppressed++;
				continue;
			}
			fabric.register(container);
		}
		if (suppressed > 0) {
			ForbricLog.info("[Forbric/Fabric] skipped %d Fabric registration(s) for jars a Forge family owns", suppressed);
		}

		fabric.freeze();
		loader = fabric;

		List<Path> jars = discovery.getClasspathJars();
		ForbricLog.info("[Forbric/Fabric] discovered %d Fabric mod(s) in %d jar(s) (incl. nested)",
				discovery.getContainers().size(), jars.size());

		return jars;
	}

	/** Points entrypoint resolution at the transforming loader. Must precede any entrypoint invocation. */
	public static void bindGameLoader(ClassLoader gameLoader) {
		if (loader != null) loader.setGameLoader(gameLoader);
	}

	/**
	 * Every discovered Fabric mod's declared access widener ({@code .classtweaker}) file contents, in mod order.
	 *
	 * <p>Read from each mod's own jar. A declared-but-missing file is a mod packaging error: warn and skip rather
	 * than fail the launch, since the mixins that need it will fail loudly on their own.
	 */
	public static List<byte[]> accessWideners() {
		if (loader == null) return List.of();

		List<byte[]> files = new ArrayList<>();

		for (ModContainer mod : loader.getAllMods()) {
			if (!(mod instanceof KernelModContainer)) continue;

			KernelModContainer container = (KernelModContainer) mod;
			String path = container.getMetadata().getAccessWidener();
			if (path == null || path.isEmpty()) continue;

			try (java.util.jar.JarFile jar = new java.util.jar.JarFile(container.getJar().toFile())) {
				java.util.zip.ZipEntry entry = jar.getEntry(path);

				if (entry == null) {
					ForbricLog.warn("[Forbric/Access] %s declares accessWidener '%s' which is not in its jar",
							container.getMetadata().getId(), path);
					continue;
				}

				try (java.io.InputStream in = jar.getInputStream(entry)) {
					files.add(in.readAllBytes());
				}
			} catch (Exception e) {
				ForbricLog.warn("[Forbric/Access] could not read accessWidener of %s: %s",
						container.getMetadata().getId(), String.valueOf(e));
			}
		}

		return files;
	}

	/**
	 * Every discovered Fabric mod's mixin configs that apply to the running side, in mod order.
	 *
	 * <p>A config declared {@code {"config": "...", "environment": "client"}} is dropped on a dedicated server —
	 * its mixins target client-only classes that do not exist here, and registering it would fail the whole config.
	 */
	public static List<String> mixinConfigs() {
		if (loader == null) return List.of();

		EnvType envType = loader.getEnvironmentType();
		List<String> configs = new ArrayList<>();

		for (ModContainer mod : loader.getAllMods()) {
			if (!(mod instanceof KernelModContainer)) continue;

			for (KernelModMetadata.MixinConfigDecl decl : ((KernelModContainer) mod).getMetadata().getMixinConfigs()) {
				if (!decl.environment().matches(envType)) continue;

				if (isDisabled(decl.config())) {
					ForbricLog.warn("[Forbric/Mixin] mixin config %s DISABLED by -Dforbric.disableMixinConfigs — "
							+ "that module's mixins will not apply", decl.config());
					continue;
				}

				configs.add(decl.config());
			}
		}

		return configs;
	}

	/**
	 * Whether {@code config} must not be registered: either it is on the built-in merged-base incompatibility list
	 * ({@link MergedBaseMixinCompat#DISABLED_CONFIGS}) or {@code -Dforbric.disableMixinConfigs} names it (csv; a
	 * trailing {@code *} is a prefix glob). Registering nothing for a config is stronger than suppressing
	 * individual mixins: it takes a whole module's mixins out of the picture, which is what an all-or-nothing
	 * module (and bisecting) needs.
	 *
	 * <p>{@code -Dforbric.enableMixinConfigs} (csv) forces a config back ON over the built-in list. That list is a
	 * record of what was true when each entry was measured, and the merged base keeps changing underneath it — so
	 * re-testing an entry has to be one flag, not an edit-and-rebuild. It overrides only the built-in list, never an
	 * explicit {@code -Dforbric.disableMixinConfigs} on the same command line.
	 */
	private static boolean isDisabled(String config) {
		if (MergedBaseMixinCompat.enabled() && MergedBaseMixinCompat.DISABLED_CONFIGS.contains(config)
				&& !isForceEnabled(config)) {
			return true;
		}

		String csv = System.getProperty("forbric.disableMixinConfigs");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;

			if (entry.endsWith("*")) {
				if (config.startsWith(entry.substring(0, entry.length() - 1))) return true;
			} else if (config.equals(entry)) {
				return true;
			}
		}

		return false;
	}

	/** Whether {@code -Dforbric.enableMixinConfigs} (csv) names {@code config}, forcing it on over the built-in list. */
	private static boolean isForceEnabled(String config) {
		String csv = System.getProperty("forbric.enableMixinConfigs");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			if (config.equals(raw.trim())) {
				ForbricLog.warn("[Forbric/Mixin] mixin config %s FORCE-ENABLED by -Dforbric.enableMixinConfigs over "
						+ "the built-in merged-base incompatibility list — expect the recorded breakage", config);
				return true;
			}
		}
		return false;
	}

	/** Publishes the game object (the {@code MinecraftServer}) for {@code FabricLoader.getGameInstance()}. */
	public static void setGameInstance(Object gameInstance) {
		if (loader != null) loader.setGameInstance(gameInstance);
	}

	/**
	 * Runs the {@code preLaunch} entrypoints, before any game class is loaded. Per Fabric's contract these must
	 * not touch game classes; the kernel does not enforce that, but it does run them at the correct point.
	 */
	public static void runPreLaunch() {
		if (loader == null || !loader.hasEntrypoints("preLaunch")) return;

		for (EntrypointContainer<PreLaunchEntrypoint> c
				: loader.getEntrypointContainers("preLaunch", PreLaunchEntrypoint.class)) {
			String id = c.getProvider().getMetadata().getId();

			try {
				c.getEntrypoint().onPreLaunch();
				ForbricLog.info("[Forbric/Fabric] preLaunch entrypoint of %s", id);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Fabric] preLaunch entrypoint of " + id + " failed", t);
			}
		}
	}

	/**
	 * Runs every Fabric {@code main} entrypoint, plus the {@code server} one on a dedicated server, exactly once per
	 * process. Must be called with the registries unfrozen — this is where mods register content.
	 *
	 * <p>The {@code client} entrypoint is NOT run here: Fabric fires it later, from inside {@code Minecraft.<init>},
	 * where {@code Minecraft.getInstance()} is already non-null but {@code Options} is not yet built. Mods rely on
	 * that window — e.g. keymapping registration reads {@code Minecraft.getInstance().options} and NPEs if the
	 * instance is null (run too early) or throws "GameOptions has already been initialised" (run too late). Running
	 * client entrypoints in this pre-{@code Minecraft} registration window gave the former (Jade's keybinds). See
	 * {@link #runClientEntrypoints()}, driven by {@code ClientEntrypointHookInjector}.
	 *
	 * @return true if this call ran them, false if they had already run
	 */
	public static boolean runMainEntrypoints() {
		if (loader == null) return false;
		if (!MAINS_RAN.compareAndSet(false, true)) return false;

		EnvType envType = loader.getEnvironmentType();
		int main = invoke("main", ModInitializer.class, ModInitializer::onInitialize);

		if (envType == EnvType.CLIENT) {
			ForbricLog.info("[Forbric/Fabric] invoked %d Fabric main entrypoint(s); client entrypoints deferred to "
					+ "Minecraft.<init>", main);
		} else {
			int server = invoke("server", DedicatedServerModInitializer.class,
					DedicatedServerModInitializer::onInitializeServer);
			ForbricLog.info("[Forbric/Fabric] invoked %d Fabric main entrypoint(s) + %d server entrypoint(s)",
					main, server);
		}
		return true;
	}

	/**
	 * Runs the Fabric {@code client} entrypoints, exactly once. Called from within {@code Minecraft.<init>} (after the
	 * singleton is set, before {@code Options} is built) by {@code ClientEntrypointHookInjector} — the window Fabric
	 * itself uses, so mods that touch {@code Minecraft.getInstance()} (keymappings, renderers) see a live instance.
	 *
	 * @return true if this call ran them, false if already run or off the client
	 */
	public static boolean runClientEntrypoints() {
		if (loader == null || loader.getEnvironmentType() != EnvType.CLIENT) return false;
		if (!CLIENTS_RAN.compareAndSet(false, true)) return false;

		int client = invoke("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
		ForbricLog.info("[Forbric/Fabric] invoked %d Fabric client entrypoint(s) (Minecraft.<init> window)", client);
		return true;
	}

	/** Whether the Fabric main entrypoints have already run. */
	public static boolean mainsAlreadyRan() {
		return MAINS_RAN.get();
	}

	/**
	 * Invokes one entrypoint key, isolating failures per mod: a mod whose {@code onInitialize} throws is reported
	 * and skipped rather than aborting the remaining mods' initialization (and with them the whole server boot).
	 */
	private static <T> int invoke(String key, Class<T> type, java.util.function.Consumer<T> action) {
		int count = 0;

		for (EntrypointContainer<T> c : loader.getEntrypointContainers(key, type)) {
			String id = c.getProvider().getMetadata().getId();

			try {
				action.accept(c.getEntrypoint());
				count++;
				ForbricLog.info("[Forbric/Fabric] invoked %s entrypoint of %s", key, id);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Fabric] " + key + " entrypoint of " + id + " failed", t);
			}
		}

		return count;
	}
}
