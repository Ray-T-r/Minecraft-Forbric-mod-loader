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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.access.ClassTweakerTransformer;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.DiscoveredMod;
import net.forbric.kernel.metadata.ModEcosystem;
import net.forbric.kernel.mixin.KernelMixinBootstrap;
import net.forbric.kernel.transform.ClientPackHookInjector;
import net.forbric.kernel.transform.CommonNetworkInteropInjector;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import net.forbric.kernel.transform.GuestMixinPluginGuard;
import net.forbric.kernel.transform.HudElementBridgeInjector;
import net.forbric.kernel.transform.LifecycleHookInjector;
import net.forbric.kernel.transform.LoaderProbeRewriter;
import net.forbric.kernel.transform.MethodBodyNeuter;
import net.forbric.kernel.transform.PackMetadataFailSoftInjector;
import net.forbric.kernel.transform.RegistryHookRedirector;
import net.forbric.kernel.transform.TransformChain;
import net.forbric.kernel.transform.TransformContext;
import net.forbric.kernel.transform.TransformPhase;
import net.forbric.kernel.util.ForbricLog;

/**
 * The shared boot flow behind {@link KernelServerLaunch} and {@code KernelClientLaunch}: build the one sovereign
 * {@link ForbricClassLoader} over the merged base + ecosystem carriers + all mods + MC libraries, install the
 * transform pipeline (access wideners → lifecycle redirect → concessions → Mixin), discover all three ecosystems,
 * bring up Mixin + Fabric, and hand off to the merged base's {@code Main.main} — which now runs plain vanilla boot
 * with the genuine loader trigger redirected to the kernel's own native registration window.
 *
 * <p>Server vs client differ only in the {@link Side}: env type, entry class, which lifecycle trigger is
 * redirected, and a few side-specific transform concessions. Everything else is identical, which is the point of
 * sharing it — the ecosystems' registration is side-independent.
 */
public final class KernelBoot {
	private KernelBoot() {
	}

	/** Used only when the base jar carries no {@code version.json}; the merged base is built from 26.2. */
	private static final String FALLBACK_GAME_VERSION = "26.2";

	/** The two boot sides. */
	public enum Side {
		SERVER(EnvType.SERVER, LifecycleHookInjector.SERVER_MAIN, true),
		CLIENT(EnvType.CLIENT, LifecycleHookInjector.CLIENT_MAIN, false);

		final EnvType envType;
		final String entryClass;
		/** The dedicated server rejects {@code --gameDir}; the client accepts it. */
		final boolean stripGameDir;

		Side(EnvType envType, String entryClass, boolean stripGameDir) {
			this.envType = envType;
			this.entryClass = entryClass;
			this.stripGameDir = stripGameDir;
		}

		LifecycleHookInjector injector() {
			return this == SERVER ? LifecycleHookInjector.forServer() : LifecycleHookInjector.forClient();
		}
	}

	/**
	 * Runs the shared boot for {@code side}. {@code args} are the raw process args:
	 * {@code --gameJar}/{@code --runtimeJar}/{@code --libraryPath} are consumed here; everything after {@code --}
	 * (and any unrecognized token) is forwarded to the game's {@code Main.main}.
	 */
	public static void launch(Side side, String[] args) throws Throwable {
		List<URL> owned = new ArrayList<>();
		List<String> gameArgs = new ArrayList<>();
		List<Path> runtimeJars = new ArrayList<>();
		Path gameJar = null;
		String libraryPath = null;
		boolean afterSep = false;

		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if (afterSep) {
				gameArgs.add(a);
				continue;
			}
			switch (a) {
				case "--gameJar" -> {
					Path jar = new File(req(args, ++i, a)).toPath();
					if (gameJar == null) gameJar = jar;
					owned.add(jar.toUri().toURL());
				}
				case "--runtimeJar" -> {
					// Retained (not just owned): each ecosystem's runtime jar IS that ecosystem's own "mod" — FML
					// scans it for @EventBusSubscriber exactly like a mod jar, so the kernel must too.
					Path jar = new File(req(args, ++i, a)).toPath();
					runtimeJars.add(jar);
					owned.add(jar.toUri().toURL());
				}
				case "--libraryPath" -> libraryPath = req(args, ++i, a);
				case "--" -> afterSep = true;
				default -> gameArgs.add(a);
			}
		}

