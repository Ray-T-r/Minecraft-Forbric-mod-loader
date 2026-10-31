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
import java.lang.reflect.InvocationHandler;
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
import java.util.concurrent.CompletableFuture;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.api.ModPresence;
import net.forbric.api.Side;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModFileScanner;
import net.forbric.kernel.metadata.forge.FmlConfigElements;
import net.forbric.kernel.util.Caught;
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

	/**
	 * FML's dev-vs-shipped flag, which for a Forbric instance is always "shipped".
	 *
	 * <p>It used to be a parameter, and {@code KernelBoot} filled it with {@code side == Side.SERVER} — so every
	 * client boot announced {@code production=false}, telling both ecosystems they were running out of a Gradle
	 * workspace. That is not a spelling mistake anyone would make with the axes named: it happened because the
	 * side and this flag were two adjacent booleans in the same signature.
	 *
	 * <p>{@code false} is what FML sets when the game is launched from a mod-development workspace: unobfuscated
	 * names, dev-only resource paths, relaxed checks. Nothing the kernel does resembles that — the gates and the
	 * installer both launch from built jars — and the dedicated server has been telling the truth about it all
	 * along. A constant rather than a parameter, because a parameter invites a caller to have an opinion, and the
	 * only opinion available here was the wrong one.
	 */
	private static final boolean PRODUCTION = true;

	/**
	 * Seeds every genuine-loader identity the merged base needs before the game entry runs. Best-effort per family.
	 *
	 * <p>{@code side} selects the seeded {@code Dist}. It is load-bearing: with the wrong dist, NeoForge's client
	 * code (and the integrated server's connection handshake) treats the client as a dedicated server — e.g. the
	 * local player's MODDED connection is rejected "Server is still starting".
	 *
	 * <p>The dev-vs-shipped flag is not a parameter — see {@link #PRODUCTION} for why it stopped being one.
	 */
	public static void seedAll(ClassLoader gameLoader, Path gameDir, Side side) {
		seedNeoForgeLoader(gameLoader, gameDir, side);
		seedNeoForgeModList(gameLoader);
		seedNeoForgePaths(gameLoader, gameDir);
		// No early window, so NeoForge's config must not promise one. Before the kernel runs NeoForge's
		// GraphicsBootstrapper services (NeoGraphicsBootstrappers), which read it.
		if (side.isClient()) seedNeoForgeNoEarlyWindow(gameLoader);
		// NOTE: NeoForge baseline-registry registration is NOT done here — NeoForgeRegistriesSetup.<clinit> touches
		// game registries and throws "Not bootstrapped" pre-Main. It runs post-Bootstrap via KernelLifecycle
		// (the redirected ServerModLoader.load window). See KernelLifecycle.onServerModLoading.
	}

	/**
	 * Initializes NeoForge {@code FMLPaths} (GAMEDIR/CONFIGDIR/MODSDIR/…) so {@code FMLPaths.<X>.get()} returns a
	 * real path instead of null. {@code ConfigTracker.<clinit>} reads {@code FMLPaths.CONFIGDIR.get()} during the
	 * server-about-to-start hook. Pure path setup, no lifecycle.
	 */
	public static void seedNeoForgePaths(ClassLoader gameLoader, Path gameDir) {
		try {
			Class<?> fmlPaths = Class.forName(ForeignType.FML_PATHS.binary(Ecosystem.NEOFORGE), false, gameLoader);
			Method load = fmlPaths.getMethod("loadAbsolutePaths", Path.class);
			load.invoke(null, gameDir.toAbsolutePath());
			ForbricLog.debug("[Forbric/Seed] initialized NeoForge FMLPaths at %s", gameDir.toAbsolutePath());
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not init FMLPaths");
		}
	}

	/**
	 * Seeds an EMPTY NeoForge {@code ModList} so {@code ModList.get()} returns an empty list instead of null. The
	 * merged base reads it early (e.g. {@code ResourcePackLoader.findResourcePacks} → {@code ModList.get().getModFiles()}
	 * when building the server pack repository). Zero mods = empty list. No-op if already present/absent.
	 */
	public static void seedNeoForgeModList(ClassLoader gameLoader) {
		try {
			Class<?> modList = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, gameLoader);
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
			Caught.expected(absent);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not seed empty ModList");
		}
	}

	/**
	 * Makes a NeoForge {@code FMLLoader} "current" (dist = DEDICATED_SERVER for the server), so
	 * {@code FMLLoader.getCurrent()} / {@code FMLEnvironment.isProduction()} answer instead of throwing.
	 * No-ops if a loader is already current or the class is absent.
	 */

	public static void seedNeoForgeLoader(ClassLoader gameLoader, Path gameDir, Side side) {
		seedNeoForgeLoader(gameLoader, gameDir, gameDir.resolve("mods"), side);
	}

	/**
	 * {@code modsDir} is the directory whose Forge-family jars become the seeded {@code LoadingModList} (see
	 * {@link #seedNeoForgeLoadingModList}). The 4-arg overload defaults it to {@code <gameDir>/mods}, which is the
	 * same directory {@code KernelBoot} walks for Forge-family discovery — the explicit parameter exists so the
	 * caller that already knows the mods dir passes ITS answer rather than re-deriving one that could drift.
	 */
	public static void seedNeoForgeLoader(ClassLoader gameLoader, Path gameDir, Path modsDir, Side side) {
		seedNeoForgeLoader(gameLoader, gameDir, modsDir, side, null);
	}

	/** Pass the detected game version into FML's own argument parser, rather than leaving VersionInfo null. */
	public static void seedNeoForgeLoader(ClassLoader gameLoader, Path gameDir, Path modsDir, Side side, String gameVersion) {
		try {
			Class<?> fmlLoader = Class.forName(ForeignType.FML_LOADER.binary(Ecosystem.NEOFORGE), false, gameLoader);

			Method getCurrentOrNull = fmlLoader.getDeclaredMethod("getCurrentOrNull");
			getCurrentOrNull.setAccessible(true);
			if (getCurrentOrNull.invoke(null) != null) {
				ForbricLog.debug("[Forbric/Seed] NeoForge FMLLoader already current — not re-seeding");
				return;
			}

			Class<?> distClass = Class.forName(ForeignType.DIST.binary(Ecosystem.NEOFORGE), false, gameLoader);
			String distName = side.distName();
			Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), distName);

			// private FMLLoader(ClassLoader, String[], Dist, boolean production, Path gameDir)
			Constructor<?> ctor = fmlLoader.getDeclaredConstructor(
					ClassLoader.class, String[].class, distClass, boolean.class, Path.class);
			ctor.setAccessible(true);
			Object loader = ctor.newInstance(gameLoader, neoForgeVersionArguments(gameVersion), dist, PRODUCTION, gameDir);

			// The ctor may or may not self-register; makeCurrent() (guarded) ensures getCurrent() resolves.
			if (getCurrentOrNull.invoke(null) == null) {
				Method makeCurrent = fmlLoader.getDeclaredMethod("makeCurrent");
				makeCurrent.setAccessible(true);
				makeCurrent.invoke(loader);
			}

			seedNeoForgeLoadingModList(gameLoader, fmlLoader, loader, modsDir);

			ForbricLog.info("[Forbric/Seed] NeoForge FMLLoader seeded (dist=%s, production=%s) — "
					+ "environment identity only, no lifecycle", distName, PRODUCTION);
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not seed NeoForge FMLLoader identity");
		}
	}

	static String[] neoForgeVersionArguments(String gameVersion) {
		List<String> args = new ArrayList<>();
		if (gameVersion != null && !gameVersion.isBlank()) {
			args.add("--fml.mcVersion"); args.add(gameVersion);
		}
		String neoVersion = net.forbric.kernel.metadata.forge.EcosystemVersions.provided("neoforge");
		if (neoVersion != null && !neoVersion.isBlank()) {
			args.add("--fml.neoForgeVersion"); args.add(neoVersion);
		}
		return args.toArray(String[]::new);
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

			Class<?> lmlCls = Class.forName(ForeignType.LOADING_MOD_LIST.binary(Ecosystem.NEOFORGE), false, gameLoader);
			// of(modFiles, gameLibraries, plugins, modInfos, issues, dependencies) — all empty for zero mods.
			Method of = lmlCls.getMethod("of", List.class, List.class, List.class, List.class, List.class, Map.class);
			Object empty = of.invoke(null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
			field.set(loaderInstance, empty);
			ForbricLog.debug("[Forbric/Seed] seeded empty NeoForge LoadingModList (zero mods)");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not seed empty LoadingModList");
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// LoadingModList — POPULATED, not empty. See seedNeoForgeLoadingModList's javadoc for the why.
	// ---------------------------------------------------------------------------------------------------------

	/** Escape hatch: {@code -Dforbric.seedLoadingModList=off} restores the pre-fix EMPTY list. */
	static final String SEED_SWITCH = "forbric.seedLoadingModList";

	/** Lazily-resolved {@code sun.misc.Unsafe}, what constructor-free allocation goes through. */
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
	 * presence ({@code Class.forName(ForeignType.FML_LOADER.binary(Ecosystem.NEOFORGE))}). On a normal instance exactly one
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
		seedNeoForgeLoadingModList(gameLoader, fmlLoader, loaderInstance, modsDir, KernelBoot.nestedJarJarJars());
	}

	/**
	 * @param nestedJars the Forge-family jar-in-jar files this boot extracted and put on the classpath — see
	 *                   {@link #arbitratedNestedForgeFamilyMods}. Null before extraction has run.
	 */
	static void seedNeoForgeLoadingModList(ClassLoader gameLoader, Class<?> fmlLoader, Object loaderInstance,
			Path modsDir, List<Path> nestedJars) {
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
			Caught.warn(t, "[Forbric/Seed] could not discover Forge-family mods for the NeoForge LoadingModList — "
					+ "falling back to the empty list");
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}
		// The Fabric mods go in TOO. Not to be loaded — nothing here loads anything — but because this list is
		// what answers "is mod X installed" for a Forge-family mod, and the honest answer includes the mods the
		// other ecosystem is running. Physics Mod reads exactly this seam (LoadingModList.getModFileById) to decide
		// whether to render through Sodium's pipeline or vanilla's; told no next to a live Fabric Sodium, it drew
		// its debris and ragdolls into a path Sodium no longer runs, so they were simply never visible.
		List<DiscoveredMod> presence = new ArrayList<>(mods);
		Set<String> presenceIds = new LinkedHashSet<>();
		for (DiscoveredMod mod : mods) presenceIds.add(mod.getId());
		// The Forge-family mods that came out of another mod's jar go in beside them. See the method.
		List<DiscoveredMod> nested = arbitratedNestedForgeFamilyMods(nestedJars, presenceIds);
		presence.addAll(nested);
		for (DiscoveredMod mod : ModPresence.fabricMods()) {
			if (mod.getId() != null && mod.getSource() != null && presenceIds.add(mod.getId())) presence.add(mod);
		}

		if (presence.isEmpty()) {
			// Zero mods of any family: the old code path exactly, so gate-m1 / gate-m2b cannot move.
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}

		// The one point in the boot where BOTH ecosystems' mods are known together, which is the only vantage from
		// which a cross-ecosystem requirement can be judged at all. Diagnostic only — it never changes what loads,
		// and it is caught here because a diagnostic must never be able to fail the window it reports on: the next
		// statement seeds the list every Forge-family mod resolves itself through.
		// The same vantage, for the same reason, one question further on: a player's Mods screen needs every
		// family's mods too, and every family's own screen can only list its own. Diagnostic-adjacent and caught
		// the same way — a screen that cannot be built must never cost the seeding below.
		try {
			KernelModCatalog.publish(presence, modsDir);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Catalog] could not build the unified mod list — the Mods screen will fall "
					+ "back to whatever one family's own registry knows");
		}
		// The audit itself is NOT run here, and that is a fix rather than a rearrangement. Its second section
		// lists mixins that were written to attach to another mod and did not -- data that KernelGuestMixinAdapter
		// records while Mixin PARSES each config, which happens in KernelMixinBootstrap.init, roughly thirty lines
		// after the call that reaches this method. So the reader ran before the writer, every time, and
		// ForeignMixinBreaks.all() was always empty: that section of the dialog has never displayed anything.
		// gate-m20 could not see it because it drives the dialog with synthetic rows.
		//
		// Which is the same shape as everything else in this area: a diagnostic wired to a moment where its data
		// does not exist yet. The list is held here and KernelBoot asks for the audit once Mixin has run.
		pendingAudit = List.copyOf(presence);

		try {
			Field field = fmlLoader.getDeclaredField("loadingModList");
			field.setAccessible(true);
			if (field.get(loaderInstance) != null) return; // a genuine list exists — never overwrite it

			Object list = buildLoadingModList(gameLoader, presence);
			field.set(loaderInstance, list);

			StringBuilder ids = new StringBuilder();
			for (DiscoveredMod mod : presence) {
				if (ids.length() > 0) ids.append(", ");
				ids.append(mod.getId());
			}
			int forgeFamily = mods.size() + nested.size();
			ForbricLog.info("[Forbric/Seed] seeded NeoForge LoadingModList with %d mod(s) (%d Forge-family, %d "
					+ "Fabric for presence) — mods that resolve themselves through FMLLoader.getLoadingModList() "
					+ "(Iris' version probe, yumi/LambDynamicLights' mod lookup) find themselves, and mods that ask "
					+ "it about ANOTHER ecosystem's mod get the truth; %d of the Forge-family ones came out of "
					+ "another mod's jar. [%s]", presence.size(), forgeFamily, presence.size() - forgeFamily,
					nested.size(), ids);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not seed a populated NeoForge LoadingModList — falling back to the "
					+ "empty list; mods that look themselves up through it will not find themselves");
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
	 * <p>A universal jar's MinecraftForge half is suppressed like any half the jar's owner is not, so what is listed is
	 * NeoForge's. De-duplicated by mod id, first-wins, because {@code fileById} is a map and a duplicate id would
	 * otherwise silently shadow.
	 */
	static List<DiscoveredMod> arbitratedForgeFamilyMods(Path modsDir) throws Exception {
		List<DiscoveredMod> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		if (!Files.isDirectory(modsDir)) return out;

		ForbricModDiscoverer discoverer = MANIFESTS;
		List<Path> jars;
		try (var entries = Files.list(modsDir)) {
			jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile).sorted().toList();
		}

		// The seeded list must describe the jars this boot actually loaded. This walk is its own pass over mods/, so
		// it saw only MultiLoaderArbiter and reported a jar that cross-jar arbitration had already superseded — a
		// mod would then resolve itself through FMLLoader.getLoadingModList() and find the copy that is NOT running.
		DuplicateModArbiter.Decision dupes = DuplicateModArbiter.current();

		for (Path jar : jars) {
			if (dupes.suppressed(jar)) continue;
			// Per-jar, not one pass over the whole directory: an unreadable or malformed manifest anywhere in a real
			// mods folder must cost that one jar, not the entire seeded list.
			List<DiscoveredMod> declared;
			try {
				declared = discoverer.discoverJar(jar);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Seed] could not read %s for the NeoForge LoadingModList — skipping it",
						jar.getFileName());
				continue;
			}

			JarClaim claim = MultiLoaderArbiter.claimOf(jar);
			for (DiscoveredMod mod : declared) {
				if (!mod.getEcosystem().isForgeFamily()) continue;
				if (mod.getId() == null || mod.getId().isBlank() || mod.getSource() == null) continue;
				// Unowned means "no loader manifest at all", which cannot happen for a Forge-family mod; it is kept
				// rather than read as "not mine", per the arbiter's own contract. A refused jar is left out for every
				// family: nothing of it loads, so none of it is listed.
				if (claim.suppresses(mod.getEcosystem())) continue;
				if (!seen.add(mod.getId())) continue;
				out.add(mod);
			}
		}
		return out;
	}

	/**
	 * The one discoverer the seeder's reads of {@code mods/} and of the nested jars go through, and that
	 * {@link KernelModLoader#declaredMods} reads the same jars through later. A discoverer remembers what it has
	 * parsed only for itself, so with one each the same jar was parsed again for every pass — and every parse logs
	 * its {@code [modproperties]} line again, which reads like a second mod declaring them.
	 */
	static final ForbricModDiscoverer MANIFESTS = new ForbricModDiscoverer();

	/** {@code -Dforbric.seedNestedMods=off} leaves the jar-in-jar Forge-family mods out of the seeded list again. */
	static final String NESTED_SWITCH = "forbric.seedNestedMods";

	/**
	 * The Forge-family mods inside {@code nestedJars} — the jar-in-jar files this boot extracted, already past
	 * cross-jar arbitration — each through the same two filters {@link #arbitratedForgeFamilyMods} applies to
	 * {@code mods/}, first-wins by id against everything already in {@code seen} (which this adds to).
	 *
	 * <p>{@link #arbitratedForgeFamilyMods} lists only the jars directly in {@code mods/}, and nothing descended into
	 * them, so a mod shipped inside another mod's jar was loaded and constructed but was not in the list a mod
	 * resolves itself or a neighbour through. Native NeoForge's list has every one of them: LibJF alone brings twelve
	 * ({@code libjf_base} … {@code libjf_web_v1}), and Fake Players brings commonnetworking. The one reader found
	 * that pays for the gap is LibJF's own entry-point lookup, which walks {@code getMods()} while {@code ModList}
	 * does not exist yet — at mixin-plugin time — and caches what it finds: its {@code libjf:asm} declarer,
	 * {@code libjf_data_manipulation_v0}, is one of the nested twelve.
	 *
	 * <p>The source is the extracted list itself, whose paths are the files the classes are served from, so a
	 * seeded file's contents and scan read the right bytes.
	 *
	 * @param nestedJars null when extraction has not run, which contributes nothing
	 */
	static List<DiscoveredMod> arbitratedNestedForgeFamilyMods(List<Path> nestedJars, Set<String> seen) {
		List<DiscoveredMod> out = new ArrayList<>();
		if (nestedJars == null || nestedJars.isEmpty()) return out;
		if ("off".equalsIgnoreCase(System.getProperty(NESTED_SWITCH, "on"))) {
			ForbricLog.info("[Forbric/Seed] -D%s=off — mods carried inside another mod's jar are left out of the "
					+ "NeoForge LoadingModList", NESTED_SWITCH);
			return out;
		}

		ForbricModDiscoverer discoverer = MANIFESTS;
		DuplicateModArbiter.Decision dupes = DuplicateModArbiter.current();
		for (Path jar : nestedJars) {
			if (jar == null || dupes.suppressed(jar)) continue;
			List<DiscoveredMod> declared;
			try {
				declared = discoverer.discoverJar(jar);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Seed] could not read nested %s for the NeoForge LoadingModList — skipping it",
						jar.getFileName());
				continue;
			}
			JarClaim claim = MultiLoaderArbiter.claimOf(jar);
			for (DiscoveredMod mod : declared) {
				if (!mod.getEcosystem().isForgeFamily()) continue;
				if (mod.getId() == null || mod.getId().isBlank() || mod.getSource() == null) continue;
				if (claim.suppresses(mod.getEcosystem())) continue;
				if (!seen.add(mod.getId())) continue;
				out.add(mod);
			}
		}
		return out;
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
		Class<?> lmlCls = Class.forName(ForeignType.LOADING_MOD_LIST.binary(Ecosystem.NEOFORGE), false, gameLoader);
		Class<?> fileInfoCls = Class.forName(ForeignType.MOD_FILE_INFO.binary(Ecosystem.NEOFORGE), false, gameLoader);
		Class<?> modInfoCls = Class.forName(ForeignType.MOD_INFO.binary(Ecosystem.NEOFORGE), false, gameLoader);

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
			Object fileInfo = allocate(fileInfoCls);
			List<Object> ownMods = new ArrayList<>();
			for (DiscoveredMod mod : jar.getValue()) {
				Object modInfo = buildModInfo(gameLoader, modInfoCls, fileInfo, mod);
				ownMods.add(modInfo);
				modInfos.add(modInfo);
				fileById.putIfAbsent(mod.getId(), fileInfo);
			}
			fillModFileInfo(gameLoader, fileInfoCls, fileInfo, Path.of(jar.getKey()), jar.getValue().get(0),
					List.copyOf(ownMods), indexedForNeoForge(jar.getValue()));
			fileInfos.add(fileInfo);
		}

		indexUnderscoredIds(fileById);

		setInstanceField(lmlCls, "fileById", list, fileById);
		setInstanceField(lmlCls, "sortedList", list, new ArrayList<>(modInfos));
		setInstanceField(lmlCls, "modFiles", list, new ArrayList<>(fileInfos));
		return list;
	}

	/**
	 * Also indexes every dashed mod id under its underscored spelling, because that is the only spelling a
	 * NeoForge-side reader can ask with.
	 *
	 * <p>NeoForge mod ids may not contain {@code -} at all, so a mod written for NeoForge that wants to detect a
	 * Fabric module has to underscore the name — and it does so blind, at the call: Sodium's
	 * {@code NeoForgeRuntimeInformation.isModInLoadingList} is literally
	 * {@code getModFileById(id.replace('-', '_')) != null}, called with the string {@code "fabric-renderer-api-v1"}.
	 * The kernel seeds this list with the Fabric mods under their GENUINE ids, so that lookup asked for
	 * {@code fabric_renderer_api_v1}, got null, and Sodium concluded FRAPI was absent — it then installed a
	 * do-nothing renderer registrar, never registered {@code SodiumRenderer}, and left the FRAPI renderer slot
	 * open for Indigo to take. Producer on one side, consumer on the other, link silently dead.
	 *
	 * <p><b>Contained by measurement, not by hope:</b> {@code fileById} has exactly one writer (the constructor)
	 * and exactly one reader ({@code getModFileById}) in the merged base — verified on the bytecode — so an extra
	 * key can only make that one lookup answer for a spelling it previously refused. {@code getMods()} and
	 * {@code sortedList} are untouched, so nothing counts these twice and nothing lands in a handshake.
	 *
	 * <p>Aliases go in a SECOND pass, after every real id is indexed, and with {@code putIfAbsent}: a real mod
	 * that genuinely owns the underscored id keeps it, whatever order discovery happened to produce.
	 */
	private static void indexUnderscoredIds(Map<String, Object> fileById) {
		Map<String, Object> aliases = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : fileById.entrySet()) {
			String id = entry.getKey();
			if (id == null || id.indexOf('-') < 0) continue;
			aliases.put(id.replace('-', '_'), entry.getValue());
		}
		int added = 0;
		String example = null;
		for (Map.Entry<String, Object> alias : aliases.entrySet()) {
			if (fileById.putIfAbsent(alias.getKey(), alias.getValue()) == null) {
				added++;
				if (example == null) example = alias.getKey();
			}
		}
		if (added > 0) {
			// The example is the first alias this boot added, read from the list itself: the line names no mod of its own.
			ForbricLog.info("[Forbric/Seed] LoadingModList.getModFileById also answers for %d underscored mod id(s) "
					+ "(e.g. %s) — NeoForge ids cannot contain '-', so a NeoForge-side mod probing for a Fabric mod "
					+ "asks with underscores; the dashed spelling alone told it no", added, example);
		}
	}

	/**
	 * The answers an {@code IConfigurable} gives when the mod declares no config section — for EITHER family.
	 *
	 * <p>There were two of these, one per family, and only one of them was right. The Forge copy answered
	 * everything except {@code getConfigList} with {@code Optional.empty()}, and a dynamic {@link Proxy} routes
	 * {@code toString}/{@code hashCode}/{@code equals} to the handler as well — so asking a seeded Forge mod's
	 * config for its hash code returned an {@code Optional} where an {@code int} was declared, and the proxy
	 * threw {@link ClassCastException} on the way out. Those three are reached by ordinary things: a record whose
	 * component this is hashes it, a log line prints it, a collection compares it.
	 *
	 * <p>The two families differ in exactly one thing here — WHICH interface — so that is the parameter, and the
	 * answers are one implementation. They are not otherwise symmetrical and this does not pretend they are:
	 * traditional Forge's {@code IConfigurable} declares two extra DEFAULT methods
	 * ({@code getConfigElement(String)}, {@code getConfigList(String)}) that NeoForge's does not. A Proxy routes
	 * default methods to the handler too — their default bodies never run — which is precisely why this
	 * dispatches on the method NAME and not on the exact signature.
	 */
	private static final InvocationHandler EMPTY_CONFIGURABLE = (proxy, method, args) ->
			switch (method.getName()) {
				case "getConfigList" -> List.of();
				case "toString" -> "KernelSeededConfig";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == (args == null ? null : args[0]);
				default -> unmodelled("configurable", method);
			};

	/**
	 * Accessors a caller asked about that this kernel does not model, and the empty answer they got.
	 *
	 * <h2>Why the question is worth recording</h2>
	 *
	 * <p>A synthetic mod info can only answer in the interface's own types, so "I do not know" and "there is
	 * none" come out as the same empty value, and the caller cannot tell them apart. That is not hypothetical:
	 * Indigo asked a Sodium built for the other ecosystem whether it declared a renderer, got an empty answer
	 * because the property was never modelled, and took the branch for "no renderer here" — on an instance where
	 * Sodium had replaced the pipeline.
	 *
	 * <p>Nothing can be returned instead: the answer's type is the interface's. What can change is that the
	 * kernel stops being the only party that does not know it was asked. Every distinct accessor lands here once,
	 * and the summary names them, so the next such branch is found by reading a log rather than by a player.
	 */
	private static final java.util.Set<String> UNMODELLED =
			java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

	/** Records the question and gives the interface's empty answer. */
	private static Object unmodelled(String what, java.lang.reflect.Method method) {
		UNMODELLED.add(what + "." + method.getName() + " -> " + method.getReturnType().getSimpleName());
		return method.getReturnType() == List.class ? List.of() : Optional.empty();
	}

	/** Every unmodelled accessor asked for so far, sorted. */
	public static List<String> unmodelledAsked() {
		synchronized (UNMODELLED) {
			return UNMODELLED.stream().sorted().toList();
		}
	}

	/** The one line a gate greps; empty when nothing asked, which is the normal case and worth saying. */
	public static String unmodelledSummary() {
		List<String> asked = unmodelledAsked();
		return "[Forbric/Seed] " + asked.size() + " unmodelled mod-info accessor(s) were asked and answered empty"
				+ (asked.isEmpty() ? " — nothing asked this boot" : ": " + String.join(", ", asked));
	}

	/** Forgets them. For tests. */
	static void resetUnmodelled() {
		UNMODELLED.clear();
	}

	/** The handler itself, so a test can drive it without the game types. See EmptyConfigurableTest. */
	static InvocationHandler emptyConfigurableHandler() {
		return EMPTY_CONFIGURABLE;
	}

	/** An {@code IConfigurable} of {@code family} reporting "this mod declares nothing". */
	private static Object emptyConfigurable(ClassLoader gameLoader, Ecosystem family) throws Exception {
		Class<?> iConfigurable = Class.forName(ForeignType.CONFIGURABLE.binary(family), false, gameLoader);
		return Proxy.newProxyInstance(gameLoader, new Class<?>[] {iConfigurable}, EMPTY_CONFIGURABLE);
	}

	/** {@code -Dforbric.configElements=off} restores the empty answer this used to give. */
	private static final String CONFIG_ELEMENTS = "forbric.configElements";

	static boolean configElementsEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(CONFIG_ELEMENTS, "on"));
	}

	/**
	 * An {@code IConfigurable} of {@code family} that answers from {@code elements} — a mod's own {@code [[mods]]}
	 * entry, or the top level of the file it came from — the way that family's own {@code NightConfigWrapper} would.
	 *
	 * <p>{@code getConfigElement} is how a mod tells ANOTHER mod something through the loader. Sodium's
	 * {@code ForgeMixinOverrides} walks {@code LoadingModList} asking each {@code IModInfo} for
	 * {@code sodium:options}, so a mod that has taken over a renderer can switch off the sodium mixin that
	 * would otherwise do the same work twice — iris declares
	 * {@code [mods."sodium:options"] "mixin.features.render.world.sky" = false} for the sky it draws itself.
	 * Every seeded mod answered {@link #EMPTY_CONFIGURABLE}, so the table reached nobody:
	 * {@code Loaded configuration file for Sodium: 37 options available, 0 override(s) found}. Lithium reads the
	 * same kind of table one level up, from the owning FILE (see {@link #fileConfigurable}).
	 *
	 * <p>Whether to answer at all is the caller's decision, because each seam has its own switch.
	 *
	 * <p>A PARALLEL path, not a re-route: {@link #EMPTY_CONFIGURABLE} stays the one shared instance for mods
	 * with nothing to declare, which is what {@code EmptyConfigurableTest} asserts by identity.
	 *
	 * <p>Dispatches on the method NAME for the reason given on {@link #EMPTY_CONFIGURABLE}: a {@link Proxy}
	 * routes DEFAULT methods to the handler too. Each path element is a LITERAL key — never split on dots, because iris'
	 * key is the single literal {@code mixin.features.render.world.sky}.
	 */
	private static Object configurableOver(ClassLoader gameLoader, Ecosystem family, Map<String, Object> elements)
			throws Exception {
		if (elements == null || elements.isEmpty()) {
			return emptyConfigurable(gameLoader, family);
		}
		Class<?> iConfigurable = Class.forName(ForeignType.CONFIGURABLE.binary(family), false, gameLoader);
		InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
			// Unconditionally empty: building a nested IConfigurable here would mean Class.forName and a second
			// Proxy inside the handler, on whatever thread happens to ask.
			case "getConfigList" -> List.of();
			case "toString" -> "KernelSeededConfig";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == (args == null ? null : args[0]);
			case "getConfigElement" -> lookup(elements, args);
			default -> unmodelled("configurable", method);
		};
		return Proxy.newProxyInstance(gameLoader, new Class<?>[] {iConfigurable}, handler);
	}

	/**
	 * The top level of the file {@code first} came from, as an {@code IConfigurable} of {@code family}: what the seeded
	 * {@code ModFileInfo}'s {@code config} field holds, and so what its {@code getConfigElement} answers.
	 *
	 * <p>Natively that field is a {@code NightConfigWrapper} over the whole parsed {@code mods.toml}. Here it was
	 * {@link #EMPTY_CONFIGURABLE}, so every file-level key read as undeclared. Lithium's {@code NeoForgeMixinOverrides}
	 * walks {@code LoadingModList.getMods()} and asks each {@code getOwningFile().getConfigElement("lithium:options")};
	 * Unlit Campfire declares that table at the top level of its file to switch off Lithium's campfire sleeping
	 * mixin, and the player's log said {@code 0 override(s) found}.
	 *
	 * <p>A Fabric mod listed here for presence has no such file and keeps the empty answer. {@code first} is any mod of
	 * the jar: every mod of one file carries the same table. {@code -Dforbric.fileConfigElements=off} answers empty.
	 */
	private static Object fileConfigurable(ClassLoader gameLoader, Ecosystem family, DiscoveredMod first)
			throws Exception {
		if (!FmlConfigElements.enabled() || first == null || !first.getEcosystem().isForgeFamily()) {
			return emptyConfigurable(gameLoader, family);
		}
		return configurableOver(gameLoader, family, first.getFileConfigElements());
	}

	/** A proxied {@code getConfigElement}'s arguments as a path: {@code String[]}, a bare {@code String}, or none. */
	private static String[] path(Object[] args) {
		if (args == null || args.length == 0 || args[0] == null) return new String[0];
		return args[0] instanceof String[] keys ? keys : new String[] {String.valueOf(args[0])};
	}

	/**
	 * Walks {@code elements} by literal key. {@code args} is {@code String[]}, a bare {@code String}, or null.
	 *
	 * <p>Answers the way NeoForge's {@code NightConfigWrapper.getConfigElement} does. A table below the entry's top
	 * level is night-config's own {@code Config} (the parser keeps FML's shallow shape, so LibJF can cast a
	 * {@code [modproperties]} value to {@code Config}), and the wrapper never hands a {@code Config} out: it answers
	 * a table with its {@code valueMap()}, whose own nested tables stay {@code Config}. So this descends through
	 * either shape, and a table it lands on is answered as that table's {@code valueMap()} — the {@code Map} Sodium
	 * reads {@code sodium:options} out of. Not modelled: the wrapper THROWS {@code InvalidModFileException} for a
	 * path that lands on an array of tables; this answers the list. The walk itself is {@link FmlConfigElements},
	 * which the kernel's own mod infos answer through as well.
	 */
	static Optional<Object> lookup(Map<String, Object> elements, Object[] args) {
		return FmlConfigElements.neoForge(elements, path(args));
	}

	private static String displayName(DiscoveredMod mod) {
		String name = mod.getDisplayName();
		return name == null || name.isBlank() ? mod.getId() : name;
	}

	/** Sets a field that a future carrier version may not have — a rename must not cost the whole seeding. */
	private static void setOptionalInstanceField(Class<?> owner, String name, Object target, Object value) {
		try {
			setInstanceField(owner, name, target, value);
		} catch (NoSuchFieldException absent) {
			// a field a future carrier version may not have — leaving it at its default
			Caught.expected(absent);
		} catch (Exception unsettable) {
			Caught.warn(unsettable, "[Forbric/Seed] could not set %s.%s — leaving it at its default",
					owner.getSimpleName(), name);
		}
	}

	/**
	 * Fills a constructor-free {@code ModFileInfo}. Every field a reader can reach is set to a real value; the two
	 * booleans and {@code issueURL} keep their allocation defaults (false / null), which is exactly "this file
	 * declares no resource pack, no data pack and no issue tracker".
	 *
	 * <p>{@code config} must not be null: {@code ModFileInfo.getConfigElement}/{@code getConfigList} delegate to it
	 * straight through, and NeoForge's own {@code ModInfo} construction path reads it. It is a {@link Proxy}, which
	 * is safe here precisely because that field is typed as the INTERFACE {@code IConfigurable} and is only ever
	 * called through it. It answers from the file's own top level ({@link #fileConfigurable}), which is where
	 * Lithium looks for Unlit Campfire's {@code ["lithium:options"]}.
	 */
	private static void fillModFileInfo(ClassLoader gameLoader, Class<?> fileInfoCls, Object fileInfo, Path jar,
			DiscoveredMod first, List<Object> ownMods, boolean indexed) throws Exception {
		setInstanceField(fileInfoCls, "config", fileInfo, fileConfigurable(gameLoader, Ecosystem.NEOFORGE, first));
		setInstanceField(fileInfoCls, "mods", fileInfo, ownMods);
		setInstanceField(fileInfoCls, "languageSpecs", fileInfo, List.of());
		setInstanceField(fileInfoCls, "properties", fileInfo, Map.of());
		setInstanceField(fileInfoCls, "usesServices", fileInfo, List.of());
		// The kernel's mods.toml reader does not carry the license line; "" keeps the Mods screen's info pane a real
		// String (it is written into it unguarded) instead of a null.
		setInstanceField(fileInfoCls, "license", fileInfo, "");
		setInstanceField(fileInfoCls, "modFile", fileInfo,
				buildModFile(gameLoader, fileInfo, jar, first.getId(), version(first), indexed));
	}

	/**
	 * Whether a seeded file is given its jar's REAL annotation index: only when the jar is here as a NeoForge mod.
	 *
	 * <p>The other two kinds of entry get an empty index, and that is the truthful answer rather than a shortcut.
	 * Natively neither is in a NeoForge {@code LoadingModList} at all — the Fabric ones are listed for presence
	 * only, and a MinecraftForge jar belongs to the other FML. What an index would add is a mod walking every
	 * file's annotations and loading whatever it finds out of a jar the arbiter handed to another ecosystem: the
	 * NeoForge half of a universal jar that Fabric is already running, or a MinecraftForge {@code @JeiPlugin}
	 * handed to the NeoForge build of JEI. It is also exactly what {@code KernelModFile} answers for a mod with no
	 * jar behind it.
	 */
	static boolean indexedForNeoForge(List<DiscoveredMod> modsOfJar) {
		for (DiscoveredMod mod : modsOfJar) {
			if (mod.getEcosystem() == Ecosystem.NEOFORGE) return true;
		}
		return false;
	}

	/**
	 * A constructor-free {@code ModFile} whose contents are the mod's jar, opened the first time anyone reads them
	 * ({@link #lazyContents}); until then it performs no I/O and opens no handle.
	 *
	 * <p>It used to be {@code JarContents.empty(jar)} for good, and a mod that reads its own files through
	 * {@code FMLLoader.getLoadingModList()} got nothing: LambDynamicLights (through yumi) looks for the default
	 * {@code lambdynlights.toml} inside its jar on the first launch, found none and stopped the game with "This
	 * distribution of LambDynamicLights is broken". Every later launch passed, because the config it had failed to
	 * copy was by then written by hand or by an earlier build, which is why a long-lived test pack never saw it.
	 *
	 * <p>It exists so the file-shaped seams answer instead of NPE-ing: {@code ModFileInfo.toString()} is literally
	 * {@code modFile.getId()}, {@code getFilePath()} is {@code contents.getPrimaryPath()}, and NeoForge's mod-error
	 * reporting walks {@code getOwningFile().getFile().getFilePath()} whenever any mod-bus listener throws. With a
	 * null {@code modFile} each of those turns a real error into an NPE that MASKS it.
	 *
	 * <p>Best-effort: on any failure the caller's {@code modFile} stays null, which is still strictly better than
	 * the empty list this whole method replaces — but it is said at WARN, because a null file is not quiet for
	 * long: a mod walking the list calls {@code getFile().getScanResult()} on every entry, and RollingGate's
	 * constructor would NPE on this one.
	 *
	 * @param indexed whether this file answers {@code getScanResult()} with its jar's real index (see
	 *                {@link #indexedForNeoForge}) rather than an empty one
	 */
	private static Object buildModFile(ClassLoader gameLoader, Object fileInfo, Path jar, String id, String version,
			boolean indexed) {
		try {
			Class<?> modFileCls = Class.forName(ForeignType.MOD_FILE.binary(Ecosystem.NEOFORGE), false, gameLoader);
			Class<?> contentsCls = Class.forName("net.neoforged.fml.jarcontents.JarContents", false, gameLoader);
			Class<?> typeCls = Class.forName(ForeignType.MOD_FILE_TYPE.binary(Ecosystem.NEOFORGE), false, gameLoader);

			Object modFile = allocate(modFileCls);
			setInstanceField(modFileCls, "contents", modFile, lazyContents(gameLoader, contentsCls, jar));
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
			} catch (ClassNotFoundException | NoSuchFieldException optional) {
				// no ModFileDiscoveryAttributes.DEFAULT on this carrier — leaving it null
				Caught.expected(optional);
			} catch (Throwable unsettable) {
				Caught.warn(unsettable, "[Forbric/Seed] could not set ModFileDiscoveryAttributes.DEFAULT on the "
						+ "synthetic ModFile for '%s' — leaving it null", id);
			}
			seedScanResult(modFileCls, modFile, gameLoader, jar, indexed);
			return modFile;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not build a synthetic ModFile for '%s' — the ModFileInfo's "
					+ "file stays null, and a mod that walks FMLLoader.getLoadingModList().getModFiles() calling "
					+ "getFile() on each entry will NPE on this one", id);
			return null;
		}
	}

	/**
	 * Gives a seeded {@code ModFile} a scan result, so {@code getScanResult()} answers instead of throwing FML's
	 * "Scanning of this mod file has not started yet."
	 *
	 * <p>FML fills {@code futureScanResult} from {@code startScan}, which is its background scan at discovery; the
	 * kernel runs no FML discovery, so on a seeded file the field was null forever and the getter threw every
	 * time. That was invisible until a mod asked. RollingGate's constructor walks
	 * {@code LoadingModList.getModFiles()} and calls {@code getFile().getScanResult().getAnnotations()} on every
	 * entry to find its rule containers — so it threw out of its own constructor, and every RollingGate rule and
	 * every Server++ rule (found by the same walk) was simply missing from the server.
	 *
	 * <p>LAZY, on purpose: a {@link LazyScanFuture} does nothing until it is read. Most instances never ask, and
	 * an eager index would be an ASM pass over every NeoForge jar on every boot, run from the pre-Mixin window
	 * ({@code KernelBoot} seeds the FML identity before Mixin starts), which should load no more game-side
	 * classes than it has to. The jar's index comes from {@link ModFileScanner#scanShared}, so this file and the
	 * {@code KernelModFile} that {@code ModList} holds for the same jar answer with one object, as a single native
	 * {@code ModFile} would.
	 *
	 * <p>An index that cannot be built reads as an empty one and says so once for the jar — a mod walking the list
	 * must not die on a neighbour's unreadable file. So {@code getScanResult()} does not throw while the carrier's
	 * {@code ModFileScanData} can itself be instantiated; if even that fails, the carrier reports the failed scan.
	 *
	 * <p>{@code -Dforbric.seededScanData=off} leaves the field null, which is the behaviour before this.
	 */
	private static void seedScanResult(Class<?> modFileCls, Object modFile, ClassLoader gameLoader, Path jar,
			boolean indexed) {
		if (!ModFileScanner.seededIndexEnabled()) return;
		try {
			setInstanceField(modFileCls, "futureScanResult", modFile, seededScan(gameLoader, jar, indexed));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not give the seeded NeoForge ModFile for %s a scan result — "
					+ "its getScanResult() throws \"Scanning of this mod file has not started yet.\", which kills "
					+ "any mod that walks the LoadingModList's scan data from its constructor (RollingGate)",
					jar.getFileName());
		}
	}

	/** The lazy future {@link #seedScanResult} installs: the jar's shared index, or an empty one. */
	private static CompletableFuture<Object> seededScan(ClassLoader gameLoader, Path jar, boolean indexed) {
		return new LazyScanFuture(() -> {
			if (indexed) {
				Object real = null;
				// The switch, when it is the reason, rather than a scan that failed: ModFileScanner names it once too.
				String why = ModFileScanner.neoIndexEnabled() ? "the scan produced no index"
						: "-D" + ModFileScanner.NEO_INDEX_PROPERTY + "=off";
				try {
					real = ModFileScanner.scanShared(jar, gameLoader);
				} catch (Throwable t) {
					Caught.warn(t, "[Forbric/Seed] could not index %s for the seeded NeoForge LoadingModList — "
							+ "it answers getScanResult() with an EMPTY index, so a mod that finds its own members by "
							+ "walking that list (RollingGate's rule containers) finds nothing in this jar",
							jar.getFileName());
					why = null;
				}
				if (real != null) return real;
				if (why != null) {
					ForbricLog.warn("[Forbric/Seed] could not index %s for the seeded NeoForge LoadingModList (%s) — "
							+ "it answers getScanResult() with an EMPTY index, so a mod that finds its own members by "
							+ "walking that list (RollingGate's rule containers) finds nothing in this jar",
							jar.getFileName(), why);
				}
			}
			String empty = ForeignType.MOD_FILE_SCAN_DATA.binary(Ecosystem.NEOFORGE);
			try {
				return Class.forName(empty, true, gameLoader).getConstructor().newInstance();
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("could not build an empty " + empty, e);
			}
		});
	}

	/**
	 * NeoForge's {@code JarContents} for {@code jar}: {@code getPrimaryPath()} answers at once, and every other
	 * call opens {@code JarContents.ofPath(jar)} once and asks it. A jar that cannot be opened reads as
	 * {@code JarContents.empty}, as before, and says so once.
	 */
	static Object lazyContents(ClassLoader gameLoader, Class<?> contentsCls, Path jar) throws ReflectiveOperationException {
		Path path = jar.toAbsolutePath();
		Method ofPath = contentsCls.getMethod("ofPath", Path.class), empty = contentsCls.getMethod("empty", Path.class);
		Object[] opened = {null};
		InvocationHandler handler = (proxy, method, args) -> {
			switch (method.getName()) {
				case "getPrimaryPath" -> { if (method.getParameterCount() == 0) return path; }
				case "toString" -> { if (method.getParameterCount() == 0) return "JarContents(" + path + ", opened on first read)"; }
				case "hashCode" -> { if (method.getParameterCount() == 0) return System.identityHashCode(proxy); }
				case "equals" -> { if (method.getParameterCount() == 1) return proxy == args[0]; }
				case "close" -> {
					synchronized (opened) { if (opened[0] instanceof java.io.Closeable closeable) closeable.close(); opened[0] = null; }
					return null;
				}
				default -> { }
			}
			Object target;
			synchronized (opened) {
				if (opened[0] == null) {
					try { opened[0] = ofPath.invoke(null, path); }
					catch (java.lang.reflect.InvocationTargetException unreadable) {
						Caught.warn(unreadable, "[Forbric/Seed] could not open %s for its mod's own file reads — it reads as empty",
								path.getFileName());
						opened[0] = empty.invoke(null, path);
					}
				}
				target = opened[0];
			}
			try { return method.invoke(target, args); }
			catch (java.lang.reflect.InvocationTargetException thrown) { throw thrown.getCause(); }
		};
		return Proxy.newProxyInstance(gameLoader, new Class<?>[] {contentsCls}, handler);
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
		Object modInfo = allocate(modInfoCls);
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
		// The declared table, so a NeoForge mod asking a kernel-built IModInfo about its properties gets the truth
		// rather than silence. A Fabric mod listed here for presence answers with what
		// its fabric.mod.json declares, in this spelling (CrossEcosystemDeclarations).
		setInstanceField(modInfoCls, "properties", modInfo, CrossEcosystemDeclarations.declarationsOf(mod));
		setInstanceField(modInfoCls, "config", modInfo, configElementsEnabled()
				? configurableOver(gameLoader, Ecosystem.NEOFORGE, mod.getConfigElements())
				: emptyConfigurable(gameLoader, Ecosystem.NEOFORGE));
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


	/**
	 * Allocates {@code type} WITHOUT running any constructor.
	 *
	 * <p>{@code sun.misc.Unsafe.allocateInstance}, from the JDK itself. It used to go through MinecraftForge's
	 * {@code UnsafeHacks.newInstance} first, which is exactly that call behind a MinecraftForge class ({@code javap}:
	 * {@code getstatic UNSAFE; aload_0; invokevirtual Unsafe.allocateInstance}), with this as the fallback where there was no
	 * MinecraftForge; the fallback is now the only way, so the seeding needs nothing of MinecraftForge and allocates the
	 * same object either way.
	 */
	private static Object allocate(Class<?> type) throws Exception {
		Object unsafe = jdkUnsafe;
		if (unsafe == null) {
			Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
			Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			jdkUnsafe = unsafe = theUnsafe.get(null);
		}
		return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
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
			Class<?> eventCls = Class.forName(ForeignType.NEW_REGISTRY_EVENT.binary(Ecosystem.NEOFORGE), false, gameLoader);

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
			Caught.expected(absent);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not register NeoForge baseline registries");
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
			Caught.expected(absent);
			return false;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not apply NeoForge registry modifications — falling back to the "
					+ "sync-flags-only path; synced data attachments will not work");
			return false;
		}
	}

	/** NeoForge's FML config, whose {@code earlyWindowControl} says whether FML shows (and so controls) a window early. */
	static final String NEO_FML_CONFIG = ForeignType.FML_CONFIG.binary(Ecosystem.NEOFORGE);
	static final String NEO_EARLY_WINDOW_CONTROL = "EARLY_WINDOW_CONTROL";

	/**
	 * Makes NeoForge's {@code FMLConfig} answer {@code earlyWindowControl = false} on a client, because under the kernel
	 * NeoForge's early window never exists: nothing calls {@code ImmediateWindowHandler.load}, its provider stays null,
	 * and the game creates its own window in {@code Minecraft.<init>}.
	 *
	 * <p>The kernel never loads {@code fml.toml} into NeoForge's config (it is MinecraftForge's file too, and
	 * MinecraftForge's FML loads it), so every value read the enum's default — {@code true} for this one, a promise of a
	 * window that is never opened. Whatever trusts it plans around a window that does not come. Sodium's NeoForge
	 * {@code GraphicsBootstrapper} is the reader that matters: on {@code true} it applies its driver environment changes
	 * for FML's window and starts a thread that waits thirty seconds for FML's early renderer before it puts them back
	 * with a warning; on {@code false} — genuine NeoForge's answer when the early window is switched off — it leaves them to
	 * its own wrap of the game's {@code glfwCreateWindow}, which is the window Forbric actually opens.
	 *
	 * <p>Only that one answer changes. The config the enum reads is given every other entry at its own default value, so
	 * each other getter — read back here before and after, and compared — answers exactly what it answered with no config
	 * loaded. In memory only: nothing is written, and a config someone already loaded is left alone. Not on a dedicated
	 * server, where genuine FML reads {@code earlyWindowControl} from the file and goes headless regardless, so the value
	 * a reader sees there is the default, as here.
	 */
	static void seedNeoForgeNoEarlyWindow(ClassLoader gameLoader) {
		try {
			Class<?> config = Class.forName(NEO_FML_CONFIG, true, gameLoader);
			Class<?> valueType = Class.forName(NEO_FML_CONFIG + "$ConfigValue", true, gameLoader);
			Field instanceField = config.getDeclaredField("INSTANCE");
			instanceField.setAccessible(true);
			Object instance = instanceField.get(null);
			Field data = config.getDeclaredField("configData");
			data.setAccessible(true);
			if (data.get(instance) != null) {
				ForbricLog.info("[Forbric/Seed] NeoForge's FMLConfig was already loaded — its earlyWindowControl is left as "
						+ "loaded (%s)", config.getMethod("getBoolConfigValue", valueType)
								.invoke(null, enumConstant(valueType, NEO_EARLY_WINDOW_CONTROL)));
				return;
			}
			Method read = valueType.getDeclaredMethod("getConfigValue", data.getType());
			read.setAccessible(true);
			Field entry = valueType.getDeclaredField("entry");
			entry.setAccessible(true);
			Field defaultValue = valueType.getDeclaredField("defaultValue");
			defaultValue.setAccessible(true);
			Object control = enumConstant(valueType, NEO_EARLY_WINDOW_CONTROL);

			Object[] values = valueType.getEnumConstants();
			Map<Object, Object> before = new LinkedHashMap<>();
			for (Object value : values) before.put(value, read.invoke(value, (Object) null));

			// CommentedConfig.inMemory(): the type of the field itself, so the config is the one NightConfig the carrier has.
			Object seeded = data.getType().getMethod("inMemory").invoke(null);
			Method set = data.getType().getMethod("set", String.class, Object.class);
			for (Object value : values) {
				set.invoke(seeded, entry.get(value), value == control ? Boolean.FALSE : defaultValue.get(value));
			}
			data.set(instance, seeded);

			List<String> changed = new ArrayList<>();
			for (Object value : values) {
				Object now = read.invoke(value, seeded);
				if (!java.util.Objects.equals(before.get(value), now)) changed.add(entry.get(value) + "=" + now);
			}
			if (!changed.equals(List.of(entry.get(control) + "=false"))) {
				data.set(instance, null);
				ForbricLog.error("[Forbric/Seed] seeding NeoForge's FMLConfig changed %s, not earlyWindowControl alone — put "
						+ "back to no config", changed);
				return;
			}
			ForbricLog.info("[Forbric/Seed] NeoForge's FMLConfig answers earlyWindowControl=false: the kernel never opens "
					+ "NeoForge's early window (the game creates its own), so whatever plans around that window — a "
					+ "GraphicsBootstrapper's driver workarounds — is told so; the other %d entries answer as before",
					values.length - 1);
		} catch (ClassNotFoundException absent) {
			// No NeoForge carrier: no config to answer for.
			Caught.expected(absent);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Seed] could not make NeoForge's FMLConfig say there is no early window — a "
					+ "GraphicsBootstrapper reading earlyWindowControl plans for FML's early window, which never opens");
		}
	}

	private static Object enumConstant(Class<?> enumType, String name) {
		for (Object constant : enumType.getEnumConstants()) {
			if (((Enum<?>) constant).name().equals(name)) return constant;
		}
		throw new IllegalArgumentException(enumType.getName() + " has no constant " + name);
	}

	/** The mods the audit will judge, held until Mixin has registered its configs. See above. */
	private static volatile List<DiscoveredMod> pendingAudit = List.of();

	/**
	 * Runs the dependency audit, now that every source it reads from has actually been written.
	 *
	 * <p>Still diagnostic-only and still caught: an audit must never be able to fail the boot it reports on.
	 */
	public static void reportDependencies() {
		List<DiscoveredMod> present = pendingAudit;
		if (present.isEmpty()) return;
		try {
			DependencyAudit.report(present, KernelBoot.nestedJarJarJars(), KernelFabricEcosystem.physicalSide());
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Deps] dependency audit failed, skipping it");
		}
	}
}
