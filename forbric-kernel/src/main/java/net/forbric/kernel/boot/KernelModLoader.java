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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.discovery.ModAnnotationScanner.Family;
import net.forbric.kernel.util.ForbricLog;

/**
 * Constructs discovered Forge-family {@code @Mod} classes natively — the M3 keystone for real mods.
 *
 * <p>For each mod jar it ASM-scans for {@code @Mod} classes ({@link ModAnnotationScanner}) and constructs each on
 * its own mod-event bus. No FancyModLoader discovery / module layer / sorting runs — the kernel owns construction.
 * Each constructed mod's bus is returned so the caller can fire {@code RegisterEvent} on it (flushing the mod's
 * {@code DeferredRegister}s).
 *
 * <p>The two Forge-family ecosystems are constructed differently and must not be confused:
 * <ul>
 *   <li><b>NeoForge</b> — a {@code BusBuilder} {@code IEventBus} + a kernel-manufactured {@code ModContainer}, with
 *       the ctor filled by parameter type ({@code IEventBus} → bus, {@code Dist} → dist, {@code ModContainer} →
 *       container);</li>
 *   <li><b>traditional MinecraftForge</b> — an EventBus 7 {@code BusGroup} + {@code FMLModContainer} +
 *       {@code FMLJavaModLoadingContext} triple ({@link KernelForgeModContext}), which every real Forge mod's ctor
 *       takes. That context used to be passed as {@code null} here, so mods like Macaw's Bridges and spark NPE'd in
 *       their own constructor and registered nothing.</li>
 * </ul>
 */
public final class KernelModLoader {
	private KernelModLoader() {
	}

	/**
	 * A constructed mod and what the caller needs to fire {@code RegisterEvent} at it. Exactly one of {@code bus} (a
	 * NeoForge {@code IEventBus}) and {@code forgeHandle} (a traditional-Forge context) is non-null — the two
	 * ecosystems carry different {@code RegisterEvent} flavours and cannot be pooled.
	 *
	 * <p>The Forge side keeps the whole {@link KernelForgeModContext.Handle}, not just its bus: dispatch has to make
	 * the mod's own container active, or the id-less {@code RegisterHelper.register(String, T)} overload namespaces
	 * its content under whichever container was active last.
	 */
	public record ConstructedMod(String modId, String className, Family family, Object bus,
			KernelForgeModContext.Handle forgeHandle) {}

	/**
	 * A NeoForge mod's identity: ONE bus and ONE {@code ModContainer} per mod id, shared by every {@code @Mod} class
	 * that declares that id. Genuine NeoForge works the same way — a mod has one container and one bus, not one per
	 * annotated class — and here it is also forced by {@code ModList.setLoadedMods}, whose {@code indexedMods} is
	 * built with {@code Collectors.toMap(ModContainer::getModId, identity())}: two containers sharing an id throw
	 * {@code IllegalStateException: Duplicate key}. Real mods do ship several (balm: {@code NeoForgeBalm} +
	 * {@code NeoForgeBalmClient}; FallingTree the same).
	 */
	public record NeoIdentity(Object bus, Object container) {}

	/**
	 * The NeoForge mods this kernel loaded, by mod id — their bus and container, as published to {@code ModList}.
	 *
	 * <p>Exposed so the lifecycle can post the FML setup events ({@code FMLCommonSetupEvent},
	 * {@code FMLClientSetupEvent}, {@code FMLLoadCompleteEvent}) at every mod, which needs exactly this pairing.
	 */
	public static Map<String, NeoIdentity> publishedNeoMods() {
		return publishedNeo;
	}

	private static volatile Map<String, NeoIdentity> publishedNeo = Map.of();

	/**
	 * The traditional-Forge mods this kernel constructed, by mod id — the MinecraftForge counterpart of
	 * {@link #publishedNeoMods()}.
	 *
	 * <p>Exposed for {@link KernelEventSubscribers}, which needs a subscriber's owning mod to answer two questions
	 * FML answers from its own container registry: which {@code BusGroup} a {@code bus = MOD} subscriber belongs on,
	 * and which container must be ACTIVE while registering (Forge's {@code bus = BOTH} routing resolves the mod bus
	 * through {@code FMLJavaModLoadingContext.get()}). The handle used to die as a local in {@link KernelLifecycle}.
	 */
	public static Map<String, KernelForgeModContext.Handle> publishedForgeMods() {
		return publishedForge;
	}

	private static volatile Map<String, KernelForgeModContext.Handle> publishedForge = Map.of();

