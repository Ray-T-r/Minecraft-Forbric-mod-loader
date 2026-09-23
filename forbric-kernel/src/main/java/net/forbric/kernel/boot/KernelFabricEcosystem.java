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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;

import net.forbric.api.Side;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.fabric.FabricModDiscovery;
import net.forbric.kernel.mixin.MixinConfigOwners;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.fabric.KernelCustomValue;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.mixin.MergedBaseMixinCompat;
import net.forbric.kernel.mixin.MixinConfigPolicy;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.fabric.KernelMetadataSupport;
import net.forbric.api.ModCatalog;

/**
 * Drives the Fabric ecosystem natively: discovery &rarr; {@link KernelFabricLoader} &rarr; entrypoints.
 *
 * <p>No Fabric Loader code runs. The kernel discovers {@code fabric.mod.json}s itself, builds the mod-facing
 * {@code FabricLoader} view, and invokes {@code ModInitializer.onInitialize()} at the point the registries are
 * writable — the same native registration window in which {@link KernelLifecycle} fires the Forge families'
 * {@code RegisterEvent}s. A Fabric mod's {@code onInitialize} calls {@code Registry.register(...)} directly, so
 * it must run inside that unfreeze/freeze span; running it before {@code Bootstrap.bootStrap} would touch
 * registries that do not exist yet, and after the freeze would throw.
 *
 * <p>The {@code main} entrypoints are guarded to run at most once per process, mirroring the old weld's shared
 * {@code ForbricFabricMains} guard: once the tri-in-one client/server paths both open registration windows, only
 * the first may run them.
 */
public final class KernelFabricEcosystem {
	/**
	 * The Fabric Loader API level the kernel implements. Vendored from the fabric-loader 0.19.3 API sources; the
	 * shipped ecosystem requires {@code >=0.18.4}. This is NOT a claim that Fabric Loader is present — no
	 * {@code net.fabricmc.loader.impl} class exists in this process.
	 */
	public static final String FABRIC_LOADER_API_LEVEL = "0.19.3";

	private static final AtomicBoolean MAINS_RAN = new AtomicBoolean();
	private static final AtomicBoolean CLIENTS_RAN = new AtomicBoolean();

	private static volatile KernelFabricLoader loader;

	private KernelFabricEcosystem() {
	}

	/** Whether any Fabric mod was discovered (so the rest of the kernel can skip Fabric work entirely). */
	public static boolean active() {
		return loader != null && !loader.getAllMods().isEmpty();
	}

	/**
	 * Discovers Fabric mods under {@code modsDir}, creates the process-wide {@code FabricLoader}, and returns the
	 * jars (mods + their extracted JiJ children) that must join the game class loader.
	 */
	public static List<Path> discover(EnvType envType, Path gameDir, String gameVersion, String[] launchArgs) {
		return discover(envType, gameDir, gameVersion, launchArgs, DuplicateModArbiter.Decision.none());
	}

	/**
	 * @param dupes cross-jar arbitration result; a Fabric jar another jar's copy of the same mod won is skipped
	 *              ENTIRELY — not merely left unregistered, as a universal jar is. It must not reach the classpath
	 *              either, or its classes still shadow the winner's (first-URL-wins) and its mixin configs still
	 *              apply.
	 */
	public static List<Path> discover(EnvType envType, Path gameDir, String gameVersion, String[] launchArgs,
			DuplicateModArbiter.Decision dupes) {
		return build(scan(envType, gameDir, dupes), envType, gameDir, gameVersion, launchArgs, dupes);
	}

	/**
	 * The FIRST half: walk {@code mods/}, extract the JiJ children, build the containers — and register nothing.
	 *
	 * <p>Split from {@link #build} so the nested jars of BOTH families are known before either family loads one.
	 * Cross-ecosystem arbitration cannot happen any earlier: the Forge family's nested jars come out of
	 * {@code KernelBoot.extractForgeFamilyJarJar} and Fabric's come out of here, so until both have run, "two jars
	 * claim this id" is a question with only half its evidence. Registering here as well would settle the question
	 * by whichever walk happened to run first, which is the ordering accident this split exists to remove.
	 */
	public static FabricModDiscovery scan(EnvType envType, Path gameDir, DuplicateModArbiter.Decision dupes) {
		Path cacheDir = gameDir.resolve(".forbric-kernel").resolve("jij");
		FabricModDiscovery discovery = new FabricModDiscovery(envType, cacheDir);
		discovery.setSkip(dupes::suppressed);
		discovery.discover(gameDir.resolve("mods"));
		return discovery;
	}

