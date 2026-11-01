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
import net.forbric.kernel.transform.DataPackHookInjector;
import net.forbric.kernel.transform.ExitHookInjector;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import net.forbric.kernel.transform.ForeignModPresenceInjector;
import net.forbric.kernel.transform.GuestMixinPluginGuard;
import net.forbric.kernel.transform.LifecycleHookInjector;
import net.forbric.kernel.transform.MergedBaseFrameRecomputer;
import net.forbric.kernel.transform.ConfigApiAbiInjector;
import net.forbric.kernel.transform.LoaderProbeRewriter;
import net.forbric.kernel.transform.MethodBodyNeuter;
import net.forbric.kernel.transform.NeoEnumExtensionInjector;
import net.forbric.kernel.transform.NullPackGuardInjector;
import net.forbric.kernel.transform.PackMetadataFailSoftInjector;
import net.forbric.kernel.transform.PackOverlayMutabilityInjector;
import net.forbric.kernel.transform.TransformChain;
import net.forbric.kernel.transform.TransformContext;
import net.forbric.kernel.transform.TransformPhase;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * The shared boot flow behind {@link KernelServerLaunch} and {@code KernelClientLaunch}: build the one sovereign
 * {@link ForbricClassLoader} over the base + ecosystem carriers + all mods + MC libraries, install the
 * transform pipeline (access wideners → lifecycle redirect → concessions → Mixin), discover both ecosystems,
 * bring up Mixin + Fabric, and hand off to the base's {@code Main.main} — which now runs plain vanilla boot
 * with the genuine loader trigger redirected to the kernel's own native registration window.
 *
 * <p>Server vs client differ only in the {@link Side}: env type, entry class, which lifecycle trigger is
 * redirected, and a few side-specific transform concessions. Everything else is identical, which is the point of
 * sharing it — the ecosystems' registration is side-independent.
 */
public final class KernelBoot {
	private KernelBoot() {
	}