	/** Scans + constructs every {@code @Mod} in {@code modJars}. Best-effort per mod. */
	public static List<ConstructedMod> constructMods(ForbricClassLoader loader, ClassLoader cl, List<Path> modJars,
			boolean client) {
		// Phase 1 — scan and ARBITRATE everything first, so the full NeoForge mod set is known before any mod's
		// constructor runs. A universal jar ships one @Mod per family; only the family that OWNS the jar may
		// construct, or the same mod initialises once per live ecosystem (see MultiLoaderArbiter).
		List<ModAnnotationScanner.ModClassInfo> claimed = new ArrayList<>();
		for (Path jar : modJars) {
			List<ModAnnotationScanner.ModClassInfo> mods;
			try {
				mods = ModAnnotationScanner.scan(jar);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not scan %s for @Mod classes", jar.getFileName());
				continue;
			}
			for (ModAnnotationScanner.ModClassInfo info : mods) {
				MultiLoaderArbiter.Ecosystem mine = info.family == Family.MINECRAFTFORGE
						? MultiLoaderArbiter.Ecosystem.MINECRAFTFORGE
						: MultiLoaderArbiter.Ecosystem.NEOFORGE;
				if (MultiLoaderArbiter.suppressedFor(jar, mine)) continue;

				claimed.add(info);
			}
		}

		// Phase 2 — build every NeoForge mod's identity and PUBLISH the whole set into ModList. This must happen
		// before phase 3: a mod's constructor may ask ModList about ITSELF, and against the kernel's empty ModList
		// that is fatal to that mod (Bookshelf: "Could not find mod 'bookshelf'" from getModBus; Architectury:
		// "Mod 'architectury' is not available!"). Publishing the set up front also makes inter-mod queries work
		// regardless of construction order.
		Map<String, NeoIdentity> neo = new LinkedHashMap<>();
		for (ModAnnotationScanner.ModClassInfo info : claimed) {
			if (info.family == Family.MINECRAFTFORGE) continue;

			String modId = safeId(info);
			if (neo.containsKey(modId)) continue;

			try {
				Object bus = KernelBusSupport.makeModBus(cl);
				neo.put(modId, new NeoIdentity(bus, KernelModContainerFactory.create(loader, cl, modId, bus)));
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not build ModContainer for NeoForge mod " + modId,
						KernelBusSupport.unwrap(t));
			}
		}
		publishedNeo = Map.copyOf(neo);
		publishNeoModList(cl, neo);