	/**
	 * The SECOND half: build the loader and register what {@code dupes} did not suppress.
	 *
	 * @param dupes the FINAL decision, i.e. after {@code DuplicateModArbiter.arbitrateNested} — a nested jar that
	 *              lost must not be registered AND must not reach the classpath, or its classes still shadow the
	 *              winner's and its mixin configs still apply. That is the whole defect for a mod like Xaero's
	 *              xaerolib: its losing half's mixins stayed live and called into the half-built winner.
	 */
	public static List<Path> build(FabricModDiscovery discovery, EnvType envType, Path gameDir, String gameVersion,
			String[] launchArgs, DuplicateModArbiter.Decision dupes) {
		return build(discovery, envType, gameDir, gameVersion, launchArgs, dupes, null);
	}

	public static List<Path> build(FabricModDiscovery discovery, EnvType envType, Path gameDir, String gameVersion,
			String[] launchArgs, DuplicateModArbiter.Decision dupes, Path gameJar) {
		KernelFabricLoader fabric = KernelFabricLoader.create(envType, gameDir, gameDir.resolve("config"),
				launchArgs, gameVersion);

		fabric.register(new KernelModContainer(
				KernelModMetadata.builtin("minecraft", gameVersion, "Minecraft"), gameJar, null));
		fabric.register(new KernelModContainer(
				KernelModMetadata.builtin("java", String.valueOf(Runtime.version().feature()), "Java"), null, null));
		fabric.register(new KernelModContainer(
				KernelModMetadata.builtin("fabricloader", FABRIC_LOADER_API_LEVEL, "Fabric Loader (Forbric kernel)"),
				null, null));

		// A universal jar also ships a fabric.mod.json; register it as a Fabric mod only if Fabric OWNS the jar,
		// otherwise the same mod runs its Fabric entrypoints on top of the Forge/NeoForge @Mod that already claimed
		// it (see MultiLoaderArbiter). The jar stays on the classpath either way — the owning family needs its
		// classes; only the Fabric-side registration (and with it the entrypoints) is skipped.
		int suppressed = 0;
		int lostNested = 0;
		// In DEPENDENCY order, not discovery order. Registration order is the order FabricLoader hands entry
		// points back in, and therefore the order onInitialize runs in — so a mod whose jar sorted before a
		// library it requires initialised first and called that library before it was ready.
		for (KernelModContainer container : orderByDependency(discovery.getContainers())) {
			Path jar = container.getJar();
			if (jar != null && MultiLoaderArbiter.suppressedFor(jar, Ecosystem.FABRIC)) {
				suppressed++;
				continue;
			}
			// Cross-jar arbitration, which by now has seen the nested jars too. Unlike the universal-jar case
			// above, this jar does not stay on the classpath (see build's @param dupes) — the winner's copy is
			// the same library, and leaving the loser's would keep its platform mixins live.
			if (jar != null && dupes.suppressed(jar)) {
				lostNested++;
				continue;
			}
			fabric.register(container);
		}
		if (lostNested > 0) {
			ForbricLog.info("[Forbric/Fabric] skipped %d Fabric registration(s) whose mod id another jar won",
					lostNested);
		}
		if (suppressed > 0) {
			ForbricLog.info("[Forbric/Fabric] skipped %d Fabric registration(s) for jars a Forge family owns", suppressed);
		}

		// Presence aliases: a mod whose Fabric jar lost cross-jar arbitration is still HERE — the winner's jar is
		// 98–100% the same classes — but without this, FabricLoader.isModLoaded(id) answers false and a Fabric mod
		// that gates an integration on that check silently disables it. Register the identity, nothing else: no
		// entrypoints, no mixins, no assets, all of which the winner already provides.
		for (DuplicateModArbiter.Alias alias : dupes.aliasesFor(Ecosystem.FABRIC)) {
			fabric.register(new KernelModContainer(KernelModMetadata.builtin(alias.modId(), alias.version(),
					alias.modId(), foreignCustomValues(alias.modId())), null, null));
			ForbricLog.info("[Forbric/Fabric] presence alias '%s' %s — its Fabric jar lost arbitration, but the "
					+ "winning jar supplies the classes; isModLoaded now answers", alias.modId(), alias.version());
		}

		// The same identity problem across ECOSYSTEMS. A Fabric mod asking isModLoaded("jei") next to a NeoForge
		// JEI was told no, because each loader only ever knew its own family's mods; the answer is almost always a
		// compatibility branch, so a wrong no silently disables an integration that would have worked. Presence
		// only, exactly like the arbitration aliases above: identity, no entrypoints, no mixins, no assets — the
		// mod is really loaded, by the other family's lifecycle, which owns everything else about it.
		int foreign = 0;
		for (DiscoveredMod mod : ModPresence.forgeFamilyMods()) {
			if (mod.getId() == null || mod.getId().isBlank()) continue;
			if (fabric.getModContainer(mod.getId()).isPresent()) continue;
			fabric.register(new KernelModContainer(KernelModMetadata.builtin(mod.getId(),
					mod.getVersion() == null ? "0" : mod.getVersion(),
					mod.getDisplayName() == null ? mod.getId() : mod.getDisplayName(),
					customValuesOf(mod)), null, null));
			foreign++;
		}
		if (foreign > 0) {
			ForbricLog.info("[Forbric/Fabric] %d Forge-family mod(s) registered for presence only — a Fabric mod "
					+ "asking isModLoaded() about one of them now gets the truth instead of no", foreign);
		}

		fabric.freeze();
		loader = fabric;

		// The mirror image: what the Forge-family lists have to be seeded with so their mods can see these.
		// Built-ins and the jar-less aliases above are left out — NeoForge's list is built per JAR, and those
		// have no jar; "minecraft"/"java"/"fabricloader" are not mods a compatibility branch asks about anyway.
		List<DiscoveredMod> fabricMods = new ArrayList<>();
		for (ModContainer container : fabric.getAllMods()) {
			if (!(container instanceof KernelModContainer kernel) || kernel.getJar() == null) continue;
			String id = kernel.getMetadata().getId();
			// Minecraft has real resource roots, but remains a builtin rather than a foreign mod alias.
			if (id != null && java.util.Set.of("minecraft", "java", "fabricloader").contains(id)) continue;
			if (id == null || id.isBlank() || ModPresence.isLoaded(id)) continue;
			// The provides aliases ride along. FabricLoader resolves them itself, but the Forge-family lists and
			// ModPresence are built from THIS list, and every LibJF module is named through an alias.
			fabricMods.add(new DiscoveredMod(Ecosystem.FABRIC, id,
					String.valueOf(kernel.getMetadata().getVersion()), kernel.getMetadata().getName(),
					unifiedDependencies(kernel.getMetadata()), List.of(), null, kernel.getJar().toString())
					.withAliases(List.copyOf(kernel.getMetadata().getProvides())));
		}
		ModPresence.publishFabric(fabricMods);

		List<Path> jars = new ArrayList<>();
		for (Path jar : discovery.getClasspathJars()) {
			if (!dupes.suppressed(jar)) jars.add(jar);
		}
		ForbricLog.info("[Forbric/Fabric] discovered %d Fabric mod(s) in %d jar(s) (incl. nested)",
				discovery.getContainers().size(), jars.size());

		return jars;
	}

