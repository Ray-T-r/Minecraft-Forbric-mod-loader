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
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.DiscoveredMod;
import net.forbric.kernel.metadata.ModEcosystem;
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
		seedNeoForgeLoader(gameLoader, gameDir, gameDir.resolve("mods"), production, client);
	}

	/**
	 * {@code modsDir} is the directory whose Forge-family jars become the seeded {@code LoadingModList} (see
	 * {@link #seedNeoForgeLoadingModList}). The 4-arg overload defaults it to {@code <gameDir>/mods}, which is the
	 * same directory {@code KernelBoot} walks for Forge-family discovery — the explicit parameter exists so the
	 * caller that already knows the mods dir passes ITS answer rather than re-deriving one that could drift.
	 */
	public static void seedNeoForgeLoader(ClassLoader gameLoader, Path gameDir, Path modsDir, boolean production,
			boolean client) {
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

			seedNeoForgeLoadingModList(gameLoader, fmlLoader, loader, modsDir);

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

	// ---------------------------------------------------------------------------------------------------------
	// LoadingModList — POPULATED, not empty. See seedNeoForgeLoadingModList's javadoc for the why.
	// ---------------------------------------------------------------------------------------------------------

	/** Escape hatch: {@code -Dforbric.seedLoadingModList=off} restores the pre-fix EMPTY list. */
	static final String SEED_SWITCH = "forbric.seedLoadingModList";

	/** Lazily-resolved {@code sun.misc.Unsafe}, the JDK-only fallback for constructor-free allocation. */
	private static volatile Object jdkUnsafe;

	/**
	 * Seeds a {@code LoadingModList} that actually CONTAINS the Forge-family mods, so a mod that resolves ITSELF
	 * through {@code FMLLoader.getLoadingModList()} finds itself.
	 *
	 * <p>The kernel used to seed a list that was structurally valid but empty ({@link #seedEmptyLoadingModList}),
	 * which answers the "does this thing exist" question the merged base's {@code <clinit>}s ask and nothing more.
	 * That is not what mods ask. A mod asks {@code getModFileById(myId).versionString()} (a version probe, rendered
	 * into a UI string) or walks {@code getMods()} looking for its own {@code ModInfo} to build a platform-neutral
	 * mod handle from. Against an empty list the first returns {@code null} and NPEs at the caller — for Iris that
	 * is inside {@code Minecraft.<init>}, i.e. a hard client crash with no world — and the second silently finds
	 * nothing, which mods read as "I am not installed, so this must be a dev environment" (LambDynamicLights then
	 * force-enables its dev-mode banner).
	 *
	 * <p>Why those probes run AT ALL on a Fabric-flavoured mod: multi-platform mods detect their platform by class
	 * presence ({@code Class.forName("net.neoforged.fml.loading.FMLLoader")}). On a normal instance exactly one
	 * family answers; on the merged base ALL of them do, so the NeoForge branch runs even for a jar that was built
	 * for Fabric. The kernel cannot make that branch not run, so it must make the branch's data true.
	 *
	 * <p>This stays passive seeding: it runs NO FancyModLoader discovery, builds no module layer, sorts nothing. It
	 * re-reads the mods dir with the kernel's own boot-side discoverer and states, in NeoForge's own data types,
	 * the set of Forge-family mods the kernel has already decided to load.
	 *
	 * <p>Falls back to the empty list — never throws, never fails the caller — when the switch is off, when there
	 * are no Forge-family mods (which keeps a zero-mod boot byte-identical to the old behaviour), or when anything
	 * at all goes wrong building the objects.
	 */
	static void seedNeoForgeLoadingModList(ClassLoader gameLoader, Class<?> fmlLoader, Object loaderInstance,
			Path modsDir) {
		if ("off".equalsIgnoreCase(System.getProperty(SEED_SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/Seed] -D%s=off — seeding an EMPTY NeoForge LoadingModList; mods that look "
					+ "themselves up through FMLLoader.getLoadingModList() will not find themselves", SEED_SWITCH);
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}

		List<DiscoveredMod> mods;
		try {
			mods = arbitratedForgeFamilyMods(modsDir);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not discover Forge-family mods for the NeoForge LoadingModList — "
					+ "falling back to the empty list", unwrap(t));
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}
		if (mods.isEmpty()) {
			// Zero Forge-family mods: the old code path exactly, so gate-m1 / gate-m2b cannot move.
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}

		try {
			Field field = fmlLoader.getDeclaredField("loadingModList");
			field.setAccessible(true);
			if (field.get(loaderInstance) != null) return; // a genuine list exists — never overwrite it

			Object list = buildLoadingModList(gameLoader, mods);
			field.set(loaderInstance, list);

			StringBuilder ids = new StringBuilder();
			for (DiscoveredMod mod : mods) {
				if (ids.length() > 0) ids.append(", ");
				ids.append(mod.getId());
			}
			ForbricLog.info("[Forbric/Seed] seeded NeoForge LoadingModList with %d mod(s) — mods that resolve "
					+ "themselves through FMLLoader.getLoadingModList() (Iris' version probe, "
					+ "yumi/LambDynamicLights' mod lookup) find themselves; an empty list NPEs Iris at "
					+ "Minecraft.<init>. [%s]", mods.size(), ids);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed a populated NeoForge LoadingModList — falling back to the "
					+ "empty list; mods that look themselves up through it will not find themselves", unwrap(t));
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
		}
	}

	/**
	 * Every Forge-family mod in {@code modsDir}, filtered through {@link MultiLoaderArbiter} so a universal jar
	 * contributes under exactly the ONE ecosystem it was arbitrated to.
	 *
	 * <p>Without the filter a jar shipping all three manifests would appear twice here (its {@code mods.toml} and
	 * its {@code neoforge.mods.toml} are both truthfully reported by discovery), and — worse — a jar the arbiter
	 * handed to FABRIC would appear in the NeoForge list at all, telling the NeoForge side it owns a mod that is
	 * being initialised as a Fabric mod. The arbiter is the single boot-time policy for that question; this asks it
	 * rather than inventing a second answer.
	 *
	 * <p>Both Forge-family ecosystems are included, not just NeoForge: on the merged base a traditional
	 * MinecraftForge mod really IS loaded, so "is X present" must answer yes for it too. De-duplicated by mod id,
	 * first-wins, because {@code fileById} is a map and a duplicate id would otherwise silently shadow.
	 */
	static List<DiscoveredMod> arbitratedForgeFamilyMods(Path modsDir) throws Exception {
		List<DiscoveredMod> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		if (!Files.isDirectory(modsDir)) return out;

		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Path> jars;
		try (var entries = Files.list(modsDir)) {
			jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile).sorted().toList();
		}

		for (Path jar : jars) {
			// Per-jar, not one pass over the whole directory: an unreadable or malformed manifest anywhere in a real
			// mods folder must cost that one jar, not the entire seeded list.
			List<DiscoveredMod> declared;
			try {
				declared = discoverer.discoverJar(jar);
			} catch (Throwable t) {
				ForbricLog.debug("[Forbric/Seed] could not read %s for the NeoForge LoadingModList (%s) — skipping it",
						jar.getFileName(), String.valueOf(t));
				continue;
			}

			MultiLoaderArbiter.Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			for (DiscoveredMod mod : declared) {
				if (!mod.getEcosystem().isForgeFamily()) continue;
				if (mod.getId() == null || mod.getId().isBlank() || mod.getSource() == null) continue;
				// owner == null means "no loader manifest at all", which cannot happen for a Forge-family mod; treat
				// it as unowned (keep) rather than as "not mine", per the arbiter's own contract.
				if (owner != null && unified(owner) != mod.getEcosystem()) continue;
				if (!seen.add(mod.getId())) continue;
				out.add(mod);
			}
		}
		return out;
	}

	private static ModEcosystem unified(MultiLoaderArbiter.Ecosystem owner) {
		return switch (owner) {
			case NEOFORGE -> ModEcosystem.NEOFORGE;
			case MINECRAFTFORGE -> ModEcosystem.FORGE;
			case FABRIC -> ModEcosystem.FABRIC;
		};
	}

	/**
	 * Builds a genuine {@code LoadingModList} carrying a genuine {@code ModFileInfo} per jar and a genuine
	 * {@code ModInfo} per mod.
	 *
	 * <p><b>Concrete classes, not {@link Proxy}.</b> Both types are read back through CONCRETE-typed seams:
	 * {@code getModFileById} ends in {@code checkcast ModFileInfo}, and every consumer of {@code getMods()} gets a
	 * {@code List<ModInfo>} whose per-element access compiles to {@code checkcast ModInfo} (verified: yumi's
	 * {@code NeoModContainer.init} is exactly {@code getMods().forEach(lambda(…, ModInfo))}). A dynamic proxy
	 * therefore does not fail here, it fails at the READER with a ClassCastException — arbitrarily far from the
	 * seeding that caused it. So the real classes are allocated without a constructor and their private fields are
	 * filled, the same technique {@code KernelModContainerFactory} uses for {@code FMLModContainer}.
	 *
	 * <p><b>Why not the real constructors.</b> {@code LoadingModList.of(…)} demands concrete {@code ModFile}s,
	 * i.e. NeoForge's whole jar-contents/discovery pipeline; it is still used here, but with empty arguments, purely
	 * so every field it initialises (issues list, package index, plugin/game-library lists) is left exactly as
	 * NeoForge would leave it. {@code ModInfo}'s one public constructor is rejected deliberately: it re-parses the
	 * mod's metadata out of an {@code IConfigurable} and VALIDATES it, throwing {@code InvalidModFileException} when
	 * the id or the version does not match its patterns (the version one demands a leading digit), and its version
	 * path runs the metadata through {@code StringSubstitutor} against a {@code ModFile} we do not have, silently
	 * degrading to its {@code "1"} default. A mod's rendered version turning into "1" is precisely the bug this
	 * method exists to fix, so the fields are set directly instead.
	 *
	 * <p><b>Mutability</b> mirrors what the constructor produces, so nothing that mutates the list later breaks:
	 * {@code sortedList} and {@code modFiles} are MUTABLE ({@code new ArrayList<>(…)} / {@code Collectors.toList()}
	 * upstream), {@code fileById} is a mutable map ({@code Collectors.toMap} upstream), and each
	 * {@code ModFileInfo.mods} is IMMUTABLE ({@code Stream.toList()} upstream).
	 *
	 * <p><b>{@code allModFiles} is deliberately left EMPTY.</b> It is a {@code Set<IModFile>}, and its only readers
	 * ({@code contains}, {@code buildPackageIndex}) go straight on to {@code ModFile.getModuleDescriptor()} — a
	 * module descriptor that only exists once the jar has been read through NeoForge's own module machinery. An
	 * entry there would turn "the kernel does not track that" into an NPE inside the package index; an absent one
	 * merely answers "not there", which is what an empty list answers today.
	 */
	static Object buildLoadingModList(ClassLoader gameLoader, List<DiscoveredMod> mods) throws Exception {
		Class<?> lmlCls = Class.forName("net.neoforged.fml.loading.LoadingModList", false, gameLoader);
		Class<?> fileInfoCls = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFileInfo", false, gameLoader);
		Class<?> modInfoCls = Class.forName("net.neoforged.fml.loading.moddiscovery.ModInfo", false, gameLoader);

		Method of = lmlCls.getMethod("of", List.class, List.class, List.class, List.class, List.class, Map.class);
		Object list = of.invoke(null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());

		// One ModFileInfo per JAR, N ModInfos inside it — a mods.toml may declare several [[mods]], and
		// ModFileInfo.versionString() is defined as its FIRST mod's version, so the grouping has to be per file.
		Map<String, List<DiscoveredMod>> byJar = new LinkedHashMap<>();
		for (DiscoveredMod mod : mods) {
			byJar.computeIfAbsent(mod.getSource(), key -> new ArrayList<>()).add(mod);
		}

		List<Object> fileInfos = new ArrayList<>();
		List<Object> modInfos = new ArrayList<>();
		Map<String, Object> fileById = new LinkedHashMap<>();

		for (Map.Entry<String, List<DiscoveredMod>> jar : byJar.entrySet()) {
			Object fileInfo = allocate(gameLoader, fileInfoCls);
			List<Object> ownMods = new ArrayList<>();
			for (DiscoveredMod mod : jar.getValue()) {
				Object modInfo = buildModInfo(gameLoader, modInfoCls, fileInfo, mod);
				ownMods.add(modInfo);
				modInfos.add(modInfo);
				fileById.putIfAbsent(mod.getId(), fileInfo);
			}
			fillModFileInfo(gameLoader, fileInfoCls, fileInfo, Path.of(jar.getKey()), jar.getValue().get(0),
					List.copyOf(ownMods));
			fileInfos.add(fileInfo);
		}

		setInstanceField(lmlCls, "fileById", list, fileById);
		setInstanceField(lmlCls, "sortedList", list, new ArrayList<>(modInfos));
		setInstanceField(lmlCls, "modFiles", list, new ArrayList<>(fileInfos));
		return list;
	}

	/**
	 * Fills a constructor-free {@code ModFileInfo}. Every field a reader can reach is set to a real value; the two
	 * booleans and {@code issueURL} keep their allocation defaults (false / null), which is exactly "this file
	 * declares no resource pack, no data pack and no issue tracker".
	 *
	 * <p>{@code config} must not be null: {@code ModFileInfo.getConfigElement}/{@code getConfigList} delegate to it
	 * straight through, and NeoForge's own {@code ModInfo} construction path reads it. It is a {@link Proxy}, which
	 * is safe here precisely because that field is typed as the INTERFACE {@code IConfigurable} and is only ever
	 * called through it.
	 */
	private static void fillModFileInfo(ClassLoader gameLoader, Class<?> fileInfoCls, Object fileInfo, Path jar,
			DiscoveredMod first, List<Object> ownMods) throws Exception {
		setInstanceField(fileInfoCls, "config", fileInfo, emptyConfigurable(gameLoader));
		setInstanceField(fileInfoCls, "mods", fileInfo, ownMods);
		setInstanceField(fileInfoCls, "languageSpecs", fileInfo, List.of());
		setInstanceField(fileInfoCls, "properties", fileInfo, Map.of());
		setInstanceField(fileInfoCls, "usesServices", fileInfo, List.of());
		// The kernel's mods.toml reader does not carry the license line; "" keeps the Mods screen's info pane a real
		// String (it is written into it unguarded) instead of a null.
		setInstanceField(fileInfoCls, "license", fileInfo, "");
		setInstanceField(fileInfoCls, "modFile", fileInfo,
				buildModFile(gameLoader, fileInfo, jar, first.getId(), version(first)));
	}

	/**
	 * A constructor-free {@code ModFile} backed by {@code JarContents.empty(jar)} — NeoForge's own "a file at this
	 * path whose contents are not indexed" value, which performs no I/O and opens no handle.
	 *
	 * <p>It exists so the file-shaped seams answer instead of NPE-ing: {@code ModFileInfo.toString()} is literally
	 * {@code modFile.getId()}, {@code getFilePath()} is {@code contents.getPrimaryPath()}, and NeoForge's mod-error
	 * reporting walks {@code getOwningFile().getFile().getFilePath()} whenever any mod-bus listener throws. With a
	 * null {@code modFile} each of those turns a real error into an NPE that MASKS it.
	 *
	 * <p>Best-effort: on any failure the caller's {@code modFile} stays null, which is still strictly better than
	 * the empty list this whole method replaces.
	 */
	private static Object buildModFile(ClassLoader gameLoader, Object fileInfo, Path jar, String id, String version) {
		try {
			Class<?> modFileCls = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFile", false, gameLoader);
			Class<?> contentsCls = Class.forName("net.neoforged.fml.jarcontents.JarContents", false, gameLoader);
			Class<?> typeCls = Class.forName("net.neoforged.neoforgespi.locating.IModFile$Type", false, gameLoader);

			Object modFile = allocate(gameLoader, modFileCls);
			setInstanceField(modFileCls, "contents", modFile, contentsCls.getMethod("empty", Path.class)
					.invoke(null, jar.toAbsolutePath()));
			setInstanceField(modFileCls, "id", modFile, id);
			setInstanceField(modFileCls, "jarVersion", modFile, version);
			setInstanceField(modFileCls, "modFileType", modFile, Enum.valueOf(typeCls.asSubclass(Enum.class), "MOD"));
			setInstanceField(modFileCls, "modFileInfo", modFile, fileInfo);
			setInstanceField(modFileCls, "mixinConfigs", modFile, List.of());
			setInstanceField(modFileCls, "accessTransformers", modFile, List.of());
			setInstanceField(modFileCls, "fileProperties", modFile, Map.of());
			setInstanceField(modFileCls, "loaders", modFile, List.of());
			try {
				Class<?> attrs = Class.forName("net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes",
						false, gameLoader);
				setInstanceField(modFileCls, "discoveryAttributes", modFile, attrs.getField("DEFAULT").get(null));
			} catch (Throwable optional) {
				ForbricLog.debug("[Forbric/Seed] no ModFileDiscoveryAttributes.DEFAULT (%s) — leaving it null",
						String.valueOf(optional));
			}
			return modFile;
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Seed] could not build a synthetic ModFile for '%s' (%s) — the ModFileInfo's "
					+ "file stays null", id, String.valueOf(t));
			return null;
		}
	}

	/**
	 * Fills a constructor-free {@code ModInfo} from a {@link DiscoveredMod}.
	 *
	 * <p>{@code version} is a REAL {@code DefaultArtifactVersion} over the mod's declared version string (already
	 * {@code ${file.jarVersion}}-resolved by the discoverer), because that string is user-visible: it is what
	 * {@code ModFileInfo.versionString()} returns and what a version probe renders. A placeholder here would be a
	 * quieter version of the very bug being fixed.
	 *
	 * <p>{@code config} must not be null — NeoForge's own {@code FeatureFlagLoader.loadModdedFlags} runs over
	 * {@code getModFiles()} during {@code Bootstrap} and calls {@code getConfig().getConfigElement("featureFlags")}
	 * on every mod unguarded. Answering {@code Optional.empty()} is both non-null and true: the kernel's reader does
	 * not carry that key, so this mod declares no feature flags, and the deeper walk into jar contents (which the
	 * kernel cannot satisfy) is never entered.
	 */
	private static Object buildModInfo(ClassLoader gameLoader, Class<?> modInfoCls, Object owningFile,
			DiscoveredMod mod) throws Exception {
		Object modInfo = allocate(gameLoader, modInfoCls);
		String id = mod.getId();

		setInstanceField(modInfoCls, "owningFile", modInfo, owningFile);
		setInstanceField(modInfoCls, "modId", modInfo, id);
		setInstanceField(modInfoCls, "namespace", modInfo, id);
		setInstanceField(modInfoCls, "version", modInfo, artifactVersion(gameLoader, version(mod)));
		setInstanceField(modInfoCls, "displayName", modInfo,
				mod.getDisplayName() == null || mod.getDisplayName().isBlank() ? id : mod.getDisplayName());
		setInstanceField(modInfoCls, "description", modInfo, "");
		setInstanceField(modInfoCls, "logoFile", modInfo, Optional.empty());
		setInstanceField(modInfoCls, "updateJSONURL", modInfo, Optional.empty());
		setInstanceField(modInfoCls, "modUrl", modInfo, Optional.empty());
		// Dependencies stay empty on purpose: the kernel's resolver has ALREADY decided what loads, and a populated
		// list here would only invite NeoForge-side re-checking of a decision that is not its to make.
		setInstanceField(modInfoCls, "dependencies", modInfo, List.of());
		setInstanceField(modInfoCls, "features", modInfo, List.of());
		setInstanceField(modInfoCls, "properties", modInfo, Map.of());
		setInstanceField(modInfoCls, "config", modInfo, emptyConfigurable(gameLoader));
		// logoBlur stays at its allocation default (false).
		return modInfo;
	}

	/** The mod's declared version, or the conventional unknown-version placeholder when it declared none. */
	private static String version(DiscoveredMod mod) {
		return mod.getVersion() == null || mod.getVersion().isBlank() ? "0.0" : mod.getVersion();
	}

	/** A real {@code DefaultArtifactVersion}; reflective because the kernel carries no compile dep on maven-artifact. */
	private static Object artifactVersion(ClassLoader gameLoader, String version) throws Exception {
		return Class.forName("org.apache.maven.artifact.versioning.DefaultArtifactVersion", true, gameLoader)
				.getConstructor(String.class).newInstance(version);
	}

	/** An {@code IConfigurable} that truthfully reports "this declares nothing": empty Optional / empty List. */
	private static Object emptyConfigurable(ClassLoader gameLoader) throws Exception {
		Class<?> iConfigurable = Class.forName("net.neoforged.neoforgespi.language.IConfigurable", false, gameLoader);
		return Proxy.newProxyInstance(gameLoader, new Class<?>[] {iConfigurable}, (proxy, method, args) ->
				switch (method.getName()) {
					case "getConfigList" -> List.of();
					case "toString" -> "KernelSeededConfig";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == (args == null ? null : args[0]);
					default -> method.getReturnType() == List.class ? List.of() : Optional.empty();
				});
	}

	/**
	 * Allocates {@code type} WITHOUT running any constructor.
	 *
	 * <p>Forge's {@code UnsafeHacks} first — that is already the kernel's technique on the Forge side
	 * ({@code KernelModContainerFactory}) and it lives on the game loader, so it is the right tool when the game
	 * runtime is present. {@code sun.misc.Unsafe} is the JDK-only fallback, which is what makes this reachable when
	 * only the NeoForge jar is on the classloader (unit tests, tooling) — never a reason to skip seeding.
	 */
	private static Object allocate(ClassLoader gameLoader, Class<?> type) throws Exception {
		try {
			Class<?> unsafeHacks = Class.forName("net.minecraftforge.unsafe.UnsafeHacks", false, gameLoader);
			return unsafeHacks.getMethod("newInstance", Class.class).invoke(null, type);
		} catch (Throwable noForgeUnsafe) {
			Object unsafe = jdkUnsafe;
			if (unsafe == null) {
				Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
				Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
				theUnsafe.setAccessible(true);
				jdkUnsafe = unsafe = theUnsafe.get(null);
			}
			return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
		}
	}

	/**
	 * Writes one private instance field, final included. {@code setAccessible(true)} is enough for a NON-STATIC
	 * final field (the JLS carve-out deserialization relies on), so this needs no Unsafe and works identically
	 * whether the target class came from the game loader or a plain classpath.
	 */
	private static void setInstanceField(Class<?> owner, String name, Object target, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
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
	 * Runs NeoForge's OWN {@code NeoForgeRegistriesSetup.modifyRegistries(ModifyRegistriesEvent)} — the twin of
	 * {@link #seedNeoForgeRegistries}, driven the same way (its handler directly, no bus, no mod dispatch).
	 *
	 * <p>{@code NeoForgeRegistriesSetup.setup(IEventBus)} only adds two listeners, {@code registerRegistries} and
	 * {@code modifyRegistries}. The kernel drove the first and never the second, so everything the second does was
	 * simply missing. It does two kinds of work:
	 *
	 * <ul>
	 *   <li>{@code setSync(true)} over {@code VANILLA_SYNC_REGISTRIES} — which the kernel had HAND-REIMPLEMENTED in
	 *       {@code KernelLifecycle.markVanillaRegistriesSynced}. That half was visible, so it got fixed; the rest
	 *       was not.</li>
	 *   <li>Five {@code addCallback} wirings that nothing replaced: {@code BLOCK}, {@code ITEM},
	 *       {@code ATTRIBUTE}, {@code POINT_OF_INTEREST_TYPE}, and — the one that bites — <b>{@code ATTACHMENT_TYPES}
	 *       ← {@code AttachmentSync.ATTACHMENT_TYPE_ADD_CALLBACK}</b>, the callback that mirrors every synced
	 *       {@code AttachmentType} into {@code neoforge:synced_attachment_types}.</li>
	 * </ul>
	 *
	 * <p>Without that last one a NeoForge mod using synced data attachments (Mutant Monsters via Puzzles Lib) kicks
	 * the player the instant they join: the server sends {@code neoforge:sync_attachments}, whose codec looks the
	 * attachment up by numeric id, and {@code IdMap.getIdOrThrow} throws
	 * {@code Can't find id for AttachmentType … in Registry[neoforge:synced_attachment_types]} inside the encoder —
	 * so the connection dies with a bare "Disconnected" and a clean world save, which reads like anything but a
	 * registry bug.
	 *
	 * <p>Ordering is load-bearing in both directions: this must run AFTER {@link #seedNeoForgeRegistries} (the
	 * callback is attached to a registry that call creates and roots) and BEFORE the {@code RegisterEvent} pass
	 * (an {@code AddCallback} fires on ADD, so an attachment registered before it is attached is never mirrored).
	 *
	 * @return true if NeoForge's handler ran; false leaves the caller to fall back to the partial hand-rolled path
	 */
	public static boolean applyNeoForgeRegistryModifications(ClassLoader gameLoader) {
		try {
			Class<?> setupCls = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistriesSetup", false,
					gameLoader);
			Class<?> eventCls = Class.forName("net.neoforged.neoforge.registries.ModifyRegistriesEvent", false,
					gameLoader);

			Constructor<?> eventCtor = eventCls.getDeclaredConstructor();
			eventCtor.setAccessible(true);

			Method modifyRegistries = setupCls.getDeclaredMethod("modifyRegistries", eventCls);
			modifyRegistries.setAccessible(true);
			modifyRegistries.invoke(null, eventCtor.newInstance());

			ForbricLog.info("[Forbric/Seed] applied NeoForge's registry modifications — vanilla registries marked "
					+ "client-syncing and the block/item/attribute/POI/attachment callbacks wired (native)");
			return true;
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] NeoForge registry setup not present — skipping registry modifications");
			return false;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not apply NeoForge registry modifications — falling back to the "
					+ "sync-flags-only path; synced data attachments will not work", unwrap(t));
			return false;
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