		if (owned.isEmpty()) {
			System.err.println("forbric-kernel: no --gameJar given (need the merged base jar)");
			System.exit(2);
			return;
		}

		Path gameDir = extractGameDir(gameArgs, side.stripGameDir);
		String gameVersion = detectGameVersion(gameJar);

		// Forge/NeoForge mod jars (Mojmap-compiled like the merged base → load directly, no remap), plus the
		// libraries they nest at META-INF/jarjar/ — see extractForgeFamilyJarJar.
		ForgeFamilyMods forgeFamily = discoverForgeFamilyModJars(gameDir.resolve("mods"));
		List<Path> modJars = new ArrayList<>(forgeFamily.jars());
		List<Path> nested = extractForgeFamilyJarJar(modJars, gameDir);
		modJars.addAll(nested);
		for (Path jar : modJars) owned.add(jar.toUri().toURL());

		// A nested mod's mixins are the same defect one level down. These jars already get everything else a
		// top-level mod gets — they are owned, and KernelModLoader scans them for @Mod, which is how whitenoise
		// (inside Mob Champions) is constructed — so leaving their configs out would be arbitrary. Pure libraries
		// declare none and cost one manifest read.
		List<KernelForgeFamilyMixins.ForgeMixinConfig> forgeMixinDecls = new ArrayList<>(forgeFamily.mixinConfigs());
		forgeMixinDecls.addAll(discoverNestedForgeMixinConfigs(nested));

		// Fabric mods (+ extracted JiJ children). Also Mojmap on this game version. Creates the FabricLoader.
		List<Path> fabricJars = KernelFabricEcosystem.discover(side.envType, gameDir, gameVersion,
				gameArgs.toArray(new String[0]));
		for (Path jar : fabricJars) {
			if (!modJars.contains(jar)) owned.add(jar.toUri().toURL());   // a multiloader jar carries both manifests
		}

		// Game-side bundled libraries (MixinExtras).
		for (Path jar : KernelBundledJars.extract(gameDir)) owned.add(jar.toUri().toURL());

		// The MC libraries, owned LAST (nothing shadows the merged base). Owned, not merely parent-visible: mods
		// mixin into them (fabric-dimension-api-v1 → DataFixerUpper's TaggedChoice). See ForbricClassLoader.
		int libCount = 0;
		for (Path lib : libraryJars(libraryPath)) {
			owned.add(lib.toUri().toURL());
			libCount++;
		}

