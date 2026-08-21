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

package net.forbric.loader.impl.forge.neoforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.metadata.CustomValue;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Brings up the <b>real</b> NeoForge runtime (loaded as a Knot mod) under the Fabric substrate, without
 * running FML's own startup ({@code FMLLoader.create}, which would build a module layer + a transforming
 * classloader that fights Knot). Reflection-only, so the loader keeps no compile-time NeoForge dependency;
 * the {@code net.neoforged.*} classes exist at runtime because the NeoForge runtime jar is Knot-loaded
 * alongside the (Mojmap-native, NeoForge-patched) game.
 *
 * <p>This is a Fabric {@code preLaunch} entrypoint. It always seeds a minimal FML environment so the patched
 * game's {@code SharedConstants.<clinit>} (→ {@code FMLEnvironment.isProduction()} →
 * {@code FMLLoader.getCurrent()}) does not throw. When {@code -Dforbric.headlessRegister=true} (the headless
 * server verification harness — the dedicated server otherwise {@code return}s at the EULA gate before
 * {@code Bootstrap.bootStrap()} runs), it additionally drives the full registration cycle itself:
 * {@code SharedConstants.tryDetectVersion()} + {@code Bootstrap.bootStrap()}, construct each discovered
 * {@code @Mod} with a per-mod {@code IEventBus} injected, open NeoForge's own registration window
 * ({@code GameData.unfreezeData()}), fire {@code RegisterEvent} per registry on each bus + drive Fabric
 * {@code main} entrypoints, then {@code GameData.freezeData()}. The real client (P2) instead rides the
 * patched MC's own {@code ClientModLoader.begin()} flow, so this manual drive stays off there.
 *
 * <p>{@code @Mod} classes are discovered from the {@code forbric:forgeClasses} custom keys that
 * {@code ForgeModRemapper.wrapAsFabricMod} writes into each wrapped NeoForge mod (with a
 * {@code -Dforbric.neoforgeMods} CSV override for testing).
 */
public final class ForbricNeoForgeRuntime implements PreLaunchEntrypoint {
	/** Set to {@code true} to drive Bootstrap + registration at preLaunch (headless/no-EULA verification). */
	public static final String HEADLESS_REGISTER = "forbric.headlessRegister";
	/** CSV of {@code @Mod} class names to construct, overriding/augmenting custom-key discovery (testing). */
	public static final String NEOFORGE_MODS = "forbric.neoforgeMods";
	/** CSV of {@code namespace:path} item ids to assert ended up in {@code BuiltInRegistries.ITEM} (testing). */
	public static final String VERIFY_ITEMS = "forbric.verifyItems";

	/** Legacy single Forge main class key written by {@code wrapAsFabricMod}. */
	private static final String FORGE_CLASS_KEY = "forbric:forgeClass";
	/** All Forge {@code @Mod} classes of a wrapped jar (array), written by {@code wrapAsFabricMod}. */
	private static final String FORGE_CLASSES_KEY = "forbric:forgeClasses";
	/** Which Forge family a wrap belongs to ({@code "forge"}/{@code "neoforge"}); absent = legacy wrap, accepted. */
	private static final String ECOSYSTEM_KEY = "forbric:ecosystem";

	private final Map<String, Object> modBuses = new LinkedHashMap<>();
	private boolean registered;

	@Override
	public void onPreLaunch() {
		ClassLoader cl = getClass().getClassLoader(); // Knot transforming classloader

		// Presence gate: on a traditional-Forge-base instance the NeoForge runtime is simply not staged.
		// Resource probe only — Class.forName (even initialize=false) would DEFINE the class into the
		// unnamed module and break the module-layer ordering invariant below.
		if (cl.getResource("net/neoforged/fml/loading/FMLLoader.class") == null) {
			ForbricLog.info("[Forbric/NeoForge] no NeoForge runtime staged — NeoForge driver idle");
			return;
		}

		// HARD ORDERING INVARIANT: the synthetic GAME module layer must be defined before ANY
		// net.neoforged.* class is Knot-loaded (see ForbricGameLayer javadoc). Collect jars first
		// (Fabric API only), then define the layer, and only then touch NeoForge classes.
		//
		// ONE shared layer across BOTH Forge-family drivers (see ForbricGameLayer.defineShared): if
		// MinecraftForge is ALSO staged (tri-in-one), its jars join the SAME layer/"minecraft" module rather
		// than a second one — JPMS forbids two same-named modules on one classloader. neoJars stays NeoForge-
		// only: it feeds ForbricNeoFmlDiscovery, which must only see THIS ecosystem's mod files.
		List<Path> neoJars = collectNeoForgeJars();
		net.forbric.loader.impl.forge.runtime.ForbricGameLayer.Result layer =
				net.forbric.loader.impl.forge.runtime.ForbricGameLayer.defineShared(cl);

		seedFmlLoader(cl);
		wireLoader(cl, layer.layer());

		if (layer.layer() != null) {
			// Only NeoForge jars that ACTUALLY joined the shared layer feed discovery — a jar ForbricGameLayer
			// dropped (its package was pre-loaded into the unnamed module) has no module, so its FML container
			// can't be built.
			List<Path> joined = neoJars.stream().filter(layer.jars()::contains).toList();
			new ForbricNeoFmlDiscovery(cl).run(joined, mcVersion());
		}

		if (Boolean.getBoolean(HEADLESS_REGISTER)) {
			driveHeadlessRegistration(cl);
		}
	}

