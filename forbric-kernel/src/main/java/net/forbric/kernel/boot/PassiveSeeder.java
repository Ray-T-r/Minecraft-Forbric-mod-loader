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

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.api.ForgeLoadingList;
import net.forbric.api.ModPresence;
import net.forbric.api.Side;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModFileScanner;
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
	 * Whether {@link #seedForgeFmlLoader} has COMPLETED. Kernel-owned rather than "is some foreign field set yet",
	 * so a partial failure — the launch handler landed, {@code FMLConfig.load()} threw — still retries on the next
	 * call instead of being mistaken for a finished job. What the guard actually prevents is a second
	 * {@code FMLConfig.load()}: {@code loadFrom} builds a fresh {@code CommentedFileConfig} over the old one
	 * without closing it and then saves.
	 */
	private static boolean forgeIdentitySeeded;

	/** Whether {@link #seedForgeFmlLoader} ran to completion. Package-visible for the test that pins the retry. */
	static boolean forgeIdentitySeeded() {
		return forgeIdentitySeeded;
	}

	/** Puts the completion flag back, so a test can seed more than one classloader in one JVM. */
	static void resetForgeIdentityForTests() {
		forgeIdentitySeeded = false;
	}

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
		// Normally a no-op by now: KernelBoot seeds the MinecraftForge identity pre-Mixin, because its
		// FMLEnvironment is a one-shot that the first guest mixin plugin's <clinit> would otherwise decide. Kept
		// here so seedAll still means "every identity" on any path that skipped that block.
		seedForgeFmlLoader(gameLoader, gameDir, side);
		// NOTE: NeoForge baseline-registry registration is NOT done here — NeoForgeRegistriesSetup.<clinit> touches
		// game registries and throws "Not bootstrapped" pre-Main. It runs post-Bootstrap via KernelLifecycle
		// (the redirected ServerModLoader.load window). See KernelLifecycle.onServerModLoading.
		// Traditional-Forge FMLEnvironment is no longer left to whoever touches it first: seedForgeFmlLoader
		// decides it, from the pre-Mixin window in KernelBoot. See verifyForgeFmlEnvironment.
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
			Object loader = ctor.newInstance(gameLoader, new String[0], dist, PRODUCTION, gameDir);

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

			Class<?> lmlCls = Class.forName(ForeignType.LOADING_MOD_LIST.binary(Ecosystem.NEOFORGE), false, gameLoader);
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
	 * The Forge-family mods this boot discovered, kept from the NeoForge seeding so traditional Forge's list can be
	 * built from the SAME answer. Two independent passes over mods/ could disagree, and the two lists disagreeing
	 * about which mods exist is precisely the bug this avoids.
	 */
	private static volatile List<DiscoveredMod> forgeFamilyMods = List.of();

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
		if ("off".equalsIgnoreCase(System.getProperty(SEED_SWITCH, "on"))) {
			ForbricLog.warn("[Forbric/Seed] -D%s=off — seeding an EMPTY NeoForge LoadingModList; mods that look "
					+ "themselves up through FMLLoader.getLoadingModList() will not find themselves", SEED_SWITCH);
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}

		List<DiscoveredMod> mods;
		try {
			mods = arbitratedForgeFamilyMods(modsDir);
			forgeFamilyMods = List.copyOf(mods);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not discover Forge-family mods for the NeoForge LoadingModList — "
					+ "falling back to the empty list", unwrap(t));
			seedEmptyLoadingModList(gameLoader, fmlLoader, loaderInstance);
			return;
		}
		// The Fabric mods go in TOO. Not to be loaded — nothing here loads anything — but because this list is
		// what answers "is mod X installed" for a Forge-family mod, and the honest answer includes the mods the
		// other ecosystem is running. Physics Mod reads exactly this seam (LoadingModList.getModFileById) to decide
		// whether to render through Sodium's pipeline or vanilla's; told no next to a live Fabric Sodium, it drew
		// its debris and ragdolls into a path Sodium no longer runs, so they were simply never visible.
		//
		// forgeFamilyMods above is deliberately NOT extended: that list is what traditional Forge's ModList is
		// built from, and a MinecraftForge ModList is announced to servers in the handshake. Presence must not
		// turn into "this client claims to run those mods".
		List<DiscoveredMod> presence = new ArrayList<>(mods);
		Set<String> presenceIds = new LinkedHashSet<>();
		for (DiscoveredMod mod : mods) presenceIds.add(mod.getId());
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
			ForbricLog.warn("[Forbric/Catalog] could not build the unified mod list — the Mods screen will fall "
					+ "back to whatever one family's own registry knows", unwrap(t));
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
			ForbricLog.info("[Forbric/Seed] seeded NeoForge LoadingModList with %d mod(s) (%d Forge-family, %d "
					+ "Fabric for presence) — mods that resolve themselves through FMLLoader.getLoadingModList() "
					+ "(Iris' version probe, yumi/LambDynamicLights' mod lookup) find themselves, and mods that ask "
					+ "it about ANOTHER ecosystem's mod get the truth. [%s]", presence.size(), mods.size(),
					presence.size() - mods.size(), ids);
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
				ForbricLog.debug("[Forbric/Seed] could not read %s for the NeoForge LoadingModList (%s) — skipping it",
						jar.getFileName(), String.valueOf(t));
				continue;
			}

			Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			for (DiscoveredMod mod : declared) {
				if (!mod.getEcosystem().isForgeFamily()) continue;
				if (mod.getId() == null || mod.getId().isBlank() || mod.getSource() == null) continue;
				// owner == null means "no loader manifest at all", which cannot happen for a Forge-family mod; treat
				// it as unowned (keep) rather than as "not mine", per the arbiter's own contract.
				if (owner != null && owner != mod.getEcosystem()) continue;
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
	 * The two lists {@code LoadingModListImpl}'s constructor takes, built once and handed to BOTH readers: the
	 * {@code ModSorter$State} MinecraftForge's own {@code init} path expects, and {@link ForgeLoadingList}, which
	 * is what the rewritten lazy holder actually reads. Keeping them one value is what stops those two from being
	 * independently derived and quietly disagreeing.
	 *
	 * @param files    MinecraftForge {@code ModFile}s, one per jar
	 * @param modInfos MinecraftForge {@code ModInfo}s, one per declared mod
	 */
	private record ForgeLoadingLists(List<Object> files, List<Object> modInfos) {
	}

	/**
	 * Builds the contents of traditional Forge's loading list — one {@code ModFile}+{@code ModFileInfo} per jar, a
	 * {@code ModInfo} per declared mod — from the same discovery answer the NeoForge list is seeded with.
	 *
	 * <p>What has to be right is narrow, because only one path ever reads these: {@code LoadingModListImpl}'s lazy
	 * holder builds the list with {@code new LoadingModListImpl(files, mods)}, and that constructor
	 * touches exactly {@code ModFile.getModFileInfo}, {@code ModFileInfo.getMods}, {@code ModInfo.getModId} and
	 * {@code ModInfo.getOwningFile}. {@code ModList}'s own initializer then adds {@code ModFileInfo.getFile}, and the
	 * handshake's {@code ModVersions.create} adds {@code getDisplayName} and {@code getVersion}. The remaining fields
	 * are filled anyway, with truthful empties, so a consumer this kernel has not met does not meet a null.
	 *
	 * <p>Deliberately NOT reached: {@code LoadingModListImpl.init}, which walks each file's access transformers and
	 * touches the jars. Nothing calls it here — the lazy holder is the only builder — and seeding must stay a
	 * description of what was loaded, not a second loading pass.
	 */
	private static ForgeLoadingLists buildForgeLoadingLists(ClassLoader gameLoader, List<DiscoveredMod> mods)
			throws Exception {
		Class<?> modFileCls = Class.forName(ForeignType.MOD_FILE.binary(Ecosystem.FORGE), false, gameLoader);
		Class<?> fileInfoCls = Class.forName(ForeignType.MOD_FILE_INFO.binary(Ecosystem.FORGE), false, gameLoader);
		Class<?> modInfoCls = Class.forName(ForeignType.MOD_INFO.binary(Ecosystem.FORGE), false, gameLoader);

		// One file per JAR, N mods inside it: a mods.toml may declare several [[mods]], and the file is what the
		// list keys its per-file map on.
		Map<String, List<DiscoveredMod>> byJar = new LinkedHashMap<>();
		for (DiscoveredMod mod : mods) {
			byJar.computeIfAbsent(mod.getSource(), key -> new ArrayList<>()).add(mod);
		}

		List<Object> files = new ArrayList<>();
		List<Object> modInfos = new ArrayList<>();
		int annotations = 0;
		long startedAt = System.nanoTime();
		for (Map.Entry<String, List<DiscoveredMod>> jar : byJar.entrySet()) {
			Object modFile = allocate(gameLoader, modFileCls);
			Object fileInfo = allocate(gameLoader, fileInfoCls);
			List<Object> ownMods = new ArrayList<>();
			for (DiscoveredMod mod : jar.getValue()) {
				Object modInfo = buildForgeModInfo(gameLoader, modInfoCls, fileInfo, mod);
				ownMods.add(modInfo);
				modInfos.add(modInfo);
			}
			fillForgeModFileInfo(gameLoader, fileInfoCls, fileInfo, modFile, List.copyOf(ownMods));
			annotations += fillForgeModFile(modFileCls, modFile, fileInfo, Path.of(jar.getKey()),
					version(jar.getValue().get(0)));
			files.add(modFile);
		}
		if (ModFileScanner.forgeIndexEnabled()) {
			ForbricLog.info("[Forbric/Seed] indexed %d annotation(s) across %d MinecraftForge jar(s) in %d ms — "
					+ "ModList.getAllScanData() is how a traditional-Forge mod finds its OWN members "
					+ "(SuperMartijn642 Core Lib's @RegistryEntryAcceptor field injection, Forge's own "
					+ "@AutoRegisterCapability sweep), and it held nothing at all until now "
					+ "(-Dforbric.forgeScanData=off to go back)",
					annotations, byJar.size(), (System.nanoTime() - startedAt) / 1_000_000L);
		}
		return new ForgeLoadingLists(files, modInfos);
	}

	/**
	 * The jar's own entry.
	 *
	 * <p>{@code modFileInfo} is what the list build reads; the rest are truthful empties — except the jar itself,
	 * which is not optional and used to be missing.
	 *
	 * <p><b>The field that was written for years and does not exist.</b> This method ended with
	 * {@code setOptionalInstanceField(modFileCls, "filePath", ...)}, and {@code ModFile} has no {@code filePath}
	 * field: javap shows the path lives behind {@code private final SecureJar jar}, and every path accessor —
	 * {@code getFilePath}, {@code getFileName}, {@code findResource}, {@code toString} — goes through it. Being
	 * "optional", the write failed silently at debug level, so the seeded ModFile carried a NULL SecureJar and
	 * every one of those accessors NPE'd. ShoulderSurfing-Forge walks {@code ModList.getModFiles()} calling
	 * {@code findResource} on each from its config-loading listener, so its init never completed; and because
	 * {@code toString} goes the same way, any attempt to LOG the failure NPE'd too and hid the real one.
	 */
	private static final Map<Path, Object> FORGE_SCAN_CACHE = new LinkedHashMap<>();

	private static int fillForgeModFile(Class<?> modFileCls, Object modFile, Object fileInfo, Path jar, String version)
			throws Exception {
		setInstanceField(modFileCls, "modFileInfo", modFile, fileInfo);
		setInstanceField(modFileCls, "jarVersion", modFile, version);
		setInstanceField(modFileCls, "fileProperties", modFile, Map.of());
		setInstanceField(modFileCls, "loaders", modFile, List.of());
		// An empty list, not null: whoever walks a file's access transformers must find none rather than throw. The
		// kernel applies them itself, from its own pass over the same jars.
		setInstanceField(modFileCls, "accessTransformers", modFile, List.of());
		// Real scan data, and an empty one rather than null if the scan could not be built: ModList.getAllScanData()
		// maps every file through getScanResult(), and MinecraftForge's own CapabilityManager.injectCapabilities
		// streams that list unguarded — a null entry NPE'd inside Forge's code on every boot.
		//
		// It was empty for years, which is a different bug with no error attached to it. The index is how a
		// traditional-Forge mod finds its OWN members: SuperMartijn642's Core Lib injects every
		// @RegistryEntryAcceptor static field from it, so Packed Up's MenuType field stayed null and the client
		// died in Minecraft.<init> with "Container screen registered with null menu type!" — text that names
		// neither the index nor the kernel. Forge's own @AutoRegisterCapability sweep read the same nothing.
		int annotations = 0;
		try {
			// Cached per jar because this whole list is built TWICE — once before Mixin starts and once from the
			// mod-loading window — and the index is a read-only answer about a file that did not change between
			// them. Without it the ASM pass over every Forge-family jar runs twice for one boot.
			Object scanData = FORGE_SCAN_CACHE.get(jar);
			if (scanData == null) scanData = ModFileScanner.scanForge(jar, modFileCls.getClassLoader());
			if (scanData == null) {
				scanData = Class.forName("net.minecraftforge.forgespi.language.ModFileScanData", true,
						modFileCls.getClassLoader()).getConstructor().newInstance();
			}
			setOptionalInstanceField(modFileCls, "fileModFileScanData", modFile, scanData);
			FORGE_SCAN_CACHE.put(jar, scanData);
			// Counted off the object itself, not off what was handed to it: the number in the boot log is then
			// evidence about the index a mod will actually read, and goes to zero the moment one stops being built.
			annotations = ((Set<?>) scanData.getClass().getMethod("getAnnotations").invoke(scanData)).size();
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Seed] could not give the seeded ModFile its scan data: %s", String.valueOf(unwrap(t)));
		}
		fillForgeModFileJar(modFileCls, modFile, jar);
		return annotations;
	}

	/**
	 * Gives a seeded {@code ModFile} the real jar behind it, and marks it a MOD.
	 *
	 * <p>MinecraftForge's own {@code SecureJar.from(Path...)} is NOT used, and the reason is not a preference:
	 * it initialises {@code cpw.mods.jarhandling.impl.Jar}, which demands ModLauncher's
	 * {@code UnionFileSystemProvider} and throws without it. The kernel replaces ModLauncher, so that call throws
	 * once and then hands back {@code NoClassDefFoundError} forever. {@link ForgeSecureJarStandIn} implements the
	 * interface over a plain zip file system instead. The class is loaded without initialising it — only its
	 * interface shape is wanted here.
	 *
	 * <p>Warn rather than debug on failure, and say what it costs. The previous silence is the whole reason this
	 * was shipped broken: a mod walking the mod files got an NPE out of MinecraftForge's own accessor, and the
	 * kernel's log said nothing at all.
	 */
	private static void fillForgeModFileJar(Class<?> modFileCls, Object modFile, Path jar) {
		try {
			Class<?> secureJar = Class.forName("cpw.mods.jarhandling.SecureJar", false, modFileCls.getClassLoader());
			setInstanceField(modFileCls, "jar", modFile, ForgeSecureJarStandIn.create(secureJar, jar));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not give the seeded MinecraftForge ModFile for " + jar.getFileName()
					+ " its jar — getFilePath, getFileName, findResource and toString all read it, so a mod walking "
					+ "ModList.getModFiles() will NPE inside MinecraftForge's own accessor", unwrap(t));
		}
		// Type.MOD, not null: a consumer filtering the list by type would otherwise drop every seeded file, and a
		// null here reaches a switch in MinecraftForge's own code.
		try {
			Class<?> type = Class.forName(ForeignType.MOD_FILE_TYPE.binary(Ecosystem.FORGE), true,
					modFileCls.getClassLoader());
			setOptionalInstanceField(modFileCls, "modFileType", modFile,
					Enum.valueOf(type.asSubclass(Enum.class), "MOD"));
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Seed] could not set the seeded ModFile's type: %s", String.valueOf(unwrap(t)));
		}
	}

	private static void fillForgeModFileInfo(ClassLoader gameLoader, Class<?> fileInfoCls, Object fileInfo,
			Object modFile, List<Object> ownMods) throws Exception {
		setInstanceField(fileInfoCls, "modFile", fileInfo, modFile);
		setInstanceField(fileInfoCls, "mods", fileInfo, ownMods);
		setInstanceField(fileInfoCls, "config", fileInfo, emptyConfigurable(gameLoader, Ecosystem.FORGE));
		setInstanceField(fileInfoCls, "languageSpecs", fileInfo, List.of());
		setInstanceField(fileInfoCls, "properties", fileInfo, Map.of());
		setInstanceField(fileInfoCls, "usesServices", fileInfo, List.of());
		// "" rather than null: the Mods screen writes the license into its info pane unguarded.
		setInstanceField(fileInfoCls, "license", fileInfo, "");
	}

	/**
	 * One mod's entry, through the record's own canonical constructor — {@code ModInfo} is a record, and a record's
	 * fields refuse reflective writes however accessible they are made, so the field surgery that builds the two
	 * classes around it is not an option here.
	 *
	 * <p>The arguments are matched by RECORD COMPONENT NAME rather than by position, so a carrier that adds or
	 * reorders a component fails loudly on the one it cannot fill instead of silently putting a version where a
	 * description belongs. {@code getDisplayName} and {@code getVersion} are what the handshake puts on the wire, so
	 * they carry the discovered name and version; everything else is a truthful empty.
	 */
	private static Object buildForgeModInfo(ClassLoader gameLoader, Class<?> modInfoCls, Object owningFile,
			DiscoveredMod mod) throws Exception {
		Class<?> holderCls = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModInfo$Holder", false, gameLoader);
		Constructor<?> holderCtor = holderCls.getDeclaredConstructor(Object.class);
		holderCtor.setAccessible(true);

		Map<String, Object> byComponent = new LinkedHashMap<>();
		byComponent.put("getOwningFile", owningFile);
		byComponent.put("getConfig", emptyConfigurable(gameLoader, Ecosystem.FORGE));
		byComponent.put("getModId", mod.getId());
		byComponent.put("getNamespace", mod.getId());
		byComponent.put("getVersion", artifactVersion(gameLoader, version(mod)));
		byComponent.put("getDisplayName", displayName(mod));
		byComponent.put("getDescription", "");
		byComponent.put("getLogoFile", Optional.empty());
		byComponent.put("getLogoBlur", Boolean.FALSE);
		byComponent.put("getUpdateURL", Optional.empty());
		byComponent.put("getModURL", Optional.empty());
		// Empty holders rather than null ones: dependency resolution does not run here, but anything that asks must
		// get a list saying "nothing declared" instead of an NPE.
		byComponent.put("dependencies", holderCtor.newInstance(List.of()));
		byComponent.put("forgeFeatures", holderCtor.newInstance(List.of()));
		// The mod's own [modproperties.<id>] table, not an empty one: it is how a mod addresses ANOTHER mod, and
		// Sodium reads its config entry point out of exactly this to build that mod's Video Settings page.
		byComponent.put("getModProperties", mod.getModProperties());

		java.lang.reflect.RecordComponent[] components = modInfoCls.getRecordComponents();
		if (components == null) {
			throw new IllegalStateException(modInfoCls.getName() + " is no longer a record — re-derive its construction");
		}
		Class<?>[] types = new Class<?>[components.length];
		Object[] args = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			String name = components[i].getName();
			if (!byComponent.containsKey(name)) {
				throw new IllegalStateException(modInfoCls.getName() + " gained a component this kernel cannot fill: "
						+ name + " (" + components[i].getType().getName() + ")");
			}
			types[i] = components[i].getType();
			args[i] = byComponent.get(name);
		}
		Constructor<?> canonical = modInfoCls.getDeclaredConstructor(types);
		canonical.setAccessible(true);
		return canonical.newInstance(args);
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
				default -> method.getReturnType() == List.class ? List.of() : Optional.empty();
			};

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
	 * An {@code IConfigurable} that answers from the mod's own {@code [[mods]]} entry.
	 *
	 * <p>{@code getConfigElement} is how a mod tells ANOTHER mod something through the loader. Sodium's
	 * {@code ForgeMixinOverrides} walks {@code LoadingModList} asking each {@code IModInfo} for
	 * {@code sodium:options}, so a mod that has taken over a renderer can switch off the sodium mixin that
	 * would otherwise do the same work twice — iris declares
	 * {@code [mods."sodium:options"] "mixin.features.render.world.sky" = false} for the sky it draws itself.
	 * Every seeded mod answered {@link #EMPTY_CONFIGURABLE}, so the table reached nobody:
	 * {@code Loaded configuration file for Sodium: 37 options available, 0 override(s) found}.
	 *
	 * <p>A PARALLEL path, not a re-route: {@link #EMPTY_CONFIGURABLE} stays the one shared instance for mods
	 * with nothing to declare, which is what {@code EmptyConfigurableTest} asserts by identity.
	 *
	 * <p>Dispatches on the method NAME for the reason given on {@link #EMPTY_CONFIGURABLE}: a {@link Proxy}
	 * routes DEFAULT methods to the handler too, and traditional Forge's interface declares two single-String
	 * overloads NeoForge's does not. Each path element is a LITERAL key — never split on dots, because iris'
	 * key is the single literal {@code mixin.features.render.world.sky}.
	 */
	private static Object configurableOver(ClassLoader gameLoader, Ecosystem family, Map<String, Object> elements)
			throws Exception {
		if (elements == null || elements.isEmpty() || !configElementsEnabled()) {
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
			default -> method.getReturnType() == List.class ? List.of() : Optional.empty();
		};
		return Proxy.newProxyInstance(gameLoader, new Class<?>[] {iConfigurable}, handler);
	}

	/** Walks {@code elements} by literal key. {@code args} is {@code String[]}, a bare {@code String}, or null. */
	private static Optional<Object> lookup(Map<String, Object> elements, Object[] args) {
		if (args == null || args.length == 0 || args[0] == null) return Optional.empty();
		String[] path = args[0] instanceof String[] keys ? keys : new String[] {String.valueOf(args[0])};
		Object current = elements;
		for (String key : path) {
			if (!(current instanceof Map<?, ?> map)) return Optional.empty();
			current = map.get(key);
			if (current == null) return Optional.empty();
		}
		return Optional.of(current);
	}

	private static String displayName(DiscoveredMod mod) {
		String name = mod.getDisplayName();
		return name == null || name.isBlank() ? mod.getId() : name;
	}

	/** Sets a field that a future carrier version may not have — a rename must not cost the whole seeding. */
	private static void setOptionalInstanceField(Class<?> owner, String name, Object target, Object value) {
		try {
			setInstanceField(owner, name, target, value);
		} catch (Exception absent) {
			ForbricLog.debug("[Forbric/Seed] %s has no field %s — leaving it at its default", owner.getSimpleName(), name);
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
	 * called through it.
	 */
	private static void fillModFileInfo(ClassLoader gameLoader, Class<?> fileInfoCls, Object fileInfo, Path jar,
			DiscoveredMod first, List<Object> ownMods) throws Exception {
		setInstanceField(fileInfoCls, "config", fileInfo, emptyConfigurable(gameLoader, Ecosystem.NEOFORGE));
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
			Class<?> modFileCls = Class.forName(ForeignType.MOD_FILE.binary(Ecosystem.NEOFORGE), false, gameLoader);
			Class<?> contentsCls = Class.forName("net.neoforged.fml.jarcontents.JarContents", false, gameLoader);
			Class<?> typeCls = Class.forName(ForeignType.MOD_FILE_TYPE.binary(Ecosystem.NEOFORGE), false, gameLoader);

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
		// As in buildForgeModInfo: the declared table, so a NeoForge mod asking a kernel-built IModInfo about
		// its properties gets the truth rather than silence.
		setInstanceField(modInfoCls, "properties", modInfo, mod.getModProperties());
		setInstanceField(modInfoCls, "config", modInfo,
				configurableOver(gameLoader, Ecosystem.NEOFORGE, mod.getConfigElements()));
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
	 * Publishes MinecraftForge's mod list to {@link ForgeLoadingList} BEFORE Mixin is installed, so the rewritten
	 * {@code LoadingModListImpl$1LazyInit} has a real answer no matter who touches {@code LoadingModList} first.
	 *
	 * <p>This exists because the seed twenty lines below is too late and cannot tell that it is. That initializer
	 * is a one-shot with an empty exception table: a caller arriving before {@link KernelLifecycle}'s mod-loading
	 * window — a guest mixin plugin resolving "is mod X present" during {@code prepareConfigs},
	 * {@code ServerStatusPing} on the first ping, anything reached from a static initializer — used to NPE inside
	 * it and leave the class permanently erroneous. Seeding afterwards then still succeeded and still logged
	 * success, because the class that goes erroneous is the nested holder, not {@code LoadingModListImpl}.
	 *
	 * <p><b>Its own discovery pass, not {@code forgeFamilyMods}.</b> That field is only written on the NeoForge
	 * seeding path, which skips itself entirely when no NeoForge carrier is present — so on an instance carrying
	 * MinecraftForge alone it is still {@code List.of()} here, and publishing from it would freeze an empty list
	 * into a {@code static final} that can never be replaced. The trade this whole fix exists to refuse is exactly
	 * "crash becomes silent empty list", and reading a field that is empty for a reason unrelated to the answer is
	 * how that trade sneaks back in. {@code arbitratedForgeFamilyMods} is deterministic for a given mods directory,
	 * so asking it again here agrees with the other caller by construction rather than by hope.
	 *
	 * <p>Nothing is published if discovery throws: {@link ForgeLoadingList} then keeps answering "no list yet",
	 * which is loud. An empty list is published only when the directory genuinely holds no MinecraftForge-family
	 * mod, which is a truthful answer and is logged as one.
	 *
	 * @param modsDir passed in rather than derived here, for the same reason
	 *                {@code seedNeoForgeLoader} takes it: two derivations of "where the mods are" is how they drift
	 */
	public static void publishForgeLoadingList(ClassLoader gameLoader, Path modsDir) {
		if ("off".equalsIgnoreCase(System.getProperty(SEED_SWITCH, "on"))) {
			ForbricLog.debug("[Forbric/ForgeList] -D%s=off — not publishing MinecraftForge's LoadingModList",
					SEED_SWITCH);
			return;
		}
		try {
			// Absence of the carrier, not a failure: with no MinecraftForge the holder is never defined, the
			// transform never fires, and nobody will ever call ForgeLoadingList.
			Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl", false, gameLoader);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/ForgeList] no MinecraftForge carrier — nothing to publish");
			return;
		}
		try {
			List<DiscoveredMod> mods = arbitratedForgeFamilyMods(modsDir);
			ForgeLoadingLists lists = mods.isEmpty()
					? new ForgeLoadingLists(List.of(), List.of())
					: buildForgeLoadingLists(gameLoader, mods);
			ForgeLoadingList.publish(lists.files(), lists.modInfos());
			// The timing claim is made HERE, by the only caller that can know it: this runs before
			// KernelMixinBootstrap.init, and the late fallback in seedForgeLoadingModList does not. ForgeLoadingList
			// .publish itself says nothing about when, because both callers reach it and a gate reading a timing
			// guarantee out of a line the callee wrote would stay green through exactly this regression.
			ForbricLog.info("[Forbric/ForgeList] published MinecraftForge's LoadingModList BEFORE Mixin starts — "
					+ "%d mod(s). Its list is built by a one-shot class initializer, so the answer has to exist "
					+ "before the first guest mixin plugin can ask for it.", lists.modInfos().size());
		} catch (Throwable t) {
			// Deliberately does NOT publish an empty list as a fallback. Reading an unpublished list throws with a
			// stack naming the reader; publishing an empty one here would freeze "no mods" into a final field and
			// put MinecraftForge's handshake on the wire saying this instance runs none.
			ForbricLog.warn("[Forbric/ForgeList] could not build MinecraftForge's LoadingModList before Mixin — "
					+ "nothing published, so the first read of LoadingModList will throw instead of quietly "
					+ "reporting zero mods", unwrap(t));
		}
	}

	/**
	 * Reads MinecraftForge's list back through the same door its mods do, and says so when the two disagree.
	 *
	 * <p>The seed above cannot detect its own failure: {@code LoadingModListImpl}'s initializer only fetches a
	 * logger and always succeeds, so {@code Class.forName} resolves, the {@code temp} write lands, and the "seeded
	 * N mod(s)" line prints — all while the nested holder is erroneous and every read fails. The write side is
	 * green in both worlds. Only the READ side can tell them apart.
	 */
	private static void verifyForgeLoadingModList(ClassLoader gameLoader, int expectedMods) {
		try {
			Class<?> lml = Class.forName(ForeignType.LOADING_MOD_LIST.binary(Ecosystem.FORGE), true, gameLoader);
			Object got = lml.getMethod("getMods").invoke(null);
			int actual = got instanceof List<?> list ? list.size() : -1;
			if (actual == expectedMods) {
				ForbricLog.debug("[Forbric/ForgeList] LoadingModList.getMods() reads back %d mod(s) — the list the "
						+ "handshake announces is the list the kernel built", actual);
			} else {
				ForbricLog.error("[Forbric/ForgeList] LoadingModList.getMods() reads back %d mod(s) but the kernel "
						+ "built %d — MinecraftForge's mods will announce themselves wrongly to every peer",
						actual, expectedMods);
			}
		} catch (ClassNotFoundException absent) {
			// No carrier; the seed above already said so.
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/ForgeList] MinecraftForge's LoadingModList is UNREADABLE even though seeding "
					+ "reported success — its lazy holder was poisoned by an earlier reader, so every mod list "
					+ "query for the rest of this run fails and its handshake will say mods=[]", unwrap(t));
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

			List<DiscoveredMod> mods = "off".equalsIgnoreCase(System.getProperty(SEED_SWITCH, "on"))
					? List.of()
					: forgeFamilyMods;
			ForgeLoadingLists lists = mods.isEmpty()
					? new ForgeLoadingLists(List.of(), List.of())
					: buildForgeLoadingLists(gameLoader, mods);
			// LATE publish, as a fallback only. publishForgeLoadingList ran before Mixin and normally owns this;
			// ForgeLoadingList.publish keeps the FIRST writer, so this is a no-op then. It matters when the early
			// pass could not build a list: the holder still gets a real answer here, exactly as it did before this
			// fix existed, instead of the run dying on a list that was computable all along.
			boolean late = !ForgeLoadingList.isPublished();
			ForgeLoadingList.publish(lists.files(), lists.modInfos());
			if (late) {
				// Everything downstream still works — but only because nothing happened to read LoadingModList
				// during Mixin this run. Next run, with one more mod, it might.
				ForbricLog.warn("[Forbric/ForgeList] MinecraftForge's LoadingModList was published LATE, from the "
						+ "mod-loading window — the pre-Mixin pass did not produce one. Anything that reads "
						+ "LoadingModList before this point poisons its one-shot holder for the whole run.");
			}
			Object state = stateCtor.newInstance(lists.files(), lists.modInfos());

			// Still written, even though the rewritten holder no longer reads it: LoadingModListImpl.init is
			// MinecraftForge's own path through this field and costs nothing to keep honest.
			Class<?> lmlImpl = Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl", true, gameLoader);
			Field temp = lmlImpl.getDeclaredField("temp");
			temp.setAccessible(true);
			if (temp.get(null) == null) temp.set(null, state);
			// The read side, because the write side above succeeds whether or not the holder is already poisoned.
			verifyForgeLoadingModList(gameLoader, ForgeLoadingList.publishedModCount());
			if (mods.isEmpty()) {
				ForbricLog.debug("[Forbric/Seed] seeded empty traditional-Forge LoadingModList (zero mods)");
			} else {
				StringBuilder ids = new StringBuilder();
				for (DiscoveredMod mod : mods) {
					if (ids.length() > 0) ids.append(", ");
					ids.append(mod.getId());
				}
				ForbricLog.info("[Forbric/Seed] seeded traditional-Forge LoadingModList with %d mod(s) — ModList.getMods() "
						+ "derives its whole contents from this once, and an empty one is what left MinecraftForge's "
						+ "handshake telling every peer it runs no mods. [%s]", mods.size(), ids);
			}
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge LoadingModListImpl not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed traditional-Forge LoadingModList — its ModList stays empty "
					+ "and its mods tell every peer they are absent", unwrap(t));
		}

		// Traditional-Forge ModList keeps mods/indexedMods/sortedContainers as STATIC fields, null until mod
		// loading. ServerStatusPing → ModList.forEachModContainer iterates indexedMods → NPE. Seed empties.
		try {
			Class<?> modList = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.FORGE), true, gameLoader);
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
	 *
	 * <p>The {@code dist} used to be the literal {@code DEDICATED_SERVER}, with no side argument to say otherwise.
	 * On a Forbric CLIENT that made traditional MinecraftForge answer "dedicated server" to every question about
	 * which half of the game it was on — {@code FMLEnvironment.dist}, {@code FMLLoader.getDist()},
	 * {@code DistExecutor}'s branch selection, the runtime {@code @OnlyIn} checks. Nothing throws when that is
	 * wrong; the mod simply takes its server branch on a client, which is the same silent shape as a mod told the
	 * wrong thing about another mod's presence. The NeoForge seeder three methods up has carried a comment about
	 * exactly this hazard for its own dist since it was written.
	 *
	 * <p>Reported at INFO rather than DEBUG for the same reason: this value decides which half of every
	 * traditional-Forge mod runs, so it belongs in a log a user can hand over.
	 *
	 * <p><b>WHEN this runs is part of the contract.</b> It is called from {@code KernelBoot}'s pre-Mixin block,
	 * not from {@link #seedAll}, because {@code FMLEnvironment} caches these values in {@code static final}
	 * fields the first time anything touches it — and a guest mixin config may declare an
	 * {@code IMixinConfigPlugin} whose own {@code <clinit>} reads {@code FMLEnvironment.dist} while Mixin is
	 * preparing configs, which is before {@code seedAll}. Seeded afterwards, {@code dist} is null for the rest of
	 * the run and nothing says so: supermartijn642's CoreLib lost every mixin to it ("Error loading companion
	 * plugin class") and libIPN's Kotlin constructor died on "dist must not be null". NeoForge's
	 * {@code FMLEnvironment} needs none of this — it is stateless, two static methods and no fields.
	 */
	public static void seedForgeFmlLoader(ClassLoader gameLoader, Path gameDir, Side side) {
		if (forgeIdentitySeeded) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge FMLLoader identity already seeded — not re-seeding");
			return;
		}
		try {
			Class<?> fmlLoader = Class.forName(ForeignType.FML_LOADER.binary(Ecosystem.FORGE), false, gameLoader);
			setStaticIfNull(fmlLoader, "gamePath", gameDir.toAbsolutePath());
			setStaticIfNull(fmlLoader, "naming", "mojmap");
			Field productionField = fmlLoader.getDeclaredField("production");
			productionField.setAccessible(true);
			productionField.setBoolean(null, PRODUCTION);
			Field distField = fmlLoader.getDeclaredField("dist");
			distField.setAccessible(true);
			Object existing = distField.get(null);
			if (existing == null) {
				Class<?> distClass = Class.forName(ForeignType.DIST.binary(Ecosystem.FORGE), false, gameLoader);
				distField.set(null, Enum.valueOf(distClass.asSubclass(Enum.class), side.distName()));
				ForbricLog.info("[Forbric/Seed] traditional-Forge dist seeded %s (production=%s) — this is what "
						+ "every MinecraftForge mod's side check reads", side.distName(), PRODUCTION);
			} else {
				ForbricLog.info("[Forbric/Seed] traditional-Forge dist was already %s; leaving it (running %s)",
						existing, side.distName());
			}
			// Immediately: FMLEnvironment caches exactly the three fields written above, and the rest of this
			// method (launch handler, FMLPaths, FMLConfig) can fail without that being wrong. Deciding the
			// one-shot here means a failure further down costs those things, not the dist.
			verifyForgeFmlEnvironment(gameLoader, side);
			seedForgeLaunchHandler(gameLoader, fmlLoader, side);

			// Traditional-Forge FMLPaths + FMLConfig (ForgeMod's config registration reads FMLConfig; ConfigFileType
			// Handler.<clinit> NPEs if FMLConfig.load() hasn't populated its backing file config).
			Class<?> fmlPaths = Class.forName(ForeignType.FML_PATHS.binary(Ecosystem.FORGE), false, gameLoader);
			fmlPaths.getMethod("loadAbsolutePaths", Path.class).invoke(null, gameDir.toAbsolutePath());
			Class<?> fmlConfig = Class.forName("net.minecraftforge.fml.loading.FMLConfig", false, gameLoader);
			fmlConfig.getMethod("load").invoke(null);
			forgeIdentitySeeded = true;
			ForbricLog.debug("[Forbric/Seed] seeded traditional-Forge FMLLoader identity + FMLPaths + FMLConfig");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge FMLLoader not present — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Seed] could not seed traditional-Forge FMLLoader identity", unwrap(t));
		}
	}

	/**
	 * DECIDES {@code FMLEnvironment} here, one line after the values it caches were written, and reads it back.
	 *
	 * <p>{@code FMLEnvironment.<clinit>} is four assignments from {@code FMLLoader}'s getters into {@code public
	 * static final} fields, with no exception table — it cannot fail and it cannot be repeated. That makes it
	 * unlike the {@code LoadingModList} holder, which went ERRONEOUS and could at least be caught: here the first
	 * reader simply wins, quietly, and every later {@code FMLEnvironment.dist} in the process is whatever that
	 * reader saw. Forcing the initializer at a kernel-owned point ends the race rather than hoping to win it.
	 *
	 * <p>Then it checks the answer, because "seeded" and "is what we seeded" are different claims and only the
	 * second one is the one mods depend on. Reported at ERROR when they disagree: with a null or wrong dist,
	 * {@code AutomaticEventSubscriber} skips every {@code @EventBusSubscriber} and nothing throws.
	 */
	private static void verifyForgeFmlEnvironment(ClassLoader gameLoader, Side side) {
		try {
			Class<?> env = Class.forName(ForeignType.FML_ENVIRONMENT.binary(Ecosystem.FORGE), true, gameLoader);
			Object seen = env.getField("dist").get(null);
			if (seen == null) {
				ForbricLog.error("[Forbric/Seed] MinecraftForge FMLEnvironment is null after seeding — something "
						+ "read it before the kernel seeded FMLLoader, and its fields are static final, so dist is "
						+ "null for the REST OF THIS RUN: every @EventBusSubscriber is skipped and every dist "
						+ "branch takes the wrong side, all without throwing");
			} else if (!side.distName().equals(String.valueOf(seen))) {
				ForbricLog.error("[Forbric/Seed] MinecraftForge FMLEnvironment disagrees with this side: it says "
						+ "dist=%s, the kernel is running %s — decided by an earlier reader and unchangeable",
						seen, side.distName());
			} else {
				ForbricLog.info("[Forbric/Seed] MinecraftForge FMLEnvironment decided here: dist=%s production=%s",
						seen, PRODUCTION);
			}
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Seed] traditional-Forge FMLEnvironment not present — nothing to decide");
		} catch (Throwable t) {
			ForbricLog.error("[Forbric/Seed] could not read back MinecraftForge's FMLEnvironment", unwrap(t));
		}
	}

	/**
	 * Gives traditional Forge its {@code CommonLaunchHandler}, which is null under the kernel because nothing runs
	 * ModLauncher's {@code setupLaunchHandler}.
	 *
	 * <p>This was invisible for as long as the dist was hardcoded to {@code DEDICATED_SERVER}: {@code FluidType}'s
	 * constructor calls {@code initClient()} only on the client, and {@code initClient} asks
	 * {@code FMLLoader.getLaunchHandler().isData()}. Telling Forge the truth about the side therefore reached a
	 * second thing the kernel had never seeded, and the NPE landed inside {@code ForgeMod}'s own
	 * {@code RegisterEvent} handler — where it aborted the WHOLE traditional-Forge baseline registration, so
	 * {@code ForgeMod.EMPTY_TYPE} was never bound and the first {@code ServerPlayer} construction died on
	 * "Registry Object not present: minecraft:empty". The player was dropped with "Invalid player data", six
	 * layers away from the cause.
	 *
	 * <p>Forge's own {@code ForgeProdLaunchHandler.Client}/{@code .Server} is used rather than a stand-in: its
	 * constructor takes no arguments and does nothing but record the launch type, and using the real class means
	 * the dist, the {@code isData} flag and the handler name all come from Forge's answer rather than the
	 * kernel's guess at it. A Forbric instance is a shipped one — the dev handlers describe a Gradle workspace.
	 */
	private static void seedForgeLaunchHandler(ClassLoader gameLoader, Class<?> fmlLoader, Side side)
			throws Exception {
		Field handlerField = fmlLoader.getDeclaredField("commonLaunchHandler");
		handlerField.setAccessible(true);
		if (handlerField.get(null) != null) return;

		Class<?> handlerClass = Class.forName("net.minecraftforge.fml.loading.targets.ForgeProdLaunchHandler$"
				+ (side.isClient() ? "Client" : "Server"), false, gameLoader);
		Object handler = handlerClass.getDeclaredConstructor().newInstance();
		handlerField.set(null, handler);

		String name = String.valueOf(handlerClass.getMethod("name").invoke(handler));
		setStaticIfNull(fmlLoader, "launchHandlerName", name);
		ForbricLog.info("[Forbric/Seed] traditional-Forge launch handler seeded (%s) — FluidType.initClient and "
				+ "anything else asking getLaunchHandler() now gets an answer instead of null", name);
	}

	private static void setStaticIfNull(Class<?> owner, String field, Object value) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		if (f.get(null) == null) f.set(null, value);
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
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
			ForbricLog.debug("[Forbric/Deps] dependency audit failed, skipping it: %s", String.valueOf(t));
		}
	}
}