	/** Points entrypoint resolution at the transforming loader. Must precede any entrypoint invocation. */
	public static void bindGameLoader(ClassLoader gameLoader) {
		if (loader != null) loader.setGameLoader(gameLoader);
	}

	/**
	 * Every discovered Fabric mod's declared access widener ({@code .classtweaker}) file contents, in mod order.
	 *
	 * <p>Read from each mod's own jar. A declared-but-missing file is a mod packaging error: warn and skip rather
	 * than fail the launch, since the mixins that need it will fail loudly on their own.
	 */
	public static List<byte[]> accessWideners() {
		List<byte[]> files = new ArrayList<>();
		for (net.forbric.kernel.access.ClassTweakerTransformer.File file : accessWidenerFiles()) files.add(file.bytes());
		return files;
	}

	/** {@link #accessWideners()} with each file's jar name beside it, for the access census. */
	public static List<net.forbric.kernel.access.ClassTweakerTransformer.File> accessWidenerFiles() {
		if (loader == null) return List.of();

		List<net.forbric.kernel.access.ClassTweakerTransformer.File> files = new ArrayList<>();

		for (ModContainer mod : loader.getAllMods()) {
			if (!(mod instanceof KernelModContainer)) continue;

			KernelModContainer container = (KernelModContainer) mod;
			String path = container.getMetadata().getAccessWidener();
			if (path == null || path.isEmpty()) continue;

			try (java.util.jar.JarFile jar = new java.util.jar.JarFile(container.getJar().toFile())) {
				java.util.zip.ZipEntry entry = jar.getEntry(path);

				if (entry == null) {
					ForbricLog.warn("[Forbric/Access] %s declares accessWidener '%s' which is not in its jar",
							container.getMetadata().getId(), path);
					continue;
				}

				try (java.io.InputStream in = jar.getInputStream(entry)) {
					files.add(new net.forbric.kernel.access.ClassTweakerTransformer.File(
							container.getJar().getFileName().toString(), in.readAllBytes()));
				}
			} catch (Exception e) {
				ForbricLog.warn("[Forbric/Access] could not read accessWidener of %s: %s",
						container.getMetadata().getId(), String.valueOf(e));
			}
		}

		return files;
	}