		ForbricLog.info("[Forbric/Boot] sovereign kernel — %s %s, %d owned jar(s), %d Forge-family mod(s), "
				+ "%d Fabric jar(s), %d MC library jar(s)", side.name().toLowerCase(), gameVersion, owned.size(),
				modJars.size(), fabricJars.size(), libCount);

		ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(new URL[0]),
				KernelBoot.class.getClassLoader());

		// Every mod jar probes as the loader the arbiter gave it, so a mod cannot wander into a branch it never ran
		// on its own platform — and a universal jar answers as the ONE ecosystem it was arbitrated to. Plain
		// libraries declare no manifest and stay unowned. See LoaderProbePolicy.
		loader.setJarFamilies(probeFamilies(fabricJars, modJars));
		LoaderProbePolicy.bindGuestLoader(loader);

		// A mod that unpacks its real payload at preLaunch has no public API for adding it to the classpath and
		// reaches into Fabric's internals for it. Installed before any mod class loads. See KernelFabricLauncher.
		net.forbric.kernel.fabric.KernelFabricLauncher.install(loader, side.envType);

		TransformChain chain = new TransformChain();

		// Fabric access wideners before Mixin (ACCESS phase): the weaver must see the widened members.
		ClassTweakerTransformer accessWideners =
				ClassTweakerTransformer.create(KernelFabricEcosystem.accessWideners(), loader::putGeneratedClass);
		if (accessWideners != null) chain.register(TransformPhase.ACCESS, accessWideners);

		// The Forge-family twin: every mod jar's META-INF/accesstransformer.cfg, in the same ACCESS phase.
		net.forbric.kernel.access.AccessTransformer forgeAts = forgeFamilyAccessTransformer(modJars);
		if (forgeAts != null) chain.register(TransformPhase.ACCESS, forgeAts);

		// A guest mod's platform probe answers for the loader that mod was loaded as. Registered first in the phase:
		// it rewrites only Class.forName call sites, so nothing later in the chain can be looking at what it edits.
		LoaderProbeRewriter loaderProbes = new LoaderProbeRewriter(loader::familyOfClass);
		if (LoaderProbePolicy.enabled()) chain.register(TransformPhase.COREMOD, loaderProbes);

		// One mod's mixin config plugin must not be able to abort config preparation for every other mod. Mixin
		// guards plugin construction but not the calls, and a throw there escapes select(). See GuestMixinPluginGuard.
		chain.register(TransformPhase.COREMOD, new GuestMixinPluginGuard());

		LifecycleHookInjector lifecycleHook = side.injector();
		chain.register(TransformPhase.COREMOD, lifecycleHook);

		// Repairs class-local invariants the 3-ABI byte-merge breaks. It was written but never wired — without it the
		// merged base keeps divergent-pipeline lambda twins whose invokedynamic bootstrap handle disagrees with the
		// surviving lambda's static-ness (e.g. PrepareSpawnTask$Ready: a REF_invokeStatic handle on lambda$spawn$1
		// that merged in as an INSTANCE method, its static twin renamed lambda$spawn$2) → IncompatibleClassChangeError
		// when the player spawns. Also re-adds the MinecraftForge getFluidType() bridge the NeoForge-won Fluid classes
		// dropped (the Forge/Neo FluidType ABI split).
		chain.register(TransformPhase.COREMOD, new ForbricMergedBaseCompatTransformer());

		// Client only: hand the kernel the live PackRepository at the vanilla-woven
		// ClientModLoader.setupModResourcePacks call inside Minecraft.<init>, so it can serve the ecosystem jars'
		// assets. (Registered unconditionally — the transformer only matches the two ClientModLoader classes, which a
		// dedicated server never loads.)
		chain.register(TransformPhase.COREMOD, new ClientPackHookInjector());

		// A multiloader mod ships one pack.mcmeta carrying a section per loader, and on Forbric all three parsers are
		// live — so a Fabric-only build gets its neoforge:overlays section read by NeoForge's parser and throws on a
		// condition only a NeoForge build would have registered. Vanilla drops the ENTIRE pack for that. Registered
		// unconditionally: the datapack path runs on a dedicated server too, and that is where it crashed.
		chain.register(TransformPhase.COREMOD, new PackMetadataFailSoftInjector());

		// Client only: NeoForge won Hud.extractRenderState, so the call sites fabric-rendering-v1's HudMixin anchors
		// on no longer exist — as METHOD REFERENCES in the layer manager they exist as no bytecode at all, so no
		// anchor resolution can reach them. Every Fabric mod's HUD element silently drew nothing. Matches only
		// GuiLayerManager, which a dedicated server never loads.
		chain.register(TransformPhase.COREMOD, new HudElementBridgeInjector());

		// Client only: fire the Fabric client entrypoints from inside Minecraft.<init> (before Options), the window
		// Fabric uses — so a client entrypoint touching Minecraft.getInstance() (keymapping registration etc.) sees a
		// live instance. Matches only Minecraft.<init>, which a dedicated server never loads.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ClientEntrypointHookInjector());

		// Arbitrate the c:version / c:register common-networking channel that Fabric and NeoForge both claim — without
		// it a tri-in-one client is kicked "invalid packet" when Fabric's addon is handed a NeoForge payload. Matches
		// only the Fabric addon + the server config listener, so it is inert until those classes load.
		chain.register(TransformPhase.COREMOD, new CommonNetworkInteropInjector());

		if (Boolean.getBoolean("forbric.kernel.registryRedirect")) {
			chain.register(TransformPhase.COREMOD, new RegistryHookRedirector());
			ForbricLog.info("[Forbric/Boot] registry-wrapper redirect ENABLED (experimental)");
		}

		MethodBodyNeuter neuter = new MethodBodyNeuter()
				.add(new MethodBodyNeuter.Target("net.neoforged.neoforge.server.ServerLifecycleHooks",
						"runModifiers", "(Lnet/minecraft/server/MinecraftServer;)V",
						"NeoForge biome/structure modifiers need neoforge:biome_modifier datapack registry"))
				.add(new MethodBodyNeuter.Target("net.minecraft.world.level.levelgen.feature.MonsterRoomFeature",
						"place", "(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
						"NeoForge MONSTER_ROOM_MOBS datamap not yet loaded"))
				.add(new MethodBodyNeuter.Target("net.minecraftforge.fluids.FluidInteractionRegistry",
						"canInteract", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
						"MinecraftForge fluid-interaction hook calls its own getFluidType() (net.minecraftforge FluidType) "
						+ "but the merged Fluid implements only NeoForge's IFluidExtension (getFluidType returns the "
						+ "neoforged FluidType) → AbstractMethodError on WaterFluid.getFluidType during worldgen fluid "
						+ "ticking. Return false so vanilla fluid behavior proceeds (Forge/Neo FluidType ABI split)"));
		addSideNeuters(side, neuter);
		chain.register(TransformPhase.COREMOD, neuter);

		TransformContext ctx = new TransformContext(side.envType, false, "named");
		loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx));

		Thread.currentThread().setContextClassLoader(loader);

		KernelLifecycle.bind(loader);
		KernelHudBridge.bind(loader);
		KernelLifecycle.setModJars(modJars);
		KernelLifecycle.setRuntimeJars(runtimeJars);
		KernelFabricEcosystem.bindGameLoader(loader);

		// The FML loader IDENTITY must exist before Mixin starts, not with the rest of the seeding below.
		//
		// A mixin config may declare an IMixinConfigPlugin, and Mixin instantiates every plugin during select() —
		// which fires on the FIRST game class load. That is always earlier than PassiveSeeder.seedAll, and seedAll
		// itself loads game classes, so the dependency is circular: seeding FMLLoader triggers select(), and
		// ferritecore's plugin needs FMLPaths.CONFIGDIR and FMLLoader.getCurrent() in its <clinit>. Unseeded it died
		// on "Cannot invoke Path.resolve because FMLPaths.get() is null", then on "There is no current FML Loader",
		// each time inside a class definition, which took the whole boot with it.
		//
		// Seeding here is safe precisely because the loader has no mixin transformer yet, so these loads cannot
		// recurse into select(). The cost is that these few classes are never weavable — measured and acceptable:
		// across every mod jar in the gates and the client, the only net/neoforged/fml class any guest mixin so much
		// as names is ImmediateWindowHandler, which is not on this path. seedAll repeats both calls; both are
		// idempotent.
		PassiveSeeder.seedNeoForgePaths(loader, gameDir);
		// The mods dir is passed explicitly (not re-derived inside the seeder) because the LoadingModList seeded here
		// must describe the SAME jars this boot decided to load — see discoverForgeFamilyModJars above, which walks
		// exactly this directory. Two independent derivations of "where the mods are" is how they drift apart.
		PassiveSeeder.seedNeoForgeLoader(loader, gameDir, gameDir.resolve("mods"), side == Side.SERVER,
				side == Side.CLIENT);

		// Mixin LAST in the pipeline but FIRST in time: installed before anything defines a targeted class.
		//
		// Fabric first, Forge-family APPENDED. Within one environment Mixin selects by priority (the config's, then
		// each @Mixin's); registration order is only the tiebreak among equal priorities, where a later-registered
		// mixin applies AFTER an earlier one on the same target. Appending therefore leaves every existing
		// Fabric-vs-Fabric ordering byte-identical — so gate-m2b cannot move for ordering reasons — and makes the
		// newly-introduced, least-proven set the OUTER wrapper around a known-good stack rather than the inner one.
		List<String> fabricConfigs = KernelFabricEcosystem.mixinConfigs();
		List<String> forgeConfigs = KernelForgeFamilyMixins.select(forgeMixinDecls);
		List<String> mixinConfigs = new ArrayList<>(fabricConfigs);
		for (String config : forgeConfigs) {
			if (!mixinConfigs.contains(config)) mixinConfigs.add(config);
		}
		if (!forgeConfigs.isEmpty() || !forgeMixinDecls.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] mixin configs: %d Fabric + %d Forge-family (%d NeoForge, %d "
					+ "MinecraftForge) — %s", fabricConfigs.size(), forgeConfigs.size(),
					KernelForgeFamilyMixins.count(forgeMixinDecls, forgeConfigs, ModEcosystem.NEOFORGE),
					KernelForgeFamilyMixins.count(forgeMixinDecls, forgeConfigs, ModEcosystem.FORGE),
					forgeConfigs.isEmpty() ? "none kept" : String.join(", ", forgeConfigs));
		}
		KernelMixinBootstrap.init(loader, side.envType, mixinConfigs);

		// Seed the minimum genuine-loader identity the merged base's patched <clinit>s read (no lifecycle). The
		// Dist must match the side — a client seeded as DEDICATED_SERVER makes NeoForge reject the local player's
		// integrated-server connection ("Server is still starting").
		PassiveSeeder.seedAll(loader, gameDir, side == Side.SERVER, side == Side.CLIENT);

		// Fabric preLaunch entrypoints, after Mixin is up and before any game class loads (their contract).
		KernelFabricEcosystem.runPreLaunch();

		Class<?> mainClass = Class.forName(side.entryClass, false, loader);
		if (lifecycleHook.missedRequiredExcision()) {
			throw new IllegalStateException("kernel refusing to boot: the genuine " + side.name().toLowerCase()
					+ "-loading lifecycle trigger was NOT redirected from the merged base (its shape changed). "
					+ "See LifecycleHookInjector.");
		}
		ForbricLog.info("[Forbric/Boot] merged base %s entry loaded through kernel loader; lifecycle redirected to "
				+ "the kernel — handing to vanilla boot", side.name().toLowerCase());

		Method main = mainClass.getMethod("main", String[].class);
		main.invoke(null, (Object) gameArgs.toArray(new String[0]));
	}

	/**
	 * Side-specific transform concessions. On the client the injector redirects the {@code ClientModLoader.begin()}
	 * CALL SITE in {@code Main.main} to the kernel's {@code onClientModLoading} hook (so begin's own body is never
	 * invoked from there); the OTHER genuine client mod-loading calls made later in {@code Minecraft.<init>}
	 * ({@code finish} / {@code completeModLoading} / {@code setupModResourcePacks}) are stubbed so the genuine client
	 * loader does not run alongside the kernel's native registration.
	 */
	private static void addSideNeuters(Side side, MethodBodyNeuter neuter) {
		if (side != Side.CLIENT) return;

		// Leaving a world, Minecraft.disconnect calls NeoForge's RegistryManager.revertToFrozen — the client-only
		// undo of server-synced registry ids back to a "frozen" snapshot. It does not survive the kernel's native
		// registration: GameData.freezeData throws "already frozen" before its takeFrozenSnapshot tail runs, so
		// frozenSnapshot stays null → applySnapshot(null) NPEs on the forEach; and even with the snapshot forced,
		// applySnapshot's registerIdMapping hits a null Holder because the kernel-managed registry state does not
		// round-trip through NeoForge's snapshot format. The kernel OWNS the registry lifecycle (it replaces this
		// whole freeze/snapshot/revert machinery), so the revert is both unnecessary and unsound here — stub it, like
		// the other NeoForge lifecycle hooks the kernel replaces. Client-only: no dedicated-server path calls it.
		neuter.add(new MethodBodyNeuter.Target("net.neoforged.neoforge.registries.RegistryManager", "revertToFrozen",
				"()V", "kernel owns the registry freeze/revert lifecycle; NeoForge's snapshot revert does not round-trip "
				+ "against kernel-managed registries (disconnect-time NPE)"));

		for (String owner : new String[] {
				"net.neoforged.neoforge.client.loading.ClientModLoader",
				"net.minecraftforge.client.loading.ClientModLoader"}) {
			// begin() is NOT neutered: its call at Main.main bc 814 is the redirect target (→ onClientModLoading), so
			// its genuine body is never reached from there. Neutering it instead defers registration to a point never
			// reached and hangs the boot (empirically). The LATER client mod-loading calls in Minecraft.<init> are the
			// ones to stub — they would run the FancyModLoader lifecycle the kernel replaces.
			neuter.add(new MethodBodyNeuter.Target(owner, "finish", "()V",
					"kernel owns client mod loading (registration in onClientModLoading)"));
			neuter.add(new MethodBodyNeuter.Target(owner, "completeModLoading", "()Z",
					"kernel owns client mod loading"));
			// setupModResourcePacks is NOT neutered: ClientPackHookInjector redirects its body to
			// KernelLifecycle.onClientResourcePacks, so the genuine loader's resource integration still never runs, but
			// the kernel gets the live PackRepository at the one correctly-timed point (Minecraft.<init>, pre-reload)
			// and serves the ecosystem jars' assets itself. Neutering it threw that handle away.
		}
	}

	/** The game version, read from the base jar's {@code version.json} (vanilla ships it at the jar root). */
	private static String detectGameVersion(Path gameJar) {
		if (gameJar == null || !Files.isRegularFile(gameJar)) return FALLBACK_GAME_VERSION;

		try (java.util.jar.JarFile jar = new java.util.jar.JarFile(gameJar.toFile())) {
			java.util.zip.ZipEntry entry = jar.getEntry("version.json");
			if (entry == null) return FALLBACK_GAME_VERSION;

			try (java.io.InputStream in = jar.getInputStream(entry);
					java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
				var json = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(reader);
				Object name = json.get(java.util.List.of("name"));
				Object id = json.get(java.util.List.of("id"));
				Object value = name != null ? name : id;
				if (value != null) return value.toString();
			}
		} catch (Exception e) {
			ForbricLog.debug("[Forbric/Boot] could not read version.json from %s: %s", gameJar.getFileName(),
					String.valueOf(e));
		}

		return FALLBACK_GAME_VERSION;
	}

	/**
	 * Which loader family each owned mod jar probes as, for {@link LoaderProbePolicy}.
	 *
	 * <p>The answer is whatever {@link MultiLoaderArbiter} already decided. A jar declaring one manifest is owned by
	 * that loader; a universal jar declaring several was arbitrated to exactly one, and its probes must agree with
	 * that decision — the whole point of arbitration is that the jar behaves as ONE mod, and a universal jar that
	 * still sees every loader defeats it. LambDynamicLights is the case that showed why: arbitrated to NeoForge, its
	 * {@code yumi-mc-foundation} still detected Fabric as well, built both runtimes, took the first, and looked its
	 * own mod up through a loader it had been suppressed on. That failure surfaced twice over — first as
	 * {@code NoSuchElementException: No value present} killing its mixin config plugin, then as a permanent red
	 * "Dev Version (Unsupported)" banner across the screen, because the version string it fell back to is the one
	 * that decides {@code isDevMode()}.
	 *
	 * <p>A jar declaring no loader manifest at all is a plain library: {@code ownerOf} returns {@code null} and it
	 * stays unowned, along with the merged base, the runtime carriers and the MC libraries.
	 */
	private static Map<Path, LoaderProbePolicy.Family> probeFamilies(List<Path> fabricJars, List<Path> modJars) {
		Map<Path, LoaderProbePolicy.Family> families = new java.util.LinkedHashMap<>();

		for (List<Path> group : List.of(fabricJars, modJars)) {
			for (Path jar : group) {
				if (families.containsKey(jar)) continue;

				MultiLoaderArbiter.Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
				if (owner == null) continue;

				families.put(jar, owner == MultiLoaderArbiter.Ecosystem.FABRIC
						? LoaderProbePolicy.Family.FABRIC
						: LoaderProbePolicy.Family.FORGE_FAMILY);
			}
		}
		return families;
	}

	/** Splits a {@code --libraryPath} classpath string into the jars that exist. Empty when not given. */
	private static List<Path> libraryJars(String libraryPath) {
		List<Path> jars = new ArrayList<>();
		if (libraryPath == null || libraryPath.isBlank()) return jars;

		for (String entry : libraryPath.split(File.pathSeparator)) {
			if (entry.isBlank()) continue;
			Path jar = new File(entry).toPath();
			if (Files.isRegularFile(jar)) jars.add(jar);
		}

		return jars;
	}

	/** Every {@code *.jar} in {@code modsDir} that declares a Forge/NeoForge mod (has a mods.toml). Sorted, stable. */
	/**
	 * Builds one {@link net.forbric.kernel.access.AccessTransformer} from every mod jar's
	 * {@code META-INF/accesstransformer*.cfg}, or null when no mod ships one.
	 *
	 * <p>A Forge/NeoForge mod declares the vanilla members it needs widened in that file, and the genuine loader
	 * applies it before the class is defined. The kernel already had the whole AT machinery (parser, directive
	 * model, monotonic widening transformer) for the Fabric side but nothing ever fed it the Forge-family files, so
	 * every mod that reaches for a private vanilla member died at runtime — AppleSkin's SyncHandler with
	 * {@code IllegalAccessError: tried to access private field FoodData.exhaustionLevel} the moment its tick
	 * listener finally started firing. Architectury declares ~30 of these, balm one, so this is not a niche path.
	 *
	 * <p>Names are Mojmap and the merged base runs Mojmap, so the directives are used as parsed — no remap step.
	 * Best-effort per jar: one unreadable AT file must not stop the others.
	 */
	private static net.forbric.kernel.access.AccessTransformer forgeFamilyAccessTransformer(List<Path> modJars) {
		List<net.forbric.kernel.access.AtDirective> directives = new ArrayList<>();
		int jarsWithAts = 0;

		for (Path jar : modJars) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
				boolean any = false;
				for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries(); e.hasMoreElements();) {
					java.util.zip.ZipEntry entry = e.nextElement();
					String name = entry.getName();
					if (!name.startsWith("META-INF/") || !name.endsWith(".cfg")) continue;
					if (!name.substring("META-INF/".length()).startsWith("accesstransformer")) continue;

					try (java.io.Reader r = new java.io.InputStreamReader(zip.getInputStream(entry),
							java.nio.charset.StandardCharsets.UTF_8)) {
						List<net.forbric.kernel.access.AtDirective> parsed =
								net.forbric.kernel.access.AccessTransformerParser.parse(r);
						directives.addAll(parsed);
						any = true;
						ForbricLog.debug("[Forbric/AT] %s: %d directive(s) from %s", jar.getFileName(), parsed.size(),
								name);
					}
				}
				if (any) jarsWithAts++;
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/AT] could not read access transformers from " + jar.getFileName(), t);
			}
		}

		if (directives.isEmpty()) return null;

		ForbricLog.info("[Forbric/AT] applying %d Forge-family access-transformer directive(s) from %d mod jar(s) "
				+ "— without these a mod touching a private vanilla member dies with IllegalAccessError",
				directives.size(), jarsWithAts);
		return new net.forbric.kernel.access.AccessTransformer(directives);
	}

	/**
	 * Extracts the JarJar (JiJ) children a Forge/NeoForge mod nests at {@code META-INF/jarjar/} and returns them so
	 * they join the class loader — the Forge-family counterpart of the Fabric side's nested-jar handling.
	 *
	 * <p>Both Forge families ship a mod's required libraries INSIDE the mod jar rather than as separate downloads,
	 * and nothing puts those on the classpath by itself. Without this, such a mod constructs straight into
	 * {@code NoClassDefFoundError} on its own dependency and is skipped — Mob Champions nests
	 * {@code whitenoise-26.2-neoforge-2.2.1.jar} and died on
	 * {@code technology/roughness/whitenoise/util/ResourceLocationHelper}. The mod looks broken while the real
	 * cause is a loader gap, and the library is not separately downloadable, so there is no way around it.
	 *
	 * <p>Deduplicated by nested file name, keeping the FIRST occurrence — an approximation of Forge's JarJarSelector
	 * version range resolution that is sufficient while nothing here ships two versions of one library. Bytecode is
	 * never remapped: on MC 26.2 nested jars are Mojmap already, exactly like their host.
	 */
	private static List<Path> extractForgeFamilyJarJar(List<Path> modJars, Path gameDir) {
		Path outDir = gameDir.resolve(".forbric-kernel").resolve("jarjar");
		List<Path> extracted = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();

		for (Path modJar : modJars) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(modJar.toFile())) {
				for (var entries = zip.entries(); entries.hasMoreElements();) {
					java.util.zip.ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					if (entry.isDirectory() || !name.startsWith("META-INF/jarjar/") || !name.endsWith(".jar")) {
						continue;
					}

					String simple = name.substring(name.lastIndexOf('/') + 1);
					if (!seen.add(simple)) continue;

					Path target = outDir.resolve(simple);
					Files.createDirectories(outDir);
					try (java.io.InputStream in = zip.getInputStream(entry)) {
						Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
					extracted.add(target);
					ForbricLog.info("[Forbric/Boot] extracted nested JarJar library %s from %s", simple,
							modJar.getFileName());
				}
			} catch (Exception e) {
				ForbricLog.warn("[Forbric/Boot] could not read JarJar children of %s: %s", modJar.getFileName(),
						String.valueOf(e));
			}
		}

		return extracted;
	}

	/**
	 * The Forge-family half of discovery: the jars the kernel must own, AND every mixin config they declare.
	 *
	 * <p>The configs used to be computed here and thrown away — this method collapsed the {@code DiscoveredMod} list
	 * to one boolean. Nothing else on the boot path ever reads Forge-family metadata again (the {@code @Mod} pass is
	 * a separate ASM scan that never opens a manifest), so that was the only place they could be captured.
	 */
	private record ForgeFamilyMods(List<Path> jars, List<KernelForgeFamilyMixins.ForgeMixinConfig> mixinConfigs) {
	}

	/** The mixin configs declared by JarJar-extracted nested jars. Same pass, applied to the children. */
	private static List<KernelForgeFamilyMixins.ForgeMixinConfig> discoverNestedForgeMixinConfigs(List<Path> nested) {
		List<KernelForgeFamilyMixins.ForgeMixinConfig> configs = new ArrayList<>();
		if (nested.isEmpty()) return configs;
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Path> ignored = new ArrayList<>();
		for (Path jar : nested) {
			try {
				collectForgeFamily(discoverer, jar, ignored, configs);
			} catch (IOException e) {
				ForbricLog.warn("could not inspect nested mod jar %s: %s", jar.getFileName(), e.getMessage());
			}
		}
		return configs;
	}

	private static ForgeFamilyMods discoverForgeFamilyModJars(Path modsDir) {
		List<Path> jars = new ArrayList<>();
		List<KernelForgeFamilyMixins.ForgeMixinConfig> configs = new ArrayList<>();
		if (!Files.isDirectory(modsDir)) return new ForgeFamilyMods(jars, configs);
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		try (var entries = Files.list(modsDir)) {
			List<Path> candidates = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile).sorted().toList();
			for (Path jar : candidates) {
				try {
					collectForgeFamily(discoverer, jar, jars, configs);
				} catch (IOException e) {
					ForbricLog.warn("could not inspect mod jar %s: %s", jar.getFileName(), e.getMessage());
				}
			}
		} catch (IOException e) {
			ForbricLog.warn("could not list mods dir %s: %s", modsDir, e.getMessage());
		}
		return new ForgeFamilyMods(jars, configs);
	}

	/**
	 * Records {@code jar} as Forge-family (if it is) and appends the mixin configs it declares.
	 *
	 * <p>De-duplicated per jar: {@code ForgeMetadataMapper} copies one manifest's config list into EVERY
	 * {@code DiscoveredMod} that manifest declares, so a toml with three {@code [[mods]]} yields the same list three
	 * times. Cross-jar de-duplication and arbitration happen later, in {@link KernelForgeFamilyMixins}.
	 */
	private static void collectForgeFamily(ForbricModDiscoverer discoverer, Path jar, List<Path> jars,
			List<KernelForgeFamilyMixins.ForgeMixinConfig> configs) throws IOException {
		boolean forgeFamily = false;
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
			if (!mod.getEcosystem().isForgeFamily()) continue;
			forgeFamily = true;
			for (String config : mod.getMixinConfigs()) {
				if (seen.add(mod.getEcosystem() + " " + config)) {
					configs.add(new KernelForgeFamilyMixins.ForgeMixinConfig(config, jar, mod.getEcosystem()));
				}
			}
		}
		if (forgeFamily) jars.add(jar);
	}

	/**
	 * Returns {@code --gameDir <path>} (or the working dir if none), removing it from {@code gameArgs} in place
	 * only when {@code strip} is set — the dedicated server rejects {@code --gameDir}, the client requires it.
	 */
	private static Path extractGameDir(List<String> gameArgs, boolean strip) {
		Path dir = new File(System.getProperty("user.dir", ".")).toPath();
		for (int i = 0; i < gameArgs.size(); i++) {
			if (gameArgs.get(i).equals("--gameDir") && i + 1 < gameArgs.size()) {
				dir = new File(gameArgs.get(i + 1)).toPath();
				if (strip) {
					gameArgs.remove(i + 1);
					gameArgs.remove(i);
				}
				break;
			}
		}
		return dir;
	}

	private static String req(String[] args, int i, String flag) {
		if (i >= args.length) {
			System.err.println("forbric-kernel: " + flag + " requires a value");
			System.exit(2);
		}
		return args[i];
	}
}
