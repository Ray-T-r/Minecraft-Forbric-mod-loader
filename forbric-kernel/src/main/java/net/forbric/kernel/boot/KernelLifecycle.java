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
import java.util.List;
import java.util.Map;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Side;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.transform.FabricFreezePointInjector;
import net.forbric.api.ModCatalog;

/**
 * The kernel-owned server lifecycle hook that runs where NeoForge's base calls the genuine
 * {@code ServerModLoader.load(...)}.
 *
 * <p>{@link net.forbric.kernel.transform.LifecycleHookInjector} rewrites that call (in
 * {@code net.minecraft.server.Main.main}, after {@code Bootstrap.bootStrap}) to invoke
 * {@link #onServerModLoading(boolean)} instead — so the kernel drives its native registration at exactly the
 * point NeoForge's lifecycle would have, with the game fully bootstrapped (registries created) but before the
 * server proper starts. No FancyModLoader discovery / module layer / mod sorting ever runs.
 *
 * <p>This is the M3 native-registration window (plan phase P9): register the ecosystems' baseline registries and
 * content into the one shared registry set, then let the single vanilla freeze stand. Everything is invoked
 * reflectively through the kernel's transforming loader so class identity matches the game.
 */
public final class KernelLifecycle {
	private static volatile ClassLoader gameLoader;
	private static volatile List<Path> modJars = List.of();
	private static volatile List<Path> runtimeJars = List.of();

	// The NeoForge baseline mod's bus + container, captured during registration so the client step can construct
	// ClientNeoForgeMod on the same bus and route the game's mod-bus events to it.
	private static volatile Object baselineBus;
	private static volatile Object baselineContainer;
	/** {@code -Dforbric.neoTooltipAppenders=off} leaves NeoForge's item tooltip appenders unbuilt, as the kernel used to. */
	public static final String NEO_TOOLTIP_APPENDERS = "forbric.neoTooltipAppenders";

	private KernelLifecycle() {
	}

	/** The game class identity shared by lifecycle callbacks and protocol extensions. */
	public static ClassLoader gameLoader() {
		return gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
	}

	/** Installed by the boot orchestrator before any injected lifecycle callback runs. */
	public static void bind(ClassLoader loader) {
		gameLoader = loader;
	}

	/** The Forge-family mod jars this boot loaded (top-level and extracted nested), for game-side helpers. */
	public static List<Path> modJars() {
		return modJars;
	}

	public static void setModJars(List<Path> jars) {
		modJars = jars == null ? List.of() : jars;
	}

	/** The ecosystem runtime jar (neoforge). It is that ecosystem's OWN mod file — FML scans it too. */
	public static void setRuntimeJars(List<Path> jars) {
		runtimeJars = jars == null ? List.of() : jars;
	}

	/**
	 * The kernel's own game-side jars, which carry its client assets.
	 *
	 * <p>One asset in particular: the Mods button's icon. A GUI sprite is resolved through the resource manager
	 * like any other, so a texture the kernel ships is only findable if the kernel's jar is a pack the repository
	 * knows about — which is the same mechanism every mod's assets already travel on, pointed at ourselves.
	 */
	public static void setKernelAssetJars(List<Path> jars) {
		kernelAssetJars = jars == null ? List.of() : jars;
	}

	private static volatile List<Path> kernelAssetJars = List.of();

	/**
	 * Invoked from {@code net.minecraft.server.Main.main} (redirected from {@code ServerModLoader.load}) after
	 * {@code Bootstrap.bootStrap}. Drives native ecosystem registration. {@code dedicated} is the original argument.
	 * Each entry point has exactly one trigger; the merged base's server entry carried both ecosystems' copies of the
	 * call, and a once-per-process guard for it went in Stage 4.
	 */
	public static void onServerModLoading(boolean dedicated) {
		try {
			driveNativeRegistration(Side.DEDICATED_SERVER);
		} catch (Throwable t) {
			// NeoForge's registration failed: what ServerModLoader.load does with its ModLoadingException before rethrowing
			// it (the crash-*-fml.txt report), then Main's own handler ("Failed to start the minecraft server").
			if (RegistrationStop.isStop(t)) RegistrationStop.asServerModLoaderDoes(gameLoader(), t);
			throw RegistrationStop.propagate(t);
		}
	}

	/**
	 * Invoked from {@code net.minecraft.client.main.Main.main} (redirected from {@code ClientModLoader.begin}) after
	 * {@code Bootstrap.validate} (which asserts {@code Bootstrap.bootStrap} already ran). Same native registration
	 * as the server — the ecosystems' baselines + content are side-independent; the Fabric ecosystem runs its
	 * {@code client} entrypoints rather than {@code server} ones, driven by {@code KernelFabricLoader}'s env type.
	 */
	public static void onClientModLoading() {
		// ClientModLoader.begin has no handler of its own: NeoForge's mod-loading error (RegistrationStop) leaves for Main's,
		// which writes the crash report, shows NeoForge's fatal-error window and exits, as natively.
		driveNativeRegistration(Side.CLIENT);
	}

	/**
	 * Invoked first thing in the client's {@code Main.logEarlyException}, vanilla's handler for the three steps
	 * {@code Main.main} opens with. Vanilla prints the throwable to stderr and exits (status 249, 252, 251) without it
	 * leaving {@code main}, so this is the one chance to put the error that ended the game into {@code latest.log}.
	 * Never throws; vanilla's print and exit follow. Inserted by
	 * {@link net.forbric.kernel.transform.LifecycleHookInjector}.
	 */
	public static void onEarlyStartupFailure(Throwable failure) {
		CompatibilityLaunchBoundary.reportEscaping(failure);
	}

	private static void driveNativeRegistration(Side side) {
		ClassLoader cl = gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
		ForbricLog.info("[Forbric/Lifecycle] kernel %s mod-loading window (native, no FancyModLoader) — "
				+ "registering ecosystem baselines", side.distName());
		// Step 0c (client only): load the carrier's own built-in translations. Their loader is called from
		// ClientModLoader.begin(), whose call site the kernel redirects here, so the table FMLTranslations
		// reads was never filled and every FML-side string — the branding line under the logo, the loading
		// screen's continue button — rendered as its raw key. The dedicated server has its own entry point
		// (LanguageHook.loadLanguagesOnServer, per world) and is not this window's business.
		if (side.isClient()) CarrierLanguages.loadBuiltins(cl);
		// Step 1: register NeoForge's baseline registries (neoforge:fluid_type, …) into the root. Correctly timed
		// now (post-Bootstrap), unlike the pre-Main attempt which tripped "Not bootstrapped".
		PassiveSeeder.seedNeoForgeRegistries(cl);
		// Step 1b: mark NeoForge's VANILLA_SYNC_REGISTRIES (item/block/fluid/recipe_serializer/…) as client-syncing.
		// NeoForge's ByteBufCodecs registry-ID sync path (getSyncableRegistryOrThrow → RegistryManager
		// .isNonSyncedBuiltInRegistry → Registry.doesSync()) throws "Cannot use ID syncing for non-synced built-in
		// registry: minecraft:item" when the vanilla registries carry doesSync()=false. NeoForgeRegistriesSetup
		// normally sets these; the kernel doesn't run that setup, so mark them here (BaseMappedRegistry.setSync(true)).
		// Without this, the player logs in but the clientbound update_recipes packet fails to encode → disconnect.
		// Prefer NeoForge's own modifyRegistries handler: it does the setSync pass the kernel used to hand-roll AND
		// the five addCallback wirings nothing replaced — including the one that mirrors synced AttachmentTypes into
		// neoforge:synced_attachment_types, without which a mod using them kicks the player on join.
		if (!PassiveSeeder.applyNeoForgeRegistryModifications(cl)) markVanillaRegistriesSynced(cl);
		// Step 2: construct both ecosystem baselines + fire RegisterEvent so default content (e.g. minecraft:empty
		// FluidType, default attributes) registers, and run the Fabric main + side entrypoints in the same window.
		registerNeoForgeContent(cl, side);
		// Step 2b (client only): construct ClientNeoForgeMod on the baseline bus, so the game's
		// ModLoader.postEvent(<client mod-bus event>) — fired from ClientHooks.initClientHooks during
		// Minecraft.<init> for reload listeners, entity renderers, sprite sources, client extensions — has NeoForge's
		// built-in client handlers to reach.
		if (side.isClient()) registerNeoForgeClientContent(cl);
		// Step 2b2 (BOTH sides): put the baseline container into ModList, so ModLoader.postEvent — NeoForge's only
		// fan-out for the mod-bus events it posts ITSELF — reaches NeoForge's own listeners, not just the mods'.
		// This ran as part of step 2b, i.e. client-only, on the reasoning that only the client posts mod-bus events
		// from game code. The dedicated server posts one too, and it is the one that matters most over a socket:
		// NetworkRegistry.setup() posts RegisterPayloadHandlersEvent, whose NeoForge-internal listener
		// (NetworkInitialization.register, wired in step 2c2 to the baseline bus) registers every neoforge:* payload
		// type. With the baseline absent from ModList that event fanned out to the mods alone, so no dedicated
		// server the kernel ever booted could ENCODE a NeoForge payload — the first real client to join was dropped
		// with "Failed to encode packet 'clientbound/minecraft:custom_payload' (neoforge:recipe_content)". Eleven
		// gates certified those servers because none of them ever connected a client, and singleplayer never encodes.
		publishNeoBaselineInModList(cl);
		// Step 2c: load the NeoForge config specs the baselines and the just-constructed mods registered. Listeners
		// read CLIENT/COMMON values early (e.g. TagConventionLogWarningClient on ServerStartingEvent when entering a
		// singleplayer world reads a CLIENT value) — an unloaded spec throws "Cannot get config value before config
		// is loaded". This ran CLIENT-ONLY, on the reasoning that it left the proven server path alone; what it
		// actually left alone was a dedicated server with NO STARTUP or COMMON config loaded at all. A mod that
		// keys anything off its own config then has nothing to read: Balm sets a mod's active config from its
		// ModConfigEvent.Loading listener, so with the event never fired Waystones' getActive() —
		// Objects.requireNonNull(...) — threw NPE out of setupDynamicRegistries and the server died before Done.
		// Only the CLIENT type is client-only; NeoForge loads STARTUP and COMMON on both sides, and SERVER is
		// loaded separately, per-world, by ServerLifecycleHooks.handleServerAboutToStart.
		loadEarlyConfigs(cl, side);
		// Step 2c2: wire NeoForge's OWN @EventBusSubscriber classes from its runtime jar. NeoForge ships as a mod and
		// FML scans its jar like any other; the kernel scanned only mod jars, so ~10 internal subscribers (network,
		// attachments, configuration tasks, model data, …) never fired. Must precede step 2d — the network setup posts
		// its Register*PayloadHandlersEvent to exactly these subscribers.
		KernelEventSubscribers.registerNeoForgeInternal(cl, runtimeJars, baselineBus, baselineContainer, side);
		// Step 3 USED TO BE HERE: registering mods' @EventBusSubscriber classes. It has moved INSIDE
		// registerNeoForgeContent, next to the constructors — see the comment at the new call site. Wiring them
		// here meant every registration-phase event had already been posted to nobody.
		// Step 3a: let mods declare their DATAPACK registries. These are not the registries RegisterEvent fills —
		// they are the per-world ones RegistryDataLoader builds from datapacks, and NeoForge collects them through
		// DataPackRegistryEvent.NewRegistry into DataPackRegistriesHooks. Nothing posted that event, so the list
		// stayed vanilla-only and lithostitched died the moment a world loaded: "Missing registry:
		// lithostitched:worldgen_modifier" out of RegistryAccess.lookupOrThrow, on the server tick loop. Must run
		// before any world is created; here is the first point where every mod's listeners are registered.
		//
		// Not here on a client whose Fabric mains run in Minecraft.<init>, though. This step initialises
		// RegistryDataLoader, whose initialiser runs Fabric mod code (WorldWeaver's datapack entrypoints ride a TAIL
		// injector there), and on native Fabric that code first runs at world load, after every main. Left here it
		// ran before any of them, with minecraft:root frozen: wover-biome's codec registry, which its own main
		// creates, was created from the initialiser instead, threw "Registry is already frozen", and poisoned
		// RegistryDataLoader and DataPackRegistriesHooks for the session — no world could be created, loaded or
		// joined, and NeoForge's data maps died with them. It moved when the client mains did (09d86de) and this
		// step did not. It runs at the end of onClientEntrypoints instead: after every main and client entrypoint,
		// the root frozen again, which is the state the dedicated server already declares in, cleanly.
		if (DatapackRegistryDeclaration.waitsForFabric(side, KernelFabricEcosystem.active(),
				KernelFabricEcosystem.mainsRunInConstructor())) {
			ForbricLog.info("[Forbric/Lifecycle] datapack-registry declaration waits for the Fabric main and client "
					+ "entrypoints in Minecraft.<init> — its initialisers run Fabric mod code, which must not run "
					+ "before those mains (-D%s=off to declare here)", DatapackRegistryDeclaration.DEFERRAL_SWITCH);
		} else {
			registerDataPackRegistries(cl);
		}
		// Step 3a2: open the game event buses — HERE, not after the setup lifecycle.
		//
		// Genuine NeoForge starts NeoForge.EVENT_BUS at the end of CommonModLoader.begin, immediately after its
		// "Config loading" task and before load() posts common setup (javap: getstatic NeoForge.EVENT_BUS;
		// invokeinterface IEventBus.start right after the second runInitTask). The kernel started it last, after
		// every setup phase — and IEventBus.post on a bus that has not started RETURNS SILENTLY (`getfield
		// shutdown; ifeq; return`). So a mod that posts its own API event during FMLConstructModEvent, RegisterEvent
		// or common setup — the "register with me" shape addon mods are built on — posted into nothing: no
		// listener ran, no error, no log, and the posting mod carried on with an empty result.
		//
		// Everything the kernel wires onto these buses (its own server duties inside startGameBuses, NeoForge's own
		// @EventBusSubscriber classes in step 2c2) is already registered by this point, and adding a listener to a
		// started bus is allowed anyway.
		startGameBuses(cl, side);
		// Step 3b: post the FML setup lifecycle at every NeoForge mod. Genuine NeoForge produces these inside
		// CommonModLoader.load(), whose only client caller is ClientModLoader.finish() — which the kernel neuters
		// because it also drives the discovery/registration the kernel owns. Nothing replaced the setup phases, so
		// they never fired for anyone: AppleSkin registers its food tooltip from FMLClientSetupEvent
		// (preInitClient -> TooltipOverlayHandler.init -> NeoForge.EVENT_BUS.register), which is why the tooltip
		// stayed missing even after mod-bus delivery was fixed.
		fireModSetupLifecycle(cl, side);
		// Step 3b2: the late-config pass, in the one place it is honest. loadEarlyConfigs (step 2c) ran before the
		// setup lifecycle (FMLConstructModEvent fired earlier, inside registerNeoForgeContent, so a config registered
		// there IS loaded by step 2c), so a mod that registers a config from common setup or a later phase — and a
		// Fabric mod registering one through the ForgeConfigAPIPort — has a config that is registered and never
		// loaded. Reading it then throws "Cannot get config value before config is loaded" rather than
		// returning a default, from wherever the mod first asked. This opens only what has no loaded config yet, so
		// it cannot re-open what step 2c already did; on a pack where nothing registers late it says nothing.
		openLateConfigs(cl, side, "the mod setup lifecycle");
		// Step 3c: NOW close the payload registration phase. NetworkRegistry.setup() posts
		// RegisterPayloadHandlersEvent (payload types + codecs, incl. playToClient(neoforge:recipe_content)) and
		// ClientNetworkRegistry.setup() then posts the client-handler event and validates every to-client payload has
		// one — else the join negotiation rejects with "Incompatible client! (No Handler for …)".
		//
		// This used to run as step 2d, BEFORE the setup lifecycle, and that ordering was wrong: setup() flips
		// NetworkRegistry's `setup` flag, after which any registration throws "Cannot register payload <id> after
		// registration phase". Mods register payloads from FMLCommonSetupEvent — CreativeCore does, for itself and
		// for EnhancedVisuals — so on the Odyssey pack their payloads never registered and the player was dropped
		// with "Network Protocol Error" seconds after the world rendered. Genuine NeoForge closes the phase after
		// mod loading, which is what this now matches. On the CLIENT it moves later still, to onClientEntrypoints,
		// because client setup itself moved there.
		if (!side.isClient()) setupNeoForgeNetwork(cl, side);
	}

	/**
	 * The block bookkeeping the kernel redoes after each registry freeze, because it drives registration itself: every
	 * block state's cache, then the mods' whole-registry passes for late registrations.
	 *
	 * <p>The blockstate→id map is not among it any more. The kernel used to rebuild it here (the merged base's bake re-added
	 * only the blocks registered inside the window, and MinecraftForge mods registered a second wave in
	 * {@code Minecraft.<init>}); NeoForge's own {@code BlockCallbacks.onBake}, which the {@code freeze} right before each
	 * call of this runs, adds the state of every block in the registry, so on NeoForge's base the map always held every
	 * registered state and the rebuild, which checked only that count, never ran (Stage 4 C5, lane S: no boot printed it;
	 * switch {@code forbric.blockStateIdRefill} retired; it compared counts only, so it could not see ids that were all
	 * there but offset). That the ids themselves were native is a separate matter: the client's second bake appended
	 * them again until {@link #clearBlockStateIds} (lane S's wave-2 review, lens A), and on every side the main window's
	 * bake appended them after the states fabric-registry-sync's tracker had already added during the window, until the
	 * same clear moved in front of that freeze too (lane K1).
	 */
	private static void completeBlockStateBookkeeping(ClassLoader cl) {
		// Vanilla fills every block state's cache in Bootstrap, before any mod has registered a block, and the kernel
		// drives registration itself.
		contentCall(cl, "initialiseBlockStateCaches", "initialise the block state caches");
		// Last, because the completion walks those registries: a mod whose own "every element exists now" pass ran before
		// the kernel's last wave of registrations never saw those elements, so each element registered since gets that
		// pass's per-element callback now, once.
		contentCall(cl, "initialiseBlockInfoCaches", "complete the mods' whole-registry passes for late registrations");
	}