	/**
	 * Every discovered Fabric mod's mixin configs that apply to the running side, in mod order.
	 *
	 * <p>A config declared {@code {"config": "...", "environment": "client"}} is dropped on a dedicated server —
	 * its mixins target client-only classes that do not exist here, and registering it would fail the whole config.
	 */
	public static List<MixinConfigOwners.Owned> mixinConfigs() {
		if (loader == null) return List.of();

		EnvType envType = loader.getEnvironmentType();
		List<MixinConfigOwners.Owned> configs = new ArrayList<>();

		for (ModContainer mod : loader.getAllMods()) {
			if (!(mod instanceof KernelModContainer)) continue;

			for (KernelModMetadata.MixinConfigDecl decl : ((KernelModContainer) mod).getMetadata().getMixinConfigs()) {
				if (!decl.environment().matches(envType)) continue;

				if (MixinConfigPolicy.isDisabled(decl.config())) {
					ForbricLog.warn("[Forbric/Mixin] mixin config %s DISABLED by -Dforbric.disableMixinConfigs — "
							+ "that module's mixins will not apply",
							MixinConfigOwners.describe(decl.config()));
					continue;
				}

				configs.add(new MixinConfigOwners.Owned(decl.config(), mod.getMetadata().getId(),
						Ecosystem.FABRIC));
			}
		}

		return configs;
	}

	/** Publishes the game object (the {@code MinecraftServer}) for {@code FabricLoader.getGameInstance()}. */
	public static void setGameInstance(Object gameInstance) {
		if (loader != null) loader.setGameInstance(gameInstance);
	}