	/** Knot-staged NeoForge jars: the merged neoforge runtime + every wrapped NeoForge-family mod. */
	private static List<Path> collectNeoForgeJars() {
		List<Path> jars = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			boolean isNeoRuntime = "neoforge".equals(mod.getMetadata().getId());
			boolean isWrappedNeoMod = (mod.getMetadata().containsCustomValue(FORGE_CLASSES_KEY)
					|| mod.getMetadata().containsCustomValue(FORGE_CLASS_KEY)) && isNeoForgeWrap(mod);
			if (!isNeoRuntime && !isWrappedNeoMod) continue;
			// Version gate: a mod that declares itself incompatible with the running MC version must not enter
			// FML discovery — its language-loader/dependency demands there would abort or poison the WHOLE
			// ecosystem's LoadingModList, not just itself (same rule as the MinecraftForge driver).
			if (!isNeoRuntime && net.forbric.loader.impl.util.ForbricVersionGate
					.isVersionIncompatible(mod.getMetadata().getId())) {
				ForbricLog.warn("[Forbric/NeoFML] excluding '" + mod.getMetadata().getId() + "' from NeoForge "
						+ "discovery: it declares itself incompatible with the running Minecraft version "
						+ "(install a build for this MC version)");
				continue;
			}
			if (mod.getOrigin().getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) {
				ForbricLog.warn("[Forbric/NeoFML] skipping non-path origin for mod '" + mod.getMetadata().getId()
						+ "' (" + mod.getOrigin() + ")");
				continue;
			}
			for (Path p : mod.getOrigin().getPaths()) {
				if (java.nio.file.Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
					jars.add(p);
				}
			}
		}
		return jars;
	}

	private static String mcVersion() {
		return System.getProperty("forbric.mcVersion", "26.2");
	}

	// --- FML environment seed (always) ---------------------------------------------------------------

	private void seedFmlLoader(ClassLoader cl) {
		try {
			Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader", false, cl);
			if (fmlLoader.getMethod("getCurrentOrNull").invoke(null) != null) {
				ForbricLog.info("[Forbric/NeoForge] FMLLoader already current; not re-seeding");
				return;
			}

			Class<?> distCls = Class.forName("net.neoforged.api.distmarker.Dist", false, cl);
			EnvType env = FabricLoader.getInstance().getEnvironmentType();
			String distName = env == EnvType.SERVER ? "DEDICATED_SERVER" : "CLIENT";
			Object dist = distCls.getMethod("valueOf", String.class).invoke(null, distName);
			Path gameDir = FabricLoader.getInstance().getGameDir();

			Constructor<?> ctor = fmlLoader.getDeclaredConstructor(
					ClassLoader.class, String[].class, distCls, boolean.class, Path.class);
			ctor.setAccessible(true);
			Object loader = ctor.newInstance(cl, new String[0], dist, true, gameDir);

			// The ctor self-registers the new instance as current (logs "Starting FancyModLoader …"); only call
			// the private makeCurrent() if some path left it unset.
			if (fmlLoader.getMethod("getCurrentOrNull").invoke(null) == null) {
				Method makeCurrent = fmlLoader.getDeclaredMethod("makeCurrent");
				makeCurrent.setAccessible(true);
				makeCurrent.invoke(loader);
			}

			// Seed an EMPTY LoadingModList so FeatureFlags.<clinit> → FMLLoader.getLoadingModList() doesn't throw
			// "isn't built yet"; we discover NeoForge mods via Forbric, not FML's own scan.
			try {
				Class<?> lmlCls = Class.forName("net.neoforged.fml.loading.LoadingModList", false, cl);
				Method of = lmlCls.getMethod("of", List.class, List.class, List.class, List.class, List.class, Map.class);
				Object empty = of.invoke(null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
				Field lmlField = fmlLoader.getDeclaredField("loadingModList");
				lmlField.setAccessible(true);
				lmlField.set(loader, empty);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/NeoForge] could not seed LoadingModList", t);
			}

			ForbricLog.info("[Forbric/NeoForge] seeded minimal FML environment: dist=" + distName + " production=true");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/NeoForge] failed to seed FML environment", t);
		}
	}

	// --- genuine-lifecycle wiring (gameLayer + versions + language providers + FML paths) ------------

	/**
	 * Seeds the {@code FMLLoader} instance fields the genuine lifecycle reads beyond the ctor's own:
	 * {@code gameLayer} (the synthetic layer — {@code FMLModContainer} resolves mod modules from it),
	 * {@code versionInfo}, and {@code languageProviderLoader} (built via a Proxy {@code ILaunchContext};
	 * its ServiceLoader pass finds {@code FMLJavaModLanguageProvider} from the runtime jar's services).
	 * Also loads {@code FMLPaths}/{@code FMLConfig} so config loading has real directories. Best-effort:
	 * each miss logs and continues — the headless registration path needs none of this.
	 */
	/**
	 * The NeoForge version to report through {@code FMLLoader.versionInfo}, read from the carrier actually
	 * loaded rather than written down here.
	 *
	 * <p>This used to be the literal {@code "26.2.0.7-beta"}. That is exactly the kind of default that survives
	 * a carrier bump and then lies: {@code assemble-neoforge-runtime.sh} moved the runtime jar to
	 * 26.2.0.38-beta and nothing in this file would have noticed, so every mod asking FML what NeoForge this is
	 * — including {@code VersionSupportMatrix}, built from this very object one block below — would have been
	 * told a version whose classes are not the ones on the classpath. The assemble script writes
	 * {@code Implementation-Version} into that jar's manifest precisely so it can be asked; ask it.
	 *
	 * <p>Order: the {@code forbric.neoforgeVersion} override, then the manifest reached through the loaded
	 * class itself (package attributes first, then the code source, since which of the two is populated depends
	 * on how the carrier was loaded), and only then a complaint. There is no literal fallback: a wrong version
	 * here is silent and acts at a distance, whereas an empty one makes the range checks fail loudly and name the
	 * cause.
	 */
	private static String neoForgeVersion(Class<?> carrierClass) {
		String override = System.getProperty("forbric.neoforgeVersion");
		if (override != null && !override.isBlank()) return override;

		Package pkg = carrierClass.getPackage();
		String fromPackage = pkg == null ? null : pkg.getImplementationVersion();
		if (fromPackage != null && !fromPackage.isBlank()) return fromPackage;

		try {
			java.security.CodeSource source = carrierClass.getProtectionDomain().getCodeSource();
			if (source != null && source.getLocation() != null) {
				try (java.util.jar.JarFile jar =
						new java.util.jar.JarFile(new java.io.File(source.getLocation().toURI()))) {
					java.util.jar.Manifest manifest = jar.getManifest();
					String value = manifest == null ? null
							: manifest.getMainAttributes().getValue("Implementation-Version");
					if (value != null && !value.isBlank()) return value;
				}
			}
		} catch (Throwable ignored) {
			// Not a plain jar on disk (nested, or served by the transforming loader) — fall through.
		}

		ForbricLog.warn("[Forbric/NeoFML] could not read the NeoForge carrier's Implementation-Version from %s;"
				+ " reporting an empty version. Pass -Dforbric.neoforgeVersion=… if a mod needs it.", carrierClass);
		return "";
	}

	private void wireLoader(ClassLoader cl, ModuleLayer gameLayer) {
		try {
			Class<?> fmlLoaderCls = Class.forName("net.neoforged.fml.loading.FMLLoader", false, cl);
			Object loader = fmlLoaderCls.getMethod("getCurrentOrNull").invoke(null);
			if (loader == null) return; // seed failed; already logged

			if (gameLayer != null) {
				setField(fmlLoaderCls, loader, "gameLayer", gameLayer);
			}

			// VersionInfo(neoForgeVersion, mcVersion, neoFormVersion) — record components in declaration order.
			Object versionInfo = null;
			try {
				Class<?> viCls = Class.forName("net.neoforged.fml.loading.VersionInfo", false, cl);
				versionInfo = viCls.getConstructor(String.class, String.class, String.class).newInstance(
						neoForgeVersion(viCls),
						mcVersion(),
						System.getProperty("forbric.neoformVersion", ""));
				setField(fmlLoaderCls, loader, "versionInfo", versionInfo);

				// The genuine flow builds the VersionSupportMatrix from VersionInfo during mod discovery;
				// LanguageProviderLoader.findLanguage reads it (getVersionSupportMatrix throws "Mod discovery
				// has not completed yet" while null), so seed it too.
				Class<?> vsmCls = Class.forName("net.neoforged.fml.loading.VersionSupportMatrix", false, cl);
				Constructor<?> vsmCtor = vsmCls.getDeclaredConstructor(viCls); // package-private class -> setAccessible
				vsmCtor.setAccessible(true);
				Object vsm = vsmCtor.newInstance(versionInfo);
				setField(fmlLoaderCls, loader, "versionSupportMatrix", vsm);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/NeoFML] could not seed VersionInfo/VersionSupportMatrix", t);
			}

			try {
				Object launchContext = launchContextProxy(cl, versionInfo);
				Class<?> lplCls = Class.forName("net.neoforged.fml.loading.LanguageProviderLoader", false, cl);
				Class<?> ctxCls = Class.forName("net.neoforged.neoforgespi.ILaunchContext", false, cl);
				Constructor<?> lplCtor = lplCls.getDeclaredConstructor(ctxCls);
				lplCtor.setAccessible(true);
				setField(fmlLoaderCls, loader, "languageProviderLoader", lplCtor.newInstance(launchContext));
				ForbricLog.info("[Forbric/NeoFML] language providers loaded (javafml et al. via runtime services)");
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/NeoFML] could not build LanguageProviderLoader — genuine mod "
						+ "construction will fail", t);
			}

			try {
				Path gameDir = FabricLoader.getInstance().getGameDir();
				Class<?> pathsCls = Class.forName("net.neoforged.fml.loading.FMLPaths", false, cl);
				Method load = null;
				for (Method m : pathsCls.getDeclaredMethods()) {
					if (m.getName().equals("loadAbsolutePaths") && m.getParameterCount() == 1) {
						load = m;
						break;
					}
				}
				if (load != null) {
					load.setAccessible(true);
					load.invoke(null, gameDir);
					Class.forName("net.neoforged.fml.loading.FMLConfig", false, cl)
							.getMethod("load").invoke(null);
					ForbricLog.info("[Forbric/NeoFML] FMLPaths + FMLConfig initialized under " + gameDir);
				} else {
					ForbricLog.warn("[Forbric/NeoFML] FMLPaths.loadAbsolutePaths not found — config dirs may be unset");
				}
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/NeoFML] FMLPaths/FMLConfig init failed (config loading may misbehave)", t);
			}
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/NeoFML] wireLoader failed", t);
		}
	}

	/** A Proxy {@code ILaunchContext}: dist/gameDir/versions from Fabric state, services from the Knot CL. */
	private Object launchContextProxy(ClassLoader cl, Object versionInfo) throws Exception {
		Class<?> ctxCls = Class.forName("net.neoforged.neoforgespi.ILaunchContext", false, cl);
		Class<?> distCls = Class.forName("net.neoforged.api.distmarker.Dist", false, cl);
		EnvType env = FabricLoader.getInstance().getEnvironmentType();
		Object dist = distCls.getMethod("valueOf", String.class)
				.invoke(null, env == EnvType.SERVER ? "DEDICATED_SERVER" : "CLIENT");
		Path gameDir = FabricLoader.getInstance().getGameDir();

		return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{ctxCls}, (proxy, method, args) -> {
			switch (method.getName()) {
			case "getRequiredDistribution": return dist;
			case "gameDirectory": return gameDir;
			case "loadServices": return java.util.ServiceLoader.load((Class<?>) args[0], cl).stream();
			case "isLocated": return false;
			case "addLocated": return true;
			case "getVersions": return versionInfo;
			case "toString": return "ForbricLaunchContext";
			case "hashCode": return System.identityHashCode(proxy);
			case "equals": return proxy == args[0];
			default: throw new UnsupportedOperationException("Forbric proxy does not implement " + method);
			}
		});
	}

	private static void setField(Class<?> cls, Object instance, String name, Object value) throws Exception {
		Field f = cls.getDeclaredField(name);
		f.setAccessible(true);
		f.set(instance, value);
	}

	// --- Headless registration drive (verification / no-EULA server) ---------------------------------

	private void driveHeadlessRegistration(ClassLoader cl) {
		try {
			Class<?> sharedConstants = Class.forName("net.minecraft.SharedConstants", false, cl);
			sharedConstants.getMethod("tryDetectVersion").invoke(null);
			Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", false, cl);
			bootstrap.getMethod("bootStrap").invoke(null);
			ForbricLog.info("[Forbric/NeoForge] Bootstrap.bootStrap() invoked — registries are up");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/NeoForge] Bootstrap.bootStrap() failed", t);
		}
		onRegistrationWindow(cl);
	}

	private synchronized void onRegistrationWindow(ClassLoader cl) {
		if (registered) return;
		registered = true;

		try {
			for (String modClass : discoverModClasses()) {
				Object bus = buildModBus(cl);
				Class<?> modCls = Class.forName(modClass, true, cl); // static init runs here (registries are up)
				Object instance = constructMod(modCls, bus, cl);
				modBuses.put(modClass, bus);
				ForbricLog.info("[Forbric/NeoForge] constructed @Mod " + modClass + " -> " + instance);
			}

			Class<?> gameData = Class.forName("net.neoforged.neoforge.registries.GameData", false, cl);
			gameData.getMethod("unfreezeData").invoke(null);
			ForbricLog.info("[Forbric/NeoForge] registration window OPEN (GameData.unfreezeData)");
			try {
				fireRegisterEvents(cl);
				invokeFabricMainEntrypoints();
			} finally {
				gameData.getMethod("freezeData").invoke(null);
				ForbricLog.info("[Forbric/NeoForge] registration window CLOSED (GameData.freezeData)");
			}

			verifyItems(cl);
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/NeoForge] registration window failed", t);
		}
	}

	private void fireRegisterEvents(ClassLoader cl) throws Exception {
		if (modBuses.isEmpty()) return;

		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
		Class<?> eventCls = Class.forName("net.neoforged.bus.api.Event", false, cl);
		Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		Class<?> registerEventCls = Class.forName("net.neoforged.neoforge.registries.RegisterEvent", false, cl);
		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);

		Constructor<?> regEventCtor = registerEventCls.getDeclaredConstructor(resourceKeyCls, registryCls);
		regEventCtor.setAccessible(true);
		Method keyM = registryCls.getMethod("key");
		Method postM = busCls.getMethod("post", eventCls);

		List<Object> registries = new ArrayList<>();
		for (Field f : builtin.getFields()) {
			if (registryCls.isAssignableFrom(f.getType())) registries.add(f.get(null));
		}

		for (Map.Entry<String, Object> e : modBuses.entrySet()) {
			for (Object registry : registries) {
				Object event = regEventCtor.newInstance(keyM.invoke(registry), registry);
				postM.invoke(e.getValue(), event);
			}
			ForbricLog.info("[Forbric/NeoForge] fired RegisterEvent x" + registries.size() + " on bus for " + e.getKey());
		}
	}

	/** Drive Fabric {@code main} entrypoints so Fabric content mods register in the same open window. */
	private void invokeFabricMainEntrypoints() {
		for (EntrypointContainer<ModInitializer> c :
				FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class)) {
			String id = c.getProvider().getMetadata().getId();
			try {
				c.getEntrypoint().onInitialize();
				ForbricLog.info("[Forbric/NeoForge] invoked Fabric main entrypoint of " + id);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/NeoForge] Fabric main entrypoint of " + id + " failed", t);
			}
		}
	}

	private void verifyItems(ClassLoader cl) throws Exception {
		String csv = System.getProperty(VERIFY_ITEMS, "").trim();
		if (csv.isEmpty()) return;

		Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> idCls = Class.forName("net.minecraft.resources.Identifier", false, cl);
		Object itemReg = builtin.getField("ITEM").get(null);
		Method contains = registryCls.getMethod("containsKey", idCls);
		Method getValue = registryCls.getMethod("getValue", idCls);
		Method idOf = idCls.getMethod("fromNamespaceAndPath", String.class, String.class);

		for (String spec : csv.split(",")) {
			spec = spec.trim();
			if (spec.isEmpty()) continue;
			String[] np = spec.split(":", 2);
			Object id = idOf.invoke(null, np[0], np[1]);
			boolean has = (Boolean) contains.invoke(itemReg, id);
			Object val = has ? getValue.invoke(itemReg, id) : null;
			ForbricLog.info("[Forbric/VERIFY] BuiltInRegistries.ITEM contains " + spec + " = " + has
					+ (val != null ? " -> " + val : ""));
		}
	}

	// --- discovery + construction helpers ------------------------------------------------------------

	/** The {@code @Mod} classes to construct: a {@code -Dforbric.neoforgeMods} CSV plus every wrapped mod's keys. */
	private static List<String> discoverModClasses() {
		LinkedHashSet<String> classes = new LinkedHashSet<>();

		String csv = System.getProperty(NEOFORGE_MODS, "").trim();
		if (!csv.isEmpty()) {
			for (String s : csv.split(",")) {
				if (!s.trim().isEmpty()) classes.add(s.trim());
			}
		}

		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (!isNeoForgeWrap(mod)) continue; // the traditional-Forge driver owns "forge"-family wraps
			classes.addAll(forgeClasses(mod));
		}

		return new ArrayList<>(classes);
	}

	/** Whether a wrapped mod belongs to the NeoForge family (missing key = legacy wrap = accept). */
	private static boolean isNeoForgeWrap(ModContainer mod) {
		CustomValue v = mod.getMetadata().getCustomValue(ECOSYSTEM_KEY);
		return v == null || v.getType() != CustomValue.CvType.STRING || "neoforge".equals(v.getAsString());
	}

	/** The Forge main classes a wrapped mod declares: {@code forbric:forgeClasses} (array) or the legacy single key. */
	private static List<String> forgeClasses(ModContainer mod) {
		List<String> classes = new ArrayList<>();

		CustomValue array = mod.getMetadata().getCustomValue(FORGE_CLASSES_KEY);
		if (array != null && array.getType() == CustomValue.CvType.ARRAY) {
			for (CustomValue element : array.getAsArray()) {
				if (element.getType() == CustomValue.CvType.STRING) classes.add(element.getAsString());
			}
		}

		if (classes.isEmpty()) {
			CustomValue single = mod.getMetadata().getCustomValue(FORGE_CLASS_KEY);
			if (single != null && single.getType() == CustomValue.CvType.STRING && !single.getAsString().isEmpty()) {
				classes.add(single.getAsString());
			}
		}

		return classes;
	}

	private static Object buildModBus(ClassLoader cl) throws Exception {
		Class<?> busBuilder = Class.forName("net.neoforged.bus.api.BusBuilder", false, cl);
		Object builder = busBuilder.getMethod("builder").invoke(null);
		try {
			Class<?> modBusEvent = Class.forName("net.neoforged.fml.event.IModBusEvent", false, cl);
			builder = busBuilder.getMethod("markerType", Class.class).invoke(builder, modBusEvent);
		} catch (Throwable ignore) {
			// markerType is best-effort
		}
		return busBuilder.getMethod("build").invoke(builder);
	}

	/** Construct a {@code @Mod} FMLModContainer-style: fill ctor params by type (IEventBus → bus, Dist → dist). */
	private static Object constructMod(Class<?> modCls, Object bus, ClassLoader cl) throws Exception {
		Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		Class<?> distCls = Class.forName("net.neoforged.api.distmarker.Dist", false, cl);
		Object dist = Class.forName("net.neoforged.fml.loading.FMLEnvironment", false, cl)
				.getMethod("getDist").invoke(null);

		Constructor<?> best = null;
		for (Constructor<?> c : modCls.getConstructors()) {
			if (best == null || c.getParameterCount() > best.getParameterCount()) best = c;
		}
		if (best == null) throw new NoSuchMethodException("no public constructor on " + modCls.getName());

		Class<?>[] params = best.getParameterTypes();
		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			if (params[i].isAssignableFrom(iEventBus)) args[i] = bus;
			else if (params[i] == distCls) args[i] = dist;
			else args[i] = null; // ModContainer / FMLModContainer / etc. — not modelled yet
		}

		best.setAccessible(true);
		return best.newInstance(args);
	}
}
