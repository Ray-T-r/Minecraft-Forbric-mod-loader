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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.forbric.api.Side;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

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
	public record ConstructedMod(String modId, String className, Ecosystem family, Object bus,
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
	public static List<ConstructedMod> constructMods(ClassLoader cl, List<Path> modJars, Side side) {
		// Phase 1 — scan and ARBITRATE everything first, so the full NeoForge mod set is known before any mod's
		// constructor runs. A universal jar ships one @Mod per family; only the family that OWNS the jar may
		// construct, or the same mod initialises once per live ecosystem (see MultiLoaderArbiter).
		List<ModAnnotationScanner.ModClassInfo> claimed = new ArrayList<>();
		// modId -> the jar it came from, so its container can hand the mod its OWN files (see
		// KernelModContainerFactory.create's jar parameter).
		Map<String, Path> jarOfMod = new LinkedHashMap<>();
		for (Path jar : modJars) {
			List<ModAnnotationScanner.ModClassInfo> mods;
			try {
				mods = ModAnnotationScanner.scan(jar);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not scan %s for @Mod classes", jar.getFileName());
				continue;
			}
			for (ModAnnotationScanner.ModClassInfo info : mods) {
				if (MultiLoaderArbiter.suppressedFor(jar, info.family)) continue;

				claimed.add(info);
				if (info.modId != null) jarOfMod.putIfAbsent(info.modId, jar);
			}
		}

		// Phase 2 — build every NeoForge mod's identity. Both families' identities are PUBLISHED below, before
		// phase 3, because a mod's constructor may ask its family's ModList about ITSELF, and against the kernel's
		// empty ModList that is fatal to that mod (Bookshelf: "Could not find mod 'bookshelf'" from getModBus;
		// Architectury: "Mod 'architectury' is not available!"). Publishing both sets up front also makes inter-mod
		// queries work regardless of construction order.
		//
		// The price, paid on both sides: between the publish and a given mod's own construction its container is
		// half-built — {@code getMod()} answers null where an unpublished list answered an honest empty Optional.
		// That is the better trade for the shape these mods actually use (resolve my container, take its bus), and
		// it is the shape to recognise if a mod ever complains that a NEIGHBOUR exists but has no instance.
		Map<String, NeoIdentity> neo = new LinkedHashMap<>();
		for (ModAnnotationScanner.ModClassInfo info : claimed) {
			if (info.family == Ecosystem.FORGE) continue;

			String modId = safeId(info);
			if (neo.containsKey(modId)) continue;

			try {
				Object bus = KernelBusSupport.makeModBus(cl);
				neo.put(modId, new NeoIdentity(bus,
						KernelModContainerFactory.create(cl, modId, bus, jarOfMod.get(modId))));
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not build ModContainer for NeoForge mod " + modId,
						Reflect.unwrap(t));
			}
		}
		// Presence aliases: a mod whose NeoForge jar lost cross-jar arbitration is still HERE — the winner's jar is
		// 98–100% the same classes — but without an entry ModList.get().isLoaded(id) answers false, and a NeoForge
		// mod that gates an integration on that check silently disables it. A container with no @Mod behind it:
		// identity only, since the winner already ran the mod's initialisation and registered its content.
		Map<String, NeoIdentity> aliases = new LinkedHashMap<>();
		for (DuplicateModArbiter.Alias alias
				: DuplicateModArbiter.current().aliasesFor(Ecosystem.NEOFORGE)) {
			if (neo.containsKey(alias.modId())) continue; // a real @Mod already owns it
			try {
				Object bus = KernelBusSupport.makeModBus(cl);
				aliases.put(alias.modId(),
						new NeoIdentity(bus, KernelModContainerFactory.create(cl, alias.modId(), bus)));
				ForbricLog.info("[Forbric/ModLoader] presence alias '%s' — its NeoForge jar lost arbitration, but "
						+ "the winning jar supplies the classes; ModList.isLoaded now answers", alias.modId());
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not alias " + alias.modId() + " into ModList",
						Reflect.unwrap(t));
			}
		}
		// Aliases go into ModList but NOT into publishedNeo: nothing must post setup events at a mod that has no
		// @Mod class here, and no caller should resolve an alias as if it were a constructed mod.
		Map<String, NeoIdentity> published = new LinkedHashMap<>(neo);
		published.putAll(aliases);

		// Phase 2b — the traditional-MinecraftForge twin of phase 2, and the reason it can exist at all: a
		// MinecraftForge container needs only the mod ID (KernelForgeContainers.create), never the @Mod class,
		// which constructMod marries in afterwards. Splitting those two halves is what lets the publish happen
		// before ANY constructor runs.
		//
		// It has to. libraryferret and awesomedungeonocean both resolve their OWN container from a class
		// initializer their constructor reaches — RegistryProviderForgeImpl.getIEventBus -> ModList
		// .getModContainerById -> orElseThrow("Mod with ID \"...\" not found") — and a class initializer is a
		// one-shot with an empty exception table, so it is erroneous for the rest of the run (the a56852b wall,
		// one layer up). The cost is not those two mods: their structure_type/structure_placement registries never
		// register, their data/ entries then fail to parse, and the SAVE will not open at all.
		Map<String, KernelForgeModContext.Handle> forge;
		if (KernelForgeModContext.available(cl)) {
			forge = buildForgeHandles(claimed, modId -> KernelForgeModContext.create(cl, modId));
		} else {
			forge = new LinkedHashMap<>();
			for (ModAnnotationScanner.ModClassInfo info : claimed) {
				if (info.family != Ecosystem.FORGE) continue;
				ForbricLog.warn("[Forbric/ModLoader] traditional-Forge @Mod %s (%s) but the merged base carries no "
						+ "javafmlmod — is the Forge family on this runtime?", safeId(info), info.className);
			}
		}

		publishedNeo = Map.copyOf(neo);
		publishNeoModList(cl, published);
		publishedForge = Map.copyOf(forge);
		publishForgeModList(cl, publishedForge, false);

		// Phase 3 — construct.
		List<ConstructedMod> built = new ArrayList<>();
		Set<String> constructed = new LinkedHashSet<>();
		for (ModAnnotationScanner.ModClassInfo info : claimed) {
			try {
				ConstructedMod mod = info.family == Ecosystem.FORGE
						? constructForgeFamilyMod(cl, info, forge.get(safeId(info)))
						: constructNeoFamilyMod(cl, info, neo.get(safeId(info)), side);
				built.add(mod);
				if (mod.forgeHandle() != null) constructed.add(mod.modId());
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] failed to construct @Mod " + info.className,
						Reflect.unwrap(t));
			}
		}
		// A mod whose constructor threw must come back OUT. This cannot un-tell it anything it learned during its
		// own construction, and it deliberately does not change isLoaded — ForeignModPresenceInjector rewrites
		// MinecraftForge's isLoaded to OR in the kernel's discovery-fed ModPresence, which answers for a mod that
		// is HERE whether or not its constructor ran. What it fixes is getModContainerById/getMods/size handing
		// back a container that passes instanceof FMLModContainer and yields a live-looking BusGroup that nothing
		// will ever post a RegisterEvent on.
		if (constructed.size() != forge.size()) {
			Map<String, KernelForgeModContext.Handle> kept = new LinkedHashMap<>();
			List<String> dropped = new ArrayList<>();
			for (Map.Entry<String, KernelForgeModContext.Handle> e : forge.entrySet()) {
				if (constructed.contains(e.getKey())) kept.put(e.getKey(), e.getValue());
				else dropped.add(e.getKey());
			}
			publishedForge = Map.copyOf(kept);
			// allowEmpty: "every MinecraftForge mod failed" must publish an EMPTY list, not leave the full one up.
			publishForgeModList(cl, publishedForge, true);
			ForbricLog.warn("[Forbric/ModLoader] withdrew %d MinecraftForge container(s) from ModList — their @Mod "
					+ "constructor threw, so nothing will ever fire RegisterEvent on the bus those containers "
					+ "hand out %s", dropped.size(), dropped);
		}
		return built;
	}

	/** Makes a MinecraftForge loading context per mod ID. Separate from the game classes so a test can drive it. */
	@FunctionalInterface
	interface ForgeHandleFactory {
		KernelForgeModContext.Handle create(String modId) throws Exception;
	}

	/**
	 * One handle per traditional-MinecraftForge mod ID, in {@code claimed} order.
	 *
	 * <p>Deduped by ID, not by {@code @Mod} class: {@code ModList.setLoadedMods} builds its index with
	 * {@code toUnmodifiableMap(ModContainer::getModId, identity())}, so two containers sharing an ID throw
	 * "Duplicate key" and cost every mod its list. Deduping here also means one {@code BusGroup} per ID where
	 * two {@code @Mod} classes with the same ID used to manufacture two groups under the same name.
	 *
	 * <p>A factory that throws costs only that mod its container; the rest still get theirs, and the mod that
	 * failed is then rejected by {@link #constructForgeFamilyMod} rather than constructed against nothing.
	 */
	static Map<String, KernelForgeModContext.Handle> buildForgeHandles(
			List<ModAnnotationScanner.ModClassInfo> claimed, ForgeHandleFactory factory) {
		Map<String, KernelForgeModContext.Handle> forge = new LinkedHashMap<>();
		for (ModAnnotationScanner.ModClassInfo info : claimed) {
			if (info.family != Ecosystem.FORGE) continue;
			String modId = safeId(info);
			if (forge.containsKey(modId)) continue;
			try {
				forge.put(modId, factory.create(modId));
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not build the MinecraftForge loading context for "
						+ modId, Reflect.unwrap(t));
			}
		}
		return forge;
	}

	/**
	 * The traditional-Forge twin of {@link #publishNeoModList}: puts the constructed MinecraftForge mods into
	 * {@code net.minecraftforge.fml.ModList} so its own {@code isLoaded} / {@code getModContainerById} answer.
	 *
	 * <p>That map is what a Forge mod asks about an optional dependency, and under the kernel it was empty — every
	 * such question answered "absent" for mods that are right there. Separate from the loading list seeded in
	 * {@link PassiveSeeder}: that one feeds {@code getMods()} (the mod INFO the handshake puts on the wire), this
	 * one feeds the container lookups. Forge sorts the containers by their position in the loading list; a
	 * container whose info is not in it sorts as -1, which is stable and harmless.
	 */
	private static void publishForgeModList(ClassLoader cl, Map<String, KernelForgeModContext.Handle> forge,
			boolean allowEmpty) {
		boolean enabled = !"off".equalsIgnoreCase(System.getProperty("forbric.publishModList", "on"));
		if (!enabled) {
			ForbricLog.warn("[Forbric/ModLoader] MinecraftForge ModList publishing DISABLED "
					+ "(-Dforbric.publishModList=off) — a Forge mod that resolves its own container during "
					+ "construction will now FAIL, not merely get \"absent\" from a later lookup");
			return;
		}
		if (!allowEmpty && forge.isEmpty()) return;
		try {
			Class<?> modListCls = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.FORGE), false, cl);
			Class<?> containerCls = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.FORGE), false, cl);
			List<Object> containers = new ArrayList<>();
			for (KernelForgeModContext.Handle handle : forge.values()) {
				if (containerCls.isInstance(handle.container())) containers.add(handle.container());
			}
			Method setLoadedMods = modListCls.getDeclaredMethod("setLoadedMods", List.class);
			boolean wrote = publishForgeContainers(containers, allowEmpty, list -> {
				setLoadedMods.setAccessible(true);
				setLoadedMods.invoke(null, list); // static on MinecraftForge; NeoForge's twin is an instance method
			});
			if (!wrote) return;
			ForbricLog.info("[Forbric/ModLoader] published %d MinecraftForge mod(s) into its ModList %s — its own "
					+ "isLoaded/getModContainerById answered \"absent\" for every one of them until now",
					containers.size(), forge.keySet());
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/ModLoader] traditional-Forge ModList not present — nothing to publish");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ModLoader] could not publish into MinecraftForge's ModList — a Forge mod asking "
					+ "whether another is loaded still gets no", Reflect.unwrap(t));
		}
	}

	/** Writes the container list into MinecraftForge's ModList. Split out so a test can drive the decision. */
	@FunctionalInterface
	interface ForgeListWriter {
		void write(List<Object> containers) throws Exception;
	}

	/**
	 * The decision half of {@link #publishForgeModList}: writes unless the list is empty and empty is not allowed.
	 *
	 * <p>{@code allowEmpty} exists for the withdrawal path. {@code setLoadedMods} REPLACES the list, so the only
	 * way to take a failed mod's container back out is to write the survivors — and if there are no survivors,
	 * an empty write is the correct answer and a skipped write leaves the full, wrong list standing.
	 *
	 * @return whether it wrote
	 */
	static boolean publishForgeContainers(List<Object> containers, boolean allowEmpty, ForgeListWriter writer)
			throws Exception {
		if (!allowEmpty && containers.isEmpty()) return false;
		writer.write(containers);
		return true;
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
			Class<?> modListCls = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, cl);
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
					+ "(mods that look themselves up will fail)", Reflect.unwrap(t));
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
			Class<?> modContainerCls = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
			fillModInfos(modListCls, modList, containers, modContainerCls.getMethod("getModInfo"));
			publishFileById(cl, modListCls, modList, containers);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ModLoader] could not fill ModList.getMods() — mods that ENUMERATE the mod list "
					+ "(Sodium's config entry points, FlawlessFrames) will find nothing", Reflect.unwrap(t));
		}
	}

	/**
	 * Makes {@code ModList.getModFileById(id)} answer for every loaded mod.
	 *
	 * <p>{@code javap} on NeoForge's {@code ModList}: {@code getModFileById} is {@code fileById.get(id)} followed by
	 * a checkcast to {@code IModFileInfo}, and nothing else in the kernel's routing ever wrote that map. So the
	 * method returned null for every mod the kernel loaded, and {@code ModList.get().getModFileById(MODID)
	 * .getFile()...} — a common enough line that it appears in mods' own version checks and resource lookups —
	 * NPE'd on the spot with nothing in the log.
	 *
	 * <p>Each mod's file comes from its own {@code IModInfo.getOwningFile()}, which is the same object NeoForge's
	 * own discovery would have put there.
	 */
	static void publishFileById(ClassLoader cl, Class<?> modListCls, Object modList, List<Object> containers)
			throws Exception {
		Class<?> modContainerCls = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
		Class<?> modInfoCls = Class.forName(ForeignType.MOD_INFO_SPI.binary(Ecosystem.NEOFORGE), false, cl);
		int added = fillFileById(modListCls, modList, containers, modContainerCls.getMethod("getModInfo"),
				modInfoCls.getMethod("getModId"), modInfoCls.getMethod("getOwningFile"));
		ForbricLog.debug("[Forbric/ModLoader] ModList.getModFileById now answers for %d mod(s)", added);
	}

	/**
	 * The reflective half of {@link #publishFileById}, split out so a test can drive it with stand-in types.
	 *
	 * <p>Merges rather than replaces, because more than one pass publishes containers and the later one must not
	 * drop what the earlier one answered for. A container that cannot produce a file is skipped rather than
	 * aborting the map: one odd mod must not be what costs every other mod its {@code getModFileById}.
	 *
	 * @return how many ids the map gained
	 */
	static int fillFileById(Class<?> modListCls, Object modList, List<Object> containers, Method getModInfo,
			Method getModId, Method getOwningFile) throws Exception {
		Field field = modListCls.getDeclaredField("fileById");
		field.setAccessible(true);

		Map<String, Object> merged = new LinkedHashMap<>();
		if (field.get(modList) instanceof Map<?, ?> existing) {
			for (Map.Entry<?, ?> entry : existing.entrySet()) {
				if (entry.getKey() != null) merged.put(String.valueOf(entry.getKey()), entry.getValue());
			}
		}

		int added = 0;
		for (Object container : containers) {
			try {
				Object info = getModInfo.invoke(container);
				if (info == null) continue;
				Object file = getOwningFile.invoke(info);
				if (file == null) continue;
				Object id = getModId.invoke(info);
				if (!(id instanceof String modId) || modId.isEmpty()) continue;
				if (merged.put(modId, file) == null) added++;
			} catch (ReflectiveOperationException oneMod) {
				ForbricLog.debug("[Forbric/ModLoader] a container could not name its mod file: %s",
						String.valueOf(Reflect.unwrap(oneMod)));
			}
		}

		field.set(modList, merged);
		return added;
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

	/**
	 * Traditional MinecraftForge: constructs the {@code @Mod} class against the handle phase 2b already published.
	 *
	 * <p>The active container is set explicitly around the constructor, the way the NeoForge twin does it, rather
	 * than left to the side effect {@code KernelForgeContainers.create} used to have. It has to be explicit now
	 * that creation and construction are separated, and being explicit also closes the leak the side effect left:
	 * nothing cleared it between the last Forge constructor and {@code fireRegisterEvents}' own finally.
	 * {@code ModLoadingContext.getActiveNamespace()} answers "minecraft" when nothing is active rather than
	 * throwing, so BOTH failure modes — the stale container and the missing one — are silent, and both put a mod's
	 * id-less registrations under someone else's namespace.
	 */
	private static ConstructedMod constructForgeFamilyMod(ClassLoader cl, ModAnnotationScanner.ModClassInfo info,
			KernelForgeModContext.Handle handle) throws Exception {
		String modId = safeId(info);
		if (handle == null) {
			throw new IllegalStateException("no MinecraftForge loading context was built for @Mod " + info.className
					+ " (id " + modId + ") — it is not in ModList, so anything resolving it by id is told it is "
					+ "absent");
		}
		KernelForgeModContext.setActiveContainer(cl, handle.container());
		Object instance;
		try {
			instance = KernelForgeModContext.constructMod(cl, info.className, handle);
		} finally {
			KernelForgeModContext.setActiveContainer(cl, null);
		}
		// The EventBus 7 startup() gate: the mod's ctor has registered its listeners (DeferredRegister etc.) by now,
		// and nothing dispatches on the group until it opens.
		KernelForgeModContext.startup(cl, handle.busGroup());
		ForbricLog.info("[Forbric/ModLoader] constructed @Mod %s (traditional-Forge, %s) -> %s", modId, info.className,
				instance);
		return new ConstructedMod(modId, info.className, info.family, null, handle);
	}

	/** NeoForge: the mod's pre-published bus + ModContainer, ctor filled by parameter type. */
	private static ConstructedMod constructNeoFamilyMod(ClassLoader cl, ModAnnotationScanner.ModClassInfo info,
			NeoIdentity identity, Side side) throws Exception {
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
			instance = constructNeoMod(cl, info.className, bus, container, side);
		} finally {
			setNeoActiveContainer(cl, null);
		}
		ForbricLog.info("[Forbric/ModLoader] constructed @Mod %s (NeoForge, %s) -> %s", modId, info.className, instance);
		return new ConstructedMod(modId, info.className, info.family, bus, null);
	}

	/**
	 * Sets (or clears, with null) NeoForge's thread-local active {@code ModContainer}. Best-effort.
	 *
	 * <p>Package-visible because construction is not the only window that needs it: {@code KernelLifecycle} must
	 * set it around each mod's SETUP events too — see the call there for what breaks without it.
	 */
	static void setNeoActiveContainer(ClassLoader cl, Object container) {
		try {
			Class<?> mlcCls = Class.forName(ForeignType.MOD_LOADING_CONTEXT.binary(Ecosystem.NEOFORGE), false, cl);
			Class<?> modContainer = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
			Object mlc = mlcCls.getMethod("get").invoke(null);
			mlcCls.getMethod("setActiveContainer", modContainer).invoke(mlc, container);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ModLoader] could not set NeoForge active container: %s",
					String.valueOf(Reflect.unwrap(t)));
		}
	}

	/** Constructs a NeoForge {@code @Mod} by filling its (widest public) constructor's params by type. */
	private static Object constructNeoMod(ClassLoader cl, String className, Object bus, Object container,
			Side side) throws Exception {
		Class<?> modCls = Class.forName(className, true, cl);
		Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		Class<?> distClass = Class.forName(ForeignType.DIST.binary(Ecosystem.NEOFORGE), false, cl);
		Class<?> modContainer = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
		Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), side.distName());

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
