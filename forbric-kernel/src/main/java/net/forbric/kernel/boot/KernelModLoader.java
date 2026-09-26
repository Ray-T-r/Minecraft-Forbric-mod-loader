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

import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.api.Side;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModAnnotationScanner;
import net.forbric.kernel.metadata.forge.LanguageProviders;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.forbric.api.ModCatalog;

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
	 * The part of {@link #publishedNeoMods()} that declares no {@code @Mod} class — see
	 * {@link #declaredWithoutClass}. Its buses carry only what an {@code @EventBusSubscriber} put there, and the
	 * lifecycle needs them apart because it posts {@code RegisterEvent} per CONSTRUCTED mod, a list these are
	 * deliberately not in.
	 */
	public static Map<String, NeoIdentity> classlessNeoMods() {
		return classlessNeo;
	}

	private static volatile Map<String, NeoIdentity> classlessNeo = Map.of();

	/** The jar that declares each of {@link #classlessNeoMods()}. */
	private static volatile Map<String, Path> classlessNeoJars = Map.of();

	/**
	 * The one mod of {@link #classlessNeoMods()} that {@code jar} declares, or null when it declares none or several.
	 *
	 * <p>FML injects a jar's {@code @EventBusSubscriber} classes through the containers of that jar's mods, and a
	 * subscriber that names no mod id belongs to the container injecting it. A mod with no {@code @Mod} class gets
	 * a container like any other, so an unnamed subscriber in its jar is its own — which only this can say, since
	 * no {@code @Mod} class in that jar names it.
	 */
	static String soleClasslessNeoModIn(Path jar) {
		String only = null;
		for (Map.Entry<String, Path> entry : classlessNeoJars.entrySet()) {
			if (!entry.getValue().equals(jar)) continue;
			if (only != null) return null;
			only = entry.getKey();
		}
		return only;
	}

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

	/**
	 * MinecraftForge low-code mods' {@code LowCodeModContainer}s, by id. Not handles: such a container has no bus
	 * group and no loading context, so nothing that walks {@link #publishedForgeMods()} has anything to do with
	 * one — but every write of MinecraftForge's {@code ModList} carries them, because that write REPLACES.
	 */
	private static volatile Map<String, Object> lowCodeForge = Map.of();

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

		// What each jar's own manifest declares, by id. Every container below is described with it, and it is the
		// only list that knows a mod with no @Mod class at all. See declaredMods.
		Map<String, Declared> declared = declaredMods(modJars);

		// Phase 1b — put them in DEPENDENCY order. Until now this list was in jar-file-name order, alphabetically,
		// which is not an order at all: a mod whose jar sorts before a library it requires was constructed first
		// and called that library's API before the library had initialised. What comes back is an error inside the
		// library, attributed to the library, on a line that has nothing to do with the cause. Both real loaders
		// sort by dependency before they construct anything.
		claimed = orderByDependency(claimed);

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
				Path jar = jarOfMod.get(modId);
				neo.put(modId, new NeoIdentity(bus,
						KernelModContainerFactory.create(cl, modId, bus, jar, declaredIn(declared, modId, jar))));
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
		// Declared-only mods: a [[mods]] entry with no @Mod class behind it. That is a legal NeoForge mod — FML gives
		// every mod it lists an FMLModContainer, classes or not, and lowcodefml is mapped onto the same provider —
		// but the kernel built containers only for @Mod classes, so these were in no ModList at all. LibJF paid for
		// it: its Translate module is exactly this shape, LibJF enumerates ModList for `libjf:config` entry points,
		// and the config native NeoForge registers for it never existed here. So were the Modrinth datapack
		// wrappers (mr_fall_effects, mr_no_croptrample), which native NeoForge lists and version-checks.
		//
		// Unlike an alias these ARE the mod, not a stand-in for one that loaded under another jar: they stay in
		// publishedNeo so an @EventBusSubscriber naming them finds their bus and the lifecycle reaches it. They
		// are kept out of `neo`, whose ids are settled by what their constructors did — a mod with no constructor
		// cannot have one that threw.
		Set<String> taken = new LinkedHashSet<>(neo.keySet());
		for (ModAnnotationScanner.ModClassInfo info : claimed) taken.add(safeId(info));
		taken.addAll(aliases.keySet());
		Map<String, NeoIdentity> classless = new LinkedHashMap<>();
		Map<String, Path> classlessJars = new LinkedHashMap<>();
		for (Declared entry : declaredWithoutClass(declared, taken, Ecosystem.NEOFORGE)) {
			String modId = entry.mod().getId();
			try {
				Object bus = KernelBusSupport.makeModBus(cl);
				classless.put(modId, new NeoIdentity(bus,
						KernelModContainerFactory.create(cl, modId, bus, entry.jar(), entry.mod())));
				classlessJars.put(modId, entry.jar());
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/ModLoader] could not build a ModContainer for NeoForge mod " + modId
						+ ", which declares no @Mod class", Reflect.unwrap(t));
			}
		}
		if (!classless.isEmpty()) {
			ForbricLog.info("[Forbric/ModLoader] %d NeoForge mod(s) declare no @Mod class and now have a container "
					+ "of their own, as NeoForge gives every mod it lists — ModList, a config, and a bus for their "
					+ "@EventBusSubscriber classes (-D%s=off to go back): %s", classless.size(), CLASSLESS_SWITCH,
					classless.keySet());
		}
		classlessNeo = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(classless));
		classlessNeoJars = Map.copyOf(classlessJars);

		// Aliases go into ModList but NOT into publishedNeo: nothing must post setup events at a mod that has no
		// @Mod class here, and no caller should resolve an alias as if it were a constructed mod.
		Map<String, NeoIdentity> published = new LinkedHashMap<>(neo);
		published.putAll(classless);
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
			// The MinecraftForge twin of the declared-only NeoForge mods above, narrower because its loader is:
			// MinecraftForge builds a container for a mod with no @Mod class only under lowcodefml (a
			// LowCodeModContainer, no bus), and a javafml one is its own load error ("missingclasses"), which must
			// not be papered over with a container here. Dungeons and Taverns ships exactly that low-code shape and
			// is in native MinecraftForge's ModList; here it was in none.
			Set<String> forgeTaken = new LinkedHashSet<>(taken);
			forgeTaken.addAll(forge.keySet());
			forgeTaken.addAll(classless.keySet());
			Map<String, Object> lowCode = new LinkedHashMap<>();
			for (Declared entry : declaredWithoutClass(declared, forgeTaken, Ecosystem.FORGE)) {
				String modId = entry.mod().getId();
				try {
					lowCode.put(modId, KernelForgeModContext.lowCode(cl, modId, entry.jar()));
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/ModLoader] could not build a LowCodeModContainer for MinecraftForge mod "
							+ modId, Reflect.unwrap(t));
				}
			}
			if (!lowCode.isEmpty()) {
				ForbricLog.info("[Forbric/ModLoader] %d MinecraftForge low-code mod(s) now have the "
						+ "LowCodeModContainer MinecraftForge gives them (-D%s=off to go back): %s", lowCode.size(),
						CLASSLESS_SWITCH, lowCode.keySet());
			}
			lowCodeForge = java.util.Collections.unmodifiableMap(lowCode);
		} else {
			forge = new LinkedHashMap<>();
			for (ModAnnotationScanner.ModClassInfo info : claimed) {
				if (info.family != Ecosystem.FORGE) continue;
				ForbricLog.warn("[Forbric/ModLoader] traditional-Forge @Mod %s (%s) but the merged base carries no "
						+ "javafmlmod — is the Forge family on this runtime?", safeId(info), info.className);
			}
		}

		publishedNeo = Map.copyOf(withClassless(neo, classless));
		publishNeoModList(cl, published, false);
		publishedForge = Map.copyOf(forge);
		publishForgeModList(cl, publishedForge, false);

		// Phase 3 — construct.
		List<ConstructedMod> built = new ArrayList<>();
		Set<String> constructed = new LinkedHashSet<>();
		// @Mod classes this side is not supposed to construct. They keep their container — the mod IS installed,
		// and a neighbour asking about it must be told so — they simply do not run here.
		Set<String> otherSide = new LinkedHashSet<>();
		// NeoForge mod ids at least one of whose @Mod constructors threw, with the classes that threw. Kept apart
		// from otherSide because one id can be in both: see settleNeo.
		Map<String, List<String>> failedNeo = new LinkedHashMap<>();
		for (ModAnnotationScanner.ModClassInfo info : claimed) {
			// NeoForge's @Mod declares which sides it belongs to, and the kernel constructed every class on every
			// side regardless. Sodium's SodiumForgeMod says dist = {CLIENT}; on a dedicated server its constructor
			// reaches a client-only type and dies with a NoClassDefFoundError blamed on the mod.
			if (!info.runsOn(side.distName())) {
				recordNeoOutcome(info, false, null, otherSide, failedNeo);
				ForbricLog.info("[Forbric/ModLoader] @Mod %s (%s) declares it belongs to %s — not constructing it "
						+ "on %s, which is what its own annotation asks for", safeId(info), info.className,
						info.dists, side.distName());
				continue;
			}
			if (constructedInTheGameConstructor(info, side)) {
				// Kept IN ModList on purpose: the mod is here and will be constructed, just where MinecraftForge
				// constructs it. Withdrawing it now and putting it back would make every neighbour that resolves
				// it by id see a hole for the length of the window.
				DEFERRED_FORGE.add(info);
				constructed.add(safeId(info));
				ForbricLog.info("[Forbric/ModLoader] @Mod %s (traditional-Forge) will be constructed in the "
						+ "Minecraft.<init> window, where MinecraftForge's own ClientModLoader.begin(Minecraft, …) "
						+ "constructs it — here there is no Minecraft for its constructor to read", safeId(info));
				continue;
			}
			try {
				ConstructedMod mod = info.family == Ecosystem.FORGE
						? constructForgeFamilyMod(cl, info, forge.get(safeId(info)))
						: constructNeoFamilyMod(cl, info, neo.get(safeId(info)), side);
				built.add(mod);
				if (mod.forgeHandle() != null) constructed.add(mod.modId());
			} catch (Throwable t) {
				recordNeoOutcome(info, true, t, otherSide, failedNeo);
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
			List<String> dropped = new ArrayList<>();
			publishedForge = Map.copyOf(keepConstructed(forge, constructed, dropped));
			// allowEmpty: "every MinecraftForge mod failed" must publish an EMPTY list, not leave the full one up.
			publishForgeModList(cl, publishedForge, true);
			ForbricLog.warn("[Forbric/ModLoader] withdrew %d MinecraftForge container(s) from ModList — their @Mod "
					+ "constructor threw, so nothing will ever fire RegisterEvent on the bus those containers "
					+ "hand out %s", dropped.size(), dropped);
			markWithdrawn(dropped, "its @Mod constructor threw");
		}

		// The NeoForge twin of the withdrawal above, which only the MinecraftForge half used to have. A NeoForge
		// mod whose constructor threw stayed in ModList holding a container that passes instanceof FMLModContainer
		// and hands out a live-looking bus — so a LIBRARY mod resolving it and registering onto that bus was
		// registering into nothing, and a mod asking whether its dependency's container exists was told yes about
		// a mod that never finished loading.
		//
		// Presence aliases are deliberately kept: they have no @Mod class here by construction, so "did not
		// construct" is their normal state, not a failure. And the withdrawn mod's FILE entry stays in the by-id
		// map, because its jar really is present — what comes out is the container.
		//
		// Each id is settled from ALL of its @Mod classes (settleNeo): RollingGate's client-only class used to
		// stand in for its common class that threw, and the mod read as fine everywhere a player could look.
		Set<String> neoConstructed = new LinkedHashSet<>();
		for (ConstructedMod mod : built) {
			if (mod.forgeHandle() == null) neoConstructed.add(mod.modId());
		}
		NeoSettlement settled = settleNeo(otherSide, neoConstructed, failedNeo.keySet());
		Set<String> neoBuilt = settled.kept();
		markPartlyConstructed(settled.degraded(), failedNeo);
		if (neoNeedsWithdrawal(neo.keySet(), neoBuilt)) {
			List<String> droppedNeo = new ArrayList<>();
			Map<String, NeoIdentity> keptNeo = keepConstructed(neo, neoBuilt, droppedNeo);
			publishedNeo = Map.copyOf(withClassless(keptNeo, classless));

			Map<String, NeoIdentity> republish = new LinkedHashMap<>(keptNeo);
			// A declared-only mod has no constructor that could have thrown, so it stays whatever else went.
			republish.putAll(classless);
			republish.putAll(aliases);
			// allowEmpty: "every NeoForge mod failed and there are no aliases" must publish an EMPTY list rather
			// than leave the full one standing.
			publishNeoModList(cl, republish, true);
			ForbricLog.warn("[Forbric/ModLoader] withdrew %d NeoForge container(s) from ModList — their @Mod "
					+ "constructor threw, so the bus those containers hand out is one nothing will ever post "
					+ "to %s", droppedNeo.size(), droppedNeo);
			for (String id : droppedNeo) markWithdrawn(List.of(id), constructorThrew(failedNeo.get(id)));
		}
		return built;
	}

	/**
	 * The published entries whose {@code @Mod} constructor actually ran, in their original order.
	 *
	 * <p>Both families withdraw the same way, and the reason is the same on both: a container left standing for a
	 * mod that never constructed passes {@code instanceof} and hands out a live-looking event bus that nothing
	 * will ever post to. So a library mod resolving it registers into nothing, and the failure surfaces much
	 * later somewhere that names neither mod.
	 *
	 * @param dropped receives the ids that come out, in order, for the log line
	 */
	static <T> Map<String, T> keepConstructed(Map<String, T> published, Set<String> constructed,
			List<String> dropped) {
		Map<String, T> kept = new LinkedHashMap<>();
		for (Map.Entry<String, T> entry : published.entrySet()) {
			if (constructed.contains(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
			else dropped.add(entry.getKey());
		}
		return kept;
	}

	static final String NEO_TWIN_SWITCH = "forbric.neoTwinCtorFailure";

	/**
	 * The per-class half of what {@link #settleNeo} settles from: a class that does not run on this side, or a
	 * NeoForge class whose constructor threw, recorded under its id with the class that threw.
	 *
	 * <p>Pulled out of the construction loop so the wiring into settleNeo is tested, not only settleNeo: dropping
	 * either branch in the loop brought back RollingGate's masking while every settleNeo test stayed green.
	 */
	static void recordNeoOutcome(ModAnnotationScanner.ModClassInfo info, boolean runsHere, Throwable failure,
			Set<String> otherSide, Map<String, List<String>> failedNeo) {
		if (!runsHere) {
			otherSide.add(safeId(info));
			return;
		}
		if (failure != null && info.family != Ecosystem.FORGE) {
			failedNeo.computeIfAbsent(safeId(info), id -> new ArrayList<>()).add(info.className);
		}
	}

	/**
	 * The FAILED reason for a withdrawn NeoForge mod, naming the {@code @Mod} classes that threw when known. It starts
	 * with the plain reason, which is what {@code CompatibilityFindings} keys the constructor finding on.
	 */
	static String constructorThrew(List<String> classes) {
		return classes == null || classes.isEmpty() ? "its @Mod constructor threw"
				: "its @Mod constructor threw (" + String.join(", ", classes) + ")";
	}

	/**
	 * What the NeoForge side keeps after construction: {@code kept} is every id whose container stays in
	 * {@code ModList}, {@code degraded} the kept ids that also lost a constructor.
	 */
	record NeoSettlement(Set<String> kept, Set<String> degraded) {
	}

	/**
	 * Settles each NeoForge mod id from what happened to ALL of its {@code @Mod} classes, not to any one of them.
	 *
	 * <p>A mod may ship several {@code @Mod} classes under one id, and the common shape is a common class plus a
	 * {@code dist = CLIENT} one. This used to count an id as settled the moment ANY of its classes was other-side,
	 * and then compared the count with the number of published containers. RollingGate is exactly that shape:
	 * its common {@code RollingGate} threw, its client-only {@code RollingGateClient} put {@code rolling_gate} in
	 * the other-side set, the counts matched, and nothing was withdrawn or reported — the Mods screen and the
	 * compatibility report said OK while every rule it registers was missing, and {@code server_plus_plus}, which
	 * requires it, was told its dependency was live. notenoughcrashes has the same pair of classes.
	 *
	 * <ul>
	 *   <li>something of the id ran here: kept — and if something else of it threw, DEGRADED rather than
	 *       withdrawn, because one id is ONE container and ONE mod bus, so withdrawing would also cut the
	 *       listeners of the class that did construct (RollingGate on a client, where both classes run);</li>
	 *   <li>nothing ran here and nothing threw: every class is other-side, and the mod keeps its container as a
	 *       mod that is installed but does not run on this side — Sodium's CLIENT-only class on a server;</li>
	 *   <li>nothing ran here and something threw: withdrawn, whatever else it has on the other side (RollingGate
	 *       on a dedicated server).</li>
	 * </ul>
	 *
	 * <p>{@code -Dforbric.neoTwinCtorFailure=off} goes back to letting any other-side class stand for the id.
	 */
	static NeoSettlement settleNeo(Set<String> otherSide, Set<String> constructed, Set<String> failed) {
		Set<String> kept = new LinkedHashSet<>(constructed);
		Set<String> degraded = new LinkedHashSet<>();
		if ("off".equalsIgnoreCase(System.getProperty(NEO_TWIN_SWITCH, "on"))) {
			kept.addAll(otherSide);
			return new NeoSettlement(kept, degraded);
		}
		for (String id : otherSide) {
			if (!failed.contains(id)) kept.add(id);
		}
		for (String id : failed) {
			if (constructed.contains(id)) degraded.add(id);
		}
		return new NeoSettlement(kept, degraded);
	}

	/**
	 * Whether any published NeoForge container has to come out.
	 *
	 * <p>By membership, not by count. The count compared the kept set's SIZE with the published map's, and the
	 * kept set can hold an id the map never published — an other-side {@code @Mod} whose container could not be
	 * built at all — so each such id cancelled out one NeoForge mod whose constructor threw, and that dead
	 * container stayed. {@code -Dforbric.neoTwinCtorFailure=off} restores the count.
	 */
	static boolean neoNeedsWithdrawal(Set<String> published, Set<String> kept) {
		if ("off".equalsIgnoreCase(System.getProperty(NEO_TWIN_SWITCH, "on"))) return kept.size() != published.size();
		return !kept.containsAll(published);
	}

	/** One Forge-family {@code [[mods]]} entry and the jar whose manifest declares it. */
	record Declared(DiscoveredMod mod, Path jar) {
	}

	/**
	 * Every Forge-family mod the jars' own manifests declare, by id, first declaration winning.
	 *
	 * <p>Only the family that OWNS each jar counts: {@link MultiLoaderArbiter} has already given a universal jar to
	 * one family, and its other manifest describes a mod that is not being loaded as that family here.
	 *
	 * <p>This is the only description the kernel has of a mod nested inside another mod's jar. Discovery's
	 * {@code ModPresence} list is built from the jars in {@code mods/}, so the containers built for LibJF's twelve
	 * modules — every one of them a jar-in-jar — described themselves at version "0.0" with an empty
	 * {@code [modproperties]} table, and LibJF, which finds every one of its entry points in that table, found none
	 * of theirs.
	 */
	static Map<String, Declared> declaredMods(List<Path> modJars) {
		Map<String, Declared> out = new LinkedHashMap<>();
		// The seeder's discoverer: it has already parsed these jars, so they are not parsed (or logged) again here.
		ForbricModDiscoverer discoverer = PassiveSeeder.MANIFESTS;
		for (Path jar : modJars) {
			List<DiscoveredMod> mods;
			try {
				mods = discoverer.discoverJar(jar);
			} catch (Throwable t) {
				// The @Mod scan below reports an unreadable jar in its own words; one line per jar is enough.
				ForbricLog.debug("[Forbric/ModLoader] could not read the manifest of %s: %s", jar.getFileName(),
						String.valueOf(t));
				continue;
			}
			for (DiscoveredMod mod : mods) {
				if (!mod.getEcosystem().isForgeFamily()) continue;
				if (mod.getId() == null || mod.getId().isBlank()) continue;
				if (MultiLoaderArbiter.suppressedFor(jar, mod.getEcosystem())) continue;
				out.putIfAbsent(mod.getId(), new Declared(mod, jar));
			}
		}
		return out;
	}

	/** {@code -Dforbric.classlessModContainers=off} gives a mod with no {@code @Mod} class no container, as before. */
	static final String CLASSLESS_SWITCH = "forbric.classlessModContainers";

	/**
	 * The declared mods of {@code family} that no {@code @Mod} class claims and that the family's own loader
	 * would still give a container, in dependency order among themselves.
	 *
	 * <p>Which ones get a container is the loader's rule, not the kernel's. NeoForge's FancyModLoader gives one to
	 * every mod of a {@code javafml} file whether or not a class carries its id, and maps the deprecated
	 * {@code lowcodefml} onto the same provider (the native log says so for LibJF's own jar). Any other language
	 * belongs to a provider the kernel does not have, and inventing a container for it would claim a mod is
	 * loaded that the real loader might have refused.
	 *
	 * @param taken ids something else already answers for — an {@code @Mod} class of any family, or a presence
	 *              alias — which must not get a second container
	 */
	static List<Declared> declaredWithoutClass(Map<String, Declared> declared, Set<String> taken, Ecosystem family) {
		return declaredWithoutClass(declared, taken, family, entry -> languageOf(entry.jar(), family));
	}

	/** As above, with the language lookup handed in so a test can say what each jar declares. */
	static List<Declared> declaredWithoutClass(Map<String, Declared> declared, Set<String> taken, Ecosystem family,
			java.util.function.Function<Declared, String> languageOf) {
		if ("off".equalsIgnoreCase(System.getProperty(CLASSLESS_SWITCH, "on"))) return List.of();

		List<Declared> out = new ArrayList<>();
		Map<Path, String> languages = new java.util.HashMap<>();
		for (Declared entry : declared.values()) {
			if (entry.mod().getEcosystem() != family || taken.contains(entry.mod().getId())) continue;
			String language = languages.containsKey(entry.jar()) ? languages.get(entry.jar()) : languageOf.apply(entry);
			languages.put(entry.jar(), language);
			if (!getsAContainer(family, language)) {
				ForbricLog.debug("[Forbric/ModLoader] %s declares mod %s with no @Mod class under modLoader=%s, which "
						+ "%s gives no container of its own", entry.jar().getFileName(), entry.mod().getId(), language,
						family);
				continue;
			}
			out.add(entry);
		}
		if (out.size() < 2) return out;
		try {
			List<DiscoveredMod> mods = new ArrayList<>();
			for (Declared entry : out) mods.add(entry.mod());
			return ModConstructionOrder.sort(out, entry -> entry.mod().getId(), ModConstructionOrder.of(mods));
		} catch (Throwable t) {
			// An order is an improvement, never a precondition — the same rule orderByDependency keeps.
			return out;
		}
	}

	/**
	 * Whether {@code family}'s own loader builds a container for a class-less mod written in {@code language}.
	 *
	 * <p>The two families differ, and each is read off its own carrier. NeoForge's FancyModLoader builds an
	 * {@code FMLModContainer} for every mod of a {@code javafml} file and routes {@code lowcodefml} to the same
	 * provider. MinecraftForge's {@code ModLoader.buildMods} gives a {@code javafml} mod with no {@code @Mod} class
	 * the {@code fml.modloading.missingclasses} error instead, and only its {@code LowCodeModLanguageProvider}
	 * builds a container — a {@code LowCodeModContainer} — for a mod with no class at all.
	 */
	static boolean getsAContainer(Ecosystem family, String language) {
		if (language == null) return false;
		if (family == Ecosystem.NEOFORGE) {
			return LanguageProviders.JAVA.equals(language) || LanguageProviders.LOW_CODE.equals(language);
		}
		if (family == Ecosystem.FORGE) return LanguageProviders.LOW_CODE.equals(language);
		return false;
	}

	/**
	 * The {@code modLoader} {@code jar}'s manifest for {@code family} declares, normalised; null when there is no
	 * such manifest or it cannot be read. A manifest that names none is a Java one.
	 */
	static String languageOf(Path jar, Ecosystem family) {
		String manifest = family == Ecosystem.FORGE ? ForbricModDiscoverer.FORGE_MANIFEST
				: ForbricModDiscoverer.NEOFORGE_MANIFEST;
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry(manifest);
			if (entry == null) return null;
			try (java.io.InputStream in = zip.getInputStream(entry)) {
				return LanguageProviders.of(ModsTomlParser.parse(in));
			}
		} catch (Exception unreadable) {
			ForbricLog.debug("[Forbric/ModLoader] could not read the %s of %s: %s", manifest, jar.getFileName(),
					String.valueOf(unreadable));
			return null;
		}
	}

	/** {@code constructed} followed by {@code classless}, which never share an id. */
	static <T> Map<String, T> withClassless(Map<String, T> constructed, Map<String, T> classless) {
		Map<String, T> out = new LinkedHashMap<>(constructed);
		out.putAll(classless);
		return out;
	}

	/**
	 * The entry {@code jar} itself declares for {@code modId}, or null. Only the same jar's entry answers: a
	 * container is built from the jar its {@code @Mod} class came from, and it must not be described with another
	 * jar's claim to the same id.
	 */
	static DiscoveredMod declaredIn(Map<String, Declared> declared, String modId, Path jar) {
		Declared entry = modId == null ? null : declared.get(modId);
		return entry != null && entry.jar().equals(jar) ? entry.mod() : null;
	}

	/**
	 * The claimed {@code @Mod} classes in dependency order.
	 *
	 * <p>The order comes from what discovery already parsed out of every mod's own metadata — its requirements
	 * and its explicit load-order declarations — through {@link ModConstructionOrder}. Mods the registry has not
	 * heard of keep their place rather than being moved to either end.
	 */
	private static List<ModAnnotationScanner.ModClassInfo> orderByDependency(
			List<ModAnnotationScanner.ModClassInfo> claimed) {
		try {
			List<net.forbric.api.DiscoveredMod> known = new ArrayList<>(ModPresence.forgeFamilyMods());
			known.addAll(ModPresence.fabricMods());
			if (known.isEmpty()) return claimed;

			List<String> order = ModConstructionOrder.of(known);
			List<ModAnnotationScanner.ModClassInfo> sorted =
					ModConstructionOrder.sort(claimed, info -> info.modId, order);

			if (!sorted.equals(claimed)) {
				ForbricLog.info("[Forbric/Order] construction order is dependency order, not jar-file order — a mod "
						+ "that needs another to have run now does (-Dforbric.modOrder=name to go back): %s",
						sorted.stream().map(KernelModLoader::safeId).distinct().toList());
			}
			return sorted;
		} catch (Throwable t) {
			// An order is an improvement, never a precondition. Losing it must not cost the pack its mods.
			ForbricLog.warn("[Forbric/Order] could not order mods by dependency; using the order they were found in",
					Reflect.unwrap(t));
			return claimed;
		}
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
		Map<String, Object> lowCode = lowCodeForge;
		if (!allowEmpty && forge.isEmpty() && lowCode.isEmpty()) return;
		try {
			Class<?> modListCls = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.FORGE), false, cl);
			Class<?> containerCls = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.FORGE), false, cl);
			List<Object> containers = forgeListContents(forge.values(), lowCode.values(), containerCls::isInstance);
			Method setLoadedMods = modListCls.getDeclaredMethod("setLoadedMods", List.class);
			boolean wrote = publishForgeContainers(containers, allowEmpty, list -> {
				setLoadedMods.setAccessible(true);
				setLoadedMods.invoke(null, list); // static on MinecraftForge; NeoForge's twin is an instance method
			});
			if (!wrote) return;
			Set<String> ids = new LinkedHashSet<>(forge.keySet());
			ids.addAll(lowCode.keySet());
			ForbricLog.info("[Forbric/ModLoader] published %d MinecraftForge mod(s) into its ModList %s — its own "
					+ "isLoaded/getModContainerById answered \"absent\" for every one of them until now",
					containers.size(), ids);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/ModLoader] traditional-Forge ModList not present — nothing to publish");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ModLoader] could not publish into MinecraftForge's ModList — a Forge mod asking "
					+ "whether another is loaded still gets no", Reflect.unwrap(t));
		}
	}

	/**
	 * What MinecraftForge's {@code ModList} is written with: each handle's container, then each low-code
	 * container.
	 *
	 * <p>The low-code ones ride along on EVERY write, not only the first. {@code setLoadedMods} replaces the list,
	 * and it is written three times in a boot — at publication, after a constructor throws, and after the deferred
	 * client constructors — so a container added once would be dropped by whichever write came next.
	 */
	static List<Object> forgeListContents(java.util.Collection<KernelForgeModContext.Handle> handles,
			java.util.Collection<Object> lowCode, java.util.function.Predicate<Object> isContainer) {
		List<Object> containers = new ArrayList<>();
		for (KernelForgeModContext.Handle handle : handles) {
			if (isContainer.test(handle.container())) containers.add(handle.container());
		}
		for (Object container : lowCode) {
			if (isContainer.test(container)) containers.add(container);
		}
		return containers;
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
	private static void publishNeoModList(ClassLoader cl, Map<String, NeoIdentity> neo, boolean allowEmpty) {
		if (neo.isEmpty() && !allowEmpty) return;
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
	public static void setNeoActiveContainer(ClassLoader cl, Object container) {
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
		if (best == null) {
			Object singleton = languageProvidedInstance(modCls);
			if (singleton != null) {
				ForbricLog.info("[Forbric/ModLoader] %s has no constructor to call and one INSTANCE to use — "
						+ "taking it, the way a language provider would", className);
				return singleton;
			}
			// Name the gap instead of the symptom. A mod whose @Mod class has no public constructor is almost
			// always not written in Java: mods.toml says so in `modLoader` (kotlinforforge, lowcodefml), the
			// kernel parses that field, exposes it -- and has never had a single consumer of it, so the failure
			// arrived as "no public constructor" and read like a broken mod.
			throw new NoSuchMethodException("no public constructor and no INSTANCE on " + className
					+ " — if its mods.toml declares a modLoader other than javafml (kotlinforforge, lowcodefml),"
					+ " that language provider is not implemented here");
		}

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

	/**
	 * The instance a non-Java language provider would hand back instead of calling a constructor.
	 *
	 * <p>Kotlin's {@code object} compiles to a class with a private constructor and one
	 * {@code public static final Self INSTANCE}; kotlinforforge's whole job on genuine Forge is to read that
	 * field rather than call {@code newInstance}. The kernel constructs Forge-family mods by reflecting the
	 * widest PUBLIC constructor, so such a mod fails with "no public constructor" — a true sentence about a mod
	 * that is not broken.
	 *
	 * <p>Deliberately narrow: public, static, final, and typed as the mod class itself. That is the Kotlin
	 * {@code object} shape exactly. A looser rule would start picking up an unrelated static field named
	 * INSTANCE and hand the loader an object that is not the mod.
	 */
	static Object languageProvidedInstance(Class<?> modCls) {
		try {
			java.lang.reflect.Field instance = modCls.getDeclaredField("INSTANCE");
			int mods = instance.getModifiers();
			if (!java.lang.reflect.Modifier.isStatic(mods)
					|| !java.lang.reflect.Modifier.isPublic(mods)
					|| !java.lang.reflect.Modifier.isFinal(mods)
					|| instance.getType() != modCls) {
				return null;
			}
			instance.setAccessible(true);
			return instance.get(null);
		} catch (NoSuchFieldException | IllegalAccessException | RuntimeException | ExceptionInInitializerError notOne) {
			return null;
		}
	}

	/** The switch that constructs every traditional-MinecraftForge mod in the early window, as before. */
	static final String DEFERRAL_SWITCH = "forbric.forgeCtorGameInstance";

	/**
	 * Traditional-MinecraftForge {@code @Mod} classes whose constructor wanted a {@code Minecraft} that does not
	 * exist yet, waiting for the constructor window.
	 */
	private static final List<ModAnnotationScanner.ModClassInfo> DEFERRED_FORGE =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	/**
	 * Whether {@code info} belongs to the {@code Minecraft.<init>} window rather than to this one.
	 *
	 * <p>The two carriers do not agree on when a client's mods are constructed, and the merged base can only carry
	 * one call site. NeoForge's {@code ClientModLoader.begin()} takes no arguments and runs in {@code Main.main},
	 * before {@code new Minecraft} — so a NeoForge {@code @Mod} constructor sees a null {@code getInstance()} on
	 * genuine NeoForge too, and belongs exactly where it is. Traditional MinecraftForge's is
	 * {@code begin(Minecraft, PackRepository, ReloadableResourceManager)}: those three exist together only inside
	 * {@code Minecraft.<init>}, so on genuine MinecraftForge a traditional-Forge constructor ALWAYS has a live
	 * instance and a built pack repository. The kernel ran both families in NeoForge's window, and Simple Voice
	 * Chat — which caches {@code Minecraft.getInstance()} and then asks it for the pack repository — died there.
	 *
	 * <p>Decided BEFORE the constructor runs, never after it throws. A constructor is not a pure function: Simple
	 * Voice Chat registers its key binds first and its own guard answers "Registered key binds twice" on a second
	 * attempt, so a failed try cannot be taken back and "retry it later" is not available as a repair.
	 *
	 * <p>A dedicated server is untouched — it has no {@code Minecraft}, and traditional MinecraftForge's server
	 * path constructs its mods in exactly the window the kernel already uses.
	 *
	 * <p>{@code -Dforbric.forgeCtorGameInstance=off} constructs them here, as before.
	 */
	static boolean constructedInTheGameConstructor(ModAnnotationScanner.ModClassInfo info, Side side) {
		if ("off".equalsIgnoreCase(System.getProperty(DEFERRAL_SWITCH, "on"))) return false;
		return side != null && side.isClient() && info.family == Ecosystem.FORGE;
	}

	/** The mod ids waiting for the {@code Minecraft.<init>} window; their events must not fire before they do. */
	public static Set<String> deferredForgeModIds() {
		Set<String> ids = new LinkedHashSet<>();
		for (ModAnnotationScanner.ModClassInfo info : DEFERRED_FORGE) ids.add(safeId(info));
		return ids;
	}


	/**
	 * Constructs the deferred traditional-Forge mods, now that {@code Minecraft.getInstance()} answers.
	 *
	 * <p>This is their FIRST construction, not a retry: they were held back before anything ran, so each still has
	 * the untouched loading context that was published into {@code ModList} for it.
	 *
	 * @return the handles that constructed, for the caller to post the construct phase and RegisterEvent on
	 */
	public static List<KernelForgeModContext.Handle> constructDeferredForgeMods(ClassLoader cl) {
		if (DEFERRED_FORGE.isEmpty()) return List.of();

		List<ModAnnotationScanner.ModClassInfo> pending = new ArrayList<>(DEFERRED_FORGE);
		DEFERRED_FORGE.clear();
		Map<String, KernelForgeModContext.Handle> published = new LinkedHashMap<>(publishedForge);
		List<KernelForgeModContext.Handle> built = new ArrayList<>();
		List<String> failed = new ArrayList<>();

		for (ModAnnotationScanner.ModClassInfo info : pending) {
			String modId = safeId(info);
			// Its own handle, the one published into ModList before the window opened. Nothing was ever
			// constructed on it, so it is exactly as pristine as it was — no second BusGroup is needed and no
			// container identity changes underneath a neighbour that already resolved this mod.
			KernelForgeModContext.Handle handle = published.get(modId);
			try {
				if (handle == null) throw new IllegalStateException("no MinecraftForge loading context for " + modId);
				constructForgeFamilyMod(cl, info, handle);
				built.add(handle);
			} catch (Throwable t) {
				failed.add(modId);
				ForbricLog.warn("[Forbric/ModLoader] failed to construct @Mod " + info.className
						+ " in the Minecraft.<init> window", Reflect.unwrap(t));
			}
		}

		if (!failed.isEmpty()) {
			for (String modId : failed) published.remove(modId);
			markWithdrawn(failed, "its @Mod constructor threw");
		}
		publishedForge = Map.copyOf(published);
		publishForgeModList(cl, publishedForge, true);
		return built;
	}

	private static String safeId(ModAnnotationScanner.ModClassInfo info) {
		return info.modId != null ? info.modId : info.className;
	}

	/**
	 * Records the withdrawn mods in the catalogue, so the Mods screen and the load report can say so.
	 *
	 * <p>Package-private and taking plain ids, so it stays a list/map operation a unit test can drive -- the same
	 * reason {@code keepConstructed} beside it is shaped that way.
	 *
	 * <p>The wording is "did not finish loading", not "is not running": a withdrawn mod's classes are still
	 * loaded, its mixins still applied, and isLoaded(id) deliberately still answers true.
	 */
	static void markWithdrawn(List<String> modIds, String why) {
		for (String id : modIds) {
			ModCatalog.mark(id, ModCatalog.Status.FAILED, why);
		}
	}

	/**
	 * Reports the NeoForge mods {@link #settleNeo} kept although one of their {@code @Mod} constructors threw.
	 * DEGRADED, not FAILED: the container stays and the class that did construct keeps running, so "did not
	 * finish loading" would be untrue — but OK, which is what these rows said before, is untrue too.
	 *
	 * <p>The row names the class that threw: RollingGate's common half and its client half share one id, and "one of
	 * its @Mod constructors threw" could not say which half is missing.
	 */
	static void markPartlyConstructed(Set<String> modIds, Map<String, List<String>> threw) {
		for (String id : modIds) {
			List<String> classes = threw.getOrDefault(id, List.of());
			String which = classes.isEmpty() ? "one of its @Mod constructors" : "@Mod " + String.join(", ", classes);
			ForbricLog.warn("[Forbric/ModLoader] NeoForge mod '%s' keeps its container although %s threw: another "
					+ "@Mod class of the same id ran on this side and put its listeners on the bus they share, so "
					+ "withdrawing would cut those too. Whatever the failed one sets up is missing", id, which);
			ModCatalog.mark(id, ModCatalog.Status.DEGRADED, which + " threw");
		}
	}
}
