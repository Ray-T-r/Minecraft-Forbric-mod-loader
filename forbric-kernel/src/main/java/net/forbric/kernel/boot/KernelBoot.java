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

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.api.ModPresence;
import net.forbric.kernel.access.ClassTweakerTransformer;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.fabric.FabricModDiscovery;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.EcosystemVersions;
import net.forbric.kernel.mixin.KernelMixinBootstrap;
import net.forbric.kernel.mixin.MixinConfigOwners;
import net.forbric.kernel.transform.ChunkExecutorGuardInjector;
import net.forbric.kernel.transform.ClientPackHookInjector;
import net.forbric.kernel.transform.ClientSmokeTickInjector;
import net.forbric.kernel.transform.CommonNetworkInteropInjector;
import net.forbric.kernel.transform.ForgeOverlayNeuterInjector;
import net.forbric.kernel.transform.SodiumConfigUserBridgeInjector;
import net.forbric.kernel.transform.DataPackHookInjector;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import net.forbric.kernel.transform.ExitHookInjector;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import net.forbric.kernel.transform.ForeignModPresenceInjector;
import net.forbric.kernel.transform.ForgeBindingsLookupInjector;
import net.forbric.kernel.transform.ForgeLoadingListHolderInjector;
import net.forbric.kernel.transform.GuestMixinPluginGuard;
import net.forbric.kernel.transform.HudElementBridgeInjector;
import net.forbric.kernel.transform.LifecycleHookInjector;
import net.forbric.kernel.transform.MergedBaseFrameRecomputer;
import net.forbric.kernel.transform.PortingLayerAbiInjector;
import net.forbric.kernel.transform.LoaderProbeRewriter;
import net.forbric.kernel.transform.MethodBodyNeuter;
import net.forbric.kernel.transform.NeoEnumExtensionInjector;
import net.forbric.kernel.transform.NullPackGuardInjector;
import net.forbric.kernel.transform.PackMetadataFailSoftInjector;
import net.forbric.kernel.transform.PackOverlayMutabilityInjector;
import net.forbric.kernel.transform.RegistryAliasParityInjector;
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
		SERVER(EnvType.SERVER, LifecycleHookInjector.SERVER_MAIN, true,
				"net.minecraft.server.dedicated.DedicatedServer"),
		CLIENT(EnvType.CLIENT, LifecycleHookInjector.CLIENT_MAIN, false,
				"net.minecraft.client.gui.screens.TitleScreen");

		final EnvType envType;
		final String entryClass;
		/**
		 * The class whose loading means "far enough along that the anchor census is worth reading".
		 *
		 * <p>Server: vanilla's own Main builds the PackRepository and the WorldStem BEFORE constructing this, so
		 * every server-side target the kernel cares about has already been through the chain. ExitHookInjector
		 * already treats this class as the server's end-of-life owner.
		 *
		 * <p>Client: the title screen is the moment the player starts looking, and by then Minecraft, Options,
		 * ClientModLoader, PackRepository, Pack, ModList and GuiLayerManager have all been defined.
		 * KernelClientSmoke already resolves exactly this class as its "we are up" landmark.
		 */
		final String censusLandmark;
		/** The dedicated server rejects {@code --gameDir}; the client accepts it. */
		final boolean stripGameDir;

		Side(EnvType envType, String entryClass, boolean stripGameDir, String censusLandmark) {
			this.censusLandmark = censusLandmark;
			this.envType = envType;
			this.entryClass = entryClass;
			this.stripGameDir = stripGameDir;
		}

		LifecycleHookInjector injector() {
			return this == SERVER ? LifecycleHookInjector.forServer() : LifecycleHookInjector.forClient();
		}

		/**
		 * The neutral spelling of this side.
		 *
		 * <p>This enum is the BOOT side: it also carries the entry class and the arg-stripping rule, neither of
		 * which means anything to the ecosystems. {@link net.forbric.api.Side} is what crosses into them.
		 */
		public net.forbric.api.Side api() {
			return this == SERVER ? net.forbric.api.Side.DEDICATED_SERVER : net.forbric.api.Side.CLIENT;
		}
	}

	/**
	 * Runs the shared boot for {@code side}. {@code args} are the raw process args:
	 * {@code --gameJar}/{@code --runtimeJar}/{@code --libraryPath} are consumed here ({@code --runtimeJar} takes
	 * either one jar or several joined by the platform path separator); everything after {@code --}
	 * (and any unrecognized token) is forwarded to the game's {@code Main.main}.
	 */
	public static void launch(Side side, String[] args) throws Throwable {
		net.forbric.api.CompatibilityFindings.reset();
		net.forbric.kernel.ui.CompatibilityDecision.reset();
		net.forbric.kernel.mixin.MixinCompatibility.reset();
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
					//
					// One flag may carry several jars, separated the way a classpath is. Repeating the flag still works
					// (every launch script in run/ does), but an installed profile must not depend on it: a launcher is
					// free to read game arguments as a flag-to-value map and keep only the last occurrence, which drops a
					// whole ecosystem's runtime and takes the game down on the first class that ecosystem owns.
					for (String entry : req(args, ++i, a).split(File.pathSeparator)) {
						if (entry.isBlank()) continue;
						Path jar = new File(entry).toPath();
						if (runtimeJars.contains(jar)) continue;
						runtimeJars.add(jar);
						owned.add(jar.toUri().toURL());
					}
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

		// Two separate jars can declare the SAME mod id — inevitable the moment a Fabric pack and a NeoForge pack
		// are merged. MultiLoaderArbiter cannot see that (it is keyed by jar path), and left alone both jars enter
		// `owned` and shadow each other class-for-class, contribute each other's mixin configs, and register the
		// same content twice. Decide once here; both discoveries below skip the losers.
		// Pre-scan every declared nested candidate before either discovery discards a root. The later
		// arbitrateNested call verifies physical files against this same decision; it does not choose again.
		DuplicateModArbiter.Decision topLevelDupes =
				DuplicateModArbiter.arbitrate(gameDir.resolve("mods"), side.envType);

		// Forge/NeoForge mod jars (Mojmap-compiled like the merged base → load directly, no remap), plus the
		// libraries they nest at META-INF/jarjar/ — see extractForgeFamilyJarJar.
		// Learn what each carrier says its own version is BEFORE discovery reads the mods, so a mod whose
		// versionRange this instance cannot satisfy says so as it is discovered rather than failing later.
		EcosystemVersions.record(runtimeJars);
		ForgeFamilyMods forgeFamily = discoverForgeFamilyModJars(gameDir.resolve("mods"), topLevelDupes);

		// Presence, not loading. Every ecosystem keeps its own mod list, so a mod asking its own loader whether some
		// OTHER family's mod is installed is told no — and that answer is usually a compatibility branch, not a
		// display string. Published here, before the Fabric ecosystem is built, because that build reads it back.
		try {
			ModPresence.publishForgeFamily(PassiveSeeder.arbitratedForgeFamilyMods(gameDir.resolve("mods")));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Presence] could not list the Forge-family mods for cross-ecosystem presence — a "
					+ "Fabric mod asking whether one of them is installed will be told no: %s", String.valueOf(t));
		}
		List<Path> modJars = new ArrayList<>(forgeFamily.jars());
		// The pre-scan has already selected every root and nested candidate. Consume those exact files;
		// the legacy extractor is only for the explicit arbitration-off mode.
		NestedCandidatePlan candidatePlan = DuplicateModArbiter.currentPlan();
		List<Path> nested = candidatePlan == null ? extractForgeFamilyJarJar(modJars, gameDir)
				: candidatePlan.nestedFiles();

		// Fabric discovery consumes the same preselected physical files and registers nothing yet. The union
		// below is checked against the plan before either loader builds containers or adds losing jars to the
		// classpath. When arbitration is explicitly disabled, both original discovery paths remain available.
		FabricModDiscovery fabricScan = KernelFabricEcosystem.scan(side.envType, gameDir, topLevelDupes);
		List<Path> allNested = new ArrayList<>(nested);
		for (Path jar : fabricScan.getClasspathJars()) {
			if (!modJars.contains(jar) && !nested.contains(jar)) allNested.add(jar);
		}
		final DuplicateModArbiter.Decision dupes = DuplicateModArbiter.arbitrateNested(side.envType, allNested);
		nested = nested.stream().filter(jar -> !dupes.suppressed(jar)).toList();

		nestedJarJarJars = List.copyOf(nested);
		modJars.addAll(nested);

		// THE MC LIBRARIES GO IN AHEAD OF THE MODS, and the order is the whole policy.
		//
		// They are owned rather than merely parent-visible because mods mixin into them
		// (fabric-dimension-api-v1 targets DataFixerUpper's TaggedChoice), and they sit AFTER the merged base and
		// the carriers so nothing shadows those. What changed is that they used to sit after the MOD jars too, and
		// URLClassLoader answers from the first URL that HAS the class — so a mod jar that bundles a copy of a
		// library the game already has WON.
		//
		// That is not hypothetical and it is not a degradation. PlayerDataSyncReloaded ships 226
		// com.google.gson.* classes at the UNSHADED package name, gson 2.10.1, against the 2.14.0 Minecraft 26.2
		// itself uses; its copy won, and the game died in SharedConstants.tryDetectVersion with
		// NoSuchMethodError JsonReader.setStrictness — reading version.json, before a single mod had loaded. The
		// same shape had already been paid for once as a hand-written pin: DelegationPolicy's NightConfig entry
		// exists because a CARRIER bundles an unshaded old copy.
		//
		// Ordering is the general form of that pin and needs no list. A class present only in a mod jar is
		// unaffected, because the library jars do not have it; a class present in BOTH now comes from the copy
		// the merged base was compiled against. That is also what the genuine loaders do — Knot and FML put
		// Minecraft's libraries on the same loader ahead of mods — so a mod relying on winning here was relying on
		// something that does not hold on its own platform either.
		List<Path> minecraftLibraries = libraryJars(libraryPath);
		int libCount = minecraftLibraries.size();

		// A nested mod's mixins are the same defect one level down. These jars already get everything else a
		// top-level mod gets — they are owned, and KernelModLoader scans them for @Mod, which is how whitenoise
		// (inside Mob Champions) is constructed — so leaving their configs out would be arbitrary. Pure libraries
		// declare none and cost one manifest read.
		List<KernelForgeFamilyMixins.ForgeMixinConfig> forgeMixinDecls = new ArrayList<>(forgeFamily.mixinConfigs());
		forgeMixinDecls.addAll(discoverNestedForgeMixinConfigs(nested));

		// Fabric mods (+ extracted JiJ children). Also Mojmap on this game version. Creates the FabricLoader.
		List<Path> fabricJars = KernelFabricEcosystem.build(fabricScan, side.envType, gameDir, gameVersion,
				gameArgs.toArray(new String[0]), dupes, gameJar);

		// Game-side bundled libraries (MixinExtras) and the kernel's own runtime jar. The latter also carries
		// the kernel's client assets -- the Mods button's icon lives in it -- so its extracted path is handed to
		// the lifecycle for the client pack repository as well as to the class loader.
		List<Path> bundled = KernelBundledJars.extract(gameDir);
		owned = KernelOwnedClasspath.compose(owned, minecraftLibraries, modJars, fabricJars, bundled);
		KernelLifecycle.setKernelAssetJars(bundled);
		if (!KernelOwnedClasspath.bundledFirst()) {
			ForbricLog.warn("[Forbric/Boot] -D%s=off — guest jars precede kernel-supplied game libraries",
					KernelOwnedClasspath.SWITCH);
		}

		// A Fabric mod shipping its own net.neoforged.* / net.minecraftforge.* loses those classes to the carrier
		// by design. Say so, and say where the two disagree, while the jar names are still in hand — the failure
		// otherwise surfaces in whichever dependent first calls the API, several lifecycle steps later.
		List<Path> shadowCandidates = new ArrayList<>(fabricJars);
		for (Path jar : modJars) if (!shadowCandidates.contains(jar)) shadowCandidates.add(jar);
		PortingLayerAudit.report(shadowCandidates, runtimeJars);
		// Which installed mods name a fabric-api surface the merged base still switches off; reported after the
		// catalog is published, so the rows reach load-report.txt.
		FabricApiModuleLossAudit.scan(shadowCandidates);
		// Which installed jars read a vanilla field with a descriptor the merge no longer declares (NoSuchFieldError
		// at that access); reported after the catalog is published, so the rows reach load-report.txt.
		FieldDriftAudit.scan(shadowCandidates);
		// Which installed jars name a Forge-family class that exists in no carrier, not the merged base and no
		// installed jar (compiled against another NeoForge/MinecraftForge); reported after the catalog is published.
		List<Path> abiUniverse = new ArrayList<>(runtimeJars);
		if (gameJar != null) abiUniverse.add(gameJar);
		AbiLinkAudit.scan(shadowCandidates, abiUniverse);

		ForbricLog.info("[Forbric/Boot] sovereign kernel — %s %s, %d owned jar(s), %d Forge-family mod(s), "
				+ "%d Fabric jar(s), %d MC library jar(s)", side.name().toLowerCase(), gameVersion, owned.size(),
				modJars.size(), fabricJars.size(), libCount);

		ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(new URL[0]),
				KernelBoot.class.getClassLoader());
		// The jars cross-jar arbitration superseded, as a LAST RESORT only — a mod built against the other side's
		// platform-only class would otherwise get a bare NoClassDefFoundError. See ForbricClassLoader.setRescueJars
		// for why this cannot shadow the winner, and for what it deliberately does not fix.
		loader.setRescueJars(rescueUrls(dupes));

		// Every mod jar probes as the loader the arbiter gave it, so a mod cannot wander into a branch it never ran
		// on its own platform — and a universal jar answers as the ONE ecosystem it was arbitrated to. Plain
		// libraries declare no manifest and stay unowned. See LoaderProbePolicy.
		loader.setJarFamilies(probeFamilies(fabricJars, modJars));
		LoaderProbePolicy.bindGuestLoader(loader);

		// A mod that unpacks its real payload at preLaunch has no public API for adding it to the classpath and
		// reaches into Fabric's internals for it. Installed before any mod class loads. See KernelFabricLauncher.
		net.forbric.kernel.fabric.KernelFabricLauncher.install(loader, side.envType);

		TransformChain chain = new TransformChain();
		boolean transferInterop = KernelTransferInterop.configure(loader);

		// Fabric access wideners before Mixin (ACCESS phase): the weaver must see the widened members.
		ClassTweakerTransformer accessWideners =
				ClassTweakerTransformer.createFrom(KernelFabricEcosystem.accessWidenerFiles(), loader::putGeneratedClass);
		if (accessWideners != null) chain.register(TransformPhase.ACCESS, accessWideners);

		// The Forge-family twin: every mod jar's META-INF/accesstransformer.cfg, in the same ACCESS phase — and
		// the two runtime carriers' own, which the merged base needs just as much. See the method.
		net.forbric.kernel.access.AccessTransformer forgeAts =
				forgeFamilyAccessTransformer(modJars, runtimeJars);
		if (forgeAts != null) chain.register(TransformPhase.ACCESS, forgeAts);

		// A guest mod's platform probe answers for the loader that mod was loaded as. Registered first in the phase:
		// it rewrites only Class.forName call sites, so nothing later in the chain can be looking at what it edits.
		LoaderProbeRewriter loaderProbes = new LoaderProbeRewriter(loader::familyOfClass);
		if (LoaderProbePolicy.enabled()) chain.register(TransformPhase.COREMOD, loaderProbes);

		// One mod's mixin config plugin must not be able to abort config preparation for every other mod. Mixin
		// guards plugin construction but not the calls, and a throw there escapes select(). See GuestMixinPluginGuard.
		chain.register(TransformPhase.COREMOD, new GuestMixinPluginGuard());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricItemContractTransformer(name -> {
			try (var in = loader.getGameResourceAsStream(name + ".class")) { return in != null; }
			catch (java.io.IOException unavailable) { return false; }
		}));
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricSoundContractTransformer(name -> {
			try (var in = loader.getGameResourceAsStream(name + ".class")) { return in != null; }
			catch (java.io.IOException unavailable) { return false; }
		}));
		if (transferInterop) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.TransferTransactionHooks());
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.TransferCapabilityFallback());
		}

		LifecycleHookInjector lifecycleHook = side.injector();
		chain.register(TransformPhase.COREMOD, lifecycleHook);

		// Repairs class-local invariants the 3-ABI byte-merge breaks. It was written but never wired — without it the
		// merged base keeps divergent-pipeline lambda twins whose invokedynamic bootstrap handle disagrees with the
		// surviving lambda's static-ness (e.g. PrepareSpawnTask$Ready: a REF_invokeStatic handle on lambda$spawn$1
		// that merged in as an INSTANCE method, its static twin renamed lambda$spawn$2) → IncompatibleClassChangeError
		// when the player spawns. Also re-adds the MinecraftForge getFluidType() bridge the NeoForge-won Fluid classes
		// dropped (the Forge/Neo FluidType ABI split).
		// With a class resolver: one of its repairs has to read the superclass chain to tell a stub that bypasses
		// a real implementation from one that bypasses nothing. Reads a RESOURCE rather than loading a class, for
		// the same reason MergedBaseFrameRecomputer does — loading one here would define it before the chain that
		// is still being built can see it.
		// MinecraftForge's capability provider, composed into Entity/BlockEntity/Level the way Forge composes it into
		// LevelChunk. BEFORE the compat transformer: its bare-return invalidateCaps/reviveCaps stubs then stand down
		// on their own, and remain the fallback when this is switched off.
		boolean forgeCapabilities = net.forbric.kernel.transform.ForgeCapabilityCompositionTransformer.enabled();
		if (forgeCapabilities) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeCapabilityCompositionTransformer());
			if (transferInterop) chain.register(TransformPhase.COREMOD,
					new net.forbric.kernel.transform.ForgeTransferCapabilityFallback());
		} else {
			ForbricLog.warn("[Forbric/Capabilities] -D%s=off — MinecraftForge capabilities are not composed into the merged "
					+ "root types and ForgeCapabilities cannot initialise; storage, pipe and machine mods stay inert",
					net.forbric.kernel.transform.ForgeCapabilityCompositionTransformer.PROPERTY);
		}
		chain.register(TransformPhase.COREMOD, new ForbricMergedBaseCompatTransformer(path -> {
			try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException unreadable) {
				return null;
			}
		}));
		// Both sides: vanilla-descriptor twins beside the fields the merge re-typed (RangedBow/CrossbowAttackGoal.mob,
		// AttributeSupplier$Builder.builder), so a vanilla-compiled reader and fabric-object-builder's accessor bind.
		if (net.forbric.kernel.transform.WidenedFieldTwinInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.WidenedFieldTwinInjector());
		} else {
			ForbricLog.warn("[Forbric/WidenedFields] -D%s=off — vanilla-compiled readers of the re-typed fields get "
					+ "NoSuchFieldError and fabric-object-builder's attribute accessor cannot bind",
					net.forbric.kernel.transform.WidenedFieldTwinInjector.PROPERTY);
		}

		// Both sides: vanilla's tooltip component order copied to the head of ItemStack.addDetailsToTooltip from the
		// merge's own renamed body, so fabric-item-api-v1's bytecode scrape of it finds what it scrapes on Fabric.
		if (net.forbric.kernel.transform.TooltipOrderScrapeInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.TooltipOrderScrapeInjector());
		} else {
			ForbricLog.warn("[Forbric/TooltipOrder] -D%s=off — fabric-item-api's tooltip-order registry throws on first "
					+ "touch (\"Found no component types\")", net.forbric.kernel.transform.TooltipOrderScrapeInjector.PROPERTY);
		}

		// Client only: route RenderPipeline$Builder.buildSnippet through the vanilla-shaped 11-arg Snippet
		// constructor (NeoForge's stencil test carried by a kernel scope) so fabric-rendering-v1's
		// @WrapOperation(NEW Snippet) matches instead of being rejected whole. Matches two classes a dedicated
		// server never loads.
		if (net.forbric.kernel.transform.SnippetConstructorFunnel.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.SnippetConstructorFunnel(path -> {
				try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
					return in == null ? null : in.readAllBytes();
				} catch (java.io.IOException unreadable) {
					return null;
				}
			}));
		} else {
			ForbricLog.warn("[Forbric/SnippetFunnel] -D%s=off — fabric-rendering-v1's snippet wrap is rejected by Mixin "
					+ "again (constructor arity)", net.forbric.kernel.transform.SnippetConstructorFunnel.PROPERTY);
		}

		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeBlockTintInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeOptionsInjector());

		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeClientConsumersInjector());

		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeCreativeTabsInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeSpawnPlacementsInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeWorldModifierInjector());

		// Client only: hand the kernel the live PackRepository at the vanilla-woven
		// ClientModLoader.setupModResourcePacks call inside Minecraft.<init>, so it can serve the ecosystem jars'
		// assets. (Registered unconditionally — the transformer only matches the two ClientModLoader classes, which a
		// dedicated server never loads.)
		chain.register(TransformPhase.COREMOD, new ClientPackHookInjector());

		// NeoForge's packet splitter is a second encoder in the same pipeline as PacketEncoder, and Fabric binds
		// its packet context only around the latter. A Fabric codec that reads it from inside the splitter — as
		// Polymer's ingredient codec does for every recipe — got null and the recipe packet failed to encode.
		chain.register(TransformPhase.COREMOD,
				new net.forbric.kernel.transform.SplitterPacketContextInjector());

		// …and keep the packs it serves OUT of the player's resource-pack screen. Pack.isHidden survived the
		// merge; the screen-side filter that reads it did not.
		chain.register(TransformPhase.COREMOD,
				new net.forbric.kernel.transform.PackScreenHiddenFilterInjector());

		// The pause menu's mods button opened NeoForge's list, which is every mod NeoForge loaded and, on this
		// instance, a fraction of what is installed. It now opens the kernel's, which reads ModCatalog.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ModsButtonRedirector());

		// A Forge-family mod's own data/ reaches the server datapack repository ONLY through this hook: the kernel
		// leaves ModList.modFiles empty, so NeoForge's own mod-pack finder walks an empty list and adds nothing.
		chain.register(TransformPhase.COREMOD, new DataPackHookInjector());

		// …and the ids in that data only resolve if the Forge registry wrappers honour aliases, which their overrides
		// of fabric-api's mixin targets silently stopped them doing.
		chain.register(TransformPhase.COREMOD, new RegistryAliasParityInjector());

		// …and NeoForge's configuration-phase registry sync remaps a registry through MappedRegistry fields those same
		// wrappers never fill, so the first real client to connect was dropped with "Failed to sync registries from the
		// server: NullPointerException". The wrapper gets NeoForge's remap contract and Forge's own injectSnapshot
		// does the work.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.RegistrySyncParityInjector());

		// The kernel owns the single registry freeze, and NeoForge's lifecycle puts it at ClientModLoader.begin() —
		// before `new Minecraft(...)`, where Fabric's is after it. A guest mixin that waits for "the real freeze"
		// therefore wakes its mod's <clinit> while Minecraft.getInstance() is still null, and fabric-api's
		// key-mapping registry read that instance to reject registration that is too LATE. Flashback died there.
		chain.register(TransformPhase.COREMOD,
				new net.forbric.kernel.transform.EarlyKeyMappingRegistrationInjector());

		// A multiloader mod ships one pack.mcmeta carrying a section per loader, and on Forbric all three parsers are
		// live — so a Fabric-only build gets its neoforge:overlays section read by NeoForge's parser and throws on a
		// condition only a NeoForge build would have registered. Vanilla drops the ENTIRE pack for that. Registered
		// unconditionally: the datapack path runs on a dedicated server too, and that is where it crashed.
		chain.register(TransformPhase.COREMOD, new PackMetadataFailSoftInjector());

		// The other half of "a multiloader pack.mcmeta must not cost you the pack", and the one that costs a
		// WORLD: NeoForge's overlay-merge patch mutates a list fabric-api's PackMixin has just frozen, so
		// readPackMetadata returns null, and a mod that passes that null on takes PackRepository down with it.
		// Repair first, backstop second — KernelPackRepair says why both. Registered unconditionally: the pack
		// repository is built on a dedicated server too.
		chain.register(TransformPhase.COREMOD, new PackOverlayMutabilityInjector());
		chain.register(TransformPhase.COREMOD, new NullPackGuardInjector());

		// A merged method keeps ONE body but BOTH ecosystems' lambdas, and a mixin's `method = "lambda$x$0"`
		// carries no descriptor because javac never lets one class have two. Drop the orphaned half before Mixin
		// looks, or it binds to dead code and the injection silently does nothing.
		chain.register(TransformPhase.COREMOD, new DuplicateLambdaPruneInjector());

		// Inert unless -Dforbric.clientSmoke=true. It is what lets gate-m9 run a client unattended: enter a
		// world, live in it, disconnect and stop, so the gate waits for an outcome instead of a timeout.
		chain.register(TransformPhase.COREMOD, new ClientSmokeTickInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.CompatibilityPromptTickInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.PortalSpawnInjector());
		// After merged-base compatibility: upgrade its owner-only redirect with the proven spawn input.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.SpawnerFinalizeInjector());
		// The only performance measurement in the tree. Beside the smoke tick because it is the same shape:
		// one static call at the head of a tick, no mixin config, nothing new in the list a gate asserts on.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ServerTickSamplerInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ServerCompatibilityTickInjector());

		// The loader's own Minecraft.close mixin never applies under the kernel, so its stop of the two loaders'
		// config file-watchers (non-daemon executors once a config file changes) is injected here: on the client at
		// Minecraft.close, on the dedicated server at DedicatedServer.onServerExit, which has no System.exit behind it.
		chain.register(TransformPhase.COREMOD, new ExitHookInjector());

		// …and RESOLVE its target now, rather than at the moment it is called.
		//
		// The hook is spliced into Minecraft.close, so without this the first and only attempt to load
		// ClientShutdown happens while the game is shutting down. Measured on a real install: three
		// "Game shutdown / NoClassDefFoundError: net/forbric/kernel/interop/ClientShutdown" crash reports, from
		// sessions whose kernel jar had been REPLACED on disk while they were running (a developer redeploying
		// mid-session); a session started after the last write exited clean. The class was in both jars the whole
		// time — it simply was not loaded yet when the file underneath it changed.
		//
		// A jar swapped under a live JVM is one way to reach that. A jar on a network or removable volume is
		// another, and so is anything that closes the loader early. None of them should be able to turn a quit into
		// a crash report, and a class the shutdown path cannot do without has no business being resolved for the
		// first time during shutdown.
		try {
			Class.forName(ExitHookInjector.HOOK_OWNER.replace('/', '.'), true, KernelBoot.class.getClassLoader());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Boot] could not preload the exit hook — a quit will still work, but if the "
					+ "kernel jar becomes unreadable before then it will end as a crash report instead", t);
		}

		// MinecraftForge's Bindings resolves its service provider through FML's module layer, which the kernel does
		// not build — so every use of its config events (registering one, loading one on a world, syncing one to a
		// client) died in that class initializer.
		chain.register(TransformPhase.COREMOD, new ForgeBindingsLookupInjector());
		// FMLLoader's three ModLauncher-backed methods. The kernel replaces ModLauncher, so Launcher.INSTANCE is
		// null and all three NPE — getNameFunction most of all, because ObfuscationReflectionHelper goes through
		// it and mods call that from static initialisers, which turns one NPE into a permanently erroneous class.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForgeLauncherInfoInjector());
		// Each family's ModList.isLoaded can only see its own family's mods, and that answer is a compatibility
		// branch far more often than a display string — a wrong "no" disables an integration in silence.
		chain.register(TransformPhase.COREMOD, new ForeignModPresenceInjector());
		// A Fabric "porting layer" ships its own net.neoforged.* so Fabric mods can use that API; under Forbric the
		// carrier's copy wins, and the port's own compiled call sites then meet an API it was not built against.
		// PortingLayerAudit reports every such skew; this adapts the one that is fatal.
		chain.register(TransformPhase.COREMOD, new PortingLayerAbiInjector());
		// MinecraftForge builds its LoadingModList in a lazy holder that reads a field the genuine loader would have
		// filled. A class initializer is a ONE-SHOT with no exception table, so the first caller to arrive before the
		// kernel seeds that field NPE'd inside it and left the class permanently erroneous -- while the seeder, which
		// only ever touches the write side, went on logging success. Make the holder read the kernel's published list
		// instead, so WHEN it is first touched stops mattering. See PassiveSeeder.publishForgeLoadingList.
		chain.register(TransformPhase.COREMOD, new ForgeLoadingListHolderInjector());

		// Client only: NeoForge won Hud.extractRenderState, so the call sites fabric-rendering-v1's HudMixin anchors
		// on no longer exist — as METHOD REFERENCES in the layer manager they exist as no bytecode at all, so no
		// anchor resolution can reach them. Every Fabric mod's HUD element silently drew nothing. Matches only
		// GuiLayerManager, which a dedicated server never loads.
		chain.register(TransformPhase.COREMOD, new HudElementBridgeInjector());

		// Both sides: route ReloadableServerRegistries' two loot seams through the kernel so fabric-loot-api-v3's
		// LootTableEvents fire from NeoForge's own LootTableLoadEvent point — its mixin cannot fit the merged base.
		if (net.forbric.kernel.transform.LootTableEventBridgeInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.LootTableEventBridgeInjector());
		} else {
			ForbricLog.warn("[Forbric/LootBridge] -D%s=off — Fabric LootTableEvents.REPLACE/MODIFY/ALL_LOADED never fire; "
					+ "loot tables load exactly as NeoForge returns them",
					net.forbric.kernel.transform.LootTableEventBridgeInjector.PROPERTY);
		}

		// Client only: trim fabric-model-loading-api-v1's ModelManagerMixin to the injectors that fit the merged
		// ModelManager (NeoForge replaced CuboidModel.fromStream at one site), so ModelLoadingPlugins dispatch instead
		// of the whole mixin being pinned. Guest MIXIN classes pass through this chain via getPreMixinClassBytes.
		if (net.forbric.kernel.transform.GuestInjectorPruner.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.GuestInjectorPruner());
		} else {
			ForbricLog.warn("[Forbric/GuestInjectorPruner] -D%s=off — ModelManagerMixin is pinned whole again; Fabric "
					+ "ModelLoadingPlugins are registered and never called",
					net.forbric.kernel.transform.GuestInjectorPruner.PROPERTY);
		}

		// Client only: fire the Fabric client entrypoints from inside Minecraft.<init> (before Options), the window
		// Fabric uses — so a client entrypoint touching Minecraft.getInstance() (keymapping registration etc.) sees a
		// live instance. Matches only Minecraft.<init>, which a dedicated server never loads.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ClientEntrypointHookInjector());

		// Client only: the NeoForge half of the same window, a few instructions later — after Minecraft.options is
		// assigned. The two ecosystems need opposite states (Fabric: options still null; NeoForge: options present),
		// so they cannot share one anchor. Matches only Minecraft.<init>, which a dedicated server never loads.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.NeoClientSetupHookInjector());

		// Arbitrate the c:version / c:register common-networking channel that Fabric and NeoForge both claim — without
		// it a tri-in-one client is kicked "invalid packet" when Fabric's addon is handed a NeoForge payload. Matches
		// only the Fabric addon + the server config listener, so it is inert until those classes load.
		// -Dforbric.commonNetworkInterop=off is how the two halves of this shim get told apart. Both are needed on a
		// tri-in-one instance and they fail in opposite directions, so a single switch that removes both is the only
		// honest way to ask "is the arbitration the cause?" of a networking symptom.
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.commonNetworkInterop", "on"))) {
			chain.register(TransformPhase.COREMOD, new CommonNetworkInteropInjector());
			chain.register(TransformPhase.COREMOD, new SodiumConfigUserBridgeInjector());
			chain.register(TransformPhase.COREMOD, new ForgeOverlayNeuterInjector());
		} else {
			ForbricLog.warn("[Forbric/Net] common-networking arbitration DISABLED — a tri-in-one client will be "
					+ "kicked \"invalid packet\" when Fabric's addon is handed a NeoForge payload");
		}

		// Hardening, on its own switch because it is not a repair: without it the game is exactly vanilla, and
		// only a mod holding a ServerLevel from a stopped integrated server can tell the difference. -off is the
		// honest way to ask "is the guard the cause?" of any chunk-scheduling symptom.
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.chunkExecutorGuard", "on"))) {
			chain.register(TransformPhase.COREMOD, new ChunkExecutorGuardInjector());
		} else {
			ForbricLog.warn("[Forbric/ChunkGuard] -Dforbric.chunkExecutorGuard=off — chunk work offered to a "
					+ "stopped server's executor will park its caller forever instead of being refused");
		}

		if (Boolean.getBoolean("forbric.kernel.registryRedirect")) {
			chain.register(TransformPhase.COREMOD, new RegistryHookRedirector());
			ForbricLog.info("[Forbric/Boot] registry-wrapper redirect ENABLED (experimental)");
		}

		// Two targets used to sit above this one and no longer do, because their reasons stopped being true:
		//   NeoForge ServerLifecycleHooks.runModifiers — "needs neoforge:biome_modifier datapack registry". The
		//     kernel declares it now, and the pass is guarded at its call site instead
		//     (guardNeoForgesWorldModifierPass), so a failure costs the modifiers rather than the boot.
		//   MonsterRoomFeature.place — "MONSTER_ROOM_MOBS datamap not yet loaded". Neutering the whole feature
		//     meant no dungeon, no spawner and no dungeon chest in EVERY world, for everyone, mods or no mods.
		//     KernelNeoWorldgen loads the data maps for real and the mob pick falls back to vanilla's own set.
		// A neuter is a promise that the method cannot work here; both promises had expired.
		MethodBodyNeuter neuter = new MethodBodyNeuter()
				.add(new MethodBodyNeuter.Target("net.minecraftforge.fluids.FluidInteractionRegistry",
						"canInteract", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
						"MinecraftForge fluid-interaction hook calls its own getFluidType() (net.minecraftforge FluidType) "
						+ "but the merged Fluid implements only NeoForge's IFluidExtension (getFluidType returns the "
						+ "neoforged FluidType) → AbstractMethodError on WaterFluid.getFluidType during worldgen fluid "
						+ "ticking. Return false so vanilla fluid behavior proceeds (Forge/Neo FluidType ABI split)"));
		addSideNeuters(side, neuter);
		chain.register(TransformPhase.COREMOD, neuter);

		// A NeoForge mod adds constants to vanilla enums by declaring them in META-INF/enumextensions.json; FML
		// rewrites the enum's <clinit> and $VALUES at load. Nothing did that here, so Sophisticated Backpacks' model
		// loader hit "No enum constant ItemDisplayContext.SOPHISTICATEDBACKPACKS_WORN" mid resource-reload and took
		// the client down. Load the declarations, then let NeoForge's own RuntimeEnumExtender do the rewrite.
		//
		// LAST in the phase, and that placement is load-bearing rather than stylistic: loadEnumPrototypes resolves
		// FML classes (RuntimeEnumExtender, EnumPrototype, ModLoadingIssue, the IModInfo chain), so wherever this
		// call sits, every class it touches is DEFINED at that point — with only the transformers registered so far.
		// Sitting it mid-list, ahead of the neuter, defined those classes unneutered and moved the client's crash
		// EARLIER, into ModelManager.reload's shared state, with no hint of the connection.
		//
		// Registered only when some mod actually declares extensions, so the chain is untouched otherwise.
		if (NeoEnumExtensions.load(loader, modJars) > 0) {
			NeoEnumExtensionInjector enumExtensions = NeoEnumExtensionInjector.create(loader);
			if (enumExtensions != null) chain.register(TransformPhase.COREMOD, enumExtensions);
		}

		// The traditional-MinecraftForge twin. Unconditional, unlike the NeoForge one above: NeoForge's model is a
		// declaration file per mod, so "did anyone declare anything" is answerable up front, while MinecraftForge's
		// is a mod calling create(...) at runtime — there is nothing to count beforehand. Their own processor
		// declines every class outside two packages, and declines everything unless MinecraftForge's mod list holds
		// more than two mods, so this is inert on a pack without traditional-Forge mods by their rule.
		net.forbric.kernel.transform.ForgeEnumExtensionInjector forgeEnums =
				net.forbric.kernel.transform.ForgeEnumExtensionInjector.create(loader);
		if (forgeEnums != null) chain.register(TransformPhase.COREMOD, forgeEnums);
		// MinecraftForge's CapabilityTokenSubclass plugin, driven the same way: without it every capability token's
		// getType() throws and ForgeCapabilities.<clinit> dies for every Forge mod that names it.
		if (forgeCapabilities) {
			net.forbric.kernel.transform.ForgeCapabilityTokenInjector tokens =
					net.forbric.kernel.transform.ForgeCapabilityTokenInjector.create(loader);
			if (tokens != null) chain.register(TransformPhase.COREMOD, tokens);
		}

		// LAST in the chain, because it has to see every edit the coremod phase made: a transformer that adds a
		// branch leaves a frame of its own, and the recomputation must be over the final shape. A mod compiled
		// against one ecosystem can name a superclass the merge took off that hierarchy — MinecraftForge's
		// CapabilityProvider above Entity is the live case — and such a class fails VERIFICATION, before any of
		// its code runs, naming a type its author never wrote. See MergedBaseFrameRecomputer.
		chain.register(TransformPhase.FABRIC_BUILTIN,
				new net.forbric.kernel.access.RestoredAccessTransformer(accessWideners, forgeAts));
		chain.register(TransformPhase.FABRIC_BUILTIN, new MergedBaseFrameRecomputer(path -> {
			try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException unreadable) {
				return null;
			}
		}));

		TransformContext ctx = new TransformContext(side.envType, false, "named");
		// One summary, at the point where "never loaded" starts meaning something. The per-repair failures are
		// already loud where they happen and do not wait for this.
		chain.reportWhenLoaded(side.censusLandmark);
		loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx));

		Thread.currentThread().setContextClassLoader(loader);

		KernelLifecycle.bind(loader);
		KernelHudBridge.bind(loader);
		LootTableEventDispatch.bind(loader);
		KernelLifecycle.setModJars(modJars);
		// Where the load report goes, and the shutdown hook that writes it if loading never finishes -- which is
		// exactly the boot whose reader needs the file most.
		KernelLoadReport.setRunDir(gameDir);
		// And the other post-mortem: if this boot ends in a crash report, say which mods it points at. Same
		// shutdown-hook idiom, a separate file, and it costs nothing on a boot that does not crash.
		CrashAttribution.setRunDir(gameDir);
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
		// as names is ImmediateWindowHandler, which is not on this path. seedAll repeats three of the four calls
		// below — seedNeoForgePaths, seedNeoForgeLoader and seedForgeFmlLoader, all idempotent — and does NOT
		// repeat publishForgeLoadingList.
		//
		// WHERE THE RACE ACTUALLY IS, because it is not where it looks: the transformer goes in inside
		// KernelMixinBootstrap.init, but gotoPhase(INIT)/gotoPhase(DEFAULT) there does NOT prepare configs. Mixin
		// prepares them, and constructs every IMixinConfigPlugin, on the FIRST class that passes through the
		// transformer — which is KernelRuntimeClasses.verify(loader) below, not the init call. `initialize=false`
		// is no help either: a class is transformed when it is DEFINED.
		PassiveSeeder.seedNeoForgePaths(loader, gameDir);
		// The mods dir is passed explicitly (not re-derived inside the seeder) because the LoadingModList seeded here
		// must describe the SAME jars this boot decided to load — see discoverForgeFamilyModJars above, which walks
		// exactly this directory. Two independent derivations of "where the mods are" is how they drift apart.
		PassiveSeeder.seedNeoForgeLoader(loader, gameDir, gameDir.resolve("mods"), side.api());
		// MinecraftForge's FMLLoader identity, for a reason its NeoForge twin does not have: NeoForge's
		// FMLEnvironment is stateless, so seeding it late could only THROW, which is loud. MinecraftForge's
		// CACHES FMLLoader's answers into four public static final fields in a <clinit> that cannot throw — every
		// getter it calls is a bare getstatic — so whoever touches it first decides `dist` FOREVER, and a guest
		// mixin plugin's own <clinit> during prepareConfigs is exactly such a toucher. Seeded afterwards, dist is
		// permanently null: AutomaticEventSubscriber's Set.contains(null) then skips every @EventBusSubscriber,
		// and ModLoader/ConfigTracker/RuntimeDistCleaner all take the wrong branch. Nothing throws.
		PassiveSeeder.seedForgeFmlLoader(loader, gameDir, side.api());
		// MinecraftForge's twin, and it has to be HERE rather than in the mod-loading window where its seed lives:
		// its list is built by a one-shot class initializer, so the answer must exist before anything can ask. The
		// most likely early asker is a guest mixin plugin during prepareConfigs, which is the next line but one.
		PassiveSeeder.publishForgeLoadingList(loader, gameDir.resolve("mods"));

		// Mixin LAST in the pipeline but FIRST in time: installed before anything defines a targeted class.
		//
		// Fabric first, Forge-family APPENDED. Within one environment Mixin selects by priority (the config's, then
		// each @Mixin's); registration order is only the tiebreak among equal priorities, where a later-registered
		// mixin applies AFTER an earlier one on the same target. Appending therefore leaves every existing
		// Fabric-vs-Fabric ordering byte-identical — so gate-m2b cannot move for ordering reasons — and makes the
		// newly-introduced, least-proven set the OUTER wrapper around a known-good stack rather than the inner one.
		List<MixinConfigOwners.Owned> fabricConfigs = KernelFabricEcosystem.mixinConfigs();
		List<MixinConfigOwners.Owned> forgeConfigs = KernelForgeFamilyMixins.select(forgeMixinDecls);
		List<MixinConfigOwners.Owned> ownedConfigs = new ArrayList<>(fabricConfigs);
		List<String> mixinConfigs = new ArrayList<>();
		for (MixinConfigOwners.Owned one : fabricConfigs) mixinConfigs.add(one.config());
		for (MixinConfigOwners.Owned one : forgeConfigs) {
			ownedConfigs.add(one);
			if (!mixinConfigs.contains(one.config())) mixinConfigs.add(one.config());
		}
		// Published BEFORE registration, not after: Mixin parses each config and constructs its plugin inside
		// addConfiguration, and the kernel's own adapter reports on individual mixins from inside that parse. A
		// map published afterwards would be correct and would arrive after every line that needed it.
		MixinConfigOwners.publish(ownedConfigs);
		if (!forgeConfigs.isEmpty() || !forgeMixinDecls.isEmpty()) {
			List<String> described = new ArrayList<>();
			for (MixinConfigOwners.Owned one : forgeConfigs) described.add(MixinConfigOwners.describe(one.config()));
			ForbricLog.info("[Forbric/Mixin] mixin configs: %d Fabric + %d Forge-family (%d NeoForge, %d "
					+ "MinecraftForge) — %s", fabricConfigs.size(), forgeConfigs.size(),
					KernelForgeFamilyMixins.count(forgeMixinDecls, forgeConfigs, Ecosystem.NEOFORGE),
					KernelForgeFamilyMixins.count(forgeMixinDecls, forgeConfigs, Ecosystem.FORGE),
					described.isEmpty() ? "none kept" : String.join(", ", described));
		}
		KernelMixinBootstrap.init(loader, side.envType, mixinConfigs);
		reportMixinExtrasSource(loader);

		// AFTER Mixin, because half of what the audit reports is written during it. KernelGuestMixinAdapter
		// records a mixin that was written to attach to another mod and did not while Mixin parses each config,
		// and the audit -- which reads that list and shows it to the player -- used to run thirty lines earlier,
		// inside the seeder. It always read an empty list, so that section of the dialog had never once appeared.
		PassiveSeeder.reportDependencies();

		// The kernel's OWN game-side half, proven here rather than assumed: loaded through the finished pipeline,
		// checked to have landed on the game loader. Deliberately not earlier -- these classes should take exactly
		// the path every game class takes, and before this point the transformer and Mixin are not yet installed.
		// Deliberately not later either: the next line starts seeding the ecosystems, and a kernel missing its own
		// game side should say so before it touches theirs. See KernelRuntimeClasses.
		KernelRuntimeClasses.verify(loader);

		// Seed the minimum genuine-loader identity the merged base's patched <clinit>s read (no lifecycle). The
		// Dist must match the side — a client seeded as DEDICATED_SERVER makes NeoForge reject the local player's
		// integrated-server connection ("Server is still starting").
		PassiveSeeder.seedAll(loader, gameDir, side.api());
		FabricApiModuleLossAudit.report(side.api());
		FieldDriftAudit.report();
		AbiLinkAudit.report();
		KernelLoadReport.write();
		net.forbric.kernel.ui.CompatibilityDecision.requireContinuation(side.api().isClient());

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
		// undo of server-synced registry ids back to a "frozen" snapshot. NeoForge's own body cannot run here: the
		// kernel owns the freeze, so GameData.freezeData never took the snapshot it re-applies (frozenSnapshot null
		// → NPE). It used to be neutered for that; it is no longer, because RegistrySyncParityInjector REWRITES the
		// body to apply the kernel's own pre-connection snapshot (KernelRegistryRevert) — and this neuter, registered
		// after that injector, was emptying the rewritten body again. Do not add it back.

		for (String owner : new String[] {
				ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE),
				ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.FORGE)}) {
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

				Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
				if (owner == null) continue;

				// One for one with the arbitrated ecosystem. It used to collapse both Forge families into one
				// constant, which told a NeoForge-only mod that traditional MinecraftForge's loader class exists.
				families.put(jar, LoaderProbePolicy.familyOf(owner));
			}
		}
		return families;
	}

	/** The version log alone cannot distinguish the supplied library from a guest's older copy. */
	private static void reportMixinExtrasSource(ForbricClassLoader loader) {
		try {
			Class<?> bootstrap = Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap", false, loader);
			var source = bootstrap.getProtectionDomain().getCodeSource();
			ForbricLog.info("[Forbric/Boot] MixinExtras sources: class=%s; config=%s; game-side=%s",
					source == null ? "<unknown>" : source.getLocation(),
					loader.findResource("mixinextras.init.mixins.json"), bootstrap.getClassLoader() == loader);
		} catch (ClassNotFoundException absent) {
			// KernelMixinBootstrap already reported the absent library and its consequence.
		}
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
	 *
	 * <h2>The carriers' own files, which is the larger half</h2>
	 *
	 * <p>{@code carrierJars} are read for the same files, and they are not an afterthought. Each Forge family
	 * ships an access transformer that widens the GAME for every mod of that family, and the genuine loader
	 * applies it before anything else runs. The merged base keeps whichever family's method body won the merge,
	 * along with that body's access flags — so where the two families patched the same method, the loser's
	 * widening is simply gone.
	 *
	 * <p>{@code MenuScreens.register} is the one that matters most: traditional MinecraftForge's file declares it
	 * public, the merged base has it private, and it is the single line EVERY MinecraftForge mod with a GUI runs
	 * during client setup. Without this the mod dies there with an illegal-access error and its screens never
	 * register. Re-applying a widening that is already in place is a no-op, so feeding both families' files in is
	 * safe as well as correct.
	 */
	private static net.forbric.kernel.access.AccessTransformer forgeFamilyAccessTransformer(List<Path> modJars,
			List<Path> carrierJars) {
		List<net.forbric.kernel.access.AtDirective> directives = new ArrayList<>();
		int jarsWithAts = 0;

		List<Path> all = new ArrayList<>(carrierJars);
		all.addAll(modJars);
		int carrierDirectives = 0;

		for (Path jar : all) {
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
								net.forbric.kernel.access.AccessTransformerParser.parse(r,
										(carrierJars.contains(jar) ? "carrier:" : "") + jar.getFileName());
						directives.addAll(parsed);
						any = true;
						ForbricLog.debug("[Forbric/AT] %s: %d directive(s) from %s", jar.getFileName(), parsed.size(),
								name);
					}
				}
				if (any) jarsWithAts++;
				if (carrierJars.contains(jar)) carrierDirectives = directives.size();
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/AT] could not read access transformers from " + jar.getFileName(), t);
			}
		}

		if (directives.isEmpty()) return null;

		ForbricLog.info("[Forbric/AT] applying %d Forge-family access-transformer directive(s) from %d jar(s), %d of "
				+ "them from the runtime carriers — the carriers' file is what makes the game's own members "
				+ "reachable to every mod of that family, and where the merge kept the other family's method body "
				+ "it kept that body's access too (MenuScreens.register, which every MinecraftForge GUI mod calls, "
				+ "came out private)",
				directives.size(), jarsWithAts, carrierDirectives);
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
	 * <p>Deduplicated by ARTIFACT, not by file name. Two mods can nest the same library under different file
	 * names — cookingforblockheads carries {@code shogi-api-26.2.0.1-SNAPSHOT.jar} and shogi carries
	 * {@code net.blay09.mods.shogi-api-26.2.0.3.jar}, the same {@code net.blay09.mods:shogi-api} at two versions —
	 * and a name-keyed set extracts both, putting two builds of one library on the class loader. Forge's own
	 * JarJarSelector resolves those by version range; the kernel reads {@code META-INF/jarjar/metadata.json} for
	 * each child's {@code group:artifact} and keeps the highest version, which is the same answer for every case
	 * that occurs in practice. A child with no metadata falls back to the file-name rule.
	 *
	 * <p>Bytecode is never remapped: on MC 26.2 nested jars are Mojmap already, exactly like their host.
	 */
	/**
	 * What {@link #extractForgeFamilyJarJar} actually put on the classpath this boot, or {@code null} before it has
	 * run.
	 *
	 * <p>Recorded because a diagnostic that asks "is this mod installed" has no other way to find out. The
	 * Forge-family mod list is a walk of {@code mods/}, and a JarJar-nested mod is not in {@code mods/} — it is
	 * unpacked here and then loaded exactly like a top-level one. A checker that consults only the walk therefore
	 * reports Journeymap's {@code commonnetworking} and LambDynamicLights' {@code spruceui} as missing while the
	 * boot log, four lines earlier, says it extracted them. The {@code null} state matters as much as the list:
	 * "extraction has not run" and "extraction found nothing" must not look the same to a caller that is about to
	 * accuse a mod of a missing dependency.
	 */
	private static volatile List<Path> nestedJarJarJars;

	/** @see #nestedJarJarJars */
	public static List<Path> nestedJarJarJars() {
		return nestedJarJarJars;
	}

	// Package-private so the tests can drive the real extraction against real jars rather than a mock of it.
	static List<Path> extractForgeFamilyJarJar(List<Path> modJars, Path gameDir) {
		Path outDir = gameDir.resolve(".forbric-kernel").resolve("jarjar");
		List<Path> extracted = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		// extracted file -> "group:artifact" -> version, for the artifact-level resolution after the walk. The
		// coordinate lives in the PARENT's metadata.json, not in the child, so it has to be captured here.
		java.util.Map<Path, String[]> coordinates = new java.util.LinkedHashMap<>();

		// A worklist, not a single pass: a nested library can nest libraries of its own, and one level of extraction
		// leaves the innermost ones on nobody's classpath. Tectonic bundles apollib, apollib bundles json5-java, and
		// Tectonic's @Mod constructor died on NoClassDefFoundError: de/marhali/json5/stream/Json5Lexer — its client
		// class had constructed, so the mod looked present while its main class had never run.
		java.util.Deque<Path> queue = new java.util.ArrayDeque<>(modJars);
		while (!queue.isEmpty()) {
			Path modJar = queue.poll();
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(modJar.toFile())) {
				java.util.Map<String, String[]> declared = jarJarCoordinates(zip);
				for (var entries = zip.entries(); entries.hasMoreElements();) {
					java.util.zip.ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					// Both conventions at every level: NeoForge nests at META-INF/jarjar/, Fabric at META-INF/jars/,
					// and a multiloader library uses its own regardless of the jar that carries it — apollib is a
					// NeoForge jar nesting json5 the Fabric way.
					boolean nested = name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/jars/");
					if (entry.isDirectory() || !nested || !name.endsWith(".jar")) continue;

					String simple = name.substring(name.lastIndexOf('/') + 1);
					if (!seen.add(simple)) continue;

					Path target = outDir.resolve(simple);
					Files.createDirectories(outDir);
					try (java.io.InputStream in = zip.getInputStream(entry)) {
						Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
					extracted.add(target);
					String[] coordinate = declared.get(name);
					if (coordinate != null) coordinates.put(target, coordinate);
					// Descend: what we just wrote may itself carry nested jars.
					queue.add(target);
					ForbricLog.info("[Forbric/Boot] extracted nested JarJar library %s from %s", simple,
							modJar.getFileName());
				}
			} catch (Exception e) {
				ForbricLog.warn("[Forbric/Boot] could not read JarJar children of %s: %s", modJar.getFileName(),
						String.valueOf(e));
			}
		}

		return resolveJarJarByArtifact(extracted, coordinates);
	}

	/**
	 * Reads a jar's {@code META-INF/jarjar/metadata.json}: nested entry name -> {@code {group:artifact, version}}.
	 *
	 * <p>Empty for a jar without it, which is most of them — Fabric-style {@code META-INF/jars/} children carry no
	 * such manifest, and those keep the file-name rule.
	 */
	private static java.util.Map<String, String[]> jarJarCoordinates(java.util.zip.ZipFile zip) {
		java.util.zip.ZipEntry metadata = zip.getEntry("META-INF/jarjar/metadata.json");
		if (metadata == null) return java.util.Map.of();

		java.util.Map<String, String[]> out = new java.util.LinkedHashMap<>();
		try (java.io.Reader reader = new java.io.InputStreamReader(zip.getInputStream(metadata),
				java.nio.charset.StandardCharsets.UTF_8)) {
			var root = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(reader);
			List<? extends com.electronwill.nightconfig.core.UnmodifiableConfig> jars =
					root.getOrElse("jars", List.of());
			for (var entry : jars) {
				com.electronwill.nightconfig.core.UnmodifiableConfig id = entry.get("identifier");
				com.electronwill.nightconfig.core.UnmodifiableConfig version = entry.get("version");
				String path = entry.getOrElse("path", (String) null);
				if (path == null || id == null) continue;
				String group = id.getOrElse("group", "");
				String artifact = id.getOrElse("artifact", "");
				if (group.isEmpty() && artifact.isEmpty()) continue;
				out.put(path, new String[] {group + ":" + artifact,
						version == null ? "0.0.0" : version.getOrElse("artifactVersion", "0.0.0")});
			}
		} catch (Exception e) {
			ForbricLog.debug("[Forbric/Boot] could not read JarJar metadata from %s: %s", zip.getName(),
					String.valueOf(e));
		}
		return out;
	}

	/**
	 * Keeps one build per {@code group:artifact} — the highest version — and drops the rest from the classpath.
	 *
	 * <p>This is the step that makes the file-name dedupe above safe. Two mods nesting the same library under
	 * different file names both get extracted, and without this both are on the class loader: two builds of one
	 * library, whose classes share names but not bytes, resolved by whichever jar the loader reaches first.
	 *
	 * <p>The superseded file is left on disk rather than deleted — it is inside the kernel's own scratch directory,
	 * deleting it buys nothing, and leaving it makes the decision inspectable after the fact.
	 */
	private static List<Path> resolveJarJarByArtifact(List<Path> extracted, java.util.Map<Path, String[]> coords) {
		if (coords.isEmpty()) return extracted;

		java.util.Map<String, Path> best = new java.util.LinkedHashMap<>();
		java.util.Set<Path> superseded = new java.util.LinkedHashSet<>();
		for (Path jar : extracted) {
			String[] coordinate = coords.get(jar);
			if (coordinate == null) continue; // no metadata — the file-name rule already decided
			Path incumbent = best.get(coordinate[0]);
			if (incumbent == null) {
				best.put(coordinate[0], jar);
				continue;
			}
			String incumbentVersion = coords.get(incumbent)[1];
			Path loser = isNewer(coordinate[1], incumbentVersion) ? incumbent : jar;
			Path winner = loser == incumbent ? jar : incumbent;
			best.put(coordinate[0], winner);
			superseded.add(loser);
			ForbricLog.info("[Forbric/Boot] %s is nested twice — keeping %s, dropping %s (two builds of one library "
					+ "on the class loader share class NAMES but not bytes)", coordinate[0],
					winner.getFileName(), loser.getFileName());
		}
		if (superseded.isEmpty()) return extracted;

		List<Path> kept = new ArrayList<>(extracted);
		kept.removeAll(superseded);
		return kept;
	}

	/** True when {@code candidate} sorts above {@code incumbent}. Unparseable versions never win. */
	private static boolean isNewer(String candidate, String incumbent) {
		try {
			return net.forbric.kernel.fabric.KernelVersion.parse(candidate)
					.compareTo(net.forbric.kernel.fabric.KernelVersion.parse(incumbent)) > 0;
		} catch (Exception unparseable) {
			return false;
		}
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

	/** The superseded jars as URLs, sorted so the last-resort lookup order is stable run to run. */
	private static List<URL> rescueUrls(DuplicateModArbiter.Decision dupes) {
		List<URL> urls = new ArrayList<>();
		for (Path jar : new java.util.TreeSet<>(dupes.suppressedJars())) {
			try {
				urls.add(jar.toUri().toURL());
			} catch (Exception e) {
				ForbricLog.debug("[Forbric/DupeId] could not offer %s as a rescue jar: %s", jar, String.valueOf(e));
			}
		}
		return urls;
	}

	private static ForgeFamilyMods discoverForgeFamilyModJars(Path modsDir,
			DuplicateModArbiter.Decision dupes) {
		List<Path> jars = new ArrayList<>();
		List<KernelForgeFamilyMixins.ForgeMixinConfig> configs = new ArrayList<>();
		if (!Files.isDirectory(modsDir)) return new ForgeFamilyMods(jars, configs);
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		try (var entries = Files.list(modsDir)) {
			List<Path> candidates = entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile).sorted().toList();
			for (Path jar : candidates) {
				// A jar another jar's copy of the same mod won is "not installed" — it contributes no classes, no
				// mixin configs, no ATs and no JiJ children. That is what both genuine loaders would see, and it is
				// the whole point: keeping it would leave the shadowing and the double mixin apply in place.
				if (dupes.suppressed(jar)) {
					ForbricLog.debug("[Forbric/DupeId] skipping Forge-family jar %s — superseded", jar.getFileName());
					continue;
				}
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
				// The mod id is part of the key on purpose. ForgeMetadataMapper copies one manifest's config list
				// into EVERY DiscoveredMod that manifest declares, so a toml with three [[mods]] offers the same
				// config under three different ids. Keeping only the first would pick one of them and be quietly
				// wrong about the other two; keeping all three lets MixinConfigOwners see that nobody owns it
				// unambiguously and report the file name instead of a name that might be the wrong one.
				if (seen.add(mod.getEcosystem() + "\0" + mod.getId() + "\0" + config)) {
					configs.add(new KernelForgeFamilyMixins.ForgeMixinConfig(config, mod.getId(), jar,
							mod.getEcosystem()));
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