	/**
	 * Wires NeoForge's built-in network payloads: subscribes {@code NetworkInitialization#register} (its
	 * {@code @EventBusSubscriber} payload handler, which the kernel's mod-jar-only scan misses) to the baseline mod
	 * bus, then runs {@code NetworkRegistry.setup()} — which posts {@code RegisterPayloadHandlersEvent} through
	 * {@code ModLoader} to that bus, populating {@code PAYLOAD_REGISTRATIONS} so payloads like
	 * {@code neoforge:recipe_content} become sendable. Best-effort; failure only leaves payloads unregistered.
	 */
	private static void setupNeoForgeNetwork(ClassLoader cl, Side side) {
		// Two-phase, in this order: NetworkRegistry.setup() posts RegisterPayloadHandlersEvent (payload TYPES + codecs,
		// incl. playToClient(neoforge:recipe_content)); ClientNetworkRegistry.setup() then posts
		// RegisterClientPayloadHandlersEvent (the CLIENT HANDLERS) and validates every to-client payload has one —
		// else the join negotiation rejects with "Incompatible client! (No Handler for …)". Both events reach
		// NeoForge's own handlers only because step 2c2 wired its runtime-jar @EventBusSubscribers.
		invokeNetworkSetup(cl, ForeignType.NETWORK_REGISTRY.binary(Ecosystem.NEOFORGE));
		// The client half is client-only, as in genuine NeoForge (ClientModLoader runs it; ServerModLoader does not).
		// It used to run on the dedicated server too, and passed — vacuously, because no NeoForge payload type was
		// registered there for it to demand a handler for. The moment the server registered them (baseline in
		// ModList) it failed with "Some clientbound payloads are missing client-side handlers", correctly: the
		// handlers live in a Dist.CLIENT @EventBusSubscriber that step 2c2 rightly skips on a server.
		if (side.isClient()) invokeNetworkSetup(cl,
				"net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry");
	}

