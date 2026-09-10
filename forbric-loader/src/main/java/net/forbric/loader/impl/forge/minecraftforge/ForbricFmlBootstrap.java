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

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Stands up the minimum FML "pre-loading" state that lets Forge's OWN {@code ModLoader}/{@code ServerModLoader}/
 * {@code ClientModLoader} run under Knot's flat classloader — without ModLauncher, BootstrapLauncher, or
 * securemodules ever executing.
 *
 * <p>The one piece of JPMS Forge genuinely requires is a GAME {@link ModuleLayer}: {@code FMLModContainer}'s
 * constructor resolves the mod's named module from it ({@code layer.findModule(...)} + {@code Class.forName(module,
 * cn)}), and FML discovers its services ({@code IModStateProvider}, {@code IModLanguageProvider}) via
 * {@code ServiceLoader.load(layer, ...)}. So Forbric synthesizes that layer: every Knot-loaded Forge jar
 * (forge-runtime.jar + each wrapped Forge mod) is derived as an <em>automatic module</em> and defined with the
 * <b>Knot classloader</b> as its loader — classes keep Knot identity and Knot's transforms keep applying; they just
 * additionally become members of named modules.
 *
 * <p><b>HARD ORDERING INVARIANT (probe-proven):</b> the layer must be defined before ANY class from those jars is
 * loaded by Knot. A package that already has a class in Knot's unnamed module cannot join a named module —
 * {@code defineModules} throws {@code LayerInstantiationException("Package … is already in the unnamed module")}.
 * That includes Forge's own {@code UnsafeHacks}/{@code ModuleLayerHandler}: {@link #defineGameLayer} therefore uses
 * pure JDK + Fabric APIs only, and all Forge-class reflection happens afterwards in {@link #wireFml}.
 *
 * <p>Everything here is reflection-only: the loader keeps no compile-time Forge dependency (Apache-2.0 tree,
 * LGPL Forge supplied at runtime).
 */
final class ForbricFmlBootstrap {
	/** Print a pre-game smoke report of the synthesized FML state ({@code -Dforbric.fmlSmoke=true}). */
	static final String FML_SMOKE = "forbric.fmlSmoke";

	private static final String FORGE_RUNTIME_MOD_ID = "forge"; // injected by run/assemble-minecraftforge-runtime.sh

	private ModuleLayer gameLayer;
	private List<Path> layerJars = List.of();
	private Path runtimeJar;

	ModuleLayer gameLayer() {
		return gameLayer;
	}

	List<Path> layerJars() {
		return layerJars;
	}

	/** The merged forge-runtime jar (modid {@value #FORGE_RUNTIME_MOD_ID}), or null if not staged. */
	Path runtimeJar() {
		return runtimeJar;
	}

	// --- phase 1: synthesize the GAME layer (NO Forge classes may load before/inside this) -----------

	/**
	 * Derive automatic modules from every Knot-staged Forge jar and define them into one layer whose loader is
	 * Knot's transforming classloader. Failures are logged and leave {@link #gameLayer} null — the headless
	 * registration path does not need the layer, so the boot continues.
	 */
	void defineGameLayer(ClassLoader knotCl) {
		List<Path> jars = collectForgeJars();
		if (jars.isEmpty()) {
			ForbricLog.warn("[Forbric/FML] no Forge jars staged (no '" + FORGE_RUNTIME_MOD_ID
					+ "' mod, no wrapped Forge mods) - skipping GAME layer synthesis");
			return;
		}

		// ONE shared layer across BOTH Forge-family drivers (see ForbricGameLayer.defineShared): if NeoForge is
		// ALSO staged (tri-in-one), its jars join the SAME layer/"minecraft" module rather than a second one —
		// JPMS forbids two same-named modules on one classloader. layerJars stays Forge-only: it feeds
		// ForbricFmlDiscovery, which must only see THIS ecosystem's mod files.
		net.forbric.loader.impl.forge.runtime.ForbricGameLayer.Result result =
				net.forbric.loader.impl.forge.runtime.ForbricGameLayer.defineShared(knotCl);
		this.gameLayer = result.layer();
		// Only THIS ecosystem's jars that ACTUALLY joined the layer feed ForbricFmlDiscovery: a jar ForbricGameLayer
		// dropped (its package was pre-loaded into the unnamed module) has no module in the layer, so building its
		// FML ModFile/container would fail — discovering it must be skipped too.
		this.layerJars = result.layer() != null
				? jars.stream().filter(result.jars()::contains).collect(Collectors.toList())
				: List.of();
	}

	/** Knot-staged Forge jars: the merged forge runtime + every wrapped/raw Forge mod (by Forbric custom key). */
	private List<Path> collectForgeJars() {
		List<Path> jars = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			boolean isForgeRuntime = FORGE_RUNTIME_MOD_ID.equals(id);
			boolean isWrappedForgeMod = mod.getMetadata().containsCustomValue("forbric:forgeClasses")
					|| mod.getMetadata().containsCustomValue("forbric:forgeClass");
			if (!isForgeRuntime && !isWrappedForgeMod) continue;

			// NeoForge-family wraps belong to the NeoForge driver's layer, not this (cpw/FML) one.
			net.fabricmc.loader.api.metadata.CustomValue eco = mod.getMetadata().getCustomValue("forbric:ecosystem");
			if (eco != null && eco.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING
					&& !"forge".equals(eco.getAsString())) {
				continue;
			}

			// Version gate: a mod that declares itself incompatible with the running MC version must not enter
			// FML discovery — its language-loader/dependency demands there would abort or poison the WHOLE
			// ecosystem's LoadingModList, not just itself (e.g. a 1.21.x mod wanting javafml [61,62) on 65.x).
			if (!isForgeRuntime && net.forbric.loader.impl.util.ForbricVersionGate.isVersionIncompatible(id)) {
				ForbricLog.warn("[Forbric/FML] excluding '" + id + "' from Forge discovery: it declares itself "
						+ "incompatible with the running Minecraft version (install a build for this MC version)");
				continue;
			}

			if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH) {
				ForbricLog.warn("[Forbric/FML] skipping non-path origin for mod '" + id + "' (" + mod.getOrigin() + ")");
				continue;
			}
			for (Path p : mod.getOrigin().getPaths()) {
				if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
					jars.add(p);
					if (isForgeRuntime && runtimeJar == null) {
						runtimeJar = p;
					}
				}
			}
		}
		return jars;
	}

	// --- phase 2: wire the layer + minimal launcher state into FML (Forge classes now safe) ----------

	/**
	 * Seed the FML statics that the genuine discovery/lifecycle path reads. Call AFTER
	 * {@code seedFmlEnvironment} (some of these read {@code FMLEnvironment.dist}).
	 *
	 * <p>The layer-INDEPENDENT statics ({@link #seedLayerIndependentFml}: {@code gamePath}, FMLPaths, FMLConfig,
	 * launch handler, access-transformer sink, tick guard) are seeded FIRST and UNCONDITIONALLY, so a FAILED GAME
	 * layer no longer leaves them null — Forge mods read them EARLY (Physics Mod's {@code <clinit>} resolves cloth
	 * dirs against {@code FMLLoader.getGamePath()} during vanilla {@code Bootstrap.bootStrap()}). The layer-DEPENDENT
	 * wiring below (ModuleLayerManager, {@code Launcher.INSTANCE}, name-mapping, language providers) runs only when
	 * the synthetic GAME module layer exists.
	 */
	void wireFml(ClassLoader cl) {
		seedLayerIndependentFml(cl);
		if (gameLayer == null) {
			ForbricLog.warn("[Forbric/FML] GAME layer absent — seeded layer-independent FML env only; layer-dependent"
					+ " wiring (ModuleLayerManager, Launcher.INSTANCE, name-mapping, language providers) skipped");
			return;
		}
		try {
			Class<?> unsafe = Class.forName("net.minecraftforge.unsafe.UnsafeHacks", false, cl);
			Method newInstance = unsafe.getMethod("newInstance", Class.class);
			Method setField = unsafe.getMethod("setField", Field.class, Object.class, Object.class);

			// A real ModuleLayerHandler (it IS the IModuleLayerManager impl): allocate without ctor, then hand it
			// our completedLayers map. getLayer(GAME) is a plain EnumMap read of LayerInfo(layer, cl) records.
			Class<?> mlhCls = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler", false, cl);
			Object layerHandler = newInstance.invoke(null, mlhCls);
			Class<?> layerEnum = Class.forName("cpw.mods.modlauncher.api.IModuleLayerManager$Layer", false, cl);
			Class<?> layerInfoCls = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler$LayerInfo", false, cl);
			Constructor<?> layerInfoCtor = layerInfoCls.getDeclaredConstructor(ModuleLayer.class, ClassLoader.class);
			layerInfoCtor.setAccessible(true);

			// GAME, PLUGIN and SERVICE all resolve to the ONE synthetic layer: real Forge spreads its jars
			// over four layers, but the merged forge-runtime.jar holds everything, and FML looks things up
			// in specific tiers (e.g. LanguageLoadingProvider service-loads from PLUGIN). Only BOOT stays
			// the real boot layer.
			@SuppressWarnings({"unchecked", "rawtypes"})
			Map<Object, Object> completed = new EnumMap(layerEnum.asSubclass(Enum.class));
			for (Object constant : layerEnum.getEnumConstants()) {
				boolean boot = "BOOT".equals(((Enum<?>) constant).name());
				completed.put(constant, layerInfoCtor.newInstance(
						boot ? ModuleLayer.boot() : gameLayer,
						boot ? ClassLoader.getSystemClassLoader() : cl));
			}
			@SuppressWarnings({"unchecked", "rawtypes"})
			Map<Object, Object> emptyLayers = new EnumMap(layerEnum.asSubclass(Enum.class));
			setField.invoke(null, mlhCls.getDeclaredField("completedLayers"), layerHandler, completed);
			setField.invoke(null, mlhCls.getDeclaredField("layers"), layerHandler, emptyLayers);

			// FMLLoader.getGameLayer() = moduleLayerManager.getLayer(GAME).orElseThrow()
			// (gamePath already seeded unconditionally in seedLayerIndependentFml.)
			Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl);
			setStatic(fmlLoader, "moduleLayerManager", layerHandler);

			// Launcher.INSTANCE.environment().findModuleLayerManager() - only LanguageLoadingProvider.<clinit>
			// walks this path, but it hard-fails without it. Environment's real (package-private) ctor wires its
			// TypesafeMap properly, so use it instead of raw allocation.
			Class<?> launcherCls = Class.forName("cpw.mods.modlauncher.Launcher", false, cl);
			Object launcher = newInstance.invoke(null, launcherCls);
			Class<?> envCls = Class.forName("cpw.mods.modlauncher.Environment", false, cl);
			Constructor<?> envCtor = envCls.getDeclaredConstructor(launcherCls);
			envCtor.setAccessible(true);
			Object environment = envCtor.newInstance(launcher);
			setField.invoke(null, launcherCls.getDeclaredField("environment"), launcher, environment);
			setField.invoke(null, launcherCls.getDeclaredField("moduleLayerHandler"), launcher, layerHandler);
			// Seed the ModLauncher name-mapping service. Without it, Launcher.findNameMapping NPEs the moment any
			// mod uses net.minecraftforge.fml.util.ObfuscationReflectionHelper — a VERY common Forge idiom for
			// reflecting into vanilla internals (Physics Mod's ReflectionsForge.<clinit> does exactly this on the
			// first entity-death render, and crashed with "nameMappingServiceHandler is null"). The handler is a
			// package-private final class with a public ctor and an internal error-catch; it ServiceLoads
			// INameMappingService (Forge's MCPNamingService) from our synthetic layer. On Mojmap-native 26.2 mods
			// pass Mojmap names, so remapName is effectively identity (getNameFunction(...).map(f->..).orElse(name)),
			// and ObfuscationReflectionHelper.findMethod/findField resolve directly instead of NPEing.
			try {
				Class<?> nmshCls = Class.forName("cpw.mods.modlauncher.NameMappingServiceHandler", false, cl);
				Constructor<?> nmshCtor = nmshCls.getDeclaredConstructor(mlhCls);
				nmshCtor.setAccessible(true);
				Object nmsh = nmshCtor.newInstance(layerHandler);
				setField.invoke(null, launcherCls.getDeclaredField("nameMappingServiceHandler"), launcher, nmsh);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/FML] could not seed NameMappingServiceHandler — mods using "
						+ "ObfuscationReflectionHelper (e.g. Physics Mod) may NPE", t);
			}

			launcherCls.getField("INSTANCE").set(null, launcher);

			// Now that Launcher.INSTANCE is wired, exercise the exact path ObfuscationReflectionHelper hits
			// (getNameFunction -> Launcher.INSTANCE.environment().findNameMapping) to confirm the NPE is gone.
			try {
				Object fn = fmlLoader.getMethod("getNameFunction", String.class).invoke(null, "srg");
				ForbricLog.info("[Forbric/FML] ObfuscationReflectionHelper reflection-remap ready "
						+ "(getNameFunction(\"srg\") -> " + fn + ")");
			} catch (Throwable probe) {
				ForbricLog.warn("[Forbric/FML] getNameFunction still failing after seed — "
						+ "ObfuscationReflectionHelper mods may NPE", probe);
			}

			// fml.toml early-display provider (dummy). FMLPaths + FMLConfig were already loaded unconditionally
			// in seedLayerIndependentFml, so updateConfig(EARLY_WINDOW_CONTROL) below has a live FMLConfig.
			try {
				// P5: the REAL early-display (DisplayWindow/GLFW) can never run under Knot (no ModLauncher
				// window thread; macOS main-thread constraints) — force the dummy provider via fml.toml.
				Class<?> fmlConfig = Class.forName("net.minecraftforge.fml.loading.FMLConfig", false, cl);
				@SuppressWarnings({"unchecked", "rawtypes"})
				Object earlyWindowControl = Enum.valueOf(
						(Class<Enum>) (Class) Class.forName("net.minecraftforge.fml.loading.FMLConfig$ConfigValue", false, cl)
								.asSubclass(Enum.class), "EARLY_WINDOW_CONTROL");
				fmlConfig.getMethod("updateConfig", earlyWindowControl.getClass(), Object.class)
						.invoke(null, earlyWindowControl, Boolean.FALSE);

				// The target string is only pattern-matched (contains "client"/"server"/"data") to decide
				// whether an early window may appear; give it the dist-appropriate real Forge target name.
				String launchTarget = FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER
						? "forge_server" : "forge_client";
				Class.forName("net.minecraftforge.fml.loading.ImmediateWindowHandler", false, cl)
						.getMethod("load", String.class, String[].class)
						.invoke(null, launchTarget, (Object) new String[0]);
			} catch (Throwable t) {
				Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
						? t.getCause() : t;
				ForbricLog.warn("[Forbric/FML] ImmediateWindowHandler.load failed (continuing)", cause);
			}
			// (progressWindowTick already installed in seedLayerIndependentFml.)

			// What FMLLoader does right before launching the game: lets the (dummy) early-display provider
			// resolve its vanilla-overlay fallback from the layer's net.minecraftforge.forge module.
			try {
				Class.forName("net.minecraftforge.fml.loading.ImmediateWindowHandler", false, cl)
						.getMethod("acceptGameLayer", ModuleLayer.class).invoke(null, gameLayer);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/FML] acceptGameLayer failed (continuing)", t);
			}

			// (launch handler + AccessTransformerService already seeded in seedLayerIndependentFml.)

			// Populate the language-provider registry (javafml/lowcodefml/minecraft). Real FML does this from
			// beginModScan; nothing calls it under Knot, and findLanguage throws on the empty registry otherwise.
			Class.forName("net.minecraftforge.fml.loading.LanguageLoadingProvider", false, cl)
					.getMethod("reload").invoke(null);

			ForbricLog.info("[Forbric/FML] wired FML pre-loading state: moduleLayerManager + Launcher.INSTANCE"
					+ " + fml.toml + immediate window provider + language providers");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML] wiring FML pre-loading state failed", t);
		}
	}

	/**
	 * Seed the layer-INDEPENDENT FML statics — the ones that need only the game directory, not the synthetic GAME
	 * module layer. These MUST be seeded even when {@link #defineGameLayer} failed (degraded boot), because Forge
	 * mods read them EARLY: {@code FMLLoader.getGamePath()} (Physics Mod's {@code <clinit>} resolves cloth dirs
	 * against it during vanilla {@code Bootstrap.bootStrap()}), {@code FMLPaths.CONFIGDIR}, the launch handler, and
	 * the access-transformer sink. Reflection-only; each seed is best-effort (a failure logs and continues) so one
	 * missing static never aborts the rest.
	 */
	private void seedLayerIndependentFml(ClassLoader cl) {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Class<?> fmlLoader;
		try {
			fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML] cannot resolve FMLLoader to seed layer-independent FML state", t);
			return;
		}

		// FMLLoader.getGamePath() — read by mods during Bootstrap/clinit (e.g. Physics Mod's ModLoaderFunctions
		// -> FMLLoader.getGamePath()). The single most important early seed: without it those reads NPE.
		try {
			setStatic(fmlLoader, "gamePath", gameDir);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML] failed to seed FMLLoader.gamePath", t);
		}

		// FMLPaths (CONFIGDIR/GAMEDIR/…) + FMLConfig — read by mods that resolve config paths early.
		try {
			Class.forName("net.minecraftforge.fml.loading.FMLPaths", false, cl)
					.getMethod("loadAbsolutePaths", Path.class).invoke(null, gameDir);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/FML] FMLPaths.loadAbsolutePaths failed (continuing)", t);
		}
		try {
			Class.forName("net.minecraftforge.fml.loading.FMLConfig", false, cl).getMethod("load").invoke(null);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/FML] FMLConfig.load() failed (continuing)", t);
		}

		// A no-op progress tick so anything that pumps the (dummy) early-display doesn't NPE on a null Runnable.
		try {
			fmlLoader.getField("progressWindowTick").set(null, (Runnable) () -> { });
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/FML] progressWindowTick seed failed (continuing)", t);
		}

		// The genuine ClientModLoader/DataGen paths null-check FMLLoader.getLaunchHandler().isData();
		// seed the real production handler for our dist.
		try {
			boolean server = FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER;
			Class<?> handlerCls = Class.forName("net.minecraftforge.fml.loading.targets.ForgeProdLaunchHandler$"
					+ (server ? "Server" : "Client"), false, cl);
			Constructor<?> handlerCtor = handlerCls.getDeclaredConstructor();
			handlerCtor.setAccessible(true);
			Object handler = handlerCtor.newInstance();
			setStatic(fmlLoader, "commonLaunchHandler", handler);
			setStatic(fmlLoader, "launchHandlerName", String.valueOf(handlerCls.getMethod("name").invoke(handler)));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/FML] launch-handler seed failed (continuing)", t);
		}

		// P2: LoadingModListImpl.init hands every mod-shipped accesstransformer.cfg to
		// FMLLoader.addAccessTransformer -> accessTransformer.offerResource, which NPEs if the static is null.
		// A real (no-arg) AccessTransformerService just COLLECTS them - nothing applies its engine without the
		// ModLauncher plugin loop; Forbric's own ACCESS transform phase does the real applying.
		try {
			Class<?> atService = Class.forName("net.minecraftforge.accesstransformer.service.AccessTransformerService", false, cl);
			setStatic(fmlLoader, "accessTransformer", atService.getConstructor().newInstance());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/FML] AccessTransformerService seed failed (continuing)", t);
		}

		ForbricLog.info("[Forbric/FML] seeded layer-independent FML env: gamePath(" + gameDir
				+ ") + FMLPaths + FMLConfig + launch handler + AccessTransformerService");
	}

	// --- smoke report ---------------------------------------------------------------------------------

	/** Pre-game assertions that the synthesized state is what genuine FML discovery will need. */
	void smoke(ClassLoader cl) {
		ForbricLog.info("[Forbric/FML/smoke] gameLayer=" + (gameLayer == null ? "ABSENT"
				: gameLayer.modules().size() + " modules " + gameLayer.modules().stream()
						.map(Module::getName).sorted().collect(Collectors.toList())));
		if (gameLayer == null) return;
		try {
			Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl);
			Object viaFml = fmlLoader.getMethod("getGameLayer").invoke(null);
			ForbricLog.info("[Forbric/FML/smoke] FMLLoader.getGameLayer() -> " + (viaFml == gameLayer ? "OK (same layer)" : viaFml));
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML/smoke] FMLLoader.getGameLayer() FAILED", t);
		}
		try {
			Class<?> launcherCls = Class.forName("cpw.mods.modlauncher.Launcher", false, cl);
			Object launcher = launcherCls.getField("INSTANCE").get(null);
			Object env = launcherCls.getMethod("environment").invoke(launcher);
			Object found = env.getClass().getMethod("findModuleLayerManager").invoke(env);
			ForbricLog.info("[Forbric/FML/smoke] Launcher.INSTANCE.environment().findModuleLayerManager() -> " + found);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML/smoke] Launcher path FAILED", t);
		}
		reportServices(cl, "net.minecraftforge.fml.IModStateProvider");
		reportServices(cl, "net.minecraftforge.forgespi.language.IModLanguageProvider");

		try {
			Class<?> javafml = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLanguageProvider", false, cl);
			Package pkg = javafml.getPackage();
			ForbricLog.info("[Forbric/FML/smoke] javafml Package: implVersion=" + (pkg == null ? "<no pkg>" : pkg.getImplementationVersion())
					+ " specVersion=" + (pkg == null ? "-" : pkg.getSpecificationVersion())
					+ " module=" + javafml.getModule());
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML/smoke] javafml package probe FAILED", t);
		}
		try {
			Class<?> llp = Class.forName("net.minecraftforge.fml.loading.LanguageLoadingProvider", true, cl);
			Field byName = llp.getDeclaredField("providersByName");
			byName.setAccessible(true);
			ForbricLog.info("[Forbric/FML/smoke] language providers = " + ((Map<?, ?>) byName.get(null)).keySet());
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML/smoke] language-provider probe FAILED", t);
		}
	}

	private void reportServices(ClassLoader cl, String serviceName) {
		try {
			Class<?> service = Class.forName(serviceName, false, cl);
			List<String> impls = new ArrayList<>();
			for (Object impl : ServiceLoader.load(gameLayer, service)) {
				impls.add(impl.getClass().getName());
			}
			ForbricLog.info("[Forbric/FML/smoke] ServiceLoader(layer, " + service.getSimpleName() + ") -> "
					+ impls.size() + " " + impls);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/FML/smoke] ServiceLoader(layer, " + serviceName + ") FAILED", t);
		}
	}

	private static void setStatic(Class<?> c, String name, Object v) throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		f.set(null, v);
	}
}