		// Phase 3 — construct.
		List<ConstructedMod> built = new ArrayList<>();
		Map<String, KernelForgeModContext.Handle> forge = new LinkedHashMap<>();
		for (ModAnnotationScanner.ModClassInfo info : claimed) {
			try {
				ConstructedMod mod = info.family == Family.MINECRAFTFORGE
						? constructForgeFamilyMod(cl, info)
						: constructNeoFamilyMod(cl, info, neo.get(safeId(info)), client);
				built.add(mod);
				// One handle per mod id — a mod may annotate several classes, and they share a bus group.
				if (mod.forgeHandle() != null) forge.putIfAbsent(mod.modId(), mod.forgeHandle());
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] failed to construct @Mod " + info.className,
						KernelBusSupport.unwrap(t));
			}
		}
		publishedForge = Map.copyOf(forge);
		return built;
	}

	/**
	 * Installs the kernel's NeoForge {@code ModContainer}s into the live {@code ModList}, so {@code isLoaded(id)} and
	 * {@code getModContainerById(id)} answer for mods the kernel loaded.
	 *
	 * <p>{@link PassiveSeeder#seedNeoForgeModList} seeds an EMPTY list purely so early merged-base reads do not NPE;
	 * nothing ever put the constructed mods in it. That is invisible to a mod whose entrypoint only registers
	 * listeners — which is why the universal jars (FallingTree, collective) never showed it — but a library mod
	 * typically resolves its OWN container to get its event bus, and got an exception instead.
	 *
	 * <p>Only {@code mods}/{@code sortedContainers}/{@code indexedMods} (via {@code setLoadedMods}) and
	 * {@code sortedList} (via {@link #fillModInfos}) are written. {@code modFiles} stays empty, so the resource-pack
	 * path ({@code ResourcePackLoader.findResourcePacks} → {@code getModFiles}) is unchanged.
	 */
	private static void publishNeoModList(ClassLoader cl, Map<String, NeoIdentity> neo) {
		if (neo.isEmpty()) return;
		if ("off".equalsIgnoreCase(System.getProperty("forbric.publishModList", "on"))) {
			ForbricLog.warn("[Forbric/ModLoader] ModList publishing DISABLED — mods that resolve their own "
					+ "container will fail (-Dforbric.publishModList=off)");
			return;
		}

		try {
			Class<?> modListCls = Class.forName("net.neoforged.fml.ModList", false, cl);
			Object modList = modListCls.getMethod("get").invoke(null);
			if (modList == null) return;

			List<Object> containers = new ArrayList<>();
			for (NeoIdentity identity : neo.values()) {
				containers.add(identity.container());
			}
			Method setLoadedMods = modListCls.getDeclaredMethod("setLoadedMods", List.class);
			setLoadedMods.setAccessible(true);
			setLoadedMods.invoke(modList, containers);
			publishModInfos(cl, modListCls, modList, containers);
			ForbricLog.info("[Forbric/ModLoader] published %d NeoForge mod(s) into ModList %s — mods that resolve "
					+ "their own container (event bus, config) now find themselves", containers.size(), neo.keySet());
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/ModLoader] NeoForge ModList not present — nothing to publish");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ModLoader] could not publish NeoForge ModList "
					+ "(mods that look themselves up will fail)", KernelBusSupport.unwrap(t));
		}
	}

	/**
	 * Fills {@code ModList.sortedList} — the field {@code getMods()} returns — with the published containers' own
	 * {@code IModInfo}s.
	 *
	 * <p>{@code setLoadedMods} writes only {@code mods}/{@code sortedContainers}/{@code indexedMods}.
	 * {@code sortedList} is a separate FINAL field written once in {@code ModList}'s private constructor, and
	 * {@link PassiveSeeder#seedNeoForgeModList} necessarily constructs the singleton as
	 * {@code ModList.of(List.of(), List.of())} — long before any mod is known. So {@code getModContainerById(id)}
	 * answered correctly for every kernel-loaded mod while {@code getMods()} answered EMPTY. That split is invisible
	 * until a mod ENUMERATES the list instead of asking for itself by id.
	 *
	 * <p>Sodium is where it turned fatal. Its {@code Minecraft.<init>} mixin calls
	 * {@code ConfigLoaderForge.collectConfigEntryPoints}, which walks {@code getMods()} for the entry whose
	 * {@code getModId()} is {@code "sodium"} in order to register SODIUM'S OWN config; against an empty list it
	 * registers nothing and {@code ConfigManager.registerConfigs} throws {@code "Sodium mod config not found"} before
	 * the window ever opens. The same walk is how every other mod declares a config entry point (via the
	 * {@code sodium:config_api_user} mod property), and Sodium's {@code @Mod} ctor walks it again for FlawlessFrames
	 * providers — that one silently.
	 *
	 * <p>{@code modFiles} is deliberately still NOT filled: it feeds the resource-pack path and
	 * {@code getAllScanData()}, and carries the gate-m7-neo {@code revertToVanilla} risk. This writes only what
	 * {@code getMods()} reads.
	 */
	private static void publishModInfos(ClassLoader cl, Class<?> modListCls, Object modList, List<Object> containers) {
		try {
			// The method is looked up on the abstract ModContainer, not on each container's own class: the
			// implementations are a kernel-generated subclass and a genuine FMLModContainer, and only the declaring
			// type guarantees a publicly accessible handle for both.
			Class<?> modContainerCls = Class.forName("net.neoforged.fml.ModContainer", false, cl);
			fillModInfos(modListCls, modList, containers, modContainerCls.getMethod("getModInfo"));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ModLoader] could not fill ModList.getMods() — mods that ENUMERATE the mod list "
					+ "(Sodium's config entry points, FlawlessFrames) will find nothing", KernelBusSupport.unwrap(t));
		}
	}

	/** The reflective half of {@link #publishModInfos}, split out so a test can drive it with stand-in types. */
	static void fillModInfos(Class<?> modListCls, Object modList, List<Object> containers, Method getModInfo)
			throws Exception {
		List<Object> infos = new ArrayList<>(containers.size());
		for (Object container : containers) {
			Object info = getModInfo.invoke(container);
			// A container whose info is null would NPE every consumer that reads getModId() off the list.
			if (info != null) infos.add(info);
		}
		// setAccessible(true) is enough for a NON-STATIC final field — the same JLS carve-out PassiveSeeder relies on.
		Field sortedList = modListCls.getDeclaredField("sortedList");
		sortedList.setAccessible(true);
		sortedList.set(modList, infos);
		ForbricLog.debug("[Forbric/ModLoader] ModList.getMods() now answers with %d mod(s)", infos.size());
	}

	/** Traditional MinecraftForge: a genuine BusGroup + FMLModContainer + FMLJavaModLoadingContext. */
	private static ConstructedMod constructForgeFamilyMod(ClassLoader cl, ModAnnotationScanner.ModClassInfo info)
			throws Exception {
		if (!KernelForgeModContext.available(cl)) {
			throw new IllegalStateException("traditional-Forge @Mod " + info.className
					+ " but the merged base carries no javafmlmod — is the Forge family on this runtime?");
		}
		String modId = safeId(info);
		KernelForgeModContext.Handle handle = KernelForgeModContext.create(cl, modId);
		Object instance = KernelForgeModContext.constructMod(cl, info.className, handle);
		// The EventBus 7 startup() gate: the mod's ctor has registered its listeners (DeferredRegister etc.) by now,
		// and nothing dispatches on the group until it opens.
		KernelForgeModContext.startup(cl, handle.busGroup());
		ForbricLog.info("[Forbric/ModLoader] constructed @Mod %s (traditional-Forge, %s) -> %s", modId, info.className,
				instance);
		return new ConstructedMod(modId, info.className, info.family, null, handle);
	}

	/** NeoForge: the mod's pre-published bus + ModContainer, ctor filled by parameter type. */
	private static ConstructedMod constructNeoFamilyMod(ClassLoader cl, ModAnnotationScanner.ModClassInfo info,
			NeoIdentity identity, boolean client) throws Exception {
		String modId = safeId(info);
		if (identity == null) {
			throw new IllegalStateException("no ModContainer was built for NeoForge @Mod " + modId);
		}
		Object bus = identity.bus();
		Object container = identity.container();
		Object instance;
		// The NeoForge twin of the traditional-Forge active-container handling: a @Mod ctor that calls
		// ModLoadingContext.get().registerExtensionPoint(...) — the standard config-screen registration — reads
		// getActiveContainer(), which without one set falls back to looking up the "minecraft" container in the
		// (kernel-empty) ModList and throws "Where is minecraft???!". Set this mod's container for the duration of
		// its construction and clear it after, so nothing later registers under a stale namespace.
		setNeoActiveContainer(cl, container);
		try {
			instance = constructNeoMod(cl, info.className, bus, container, client);
		} finally {
			setNeoActiveContainer(cl, null);
		}
		ForbricLog.info("[Forbric/ModLoader] constructed @Mod %s (NeoForge, %s) -> %s", modId, info.className, instance);
		return new ConstructedMod(modId, info.className, info.family, bus, null);
	}

	/** Sets (or clears, with null) NeoForge's thread-local active {@code ModContainer}. Best-effort. */
	private static void setNeoActiveContainer(ClassLoader cl, Object container) {
		try {
			Class<?> mlcCls = Class.forName("net.neoforged.fml.ModLoadingContext", false, cl);
			Class<?> modContainer = Class.forName("net.neoforged.fml.ModContainer", false, cl);
			Object mlc = mlcCls.getMethod("get").invoke(null);
			mlcCls.getMethod("setActiveContainer", modContainer).invoke(mlc, container);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ModLoader] could not set NeoForge active container: %s",
					String.valueOf(KernelBusSupport.unwrap(t)));
		}
	}

	/** Constructs a NeoForge {@code @Mod} by filling its (widest public) constructor's params by type. */
	private static Object constructNeoMod(ClassLoader cl, String className, Object bus, Object container,
			boolean client) throws Exception {
		Class<?> modCls = Class.forName(className, true, cl);
		Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", false, cl);
		Class<?> modContainer = Class.forName("net.neoforged.fml.ModContainer", false, cl);
		Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), client ? "CLIENT" : "DEDICATED_SERVER");

		Constructor<?> best = null;
		for (Constructor<?> c : modCls.getConstructors()) {
			if (best == null || c.getParameterCount() > best.getParameterCount()) best = c;
		}
		if (best == null) throw new NoSuchMethodException("no public constructor on " + className);

		Class<?>[] params = best.getParameterTypes();
		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			if (iEventBus.isAssignableFrom(params[i]) || params[i] == iEventBus) {
				args[i] = bus;
			} else if (params[i] == distClass) {
				args[i] = dist;
			} else if (params[i] == modContainer || params[i].isAssignableFrom(container.getClass())) {
				args[i] = container;
			} else {
				// A param shape the kernel does not model. Passing null here silently produced mods that NPE'd inside
				// their own ctor (M7 Wall A) — fail loudly instead so the gap names itself.
				throw new IllegalArgumentException("unmodelled @Mod ctor param " + params[i].getName() + " on "
						+ className);
			}
		}
		best.setAccessible(true);
		return best.newInstance(args);
	}

	private static String safeId(ModAnnotationScanner.ModClassInfo info) {
		return info.modId != null ? info.modId : info.className;
	}
}