	/** Used only when the base jar carries no {@code version.json}; the base is built from 26.2. */
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
	 * {@code --gameJar}/{@code --runtimeJar}/{@code --libraryPath}/{@code --nativeReference} are consumed here
	 * ({@code --runtimeJar} takes either one jar or several joined by the platform path separator); everything after
	 * {@code --} (and any unrecognized token) is forwarded to the game's {@code Main.main}.
	 */
	public static void launch(Side side, String[] args) throws Throwable {
		// Before any switch is read: a renamed or retired -Dforbric.* flag is named in the log even when the mechanism
		// it controls never runs in this session.
		net.forbric.kernel.util.ForbricSwitches.announce();
		net.forbric.api.CompatibilityFindings.reset();
		net.forbric.kernel.discovery.MetadataFailures.reset();
		net.forbric.kernel.ui.CompatibilityDecision.reset();
		net.forbric.kernel.mixin.MixinCompatibility.reset();
		List<URL> owned = new ArrayList<>();
		List<String> gameArgs = new ArrayList<>();
		List<Path> runtimeJars = new ArrayList<>();
		List<Path> gameJars = new ArrayList<>();
		Path gameJar = null;
		String libraryPath = null;
		Path nativeReference = null;
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
					gameJars.add(jar);
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
				// The vanilla-reference sidecar: read by NativeReferenceSources, never owned -- it holds no classes and
				// is not part of the game. Its own flag rather than a second --gameJar, because a launcher that keeps the
				// last value of a repeated flag would hand the kernel the sidecar as the game.
				case "--nativeReference" -> nativeReference = new File(req(args, ++i, a)).toPath();
				case "--" -> afterSep = true;
				default -> gameArgs.add(a);
			}
		}

		if (owned.isEmpty()) {
			System.err.println("forbric-kernel: no --gameJar given (need NeoForge's patched game jar)");
			System.exit(2);
			return;
		}

		// The jars ARE the install, so they are checked by what is in them before anything is read out of them. A
		// "runtime" jar holding no Forge or NeoForge used to pass every step below with an empty answer and take the
		// boot down at KernelRuntimeClasses.verify, on stderr, with nothing in latest.log (issue #13).
		LaunchInputCheck.require(gameJars, runtimeJars);
		// Where every ecosystem's native references come from, decided from these files before anything reads one, and
		// said in one line: on a base that embeds no index the FABRIC half is the sidecar or nothing.
		net.forbric.kernel.mixin.NativeGameReferences.configure(
				net.forbric.kernel.mixin.NativeReferenceSources.open(gameJar, nativeReference));

		Path gameDir = extractGameDir(gameArgs, side.stripGameDir);
		String gameVersion = detectGameVersion(gameJar);

		// After a crash the last run attributed, offer to start without its suspects. Here, before arbitration,
		// because "start without" is a line in forbric-disabled.txt and arbitration is what reads that file.
		if (!CrashSuspectOffer.run(gameDir, side == Side.CLIENT)) return;

		// Two separate jars can declare the SAME mod id — inevitable the moment a Fabric pack and a NeoForge pack
		// are merged. MultiLoaderArbiter cannot see that (it is keyed by jar path), and left alone both jars enter
		// `owned` and shadow each other class-for-class, contribute each other's mixin configs, and register the
		// same content twice. Decide once here; both discoveries below skip the losers.
		// Pre-scan every declared nested candidate before either discovery discards a root. The later
		// arbitrateNested call verifies physical files against this same decision; it does not choose again.
		DuplicateModArbiter.Decision topLevelDupes =
				DuplicateModArbiter.arbitrate(gameDir.resolve("mods"), side.envType, gameVersion);

		// Forge/NeoForge mod jars (Mojmap-compiled like the base → load directly, no remap), plus the
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
			Caught.warn(t, "[Forbric/Presence] could not list the Forge-family mods for cross-ecosystem presence — a "
					+ "Fabric mod asking whether one of them is installed will be told no");
		}
		List<Path> modJars = new ArrayList<>(forgeFamily.jars());
		// The top-level ones alone, before the nested are added below: genuine FML looks for early services
		// (NeoGraphicsBootstrappers) among the files of mods/ and never inside a JarJar.
		final List<Path> topLevelForgeFamilyJars = List.copyOf(forgeFamily.jars());
		// The pre-scan has already selected every root and nested candidate. Consume those exact files;
		// the legacy extractor is only for the explicit arbitration-off mode.
		NestedCandidatePlan candidatePlan = DuplicateModArbiter.currentPlan();
		List<Path> nested = candidatePlan == null ? extractForgeFamilyJarJar(modJars, gameDir)
				: candidatePlan.nestedFiles();

		// Fabric discovery consumes the same preselected physical files and registers nothing yet. The union
		// below is checked against the plan before either loader builds containers or adds losing jars to the
		// classpath. When arbitration is explicitly disabled, both original discovery paths remain available.
		FabricModDiscovery fabricScan = scanFabricMods(side, gameDir, topLevelDupes, gameVersion, candidatePlan, modJars,
				nested);
		List<Path> allNested = new ArrayList<>(nested);
		for (Path jar : fabricScan.getClasspathJars()) {
			if (!modJars.contains(jar) && !nested.contains(jar)) allNested.add(jar);
		}
		final DuplicateModArbiter.Decision dupes = DuplicateModArbiter.arbitrateNested(side.envType, allNested);
		nested = nested.stream().filter(jar -> !dupes.suppressed(jar)).toList();

		nestedJarJarJars = List.copyOf(nested);
		modJars.addAll(nested);
		publishNestedPresence(nested);

		// THE MC LIBRARIES GO IN AHEAD OF THE MODS, and the order is the whole policy.
		//
		// They are owned rather than merely parent-visible because mods mixin into them
		// (fabric-dimension-api-v1 targets DataFixerUpper's TaggedChoice), and they sit AFTER the base and
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
		// the base was compiled against. That is also what the genuine loaders do — Knot and FML put
		// Minecraft's libraries on the same loader ahead of mods — so a mod relying on winning here was relying on
		// something that does not hold on its own platform either.
		List<Path> minecraftLibraries = libraryJars(libraryPath);
		int libCount = minecraftLibraries.size();

		// A nested mod's mixins are the same defect one level down. These jars already get everything else a
		// top-level mod gets — they are owned, and KernelModLoader scans them for @Mod, which is how whitenoise
		// (inside Mob Champions) is constructed — so leaving their configs out would be arbitrary. Pure libraries
		// declare none and cost one manifest read.
		List<KernelForgeFamilyMixins.ForgeMixinConfig> forgeMixinDecls = new ArrayList<>(forgeFamily.mixinConfigs());
		forgeMixinDecls.addAll(discoverForgeMixinConfigs(nested, "nested mod jar"));
		// And the runtimes' own. NeoForge's jar declares neoforge.mixins.json — two accessors its own code casts to
		// (BlockEntityTypeAddBlocksEvent on BlockEntityType.validBlocks, the biome/structure modifier re-sync on
		// MappedRegistry.registrationInfos) — and the runtime jars never went through discovery, so it was never
		// registered: every mod adding blocks to a block entity type got a ClassCastException from NeoForge itself
		// (tofucraft on the popular pack). The genuine loader registers it like any mod's.
		forgeMixinDecls.addAll(discoverForgeMixinConfigs(runtimeJars, "runtime jar"));
		// Every jar has been read and has an owner. A manifest that could not be read cost that jar alone; say
		// which, at the same weight as any other mod that did not load (see MetadataFailures).
		// A refused MinecraftForge jar is recorded the same way: installed in mods/, a required loss; bundled, a note;
		// switched off by the player, nothing; supplied by the NeoForge or Fabric build of the same mod beside it, a note.
		// A finding asks which family loads a jar: none for a library, and none for a refused jar, whose refusal it
		// reports on its own.
		net.forbric.kernel.discovery.MetadataFailures.recordFindings(
				jar -> MultiLoaderArbiter.claimOf(jar) instanceof JarClaim.Owned loads ? loads.ecosystem() : null,
				runtimeJars, gameDir.resolve("mods"), topLevelDupes::suppressed, DuplicateModArbiter::loadedClaimant);

		// Game-side bundled libraries (MixinExtras) and the kernel's own runtime jar. The latter also carries
		// the kernel's client assets -- the Mods button's icon lives in it -- so its extracted path is handed to
		// the lifecycle for the client pack repository as well as to the class loader. Extracted before the Fabric
		// side is built: Fabric Loader nests MixinExtras in its own jar, so on Fabric it is a mod inside fabricloader,
		// and the kernel's copy is registered the same way (KernelFabricEcosystem.registerLoaderNested).
		List<Path> bundled = KernelBundledJars.extract(gameDir);

		// Fabric mods (+ extracted JiJ children). Also Mojmap on this game version. Creates the FabricLoader.
		List<Path> fabricJars = KernelFabricEcosystem.build(fabricScan, side.envType, gameDir, gameVersion,
				gameArgs.toArray(new String[0]), dupes, gameJar, bundled);
		// Which custom entrypoint keys the Fabric mods just declared only a build arbitration did NOT load would have
		// dispatched; the lifecycle dispatches them in its place (ArbitratedAwayDispatchers says when and why).
		try {
			ArbitratedAwayDispatchers.record(dupes, KernelFabricEcosystem.declaredEntrypointKeys(),
					KernelFabricEcosystem.knownModIds());
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/DupeId] could not work out which entrypoint keys a superseded build dispatched — "
					+ "a Fabric mod integrating with a library whose other build loaded may go uninitialised");
		}

		owned = KernelOwnedClasspath.compose(owned, minecraftLibraries, modJars, fabricJars, bundled);
		// A MinecraftForge-only build another mod bundles is refused like one in mods/, but it is not the player's
		// mod and its parent may call into it. It stays on the class path above, as native NeoForge keeps a nested jar
		// it does not read as a mod — a library — and leaves every pass from here on: no @Mod, mixins, access
		// transformer, event subscribers or packs of it, and no probe family. The refused jars in mods/ never got
		// this far: discovery yielded no mod of theirs.
		List<Path> refusedNested = nested.stream().filter(MultiLoaderArbiter::refused).toList();
		if (!refusedNested.isEmpty()) {
			modJars.removeAll(refusedNested);
			ForbricLog.info("[Forbric/Unsupported] %d bundled MinecraftForge build(s) kept only as libraries — Forbric no "
					+ "longer loads MinecraftForge mods: %s", refusedNested.size(),
					refusedNested.stream().map(jar -> String.valueOf(jar.getFileName())).toList());
		}
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
		// Which installed mods name a fabric-api surface this boot still switches off; reported after the
		// catalog is published, so the rows reach load-report.txt.
		FabricApiModuleLossAudit.scan(shadowCandidates);
		// Which installed jars read a vanilla field with a descriptor the base no longer declares (NoSuchFieldError
		// at that access); reported after the catalog is published, so the rows reach load-report.txt.
		FieldDriftAudit.scan(shadowCandidates);
		// Which base methods nothing in the game calls an installed mod calls itself: an injector bound
		// there still runs. Read before Mixin prepares a config, so MixinFit's liveness verdict can ask.
		net.forbric.kernel.mixin.MergedBaseUncalledMethods.scanGuests(shadowCandidates);
		// Which installed jars name a Forge-family class that exists in no carrier, not the base and no
		// installed jar (compiled against another NeoForge/MinecraftForge); reported after the catalog is published.
		List<Path> abiUniverse = new ArrayList<>(runtimeJars);
		if (gameJar != null) abiUniverse.add(gameJar);
		AbiLinkAudit.scan(shadowCandidates, abiUniverse);

		// The jars of mods/ that are Forge-family mods, and apart from them every jar the boot took out of another jar
		// (of either ecosystem: fabric-api's modules are among them), which modJars also holds.
		ForbricLog.info("[Forbric/Boot] sovereign kernel — %s %s, %d owned jar(s), %d Forge-family mod(s) in mods/, "
				+ "%d nested jar(s), %d Fabric jar(s), %d MC library jar(s)", side.name().toLowerCase(), gameVersion,
				owned.size(), topLevelForgeFamilyJars.size(), modJars.size() - topLevelForgeFamilyJars.size(),
				fabricJars.size(), libCount);

		ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(new URL[0]),
				KernelBoot.class.getClassLoader());
		// How the liveness verdict follows a mod's static call naming a subclass up to the class that declares the method:
		// the super class each class's bytes name, read as resources (nothing is loaded).
		net.forbric.kernel.mixin.MergedBaseUncalledMethods.superclasses(internalName -> {
			try (java.io.InputStream in = loader.getGameResourceAsStream(internalName + ".class")) {
				return in == null ? null : new org.objectweb.asm.ClassReader(in.readAllBytes()).getSuperName();
			} catch (java.io.IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		});
		// The jars cross-jar arbitration superseded, as a LAST RESORT only — a mod built against the other side's
		// platform-only class would otherwise get a bare NoClassDefFoundError. See ForbricClassLoader.setRescueJars
		// for why this cannot shadow the winner, and for what it deliberately does not fix.
		loader.setRescueJars(rescueUrls(dupes));
		// Every mod jar probes as the loader the arbiter gave it, so a mod cannot wander into a branch it never ran
		// on its own platform — and a universal jar answers as the ONE ecosystem it was arbitrated to. Plain
		// libraries declare no manifest and stay unowned. See LoaderProbePolicy.
		loader.setJarFamilies(probeFamilies(fabricJars, modJars));
		List<DiscoveredMod> transformOwners = new ArrayList<>(ModPresence.forgeFamilyMods());
		transformOwners.addAll(ModPresence.fabricMods());
		loader.setModOrigins(transformOwners);
		RuntimeJarProvenance.register(loader, runtimeJars);
		// …and a universal jar's ServiceLoader lists only the providers that loader could link, as on its own.
		loader.setUniversalJars(universalJars(fabricJars, modJars));
		LoaderProbePolicy.bindGuestLoader(loader);
		// Service discovery sees the selected classpath and narrowed universal-jar declarations.
		// Providers can offer an absent public API before any consumer links against it.
		net.forbric.api.ProtocolExtensions.discover(loader, net.forbric.api.Side.parse(side.envType.name())).offerClasses();

		// A mod that unpacks its real payload at preLaunch has no public API for adding it to the classpath and
		// reaches into Fabric's internals for it. Installed before any mod class loads. See KernelFabricLauncher.
		net.forbric.kernel.fabric.KernelFabricLauncher.install(loader, side.envType);

		TransformChain chain = new TransformChain();
		boolean transferInterop = KernelTransferInterop.configure(loader);

		// Fabric Loader's @Environment stripping, in the phase TransformPhase always reserved for it: before ACCESS, as
		// on Fabric, so an access widener naming a stripped member matches nothing there too. Only classes from jars
		// arbitrated to Fabric -- Forge and NeoForge strip nothing, and Fabric does not strip Minecraft. Asked by
		// RESOURCE, because Mixin sees a class through this chain before it is defined. Without it CreativeCore's main
		// entrypoint died on every dedicated server. See EnvironmentStripTransformer.
		net.forbric.kernel.transform.EnvironmentStripTransformer envStrip =
				net.forbric.kernel.transform.EnvironmentStripTransformer.configured(loader::familyOfResource);
		if (envStrip != null) chain.register(TransformPhase.ENV_STRIP, envStrip);

		// Fabric access wideners before Mixin (ACCESS phase): the weaver must see the widened members.
		ClassTweakerTransformer accessWideners =
				ClassTweakerTransformer.createFrom(KernelFabricEcosystem.accessWidenerFiles(), loader::putGeneratedClass);
		if (accessWideners != null) chain.register(TransformPhase.ACCESS, accessWideners);

		// The Forge-family twin: every mod jar's META-INF/accesstransformer.cfg, in the same ACCESS phase — and
		// the NeoForge runtime carrier's own, which the base needs just as much. See the method.
		net.forbric.kernel.access.AccessTransformer forgeAts =
				forgeFamilyAccessTransformer(modJars, runtimeJars);
		if (forgeAts != null) chain.register(TransformPhase.ACCESS, forgeAts);

		// A guest mod's platform probe answers for the loader that mod was loaded as. Registered first in the phase:
		// it rewrites only Class.forName call sites, so nothing later in the chain can be looking at what it edits.
		LoaderProbeRewriter loaderProbes = new LoaderProbeRewriter(loader::familyOfClass);
		if (LoaderProbePolicy.enabled()) chain.register(TransformPhase.COREMOD, loaderProbes);

		// A NeoForge mod that reaches the Mixin weaver the way NeoForge lets it -- casting the context loader to FML's
		// TransformingClassLoader and wrapping FMLMixinClassProcessor.transformer -- is handed a view of that object
		// graph at the cast, and ModuleClassLoader is let initialise without java.lang.invoke opened so the view can
		// exist. LibJF's ASM layer paid for it ("Could not initialize LibJF ASM"). See FmlContextLoaderRewriter.
		if (net.forbric.kernel.mixin.MixinWeaverSlot.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FmlContextLoaderRewriter(
					loader::familyOfClass));
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ModuleClassLoaderInitInjector());
		} else {
			ForbricLog.warn("[Forbric/FmlView] -D%s=off -- a NeoForge mod that reaches the Mixin weaver through FML's "
					+ "TransformingClassLoader (LibJF's ASM layer) fails its cast and applies none of its class patches",
					net.forbric.kernel.mixin.MixinWeaverSlot.SWITCH);
		}

		// One mod's mixin config plugin must not be able to abort config preparation for every other mod. Mixin
		// guards plugin construction but not the calls, and a throw there escapes select(). See GuestMixinPluginGuard.
		chain.register(TransformPhase.COREMOD, new GuestMixinPluginGuard());
		// A plugin that reaches its mod's platform provider while Mixin prepares configs no longer defines game classes
		// by verifying that provider: ForbricClassLoader defers those checks for any class defined inside a weave.
		// See VerifierTypeDeferral.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ZeroNameTagMigrationInjector(name -> {
			try {
				String binary = name.replace('/', '.');
				if (loader.isClassLoadedByName(binary)) {
					// Inspect an already-defined class without initializing it; late mixins may have restored a field.
					Class<?> type = Class.forName(binary, false, loader);
					org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode(); node.name = name;
					for (var field : type.getDeclaredFields()) node.fields.add(new org.objectweb.asm.tree.FieldNode(
							field.getModifiers(), field.getName(), org.objectweb.asm.Type.getDescriptor(field.getType()), null, null));
					return node;
				}
				try (var in = loader.getGameResourceAsStream(name + ".class")) {
					if (in == null) return null;
					org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
					new org.objectweb.asm.ClassReader(in).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
					return node;
				}
			} catch (ReflectiveOperationException | java.io.IOException | LinkageError unavailable) {
				Caught.warn(unavailable, "[Forbric/Boot] could not read the declared fields of %s for the name-tag "
						+ "migration; it reads as absent", name);
				return null;
			}
		}));
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricItemContractTransformer(name -> {
			try (var in = loader.getGameResourceAsStream(name + ".class")) { return in != null; }
			catch (java.io.IOException unavailable) {
				Caught.warn(unavailable, "[Forbric/Boot] could not read game class %s for the Fabric item contract;"
						+ " it reads as absent", name);
				return false;
			}
		}));
		if (transferInterop) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.TransferTransactionHooks(name -> {
				try (var in = loader.getGameResourceAsStream(name + ".class")) { return in == null ? null : in.readAllBytes(); }
				catch (java.io.IOException unavailable) { Caught.warn(unavailable, "[Forbric/Boot] could not read game class %s for the transfer transaction hooks; it reads as absent", name); return null; }
			}));
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.TransferCapabilityFallback());
		}
		// Hoppers ask Fabric's item storage lookup where NeoForge's hopper found nothing, with or without the bridge.
		if (KernelTransferInterop.hopperActive()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.HopperFabricStorageInjector());
		}

		LifecycleHookInjector lifecycleHook = side.injector();
		chain.register(TransformPhase.COREMOD, lifecycleHook);

		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricModelContextTransformer(path -> {
			try (var in = loader.getGameResourceAsStream(path)) { return in == null ? null : in.readAllBytes(); }
			catch (java.io.IOException unavailable) {
				Caught.warn(unavailable, "[Forbric/Boot] could not read game class %s for the Fabric model context;"
						+ " it reads as absent", path);
				return null;
			}
		}));
		// Both sides, before the base compatibility repairs, which find the vanilla members these add and wire them to
		// NeoForge's live ones: vanilla members NeoForge's patches dropped (the int-keyed particle providers, vanilla's
		// payload codec, Hud's hotbar-and-decorations…), added where the base lacks them (on the merged base of Forbric
		// 0.4 and earlier, MinecraftForge's half kept them, so nothing landed there). The copied bodies are vanilla's, read through this
		// launch's native-reference sources: on a base that embeds no index, the sidecar is the only place they exist.
		if (net.forbric.kernel.transform.VanillaMemberTwinInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.VanillaMemberTwinInjector(
					net.forbric.kernel.transform.VanillaMemberTwinInjector.vanillaReferences(path -> {
						try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
							return in == null ? null : in.readAllBytes();
						} catch (java.io.IOException unreadable) {
							Caught.warn(unreadable, "[Forbric/Boot] could not read game resource %s for the vanilla member twins;"
									+ " it reads as absent", path);
							return null;
						}
					})));
		}
		// Both sides: NeoForge's custom-payload codec looks codecs up by the payload's class across every ecosystem (the
		// merged base of Forbric 0.4 and earlier had this splice built in, its builder's PASS 4b). Pre-Mixin, so the post-Mixin
		// codec arbitration finds the seam around fabric-networking's wrap.
		if (net.forbric.kernel.transform.CustomPayloadInteropSeam.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.CustomPayloadInteropSeam(path -> {
				try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
					return in == null ? null : in.readAllBytes();
				} catch (java.io.IOException unreadable) {
					Caught.warn(unreadable, "[Forbric/Boot] could not read game class %s for the custom-payload codec seam;"
							+ " it reads as absent", path);
					return null;
				}
			}));
		}
		chain.register(TransformPhase.COREMOD, new ForbricMergedBaseCompatTransformer());
		// Both sides: a game local whose variable-table range starts after reads of it starts at its declaring store again,
		// so a mod's @Local / @ModifyVariable finds it where vanilla's compiler had it in scope. On NeoForge's base the one
		// such local is left by the kernel's own NativeTagTrackerSourceInjector rewrite of EntityFluidInteraction. Just
		// before the early returns, which split tails by those ranges.
		if (net.forbric.kernel.transform.LocalRanges.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.LocalRanges(), Integer.MAX_VALUE - 1);
		} else {
			ForbricLog.warn("[Forbric/LocalRanges] -D%s=off — a game local whose range the kernel's chain starts after its "
					+ "reads (EntityFluidInteraction, after the tag-tracker rewrite) stays out of scope there for a mod's @Local", net.forbric.kernel.transform.LocalRanges.PROPERTY);
		}
		// Both sides: the early returns the carriers' decompile-recompile folded into each method's last return, so a
		// Fabric mod's TAIL handler runs only where vanilla's does (TaCZ's Camera.update hook ran on the title screen,
		// issue #31). LAST in the coremod phase (the sort index): every kernel injector still matches the folded
		// shape it was written against, and a method one of them edited at its tail has no frame-only tail run and
		// is left folded — its hook keeps running on every path.
		if (net.forbric.kernel.transform.VanillaEarlyReturns.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.VanillaEarlyReturns(), Integer.MAX_VALUE);
		} else {
			ForbricLog.warn("[Forbric/EarlyReturns] -D%s=off — the base's methods keep their early returns folded into the "
					+ "tail, and a Fabric mod's TAIL handler also runs where vanilla's body returned early",
					net.forbric.kernel.transform.VanillaEarlyReturns.PROPERTY);
		}
		// Both sides: vanilla-descriptor twins beside the fields NeoForge re-typed (RangedBow/CrossbowAttackGoal.mob,
		// AttributeSupplier$Builder.builder), so a vanilla-compiled reader and fabric-object-builder's accessor bind.
		if (net.forbric.kernel.transform.WidenedFieldTwinInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.WidenedFieldTwinInjector());
		} else {
			ForbricLog.warn("[Forbric/WidenedFields] -D%s=off — vanilla-compiled readers of the re-typed fields get "
					+ "NoSuchFieldError and fabric-object-builder's attribute accessor cannot bind",
					net.forbric.kernel.transform.WidenedFieldTwinInjector.PROPERTY);
		}

		// Both sides: vanilla's tooltip component order copied to the head of ItemStack.addDetailsToTooltip from the
		// base's own renamed body, so fabric-item-api-v1's bytecode scrape of it finds what it scrapes on Fabric.
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
					Caught.warn(unreadable, "[Forbric/Boot] could not read game class %s for the Snippet funnel;"
							+ " it reads as absent", path);
					return null;
				}
			}));
		} else {
			ForbricLog.warn("[Forbric/SnippetFunnel] -D%s=off — fabric-rendering-v1's snippet wrap is rejected by Mixin "
					+ "again (constructor arity)", net.forbric.kernel.transform.SnippetConstructorFunnel.PROPERTY);
		}

		// The title screen and F3 name Forbric's release (forbric-v0.3.1-beta), not NeoForge's or the launcher's
		// profile id; display only, the brand sent to servers stays NeoForge's.
		if (net.forbric.kernel.transform.ForbricBrandingInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForbricBrandingInjector());
		}

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
		// NeoForge syncs recipes by type, so one recipe whose serializer cannot encode it (Enchant Craft's) used to
		// disconnect every player on join once any mod asked for crafting recipes; it is left out with a warning.
		if (net.forbric.kernel.transform.RecipeSyncFailSoftInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.RecipeSyncFailSoftInjector());
		}
		// Every caller in the game builds custom-payload codecs with NeoForge's overload, so a mod hooking vanilla's
		// CustomPacketPayload.codec was never called: Carpet's carpet:hello could not be encoded and a dedicated
		// server running it disconnected every player at login. Builds go through vanilla's overload again.
		if (net.forbric.kernel.transform.PayloadCodecFunnelInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.PayloadCodecFunnelInjector());
		}
		// …and a payload on a channel only another ecosystem negotiated is RECEIVED down vanilla's path, where its mod
		// listens, not by NeoForge's dispatcher, which disconnected Carpet's client on carpet:hello.
		if (net.forbric.kernel.transform.ForeignPayloadReceiveInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForeignPayloadReceiveInjector());
		}

		// The pause menu's mods button opened NeoForge's list, which is every mod NeoForge loaded and, on this
		// instance, a fraction of what is installed. It now opens the kernel's, which reads ModCatalog.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ModsButtonRedirector());

		// A Forge-family mod's own data/ reaches the server datapack repository ONLY through this hook: the kernel
		// leaves ModList.modFiles empty, so NeoForge's own mod-pack finder walks an empty list and adds nothing.
		chain.register(TransformPhase.COREMOD, new DataPackHookInjector());

		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ServerReloadListenerNamesInjector());
		java.util.function.Function<String, org.objectweb.asm.tree.ClassNode> gameDeclarations = internal -> {
			try (var input = loader.getGameResourceAsStream(internal.replace('.', '/') + ".class")) {
				if (input == null) return null;
				var declaration = new org.objectweb.asm.tree.ClassNode();
				new org.objectweb.asm.ClassReader(input.readAllBytes()).accept(declaration, 0); return declaration;
			} catch (java.io.IOException absent) {
				Caught.warn(absent, "[Forbric/Boot] could not read game class %s for its worker declarations;"
						+ " it reads as absent", internal);
				return null;
			}
		};
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.RegistryElementCallbackInjector(gameDeclarations));
		// Where the native method clears a field and the carrier removes only a computed subset of its keys, a Map.clear()
		// on a view of that subset goes back in, so a guest's injector on the native clear lands and runs as natively.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ScopedClearRelayInjector());
		// The creative tab order NeoForge publishes, with a Fabric mod's tabs placed by the Fabric pager's rule.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.CreativeTabOrderInjector());
		// A config watch's handler runs only for a change made after the watch: the carriers' own configs keep config/
		// watched, so a config file the tracker creates there afterwards reached its new watch as a "change".
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.StaleWatchEventInjector());
		// canGlide asks the equipment, as the native game does, while the carrier's cached gliding attribute lags behind it.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.GlideFreshEquipmentInjector());
		// Carrier callback seams are available to every structurally compatible guest mixin.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.BlockSoundQueryInjector());
		if (side == Side.CLIENT) chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.HudContextQueryInjector());

		// NeoForge's disconnect-time revert (RegistryManager.revertToFrozen) puts back the kernel's own pre-connection
		// snapshot, which it captures at the head of NeoForge's applySnapshot and of every caller of a mod's remap
		// contract (fabric-api's client registry sync), whichever runs first.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.RegistryRevertInjector());

		// A Fabric mod's registry reads its data where native Fabric reads it. The base's Registries body is
		// NeoForge's, which prefixes the namespace itself; Fabric prefixes in a return-value mixin instead, and
		// WorldWeaver's own mixin hands back the body's answer ahead of it — so here its world presets and biome
		// data were read from data/<ns>/wover/wover/…, which nothing ships, and loaded empty.
		if (net.forbric.kernel.transform.RegistryDirectoryOwnerInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.RegistryDirectoryOwnerInjector());
		} else {
			ForbricLog.warn("[Forbric/Registries] -D%s=off — every registry reads NeoForge's directory, "
					+ "and WorldWeaver's world presets and biome data load empty",
					net.forbric.kernel.transform.RegistryDirectoryOwnerInjector.PROPERTY);
		}

		// The kernel owns the single registry freeze, and NeoForge's lifecycle puts it at ClientModLoader.begin() —
		// before `new Minecraft(...)`, where Fabric's is after it. A guest mixin that waits for "the real freeze"
		// therefore wakes its mod's <clinit> while Minecraft.getInstance() is still null, and fabric-api's
		// key-mapping registry read that instance to reject registration that is too LATE. Flashback died there.
		chain.register(TransformPhase.COREMOD,
				new net.forbric.kernel.transform.EarlyKeyMappingRegistrationInjector());
		// The same freeze, seen by a Fabric mod's own injectors on BuiltInRegistries.freeze(): with fabric-api they run
		// after every Fabric main on Fabric, and here they ran in Bootstrap. Create Fly's TAIL injector then created
		// its registries in a frozen root and the game could not start (issue #52). The hooks this adds are where
		// FabricFreezeHookMixinAdapter moves those injectors and where KernelLifecycle calls them.
		if (net.forbric.kernel.transform.FabricFreezePointInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricFreezePointInjector());
		} else {
			ForbricLog.warn("[Forbric/RegistrySync] -D%s=off — a Fabric mod's injectors on BuiltInRegistries.freeze() run in "
					+ "Bootstrap, before every Fabric main, even with fabric-api installed (Create Fly cannot start)",
					net.forbric.kernel.transform.FabricFreezePointInjector.PROPERTY);
		}

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

		// Inert unless -Dforbric.clientSmoke=true. It is what lets gate-m9 run a client unattended: enter a
		// world, live in it, disconnect and stop, so the gate waits for an outcome instead of a timeout.
		chain.register(TransformPhase.COREMOD, new ClientSmokeTickInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.CompatibilityPromptTickInjector());
		// Vanilla's spawn-position calls go back into the spawner, natural and summon paths, for Fabric mixins that
		// redirect them (architectury).
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.SpawnPositionCallsInjector(path -> {
			try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException unreadable) {
				Caught.warn(unreadable, "[Forbric/Boot] could not read game class %s for the spawn-position calls;"
						+ " it reads as absent", path);
				return null;
			}
		}));
		// NeoForge's ItemStack.useOn makes its Item.useOn calls in CommonHooks.onPlaceItemIntoWorld and a lambda; both go
		// through one ItemStack relay that Fabric's ItemEvents.USE_ON wraps (MixinRelocatedCall moves the injector).
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ItemUseOnInjector());
		// A Fabric or MinecraftForge mod's fluid has no NeoForge type; it gets the one its fluid tags imply.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ForeignFluidTypeInjector());
		// …and a tag a Fabric mod gave a fluid behaviour has that behaviour's fluid type where NeoForge's
		// EntityFluidInteraction turns tags into types, instead of the IllegalArgumentException fabric-api hit every tick.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricFluidBehaviorInjector());
		// A body that breathes where its eyes are (water breathing, a drowned, lava, a fluid it may breathe in) refills its air
		// as in vanilla; NeoForge's breathing hook held it, and its breathe event now starts from vanilla's refill.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.VanillaAirRefillInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.UntrackedFluidEyeQueryInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.PredicateGetterResultRecorder());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.mixin.NativeTagTrackerSourceInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.mixin.NativeTagSourceFacadeInjector());
		// A base method that asks one fluid both families' type (Fluid.entityInside, BucketItem.playEmptySound) asks each
		// family only the type it gave the fluid: where the kernel's tag-derived stand-in would answer, that family's empty
		// type does — a NeoForge or Fabric fluid in minecraft:water no longer puts out fire by MinecraftForge's water type.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.OwnFluidTypeClausesInjector());
		// NeoForge's empty fluid type gets no tracker: a fluid the kernel types empty (a Fabric fluid in no tag) is no fluid
		// to NeoForge's entity code, as on Fabric. After the native tag-tracker source, which proves the walk as it is.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.EmptyFluidTypeTrackerInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.mixin.CrossHostPredicateIslandInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.AxeStripCallbacksInjector());
		// Vanilla's pre-armour read of actuallyHurt's damage goes back where vanilla reads it, so a Fabric mod rewriting
		// the damage there (TaCZ) rewrites what NeoForge applies instead of throwing on every hit.
		if (net.forbric.kernel.transform.VanillaDamageReadInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.VanillaDamageReadInjector());
		}
		// fabric-api's class tweaker injects FabricCreativeModeInventoryScreen into the creative screen, and the mixin that
		// implements it is pinned (MergedBaseMixinCompat), so every call threw AssertionError — owo-lib makes one the
		// moment the creative inventory opens. The screen answers it from NeoForge's pager instead. Client only: a
		// dedicated server never loads the screen, so it carries no anchor for it.
		if (side == Side.CLIENT) {
			java.util.function.Function<String, byte[]> gameClass = name -> {
				try (var in = loader.getGameResourceAsStream(name + ".class")) { return in == null ? null : in.readAllBytes(); }
				catch (java.io.IOException unavailable) {
					Caught.warn(unavailable, "[Forbric/Boot] could not read game class %s; it reads as absent", name);
					return null;
				}
			};
			java.util.function.BooleanSupplier pinned = () -> net.forbric.kernel.mixin.FabricCreativePagerMixinAdapter.enabled()
					|| net.forbric.kernel.mixin.MergedBaseMixinCompat.contractSuppressed(
							"net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen",
							net.forbric.kernel.transform.CreativePagerBridgeInjector.API);
			if (net.forbric.kernel.transform.CreativePagerBridgeInjector.enabled()) {
				chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.CreativePagerBridgeInjector(gameClass, pinned));
			} else if (pinned.getAsBoolean() && gameClass.apply(net.forbric.kernel.transform.CreativePagerBridgeInjector.API) != null) {
				ForbricLog.warn("[Forbric/CreativePager] -D%s=off — FabricCreativeModeInventoryScreen has nothing behind it on "
						+ "the creative screen: a guest mixin relying on it (owo-lib's per-page tab memory) is left out unless "
						+ "-Dforbric.pinnedContracts=off keeps it, and any call through it throws AssertionError",
						net.forbric.kernel.transform.CreativePagerBridgeInjector.PROPERTY);
			}
		}
		// The game builds its fuels from NeoForge's data map; Fabric's fuel events run on that builder too.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.NativeConstructorFacadeRestorer(path -> {
			try (var in = loader.getGameResourceAsStream(path)) { return in == null ? null : in.readAllBytes(); }
			catch (java.io.IOException missing) {
				Caught.warn(missing, "[Forbric/Boot] could not read game class %s for the native constructor facades;"
						+ " it reads as absent", path);
				return null;
			}
		}));
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricFuelValuesInjector());
		// NeoForge's "Missing FluidModel" check runs inside the bake Fabric wraps, before Fabric adds its fluid models.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FabricFluidModelsInjector());
		// NeoForge's composter reads only its compostables data map; on a miss it asks vanilla's map too (what a
		// Fabric mod added after bootstrap), through vanilla-shaped calls a Fabric wrap such as BCLib's binds to.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.CompostablesFallbackInjector());
		// Those data-map lookups (and the oxidation, waxing and stripping ones) end in Holder.Reference.getData, which
		// threw for a value not registered yet; it answers "no data" there, so they fall back to vanilla's maps.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.UnboundHolderDataInjector());
		// NeoForge's tooltip registration event goes to each mod on its own, not through ModLoader's aborting fan-out.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.NeoTooltipAppendersInjector());
		// NeoForge's coremods never run under the kernel; NativeCoremodParity does their rewrites after Mixin. These
		// are the parts that must come before it: the flower pot's constructor storing its plant; the biome modifier
		// pass starting from the biome's current climate, and the biome's getters yielding to a later replacement.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.FlowerPotRepairInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.BiomeInfoRebaseInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.BiomeLateWriteInjector());
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
		//
		// The exit hook is not the only one. Every hook the chain splices into the game is read from the kernel jar at
		// its first use, and gate M22 measured two more after the jar was truncated: an interop hook at the first
		// lava flow (a server crash report) and KernelRegistryRevert on leaving a world. So the whole jar is defined
		// now, while it is readable; the exit hook is still initialized by name below, as before.
		KernelJarPreload.run(KernelBoot.class);
		try {
			Class.forName(ExitHookInjector.HOOK_OWNER.replace('/', '.'), true, KernelBoot.class.getClassLoader());
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Boot] could not preload the exit hook — a quit will still work, but if the "
					+ "kernel jar becomes unreadable before then it will end as a crash report instead");
		}

		// Each family's ModList.isLoaded can only see its own family's mods, and that answer is a compatibility
		// branch far more often than a display string — a wrong "no" disables an integration in silence.
		chain.register(TransformPhase.COREMOD, new ForeignModPresenceInjector());
		// The same wall one step further: a library that finds its users by reading [modproperties] out of its own
		// family's ModList never meets a Fabric mod, which is in no such list. In a class that does both, ModList
		// also answers with the Fabric mods that declare something (CrossEcosystemDeclarations); nowhere else.
		if (CrossEcosystemDeclarations.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.DeclarationReaderModListInjector());
		} else {
			ForbricLog.warn("[Forbric/Declarations] -D%s=off — a Forge-family library reading [modproperties] out of "
					+ "ModList meets no Fabric mod, and a Fabric mod reading entrypoints meets no Forge-family "
					+ "declaration (a Fabric mod's Sodium options page is missing next to a NeoForge Sodium)",
					CrossEcosystemDeclarations.SWITCH);
		}
		// A Fabric "porting layer" ships its own net.neoforged.* so Fabric mods can use that API; under Forbric the
		// carrier's copy wins, and the port's own compiled call sites then meet an API it was not built against.
		// PortingLayerAudit reports every such skew; this adapts the one that is fatal.
		chain.register(TransformPhase.COREMOD, new ConfigApiAbiInjector(name -> readDeclaration(loader, name)));

		// Both sides: route ReloadableServerRegistries' two loot seams through the kernel so fabric-loot-api-v3's
		// LootTableEvents fire from NeoForge's own LootTableLoadEvent point — its mixin cannot fit NeoForge's base.
		if (net.forbric.kernel.transform.LootTableEventBridgeInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.LootTableEventBridgeInjector());
		} else {
			ForbricLog.warn("[Forbric/LootBridge] -D%s=off — Fabric LootTableEvents.REPLACE/MODIFY/ALL_LOADED never fire; "
					+ "loot tables load exactly as NeoForge returns them",
					net.forbric.kernel.transform.LootTableEventBridgeInjector.PROPERTY);
		}

		// Trim guest mixins to the injectors that fit: fabric-model-loading-api-v1's ModelManagerMixin (client;
		// NeoForge replaced CuboidModel.fromStream at one site), so ModelLoadingPlugins dispatch instead of the whole
		// mixin being pinned; and fabric-item-api-v1's ItemStackMixin (both sides), whose tooltip injectors the kernel
		// replaces from NeoForge's appenders. Guest MIXIN classes pass through this chain via getPreMixinClassBytes.
		if (net.forbric.kernel.transform.GuestInjectorPruner.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.GuestInjectorPruner());
		} else {
			ForbricLog.warn("[Forbric/GuestInjectorPruner] -D%s=off — ModelManagerMixin is pinned whole again; Fabric "
					+ "ModelLoadingPlugins are registered and never called, and Fabric component tooltip providers show only "
					+ "above the item id in advanced tooltips",
					net.forbric.kernel.transform.GuestInjectorPruner.PROPERTY);
		}

		// Client only: NeoForge's model deserializer throws "Unknown loader" for every loader it did not register,
		// BEFORE the vanilla cuboid deserializer that MinecraftForge's geometry loaders and fusion's model hook read,
		// and it never reads Fabric's fabric:type at all (the two injectors that dispatched it are the pair pruned
		// above). One call ahead of NeoForge's dispatch lets each format's owner parse it. Matches only
		// UnbakedModelParser$Deserializer, which a dedicated server never loads.
		if (net.forbric.kernel.transform.ModelFormatFunnelInjector.enabled()) {
			chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ModelFormatFunnelInjector());
		} else {
			ForbricLog.warn("[Forbric/ModelFormats] -D%s=off — Fabric fabric:type model formats parse as plain models, "
					+ "and a MinecraftForge geometry loader or fusion model fails as \"Unknown loader\"",
					net.forbric.kernel.transform.ModelFormatFunnelInjector.PROPERTY);
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
		// Declared configuration protocols are independent of network-channel arbitration.
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.commonNetworkInterop", "on"))) {
			// Fabric's payload receivers are found by the public PacketSender they implement, through their superclasses.
			chain.register(TransformPhase.COREMOD, new CommonNetworkInteropInjector(name -> {
				try (var in = loader.getGameResourceAsStream(name + ".class")) { return in == null ? null : in.readAllBytes(); }
				catch (java.io.IOException unavailable) { Caught.warn(unavailable, "[Forbric/Boot] could not read game class %s for the common-network interop; it reads as absent", name); return null; }
			}));
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

		// Two targets used to sit above this one and no longer do, because their reasons stopped being true:
		//   NeoForge ServerLifecycleHooks.runModifiers — "needs neoforge:biome_modifier datapack registry". The
		//     kernel declares it now, and the pass is guarded at its call site instead
		//     (guardNeoForgesWorldModifierPass), so a failure costs the modifiers rather than the boot.
		//   MonsterRoomFeature.place — "MONSTER_ROOM_MOBS datamap not yet loaded". Neutering the whole feature
		//     meant no dungeon, no spawner and no dungeon chest in EVERY world, for everyone, mods or no mods.
		//     NeoForge's own reload path loads the data maps, and the mob pick goes through
		//     KernelNeoWorldgen.randomMonsterRoomMob, which draws vanilla's mob while the map is NeoForge's default.
		// A neuter is a promise that the method cannot work here; both promises had expired.
		MethodBodyNeuter neuter = neuters(side);
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

		// Ordinary virtual method references expose their exact invocation for provenance-checked guest anchors.
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.LambdaInvocationThunkInjector());
		chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.ContextualDefaultMethodInjector(path -> {
			try (java.io.InputStream in = loader.getGameResourceAsStream(path)) { return in == null ? null : in.readAllBytes(); }
			catch (java.io.IOException unavailable) {
				Caught.warn(unavailable, "[Forbric/Boot] could not read game class %s for the contextual default "
						+ "methods; it reads as absent", path);
				return null;
			}
		}));

		// Extensions join the configured pipeline after the core probes and before final frame recomputation.
		net.forbric.api.ProtocolExtensions.forLoader(loader).registerTransformers(
				net.forbric.kernel.interop.protocol.ProtocolTransformAdapters.registry(chain));

		// LAST in the chain, because it has to see every edit the coremod phase made: a transformer that adds a
		// branch leaves a frame of its own, and the recomputation must be over the final shape. A mod compiled
		// against one ecosystem can name a superclass the base does not have in that hierarchy (MinecraftForge's
		// CapabilityProvider above Entity was the case on the merged base), and such a class fails VERIFICATION,
		// before any of its code runs, naming a type its author never wrote. See MergedBaseFrameRecomputer.
		chain.register(TransformPhase.FABRIC_BUILTIN,
				new net.forbric.kernel.access.RestoredAccessTransformer(accessWideners, forgeAts));
		chain.register(TransformPhase.FABRIC_BUILTIN, new MergedBaseFrameRecomputer(path -> {
			try (java.io.InputStream in = loader.getGameResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException unreadable) {
				Caught.warn(unreadable, "[Forbric/Boot] could not read game class %s for the frame recomputation;"
						+ " it reads as absent", path);
				return null;
			}
		}));

		TransformContext ctx = new TransformContext(side.envType, false, "named");
		// One summary, at the point where "never loaded" starts meaning something. The per-repair failures are
		// already loud where they happen and do not wait for this.
		chain.reportWhenLoaded(side.censusLandmark);
		loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, loader.contextFor(name, ctx)));

		Thread.currentThread().setContextClassLoader(loader);

		KernelLifecycle.bind(loader);
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
		// as names is ImmediateWindowHandler, which is not on this path. seedAll repeats both calls below, which are
		// idempotent.
		//
		// WHERE THE RACE ACTUALLY IS, because it is not where it looks: the transformer goes in inside
		// KernelMixinBootstrap.init, but gotoPhase(INIT)/gotoPhase(DEFAULT) there does NOT prepare configs. Mixin
		// prepares them, and constructs every IMixinConfigPlugin, on the FIRST class that passes through the
		// transformer — the first class the boot defines after the init call below, not the init call itself.
		// `initialize=false` is no help either: a class is transformed when it is DEFINED.
		PassiveSeeder.seedNeoForgePaths(loader, gameDir);
		// The mods dir is passed explicitly (not re-derived inside the seeder) because the LoadingModList seeded here
		// must describe the SAME jars this boot decided to load — see discoverForgeFamilyModJars above, which walks
		// exactly this directory. Two independent derivations of "where the mods are" is how they drift apart.
		PassiveSeeder.seedNeoForgeLoader(loader, gameDir, gameDir.resolve("mods"), side.api(), gameVersion);
		// NeoForge's FMLEnvironment is stateless, so nothing else needs seeding before Mixin: seeding it late could
		// only THROW, which is loud.

		// Fabric Loader's freeze(): every declared language adapter is constructed before Mixin starts, named by an
		// entrypoint or not. After the seeding above, so the Mods screen's rows exist for a failure to land on.
		KernelFabricEcosystem.constructLanguageAdapters();

		// Mixin LAST in the pipeline but FIRST in time: installed before anything defines a targeted class.
		//
		// Fabric first, Forge-family APPENDED. Within one environment Mixin selects by priority (the config's, then
		// each @Mixin's); registration order is only the tiebreak among equal priorities, where a later-registered
		// mixin applies AFTER an earlier one on the same target. Appending therefore leaves the Fabric-vs-Fabric
		// ordering exactly as the Fabric configs come in — Fabric Loader's own order, by mod id, unless
		// -Dforbric.fabricOrder=off — and makes the newly-introduced, least-proven set the OUTER wrapper around a
		// known-good stack rather than the inner one.
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
			ForbricLog.info("[Forbric/Mixin] mixin configs: %d Fabric + %d Forge-family (%d NeoForge) — %s",
					fabricConfigs.size(), forgeConfigs.size(),
					KernelForgeFamilyMixins.count(forgeMixinDecls, forgeConfigs, Ecosystem.NEOFORGE),
					described.isEmpty() ? "none kept" : String.join(", ", described));
		}
		KernelMixinBootstrap.init(loader, side.envType, mixinConfigs);
		reportMixinExtrasSource(loader);

		// AFTER Mixin, because half of what the audit reports is written during it. KernelGuestMixinAdapter
		// records a mixin that was written to attach to another mod and did not while Mixin parses each config,
		// and the audit -- which reads that list and shows it to the player -- used to run thirty lines earlier,
		// inside the seeder. It always read an empty list, so that section of the dialog had never once appeared.
		PassiveSeeder.reportDependencies();

		// Seed the minimum genuine-loader identity the base's patched <clinit>s read (no lifecycle). The
		// Dist must match the side — a client seeded as DEDICATED_SERVER makes NeoForge reject the local player's
		// integrated-server connection ("Server is still starting").
		PassiveSeeder.seedAll(loader, gameDir, side.api());
		FabricApiModuleLossAudit.report(side.api());
		FieldDriftAudit.report();
		// After Mixin has read every config (on registration, in KernelMixinBootstrap.init) and the adapter has dropped
		// what it drops from them: the lint reads each config as Mixin was served it, so a mixin the kernel removed is not
		// half of an overlap. Before writeEvidence, so its findings are in the first report.
		net.forbric.kernel.mixin.MixinOverlapLint.reportRegistered();
		AbiLinkAudit.report();
		// Evidence, not the end of loading: no mod has initialised yet, so this boundary may name what already
		// failed but must not be the one that says every mod finished loading.
		KernelLoadReport.writeEvidence();
		if (side.api().isClient()) net.forbric.kernel.ui.CompatibilityDecision.noteGameArguments(args);
		net.forbric.kernel.ui.CompatibilityDecision.requireContinuation(side.api().isClient());

		// NeoForge's GraphicsBootstrapper services: genuine FML runs them first of all, before its early window and long
		// before the game builds its own. Here, with Mixin up and the FML identities seeded (NeoForge's config already
		// says no early window is coming), and before any game class -- the window is made inside Minecraft.<init>.
		NeoGraphicsBootstrappers.run(loader, topLevelForgeFamilyJars,
				jar -> MultiLoaderArbiter.claimOf(jar).ownedBy(Ecosystem.NEOFORGE), gameArgs.toArray(new String[0]));

		// Fabric preLaunch entrypoints, after Mixin is up and before any game class loads (their contract).
		KernelFabricEcosystem.runPreLaunch();

		// The kernel's OWN game-side half, proven here rather than assumed: loaded through the finished pipeline,
		// checked to have landed on the game loader. Not before Mixin -- these classes should take exactly the path
		// every game class takes. And not before preLaunch, though it used to run right after Mixin: proving that
		// a class links resolves every type its public methods name, so it defined ~40 Minecraft classes
		// (MinecraftServer, Screen, Component, CompoundTag, Registry...) before any preLaunch entrypoint ran. A mod
		// that registers its mixin config from preLaunch, as Fabric allows (Mixins.addConfiguration), then found
		// its targets already defined: Mixin threw MixinTargetAlreadyLoadedException for Essential's
		// BlockableEventLoop mixin and the boot died. On Fabric nothing loads a game class before preLaunch has run.
		// It only reports, so running it later costs nothing but its place in the log. See KernelRuntimeClasses.
		KernelRuntimeClasses.verify(loader);

		Class<?> mainClass = Class.forName(side.entryClass, false, loader);
		if (lifecycleHook.missedRequiredExcision()) {
			throw new IllegalStateException("kernel refusing to boot: the genuine " + side.name().toLowerCase()
					+ "-loading lifecycle trigger was NOT redirected from the game base (its shape changed). "
					+ "See LifecycleHookInjector.");
		}
		// gate-m17-installer.sh:340 greps this line ("it opened the merged base") and still names the old wording; C5
		// changes its pattern to "game base". The wording follows the base: NeoForge's patched game, merged with nothing.
		ForbricLog.info("[Forbric/Boot] game base %s entry loaded through kernel loader; lifecycle redirected to "
				+ "the kernel — handing to vanilla boot", side.name().toLowerCase());

		Method main = mainClass.getMethod("main", String[].class);
		main.invoke(null, (Object) gameArgs.toArray(new String[0]));
	}

	/**
	 * Every method the kernel empties on {@code side}: built here, outside {@link #launch}, so a test can read the list
	 * KernelBoot really registers.
	 */
	static MethodBodyNeuter neuters(Side side) {
		MethodBodyNeuter neuter = new MethodBodyNeuter();
		addSideNeuters(side, neuter);
		return neuter;
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
		// → NPE). It used to be neutered for that; it is no longer, because RegistryRevertInjector REWRITES the
		// body to apply the kernel's own pre-connection snapshot (KernelRegistryRevert) — and this neuter, registered
		// after that injector, was emptying the rewritten body again. Do not add it back.

		String owner = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE);
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
			Caught.warn(e, "[Forbric/Boot] could not read version.json from %s; using the fallback game version %s",
					gameJar.getFileName(), FALLBACK_GAME_VERSION);
		}

		return FALLBACK_GAME_VERSION;
	}

	private static org.objectweb.asm.tree.ClassNode readDeclaration(ForbricClassLoader loader, String name) {
		try (var input = loader.getGameResourceAsStream(name.replace('.', '/') + ".class")) {
			if (input == null) return null;
			var node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(input.readAllBytes()).accept(node, 0); return node;
		} catch (java.io.IOException absent) {
			Caught.warn(absent, "[Forbric/Boot] could not read the declaration of %s; it reads as absent", name);
			return null;
		}
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
	 * <p>A jar declaring no loader manifest at all is a plain library: its claim is unowned and it stays unowned, along
	 * with the base, the runtime carriers and the MC libraries.
	 */
	private static Map<Path, LoaderProbePolicy.Family> probeFamilies(List<Path> fabricJars, List<Path> modJars) {
		Map<Path, LoaderProbePolicy.Family> families = new java.util.LinkedHashMap<>();

		for (List<Path> group : List.of(fabricJars, modJars)) {
			for (Path jar : group) {
				if (families.containsKey(jar)) continue;

				// A plain library stays unowned. A refused jar gets no probe family either, and never gets here: the
				// refused nested ones left modJars above, Fabric discovery skips them, and discovery yields no mod of
				// the refused ones in mods/.
				if (!(MultiLoaderArbiter.claimOf(jar) instanceof JarClaim.Owned owned)) continue;

				// One for one with the arbitrated ecosystem. It used to collapse both Forge families into one
				// constant, which told a NeoForge-only mod that traditional MinecraftForge's loader class exists.
				families.put(jar, LoaderProbePolicy.familyOf(owned.ecosystem()));
			}
		}
		return families;
	}

	/** The mod jars that declare more than one loader; each runs as the one {@code MultiLoaderArbiter} chose. */
	private static java.util.Set<Path> universalJars(List<Path> fabricJars, List<Path> modJars) {
		java.util.Set<Path> universal = new java.util.LinkedHashSet<>();
		for (List<Path> group : List.of(fabricJars, modJars)) {
			for (Path jar : group) {
				if (MultiLoaderArbiter.loadersOf(jar).size() > 1
						&& MultiLoaderArbiter.claimOf(jar) instanceof JarClaim.Owned) {
					universal.add(jar);
				}
			}
		}
		return universal;
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
			Caught.expected(absent);
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
	 * <p>Names are Mojmap and the base runs Mojmap, so the directives are used as parsed — no remap step.
	 * Best-effort per jar: one unreadable AT file must not stop the others.
	 *
	 * <h2>The carriers' own files, which is the larger half</h2>
	 *
	 * <p>{@code carrierJars} are read for the same files, and they are not an afterthought. NeoForge ships an access
	 * transformer that widens the GAME for every NeoForge mod, and the genuine loader applies it before anything else
	 * runs; a base whose bytes do not already carry a widening gets it here. Re-applying a widening that is already in
	 * place is a no-op.
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
				Caught.warn(t, "[Forbric/AT] could not read access transformers from %s", jar.getFileName());
			}
		}

		if (directives.isEmpty()) return null;

		// "of them from the runtime carriers" is what gate-m21 greps; it stays until C5 changes the line and the gate together.
		ForbricLog.info("[Forbric/AT] applying %d Forge-family access-transformer directive(s) from %d jar(s), %d of "
				+ "them from the runtime carriers — the NeoForge carrier's file is what makes the game's own "
				+ "members reachable to every NeoForge mod",
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

	/**
	 * The Fabric half of discovery: reads, registers nothing. With a plan it consumes the plan's files, and the plan has
	 * already settled Fabric's nested rule over every ecosystem's mods. Without one
	 * ({@code -Dforbric.crossJarArbitration=off}) it walks {@code mods/} and Fabric {@code jars} only, so what the
	 * jars it does not read hard-require is handed over (the Forge-family mods, and the Fabric jars the Forge-family
	 * walk took out of their parents), and a nested Fabric mod one of them needs is not left out from under it
	 * ({@link net.forbric.kernel.fabric.NestedFabricRequirements#requiredOutsideFabricDiscovery}).
	 */
	static FabricModDiscovery scanFabricMods(Side side, Path gameDir, DuplicateModArbiter.Decision topLevelDupes,
			String gameVersion, NestedCandidatePlan plan, List<Path> forgeFamilyJars, List<Path> jarJarChildren) {
		List<net.forbric.kernel.fabric.NestedFabricRequirements.Requirement> requiredElsewhere = plan != null ? List.of()
				: net.forbric.kernel.fabric.NestedFabricRequirements.requiredOutsideFabricDiscovery(forgeFamilyJars,
						jarJarChildren, side.api());
		return KernelFabricEcosystem.scan(side.envType, gameDir, topLevelDupes, gameVersion, requiredElsewhere);
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
				Caught.warn(e, "[Forbric/Boot] could not read JarJar children of %s", modJar.getFileName());
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
			Caught.warn(e, "[Forbric/Boot] could not read JarJar metadata from %s; its nested jars are matched by file "
					+ "name", zip.getName());
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
			Caught.expected(unparseable);
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
	record ForgeFamilyMods(List<Path> jars, List<KernelForgeFamilyMixins.ForgeMixinConfig> mixinConfigs) {
	}

	/** The mixin configs declared by JarJar-extracted nested jars. Same pass, applied to the children. */
	/** Publish the selected nested owners before Fabric creates presence aliases for them. */
	static void publishNestedPresence(List<Path> nested) {
		List<DiscoveredMod> mods = new ArrayList<>(ModPresence.forgeFamilyMods());
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		for (DiscoveredMod mod : mods) seen.add(mod.getId());
		mods.addAll(PassiveSeeder.arbitratedNestedForgeFamilyMods(nested, seen));
		ModPresence.publishForgeFamily(mods);
	}

	static List<KernelForgeFamilyMixins.ForgeMixinConfig> discoverForgeMixinConfigs(List<Path> jars, String what) {
		List<KernelForgeFamilyMixins.ForgeMixinConfig> configs = new ArrayList<>();
		if (jars.isEmpty()) return configs;
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		List<Path> ignored = new ArrayList<>();
		for (Path jar : jars) {
			try {
				collectForgeFamily(discoverer, jar, ignored, configs);
			} catch (IOException | RuntimeException e) {
				Caught.warn(e, "could not inspect %s %s", what, jar.getFileName());
			}
		}
		return configs;
	}

	/** The superseded jars as URLs, sorted so the last-resort lookup order is stable run to run. */
	static List<URL> rescueUrls(DuplicateModArbiter.Decision dupes) {
		List<URL> urls = new ArrayList<>();
		// Only the other ecosystem's build of a mod that loaded; see Decision.rescueJars for what is kept out.
		for (Path jar : new java.util.TreeSet<>(dupes.rescueJars())) {
			try {
				urls.add(jar.toUri().toURL());
			} catch (Exception e) {
				Caught.warn(e, "[Forbric/DupeId] could not offer %s as a rescue jar", jar);
			}
		}
		return urls;
	}

	static ForgeFamilyMods discoverForgeFamilyModJars(Path modsDir,
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
				// One jar's problem stays that jar's: anything it throws costs it, not the boot. A manifest that
				// cannot be read does not even get here — discovery records it in MetadataFailures.
				try {
					collectForgeFamily(discoverer, jar, jars, configs);
				} catch (IOException | RuntimeException e) {
					Caught.warn(e, "could not inspect mod jar %s", jar.getFileName());
				}
			}
		} catch (IOException e) {
			Caught.warn(e, "could not list mods dir %s", modsDir);
		}
		return new ForgeFamilyMods(jars, configs);
	}

	/**
	 * Records {@code jar} as Forge-family (if it is) and appends the mixin configs it declares.
	 *
	 * <p>De-duplicated per jar: {@code ForgeMetadataMapper} copies one manifest's config list into EVERY
	 * {@code DiscoveredMod} that manifest declares, so a toml with three {@code [[mods]]} yields the same list three
	 * times. Cross-jar de-duplication and arbitration happen later, in {@link KernelForgeFamilyMixins}.
	 *
	 * <p>Only {@code neoforge.mods.toml} is parsed here. A MinecraftForge {@code mods.toml} is not read for mixin
	 * requirements: whether a jar's MinecraftForge half refuses the jar or is set aside is decided upstream, by
	 * {@code LoaderHalfJudge} alone, and none of its mixin configs is collected for anyone.
	 */
	private static void collectForgeFamily(ForbricModDiscoverer discoverer, Path jar, List<Path> jars,
			List<KernelForgeFamilyMixins.ForgeMixinConfig> configs) throws IOException {
		boolean forgeFamily = false;
		java.util.Map<Ecosystem, net.forbric.kernel.metadata.forge.ForgeModsToml> tomls = new java.util.HashMap<>();
		try (java.util.jar.JarFile zip = new java.util.jar.JarFile(jar.toFile())) {
			var entry = zip.getJarEntry(ForbricModDiscoverer.NEOFORGE_MANIFEST);
			if (entry != null) try (var in = zip.getInputStream(entry)) {
				tomls.put(Ecosystem.NEOFORGE, net.forbric.kernel.metadata.forge.ModsTomlParser.parse(in));
			} catch (RuntimeException unreadable) {
				// Discovery below records this manifest's failure and yields no mods for its family.
				Caught.expected(unreadable);
			}
		}
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
			// NeoForge's mods only: discovery yields no MinecraftForge mod (Forbric no longer loads them; a Forge+Fabric jar
			// loads through Fabric's discovery), so a MinecraftForge mod's mixin configs are not collected for anyone.
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
							mod.getEcosystem(), tomls.containsKey(mod.getEcosystem())
									? tomls.get(mod.getEcosystem()).getMixinRequiredMods(config) : List.of()));
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