	/** Runs a NeoForge {@code *NetworkRegistry.setup()} — it posts its Register*PayloadHandlersEvent via ModLoader. */
	private static void invokeNetworkSetup(ClassLoader cl, String registryClass) {
		try {
			Class<?> registry = Class.forName(registryClass, false, cl);
			registry.getMethod("setup").invoke(null);
			ForbricLog.info("[Forbric/Lifecycle] %s.setup() — payload handlers registered%s",
					registryClass.substring(registryClass.lastIndexOf('.') + 1), describePayloadRegistrations(registry));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] " + registryClass + ".setup() failed — NeoForge payloads incomplete "
					+ "(join may fail 'No Handler for …')");
		}
	}

	/**
	 * "; N payload type(s) registered {CONFIGURATION=a, PLAY=b}, NeoForge's own included: yes/no" — or "" when the
	 * class has no {@code PAYLOAD_REGISTRATIONS} (the client registry). Logged with the setup line because the one
	 * thing that line used to certify — "payload handlers registered" — was false for two months on every dedicated
	 * server the kernel ever booted: {@code setup()} had run, and had registered nothing of NeoForge's, because the
	 * event it posts fans out over {@code ModList} and the baseline container was not in it (see
	 * {@link #publishModBusDelivery}). Zero gates saw it, since singleplayer never encodes a packet. A count that
	 * says {@code PLAY=10, NeoForge's own included: no} would have.
	 */
	private static String describePayloadRegistrations(Class<?> registry) {
		try {
			java.lang.reflect.Field f = registry.getDeclaredField("PAYLOAD_REGISTRATIONS");
			f.setAccessible(true);
			java.util.Map<?, ?> byProtocol = (java.util.Map<?, ?>) f.get(null);
			java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
			boolean natives = false;
			int total = 0;
			for (java.util.Map.Entry<?, ?> e : byProtocol.entrySet()) {
				java.util.Map<?, ?> ids = (java.util.Map<?, ?>) e.getValue();
				counts.put(String.valueOf(e.getKey()), ids.size());
				total += ids.size();
				for (Object id : ids.keySet()) {
					if (String.valueOf(id).startsWith("neoforge:")) natives = true;
				}
				// The registered half of the channel census. Recorded here because this is the one walk over
				// PAYLOAD_REGISTRATIONS anywhere, and a second one would be a second thing to keep in step.
				net.forbric.kernel.interop.NetworkChannelCensus.registered(
						net.forbric.api.Ecosystem.NEOFORGE, ids.keySet());
			}
			return "; " + total + " payload type(s) registered " + counts + ", NeoForge's own included: "
					+ (natives ? "yes" : "NO");
		} catch (NoSuchFieldException clientRegistry) {
			Caught.expected(clientRegistry);
			return "";
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not read %s.PAYLOAD_REGISTRATIONS; the channel census misses "
					+ "its registrations", registry.getName());
			return "; (could not read PAYLOAD_REGISTRATIONS: " + t + ")";
		}
	}

	/**
	 * Marks NeoForge's {@code VANILLA_SYNC_REGISTRIES} (item/block/fluid/recipe_serializer/…) as client-syncing via
	 * {@code BaseMappedRegistry.setSync(true)}, so the play-phase registry-ID codecs
	 * ({@code ByteBufCodecs.getSyncableRegistryOrThrow}) accept them instead of throwing "non-synced built-in
	 * registry". Best-effort; a failure only degrades registry-ID sync (logged, not fatal).
	 */
	private static void markVanillaRegistriesSynced(ClassLoader cl) {
		try {
			Class<?> setupCls = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistriesSetup", false, cl);
			java.lang.reflect.Field f = setupCls.getDeclaredField("VANILLA_SYNC_REGISTRIES");
			f.setAccessible(true);
			java.util.Set<?> regs = (java.util.Set<?>) f.get(null);
			Class<?> baseMapped = Class.forName("net.neoforged.neoforge.registries.BaseMappedRegistry", false, cl);
			java.lang.reflect.Method setSync = baseMapped.getDeclaredMethod("setSync", boolean.class);
			setSync.setAccessible(true);
			int n = 0;
			for (Object reg : regs) {
				if (baseMapped.isInstance(reg)) {
					setSync.invoke(reg, true);
					n++;
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] marked %d vanilla registr(ies) client-syncing (doesSync=true)", n);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not mark vanilla registries synced (registry-ID sync may fail)");
		}
	}

	/**
	 * Loads NeoForge's STARTUP/COMMON (and, on the client, CLIENT) config specs — registered by the baselines and by
	 * the mods constructed just before this — from the config dir, so {@code ModConfigSpec.ConfigValue.get()} reads
	 * work later in the lifecycle. Loading a spec is also what posts {@code ModConfigEvent.Loading}, which is how a
	 * config framework layered on NeoForge (Balm, for one) learns that a mod's config now has values; skip it and
	 * such a mod reads null forever.
	 *
	 * <p>SERVER is deliberately absent: it is per-world and belongs to {@code
	 * ServerLifecycleHooks.handleServerAboutToStart}, which the kernel does not excise.
	 *
	 * <p>Missing files are fine — NeoForge writes defaults. Best-effort per type; a failure is logged, not fatal.
	 */
	private static void loadEarlyConfigs(ClassLoader cl, Side side) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.earlyConfigs", "on"))) {
			ForbricLog.warn("[Forbric/Lifecycle] early config loading DISABLED (-Dforbric.earlyConfigs=off) — "
					+ "NeoForge COMMON/CLIENT configs are not opened by the kernel; mods may keep "
					+ "defaults or read unloaded values");
			return;
		}
		List<String> types = earlyConfigTypes(side);
		try {
			configClass(cl).getMethod("loadEarly", List.class).invoke(null, types);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not load NeoForge configs");
		}
	}

	/**
	 * The config types {@link #loadEarlyConfigs} opens, in the order it opens them: native NeoForge's
	 * ({@code CommonModLoader.begin}'s "Config loading" step, {@code lambda$begin$1} in 26.2.0.93: {@code CLIENT} when the
	 * dist is the client, then {@code COMMON}). Opening a config posts {@code ModConfigEvent.Loading} for it, so natively a
	 * COMMON config's Loading listener that reads the mod's CLIENT values finds them loaded. With COMMON first, as the
	 * kernel had it, those values were still unloaded there and the read throws "Cannot get config value before config
	 * is loaded" (Stage 4 C5 K3b review A-3).
	 *
	 * <p>STARTUP is deliberately absent — see the game side, which explains what naming it would cost. SERVER is
	 * per-world (see {@link #loadEarlyConfigs}).
	 */
	static List<String> earlyConfigTypes(Side side) {
		return side.isClient() ? List.of("CLIENT", "COMMON") : List.of("COMMON");
	}

	/** The game-side half of config loading. */
	private static Class<?> configClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.forbric.kernel.runtime.KernelConfigLoad", true, cl);
	}

	/**
	 * Opens NeoForge configs that were registered after {@link #loadEarlyConfigs} had already run.
	 *
	 * <p>The early pass happens once, after mod content registration and before the setup lifecycle (step 2c, where
	 * native NeoForge loads its configs too). A Fabric mod registering a config from a CLIENT entrypoint, or a mod
	 * registering one from common setup or later, is therefore too late for it, and nothing else opens a non-STARTUP
	 * config — the carrier's {@code registerConfig} eagerly opens STARTUP only. The mod then reads a config that was registered and never
	 * loaded, and what it gets is not an empty config but "Cannot get config value before config is loaded",
	 * thrown from wherever it first asked. ShoulderSurfing asks from a mixin in {@code Minecraft.<init>}.
	 *
	 * <p>General on purpose: it fixes any late registrar, not the one that exposed it, and it cannot double-open
	 * because it opens only what has no loaded config yet. {@code ConfigTracker.loadConfigs} would have been the
	 * obvious call and is the wrong one — it re-opens every config of the type, and the carrier's second open
	 * warns and installs a SECOND file watcher, so every later edit of that file fires the reload twice.
	 *
	 * <p>Never SERVER: those are per-world and belong to the server-about-to-start hook, which loads them from the
	 * world directory. Opening them here would load them from the wrong place and overwrite them from the right
	 * one a moment later.
	 */
	private static void openLateConfigs(ClassLoader cl, Side side, String when) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.earlyConfigs", "on"))) return;
		try {
			Object result = configClass(cl).getMethod("openLate", List.class)
					.invoke(null, lateConfigTypes(side));
			if (result instanceof List<?> opened && !opened.isEmpty()) {
				ForbricLog.info("[Forbric/Lifecycle] opened %d late-registered NeoForge config(s) after %s %s — "
						+ "they were registered after the early pass, and nothing else would have loaded them",
						opened.size(), when, opened);
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not open late-registered NeoForge configs");
		}
	}

	/**
	 * Which config types {@link #openLateConfigs} may open, for a side.
	 *
	 * <p>SERVER is absent from both, and that absence is load-bearing rather than an oversight: a SERVER config is
	 * per-world and is loaded from the world directory by the server-about-to-start hook. Opening one here would
	 * load it from the global config directory, and the carrier's own warning for that ("Overwriting non-null
	 * config") is asserted absent by two gates.
	 */
	static List<String> lateConfigTypes(Side side) {
		return side.isClient() ? List.of("STARTUP", "COMMON", "CLIENT") : List.of("STARTUP", "COMMON");
	}

	/**
	 * The two census lines gates read once every mod's subscribers are wired: how many guest mixins apply only partially
	 * (gate-m12 holds the count under a ceiling) and which mod-info questions were answered empty.
	 *
	 * <p>They used to be the last lines of {@code KernelEventSubscribers.registerAll}, which returns early when NeoForge's bus
	 * API is not there; neither census is about subscribers at all. Logged at the same moment, right after that pass, with
	 * the same words.
	 *
	 * <p>Isolated as that pass is: the call sits inside {@code registerNeoForgeContent}'s try, where an escape would abort the
	 * whole registration window — RegisterEvent, the Fabric mains, every step after it — for a log line. Each census on its
	 * own, too, so one that throws costs only its own line, and the gate reading the other still finds it.
	 */
	static void reportGuestCensuses() {
		reportGuestCensuses(List.of(
				// Ninety individual lines on a client boot, none of them totalled anywhere, for the state MixinFit's javadoc
				// calls worse than either extreme.
				Map.entry("partial-mixin", net.forbric.kernel.mixin.KernelGuestMixinAdapter::partialSummary),
				// And questions the kernel was asked and could only answer empty — where "I do not know" and "there is
				// none" are the same value to the caller.
				Map.entry("unmodelled-accessor", PassiveSeeder::unmodelledSummary)));
	}

	/** Logs each census's line in order; one that throws is named and skipped, never thrown on. */
	static void reportGuestCensuses(List<Map.Entry<String, java.util.function.Supplier<String>>> censuses) {
		for (Map.Entry<String, java.util.function.Supplier<String>> census : censuses) {
			try {
				ForbricLog.info("%s", census.getValue().get());
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Lifecycle] could not take the %s census — its line is missing from this log; the "
						+ "registration window goes on", census.getKey());
			}
		}
	}

	/**
	 * Starts NeoForge's global game bus so game-event listeners dispatch.
	 *
	 * <p>Absent and failed are split here for the same reason as in {@link #invokeGameDataOn}: an instance without
	 * NeoForge's runtime legitimately has no such bus, and that is a debug line. A bus that is PRESENT and fails to
	 * start is total — every game-event listener of every mod is on a bus nothing dispatches, and the game then runs
	 * with no visible error at all.
	 */
	private static void startGameBuses(ClassLoader cl, Side side) {
		// The kernel's own server duties (Fabric's game instance, the load report and compatibility decision once a world
		// is up, the tryDropExperience relay, the data-map watch) go on right before the bus starts, so each is in place
		// before the first game event dispatches.
		KernelServerDutiesInstaller.install(cl);
		startBus(cl, "net.neoforged.neoforge.common.NeoForge", "EVENT_BUS",
				"net.neoforged.bus.api.IEventBus", "start", "NeoForge.EVENT_BUS");
	}

	/**
	 * Resolves one family's game bus and opens it, reporting absence and failure differently.
	 *
	 * @param holder the class holding the bus as a static field, {@code field} the field, {@code api} the type
	 *               declaring the start method, {@code start} that method, {@code label} what to call it in the log
	 */
	private static void startBus(ClassLoader cl, String holder, String field, String api, String start,
			String label) {
		Object bus;
		try {
			bus = Class.forName(holder, false, cl).getField(field).get(null);
		} catch (ClassNotFoundException | NoSuchFieldException | LinkageError absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] no %s on this runtime — nothing to start", label);
			return;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not read " + label + " — every game-event listener of that "
					+ "family is on a bus nothing will dispatch");
			return;
		}
		try {
			Class.forName(api, false, cl).getMethod(start).invoke(bus);
			ForbricLog.info("[Forbric/Lifecycle] started %s (game events now dispatch)", label);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] " + label + " is present but did NOT start — every game-event "
					+ "listener of that family, in every mod, is now on a bus nothing dispatches");
		}
	}

	/**
	 * The NeoForge mod buses the registry events go to, each once: every constructed mod's, then every
	 * declared-only mod's.
	 *
	 * <p>Deduped by IDENTITY: a NeoForge mod has ONE bus shared by all its {@code @Mod} classes (balm ships
	 * NeoForgeBalm + NeoForgeBalmClient, FallingTree the same), so a per-entry list would fire RegisterEvent twice
	 * on that bus and register the mod's content twice.
	 *
	 * <p>The declared-only mods — a {@code [[mods]]} entry with no {@code @Mod} class, see
	 * {@link KernelModLoader#classlessNeoMods()} — had nothing constructed, but FML posts the registry events to
	 * every container it lists, and for such a mod an {@code @EventBusSubscriber} registering its content from
	 * RegisterEvent is the whole of its code.
	 */
	static List<Object> registrationBuses(List<KernelModLoader.ConstructedMod> mods,
			java.util.Collection<KernelModLoader.NeoIdentity> classless) {
		List<Object> buses = new ArrayList<>();
		java.util.Set<Object> seenBuses = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (KernelModLoader.ConstructedMod m : mods) {
			if (m.bus() != null && seenBuses.add(m.bus())) buses.add(m.bus());
		}
		for (KernelModLoader.NeoIdentity identity : classless) {
			if (identity.bus() != null && seenBuses.add(identity.bus())) buses.add(identity.bus());
		}
		return buses;
	}

	/**
	 * Constructs NeoForge's baseline mod ({@code NeoForgeMod}) on a fresh mod-event bus — which registers its
	 * {@code DeferredRegister}s — then fires {@code RegisterEvent} per registry so those DeferredRegisters flush
	 * their default content (the empty/water/lava FluidTypes, default attributes, …). A single unfreeze/freeze
	 * window surrounds the registration; because the kernel drives ONE pass (no dual-ecosystem refreeze), the
	 * "Tags not bound" wall of the old weld does not arise.
	 */
	private static void registerNeoForgeContent(ClassLoader cl, Side side) {
		// Whether the registration window was opened, and so whether the finally below owes it a close.
		boolean closeWindow = false;
		try {
			Class<?> distClass = Class.forName(ForeignType.DIST.binary(Ecosystem.NEOFORGE), false, cl);
			Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), side.distName());

			// The NeoForge baseline mod on its own bus. Captured so the client step can add ClientNeoForgeMod to
			// the same bus + route the game's mod-bus events to its container. Described by the [[mods]] entry NeoForge's
			// own manifest declares for itself (name "NeoForge", its version), as genuine NeoForge's container is.
			Object bus = KernelBusSupport.makeModBus(cl);
			Object container = KernelModContainerFactory.create(cl, CarrierOwnMod.NEOFORGE_ID, bus, null,
					CarrierOwnMod.neoForge(runtimeJars));
			baselineBus = bus;
			baselineContainer = container;
			Class<?> neoForgeMod = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", false, cl);
			Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> modContainer = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
			// On NeoForge's modloading-worker pool with its container active there, where native NeoForge constructs it
			// (ModLoader.constructMods: "NeoForge mod loading, version ..." is a modloading-worker line natively; it was
			// a main line here). Before every other mod, which is one of the orders natively possible.
			Constructor<?> baseline = neoForgeMod.getConstructor(iEventBus, distClass, modContainer);
			NeoParallelDispatch.runOne(NeoParallelDispatch.parallelExecutor(cl), container, CarrierOwnMod.NEOFORGE_ID, own -> {
				KernelModLoader.setNeoActiveContainer(cl, own);
				try {
					baseline.newInstance(bus, dist, own);
				} finally {
					KernelModLoader.setNeoActiveContainer(cl, null);
				}
			});
			ForbricLog.info("[Forbric/Lifecycle] constructed NeoForge baseline mod on a native bus (dist=%s)",
					side.distName());
			Object baselineBus = bus;

			// Real NeoForge @Mods, each on its own bus.
			List<KernelModLoader.ConstructedMod> mods =
					KernelModLoader.constructMods(cl, modJars, side);

			// No config is loaded in this window. COMMON (and, on a client, CLIENT) configs load at step 2c,
			// loadEarlyConfigs, after registerNeoForgeContent has returned — where native NeoForge loads them too:
			// CommonModLoader.begin runs "Registry initialization" (NewRegistryEvent, RegisterEvent, freeze) and only
			// then "Config loading" (javap of 26.2.0.93). STARTUP configs load when they are registered. So a
			// registry-phase listener that reads a COMMON value throws "Cannot get config value before config is
			// loaded" here as it does natively. This comment used to say the opposite; the logs say otherwise
			// ("loaded NeoForge configs" follows "fired RegisterEvent" on a server and a client, Stage 4 C5 K3b).
			// Wire every mod's @EventBusSubscriber classes NOW, while the registration window is still ahead of
			// them. Genuine FML does this inside ModContainer.constructMod(), i.e. before registry init, so a
			// subscriber-declared handler is attached by the time any registration event is posted. The kernel used
			// to do it much later, after registerNeoForgeContent had already returned, and the cost was silent:
			// earthmobsmod and bagus_lib declare their EntityAttributeCreationEvent handlers on a class-level
			// @EventBusSubscriber, so CommonHooks.modifyAttributes below posted to an empty bus and every one of
			// their entities came out attribute-less — 2250 "Entity <id> has no attributes" errors per freeze, and
			// mobs that cannot spawn. The same was true of any RegisterEvent handler declared that way.
			//
			// This class-loads every subscriber before the COMMON/CLIENT configs load (step 2c), as native NeoForge
			// does: it loads these classes during construction, before its "Config loading" task, so a <clinit> that
			// reads such a value fails there too. Still later than genuine NeoForge, so nothing that survives real
			// NeoForge can fail for being early here. Both game buses stay unstarted until startGameBuses, so early
			// registration is buffered, not lost.
			//
			// Isolated: this now sits inside registerNeoForgeContent's try, and an escape would abort the whole
			// registration window and be reported as "could not register ecosystem content", blaming the wrong
			// thing entirely.
			try {
				KernelEventSubscribers.registerAll(cl, modJars, side);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Lifecycle] could not wire guest @EventBusSubscriber classes — mods that "
						+ "declare their registry or attribute handlers there will not be reached");
			}
			// Where the subscriber pass used to end, and whether or not it got that far.
			reportGuestCensuses();

			// FMLConstructModEvent, the phase genuine FML posts to each container the moment it is built. The
			// kernel constructed the mods and went straight on, so anything a mod does there — and it is the
			// earliest mod-bus phase there is — never happened. Posted after the subscribers are wired, so a
			// handler declared on an @EventBusSubscriber receives it too.
			fireSetupPhase(cl, KernelModLoader.publishedNeoMods(), ForeignType.FML_CONSTRUCT_MOD_EVENT, "construct");

			// The baseline's bus, then every mod's: NewRegistryEvent goes to the mods' buses below, all inside the one
			// unfreeze/freeze window. RegisterEvent goes to the same containers, through NeoForge's own method.
			List<Object> buses = new ArrayList<>();
			buses.add(baselineBus);
			buses.addAll(registrationBuses(mods, KernelModLoader.classlessNeoMods().values()));

			openRegistrationWindow(cl);
			// From here the registries are OPEN, and everything that closes them again lives in the finally below.
			// It used to live inline at the end of this try, so anything that threw in between — a Class.forName for
			// a carrier type that was renamed, NeoForgeRegistries failing to initialise, an Error escaping the
			// baseline or the Fabric entrypoints — left every registry writable for the rest of the run and skipped
			// linkBlockItems, the block-state bookkeeping, the creative-tab sort and the registriesLoaded latch. The
			// symptoms are the ones this file already documents one by one: Block.asItem() returns AIR so a mod's
			// creative tab is empty and its blocks cannot be picked, the first block_update fails to encode with
			// "Can't find id for Block{minecraft:lava}" and kicks the player at spawn, and mods gating on
			// areRegistriesLoaded() refuse to register render layers. The only report was one WARN saying the
			// registration window "could not register ecosystem content", which names none of that.
			closeWindow = true;
			// Every Forge-family mod is constructed and no registry event has fired: the point a library's winning
			// Forge-family build has seen its own consumers declare themselves and has not yet read what they declared.
			// A custom key only its losing Fabric build dispatched from preLaunch is dispatched here, before any main.
			try {
				KernelFabricEcosystem.dispatchArbitratedAwayKeys(net.forbric.kernel.fabric.EntrypointDispatchScan.Phase.PRE_INIT);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Lifecycle] could not dispatch the entrypoint keys a superseded build dispatched");
			}
			// MOD buses only — buses.get(0) is the baseline, whose registries PassiveSeeder already registered at
			// seed time; posting there re-collects them and fill() dies on "Attempted duplicate registration".
			net.forbric.api.ProtocolExtensions.forLoader(cl).beforeConfigurationLoading();
			postNeoNewRegistryEvent(cl, buses.subList(1, buses.size()));
			// RegisterEvent is posted by NeoForge's OWN GameData.postRegisterEvents, so every mixin and transformer aimed at it
			// runs. The kernel used to post it itself and copy the method's tail, and a mod hooking the method was woven into
			// a method nobody called: supermartijn642corelib fills its @RegistryEntryAcceptor fields from a mixin there, so
			// packedup's menu type stayed null and its client died at startup ("Container screen registered with null menu
			// type!"). RegisterEventsSeam meets the kernel's needs at the method's own call sites: delivery to the containers
			// the kernel loaded, the Fabric mains (runFabricMainsInTheWindow) where the dispatch loop ends, before NeoForge's
			// tail, and each tail step reporting its own failure. What the method throws — a mod's failing listener, rolled
			// back by NeoForge, or anything else — stops the boot as native NeoForge stops it (RegistrationStop, D82).
			dispatchRegisterEvents(cl, () -> runFabricMainsInTheWindow(cl, side));
			logRegisteredContent(cl);
		} catch (Throwable t) {
			// NeoForge's registration failed and the boot stops, as natively (D82): the stop leaves for the redirected
			// ServerModLoader.load / ClientModLoader.begin call site.
			if (RegistrationStop.isStop(t)) throw RegistrationStop.propagate(t);
			Caught.warn(t, "[Forbric/Lifecycle] could not register ecosystem content");
		} finally {
			// Only when the window was actually opened: before unfreeze there is nothing to put back, and freezing
			// a registry the kernel never opened would close one the caller still owns. Neither after a stop: NeoForge's
			// freezeData never runs after its roll-back, and nothing of a mod does (Fabric's TAIL half).
			if (closeWindow && !RegistrationStop.stopped()) closeRegistrationWindow(cl);
			if (!RegistrationStop.stopped() && !side.isClient()) fabricFreezePoint(cl, FabricFreezePointInjector.TAIL_HOOK);
		}
	}


	/**
	 * The Fabric main entrypoints in the registration window, with the root open (NeoForge's {@code NewRegistryEvent.fill}
	 * re-froze it, and a Fabric mod declaring its own registry registers into it). Runs where NeoForge's
	 * {@code GameData.postRegisterEvents} ends its dispatch loop ({@link #onRegisterEventsDispatched}), before its tail —
	 * where it ran between the kernel's own dispatch and its copy of that tail — so the attribute events and every other
	 * tail step see what the Fabric mods registered.
	 */
	private static void runFabricMainsInTheWindow(ClassLoader cl, Side side) {
		rootRegistry(cl, true);
		try {
			try {
				// On a client these now run from onClientEntrypoints, inside Minecraft.<init>, where Fabric runs
				// them and where Minecraft.getInstance() is live. Here they would see a null instance, and a mod
				// that caches it caches null for the whole process. The dedicated server keeps this window: it has
				// no Minecraft to wait for, and Fabric's own startServer runs main just as early there.
				if (!side.isClient() || !KernelFabricEcosystem.mainsRunInConstructor()) {
					KernelFabricEcosystem.runMainEntrypoints();
				}
			} finally {
				// Fabric's registry freeze, as a server with fabric-api has it: after every main, the root still
				// open. Only the Fabric injectors on BuiltInRegistries.freeze() that FabricFreezeHookMixinAdapter
				// moved run here; the HEAD half now, the TAIL half once the window below is frozen. A client
				// does both in onClientEntrypoints, after its client entrypoints, as Fabric does.
				if (!side.isClient()) fabricFreezePoint(cl, FabricFreezePointInjector.HEAD_HOOK);
			}
		} finally {
			rootRegistry(cl, false);
		}
	}

	/**
	 * {@code RegisterEvent} for every registry, by NeoForge's own {@code GameData.postRegisterEvents}, with
	 * {@code afterDispatch} run once where its dispatch loop ends (or right after the method, when it never got there). Returns
	 * how many registries it posted for.
	 *
	 * <p>Whatever the method throws stops the boot, as native NeoForge stops it (owner ruling D82): a mod's listener that
	 * threw (NeoForge collected it, rolled every registry back to vanilla and threw before its tail) or anything else inside or
	 * after its per-registry try. What leaves here is the {@code ModLoadingException} NeoForge's own {@code ModLoader} builds
	 * for it ({@link RegistrationStop}); {@code afterDispatch} — the Fabric main entrypoints of a dedicated server — does not
	 * run after it, as nothing of a mod runs after that roll-back natively.
	 */
	static int dispatchRegisterEvents(ClassLoader cl, Runnable afterDispatch) {
		RegisterEventsWindow window = new RegisterEventsWindow(afterDispatch);
		int n;
		try {
			n = postRegisterEvents(cl, window);
		} catch (Throwable t) {
			throw RegistrationStop.propagate(RegistrationStop.stop(cl, t));
		}
		// Not reached through the seam (the seam was not applied): the Fabric mains still run in this window, after NeoForge's.
		window.runAfterDispatch();
		ForbricLog.info("[Forbric/Lifecycle] fired RegisterEvent x%d on %d bus(es) [NeoForge baseline + %d mod(s)]",
				n, window.containers(), Math.max(0, window.containers() - 1));
		return n;
	}

	/** One registration window's call of {@code GameData.postRegisterEvents}: its after-dispatch step and its counts. */
	static final class RegisterEventsWindow {
		private final Runnable afterDispatch;
		private boolean afterDispatchRan;
		private int events;
		private int containers;

		RegisterEventsWindow(Runnable afterDispatch) {
			this.afterDispatch = afterDispatch;
		}

		/** Once per window, whichever reaches it first: the seam at the end of NeoForge's loop, or the kernel after it. */
		synchronized void runAfterDispatch() {
			if (afterDispatchRan) return;
			afterDispatchRan = true;
			try {
				afterDispatch.run();
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Lifecycle] the Fabric main entrypoints did not finish in the registration window — "
						+ "NeoForge's tail (attribute events, spawn placements, block entity blocks) still runs");
			}
		}

		int containers() {
			return containers;
		}
	}

	/** The window whose {@code GameData.postRegisterEvents} call is in progress; null outside it. */
	private static volatile RegisterEventsWindow registerEventsWindow;

	/**
	 * Calls NeoForge's own {@code GameData.postRegisterEvents} — the dispatch of {@code RegisterEvent} for every registry
	 * in {@code GameData.getRegistrationOrder()}, then its tail — and returns how many registries it posted for.
	 *
	 * <p>The class is defined first, so Mixin and {@code RegisterEventsSeam} have run on it before the kernel decides how
	 * to call it. Without the seam NeoForge's own {@code ModLoader} delivers the event, and it walks {@code ModList}: the
	 * baseline joins it first, or NeoForge's own {@code DeferredRegister}s would never be told to register.
	 */
	private static int postRegisterEvents(ClassLoader cl, RegisterEventsWindow window) throws Exception {
		Class<?> gameData = Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), true, cl);
		boolean seam = net.forbric.kernel.transform.RegisterEventsSeam.applied();
		if (!seam) publishModBusDelivery(cl);
		int registries = ((java.util.Collection<?>) gameData.getMethod("getRegistrationOrder").invoke(null)).size();
		registerEventsWindow = window;
		try {
			gameData.getMethod("postRegisterEvents").invoke(null);
		} catch (java.lang.reflect.InvocationTargetException failed) {
			throw failed.getCause() instanceof Exception e ? e : failed;
		} finally {
			registerEventsWindow = null;
		}
		ForbricLog.info("[Forbric/Lifecycle] fired RegisterEvent in NeoForge's registration order — NeoForge's own "
				+ "GameData.postRegisterEvents walked %d registr(ies), every mixin on it applied%s", registries,
				seam ? "" : " (without the kernel's seam: a failing listener rolls the registries back, as on NeoForge)");
		if (!seam) window.containers = modListSize(cl);
		return seam ? window.events : registries;
	}

	private static int modListSize(ClassLoader cl) {
		try {
			Class<?> modListCls = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, cl);
			Object modList = modListCls.getMethod("get").invoke(null);
			return (int) modListCls.getMethod("size").invoke(modList);
		} catch (Throwable t) {
			Caught.expected(t);
			return 0;
		}
	}

	/**
	 * {@code GameData.postRegisterEvents}' {@code ModLoader.postEventWrapContainerInModOrder(event)}, rewritten by
	 * {@code RegisterEventsSeam} to come here: the same delivery — priority phase by phase, each container in mod order with
	 * itself the active container — to NeoForge's baseline and every NeoForge mod the kernel loaded, in {@code ModList}'s
	 * order. A mod whose listener throws ends the delivery there, as NeoForge's own fan-out ends it: its
	 * {@code ModContainer.acceptEvent} rethrows the failure as a {@code ModLoadingException} naming the mod, and that leaves
	 * here for {@code postRegisterEvents}' per-registry catch, which rolls every registry back and stops the boot (D82). It
	 * used to cost that mod only the registry, every other mod still getting it, and the boot carried on.
	 */
	public static void onRegisterEvent(Object event) {
		RegisterEventsWindow window = registerEventsWindow;
		ClassLoader cl = event.getClass().getClassLoader();
		String key;
		try {
			key = String.valueOf(event.getClass().getMethod("getRegistryKey").invoke(event));
		} catch (Throwable t) {
			Caught.expected(t);
			key = "?";
		}
		String registry = key;
		int delivered = deliverInModOrder(cl, event, true, true, id -> "[Forbric/Lifecycle] a NeoForge mod's RegisterEvent "
				+ "listener failed on " + registry + " — " + id + " ends that registry's dispatch, as on NeoForge");
		if (window != null) {
			synchronized (window) {
				window.events++;
				window.containers = Math.max(window.containers, delivered);
			}
		}
	}

	/** Where NeoForge's dispatch loop ends and its tail begins ({@code RegisterEventsSeam}): the window's Fabric step. */
	public static void onRegisterEventsDispatched() {
		RegisterEventsWindow window = registerEventsWindow;
		if (window != null) window.runAfterDispatch();
	}

	/**
	 * One no-argument static step of {@code GameData.postRegisterEvents}' tail, called here by {@code RegisterEventsSeam}
	 * and run as NeoForge wrote it, with its failure reported at the step's own catch site: the attribute events through
	 * {@link #postAttributeEvents}, NeoForge's tooltip appenders through {@code KernelNeoTooltips} (and only with
	 * {@code -Dforbric.neoTooltipAppenders} on), every other step through {@link #invokeStaticOn}.
	 */
	public static void onRegisterEventsTailStep(Class<?> owner, String method) {
		ClassLoader cl = owner.getClassLoader();
		String name = owner.getName();
		if (name.equals("net.neoforged.neoforge.common.CommonHooks") && method.equals("modifyAttributes")) {
			postAttributeEvents(cl);
		} else if (name.equals("net.neoforged.neoforge.common.tooltip.ItemTooltipHandler") && method.equals("init")) {
			// Last in postRegisterEvents: NeoForge builds its item tooltip appenders — every vanilla component line
			// (enchantments, lore, attributes, durability, …) and every mod's. KernelNeoTooltips.init calls it once.
			if (!"off".equalsIgnoreCase(System.getProperty(NEO_TOOLTIP_APPENDERS, "on"))) {
				invokeStaticOn(cl, "net.forbric.kernel.runtime.KernelNeoTooltips", "init");
			} else {
				ForbricLog.warn("[Forbric/Tooltips] NeoForge's tooltip appenders left unbuilt with -D%s=off — item "
						+ "tooltips show no component lines", NEO_TOOLTIP_APPENDERS);
			}
		} else {
			invokeStaticOn(cl, name, method);
		}
	}

	/**
	 * {@code GameData.postRegisterEvents}' {@code ModLoader.postEvent(event)} (BlockEntityTypeAddBlocksEvent), delivered as
	 * NeoForge's {@code postEvent} does — priority phase by phase, each container in {@code ModList}'s order, none of them
	 * active — with a container that throws contained.
	 */
	public static void onRegisterEventsModBusEvent(Object event) {
		String name = event.getClass().getName();
		deliverInModOrder(event.getClass().getClassLoader(), event, false, false, id -> "[Forbric/Lifecycle] " + id + " threw during "
				+ name + " — its own registration from that event is lost, every other mod still gets it");
	}

	/**
	 * Opens the main registration window.
	 *
	 * <p>First the post-Bootstrap vanilla registry state is captured for NeoForge. Then {@code unfreezeData}, which
	 * clears the vanilla {@code frozen} flag and nothing else: on NeoForge's base it leaves the blockstate→id map as it
	 * is (empty after Bootstrap on a server, measured), and {@code BlockCallbacks.onBake} walks the whole block registry
	 * at the freeze that closes the window, after {@link #closeRegistrationWindow} has emptied the map.
	 */
	static void openRegistrationWindow(ClassLoader cl) {
		invokeGameDataOn(cl, ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), "vanillaSnapshot");
		unfreeze(cl);
	}

	/**
	 * Closes the registration window and redoes the bookkeeping the open window invalidated.
	 *
	 * <p>Each of these three is a failure the kernel has already paid for once, so they are named rather than
	 * folded: {@code linkBlockItems} links any block item still missing from {@code Item.BY_BLOCK} (normally none: the
	 * item registry's add-callback links each one as it is added; without either, {@code Block.asItem()} is AIR and a
	 * mod's creative tab collapses to empty), {@code freeze} also latches {@code registriesLoaded} (and its bake
	 * numbers every block's states, from the map {@link #clearBlockStateIds} has just emptied), and
	 * {@code completeBlockStateBookkeeping} fills the state caches.
	 *
	 * <p>No creative-tab sort here any more: NeoForge's own {@code GameData.postRegisterEvents} tail sorts the tabs once
	 * everything this window registers is in (on a dedicated server the Fabric mains too), so a second sort here found the
	 * same list every time (Stage 4 C5 lane K3b, the plan's C3 checks: a dedicated server and a client, re-sort on and
	 * off). The client's last registration happens later, in the Fabric entrypoint window, and is sorted where that
	 * window closes ({@link #closeClientEntrypointWindow}).
	 *
	 * <p>Best-effort as a whole AND per step, because this runs in a finally: it must not replace the exception
	 * that brought it here.
	 */
	static void closeRegistrationWindow(ClassLoader cl) {
		try {
			linkBlockItems(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not link block->item mappings while closing the registration "
					+ "window");
		}
		// Right before the freeze, as at the client window's close: the freeze's bake must number every state once, from
		// an empty map, as NeoForge's single bake does natively. fabric-registry-sync's StateIdTracker has appended each
		// block's states to this same map as the window registered it (Fabric numbers states that way), so without the
		// clear the bake appended the whole registry after them and every id on a dedicated server was its native id
		// plus that count (Stage 4 C5 lane K1).
		clearBlockStateIds(cl);
		try {
			freeze(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not re-freeze the registries — they stay writable for the "
					+ "rest of this run");
		}
		verifyBlockStateIds(cl, "registration window");
		try {
			completeBlockStateBookkeeping(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not complete the block state bookkeeping — late-registered block "
					+ "states keep the caches their registration left");
		}
		// After NeoForge's own sort (the tail of postRegisterEvents), which is what it reports on.
		if (Boolean.getBoolean("forbric.tabProbe")) startCreativeTabProbe(cl);
	}

	/**
	 * Sorts NeoForge's creative-tab ORDER list again, so the tabs the Fabric client entrypoints registered reach the strip.
	 *
	 * <p>NeoForge's {@code CreativeModeInventoryScreen} paginates its tab strip EXCLUSIVELY from
	 * {@code net.neoforged.neoforge.common.CreativeModeTabRegistry.getSortedCreativeModeTabs()} — not from
	 * {@code CreativeModeTabs.tabs()}. That {@code SORTED_TABS} list is rewritten by {@code sortTabs()}, which walks the
	 * whole {@code CREATIVE_MODE_TAB} registry, and natively runs once, at the end of {@code GameData.postRegisterEvents},
	 * after the last registration; the server's datapack reload ({@code NeoForgeEventHandler}'s creative-tab reload
	 * listener) recalculates it again on the server thread. On a client the Fabric mains and client entrypoints register
	 * in {@code Minecraft.<init>} ({@link #onClientEntrypoints}), after that sort, so a Fabric mod's tab was in the
	 * {@code CREATIVE_MODE_TAB} registry but not in the strip: measured (Stage 4 C5 lane K3b, mcw-stairs Fabric +
	 * mcw-bridges NeoForge, a probe on {@code SORTED_TABS} and the registry), the tab stayed out of {@code SORTED_TABS}
	 * until an integrated server's reload put it in, and on a client joining a dedicated server it never did. The tab's
	 * contents and the search tab were not measured. Native Fabric's pager reads vanilla's {@code CreativeModeTabs.tabs()},
	 * which streams the live registry on every call (javap of 26.2 and fabric-creative-tab-api-v1 5.0.14; no native
	 * client was run), so there a registered tab is in the list the strip reads. Sorting where the client's last
	 * registration window closes gives the list that reload gives, from the start and on every join.
	 *
	 * <p>Calling {@code sortTabs()} a second time is safe: with no server up, {@code runInServerThreadIfPossible} runs
	 * inline; it is a topological sort over the tab registry plus the ordering edges; {@code KernelRegistryContent} clears
	 * {@code DEFAULT_TABS} first, which {@code sortTabs()} appends to.
	 */
	private static void sortNeoCreativeTabs(ClassLoader cl) {
		contentCall(cl, "sortCreativeTabs", "re-sort the NeoForge creative tabs");
	}

	/** {@code -Dforbric.tabProbe} — dumps every non-vanilla creative tab's live state every 3s. */
	private static void startCreativeTabProbe(ClassLoader cl) {
		contentCall(cl, "startCreativeTabProbe", "start the creative-tab probe");
	}

	/**
	 * Links any registered {@code BlockItem} still missing from {@code Item.BY_BLOCK} — a net for the block→item link.
	 *
	 * <p>{@code Block.asItem()} resolves through {@code Item.byBlock(this)}, which is a plain
	 * {@code BY_BLOCK.get(block)}. NeoForge's {@code BlockItem} constructor only stores its block; it never adds
	 * itself to that map. On MinecraftForge the ITEMS registry's add-callback linked each one as it was added; the
	 * kernel's rewrite of that callback went with MinecraftForge, so this sweep is what links any block item still
	 * missing.
	 *
	 * <p>That is invisible in the registry dump (the blocks and their items both register fine, and gate-m4 counted
	 * them) but breaks anything that goes block→item. It is why Macaw's Bridges was unreachable: its creative tab
	 * feeds blocks in via {@code Output.accept(ItemLike)}, each became {@code new ItemStack(AIR)} = EMPTY, all ~150
	 * entries were dropped, and Minecraft HIDES a tab that ends up empty — indistinguishable from "the tab was never
	 * registered". Picking a block with the middle mouse button and any recipe/tag lookup that goes through
	 * {@code asItem()} were equally affected.
	 *
	 * <p>{@code putIfAbsent} so an entry vanilla already established always wins; best-effort, because a diagnostic
	 * link-up must never be able to fail the registration window.
	 */
	private static void linkBlockItems(ClassLoader cl) {
		contentCall(cl, "linkBlockItems", "link block->item mappings");
	}

	/**
	 * Reports what the registration window actually put into the vanilla registries, grouped by namespace.
	 *
	 * <p>Constructing a mod is not the same as the mod registering anything, and {@code DeferredRegister} is silent —
	 * so a kernel that fired {@code RegisterEvent} at a mod whose listeners never attached looked exactly like one
	 * that worked. This is the line that tells them apart, and it is how M7 Wall A was confirmed. Best-effort: a
	 * diagnostic must never be able to fail the window it reports on.
	 */
	private static void logRegisteredContent(ClassLoader cl) {
		contentCall(cl, "logRegisteredContent", "summarise registered content");
	}

	/**
	 * Calls one no-arg method on the game-side registry-content class.
	 *
	 * <p>Each of those five already reports its own failure in the terms of what it was repairing, so this only
	 * has to cover the class not being there at all — which on a machine whose boot jar was built without the
	 * staged artifacts is the same message for all five, and {@code KernelRuntimeClasses.verify} has already said
	 * it once at the top of the log.
	 */
	private static void contentCall(ClassLoader cl, String method, String what) {
		try {
			Class.forName("net.forbric.kernel.runtime.KernelRegistryContent", true, cl)
					.getMethod(method).invoke(null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not " + what);
		}
	}

	/**
	 * Client-only: construct {@code ClientNeoForgeMod} on the NeoForge baseline bus and route the game's mod-bus
	 * events to it.
	 *
	 * <p>NeoForge's built-in CLIENT registrations — reload listeners ({@code AddClientReloadListenersEvent} adds
	 * {@code AnimationLoader}, {@code ObjLoader}, branding), entity renderers, sprite sources, client extensions —
	 * live in {@code ClientNeoForgeMod}'s {@code @SubscribeEvent} handlers. The base's
	 * {@code Minecraft.<init>} fires those events via {@code ClientHooks.initClientHooks →
	 * ModLoader.postEvent(...)}, which iterates {@code ModList.sortedContainers} and calls each container's
	 * {@code acceptEvent} (→ its {@code getEventBus().post(...)}). The kernel seeded an EMPTY ModList, so those
	 * events reached nobody — {@code ModelManager.reload} then NPE'd reading the never-produced
	 * {@code AnimationLoader.STATE_KEY}. Constructing {@code ClientNeoForgeMod} on the baseline bus and pointing the
	 * ModList's one container at that bus makes {@code postEvent} deliver every client mod-bus event to NeoForge's
	 * handlers — the client analogue of the server's native RegisterEvent dispatch.
	 */
	private static void registerNeoForgeClientContent(ClassLoader cl) {
		try {
			Class<?> clientMod = Class.forName("net.neoforged.neoforge.client.ClientNeoForgeMod", false, cl);
			Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> modContainer = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
			clientMod.getConstructor(iEventBus, modContainer).newInstance(baselineBus, baselineContainer);
			ForbricLog.info("[Forbric/Lifecycle] constructed ClientNeoForgeMod on the baseline bus");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not register NeoForge client content — the client's mod-bus "
					+ "events (reload listeners, renderers) will not reach NeoForge");
		}
	}

	/** {@link #publishModBusDelivery} for both sides; a failure is logged, since the game still runs without it. */
	private static void publishNeoBaselineInModList(ClassLoader cl) {
		try {
			publishModBusDelivery(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not put the NeoForge baseline into ModList — NeoForge's own "
					+ "mod-bus listeners (its payload types among them) will not receive the events NeoForge posts");
		}
	}

	/**
	 * Makes the NeoForge {@code ModList} deliver mod-bus events to the baseline AND to every mod the kernel loaded.
	 *
	 * <p>{@code ModList.sortedContainers} is read by two things that both matter: {@code forEachModInOrder}, which is
	 * how {@code ModLoader.postEvent} fans a mod-bus event out to containers, and {@code getSortedMods()}, which is
	 * what {@code ModListScreen} lists. This method used to set that field (and {@code mods}) to a ONE-element list
	 * holding only the baseline container — which silently undid {@link KernelModLoader#publishNeoModList}, since the
	 * client step runs right after mod construction.
	 *
	 * <p>Both reported symptoms came from that single line. Every game-posted mod-bus event reached only NeoForge's
	 * baseline bus, so a mod's own listeners never fired: AppleSkin registers ALL of its client features with
	 * {@code IEventBus.addListener} on its mod bus ({@code RegisterGuiLayersEvent} for the four HUD overlays,
	 * {@code RegisterClientTooltipComponentFactoriesEvent} for the food tooltip, {@code RegisterPayloadHandlersEvent}
	 * for its sync packets) and got none of them. And the Mods screen listed only the baseline, because it reads the
	 * same field.
	 *
	 * <p>So the list is UNIONed instead of replaced: baseline first (genuine NeoForge also orders it first), then
	 * whatever {@code publishNeoModList} installed. {@code indexedMods} is rebuilt to match so
	 * {@code getModContainerById}/{@code isLoaded} answer for the baseline too.
	 *
	 * <p>Runs on the dedicated server as well, and did not always: it lived inside the client-only step, so every
	 * server's ModList held the mods and not NeoForge. The server posts fewer mod-bus events from game code than the
	 * client, but {@code NetworkRegistry.setup()}'s {@code RegisterPayloadHandlersEvent} is one of them, and its
	 * NeoForge-internal listener is what registers {@code neoforge:recipe_content} and every other built-in payload
	 * type. Without it a server can negotiate a NeoForge connection and then fail to encode the first NeoForge
	 * payload it sends (gate-m12).
	 */
	private static void publishModBusDelivery(ClassLoader cl) throws Exception {
		Class<?> modListCls = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, cl);
		Object modList = modListCls.getMethod("get").invoke(null);

		Field modsField = modListCls.getDeclaredField("mods");
		modsField.setAccessible(true);
		Object current = modsField.get(modList);

		List<Object> containers = new ArrayList<>();
		containers.add(baselineContainer);
		if (current instanceof List<?> existing) {
			for (Object c : existing) {
				if (c != null && c != baselineContainer) containers.add(c);
			}
		}

		for (String field : new String[] {"sortedContainers", "mods"}) {
			Field f = modListCls.getDeclaredField(field);
			f.setAccessible(true);
			f.set(modList, List.copyOf(containers));
		}

		try {
			Method getModId = modContainerClass(cl).getMethod("getModId");
			java.util.Map<String, Object> indexed = new java.util.HashMap<>();
			for (Object c : containers) {
				indexed.put((String) getModId.invoke(c), c);
			}
			// "minecraft" goes into the by-id index and NOWHERE else. ModLoadingContext.getActiveContainer()
			// falls back to getModContainerById("minecraft").orElseThrow() when no container is active, and the
			// throw it reaches says "Where is minecraft???!" — so a mod registering an extension point outside a
			// window the kernel wraps got an exception out of NeoForge rather than a container. The container's
			// own getEventBus() returns null by design, which is why it must not join the list the mod-bus
			// fan-out walks.
			indexed.computeIfAbsent("minecraft", id -> minecraftContainerOrNull(cl));
			indexed.values().removeIf(java.util.Objects::isNull);

			Field f = modListCls.getDeclaredField("indexedMods");
			f.setAccessible(true);
			f.set(modList, indexed);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not rebuild ModList.indexedMods — getModContainerById answers "
					+ "from the previous index");
		}

		// NeoForge's title-screen version-check overlay (NeoForgeVersionCheck.getStatus →
		// ModList.getModFileById("neoforge").getMods().get(0)) reads the `fileById` map, which our routing does not
		// otherwise touch. It used to be seeded here with the BASELINE'S entry alone, which rendered the main menu
		// and left getModFileById(anyOtherMod) answering null — an NPE inside any mod that resolves its own file by
		// id. The whole container list goes in now, baseline included, through the same helper the publish pass
		// uses, so the two passes cannot disagree about what the map holds.
		try {
			KernelModLoader.publishFileById(cl, modListCls, modList, containers);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not seed ModList.fileById (title version-check may NPE)");
		}
		ForbricLog.info("[Forbric/Lifecycle] NeoForge mod-bus delivery covers %d container(s) — baseline + every "
				+ "loaded mod (ModLoader.postEvent fans out over this list, and the Mods screen lists it)",
				containers.size());
	}

	/**
	 * NeoForge's own {@code "minecraft"} container, or null if it cannot be built.
	 *
	 * <p>Null rather than a throw: failing to publish this costs one fallback lookup, while letting it abort the
	 * index rebuild would cost every mod its container.
	 */
	private static Object minecraftContainerOrNull(ClassLoader cl) {
		try {
			return KernelModContainerFactory.minecraftContainer(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not publish the 'minecraft' container — a mod outside a "
					+ "kernel window asking for the active container gets NeoForge's \"Where is minecraft???!\"");
			return null;
		}
	}

	/**
	 * Posts {@code DataPackRegistryEvent.NewRegistry} so mods can declare their own DATAPACK registries.
	 *
	 * <p>Distinct from {@code RegisterEvent}, which the kernel already fires: that one fills registries that exist,
	 * while this one DECLARES per-world registries {@code RegistryDataLoader} must then build from datapacks.
	 * NeoForge accumulates the declarations on the event and flushes them into
	 * {@code DataPackRegistriesHooks.DATA_PACK_REGISTRIES} in its package-private {@code process()}.
	 *
	 * <p>One event instance posted to every bus and processed once — the shape FML uses, and required: the
	 * declarations accumulate ON the event, so a per-mod instance would drop all but the last mod's.
	 *
	 * <p>The baseline bus is included deliberately. NeoForge declares its OWN datapack registries through this same
	 * event ({@code neoforge:biome_modifier}, {@code neoforge:structure_modifier}), so posting it there is what
	 * makes those resolvable — the gap that forced {@code ServerLifecycleHooks.runModifiers} to be neutered.
	 *
	 * <p>Once per process, and never retried: a client reaches it from up to three places (see
	 * {@link DatapackRegistryDeclaration#waitsForFabric}), and a second post would hand every mod's listener the
	 * event twice, while a failed first attempt has usually left a class erroneous that a retry cannot revive.
	 */
	private static void registerDataPackRegistries(ClassLoader cl) {
		if (!DATAPACK_REGISTRIES_DECLARED.compareAndSet(false, true)) return;
		try {
			Class<?> eventCls = Class.forName(
					"net.neoforged.neoforge.registries.DataPackRegistryEvent$NewRegistry", false, cl);
			Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> baseEvent = Class.forName("net.neoforged.bus.api.Event", false, cl);
			Class<?> hooksCls = Class.forName(
					"net.neoforged.neoforge.registries.DataPackRegistriesHooks", false, cl);

			int before = ((java.util.List<?>) hooksCls.getMethod("getDataPackRegistries").invoke(null)).size();

			Object event = eventCls.getConstructor().newInstance();
			Method post = busCls.getMethod("post", baseEvent);

			int posted = 0;
			if (baselineBus != null) {
				post.invoke(baselineBus, event);
				posted++;
			}
			for (java.util.Map.Entry<String, KernelModLoader.NeoIdentity> e
					: KernelModLoader.publishedNeoMods().entrySet()) {
				// The active container matters here too: a mod may resolve itself while building its codec.
				KernelModLoader.setNeoActiveContainer(cl, e.getValue().container());
				try {
					post.invoke(e.getValue().bus(), event);
					posted++;
				} catch (Throwable perMod) {
					Caught.warn(perMod, "[Forbric/Lifecycle] " + e.getKey()
							+ " failed declaring its datapack registries");
				} finally {
					KernelModLoader.setNeoActiveContainer(cl, null);
				}
			}

			Method process = eventCls.getDeclaredMethod("process");
			process.setAccessible(true);
			process.invoke(event);

			// Name what landed, not just how many: on the merged pack a count alone could not distinguish "the
			// registry a mod needs is present" from "nine OTHER registries are present", and that ambiguity cost a
			// diagnosis. RegistryData is a record whose toString carries the key.
			java.util.List<?> now = (java.util.List<?>) hooksCls.getMethod("getDataPackRegistries").invoke(null);
			java.util.List<String> added = new java.util.ArrayList<>();
			for (int i = before; i < now.size(); i++) added.add(String.valueOf(now.get(i)));
			ForbricLog.info("[Forbric/Lifecycle] posted datapack-registry declaration to %d bus(es) — %d declared "
					+ "(%s), %d total", posted, now.size() - before, added, now.size());

			mirrorIntoFabricDynamicRegistries(cl, now.subList(before, now.size()));
			// Before the Fabric mirror: that one would also carry these across, but as bare (key, codec) copies.
			reconcileLoaderRegistriesIntoNeoForge(cl, hooksCls);
			mirrorFabricDynamicRegistriesIntoNeoForge(cl, eventCls, hooksCls);
			reconcileSynchronizedRegistries(cl, hooksCls);
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] no NeoForge DataPackRegistryEvent — skipping");
		} catch (Throwable t) {
			// A class that failed to initialise is a different failure from a declaration that failed, and a far
			// bigger one; it gets a finding, so the player hears it before the title screen and not at "Create".
			CompatibilityFinding poisoned = DatapackRegistryDeclaration.poisonedLoader(t);
			if (poisoned != null) CompatibilityFindings.record(poisoned);
			Caught.error(t, "[Forbric/Lifecycle] %s", poisoned != null ? poisoned.detail()
					: "could not declare mods' datapack registries — a mod with its own worldgen registry will fail "
							+ "with \"Missing registry\" the moment a world loads");
		}
	}

	private static final java.util.concurrent.atomic.AtomicBoolean DATAPACK_REGISTRIES_DECLARED =
			new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Puts NeoForge's synced datapack registries back into {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES}, the
	 * list both ends sync from: the server packs each entry of it for the client, and the client builds each one.
	 *
	 * <p>NeoForge's {@code <clinit>} makes that field a live view of its own networkable list, and
	 * {@code DataPackRegistryEvent} adds every registry declared with a network codec to it. fabric-api's
	 * {@code DynamicRegistriesImpl.registerSynced} replaces the field with an {@code ArrayList} copy the first time a
	 * Fabric mod syncs a registry of its own, and on a Forbric client the Fabric mains run before NeoForge's
	 * declaration — so every NeoForge mod's synced registry was left out of the copy. The server never sent it, the
	 * client never built it, and the first lookup threw: Create's {@code create:potato_projectile/type} crashed
	 * the client building the creative search tree ("Missing registry"). Each NeoForge entry the list lacks by key
	 * is appended; a list that is still NeoForge's view lacks none. Under {@code -Dforbric.datapackRegistryReconcile}.
	 */
	@SuppressWarnings("unchecked")
	private static void reconcileSynchronizedRegistries(ClassLoader cl, Class<?> hooksCls) {
		try {
			Class<?> loaderCls = Class.forName(DatapackRegistryDeclaration.LOADER, false, cl);
			Class<?> dataCls = Class.forName("net.minecraft.resources.RegistryDataLoader$RegistryData", false, cl);
			Method key = dataCls.getMethod("key");
			Field networkable = hooksCls.getDeclaredField("NETWORKABLE_REGISTRIES");
			networkable.setAccessible(true);
			Field syncedField = loaderCls.getField("SYNCHRONIZED_REGISTRIES");
			java.util.List<Object> synced = (java.util.List<Object>) syncedField.get(null);
			java.util.List<Object> copy = new java.util.ArrayList<>(synced);
			java.util.List<Object> added = DatapackRegistryDeclaration.reconcile((java.util.List<?>) networkable.get(null),
					copy, data -> {
						try {
							return key.invoke(data);
						} catch (ReflectiveOperationException e) {
							throw new IllegalStateException(e);
						}
					}, copy::add, null);
			if (added.isEmpty()) return;
			try {
				synced.addAll(added);
			} catch (UnsupportedOperationException unmodifiable) {
				Caught.expected(unmodifiable);
				syncedField.setAccessible(true);
				syncedField.set(null, copy);
			}
			java.util.List<String> keys = new java.util.ArrayList<>();
			for (Object data : added) keys.add(String.valueOf(key.invoke(data)));
			ForbricLog.info("[Forbric/Lifecycle] put %d NeoForge-synced datapack registr(ies) back into "
					+ "RegistryDataLoader.SYNCHRONIZED_REGISTRIES — fabric-api had replaced that live view with a copy "
					+ "before NeoForge's declaration, so the server would not send them and the client would not build "
					+ "them: %s", keys.size(), keys);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not check NeoForge's synced datapack registries against "
					+ "RegistryDataLoader.SYNCHRONIZED_REGISTRIES — a NeoForge mod's synced registry may be missing on "
					+ "the client");
		}
	}

	/**
	 * Declares to NeoForge whatever {@code RegistryDataLoader.WORLDGEN_REGISTRIES} ended up holding that NeoForge's
	 * list lacks — the entries a {@code <clinit>} TAIL injector added after NeoForge had already copied the list,
	 * which happens whenever the loader initialises before the hooks. See
	 * {@link DatapackRegistryDeclaration#reconcile} for why the order is not the kernel's to choose.
	 *
	 * <p>Through NeoForge's own {@code addRegistryCodec}, the method {@code NewRegistry.process()} ends in, with the
	 * loader's entry object itself and no network codec — exactly what the hooks-first copy would have put there.
	 * {@code -Dforbric.datapackRegistryReconcile=off} leaves NeoForge's list as it was copied.
	 */
	private static void reconcileLoaderRegistriesIntoNeoForge(ClassLoader cl, Class<?> hooksCls) {
		try {
			Class<?> loaderCls = Class.forName(DatapackRegistryDeclaration.LOADER, false, cl);
			Class<?> dataCls = Class.forName("net.minecraft.resources.RegistryDataLoader$RegistryData", false, cl);
			Class<?> wrapperCls = Class.forName(
					"net.neoforged.neoforge.registries.DataPackRegistryEvent$DataPackRegistryData", false, cl);
			Class<?> codecCls = Class.forName("com.mojang.serialization.Codec", false, cl);
			Method key = dataCls.getMethod("key");
			Constructor<?> wrap = wrapperCls.getDeclaredConstructor(dataCls, codecCls);
			wrap.setAccessible(true);
			Method add = hooksCls.getDeclaredMethod("addRegistryCodec", wrapperCls);
			add.setAccessible(true);
			Field worldgen = loaderCls.getDeclaredField("WORLDGEN_REGISTRIES");
			worldgen.setAccessible(true);

			java.util.List<?> loaderList = (java.util.List<?>) worldgen.get(null);
			java.util.List<?> neoList = (java.util.List<?>) hooksCls.getMethod("getDataPackRegistries").invoke(null);
			java.util.List<Object> replaced = new java.util.ArrayList<>();
			java.util.List<Object> declared = DatapackRegistryDeclaration.reconcile(loaderList, neoList,
					data -> {
						try {
							return key.invoke(data);
						} catch (ReflectiveOperationException e) {
							throw new IllegalStateException(e);
						}
					},
					data -> {
						try {
							add.invoke(null, wrap.newInstance(data, null));
						} catch (ReflectiveOperationException e) {
							throw new IllegalStateException(e);
						}
					}, replaced);
			if (!declared.isEmpty()) {
				java.util.List<String> keys = new java.util.ArrayList<>();
				for (Object data : declared) keys.add(String.valueOf(key.invoke(data)));
				ForbricLog.info("[Forbric/Lifecycle] reconciled %d datapack registr(ies) from RegistryDataLoader's own "
						+ "list into NeoForge's — the loader initialised before DataPackRegistriesHooks, so NeoForge "
						+ "copied the list before a mixin added these, and worlds load only NeoForge's list: %s",
						keys.size(), keys);
			}
			if (!replaced.isEmpty()) {
				ForbricLog.warn("[Forbric/Lifecycle] RegistryDataLoader's list and NeoForge's disagree on the entry for "
						+ "%s — NeoForge's is the one worlds load, so a mixin that replaced it in place is not in effect",
						replaced);
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not reconcile RegistryDataLoader's list with NeoForge's — a "
					+ "registry a mixin added to the loader may be missing at world load");
		}
	}

	/**
	 * The same mirror in the other direction: Fabric-declared dynamic registries into NeoForge's list.
	 *
	 * <p><b>Both directions are needed because which list wins is not ours to decide.</b>
	 * {@link #mirrorIntoFabricDynamicRegistries} exists because fabric-api's {@code WorldLoaderMixin} replaces the
	 * loader's argument with Fabric's own list. That mixin stopped applying at NeoForge 26.2.0.88, which widened
	 * {@code RegistryDataLoader.load} from four parameters to five — fabric-api is compiled against vanilla's
	 * four-parameter signature, so its {@code @At(INVOKE)} anchor no longer resolves. Nothing about that is
	 * reported as an error: the mixin simply applies partially, Fabric's substitution never happens, NeoForge's
	 * list is used as-is, and every registry a FABRIC mod declared is missing at world load.
	 *
	 * <p>What that cost, measured: lithostitched declares {@code lithostitched:fast_noise_config} through Fabric's
	 * API, and its own worldgen regions then failed to parse with
	 * {@code Registry does not exist: ResourceKey[minecraft:root / lithostitched:fast_noise_config]} — nested four
	 * levels deep inside a density-function {@code Codec.either}, which is where the real message was hiding —
	 * and the server refused to load its datapacks at all.
	 *
	 * <p>Mirroring both ways makes the instance correct under either outcome: whichever list {@code WorldLoader}
	 * ends up passing, it holds every registry either ecosystem declared. Declared through a second
	 * {@code NewRegistry} event rather than by touching NeoForge's private list, so NeoForge's own bookkeeping
	 * ({@code DataPackRegistriesHooks.addRegistryCodec}) runs exactly as it does for its own mods.
	 *
	 * <p>Unsynced on purpose, for the same reason the other direction is: sync is a separate path that the
	 * declaring side already owns, and claiming it twice puts the registry in both synced sets, which the client's
	 * configuration-phase collector reads twice and dies on.
	 */
	private static void mirrorFabricDynamicRegistriesIntoNeoForge(ClassLoader cl, Class<?> eventCls,
			Class<?> hooksCls) {
		try {
			Class<?> dynamicCls = Class.forName(
					"net.fabricmc.fabric.api.event.registry.DynamicRegistries", false, cl);
			Class<?> keyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
			Class<?> codecCls = Class.forName("com.mojang.serialization.Codec", false, cl);
			Class<?> dataCls = Class.forName("net.minecraft.resources.RegistryDataLoader$RegistryData", false, cl);
			Method key = dataCls.getMethod("key");
			Method elementCodec = dataCls.getMethod("elementCodec");

			java.util.Set<Object> alreadyNeo = new java.util.HashSet<>();
			for (Object data : (java.util.List<?>) hooksCls.getMethod("getDataPackRegistries").invoke(null)) {
				alreadyNeo.add(key.invoke(data));
			}

			Object event = eventCls.getConstructor().newInstance();
			Method declare = eventCls.getMethod("dataPackRegistry", keyCls, codecCls);
			java.util.List<String> mirrored = new java.util.ArrayList<>();
			for (Object data : (java.util.List<?>) dynamicCls.getMethod("getDynamicRegistries").invoke(null)) {
				Object registryKey = key.invoke(data);
				if (!alreadyNeo.add(registryKey)) continue;
				declare.invoke(event, registryKey, elementCodec.invoke(data));
				mirrored.add(String.valueOf(registryKey));
			}
			if (mirrored.isEmpty()) return;

			Method process = eventCls.getDeclaredMethod("process");
			process.setAccessible(true);
			process.invoke(event);
			ForbricLog.info("[Forbric/Lifecycle] mirrored %d Fabric-declared datapack registr(ies) into NeoForge's "
					+ "list too — from NeoForge 26.2.0.88 the loader's argument is NeoForge's own (fabric-api's "
					+ "WorldLoaderMixin no longer anchors on the widened RegistryDataLoader.load), so a "
					+ "Fabric-declared registry is invisible at world load without this: %s",
					mirrored.size(), mirrored);
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] fabric-api dynamic registries absent — nothing to mirror back");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not mirror Fabric's datapack registries into NeoForge's "
					+ "list — a Fabric mod's worldgen registry may be missing at world load");
		}
	}

	/**
	 * Mirrors the NeoForge-declared datapack registries into Fabric API's {@code DynamicRegistries}, so both
	 * ecosystems' worldgen registries exist whichever list the world loader ends up reading.
	 *
	 * <p><b>Why this is needed at all.</b> The base is NeoForge-patched, so {@code WorldLoader} passes
	 * {@code DataPackRegistriesHooks.getDataPackRegistries()} to {@code RegistryDataLoader}. But fabric-api's
	 * {@code fabric-registry-sync-v0} ships {@code WorldLoaderMixin.modifyLoadedEntries}, which REPLACES that
	 * argument with {@code DynamicRegistries.getBootstrappingRegistries()} — its own list, built only from what was
	 * registered through Fabric's API. On a Fabric-only or NeoForge-only instance exactly one of the two lists is
	 * live and everything works; put both packs in one instance and Fabric's wins, silently dropping every
	 * NeoForge-declared registry. lithostitched then died at world load with
	 * {@code Missing registry: lithostitched:worldgen_modifier} even though the declaration was demonstrably present
	 * in {@code DataPackRegistriesHooks} — which is what made this so hard to see.
	 *
	 * <p>So the kernel registers each one on the Fabric side too. Synced-ness is carried across rather than
	 * guessed: NeoForge records it in {@code getSyncedCustomRegistries()}, and Fabric splits the two into
	 * {@code register} and {@code registerSynced}.
	 *
	 * <p>No-ops when fabric-api is absent — then nothing rewrites the list and NeoForge's own is used as-is.
	 * Best-effort: a failure here costs a mod's worldgen registry, and is reported, but must not fail the boot.
	 */
	private static void mirrorIntoFabricDynamicRegistries(ClassLoader cl, java.util.List<?> added) {
		if (added.isEmpty()) return;
		try {
			Class<?> dynamicCls = Class.forName(
					"net.fabricmc.fabric.api.event.registry.DynamicRegistries", false, cl);
			Class<?> keyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
			Class<?> codecCls = Class.forName("com.mojang.serialization.Codec", false, cl);
			Class<?> dataCls = Class.forName("net.minecraft.resources.RegistryDataLoader$RegistryData", false, cl);
			Method key = dataCls.getMethod("key");
			Method elementCodec = dataCls.getMethod("elementCodec");
			Method register = dynamicCls.getMethod("register", keyCls, codecCls);

			// Fabric throws on a duplicate key, and a Fabric mod may legitimately have registered the same registry.
			java.util.Set<Object> alreadyFabric = new java.util.HashSet<>();
			for (Object data : (java.util.List<?>) dynamicCls.getMethod("getDynamicRegistries").invoke(null)) {
				alreadyFabric.add(key.invoke(data));
			}
			java.util.List<String> mirrored = new java.util.ArrayList<>();
			for (Object data : added) {
				Object registryKey = key.invoke(data);
				if (!alreadyFabric.add(registryKey)) continue;
				// PLAIN register, never registerSynced — even for a registry NeoForge does sync. The mirror exists
				// for ONE reason: fabric-api's WorldLoaderMixin substitutes its own list at world load, so a
				// NeoForge-declared registry has to appear in that list to survive. Sync is a different path and
				// NeoForge already owns it for its own registries; claiming it on Fabric's side too puts the
				// registry in BOTH synced sets, and the client's configuration-phase collector then reads it twice
				// and dies on "Duplicate key ResourceKey[minecraft:root / bagus_lib:dialog]" inside
				// ImmutableRegistryAccess — a join failure reported only as "Network Protocol Error".
				register.invoke(null, registryKey, elementCodec.invoke(data));
				mirrored.add(String.valueOf(registryKey));
			}
			if (!mirrored.isEmpty()) {
				ForbricLog.info("[Forbric/Lifecycle] mirrored %d datapack registr(ies) into Fabric's list too — "
						+ "fabric-api's WorldLoaderMixin replaces the loader's argument with its own, so a "
						+ "NeoForge-declared registry is invisible at world load without this: %s",
						mirrored.size(), mirrored);
			}
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] fabric-api dynamic registries absent — NeoForge's list stands");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not mirror datapack registries into Fabric's list — a "
					+ "NeoForge mod's worldgen registry may be missing at world load");
		}
	}

	/**
	 * Posts {@code FMLCommonSetupEvent} → (client) {@code FMLClientSetupEvent} → {@code FMLLoadCompleteEvent} at
	 * every NeoForge mod the kernel loaded, each on that mod's own bus, running the deferred work between phases.
	 *
	 * <p>Scoped to GUEST mods on purpose: NeoForge's own baseline setup is already driven by the kernel's explicit
	 * steps above (registries, network, config, internal subscribers), and posting these at the baseline too would
	 * re-run work the kernel has taken over. Extend only with a measured reason.
	 *
	 * <p>Best-effort per phase and per mod — a mod that throws in its own setup must not abort the boot, exactly as
	 * genuine FML collects such failures rather than dying at the first one.
	 */
	private static void fireModSetupLifecycle(ClassLoader cl, Side side) {
		java.util.Map<String, KernelModLoader.NeoIdentity> mods = KernelModLoader.publishedNeoMods();
		// NOT an early return on an empty NeoForge set: fireRegistrationEvents runs NeoForge's own
		// RegistrationEvents.init, which a pack with no NeoForge mod still needs (capabilities, data maps), and the
		// load report below is written for every pack. fireSetupPhase no-ops on an empty set by itself.

		// On the CLIENT every phase, common setup included, is deferred to fireClientSetupLifecycle. This method
		// runs BEFORE `new Minecraft(...)`, so Minecraft.getInstance() is still null here — and common setup is
		// exactly where a mod does its dist-guarded client initialisation: caching the singleton into a static
		// field, or handing work to Minecraft.execute. Genuine NeoForge posts common setup from
		// ClientModLoader.finish(), inside Minecraft's own constructor, where the singleton exists. Posting it
		// here handed those mods a null and the failure surfaced later, in rendering, with nothing pointing back.
		if (side.isClient()) return;

		fireSetupPhase(cl, mods, ForeignType.FML_COMMON_SETUP_EVENT, "common setup");
		// The sided phase. The kernel used to jump straight from common setup to load complete, so on a dedicated
		// server this event was never posted to anyone at all.
		fireSetupPhase(cl, mods, ForeignType.FML_DEDICATED_SERVER_SETUP_EVENT, "dedicated server setup");
		fireRegistrationEvents(cl);
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_ENQUEUE_EVENT, "IMC enqueue");
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_PROCESS_EVENT, "IMC process");
		fireSetupPhase(cl, mods, ForeignType.FML_LOAD_COMPLETE_EVENT, "load complete");

		// Loading is over on this side, so whatever went wrong during it is now the whole story rather than a
		// partial one. A clean run writes no file and says one line.
		net.forbric.kernel.access.AccessCensus.report();
		KernelLoadReport.write();
		net.forbric.kernel.ui.CompatibilityDecision.requireContinuation(false);
	}

	/**
	 * Runs NeoForge's own {@code RegistrationEvents.init()} — the "Registration events" task of
	 * {@code CommonModLoader.load}, between the sided setup and load complete.
	 *
	 * <p>One call, and a surprising amount behind it. It posts {@code RegisterCapabilitiesEvent} and
	 * {@code RegisterDataMapTypesEvent}, and initialises five NeoForge built-ins besides — cauldron fluid content
	 * and interactions, forced chunks, data component modifiers, POI extension. The kernel drives the lifecycle
	 * itself and never replaced this step, so NOT ONE capability was registered in a Forbric instance, NeoForge's
	 * own vanilla providers included, and no mod's data maps existed.
	 *
	 * <p>Called through NeoForge's method rather than reimplemented: the contents are its internals, they change
	 * between versions, and a hand-rolled copy would rot silently. Its steps are run one at a time where its
	 * bytecode allows — {@link RegistrationEventSteps} — so one failing step no longer takes the rest with it.
	 * Once only — the client and server paths each reach this point, and both must not run it.
	 */
	private static void fireRegistrationEvents(ClassLoader cl) {
		if (!REGISTRATION_EVENTS_FIRED.compareAndSet(false, true)) return;
		RegistrationEventSteps.Outcome outcome;
		try {
			outcome = RegistrationEventSteps.fire(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not run NeoForge's registration events — capabilities and "
					+ "data maps will be missing");
			return;
		}
		if (outcome == null) return;
		// Outside the call, and waiting only on the step it needs. It used to follow init in the same try, so on the
		// sweep pack's client a data-map listener's failure also cost the cross-ecosystem transfer bridge, which has
		// nothing to do with data maps; it is installed "after native capability registration", and that is the one
		// step it is gated on now.
		if (outcome.capabilitiesRegistered()) {
			KernelTransferInterop.install(cl);
		} else if (KernelTransferInterop.active()) {
			ForbricLog.warn("[Forbric/Transfer] not initialized: NeoForge's capability registration failed, and the "
					+ "bridge answers after the native providers it registers");
			CompatibilityFindings.record(new CompatibilityFinding("transfer-initialization", "forbric",
					"Cross-ecosystem item and fluid transfer", "KernelTransferInterop",
					CompatibilityFinding.Confidence.CONFIRMED, true,
					"NeoForge's capability registration failed, so the transfer APIs were not connected; foreign "
							+ "storage is unavailable.", List.of("capability registration failed")));
		}
	}

	private static final java.util.concurrent.atomic.AtomicBoolean REGISTRATION_EVENTS_FIRED =
			new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Posts a no-arg mod-bus event at every published container, one container at a time.
	 *
	 * <p>It used to hand the event to {@code ModLoader.postEvent}, NeoForge's own fan-out. That walks the ModList
	 * in order and calls {@code ModContainer.acceptEvent}, which rethrows the first listener failure as a
	 * {@code ModLoadingException} — so one mod throwing ended the fan-out and every mod AFTER it in the list never
	 * saw the event, with a single kernel WARN naming the event and not the mod. For
	 * {@code BlockEntityTypeAddBlocksEvent} that means a mod's blocks are simply never attached to the vanilla
	 * block entity they extend, silently, because of a different mod's bug.
	 *
	 * <p>Genuine NeoForge is entitled to that behaviour: it turns the exception into a loading-error SCREEN and
	 * stops. The kernel does not have that screen and carries on booting, so aborting the fan-out costs mods their
	 * registration and tells nobody. Dispatching per container is the same delivery with the failure contained,
	 * and each failure names the mod it belongs to.
	 *
	 * <p>The baseline container goes first, as it does in {@code ModList}, so NeoForge's own handlers still run
	 * before the mods'. And it is delivered phase by phase, as {@code ModLoader.postEvent} does: every mod's
	 * {@code HIGHEST} listeners, then every mod's {@code HIGH}, and so on. One container at a time with all its phases
	 * would let an earlier mod's {@code LOWEST} listener run before a later mod's {@code HIGHEST}; for an event whose
	 * registration order is draw order ({@code RegisterTooltipAppendersEvent}) that is visible on screen.
	 */
	private static void postModBusEvent(ClassLoader cl, String eventClassName) {
		Object event;
		Method acceptEvent;
		try {
			Class<?> eventCls = Class.forName(eventClassName, false, cl);
			Class<?> baseEvent = Class.forName("net.neoforged.bus.api.Event", false, cl);
			event = eventCls.getConstructor().newInstance();
			acceptEvent = modContainerClass(cl).getMethod("acceptEvent", baseEvent);
		} catch (ClassNotFoundException | NoSuchMethodException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping", eventClassName);
			return;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not build " + eventClassName);
			return;
		}
		deliverModBusEvent(cl, acceptEvent, event, eventClassName);
	}

	/**
	 * The same per-container delivery for a mod-bus event game code built itself and would have handed to
	 * {@code ModLoader.postEvent} — {@code ItemTooltipHandler.init}'s {@code RegisterTooltipAppendersEvent}. Returns
	 * how many containers took it.
	 */
	public static int postModBusEvent(Object event) {
		ClassLoader cl = event.getClass().getClassLoader();
		Method acceptEvent;
		try {
			acceptEvent = modContainerClass(cl).getMethod("acceptEvent", Class.forName("net.neoforged.bus.api.Event", false, cl));
		} catch (ReflectiveOperationException absent) {
			Caught.warn(absent, "[Forbric/Lifecycle] no NeoForge mod container to post %s through",
					event.getClass().getName());
			return 0;
		}
		return deliverModBusEvent(cl, acceptEvent, event, event.getClass().getName());
	}

	/**
	 * {@code ModLoader.postEventWrapContainerInModOrder}'s delivery: for every priority phase, every container in turn —
	 * NeoForge's baseline first, then the NeoForge mods the kernel loaded in {@code ModList}'s order (presence aliases have
	 * no listener of their own and are left out) — with that container active while it takes the event when
	 * {@code activeContainer} is set, as NeoForge's wrap does. With {@code stopAtFirstFailure}, as NeoForge's own fan-out,
	 * the first container that throws ends the delivery and its failure (NeoForge's {@code ModLoadingException} naming the
	 * mod) leaves here; otherwise a container that throws is reported once and skipped for the event's remaining phases.
	 * Returns how many containers took it.
	 */
	private static int deliverInModOrder(ClassLoader cl, Object event, boolean activeContainer, boolean stopAtFirstFailure,
			java.util.function.Function<String, String> failure) {
		java.util.Map<Object, String> ordered = new java.util.LinkedHashMap<>();
		if (baselineContainer != null) ordered.put(baselineContainer, "neoforge");
		java.util.Map<Object, String> published = new java.util.IdentityHashMap<>();
		KernelModLoader.publishedNeoMods().forEach((id, identity) -> published.put(identity.container(), id));
		try {
			Class<?> modListCls = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, cl);
			Object modList = modListCls.getMethod("get").invoke(null);
			for (Object c : (List<?>) modListCls.getMethod("getSortedMods").invoke(modList)) {
				String id = published.get(c);
				if (id != null && !ordered.containsKey(c)) ordered.put(c, id);
			}
		} catch (Throwable t) {
			Caught.expected(t);
		}
		published.forEach((c, id) -> ordered.putIfAbsent(c, id));

		Method phased;
		Object[] phases;
		Method setActive = null;
		Object context = null;
		try {
			Class<?> eventBase = Class.forName("net.neoforged.bus.api.Event", false, cl);
			Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority", false, cl);
			phased = modContainerClass(cl).getMethod("acceptEvent", priority, eventBase);
			phases = priority.getEnumConstants();
			if (activeContainer) {
				Class<?> loading = Class.forName("net.neoforged.fml.ModLoadingContext", false, cl);
				context = loading.getMethod("get").invoke(null);
				setActive = loading.getMethod("setActiveContainer", modContainerClass(cl));
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] no NeoForge mod container to post %s through", event.getClass().getName());
			return 0;
		}
		java.util.Set<Object> failed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Object phase : phases) {
			for (java.util.Map.Entry<Object, String> e : ordered.entrySet()) {
				if (failed.contains(e.getKey())) continue;
				try {
					if (setActive != null) setActive.invoke(context, e.getKey());
					try {
						phased.invoke(e.getKey(), phase, event);
					} finally {
						if (setActive != null) setActive.invoke(context, (Object) null);
					}
				} catch (Throwable perMod) {
					failed.add(e.getKey());
					Throwable cause = perMod instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
							? ite.getCause() : perMod;
					if (stopAtFirstFailure) {
						ForbricLog.error("%s", failure.apply(e.getValue()));
						throw RegistrationStop.propagate(cause);
					}
					Caught.warn(cause, failure.apply(e.getValue()));
				}
			}
		}
		return ordered.size() - failed.size();
	}

	private static int deliverModBusEvent(ClassLoader cl, Method acceptEvent, Object event, String eventClassName) {
		java.util.Map<String, Object> byId = new java.util.LinkedHashMap<>();
		if (baselineContainer != null) byId.put("neoforge", baselineContainer);
		KernelModLoader.publishedNeoMods().forEach((id, identity) -> byId.put(id, identity.container()));
		if (byId.isEmpty()) return 0;

		// Phase by phase where the carrier offers it (ModContainer.acceptEvent(EventPriority, Event)); otherwise each
		// container with all its phases.
		Method phased = null;
		Object[] phases = null;
		try {
			Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority", false, cl);
			phased = modContainerClass(cl).getMethod("acceptEvent", priority, Class.forName("net.neoforged.bus.api.Event", false, cl));
			phases = priority.getEnumConstants();
		} catch (ReflectiveOperationException | LinkageError single) {
			Caught.expected(single);
			ForbricLog.debug("[Forbric/Lifecycle] no phased acceptEvent — %s goes to each container whole", eventClassName);
		}
		java.util.Set<String> failed = new java.util.HashSet<>();
		for (Object phase : phases == null ? new Object[] {null} : phases) {
			for (java.util.Map.Entry<String, Object> e : byId.entrySet()) {
				if (failed.contains(e.getKey())) continue;
				try {
					if (phase == null) acceptEvent.invoke(e.getValue(), event);
					else phased.invoke(e.getValue(), phase, event);
				} catch (Throwable perMod) {
					failed.add(e.getKey());
					Caught.warn(perMod, "[Forbric/Lifecycle] " + e.getKey() + " threw during " + eventClassName
							+ " — its own registration from that event is lost, every other mod still gets it");
				}
			}
		}
		int delivered = byId.size() - failed.size();
		ForbricLog.debug("[Forbric/Lifecycle] posted %s to %d container(s)", eventClassName, delivered);
		return delivered;
	}

	/**
	 * Posts {@code FMLClientSetupEvent} → {@code FMLLoadCompleteEvent} from INSIDE {@code Minecraft.<init>}, which is
	 * the window genuine NeoForge uses (its {@code ClientModLoader.begin} runs there) and the only one where
	 * {@code Minecraft.getInstance()} is live.
	 *
	 * <p>This is the same bug the Fabric client entrypoints already moved for, one ecosystem later. Fired from the
	 * kernel's pre-{@code Minecraft} registration window instead, the whole setup lifecycle ran with the singleton
	 * still null, and it cost three distinct failures on the Odyssey pack:
	 * <ul>
	 *   <li>five mods threw {@code "Render layers can only be set during client loading!"} — NeoForge gates that on
	 *       a flag only set inside its own client-loading window;</li>
	 *   <li>{@code DeferredWorkQueue.runTasks} then threw, so client setup never completed for anyone;</li>
	 *   <li>CreativeCore's {@code GuiStyle.<clinit>} caches {@code Minecraft.getInstance()} into a static and got
	 *       null, so every {@code GuiStyle.reload} threw at its first line. Its {@code catch} covers the whole
	 *       method body — including the {@code clearRegistry} it never reached — and then re-registers the default
	 *       style, so the SECOND resource reload died on {@code 'default' already exists} and took the client with
	 *       it. One null static, three layers deep.</li>
	 * </ul>
	 *
	 * <p>Deliberately outside the reopened-registry window {@link #onClientEntrypoints} holds for Fabric: on genuine
	 * NeoForge these two phases run with the registries FROZEN, and a mod registering content from them is expected
	 * to fail. Best-effort and once-only, so a second reload cannot re-post them.
	 */
	private static void fireClientSetupLifecycle(ClassLoader cl) {
		if (!CLIENT_SETUP_FIRED.compareAndSet(false, true)) return;
		java.util.Map<String, KernelModLoader.NeoIdentity> mods = KernelModLoader.publishedNeoMods();
		// NOT an early return on an empty NeoForge set: the registration events, the shim delivery and the load
		// report below run for every pack.
		// Common setup FIRST, and on the client it is posted from here rather than from the pre-Minecraft window
		// — the same move the client setup phases themselves already made, one phase earlier. Before the sided
		// phase, which is the order genuine NeoForge's CommonModLoader.load uses.
		fireSetupPhase(cl, mods, ForeignType.FML_COMMON_SETUP_EVENT, "common setup");
		fireSetupPhase(cl, mods, ForeignType.FML_CLIENT_SETUP_EVENT, "client setup");
		// A mod that is not a NeoForge mod but was handed the NeoForge build of a multi-loader library registered
		// on a bus of its own, which is in no ModList and which NeoForge's own fan-out therefore never reaches.
		// Here, not from the window that handed out the container: that one is inside Minecraft.<init> and runs
		// before Minecraft.options exists, so the key mappings would have nowhere to land.
		KernelForeignShimContext.deliver(cl);
		// Same tail as the server's, and the same order CommonModLoader.load uses: sided setup, then the
		// registration events, then IMC, then load complete.
		fireRegistrationEvents(cl);
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_ENQUEUE_EVENT, "IMC enqueue");
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_PROCESS_EVENT, "IMC process");
		fireSetupPhase(cl, mods, ForeignType.FML_LOAD_COMPLETE_EVENT, "load complete");

		// The client's own end of loading. Same reason as the server twin: at this point what went wrong is the
		// whole story, and this is the last moment before the player is looking at a title screen.
		net.forbric.kernel.access.AccessCensus.report();
		KernelLoadReport.write();
		requireClientContinuation(cl);
	}

	/**
	 * The client's end-of-loading decision, which runs inside {@code Minecraft.<init>}.
	 *
	 * <p>A refusal cannot leave as itself from here. The base's {@code Main.main} wraps {@code new Minecraft(..)}
	 * in a catch-all that builds an "Initializing game" crash report and ends in {@code Minecraft.crash(.., -1)} —
	 * {@code System.exit} before {@link CompatibilityLaunchBoundary} is ever reached — so the player's Quit, or a
	 * strict stop, arrived as a game crash with a crash report and exit status 255. The same try has exactly one
	 * quiet way out, ahead of the catch-all: {@code SilentInitException}, whose handler shuts the executors down,
	 * logs, and RETURNS. Leaving through it lets {@code Main.main} return normally, and the boundary then turns the
	 * recorded refusal into the typed exit 78 with no crash report. The policy stop stays the cause.
	 */
	static void requireClientContinuation(ClassLoader game) {
		try {
			net.forbric.kernel.ui.CompatibilityDecision.requireContinuation(true);
		} catch (net.forbric.kernel.ui.CompatibilityDecision.LaunchStopped stop) {
			throw CompatibilityLaunchBoundary.insideClientMain(game, stop);
		}
	}

	private static final java.util.concurrent.atomic.AtomicBoolean CLIENT_SETUP_FIRED =
			new java.util.concurrent.atomic.AtomicBoolean();

	private static void fireSetupPhase(ClassLoader cl, java.util.Map<String, KernelModLoader.NeoIdentity> mods,
			ForeignType event, String label) {
		// The empty guard comes before the game-side class is named: without it every phase would build a DeferredWorkQueue, hand it to the sync
		// executor and log "posted FML <phase> to 0 NeoForge mod(s)" eight times on a pack that has no NeoForge mod.
		if (mods.isEmpty()) return;
		try {
			int fired = (int) Class.forName("net.forbric.kernel.runtime.KernelNeoSetup", true, cl)
					.getMethod("firePhase", java.util.Map.class, ForeignType.class, String.class)
					.invoke(null, mods, event, label);
			ForbricLog.info("[Forbric/Lifecycle] posted FML %s to %d NeoForge mod(s)", label, fired);
		} catch (ClassNotFoundException | NoClassDefFoundError absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", event, label);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not post FML " + label);
		}
	}

	/** The game-side {@code net.neoforged.fml.ModContainer} class. */
	private static Class<?> modContainerClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
	}

	/** The game-side half of NeoForge's registry phase. */
	private static Class<?> neoRegistryClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.forbric.kernel.runtime.KernelNeoRegistries", true, cl);
	}

	/**
	 * Posts NeoForge's {@code NewRegistryEvent} to every mod bus, then fills it — the phase before
	 * {@code RegisterEvent} where mods create their own registries.
	 *
	 * <p>The kernel used to drive NeoForge's own handler directly in {@link PassiveSeeder}, so no NeoForge MOD ever received it. Two things
	 * break without it: a mod that declares a custom registry never gets one, and — less obviously — mods use this
	 * earliest mod-bus phase for setup that later phases depend on. WhiteNoise loads its config here (its own spec,
	 * outside NeoForge's ConfigTracker, so no amount of {@code ConfigTracker.loadConfigs} substitutes), which is why
	 * Mob Champions could read a config value from its {@code RegisterEvent} listener on genuine NeoForge but threw
	 * "Cannot get config value before config is loaded" here.
	 *
	 * <p>One event instance is posted to every bus and filled once, matching NeoForge, which collects each mod's
	 * registries into a single event and registers them together. Per-bus failures are isolated for the same reason
	 * as the {@code RegisterEvent} dispatch.
	 */
	private static void postNeoNewRegistryEvent(ClassLoader cl, List<Object> buses) {
		try {
			int delivered = (int) neoRegistryClass(cl).getMethod("postNewRegistryEvent", List.class)
					.invoke(null, buses);
			ForbricLog.info("[Forbric/Lifecycle] posted NewRegistryEvent to %d NeoForge mod bus(es) — mods create "
					+ "their own registries (and do their earliest mod-bus setup) before RegisterEvent", delivered);
		} catch (ClassNotFoundException | NoClassDefFoundError absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] no NewRegistryEvent type — skipping");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not post NeoForge NewRegistryEvent");
		}
	}


	private static void unfreeze(ClassLoader cl) {
		invokeGameDataOn(cl, ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), "unfreezeData");
	}

	/**
	 * NeoForge's {@code GameData.freezeData}: for every {@code MappedRegistry} in {@code BuiltInRegistries.REGISTRY}
	 * it calls {@code bindAllTagsToEmpty()} then {@code freeze()}, then {@code RegistryManager.takeFrozenSnapshot()}.
	 */
	private static void freeze(ClassLoader cl) {
		invokeGameDataOn(cl, ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), "freezeData");
		latchRegistriesLoaded(cl);
		describeFreeze(cl);
	}

	/**
	 * One count line: how many registries were frozen and how many tag keys NeoForge bound to empty. Read from
	 * {@code MappedRegistry.frozenTags}, NOT {@code getTags()}/{@code listTags()} — those read {@code allTags},
	 * which stays unbound until the tag reload and would count zero on a correct boot. The words
	 * "froze the registries NeoForge-first" stay as they were when a second family's pass followed: gates read them.
	 */
	private static void describeFreeze(ClassLoader cl) {
		int registries = 0;
		int bound = 0;
		String boundText;
		try {
			Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Field field = builtIn.getDeclaredField("REGISTRY");
			field.setAccessible(true);
			Object root = field.get(null);
			Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", false, cl);
			Field frozenTags = mapped.getDeclaredField("frozenTags");
			frozenTags.setAccessible(true);
			for (Object registry : (Iterable<?>) root) {
				if (!mapped.isInstance(registry)) continue;
				registries++;
				Object tags = frozenTags.get(registry);
				if (!(tags instanceof java.util.Map<?, ?> map)) continue;
				for (Object named : map.values()) {
					try {
						if (Boolean.TRUE.equals(named.getClass().getMethod("isBound").invoke(named))) bound++;
					} catch (ReflectiveOperationException ignored) {
						// an unexpected HolderSet shape: not counted, never fatal
						Caught.expected(ignored);
					}
				}
			}
			boundText = Integer.toString(bound);
		} catch (Throwable unreadable) {
			Caught.expected(unreadable);
			if (registries == 0) return;
			boundText = "?";
		}
		ForbricLog.info("[Forbric/Lifecycle] froze the registries NeoForge-first: %d registr%s, %s tag key(s) bound to "
				+ "empty until the tag reload", registries, registries == 1 ? "y" : "ies", boundText);
	}

	/**
	 * Flips NeoForge's {@code registriesLoaded} latch, which nothing else in the kernel ever reaches.
	 *
	 * <p>It is a ONE-WAY latch, not a window: {@code CommonModLoader} initialises it false and sets it true once,
	 * immediately after {@code GameData.freezeData()}, and nothing ever clears it. The only writer lives inside
	 * {@code CommonModLoader.begin}, which the kernel excises because that same method drives the discovery and
	 * registration the kernel owns — so the flag stayed false for the whole process.
	 *
	 * <p>Mods read it through {@code ClientModLoader.areRegistriesLoaded()} to check they are inside the loading
	 * phase before touching render layers or anything else registration-shaped. With it false forever, Useful Food
	 * threw {@code "Render layers can only be set during client loading!"} out of its own client setup — from a
	 * helper it had copied from NeoForge, message and all, which is why the string is nowhere in the game jar.
	 *
	 * <p>Set here, right after the freeze, so it matches NeoForge's own placement and covers the dedicated server
	 * too. Best-effort: an older runtime without the field must not cost anyone the registration window.
	 */
	private static void latchRegistriesLoaded(ClassLoader cl) {
		try {
			Class<?> common = Class.forName("net.neoforged.neoforge.internal.CommonModLoader", false, cl);
			Field flag = common.getDeclaredField("registriesLoaded");
			flag.setAccessible(true);
			if (Boolean.TRUE.equals(flag.get(null))) return;
			flag.setBoolean(null, true);
			ForbricLog.debug("[Forbric/Lifecycle] latched CommonModLoader.registriesLoaded — mods gating on "
					+ "areRegistriesLoaded() can now register render layers and the like");
		} catch (ClassNotFoundException | NoSuchFieldException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] no CommonModLoader.registriesLoaded to latch: %s",
					String.valueOf(absent));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not latch CommonModLoader.registriesLoaded — mods gating on "
					+ "areRegistriesLoaded() will refuse to register");
		}
	}

	/**
	 * Opens or closes the ROOT registry, which NeoForge's {@code GameData} leaves alone.
	 *
	 * <p>Called immediately around the Fabric entrypoints rather than folded into {@link #unfreeze}: NeoForge's
	 * {@code NewRegistryEvent.fill()} registers the mod-declared registries into the root and re-freezes it on the
	 * way out, so an earlier unfreeze is undone before any Fabric code runs.
	 *
	 * <p>{@code unfreezeData} walks the registries INSIDE {@code BuiltInRegistries.REGISTRY} and unfreezes each;
	 * the root holding them stays frozen. That is fine for Forge and NeoForge, whose mods declare a new registry
	 * through {@code NewRegistryEvent} during a phase the kernel drives itself. Fabric has no such event — a mod
	 * calls {@code FabricRegistryBuilder.buildAndRegister()} straight from {@code onInitialize}, which is a plain
	 * {@code Registry.register} into the root. Lithostitched does exactly that and died on "Registry is already
	 * frozen (trying to add key minecraft:root / lithostitched:modifier_type)" inside the kernel's own window,
	 * where every other registry was open.
	 */
	private static void rootRegistry(ClassLoader cl, boolean open) {
		try {
			Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);

			// REGISTRY is the public read view (on a merged base it was Forge-wrapped); WRITABLE_REGISTRY is the
			// plain MappedRegistry that Registry.register actually validates against. Reopening only the first is
			// the mistake that leaves this looking fixed while "Registry is already frozen" keeps being thrown.
			for (String fieldName : new String[] {"REGISTRY", "WRITABLE_REGISTRY"}) {
				Field field;
				try {
					field = builtIn.getDeclaredField(fieldName);
				} catch (NoSuchFieldException notOnThisVersion) {
					Caught.expected(notOnThisVersion);
					continue;
				}
				field.setAccessible(true);
				Object root = field.get(null);
				if (root == null) continue;

				if (open) openRegistry(root); else root.getClass().getMethod("freeze").invoke(root);
			}
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] no BuiltInRegistries to reopen — skipping");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not " + (open ? "unfreeze" : "freeze") + " the root registry "
					+ "— a Fabric mod adding its own registry (FabricRegistryBuilder) may fail");
		}
	}

	/**
	 * Reopens one registry for writing, whichever ecosystem owns it.
	 *
	 * <p>{@code unfreeze()} is a Forge-family ADDITION: vanilla's {@code MappedRegistry} declares only
	 * {@code freeze()}, deliberately one-way. So a Forge-wrapped registry is reopened through its own method, and a
	 * plain vanilla one by clearing the private flag {@code validateWrite} reads.
	 */
	private static void openRegistry(Object registry) throws Exception {
		try {
			registry.getClass().getMethod("unfreeze").invoke(registry);
			return;
		} catch (NoSuchMethodException vanilla) {
			// falls through to the flag below
			Caught.expected(vanilla);
		}

		Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", false, registry.getClass()
				.getClassLoader());
		Field frozen = mapped.getDeclaredField("frozen");
		frozen.setAccessible(true);
		frozen.setBoolean(registry, false);
	}

	/**
	 * NeoForge's entity-attribute events ({@code CommonHooks.modifyAttributes}), with {@code DefaultAttributes} initialised
	 * first.
	 *
	 * <p>{@code EntityAttributeModificationEvent}'s constructor builds its entity-type list by streaming
	 * {@code BuiltInRegistries.ENTITY_TYPE} through {@code DefaultAttributes::hasSupplier}, so whatever first touches
	 * {@code DefaultAttributes} runs its class initialiser in the middle of that walk. Mods extend that initialiser: a Fabric
	 * mod that wraps the build of the supplier map with its own entities' attributes, whose classes register entity types
	 * as they initialise (Create Fly with fabric-api), changes the registry under the stream. The iterator throws
	 * {@code ConcurrentModificationException}, the event never finishes, NeoForge's own attributes ({@code swim_speed},
	 * {@code nametag_distance}) reach no entity, and the first living entity in water crashes.
	 *
	 * <p>Vanilla, and so Fabric, initialises the class in {@code Bootstrap.validate()}, before any mod event walks the
	 * entity types. NeoForge's {@code Bootstrap.validate} does not (javap on the genuine 26.2.0.93 base: only
	 * {@code checkBootstrapCalled}, the missing-translation report and {@code Commands.validate}), so this is the
	 * Fabric-on-NeoForge half of the order and stays: initialised here, on the same thread, just no longer inside the walk.
	 * A class already initialised is left as it is.
	 */
	static void postAttributeEvents(ClassLoader cl) {
		try {
			Class.forName("net.minecraft.world.entity.ai.attributes.DefaultAttributes", true, cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Attributes] DefaultAttributes could not initialise before the entity-attribute events — "
					+ "they read it for every entity type and will fail on it too");
		}
		java.lang.reflect.Method modify = staticHook(cl, "net.neoforged.neoforge.common.CommonHooks", "modifyAttributes");
		if (modify == null) return;
		try {
			modify.invoke(null);
		} catch (Throwable t) {
			// Its own catch, so its own Caught site: see invokeStaticOn.
			Caught.warn(t, "[Forbric/Lifecycle] net.neoforged.neoforge.common.CommonHooks.modifyAttributes() THREW — the "
					+ "entity-attribute events abort at the first failing mod, and every mod after it in the list gets no "
					+ "attributes");
		}
	}

	/**
	 * Invokes a no-arg static hook, tolerating its absence — for reaching past a method the kernel must not call.
	 *
	 * <p>The same contract as {@link #invokeGameDataOn}, at catch sites of its own. A {@link Caught} site is the catch that
	 * reports, and it prints its first message once and counts the rest: while this delegated, {@code modifyAttributes}
	 * failing after GameData's unfreeze or snapshot had failed was a count on their site, never its own line, so a gate's
	 * net over the log missed it (G-B1's wave-2 handoff, for the kernel lane).
	 */
	private static void invokeStaticOn(ClassLoader cl, String className, String method) {
		java.lang.reflect.Method hook = staticHook(cl, className, method);
		if (hook == null) return;
		try {
			hook.invoke(null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] " + className + "." + method + "() THREW — it exists and did not "
					+ "finish, so whatever it drives is half-done (a spawn-placement event aborts at the first failing mod, "
					+ "and every mod after it in the list is skipped)");
		}
	}

	/**
	 * The no-arg static {@code method} of {@code className}, or null: absent class at debug, a class without the method as
	 * a WARN naming both ("… has no …()", which gates' fatal nets match). The invocation is left to the caller, so a
	 * failure is reported at the caller's own catch site.
	 */
	private static java.lang.reflect.Method staticHook(ClassLoader cl, String className, String method) {
		Class<?> owner;
		try {
			owner = Class.forName(className, false, cl);
		} catch (ClassNotFoundException | LinkageError absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", className, method);
			return null;
		}
		try {
			return owner.getMethod(method);
		} catch (NoSuchMethodException gone) {
			Caught.warn(gone, "[Forbric/Lifecycle] %s has no %s() — the kernel expected that hook on this carrier, and "
					+ "whatever it does is now simply not done", className, method);
			return null;
		}
	}

	/**
	 * A no-arg static hook on one game-side class: NeoForge's {@code GameData} or another step of its loading.
	 *
	 * <p><b>ABSENT and FAILED are not the same thing, and this method used to report them the same way.</b> The
	 * absence of a class is ordinary (a test loader, a carrier without the step). A method that is THERE and THREW is the opposite of ordinary, and every caller here is a whole
	 * feature: the registry bake, the freeze/unfreeze pair, the vanilla snapshot. ({@code CommonHooks.modifyAttributes},
	 * {@code SpawnPlacements.fireSpawnPlacementEvent} and {@code GameRuleCategory.registerModdedCategories} were callers too;
	 * they now report at catch sites of their own, {@link #postAttributeEvents} and {@link #invokeStaticOn}.)
	 *
	 * <p>What that cost, and why it was invisible: {@code modifyAttributes} walks {@code ModList} in order and
	 * {@code ModContainer.acceptEvent} rethrows the first failure as a {@code ModLoadingException}, so ONE mod
	 * throwing in its {@code EntityAttributeCreationEvent} handler ends the dispatch — every mod after it in the
	 * list gets no attributes, its entities log "Entity … has no attributes" by the hundred and cannot spawn. The
	 * kernel's only trace of that was a {@code debug} line, off unless {@code -Dforbric.debug} is set.
	 *
	 * <p>So the two are split: a missing class stays at debug, an invocation that threw is a WARN naming the step
	 * and what it cost, with the real exception attached rather than its {@code toString}.
	 */
	private static void invokeGameDataOn(ClassLoader cl, String className, String method) {
		Class<?> owner;
		try {
			owner = Class.forName(className, false, cl);
		} catch (ClassNotFoundException | LinkageError absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", className, method);
			return;
		}
		java.lang.reflect.Method hook;
		try {
			hook = owner.getMethod(method);
		} catch (NoSuchMethodException gone) {
			Caught.warn(gone, "[Forbric/Lifecycle] %s has no %s() — the kernel expected that hook on this carrier, and "
					+ "whatever it does is now simply not done", className, method);
			return;
		}
		try {
			hook.invoke(null);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] " + className + "." + method + "() THREW — it exists and did not "
					+ "finish, so whatever it drives is half-done (a registry left open or unbaked, a snapshot not taken)");
		}
	}

	/**
	 * Called from the game side: {@code ClientModLoader.setupModResourcePacks(PackRepository)} inside
	 * {@code Minecraft.<init>}, redirected here by {@code ClientPackHookInjector}. This is the one correctly-timed
	 * handle on the live client {@code PackRepository} — before the first resource reload — so the kernel serves the
	 * ecosystem jars' assets (NeoForge's shaders, mods' textures/models) here.
	 *
	 * <p>It may be called more than once (on the old merged base both ecosystems' ClientModLoaders routed here);
	 * {@link KernelClientPacks#addTo} is cheap and the repository de-dups by pack id.
	 */
	public static void onClientResourcePacks(Object packRepository) {
		ClassLoader cl = gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
		List<Path> jars = new ArrayList<>(runtimeJars);
		jars.addAll(modJars);
		jars.addAll(kernelAssetJars);
		// The carriers are named as such: they are their ecosystems' own mod files, not mods anyone arbitrates.
		KernelClientPacks.addTo(packRepository, cl, jars, runtimeJars);
	}

	/**
	 * Called from the game side: the head of {@code ResourcePackLoader.populatePackRepository(...)}, prepended by
	 * {@code DataPackHookInjector}. This is the genuine loader's own choke point for mod packs, and the only place
	 * with a handle on the SERVER datapack repository before it is read.
	 *
	 * <p>Filters to {@code SERVER_DATA} here rather than in the injector: the same method builds the client resource
	 * repository too, and client assets are already served — correctly, and with synthesised metadata this path
	 * deliberately does not use — by {@link #onClientResourcePacks}. Serving them twice would put every ecosystem
	 * jar in the client repository under two ids.
	 *
	 * <p>Both the mod jars and the ecosystem carriers, in that priority order: see {@link KernelDataPacks} for why
	 * the carriers sit underneath, and which of them wins where the two of them disagree.
	 */
	public static void onServerDataPacks(Object packRepository, Object packType) {
		if (!(packType instanceof Enum<?> type) || !"SERVER_DATA".equals(type.name())) return;
		ClassLoader cl = gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
		KernelDataPacks.addTo(packRepository, packType, cl, modJars, runtimeJars);
	}

	/**
	 * Runs the Fabric client entrypoints with the registries REOPENED, because registering content from
	 * {@code onInitializeClient} is ordinary Fabric practice and it has to keep working here.
	 *
	 * <p>Injected into {@code Minecraft.<init>} (by {@code ClientEntrypointHookInjector}) just before {@code Options} is
	 * created — after the {@code Minecraft} singleton is set, so {@code Minecraft.getInstance()} is live but
	 * {@code getInstance().options} is still null. That is the exact window Fabric uses and that keymapping registration
	 * (and other instance-touching client setup) requires; running them earlier, in the pre-{@code Minecraft}
	 * registration window, NPE'd on a null instance (Jade's keybinds). Best-effort — a failing entrypoint must not abort
	 * client startup.
	 *
	 * <p>On Fabric both entrypoint phases run at the head of {@code Minecraft.<init>}, and the freeze does not
	 * happen until after that: {@code fabric-registry-sync-v0}'s {@code BootstrapMixin} redirects the
	 * {@code BuiltInRegistries.bootStrap()} call in {@code Bootstrap} to {@code createContents()} alone, and its client
	 * {@code MinecraftMixin} runs {@code bootStrap()} after the entrypoints. The kernel owns registration instead and
	 * keeps those mixins without their freeze ({@code FabricRegistryInitializationMixinAdapter}), so by the time this
	 * hook fires its own window has already closed and every registry is frozen — a mod registering here died, and if
	 * it swallowed the failure the damage surfaced far away. Xaero's World Map
	 * registers its status effects from {@code loadCommon()} and catches Throwable into a field, so the only symptom
	 * was {@code WorldMap.events} still being null a hundred ticks later, inside {@code Minecraft.runTick}.
	 *
	 * <p>Reopening is the smaller half of the job: any late {@code BlockItem} is linked back to its block, NeoForge's
	 * blockstate→id map is emptied so the closing freeze's own bake numbers every state once
	 * ({@link #closeClientEntrypointWindow}), and the state caches are filled. All of it runs in a {@code finally} so a mod
	 * throwing cannot leave the registries open.
	 *
	 * <p>Closing this window is also Fabric's registry freeze point: the Fabric injectors on
	 * {@code BuiltInRegistries.freeze()} that {@code FabricFreezeHookMixinAdapter} moved run here, the HEAD half while
	 * the registries are still open and the TAIL half once they are frozen (issue #52).
	 */
	public static void onClientEntrypoints() {
		ClassLoader cl = gameLoader;
		boolean reopened = false;

		try {
			unfreeze(cl);
			rootRegistry(cl, true);
			reopened = true;
			ForbricLog.info("[Forbric/Lifecycle] registries reopened for the Fabric client entrypoints");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not reopen the registries for the Fabric client "
					+ "entrypoints — a mod registering content from onInitializeClient will fail");
		}

		try {
			// main first, then client — Fabric's own Hooks.startClient order, now at Fabric's own point in the
			// constructor. A no-op when the pre-Minecraft window already ran them (the switch, or a server).
			KernelFabricEcosystem.runMainEntrypoints();
			KernelFabricEcosystem.runClientEntrypoints();
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] client entrypoints failed");
		} finally {
			// Fabric freezes here, after the main and client entrypoints: the Fabric injectors on
			// BuiltInRegistries.freeze() that FabricFreezeHookMixinAdapter moved run around the freeze that closes
			// this window — the HEAD half while it is still open, the TAIL half once it is frozen.
			fabricFreezePoint(cl, FabricFreezePointInjector.HEAD_HOOK);
			if (reopened) closeClientEntrypointWindow(cl);
			fabricFreezePoint(cl, FabricFreezePointInjector.TAIL_HOOK);
			// A CLIENT config registered from a Fabric client entrypoint missed the early pass entirely, and
			// nothing else opens one. Reading it then throws rather than returning a default.
			openLateConfigs(cl, Side.CLIENT, "the Fabric client entrypoints");
		}

		// Step 3a, where a client whose mains run here declares it (driveNativeRegistration says why it waits).
		// After the window has closed, not inside it: native Fabric first initialises RegistryDataLoader at world
		// load, with the root frozen, and NeoForge processes NewRegistry with it frozen too — the state the
		// dedicated server declares in. Before NeoForge's client setup, whose RegisterDataMapTypesEvent reads the
		// declared list. A no-op when the pre-Minecraft window already declared.
		registerDataPackRegistries(cl);
	}

	/**
	 * Invoked from {@code Minecraft.<init>} at the base's own {@code ClientModLoader.finish()} call
	 * ({@code NeoClientSetupHookInjector}) — the NeoForge half of the client mod-loading window, and the last
	 * kernel hook the constructor reaches: the block-colour table, the particle providers and
	 * {@code initClientHooks} have all run by then.
	 *
	 * <p>Separate from {@link #onClientEntrypoints}, which runs earlier, because the two ecosystems need opposite
	 * states: Fabric's keymapping registration requires {@code options} to still be null, NeoForge's setup requires
	 * it to exist. See {@code NeoClientSetupHookInjector} for the full account.
	 */
	public static void onNeoClientSetup() {
		ClassLoader cl = gameLoader;
		// Step 3a's last chance: client setup posts RegisterDataMapTypesEvent, which reads the declared list, so the
		// declaration must have happened by now even if the Fabric hook above never landed. Normally a no-op.
		registerDataPackRegistries(cl);
		fireClientSetupLifecycle(cl);
		// Common setup now runs in there, and registering a config is one of the things mods do from it. On the
		// server the pass right after the setup lifecycle catches those; the client had no equivalent once the
		// phases moved here, so a config registered from client-side common setup was registered and never
		// loaded — and reading it throws rather than returning a default.
		openLateConfigs(cl, Side.CLIENT, "the client setup lifecycle");
		// And only then close the payload registration phase — see step 3c for why it cannot precede setup.
		setupNeoForgeNetwork(cl, Side.CLIENT);
	}

	/** Hooks of {@link #fabricFreezePoint} already called; each runs once per process. */
	private static final java.util.Set<String> FABRIC_FREEZE_HOOKS_RUN = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Calls one of the hooks {@link FabricFreezePointInjector} added to {@code BuiltInRegistries}, once: Fabric's
	 * registry freeze point, where the Fabric mods' own injectors on {@code BuiltInRegistries.freeze()} run with
	 * fabric-api installed.
	 *
	 * <p>On Fabric that is after every main (a server) or main and client (a client) entrypoint, because
	 * fabric-registry-sync moves the freeze there; the kernel freezes in {@code Bootstrap} and opens its own windows,
	 * so the freeze's observers are moved instead (issue #52: Create Fly's TAIL injector creating its registries
	 * against the frozen bootstrap root). A handler that throws is reported, not rethrown — the same as a failing
	 * entrypoint here — and the hook it shares runs no further handlers, as on Fabric. The TAIL half runs only after
	 * the HEAD half: a server whose window failed before its mains never reaches HEAD, and Create's TAIL injector would
	 * then add a second, misleading failure to the first.
	 */
	static void fabricFreezePoint(ClassLoader cl, String hook) {
		if (!FabricFreezePointInjector.enabled()) return;
		if (FabricFreezePointInjector.TAIL_HOOK.equals(hook) && !FABRIC_FREEZE_HOOKS_RUN.contains(FabricFreezePointInjector.HEAD_HOOK)) {
			ForbricLog.debug("[Forbric/RegistrySync] Fabric's registry freeze point never reached its HEAD half — TAIL skipped");
			return;
		}
		if (!FABRIC_FREEZE_HOOKS_RUN.add(hook)) return;
		List<String> moved = net.forbric.kernel.mixin.FabricFreezeHookMixinAdapter.moved().stream()
				.filter(row -> row.endsWith("-> " + hook)).toList();
		try {
			Class.forName(FabricFreezePointInjector.TARGET, true, cl).getMethod(hook).invoke(null);
			if (!moved.isEmpty()) {
				ForbricLog.info("[Forbric/RegistrySync] Fabric's registry freeze point (%s): ran %d moved injector(s) "
						+ "after the Fabric entrypoints — %s", hook.endsWith("Head") ? "HEAD" : "TAIL", moved.size(),
						String.join(", ", moved.stream().map(row -> row.substring(0, row.indexOf(" -> "))).toList()));
			}
		} catch (NoSuchMethodException | ClassNotFoundException absent) {
			Caught.expected(absent);
			ForbricLog.debug("[Forbric/RegistrySync] no %s on BuiltInRegistries — nothing waits for Fabric's freeze point", hook);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/RegistrySync] a Fabric mod's injector on BuiltInRegistries.freeze() failed at Fabric's "
					+ "registry freeze point (" + hook + "); every later one on that hook was skipped. Moved here: "
					+ moved);
		}
	}

	/**
	 * Empties NeoForge's blockstate→id map ({@code GameData.getBlockStateIDMap()}, a {@code ClearableObjectIntIdentityMap}
	 * whose {@code clear()} is the one {@code BlockCallbacks.onClear} calls), so the bake of the freeze that follows numbers
	 * every state once, in registry order, as NeoForge's own single bake does. Called right before each freeze the kernel
	 * drives: the one that closes the registration window ({@link #closeRegistrationWindow}) and the one that closes the
	 * client entrypoint window ({@link #closeClientEntrypointWindow}). Whatever was appended before it — the previous bake,
	 * or fabric-registry-sync's {@code StateIdTracker}, which adds each block's states as the block is registered — is
	 * renumbered by that bake. Reported, never thrown: a map left full keeps the offset ids, as before.
	 */
	static void clearBlockStateIds(ClassLoader cl) {
		try {
			Class<?> gameData = Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), false, cl);
			Object map = gameData.getMethod("getBlockStateIDMap").invoke(null);
			java.lang.reflect.Method clear = map.getClass().getDeclaredMethod("clear");
			clear.setAccessible(true);
			clear.invoke(map);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not empty NeoForge's blockstate->id map before a registry freeze — "
					+ "its bake appends every state after the ones already there, so each block-state id on this side is "
					+ "offset and a peer decodes the wrong blocks");
		}
	}

	/**
	 * Checks, after a freeze the kernel drove, that NeoForge's blockstate→id map holds every block state of the registry
	 * exactly once, numbered 0..n-1: {@code nextId == size()} and {@code size()} equal to the states of every registered
	 * block. That is what NeoForge's single bake leaves natively, and what a peer's chunks are decoded against. Lane K1's
	 * defect broke it silently (fabric-registry-sync's tracker had appended the window's states before the bake appended
	 * the whole registry, so a dedicated server held 34392 states at ids up to 36417 and only a socket join with a mod's
	 * block in view showed it); so does a clear whose freeze never ran its bake (the registry already frozen, or
	 * {@code freezeData} throwing before the block registry), which leaves the map empty. One line either way: the count
	 * when it holds, a {@link Caught.Invariant} at WARN when it does not. Never thrown.
	 */
	static void verifyBlockStateIds(ClassLoader cl, String window) {
		try {
			Class<?> gameData = Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), false, cl);
			Object map = gameData.getMethod("getBlockStateIDMap").invoke(null);
			Class<?> idMapper = Class.forName("net.minecraft.core.IdMapper", false, cl);
			Field next = idMapper.getDeclaredField("nextId");
			next.setAccessible(true);
			int nextId = next.getInt(map);
			int mapped = (Integer) idMapper.getMethod("size").invoke(map);
			Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Object blocks = builtIn.getField("BLOCK").get(null);
			Class<?> block = Class.forName("net.minecraft.world.level.block.Block", false, cl);
			java.lang.reflect.Method definition = block.getMethod("getStateDefinition");
			java.lang.reflect.Method possible = null;
			int states = 0;
			for (Object b : (Iterable<?>) blocks) {
				Object def = definition.invoke(b);
				if (possible == null) possible = def.getClass().getMethod("getPossibleStates");
				states += ((java.util.Collection<?>) possible.invoke(def)).size();
			}
			String problem = blockStateIdProblem(nextId, mapped, states);
			if (problem != null) throw new Caught.Invariant(problem);
			ForbricLog.info("[Forbric/Lifecycle] block-state ids after the %s freeze: %d states, each numbered once "
					+ "(0..%d)", window, states, states - 1);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] NeoForge's blockstate->id map is not one id per block state after the %s "
					+ "freeze — a peer decodes this side's block-state ids as other blocks, or cannot decode them", window);
		}
	}

	/**
	 * What is wrong with a blockstate→id map whose next id is {@code nextId}, that maps {@code mapped} distinct states,
	 * for a registry whose blocks have {@code states} states between them; {@code null} when it is one id per state.
	 */
	static String blockStateIdProblem(int nextId, int mapped, int states) {
		if (nextId == mapped && mapped == states) return null;
		StringBuilder why = new StringBuilder("blockstate->id map: next id ").append(nextId).append(", ").append(mapped)
				.append(" state(s) mapped, ").append(states).append(" registered");
		if (nextId > mapped) {
			why.append("; ").append(nextId - mapped).append(" id(s) are stale slots of a state numbered again later, so "
					+ "every later id is offset (a numbering appended after another)");
		}
		if (mapped < states) why.append("; ").append(states - mapped).append(" registered state(s) have no id");
		if (mapped > states) why.append("; ").append(mapped - states).append(" mapped state(s) belong to no registered block");
		if (nextId < mapped) why.append("; ids were assigned out of order");
		return why.toString();
	}

	/** Test seam. */
	static void forgetFabricFreezePoint() {
		FABRIC_FREEZE_HOOKS_RUN.clear();
	}

	/**
	 * Re-closes after the client entrypoints and redoes the id bookkeeping their registrations invalidated.
	 *
	 * <p>NeoForge's blockstate→id map is emptied right before the freeze. Reopening ({@code unfreezeData}) clears nothing,
	 * and the freeze's {@code BlockCallbacks.onBake} adds every block's states again after the ones the main window's bake
	 * left, so without the clear every id on a client was its dedicated-server id plus the whole state count (gate-m26:
	 * NeoForge's probe block 64733 where a dedicated server reads 32366), past the width of the global palette sized from
	 * the map's {@code size()}. Native NeoForge bakes a client's blocks once, so each state's id is its position in registry
	 * order; the bake after the clear gives exactly that, with the late registrations at the end.
	 *
	 * <p>Then the creative tabs are sorted again ({@link #sortNeoCreativeTabs}): this is the client's last registration,
	 * after NeoForge's own sort, and a Fabric mod's tab is not in the strip otherwise.
	 */
	private static void closeClientEntrypointWindow(ClassLoader cl) {
		try {
			linkBlockItems(cl);
			rootRegistry(cl, false);
			clearBlockStateIds(cl);
			freeze(cl);
			completeBlockStateBookkeeping(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not re-close after the Fabric client entrypoints");
		}
		verifyBlockStateIds(cl, "client entrypoint window");
		// The client's last registration: the tabs the Fabric entrypoints added go into NeoForge's strip now, as NeoForge's
		// own tail sort puts in what RegisterEvent added (see sortNeoCreativeTabs).
		try {
			sortNeoCreativeTabs(cl);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] could not sort the creative tabs after the Fabric client entrypoints — "
					+ "a Fabric mod's tab may be missing from the strip");
		}
	}
}