	/**
	 * Runs the {@code preLaunch} entrypoints, before any game class is loaded. Per Fabric's contract these must
	 * not touch game classes; the kernel does not enforce that, but it does run them at the correct point.
	 */
	public static void runPreLaunch() {
		if (loader == null || !loader.hasEntrypoints("preLaunch")) return;

		for (EntrypointContainer<PreLaunchEntrypoint> c
				: loader.getEntrypointContainers("preLaunch", PreLaunchEntrypoint.class)) {
			String id = c.getProvider().getMetadata().getId();

			try {
				c.getEntrypoint().onPreLaunch();
				ForbricLog.info("[Forbric/Fabric] preLaunch entrypoint of %s", id);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Fabric] preLaunch entrypoint of " + id + " failed", t);
			}
		}
	}

	/**
	 * Runs every Fabric {@code main} entrypoint, plus the {@code server} one on a dedicated server, exactly once per
	 * process. Must be called with the registries unfrozen — this is where mods register content.
	 *
	 * <p>On a CLIENT this is called from {@code KernelLifecycle.onClientEntrypoints}, inside {@code Minecraft.<init>}
	 * and immediately before {@link #runClientEntrypoints()} — Fabric's own {@code Hooks.startClient} order, at
	 * Fabric's own point in the constructor. {@code Minecraft.getInstance()} is live there and {@code Options} is
	 * not yet built, which is the window mods are written against from both ends: keymapping registration reads
	 * {@code Minecraft.getInstance().options} and NPEs if the instance is null (run too early) or throws
	 * "GameOptions has already been initialised" (run too late). That hook reopens the registries and rebuilds what
	 * reopening invalidates, so "unfrozen" still holds — see {@code ClientEntrypointHookInjector}.
	 *
	 * <p>A dedicated server calls it from the pre-{@code Minecraft} registration window instead, because there is no
	 * {@code Minecraft} to wait for and Fabric's {@code startServer} runs {@code main} just as early.
	 *
	 * @return true if this call ran them, false if they had already run
	 */
	public static boolean runMainEntrypoints() {
		if (loader == null) return false;
		if (!MAINS_RAN.compareAndSet(false, true)) return false;

		EnvType envType = loader.getEnvironmentType();
		int main = invoke("main", ModInitializer.class, ModInitializer::onInitialize);

		if (envType == EnvType.CLIENT) {
			ForbricLog.info("[Forbric/Fabric] invoked %d Fabric main entrypoint(s) in the %s window", main,
					mainsRunInConstructor() ? "Minecraft.<init>" : "pre-Minecraft registration");
		} else {
			int server = invoke("server", DedicatedServerModInitializer.class,
					DedicatedServerModInitializer::onInitializeServer);
			ForbricLog.info("[Forbric/Fabric] invoked %d Fabric main entrypoint(s) + %d server entrypoint(s)",
					main, server);
		}
		return true;
	}

	/**
	 * Runs the Fabric {@code client} entrypoints, exactly once. Called from within {@code Minecraft.<init>} (after the
	 * singleton is set, before {@code Options} is built) by {@code ClientEntrypointHookInjector} — the window Fabric
	 * itself uses, so mods that touch {@code Minecraft.getInstance()} (keymappings, renderers) see a live instance.
	 *
	 * @return true if this call ran them, false if already run or off the client
	 */
	/**
	 * The containers in dependency order.
	 *
	 * <p>Registration order is the order FabricLoader hands entry points back in, and therefore the order
	 * {@code onInitialize} runs in. It was discovery order — jar file name, alphabetically — so a mod whose file
	 * sorted before a library it requires initialised first and called that library's API before the library had
	 * set itself up. The requirements are already parsed; they were simply never used for this.
	 *
	 * <p>Best effort: an order is an improvement, never a precondition, and losing it must not cost the pack its
	 * mods.
	 */
	private static List<KernelModContainer> orderByDependency(List<KernelModContainer> containers) {
		try {
			List<DiscoveredMod> known = new ArrayList<>(containers.size());
			for (KernelModContainer container : containers) {
				String id = container.getMetadata().getId();
				if (id == null || id.isBlank()) continue;
				known.add(new DiscoveredMod(Ecosystem.FABRIC, id,
						String.valueOf(container.getMetadata().getVersion()), container.getMetadata().getName(),
						unifiedDependencies(container.getMetadata()), List.of(), null, id)
						.withAliases(List.copyOf(container.getMetadata().getProvides())));
			}
			if (known.isEmpty()) return containers;

			List<KernelModContainer> sorted = ModConstructionOrder.sort(containers,
					c -> c.getMetadata().getId(), ModConstructionOrder.of(known));
			if (!sorted.equals(containers)) {
				ForbricLog.info("[Forbric/Order] %d Fabric mod(s) initialise in dependency order, not jar-file "
						+ "order (-Dforbric.modOrder=name to go back)", sorted.size());
			}
			return sorted;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Order] could not order Fabric mods by dependency; using discovery order",
					t);
			return containers;
		}
	}

	/**
	 * A Fabric mod's declared dependencies in the unified model.
	 *
	 * <p>These used to be {@code List.of()} — not because Fabric mods declare none, but because nobody filled them
	 * in. A {@link DiscoveredMod} reporting an empty dependency list is indistinguishable from one that genuinely
	 * has none, so {@code DependencyAudit} silently judged only the Forge families and nothing said so.
	 *
	 * <p>Only POSITIVE kinds cross over. A {@code breaks}/{@code conflicts} entry is a requirement that a mod be
	 * ABSENT, and carrying it here as a dependency would make the audit report "requires X — not installed" about
	 * a mod that must not be installed. {@link UnifiedDependency} has no negative sense yet, and inventing one
	 * that nothing evaluates is how this field came to lie in the first place.
	 *
	 * <p>Fabric declares no side or ordering axis, so both stay neutral rather than being guessed at from the
	 * mod's {@code environment} — that says where the MOD runs, not where its requirement applies.
	 */
	static List<UnifiedDependency> unifiedDependencies(ModMetadata metadata) {
		List<UnifiedDependency> out = new ArrayList<>();
		for (ModDependency dep : metadata.getDependencies()) {
			if (!dep.getKind().isPositive()) continue;
			out.add(new UnifiedDependency(dep.getModId(), constraintOf(dep), !dep.getKind().isSoft()));
		}
		return out;
	}

	/** The predicate as declared; the array form is OR-joined, which is what {@code VersionPredicate} reads. */
	private static String constraintOf(ModDependency dep) {
		if (dep instanceof KernelMetadataSupport.SimpleModDependency simple && !simple.getConstraints().isEmpty()) {
			return String.join(" || ", simple.getConstraints());
		}
		return "*";
	}

	/**
	 * The physical side this boot is running on, or {@code null} while the Fabric side has not been brought up.
	 *
	 * <p>There is no other authority for this in the kernel: the merged base carries the client classes even on a
	 * dedicated server, so "can I load Minecraft.class" answers the wrong question. Callers that would have to
	 * guess should treat {@code null} as "do not judge side-scoped things" rather than picking a side.
	 */
	public static Side physicalSide() {
		KernelFabricLoader current = loader;
		return current == null ? null : Side.parse(String.valueOf(current.getEnvironmentType()));
	}

	public static boolean runClientEntrypoints() {
		if (loader == null || loader.getEnvironmentType() != EnvType.CLIENT) return false;
		if (!CLIENTS_RAN.compareAndSet(false, true)) return false;

		int client = invoke("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
		ForbricLog.info("[Forbric/Fabric] invoked %d Fabric client entrypoint(s) (Minecraft.<init> window)", client);
		reportActiveRenderer();
		return true;
	}

	/**
	 * Names whichever mod ended up owning the Fabric Rendering API's single renderer slot.
	 *
	 * <p>That slot takes exactly one occupant — {@code RendererManager.registerRenderer} throws on a second — and
	 * on a normal Fabric instance Sodium takes it and Indigo stands down. Every party to that handover is
	 * cross-ecosystem here (a NeoForge Sodium declaring itself to a Fabric Indigo through the kernel's metadata),
	 * and when it goes wrong NOTHING says so at the time: the wrong renderer registers quietly and the first
	 * frame that draws a mesh-emitting model dies with {@code MutableQuadViewWrapper cannot be cast to
	 * MutableQuadViewImpl}, inside a mod, with no mention of a renderer. So the handover is stated out loud at the
	 * one moment both candidates have had their say.
	 *
	 * <p>Reflective, and silent when FRAPI is not installed — most instances have no renderer slot at all.
	 */
	private static void reportActiveRenderer() {
		try {
			if (loader == null || loader.getModContainer("fabric-renderer-api-v1").isEmpty()) return;
			ClassLoader mods = Thread.currentThread().getContextClassLoader();
			Class<?> manager = Class.forName("net.fabricmc.fabric.impl.client.renderer.RendererManager", false, mods);
			java.lang.reflect.Field active = manager.getDeclaredField("activeRenderer");
			active.setAccessible(true);
			Object renderer = active.get(null);
			if (renderer == null) {
				ForbricLog.warn("[Forbric/Fabric] the Fabric Rendering API has NO renderer registered — the next "
						+ "model that emits a mesh will die on \"Attempted to retrieve active rendering plug-in "
						+ "before one was registered\"");
				return;
			}
			ForbricLog.info("[Forbric/Fabric] the Fabric Rendering API renderer is %s — the one slot is taken, and "
					+ "whoever lost it must have stood down rather than registered", renderer.getClass().getName());
		} catch (ClassNotFoundException | NoSuchFieldException absent) {
			// A different FRAPI version keeps its renderer somewhere else; a diagnostic must not invent a finding.
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Fabric] could not read the active Fabric renderer", t);
		}
	}

	/** Whether the Fabric main entrypoints have already run. */
	public static boolean mainsAlreadyRan() {
		return MAINS_RAN.get();
	}

	/** The switch that puts the client's {@code main} entrypoints back in the pre-{@code Minecraft} window. */
	public static final String MAIN_WINDOW_SWITCH = "forbric.fabricMainInConstructor";

	/**
	 * Whether the client's {@code main} entrypoints run inside {@code Minecraft.<init>}, where Fabric runs them.
	 *
	 * <p>Fabric's own {@code Hooks.startClient} invokes {@code main} and then {@code client}, and its game patch
	 * inserts that call before the {@code Thread.currentThread()} in the constructor — after {@code instance = this}.
	 * So on Fabric a {@code main} entrypoint always sees a live {@code Minecraft.getInstance()}.
	 *
	 * <p>The kernel used to run them in its own pre-{@code Minecraft} registration window instead, because that
	 * window is where the registries are unfrozen. {@code getInstance()} is null there, and a mod that caches it —
	 * ClickCrystals holds it in a {@code static final} interface field read from the first line of its
	 * {@code onInitialize} — cached null for the rest of the process and took the game down inside the constructor
	 * with an NPE naming neither the loader nor the window. The client-entrypoint hook already reopens the
	 * registries and rebuilds what the reopen invalidates, so the two phases now run there together, in Fabric's
	 * order and at very nearly Fabric's instruction.
	 *
	 * <p>{@code -Dforbric.fabricMainInConstructor=off} puts them back in the pre-{@code Minecraft} window.
	 */
	public static boolean mainsRunInConstructor() {
		return !"off".equalsIgnoreCase(System.getProperty(MAIN_WINDOW_SWITCH, "on"));
	}

	/**
	 * Invokes one entrypoint key, isolating failures per mod: a mod whose {@code onInitialize} throws is reported
	 * and skipped rather than aborting the remaining mods' initialization (and with them the whole server boot).
	 */
	private static <T> int invoke(String key, Class<T> type, java.util.function.Consumer<T> action) {
		int count = 0;

		for (EntrypointContainer<T> c : loader.getEntrypointContainers(key, type)) {
			String id = c.getProvider().getMetadata().getId();

			try {
				T entrypoint = c.getEntrypoint();
				// With a NeoForge ModContainer active for THIS mod, because a Fabric mod can be holding the
				// NeoForge build of a multi-loader library: only one copy of a class exists, so the build the
				// nested-jar arbitration kept is the build every host gets. Without this, EntityCulling's
				// onInitializeClient asked tr7zw's TRansition to register a keybind, that build asked
				// ModLoadingContext for the active container, got NeoForge's "minecraft" fallback whose
				// getEventBus() is null by design, and threw out of its first line — losing the whole entrypoint.
				// The MOD's loader, not the kernel's: the runtime half and NeoForge itself are transform-loaded,
				// and the kernel's own boot classloader cannot see either of them.
				KernelForeignShimContext.with(entrypoint.getClass().getClassLoader(), id,
						() -> action.accept(entrypoint));
				count++;
				ForbricLog.info("[Forbric/Fabric] invoked %s entrypoint of %s", key, id);
				reportSwallowedFailure(key, id, entrypoint);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Fabric] " + key + " entrypoint of " + id + " failed", t);
				ModCatalog.mark(id, ModCatalog.Status.FAILED, "its " + key + " entrypoint threw");
			}
		}

		KernelForeignShimContext.report();
		return count;
	}

	/**
	 * Reports an initialization failure a mod caught and parked in one of its own fields instead of rethrowing.
	 *
	 * <p>{@code try { loadCommon(); loadClient(); } catch (Throwable t) { this.firstStageError = t; }} is a common
	 * shape, and it turns a loader problem into a lie: the entrypoint returns normally, the kernel logs it as
	 * invoked, and the mod then dies far away with something that names neither the cause nor the mod's own init.
	 * Xaero's World Map does exactly this — its swallowed failure surfaced a hundred ticks later as
	 * {@code "xaero.map.WorldMap.events" is null} inside {@code Minecraft.runTick}, with nothing in between.
	 *
	 * <p>So after a successful-looking entrypoint, any non-null {@code Throwable} field on the instance is surfaced
	 * once, at the point it actually happened. This reads fields the mod declared on itself; it changes nothing.
	 */
	private static void reportSwallowedFailure(String key, String id, Object entrypoint) {
		if (entrypoint == null) return;

		for (java.lang.reflect.Field field : entrypoint.getClass().getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
					|| !Throwable.class.isAssignableFrom(field.getType())) {
				continue;
			}
			try {
				field.setAccessible(true);
				Throwable parked = (Throwable) field.get(entrypoint);

				if (parked != null) {
					ForbricLog.warn("[Forbric/Fabric] " + key + " entrypoint of " + id + " returned normally but "
							+ "caught its own failure into " + field.getName() + " — the mod is only PARTLY "
							+ "initialised and will fail later somewhere unrelated", parked);
					ModCatalog.mark(id, ModCatalog.Status.DEGRADED,
							"its " + key + " entrypoint swallowed its own failure");
				}
			} catch (Throwable inaccessible) {
				// A mod that hides the field from reflection simply keeps its secret; this is diagnostics only.
			}
		}
	}

	/**
	 * A Forge-family mod's {@code [modproperties.<id>]} table, as Fabric custom values.
	 *
	 * <p>The two ecosystems spell the same declaration differently and each family's loader only ever exposed its
	 * own spelling, so a Fabric mod asking a NeoForge mod what it offers was told "nothing" — the answer a mod
	 * reads as "not installed", by a different route.
	 *
	 * <p>The measured case: Sodium's NeoForge build declares {@code "fabric-renderer-api-v1:contains_renderer" =
	 * true}, which is the ONLY way Indigo (fabric-renderer-indigo) learns that another rendering plug-in is
	 * present — {@code IndigoMixinConfigPlugin} walks {@code FabricLoader.getAllMods()} and calls
	 * {@code ModMetadata.containsCustomValue} on that exact key. Told nothing, Indigo registered ITSELF as the
	 * FRAPI renderer, so a connected-texture mod built its mesh with Indigo's encoding and Sodium's feature
	 * renderer then handed that mesh Sodium's own emitter: {@code MutableQuadViewWrapper cannot be cast to
	 * MutableQuadViewImpl}, every frame, the instant a CTM block was on screen.
	 *
	 * <p>The key names are not a coincidence to be exploited — the {@code modproperties} table is where the Forge
	 * family agreed cross-loader declarations live, and mods write Fabric's namespaced keys into it verbatim.
	 */
	private static Map<String, CustomValue> customValuesOf(DiscoveredMod mod) {
		Map<String, Object> properties = mod == null ? null : mod.getModProperties();
		if (properties == null || properties.isEmpty()) return Map.of();

		Map<String, CustomValue> values = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			values.put(entry.getKey(), KernelCustomValue.of(entry.getValue()));
		}
		ForbricLog.info("[Forbric/Fabric] %s's %d [modproperties] key(s) are now Fabric custom values %s — a Fabric "
				+ "mod asking this mod what it offers (Indigo asking Sodium whether a renderer is already here) "
				+ "reads the same declaration its own family would have written", mod.getId(), values.size(),
				values.keySet());
		return values;
	}

	/**
	 * The same table, found by mod id — for the arbitration aliases, where the losing FABRIC jar supplies the
	 * identity but the WINNING Forge-family jar supplies the declaration. Sodium arrives this way whenever both
	 * builds are installed, which is the configuration that crashed.
	 */
	private static Map<String, CustomValue> foreignCustomValues(String id) {
		if (id == null) return Map.of();
		for (DiscoveredMod mod : ModPresence.forgeFamilyMods()) {
			if (id.equals(mod.getId())) return customValuesOf(mod);
		}
		return Map.of();
	}
}
