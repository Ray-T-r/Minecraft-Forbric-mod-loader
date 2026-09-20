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

import net.forbric.api.Side;
import net.forbric.api.Ecosystem;
import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * The kernel-owned server lifecycle hook that runs where the merged base used to call the genuine
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

	private KernelLifecycle() {
	}

	/** Installed by the boot orchestrator so the injected game-side call can reach the transforming loader. */
	public static void bind(ClassLoader loader) {
		gameLoader = loader;
	}

	/** The Forge-family mod jars to construct in the registration window (set by the boot orchestrator). */
	/** The Forge-family mod jars this boot loaded (top-level and extracted nested), for game-side helpers. */
	public static List<Path> modJars() {
		return modJars;
	}

	public static void setModJars(List<Path> jars) {
		modJars = jars == null ? List.of() : jars;
	}

	/** The ecosystem runtime jars (forge/neoforge). Each is that ecosystem's OWN mod file — FML scans it too. */
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
	 */
	public static void onServerModLoading(boolean dedicated) {
		driveNativeRegistration(Side.DEDICATED_SERVER);
	}

	/**
	 * Invoked from {@code net.minecraft.client.main.Main.main} (redirected from {@code ClientModLoader.begin}) after
	 * {@code Bootstrap.validate} (which asserts {@code Bootstrap.bootStrap} already ran). Same native registration
	 * as the server — the ecosystems' baselines + content are side-independent; the Fabric ecosystem runs its
	 * {@code client} entrypoints rather than {@code server} ones, driven by {@code KernelFabricLoader}'s env type.
	 */
	public static void onClientModLoading() {
		driveNativeRegistration(Side.CLIENT);
	}

	private static void driveNativeRegistration(Side side) {
		ClassLoader cl = gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
		ForbricLog.info("[Forbric/Lifecycle] kernel %s mod-loading window (native, no FancyModLoader) — "
				+ "registering ecosystem baselines", side.distName());
		// Step 0: seed traditional Forge's empty LoadingModList (ServerStatusPing / client status touch it later).
		PassiveSeeder.seedForgeLoadingModList(cl);
		// Step 0b: give traditional Forge its sided executors. Forge's LogicalSidedProvider hands a mod's network
		// handler the main thread to run on (CustomPayloadEvent.Context.enqueueWork); Forge fills it from
		// ClientModLoader / ServerLifecycleHooks, both of which the kernel owns and neither of which runs. Left
		// empty, the first Forge packet a mod handled on its main thread died in an NPE inside the dispatcher
		// (gate-m15). Both suppliers are lazy — the client one asks Minecraft for its instance each time, the
		// server one asks NeoForge's ServerLifecycleHooks for the current server, which the merged base keeps.
		bridgeForgeSidedProviders(cl);
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
		// Step 2a: the two REGISTRATION bridges (creative-tab contents, spawn placements) are landed by class
		// transformers when the game defines CreativeModeTab and SpawnPlacements — both of which the window above
		// has driven by now — not by the multiplexer. Verify them here, where a repair that stood down on an
		// unexpected base gets named with its cost instead of leaving a Forge mod's items and mobs silently absent.
		EventBridges.verify(GameEventBridge.Pass.REGISTRATION);
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
		KernelEventSubscribers.registerNeoForgeInternal(cl, runtimeJars, baselineBus, side);
		// Forge also declares internal subscribers in its carrier, including its geometry-loader registrations.
		KernelForgeInternalSubscribers.register(cl, runtimeJars, side);
		// Step 2c3 (client only): NeoForge won the client reload-listener path in the byte merge, so MinecraftForge's
		// RegisterClientReloadListenersEvent is never posted and a Forge mod's handler for it sits on a dead bus.
		// Bridge it off NeoForge's AddClientReloadListenersEvent, which ClientHooks.initClientHooks posts to the
		// baseline mod bus during Minecraft.<init> — i.e. after this point, which is why the listener goes on now.
		if (side.isClient()) GameEventMultiplexer.installClientReloadBridge(cl, baselineBus);
		// Step 3 USED TO BE HERE: registering mods' @EventBusSubscriber classes. It has moved INSIDE
		// registerNeoForgeContent, next to the constructors — see the comment at the new call site. Wiring them
		// here meant every registration-phase event had already been posted to nobody.
		// Step 3a: let mods declare their DATAPACK registries. These are not the registries RegisterEvent fills —
		// they are the per-world ones RegistryDataLoader builds from datapacks, and NeoForge collects them through
		// DataPackRegistryEvent.NewRegistry into DataPackRegistriesHooks. Nothing posted that event, so the list
		// stayed vanilla-only and lithostitched died the moment a world loaded: "Missing registry:
		// lithostitched:worldgen_modifier" out of RegistryAccess.lookupOrThrow, on the server tick loop. Must run
		// before any world is created; here is the first point where every mod's listeners are registered.
		registerDataPackRegistries(cl);
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
		// Everything the kernel wires onto these buses (the Neo→Forge bridges inside startGameBuses, NeoForge's own
		// @EventBusSubscriber classes in step 2c2, the client reload bridge in 2c3) is already registered by this
		// point, and adding a listener to a started bus is allowed anyway.
		startGameBuses(cl, side);
		// Step 3b: post the FML setup lifecycle at every NeoForge mod. Genuine NeoForge produces these inside
		// CommonModLoader.load(), whose only client caller is ClientModLoader.finish() — which the kernel neuters
		// because it also drives the discovery/registration the kernel owns. Nothing replaced the setup phases, so
		// they never fired for anyone: AppleSkin registers its food tooltip from FMLClientSetupEvent
		// (preInitClient -> TooltipOverlayHandler.init -> NeoForge.EVENT_BUS.register), which is why the tooltip
		// stayed missing even after mod-bus delivery was fixed.
		fireModSetupLifecycle(cl, side);
		// Step 3b2: the late-config pass, in the one place it is honest. loadEarlyConfigs (step 2c) ran before
		// construction's own events, so a mod that registers a config from FMLConstructModEvent or common setup —
		// and a Fabric mod registering one through the ForgeConfigAPIPort — has a config that is registered and
		// never loaded. Reading it then throws "Cannot get config value before config is loaded" rather than
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
	 * Rebuilds NeoForge's blockstate→id map after the registration window closes.
	 *
	 * <p>Opening the window runs the vanilla registries' clear callback, and NeoForge's
	 * {@code BlockCallbacks.onClear} empties both that map and the {@code addedBlocks} set its {@code onBake} rebuilds
	 * from — so the bake only re-adds blocks REGISTERED INSIDE the window, i.e. none of vanilla's. The map is then
	 * empty and the first {@code clientbound/minecraft:block_update} cannot encode ("Can't find id for
	 * Block{minecraft:lava}"), kicking the player right after spawn. Re-add every block's states in registry order —
	 * the same order vanilla assigns ids in, so the numbering a remote client expects is reproduced. No-op if the map
	 * is already populated.
	 */
	private static void rebuildNeoForgeBlockStateIds(ClassLoader cl) {
		contentCall(cl, "rebuildBlockStateIds", "rebuild the NeoForge blockstate→id map");
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

	/**
	 * {@code LogicalSidedProvider.setClient(() -> Minecraft.getInstance())} and
	 * {@code setServer(() -> ServerLifecycleHooks.getCurrentServer())} — traditional Forge's view of "the game on
	 * this side", resolved lazily so that neither the client instance nor a server has to exist yet. Best-effort.
	 */
	private static void bridgeForgeSidedProviders(ClassLoader cl) {
		try {
			Class<?> provider = Class.forName("net.minecraftforge.common.util.LogicalSidedProvider", false, cl);
			java.util.function.Supplier<Object> clientSupplier = () -> {
				try {
					return Class.forName("net.minecraft.client.Minecraft", false, cl).getMethod("getInstance").invoke(null);
				} catch (Throwable t) {
					return null;
				}
			};
			java.util.function.Supplier<Object> serverSupplier = () -> {
				try {
					return Class.forName(ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.NEOFORGE), false, cl)
							.getMethod("getCurrentServer").invoke(null);
				} catch (Throwable t) {
					return null;
				}
			};
			provider.getMethod("setClient", java.util.function.Supplier.class).invoke(null, clientSupplier);
			provider.getMethod("setServer", java.util.function.Supplier.class).invoke(null, serverSupplier);
			ForbricLog.info("[Forbric/Lifecycle] bridged traditional Forge's LogicalSidedProvider to the live client / "
					+ "NeoForge's current server — Forge network handlers can enqueue onto the main thread");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no traditional-Forge LogicalSidedProvider to bridge");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not bridge Forge's LogicalSidedProvider — a Forge mod's main-thread "
					+ "packet handler will NPE", unwrap(t));
		}
	}

	/** Runs a NeoForge {@code *NetworkRegistry.setup()} — it posts its Register*PayloadHandlersEvent via ModLoader. */
	private static void invokeNetworkSetup(ClassLoader cl, String registryClass) {
		try {
			Class<?> registry = Class.forName(registryClass, false, cl);
			registry.getMethod("setup").invoke(null);
			ForbricLog.info("[Forbric/Lifecycle] %s.setup() — payload handlers registered%s",
					registryClass.substring(registryClass.lastIndexOf('.') + 1), describePayloadRegistrations(registry));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] " + registryClass + ".setup() failed — NeoForge payloads incomplete "
					+ "(join may fail 'No Handler for …')", unwrap(t));
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
			}
			return "; " + total + " payload type(s) registered " + counts + ", NeoForge's own included: "
					+ (natives ? "yes" : "NO");
		} catch (NoSuchFieldException clientRegistry) {
			return "";
		} catch (Throwable t) {
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
			ForbricLog.warn("[Forbric/Lifecycle] could not mark vanilla registries synced (registry-ID sync may fail)",
					unwrap(t));
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
					+ "Forge and NeoForge COMMON/CLIENT configs are not opened by the kernel; mods may keep "
					+ "defaults or read unloaded values");
			return;
		}
		// STARTUP is deliberately absent — see the game side, which explains what naming it would cost.
		List<String> types = side.isClient() ? List.of("COMMON", "CLIENT") : List.of("COMMON");
		try {
			configClass(cl).getMethod("loadEarly", List.class).invoke(null, types);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not load NeoForge configs", unwrap(t));
		}
		try {
			Class<?> forge = forgeConfigClass(cl);
			if (forge != null) forge.getMethod("loadEarly", List.class).invoke(null, forgeEarlyConfigTypes(side));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not load MinecraftForge configs", unwrap(t));
		}
	}

	/** The game-side half of config loading. */
	private static Class<?> configClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.forbric.kernel.runtime.KernelConfigLoad", true, cl);
	}

	/**
	 * Forge's own capability registration stage. Advisory on this base (isRegistered is read only by Forge's own
	 * manager), so the count is logged rather than acted on. It was structurally zero while the seeded
	 * MinecraftForge {@code ModFile}s carried an EMPTY scan data; see {@code ModFileScanner.scanForge}.
	 */
	private static void injectForgeCapabilities(ClassLoader cl) {
		if (KernelModLoader.publishedForgeMods().isEmpty()
				|| !net.forbric.kernel.transform.ForgeCapabilityCompositionTransformer.enabled()) return;
		try {
			Object count = Class.forName("net.forbric.kernel.runtime.KernelForgeCapabilities", true, cl)
					.getMethod("injectCapabilities").invoke(null);
			ForbricLog.info("[Forbric/Capabilities] ran MinecraftForge's injectCapabilities — %s @AutoRegisterCapability "
					+ "annotation(s) in the mod scan data (lookups work without it; -1 = the index could not be read)",
					count);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Capabilities] MinecraftForge's injectCapabilities threw — capability lookups still "
					+ "work, isRegistered() answers false", unwrap(t));
		}
	}

	/** The carrier itself owns configs even if no third-party Forge mod was installed. */
	private static Class<?> forgeConfigClass(ClassLoader cl) throws ClassNotFoundException {
		try {
			Class.forName(ForeignType.CONFIG_TRACKER.binary(Ecosystem.FORGE), false, cl);
		} catch (ClassNotFoundException absent) {
			return null;
		}
		return Class.forName("net.forbric.kernel.runtime.KernelForgeConfigLoad", true, cl);
	}

	/** Forge has no STARTUP type; its native config phase opens CLIENT before COMMON. */
	static List<String> forgeEarlyConfigTypes(Side side) {
		return side.isClient() ? List.of("CLIENT", "COMMON") : List.of("COMMON");
	}

	/**
	 * Opens NeoForge configs that were registered after {@link #loadEarlyConfigs} had already run.
	 *
	 * <p>The early pass happens once, before mod content registration. A Fabric mod registering a config from a
	 * CLIENT entrypoint is therefore too late for it, and nothing else opens a non-STARTUP config — the carrier's
	 * {@code registerConfig} eagerly opens STARTUP only. The mod then reads a config that was registered and never
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
			ForbricLog.warn("[Forbric/Lifecycle] could not open late-registered NeoForge configs", unwrap(t));
		}
		try {
			Class<?> forge = forgeConfigClass(cl);
			Object result = forge == null ? null : forge.getMethod("openLate", List.class)
					.invoke(null, forgeLateConfigTypes(side));
			if (result instanceof List<?> opened && !opened.isEmpty()) {
				ForbricLog.info("[Forbric/Lifecycle] opened %d late-registered MinecraftForge config(s) after %s %s",
						opened.size(), when, opened);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not open late-registered MinecraftForge configs", unwrap(t));
		}
	}

	static List<String> forgeLateConfigTypes(Side side) {
		return forgeEarlyConfigTypes(side);
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
	 * Starts NeoForge's global game bus + Forge's DEFAULT BusGroup so game-event listeners dispatch.
	 *
	 * <p>Absent and failed are split here for the same reason as in {@link #invokeGameDataOn}: a single-family
	 * instance legitimately has only one of these two buses, and that is a debug line. A bus that is PRESENT and
	 * fails to start is total — every game-event listener of that family, of every mod, is on a bus nothing
	 * dispatches, and the game then runs with no visible error at all.
	 */
	private static void startGameBuses(ClassLoader cl, Side side) {
		// Bridge merge-lost game events (Neo won the tick hook → forward to Forge) BEFORE starting the buses.
		// The side decides whether the CLIENT-only game-bus bridges go on: they name NeoForge's client event
		// package, which a dedicated server must never be made to resolve.
		GameEventMultiplexer.install(cl, side.isClient());
		GameEventMultiplexer.installDataMapWatch(cl);
		startBus(cl, "net.neoforged.neoforge.common.NeoForge", "EVENT_BUS",
				"net.neoforged.bus.api.IEventBus", "start", "NeoForge.EVENT_BUS");
		startBus(cl, "net.minecraftforge.eventbus.api.bus.BusGroup", "DEFAULT",
				"net.minecraftforge.eventbus.api.bus.BusGroup", "startup", "Forge BusGroup.DEFAULT");
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
			ForbricLog.debug("[Forbric/Lifecycle] no %s on this runtime — nothing to start", label);
			return;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not read " + label + " — every game-event listener of that "
					+ "family is on a bus nothing will dispatch", unwrap(t));
			return;
		}
		try {
			Class.forName(api, false, cl).getMethod(start).invoke(bus);
			ForbricLog.info("[Forbric/Lifecycle] started %s (game events now dispatch)", label);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] " + label + " is present but did NOT start — every game-event "
					+ "listener of that family, in every mod, is now on a bus nothing dispatches", unwrap(t));
		}
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
			setForgeLoadingState(cl, false);
			Class<?> distClass = Class.forName(ForeignType.DIST.binary(Ecosystem.NEOFORGE), false, cl);
			Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), side.distName());

			// The NeoForge baseline mod on its own bus. Captured so the client step can add ClientNeoForgeMod to
			// the same bus + route the game's mod-bus events to its container.
			Object bus = KernelBusSupport.makeModBus(cl);
			Object container = KernelModContainerFactory.create(cl, "neoforge", bus);
			baselineBus = bus;
			baselineContainer = container;
			Class<?> neoForgeMod = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", false, cl);
			Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> modContainer = Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
			neoForgeMod.getConstructor(iEventBus, distClass, modContainer)
					.newInstance(bus, dist, container);
			ForbricLog.info("[Forbric/Lifecycle] constructed NeoForge baseline mod on a native bus (dist=%s)",
					side.distName());
			Object baselineBus = bus;

			// Real Forge-family @Mods, each on its own bus.
			List<KernelModLoader.ConstructedMod> mods =
					KernelModLoader.constructMods(cl, modJars, side);
			// constructMods has published the surviving Forge containers and their real bus groups. Native
			// gatherAndInitializeMods normally opens this gate; it is replaced by this kernel-owned stage.
			setForgeLoadingState(cl, true);

			// Load the config specs those constructors just registered, BEFORE any RegisterEvent fires. Genuine
			// NeoForge loads STARTUP/COMMON right after construction and only then posts the registry events, and
			// mods rely on that: Mob Champions' MobChampionsEffects.<clinit> runs from its RegisterEvent listener
			// and reads a config value, so with the load still pending it threw "Cannot get config value before
			// config is loaded" — which, before the isolation below, aborted the whole window and left even the
			// NeoForge baseline unregistered (later surfacing as an unbound neoforge:fluid_type/water). Re-run after
			// the client baseline in driveNativeRegistration too, for specs registered later; loading twice is
			// harmless (each type is attempted independently and a redundant load is swallowed).
			// Wire every mod's @EventBusSubscriber classes NOW, while the registration window is still ahead of
			// them. Genuine FML does this inside ModContainer.constructMod(), i.e. before registry init, so a
			// subscriber-declared handler is attached by the time any registration event is posted. The kernel used
			// to do it much later, after registerNeoForgeContent had already returned, and the cost was silent:
			// earthmobsmod and bagus_lib declare their EntityAttributeCreationEvent handlers on a class-level
			// @EventBusSubscriber, so CommonHooks.modifyAttributes below posted to an empty bus and every one of
			// their entities came out attribute-less — 2250 "Entity <id> has no attributes" errors per freeze, and
			// mobs that cannot spawn. The same was true of any RegisterEvent handler declared that way.
			//
			// AFTER loadEarlyConfigs, not before: this class-loads every subscriber, and a <clinit> that reads a
			// config value must not run ahead of the specs. Still strictly later than genuine NeoForge, which loads
			// these classes during construction — so nothing that survives real NeoForge can fail for being early
			// here. Both game buses stay unstarted until startGameBuses, so early registration is buffered, not lost.
			//
			// Isolated: this now sits inside registerNeoForgeContent's try, and an escape would abort the whole
			// registration window and be reported as "could not register ecosystem content", blaming the wrong
			// thing entirely.
			try {
				KernelEventSubscribers.registerAll(cl, modJars, side);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/Lifecycle] could not wire guest @EventBusSubscriber classes — mods that "
						+ "declare their registry or attribute handlers there will not be reached", unwrap(t));
			}

			// FMLConstructModEvent, the phase genuine FML posts to each container the moment it is built. The
			// kernel constructed the mods and went straight on, so anything a mod does there — and it is the
			// earliest mod-bus phase there is — never happened. Posted after the subscribers are wired, so a
			// handler declared on an @EventBusSubscriber receives it too.
			fireSetupPhase(cl, KernelModLoader.publishedNeoMods(), ForeignType.FML_CONSTRUCT_MOD_EVENT, "construct");
			// The other family's half of the same phase. It had no half at all: the kernel named NeoForge's event
			// class inline here, so every MinecraftForge mod went from construction straight to RegisterEvent and
			// whatever it does in the earliest mod-bus phase never happened. Pairing the two names in ForeignType
			// is what made the absence visible.
			fireForgeSetupPhase(cl, ForeignType.FML_CONSTRUCT_MOD_EVENT, "construct");
			// Forge's INJECT_CAPABILITIES state comes right after CREATE_REGISTRIES, i.e. here, before the registry
			// window: CapabilityManager.injectCapabilities scans mod scan data for @AutoRegisterCapability.
			injectForgeCapabilities(cl);

			// Each ecosystem's mods take their own RegisterEvent flavour: NeoForge's 2-arg event on an IEventBus, and
			// traditional Forge's 3-arg (key, ForgeRegistry, Registry) on a BusGroup. Split them here; both streams
			// run inside the one unfreeze/freeze window below.
			List<Object> buses = new ArrayList<>();
			buses.add(baselineBus);
			// The published map, not a per-entry list: it is already one handle per mod ID, it is the same source
			// fireForgeSetupPhase reads, and two @Mod classes sharing an ID now share ONE handle — a per-entry
			// list would hold it twice and fire the whole RegisterEvent stream twice on that BusGroup, so the
			// mod's DeferredRegisters would register their content twice.
			List<KernelForgeModContext.Handle> forgeHandles =
					new ArrayList<>(KernelModLoader.publishedForgeMods().values());
			// Dedupe by IDENTITY: a NeoForge mod has ONE bus shared by all its @Mod classes (balm ships
			// NeoForgeBalm + NeoForgeBalmClient, FallingTree the same), so a per-entry list would fire
			// RegisterEvent twice on that bus and register the mod's content twice.
			java.util.Set<Object> seenBuses =
					java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			for (KernelModLoader.ConstructedMod m : mods) {
				if (m.bus() != null && seenBuses.add(m.bus())) buses.add(m.bus());
			}

			// Capture the post-Bootstrap vanilla registry state for NEOFORGE only, before the window opens. NeoForge's
			// unfreeze clear-callback empties its blockstate→id map, and BlockCallbacks.onBake only re-adds blocks that
			// onAdd saw during the window (none of vanilla's) — so without a snapshot to restore from, the map stays
			// empty and the first clientbound block_update cannot encode ("Can't find id for Block{minecraft:lava}").
			// NOT MinecraftForge's: its vanillaSnapshot LOCKS the vanilla wrappers, and every later register in this
			// window then throws "Can not register to a locked registry" (gate-m1 RED).
			invokeGameDataOn(cl, ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), "vanillaSnapshot");
			unfreeze(cl);
			// From here the registries are OPEN, and everything that closes them again lives in the finally below.
			// It used to live inline at the end of this try, so anything that threw in between — a Class.forName for
			// a carrier type that was renamed, NeoForgeRegistries failing to initialise, an Error escaping the
			// baseline or the Fabric entrypoints — left every registry writable for the rest of the run and skipped
			// linkBlockItems, the blockstate-id rebuild, the creative-tab sort and the registriesLoaded latch. The
			// symptoms are the ones this file already documents one by one: Block.asItem() returns AIR so a mod's
			// creative tab is empty and its blocks cannot be picked, the first block_update fails to encode with
			// "Can't find id for Block{minecraft:lava}" and kicks the player at spawn, and mods gating on
			// areRegistriesLoaded() refuse to register render layers. The only report was one WARN saying the
			// registration window "could not register ecosystem content", which names none of that.
			closeWindow = true;
			// MOD buses only — buses.get(0) is the baseline, whose registries PassiveSeeder already registered at
			// seed time; posting there re-collects them and fill() dies on "Attempted duplicate registration".
			postNeoNewRegistryEvent(cl, buses.subList(1, buses.size()));
			// Isolated for the same reason KernelEventSubscribers.registerAll above is, and this one is wider.
			// fireRegisterEvents resolves a GAME-side class reflectively, so a LinkageError inside it escapes to
			// the outer catch and skips EVERYTHING below: the traditional-Forge baseline, the Fabric mods' main
			// entrypoints, the attribute events, the spawn-placement event, BlockEntityTypeAddBlocksEvent and the
			// modded creative-tab categories. The one WARN that reported it said "could not register ecosystem
			// content", which names none of that -- it blames the window for what one call inside it did.
			int n = 0;
			try {
				n = fireRegisterEvents(cl, buses);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/Lifecycle] could not fire RegisterEvent — mods that register content "
						+ "through DeferredRegister or RegisterEvent will have none of it. The rest of the "
						+ "registration window below still runs", unwrap(t));
			}
			// Traditional-Forge baseline: construct ForgeMod + fire the 3-arg Forge RegisterEvent so ForgeMod's own
			// DeferredRegisters (e.g. the empty forge:fluid_type read by EntityFluidInteraction) register. The real
			// Forge mods' buses ride along: their DeferredRegisters flush off the same event stream, and it can only
			// be fired once NewRegistryEvent (inside) has created Forge's custom registries.
			KernelForgeBaseline.register(cl, forgeHandles);
			// Fabric mods' onInitialize() calls Registry.register(...) directly, so it belongs in this same unfrozen
			// span. It runs BEFORE the bake below so the bake sees Fabric-registered content. The root registry is
			// opened right here because NewRegistryEvent.fill() above re-froze it: a Fabric mod declaring its own
			// registry goes through FabricRegistryBuilder, which is a plain Registry.register into that root.
			rootRegistry(cl, true);
			try {
				KernelFabricEcosystem.runMainEntrypoints();
			} finally {
				rootRegistry(cl, false);
				// No late-config pass here. It used to sit in this finally, and the comment that justified it said
				// the quiet part: these entrypoints run BEFORE loadEarlyConfigs, "so a config registered here would
				// in fact be caught by it". It was caught by it — TWICE. This pass opened each one, and
				// loadEarlyConfigs then ran ConfigTracker.loadConfigs over the WHOLE type, which re-opens a config
				// that already has one: "Opening a config that was already loaded" per config, ModConfigEvent.Loading
				// delivered a second time (a Loading handler that appends to a list or registers a listener does it
				// twice), the file re-read and a second watcher installed. The late pass now runs AFTER the setup
				// lifecycle instead, where it is the "only what is still unopened" pass it claims to be — see
				// driveNativeRegistration.
			}
			// Bake the ForgeRegistries. Note a DeferredRegister's RegistryObjects bind during their OWN registry's
			// RegisterEvent above (DeferredRegister$EventDispatcher calls updateReference right after each register),
			// not here — so a mod reading another mod's RegistryObject during RegisterEvent depends on the dispatch
			// order, not on this bake.
			// NOT MinecraftForge's GameData.postRegisterEvents, which the kernel called here for years and which
			// NEVER ONCE RAN: its second instruction block is `new LinkedHashSet<>(GameData.vanillaRegistryOrder)`
			// and that field is written only by GameData.vanillaSnapshot(), which the kernel deliberately does not
			// call on this side (it LOCKS the vanilla wrappers — see the NeoForge-only snapshot above). So it threw
			// NPE at instruction 36 on every boot and the warning it produced described the symptom. What it would
			// have reached is the same dispatch loop the kernel already drives itself, plus the attribute events —
			// so the attribute events are what is called, directly, the way NeoForge's tail already is.
			invokeStaticOn(cl, "net.forbric.kernel.runtime.KernelForgeAttributes", "fireForgeAttributeEvents");
			// NeoForge's postRegisterEvents is NOT the bake — it is the dispatch loop the kernel REPLACES: it walks
			// getRegistrationOrder() and re-fires RegisterEvent through ModLoader.postEventWrapContainerInModOrder.
			// While ModList was empty that was a silent no-op, so calling it looked harmless. Once the kernel
			// publishes its mods (KernelModLoader.publishNeoModList) it double-fires every DeferredRegister —
			// "Adding duplicate key 'neoforge:condition_codecs / balm:config'" — and its own error path then calls
			// RegistryManager.revertToVanilla(), ROLLING BACK the NeoForge registries: 21 baseline entries
			// (attribute_type, ticket_type, slot_display, entity_sub_predicate_type, …) silently disappeared.
			// Only its tail is wanted, so call that directly.
			invokeStaticOn(cl, "net.neoforged.neoforge.common.CommonHooks", "modifyAttributes");
			// The rest of postRegisterEvents' tail, in its order. Cheap calls, and each one is a whole feature that
			// simply did not exist: without fireSpawnPlacementEvent a mod's mob has no spawn rules and never
			// generates, without BlockEntityTypeAddBlocksEvent a mod cannot attach its blocks to a vanilla block
			// entity, and without registerModdedCategories its gamerules have no category to sit in.
			// (CreativeModeTabRegistry.sortTabs is the kernel's sortNeoCreativeTabs, below, after the freeze.)
			invokeStaticOn(cl, "net.minecraft.world.entity.SpawnPlacements", "fireSpawnPlacementEvent");
			postModBusEvent(cl, "net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent");
			invokeStaticOn(cl, "net.minecraft.world.level.gamerules.GameRuleCategory", "registerModdedCategories");
			ForbricLog.info("[Forbric/Lifecycle] fired RegisterEvent x%d on %d bus(es) [NeoForge baseline + %d mod(s)] "
					+ "+ %d traditional-Forge mod bus(es) + baked Forge registries", n, buses.size(),
					buses.size() - 1, forgeHandles.size());
			logRegisteredContent(cl);
		} catch (Throwable t) {
			setForgeLoadingState(cl, false);
			ForbricLog.warn("[Forbric/Lifecycle] could not register ecosystem content", unwrap(t));
		} finally {
			// Only when the window was actually opened: before unfreeze there is nothing to put back, and freezing
			// a registry the kernel never opened would close one the caller still owns.
			if (closeWindow) closeRegistrationWindow(cl);
		}
	}

	/** Keep the carrier's actual flag writable by its own failure paths; never replace its getter with true. */
	static void setForgeLoadingState(ClassLoader cl, boolean ready) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return;
		try {
			Class<?> loader = Class.forName(ForeignType.FML_MOD_LOADER.binary(Ecosystem.FORGE), false, cl);
			Field state = loader.getDeclaredField("loadingStateValid");
			state.setAccessible(true);
			state.setBoolean(null, ready);
			if (ready) ForbricLog.info("[Forbric/Lifecycle] MinecraftForge event delivery enabled after container construction");
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no MinecraftForge loading state to publish");
		} catch (ReflectiveOperationException failed) {
			ForbricLog.warn("[Forbric/Lifecycle] could not publish MinecraftForge loading state", failed);
		}
	}

	/**
	 * Closes the registration window and redoes the bookkeeping the open window invalidated.
	 *
	 * <p>Each of these four is a failure the kernel has already paid for once, so they are named rather than
	 * folded: {@code linkBlockItems} fills {@code Item.BY_BLOCK} (without it {@code Block.asItem()} is AIR and a
	 * mod's creative tab collapses to empty), {@code freeze} also latches {@code registriesLoaded},
	 * {@code rebuildNeoForgeBlockStateIds} re-adds the blockstate ids the open window's clear callback dropped, and
	 * {@code sortNeoCreativeTabs} puts window-registered tabs into the strip the creative screen actually reads.
	 *
	 * <p>Best-effort as a whole AND per step, because this runs in a finally: it must not replace the exception
	 * that brought it here.
	 */
	private static void closeRegistrationWindow(ClassLoader cl) {
		try {
			linkBlockItems(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not link block->item mappings while closing the registration "
					+ "window", unwrap(t));
		}
		try {
			freeze(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not re-freeze the registries — they stay writable for the "
					+ "rest of this run", unwrap(t));
		}
		try {
			rebuildNeoForgeBlockStateIds(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not rebuild the blockstate->id map — the first block update "
					+ "will fail to encode", unwrap(t));
		}
		try {
			sortNeoCreativeTabs(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not re-sort the creative tabs — a mod's tab may be missing "
					+ "from the strip", unwrap(t));
		}
		// After the sort, which is what it reports on.
		if (Boolean.getBoolean("forbric.tabProbe")) startCreativeTabProbe(cl);
	}

	/**
	 * Re-sorts NeoForge's creative-tab ORDER list so tabs registered in the kernel's window become visible.
	 *
	 * <p>The merged {@code CreativeModeInventoryScreen} paginates its tab strip EXCLUSIVELY from
	 * {@code net.neoforged.neoforge.common.CreativeModeTabRegistry.getSortedCreativeModeTabs()} — not from
	 * {@code CreativeModeTabs.tabs()}. That {@code SORTED_TABS} list starts empty and is only rewritten by
	 * {@code sortTabs()}, which walks the whole {@code CREATIVE_MODE_TAB} registry. Vanilla's tabs enter the
	 * registry during {@code Bootstrap} — BEFORE the kernel's registration window — so the sort that ran during
	 * NeoForge baseline bring-up froze a vanilla-only snapshot, and a mod tab registered in the window never
	 * appeared in the strip. It stayed fully SEARCHABLE the whole time, because the search tree is built from
	 * {@code CreativeModeTabs.allTabs()} (the live registry) — that split, "searchable but no tab", is this bug's
	 * fingerprint.
	 *
	 * <p>Calling {@code sortTabs()} again after the window is safe and idempotent: with no server up,
	 * {@code runInServerThreadIfPossible} runs inline; the recalculation is a pure topological sort over the tab
	 * registry plus the ordering-JSON edges; and any later genuine re-sort (the datapack reload listener) walks the
	 * same registry and keeps the tab.
	 */
	private static void sortNeoCreativeTabs(ClassLoader cl) {
		contentCall(cl, "sortCreativeTabs", "re-sort the NeoForge creative tabs");
	}

	/** {@code -Dforbric.tabProbe} — dumps every non-vanilla creative tab's live state every 3s. */
	private static void startCreativeTabProbe(ClassLoader cl) {
		contentCall(cl, "startCreativeTabProbe", "start the creative-tab probe");
	}

	/**
	 * Fills {@code Item.BY_BLOCK} for every registered {@code BlockItem} — the block→item link.
	 *
	 * <p>{@code Block.asItem()} resolves through {@code Item.byBlock(this)}, which is a plain
	 * {@code BY_BLOCK.get(block)}. The merged {@code BlockItem} constructor only stores its block; it never adds
	 * itself to that map. In Forge the map is filled by the ITEMS registry's ADD-CALLBACK
	 * ({@code GameData.ItemCallbacks} -> {@code BlockItem.registerBlocks}), and the kernel registers content without
	 * running those callbacks — so for every modded block {@code asItem()} fell through to AIR.
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
			ForbricLog.warn("[Forbric/Lifecycle] could not " + what, unwrap(t));
		}
	}

	/**
	 * Client-only: construct {@code ClientNeoForgeMod} on the NeoForge baseline bus and route the game's mod-bus
	 * events to it.
	 *
	 * <p>NeoForge's built-in CLIENT registrations — reload listeners ({@code AddClientReloadListenersEvent} adds
	 * {@code AnimationLoader}, {@code ObjLoader}, branding), entity renderers, sprite sources, client extensions —
	 * live in {@code ClientNeoForgeMod}'s {@code @SubscribeEvent} handlers. The merged base's
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
			ForbricLog.warn("[Forbric/Lifecycle] could not register NeoForge client content — the client's mod-bus "
					+ "events (reload listeners, renderers) will not reach NeoForge", unwrap(t));
		}
	}

	/** {@link #publishModBusDelivery} for both sides; a failure is logged, since the game still runs without it. */
	private static void publishNeoBaselineInModList(ClassLoader cl) {
		try {
			publishModBusDelivery(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not put the NeoForge baseline into ModList — NeoForge's own "
					+ "mod-bus listeners (its payload types among them) will not receive the events NeoForge posts",
					unwrap(t));
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
			ForbricLog.debug("[Forbric/Lifecycle] could not rebuild ModList.indexedMods: %s",
					String.valueOf(unwrap(t)));
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
			ForbricLog.debug("[Forbric/Lifecycle] could not seed ModList.fileById (title version-check may NPE): %s",
					String.valueOf(unwrap(t)));
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
			ForbricLog.debug("[Forbric/Lifecycle] could not publish the 'minecraft' container: %s",
					String.valueOf(unwrap(t)));
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
	 */
	private static void registerDataPackRegistries(ClassLoader cl) {
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
					ForbricLog.warn("[Forbric/Lifecycle] " + e.getKey()
							+ " failed declaring its datapack registries", unwrap(perMod));
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
			mirrorFabricDynamicRegistriesIntoNeoForge(cl, eventCls, hooksCls);
			declareMinecraftForgeModifierRegistries(cl, eventCls, hooksCls);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no NeoForge DataPackRegistryEvent — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not declare mods' datapack registries — a mod with its own "
					+ "worldgen registry will fail with \"Missing registry\" the moment a world loads", unwrap(t));
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
			ForbricLog.debug("[Forbric/Lifecycle] fabric-api dynamic registries absent — nothing to mirror back");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not mirror Fabric's datapack registries into NeoForge's "
					+ "list — a Fabric mod's worldgen registry may be missing at world load", unwrap(t));
		}
	}

	/**
	 * Declares {@code forge:biome_modifier} and {@code forge:structure_modifier} on NeoForge's datapack-registry
	 * list through a second {@code NewRegistry} event — the shape of {@link #mirrorFabricDynamicRegistriesIntoNeoForge}
	 * — so the merged {@code RegistryDataLoader} (which asks only NeoForge's hooks) loads a MinecraftForge mod's
	 * {@code data/<ns>/forge/biome_modifier} files at all. The codecs are Forge's own, wrapped leniently in the
	 * game-side helper. Guarded by the carrier's presence, not by "a Forge mod is installed": the declaration is
	 * cheap and a later-installed mod's files must load. {@code -Dforbric.forgeWorldgen=off} skips it and names
	 * the mods that ship such files instead.
	 */
	private static void declareMinecraftForgeModifierRegistries(ClassLoader cl, Class<?> eventCls, Class<?> hooksCls) {
		try {
			Class.forName(ForeignType.MODIFIER_REGISTRY_KEYS.binary(Ecosystem.FORGE), false, cl);
		} catch (ClassNotFoundException absent) {
			return;
		}
		boolean enabled = !"off".equalsIgnoreCase(System.getProperty("forbric.forgeWorldgen", "on"));
		try {
			ForgeWorldgenShippers.report(modJars, enabled);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Worldgen] could not scan mod jars for forge modifier files: %s", String.valueOf(t));
		}
		if (!enabled) return;
		try {
			int before = ((java.util.List<?>) hooksCls.getMethod("getDataPackRegistries").invoke(null)).size();
			Object event = eventCls.getConstructor().newInstance();
			Class.forName("net.forbric.kernel.runtime.KernelForgeWorldgen", true, cl)
					.getMethod("declareForgeModifierRegistries", Object.class).invoke(null, event);
			Method process = eventCls.getDeclaredMethod("process");
			process.setAccessible(true);
			process.invoke(event);
			java.util.List<?> now = (java.util.List<?>) hooksCls.getMethod("getDataPackRegistries").invoke(null);
			java.util.List<String> added = new java.util.ArrayList<>();
			for (int i = before; i < now.size(); i++) added.add(String.valueOf(now.get(i)));
			ForbricLog.info("[Forbric/Lifecycle] posted datapack-registry declaration for MinecraftForge's modifier "
					+ "registries — %d declared (%s), %d total", now.size() - before, added, now.size());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Worldgen] could not declare MinecraftForge's biome/structure modifier registries — "
					+ "a Forge mod's forge/biome_modifier files will not load", unwrap(t));
		}
	}

	/**
	 * Mirrors the NeoForge-declared datapack registries into Fabric API's {@code DynamicRegistries}, so both
	 * ecosystems' worldgen registries exist whichever list the world loader ends up reading.
	 *
	 * <p><b>Why this is needed at all.</b> The merged base is NeoForge-patched, so {@code WorldLoader} passes
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
			ForbricLog.debug("[Forbric/Lifecycle] fabric-api dynamic registries absent — NeoForge's list stands");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not mirror datapack registries into Fabric's list — a "
					+ "NeoForge mod's worldgen registry may be missing at world load", unwrap(t));
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
		// NOT an early return on an empty NeoForge set. Every fireForgeSetupPhase below belongs to the OTHER family,
		// and fireRegistrationEvents belongs to neither: on a pack whose Forge-family mods are all traditional
		// MinecraftForge (a classic Forge modpack), publishedNeoMods() is empty while publishedForgeMods() is not,
		// and this return skipped all four MinecraftForge phases plus NeoForge's own RegistrationEvents.init.
		// That is the BiomesOPlenty/TerraBlender "the world came out looking vanilla" failure this method's own
		// javadoc describes, with nothing anywhere saying so. fireSetupPhase and fireForgeSetupPhase each no-op on
		// an empty set of their own family, so the guard buys nothing. The CLIENT twin (fireClientSetupLifecycle)
		// dropped the same guard for the same reason; the server path never followed.

		// On the CLIENT every phase, common setup included, is deferred to fireClientSetupLifecycle. This method
		// runs BEFORE `new Minecraft(...)`, so Minecraft.getInstance() is still null here — and common setup is
		// exactly where a mod does its dist-guarded client initialisation: caching the singleton into a static
		// field, or handing work to Minecraft.execute. Genuine NeoForge posts common setup from
		// ClientModLoader.finish(), inside Minecraft's own constructor, where the singleton exists. Posting it
		// here handed those mods a null and the failure surfaced later, in rendering, with nothing pointing back.
		if (side.isClient()) return;

		fireSetupPhase(cl, mods, ForeignType.FML_COMMON_SETUP_EVENT, "common setup");
		fireForgeSetupPhase(cl, ForeignType.FML_COMMON_SETUP_EVENT, "common setup");
		// The sided phase. The kernel used to jump straight from common setup to load complete, so on a dedicated
		// server this event was never posted to anyone at all.
		fireSetupPhase(cl, mods, ForeignType.FML_DEDICATED_SERVER_SETUP_EVENT, "dedicated server setup");
		fireForgeSetupPhase(cl, ForeignType.FML_DEDICATED_SERVER_SETUP_EVENT, "dedicated server setup");
		fireRegistrationEvents(cl);
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_ENQUEUE_EVENT, "IMC enqueue");
		fireForgeSetupPhase(cl, ForeignType.INTER_MOD_ENQUEUE_EVENT, "IMC enqueue");
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_PROCESS_EVENT, "IMC process");
		fireForgeSetupPhase(cl, ForeignType.INTER_MOD_PROCESS_EVENT, "IMC process");
		fireSetupPhase(cl, mods, ForeignType.FML_LOAD_COMPLETE_EVENT, "load complete");
		fireForgeSetupPhase(cl, ForeignType.FML_LOAD_COMPLETE_EVENT, "load complete");

		// Loading is over on this side, so whatever went wrong during it is now the whole story rather than a
		// partial one. A clean run writes no file and says one line.
		net.forbric.kernel.access.AccessCensus.report();
		KernelLoadReport.write();
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
	 * between versions, and a hand-rolled copy would rot silently. It is package-private, hence the declared
	 * lookup. Once only — the client and server paths each reach this point, and both must not run it.
	 */
	private static void fireRegistrationEvents(ClassLoader cl) {
		if (!REGISTRATION_EVENTS_FIRED.compareAndSet(false, true)) return;
		try {
			Class<?> events = Class.forName("net.neoforged.neoforge.internal.RegistrationEvents", false, cl);
			Method init = events.getDeclaredMethod("init");
			init.setAccessible(true);
			init.invoke(null);
			ForbricLog.info("[Forbric/Lifecycle] ran NeoForge's registration events — capabilities and data maps "
					+ "are registered, and its cauldron/forced-chunk/data-component/POI built-ins initialised");
		} catch (ClassNotFoundException | NoSuchMethodException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no RegistrationEvents.init to run: %s", String.valueOf(absent));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] NeoForge's registration events failed — capabilities and data "
					+ "maps will be missing", unwrap(t));
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
	 * before the mods'.
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
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping", eventClassName);
			return;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not build " + eventClassName, unwrap(t));
			return;
		}

		java.util.Map<String, Object> byId = new java.util.LinkedHashMap<>();
		if (baselineContainer != null) byId.put("neoforge", baselineContainer);
		KernelModLoader.publishedNeoMods().forEach((id, identity) -> byId.put(id, identity.container()));
		if (byId.isEmpty()) return;

		int delivered = 0;
		for (java.util.Map.Entry<String, Object> e : byId.entrySet()) {
			try {
				acceptEvent.invoke(e.getValue(), event);
				delivered++;
			} catch (Throwable perMod) {
				ForbricLog.warn("[Forbric/Lifecycle] " + e.getKey() + " threw during " + eventClassName
						+ " — its own registration from that event is lost, every other mod still gets it",
						unwrap(perMod));
			}
		}
		ForbricLog.debug("[Forbric/Lifecycle] posted %s to %d container(s)", eventClassName, delivered);
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
		// NOT an early return on an empty NeoForge set any more: the traditional-Forge phases below are a
		// different family's, and an instance carrying only MinecraftForge mods would have skipped them for a
		// reason that has nothing to do with it.
		// Common setup FIRST, and on the client it is posted from here rather than from the pre-Minecraft window
		// — the same move the client setup phases themselves already made, one phase earlier. Both families, and
		// before the sided phase, which is the order genuine NeoForge's CommonModLoader.load uses.
		fireSetupPhase(cl, mods, ForeignType.FML_COMMON_SETUP_EVENT, "common setup");
		fireForgeSetupPhase(cl, ForeignType.FML_COMMON_SETUP_EVENT, "common setup");
		fireSetupPhase(cl, mods, ForeignType.FML_CLIENT_SETUP_EVENT, "client setup");
		fireForgeSetupPhase(cl, ForeignType.FML_CLIENT_SETUP_EVENT, "client setup");
		// Same tail as the server's, and the same order CommonModLoader.load uses: sided setup, then the
		// registration events, then IMC, then load complete.
		fireRegistrationEvents(cl);
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_ENQUEUE_EVENT, "IMC enqueue");
		fireForgeSetupPhase(cl, ForeignType.INTER_MOD_ENQUEUE_EVENT, "IMC enqueue");
		fireSetupPhase(cl, mods, ForeignType.INTER_MOD_PROCESS_EVENT, "IMC process");
		fireForgeSetupPhase(cl, ForeignType.INTER_MOD_PROCESS_EVENT, "IMC process");
		fireSetupPhase(cl, mods, ForeignType.FML_LOAD_COMPLETE_EVENT, "load complete");
		fireForgeSetupPhase(cl, ForeignType.FML_LOAD_COMPLETE_EVENT, "load complete");

		// The client's own end of loading. Same reason as the server twin: at this point what went wrong is the
		// whole story, and this is the last moment before the player is looking at a title screen.
		net.forbric.kernel.access.AccessCensus.report();
		KernelLoadReport.write();
	}

	/**
	 * The traditional-MinecraftForge half of a setup phase.
	 *
	 * <p>Separate from {@link #fireSetupPhase} because the two families' buses are not the same shape, not because
	 * the phases differ: NeoForge posts on a per-mod {@code IEventBus}, EventBus 7 resolves the bus from the event
	 * plus that mod's {@code BusGroup}. Folding them would be the averaging-away this repo's {@code ForeignType}
	 * javadoc warns about; the divergence stays as data at the call site, which is why every call above comes in
	 * pairs.
	 *
	 * <p>Best-effort: a family that is not present resolves no event class and says so once at debug.
	 */
	private static void fireForgeSetupPhase(ClassLoader cl, ForeignType event, String label) {
		java.util.List<KernelForgeModContext.Handle> handles =
				new java.util.ArrayList<>(KernelModLoader.publishedForgeMods().values());
		if (handles.isEmpty()) return;
		try {
			int fired = KernelForgeModContext.fireSetupPhase(cl, handles, event, label);
			if (fired > 0) {
				ForbricLog.info("[Forbric/Lifecycle] posted FML %s to %d traditional-Forge mod(s), then ran what "
						+ "they deferred — a mod that does its real work from this event did nothing at all before",
						label, fired);
			}
		} catch (ClassNotFoundException | NoClassDefFoundError absent) {
			// NoClassDefFoundError as well as CNFE: the event types are named game-side now, so a carrier without
			// them fails when KernelForgeSetup links rather than when Class.forName is called.
			ForbricLog.debug("[Forbric/Lifecycle] no traditional-MinecraftForge %s on this carrier", label);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not post traditional-Forge " + label, Reflect.unwrap(t));
		}
	}

	private static final java.util.concurrent.atomic.AtomicBoolean CLIENT_SETUP_FIRED =
			new java.util.concurrent.atomic.AtomicBoolean();

	private static void fireSetupPhase(ClassLoader cl, java.util.Map<String, KernelModLoader.NeoIdentity> mods,
			ForeignType event, String label) {
		// The NeoForge twin of fireForgeSetupPhase's own empty guard, and it comes before the game-side class is
		// named for the same reason: without it every phase would build a DeferredWorkQueue, hand it to the sync
		// executor and log "posted FML <phase> to 0 NeoForge mod(s)" eight times on a pack that has no NeoForge mod.
		if (mods.isEmpty()) return;
		try {
			int fired = (int) Class.forName("net.forbric.kernel.runtime.KernelNeoSetup", true, cl)
					.getMethod("firePhase", java.util.Map.class, ForeignType.class, String.class)
					.invoke(null, mods, event, label);
			ForbricLog.info("[Forbric/Lifecycle] posted FML %s to %d NeoForge mod(s)", label, fired);
		} catch (ClassNotFoundException | NoClassDefFoundError absent) {
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", event, label);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not post FML " + label, unwrap(t));
		}
	}

	/** The game-side {@code net.neoforged.fml.ModContainer} class. */
	private static Class<?> modContainerClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName(ForeignType.MOD_CONTAINER.binary(Ecosystem.NEOFORGE), false, cl);
	}

	/** Fires RegisterEvent for every registry (vanilla BuiltInRegistries + NeoForgeRegistries) on each bus. */
	/**
	 * Sorts the collected registries into NeoForge's OWN registration order, read from NeoForge at runtime.
	 *
	 * <p>The kernel used to fire {@code RegisterEvent} in the order the registries were COLLECTED, which is the
	 * order {@code BuiltInRegistries} happens to declare its static fields in. NeoForge does not: its
	 * {@code GameData.getRegistrationOrder()} hoists three registries to the front —
	 * {@code minecraft:attribute}, {@code minecraft:data_component_type} and {@code minecraft:particle_type} —
	 * before vanilla's own order and then everything else. Those three are hoisted precisely because a mod's block
	 * and item definitions REFERENCE them while being constructed.
	 *
	 * <p>What the field order cost: {@code BuiltInRegistries} declares {@code ITEM} 57 fields before
	 * {@code DATA_COMPONENT_TYPE}, so a {@code DeferredRegister} item whose builder reads its own mod's
	 * {@code DataComponentType} threw "Trying to access unbound value" from {@code DeferredHolder.value()} — and
	 * because one throw ends that mod's listener, EVERY item it had not registered yet was skipped. Waystones
	 * registered 31 of its 31 blocks and 11 of its 48 items; the other 37 (warp stones, scrolls, shards, and every
	 * portstone/sharestone block item) simply were not there. It stayed invisible while nothing read a Forge-family
	 * mod's {@code data/}, and surfaced the moment {@link KernelDataPacks} did: waystones adds those item ids to the
	 * VANILLA {@code minecraft:enchantable/durability} tag, the tag dropped for dangling references, and vanilla's
	 * own mending/unbreaking/vanishing_curse failed to parse with it.
	 *
	 * <p>Read from NeoForge rather than hardcoded here: it is their contract, it has changed before, and a list
	 * copied into the kernel would go stale silently. Unknown registries — a mod's own, mostly — keep their relative
	 * order at the end, which is where NeoForge puts them too. Fail soft: no order available means today's order.
	 */
	private static List<Object> inNeoForgeRegistrationOrder(ClassLoader cl, Class<?> registryCls,
			List<Object> registries) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.neoRegistrationOrder", "on"))) {
			ForbricLog.warn("[Forbric/Lifecycle] NeoForge registration order DISABLED "
					+ "(-Dforbric.neoRegistrationOrder=off) — RegisterEvent fires in field-declaration order, so a "
					+ "mod whose items read their own data components loses them");
			return registries;
		}
		try {
			Class<?> gameData = Class.forName(ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE), false, cl);
			Object order = gameData.getMethod("getRegistrationOrder").invoke(null);
			if (!(order instanceof java.util.Collection<?> ids) || ids.isEmpty()) return registries;

			Map<String, Integer> rank = new java.util.HashMap<>();
			int next = 0;
			for (Object id : ids) rank.putIfAbsent(String.valueOf(id), next++);
			int unranked = rank.size();

			Method keyM = registryCls.getMethod("key");
			Map<Object, Integer> ranked = new java.util.IdentityHashMap<>();
			for (Object registry : registries) {
				Object key = keyM.invoke(registry);
				Object id = key.getClass().getMethod("identifier").invoke(key);
				ranked.put(registry, rank.getOrDefault(String.valueOf(id), unranked));
			}

			List<Object> sorted = new ArrayList<>(registries);
			// Stable, so everything NeoForge does not rank keeps the collection order the gates have proven.
			sorted.sort(java.util.Comparator.comparingInt(r -> ranked.getOrDefault(r, unranked)));

			int moved = 0;
			for (int i = 0; i < sorted.size(); i++) {
				if (sorted.get(i) != registries.get(i)) moved++;
			}
			if (moved > 0) {
				ForbricLog.info("[Forbric/Lifecycle] fired RegisterEvent in NeoForge's registration order, not "
						+ "BuiltInRegistries' field order — %d registr(ies) moved. attribute, data_component_type and "
						+ "particle_type go first because mods' block and item builders read them", moved);
			}
			return sorted;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] no NeoForge registration order available — firing RegisterEvent in "
					+ "collection order; a mod whose items read their own data components may lose them", unwrap(t));
			return registries;
		}
	}

	private static int fireRegisterEvents(ClassLoader cl, List<Object> buses) throws Exception {
		boolean neoOrder = !"off".equalsIgnoreCase(System.getProperty("forbric.neoRegistrationOrder", "on"));
		if (!neoOrder) {
			ForbricLog.warn("[Forbric/Lifecycle] NeoForge registration order DISABLED "
					+ "(-Dforbric.neoRegistrationOrder=off) — RegisterEvent fires in field-declaration order, so a "
					+ "mod whose items read their own data components loses them");
		}
		Class<?> gameSide = neoRegistryClass(cl);
		Object registries = gameSide.getMethod("collect", boolean.class).invoke(null, neoOrder);
		return (int) gameSide.getMethod("fireRegisterEvents", List.class, List.class)
				.invoke(null, buses, registries);
	}

	/** The game-side half of NeoForge's registry phase. */
	private static Class<?> neoRegistryClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.forbric.kernel.runtime.KernelNeoRegistries", true, cl);
	}

	/**
	 * Posts NeoForge's {@code NewRegistryEvent} to every mod bus, then fills it — the phase before
	 * {@code RegisterEvent} where mods create their own registries.
	 *
	 * <p>The kernel fired this only on traditional Forge's global bus ({@code KernelForgeBaseline}) and drove
	 * NeoForge's own handler directly in {@link PassiveSeeder}, so no NeoForge MOD ever received it. Two things
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
			ForbricLog.debug("[Forbric/Lifecycle] no NewRegistryEvent type — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not post NeoForge NewRegistryEvent", unwrap(t));
		}
	}

	private static void unfreeze(ClassLoader cl) {
		invokeGameData(cl, "unfreezeData");
	}

	/** {@code -Dforbric.freezeNeoForgeFirst=off}: freeze in {@link #GAME_DATA_CLASSES} order (MinecraftForge first), as before. */
	static final String FREEZE_ORDER_PROPERTY = "forbric.freezeNeoForgeFirst";

	/**
	 * NeoForge first, then MinecraftForge — the one order in which both {@code freezeData()} calls finish.
	 *
	 * <p>Bytecode, both carriers: NeoForge's {@code GameData.freezeData} walks {@code BuiltInRegistries.REGISTRY},
	 * and for every {@code MappedRegistry} calls {@code bindAllTagsToEmpty()} then {@code freeze()}, then
	 * {@code RegistryManager.takeFrozenSnapshot()}. {@code bindAllTagsToEmpty} starts with {@code validateWrite},
	 * which THROWS on a registry that is already frozen — and MinecraftForge's {@code freezeData} freezes every
	 * plain {@code MappedRegistry} ({@code freeze()}, bc 73-89). Forge first therefore aborted NeoForge's pass at
	 * the FIRST registry: the "GameData.freezeData() THREW" warning on every boot, no tag keys bound to empty
	 * until the tag reload ({@code Trying to access unbound value} downstream), and the snapshot never taken.
	 *
	 * <p>NeoForge first: registries are still writable, so its bind + freeze + snapshot complete. MinecraftForge's
	 * pass afterwards is provably non-destructive: on a plain {@code MappedRegistry} its {@code freeze()} early-returns
	 * (bc 0-8, already frozen); the ≤3 {@code NamespacedWrapper}s it created itself are unfrozen and re-frozen
	 * ({@code isFrozen→unfreeze→freeze}, bc 42-70) — {@code unfreeze()} touches only {@code frozen}/{@code frozenTags},
	 * and {@code NamespacedWrapper.freeze()} never calls {@code super.freeze()}, so NeoForge's bake callbacks do not
	 * run twice. The one declared delta: those wrappers are frozen twice ({@code onBindTags},
	 * {@code refreshTagsInHoldersForge} and the {@code DataComponentLookup} rebuilt twice on the same tag map —
	 * idempotent), and {@code RegistryManager.takeFrozenSnapshot()} now genuinely runs.
	 */
	private static final String[] FREEZE_ORDER = {
		ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE),
		ForeignType.GAME_DATA.binary(Ecosystem.FORGE),
	};

	private static String[] freezeOrder() {
		return "off".equalsIgnoreCase(System.getProperty(FREEZE_ORDER_PROPERTY, "on")) ? GAME_DATA_CLASSES : FREEZE_ORDER;
	}

	private static void freeze(ClassLoader cl) {
		for (String className : freezeOrder()) invokeGameDataOn(cl, className, "freezeData");
		latchRegistriesLoaded(cl);
		describeFreeze(cl);
	}

	/**
	 * One count line: how many registries were frozen and how many tag keys NeoForge bound to empty. Read from
	 * {@code MappedRegistry.frozenTags}, NOT {@code getTags()}/{@code listTags()} — those read {@code allTags},
	 * which stays unbound until the tag reload and would count zero on a correct boot.
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
					}
				}
			}
			boundText = Integer.toString(bound);
		} catch (Throwable unreadable) {
			ForbricLog.debug("[Forbric/Lifecycle] could not count the frozen registries: %s", String.valueOf(unreadable));
			if (registries == 0) return;
			boundText = "?";
		}
		boolean neoFirst = freezeOrder() == FREEZE_ORDER;
		ForbricLog.info("[Forbric/Lifecycle] froze the registries %s: %d registr%s, %s tag key(s) bound to empty until the "
				+ "tag reload%s", neoFirst ? "NeoForge-first" : "MinecraftForge-first", registries,
				registries == 1 ? "y" : "ies", boundText,
				neoFirst ? " (MinecraftForge's pass then re-froze the Forge-wrapped ones)" : "");
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
			ForbricLog.debug("[Forbric/Lifecycle] no CommonModLoader.registriesLoaded to latch: %s",
					String.valueOf(absent));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not latch CommonModLoader.registriesLoaded — mods gating on "
					+ "areRegistriesLoaded() will refuse to register", unwrap(t));
		}
	}

	/**
	 * Opens or closes the ROOT registry, which both ecosystems' {@code GameData} leaves alone.
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

			// REGISTRY is the public read view and on the merged base it is Forge-wrapped; WRITABLE_REGISTRY is the
			// plain MappedRegistry that Registry.register actually validates against. Reopening only the first is
			// the mistake that leaves this looking fixed while "Registry is already frozen" keeps being thrown.
			for (String fieldName : new String[] {"REGISTRY", "WRITABLE_REGISTRY"}) {
				Field field;
				try {
					field = builtIn.getDeclaredField(fieldName);
				} catch (NoSuchFieldException notOnThisVersion) {
					continue;
				}
				field.setAccessible(true);
				Object root = field.get(null);
				if (root == null) continue;

				if (open) openRegistry(root); else root.getClass().getMethod("freeze").invoke(root);
			}
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no BuiltInRegistries to reopen — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not " + (open ? "unfreeze" : "freeze") + " the root registry "
					+ "— a Fabric mod adding its own registry (FabricRegistryBuilder) may fail", unwrap(t));
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
		}

		Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", false, registry.getClass()
				.getClassLoader());
		Field frozen = mapped.getDeclaredField("frozen");
		frozen.setAccessible(true);
		frozen.setBoolean(registry, false);
	}

	/** What a reopened registration window has to put back when it closes. */
	private record ReopenedRegistries(List<Object> lockedWrappers, List<Object> frozenForgeRegistries) {
		static final ReopenedRegistries NONE = new ReopenedRegistries(List.of(), List.of());

		int count() {
			return lockedWrappers.size() + frozenForgeRegistries.size();
		}
	}

	/**
	 * Opens all THREE gates that stand between a late {@code Registry.register} and the registry it targets.
	 *
	 * <p>{@code unfreezeData} clears only the first. On the merged base every vanilla registry is additionally
	 * wrapped by MinecraftForge, and Forge closes registration twice more:
	 *
	 * <ol>
	 *   <li>vanilla {@code MappedRegistry.frozen} — cleared by {@code GameData.unfreezeData}</li>
	 *   <li>{@code NamespacedWrapper.locked} — set by {@code GameData.postRegisterEvents} at the end of the main
	 *       window. {@code ILockableRegistry} declares {@code lock()} and deliberately nothing to undo it, so the
	 *       flag is cleared directly. Symptom while set: "Can not register to a locked registry."</li>
	 *   <li>{@code ForgeRegistry.isFrozen} on the backing registry in {@code RegistryManager.ACTIVE} — this one
	 *       does have {@code unfreeze()}. Symptom while set: "… is being added too late."</li>
	 * </ol>
	 *
	 * <p>All three are Forge's answer to "a Forge mod should use {@code DeferredRegister}, not register late". A
	 * Fabric mod has no such contract — it calls {@code Registry.register} directly, and on Fabric that keeps
	 * working right through client init — so a window the kernel opens for Fabric code has to open all three.
	 */
	private static ReopenedRegistries reopenForgeRegistries(ClassLoader cl) {
		List<Object> unlocked = new ArrayList<>();
		List<Object> unfrozen = new ArrayList<>();

		try {
			Class<?> wrapper = Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Field lockedField = wrapper.getDeclaredField("locked");
			lockedField.setAccessible(true);

			for (Object registry : rootRegistries(cl)) {
				if (!wrapper.isInstance(registry) || !lockedField.getBoolean(registry)) continue;

				lockedField.setBoolean(registry, false);
				unlocked.add(registry);
			}
		} catch (ClassNotFoundException | NoSuchFieldException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no MinecraftForge registry lock to clear — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not clear the MinecraftForge registry lock", unwrap(t));
		}

		try {
			Class<?> forgeRegistry = Class.forName("net.minecraftforge.registries.ForgeRegistry", false, cl);
			Field isFrozen = forgeRegistry.getDeclaredField("isFrozen");
			isFrozen.setAccessible(true);

			for (Object registry : activeForgeRegistries(cl)) {
				if (!isFrozen.getBoolean(registry)) continue;

				forgeRegistry.getMethod("unfreeze").invoke(registry);
				unfrozen.add(registry);
			}
		} catch (ClassNotFoundException | NoSuchFieldException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no MinecraftForge ForgeRegistry to unfreeze — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not unfreeze the MinecraftForge registries", unwrap(t));
		}

		return new ReopenedRegistries(unlocked, unfrozen);
	}

	/**
	 * Puts back exactly what {@link #reopenForgeRegistries} opened — each gate by the same mechanism that opened it.
	 *
	 * <p>{@code ForgeRegistry} is public, so {@code freeze()} is reachable by reflection. {@code NamespacedWrapper}
	 * is NOT: it is package-private, and {@code Method.invoke} on a public method of a package-private class throws
	 * {@code IllegalAccessException} from outside the package however public the method looks. Calling
	 * {@code lock()} therefore failed on all 30 wrappers, every boot, and only said so at WARN — so the registration
	 * window the kernel opens for late Fabric registration was never closed again on the MinecraftForge side.
	 *
	 * <p>The fix is the symmetric one rather than {@code setAccessible} on the method, because
	 * {@link #reopenForgeRegistries} opens this gate by writing the {@code locked} field directly and
	 * {@code NamespacedWrapper.lock()} is, verbatim, {@code this.locked = true} — nothing else. Reversing a field
	 * write with a field write cannot drift from what it undoes; going through the method could, the day Forge gives
	 * {@code lock()} a body.
	 */
	private static void recloseForgeRegistries(ClassLoader cl, ReopenedRegistries opened) {
		for (Object registry : opened.frozenForgeRegistries()) {
			invokeNoArg(registry, "freeze", "re-freeze a MinecraftForge registry");
		}

		List<Object> wrappers = opened.lockedWrappers();
		if (wrappers.isEmpty()) return;

		try {
			Class<?> wrapper = Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Field lockedField = wrapper.getDeclaredField("locked");
			lockedField.setAccessible(true);

			for (Object registry : wrappers) {
				lockedField.setBoolean(registry, true);
			}
			ForbricLog.debug("[Forbric/Lifecycle] re-locked %d MinecraftForge registry wrapper(s)", wrappers.size());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not re-lock %d MinecraftForge registry wrapper(s) — late "
					+ "registration into them stays possible for the rest of this run", wrappers.size());
			ForbricLog.debug("[Forbric/Lifecycle] re-lock failure", unwrap(t));
		}
	}

	private static void invokeNoArg(Object target, String method, String what) {
		try {
			target.getClass().getMethod(method).invoke(target);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not " + what, unwrap(t));
		}
	}

	/** Every registry in the root {@code BuiltInRegistries.REGISTRY}, plus the root itself. */
	private static List<Object> rootRegistries(ClassLoader cl) throws Exception {
		Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
		Object root = builtIn.getField("REGISTRY").get(null);
		List<Object> all = new ArrayList<>();
		all.add(root);

		for (Object registry : (Iterable<?>) root) all.add(registry);
		return all;
	}

	/** The {@code ForgeRegistry} instances backing {@code RegistryManager.ACTIVE}. */
	private static List<Object> activeForgeRegistries(ClassLoader cl) throws Exception {
		Class<?> managerCls = Class.forName(ForeignType.REGISTRY_MANAGER.binary(Ecosystem.FORGE), false, cl);
		Object active = managerCls.getField("ACTIVE").get(null);
		Field registries = managerCls.getDeclaredField("registries");
		registries.setAccessible(true);

		return new ArrayList<>(((java.util.Map<?, ?>) registries.get(active)).values());
	}

	/**
	 * BOTH ecosystems ship their own {@code GameData} (same API, different registry bookkeeping) and the kernel's
	 * registration window must drive both — driving only MinecraftForge's left NeoForge's registry callbacks unfired,
	 * so {@code NeoForgeRegistryCallbacks$BlockCallbacks.onBake} never rebuilt its blockstate→id map and the first
	 * {@code clientbound/minecraft:block_update} failed to encode ("Can't find id for Block{minecraft:lava}").
	 */
	private static final String[] GAME_DATA_CLASSES = {
		ForeignType.GAME_DATA.binary(Ecosystem.FORGE),
		ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE),
	};

	private static void invokeGameData(ClassLoader cl, String method) {
		for (String className : GAME_DATA_CLASSES) {
			invokeGameDataOn(cl, className, method);
		}
	}

	/** Invokes a no-arg static hook, tolerating its absence — for reaching past a method the kernel must not call. */
	private static void invokeStaticOn(ClassLoader cl, String className, String method) {
		invokeGameDataOn(cl, className, method);
	}

	/**
	 * One ecosystem's {@code GameData} only — for steps whose semantics differ between the two families.
	 *
	 * <p><b>ABSENT and FAILED are not the same thing, and this method used to report them the same way.</b> The
	 * absence of a class is ordinary: only one Forge family may be present, and {@link #invokeGameData} deliberately
	 * asks both. A method that is THERE and THREW is the opposite of ordinary, and every caller here is a whole
	 * feature: the registry bake, the freeze/unfreeze pair, {@code CommonHooks.modifyAttributes},
	 * {@code SpawnPlacements.fireSpawnPlacementEvent}, {@code GameRuleCategory.registerModdedCategories}.
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
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", className, method);
			return;
		}
		java.lang.reflect.Method hook;
		try {
			hook = owner.getMethod(method);
		} catch (NoSuchMethodException gone) {
			ForbricLog.warn("[Forbric/Lifecycle] %s has no %s() — the kernel expected that hook on this carrier, and "
					+ "whatever it does is now simply not done", className, method);
			return;
		}
		try {
			hook.invoke(null);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] " + className + "." + method + "() THREW — it exists and did not "
					+ "finish, so whatever it drives is half-done (an attribute/spawn-placement event aborts at the "
					+ "first failing mod, and every mod after it in the list is skipped)", unwrap(t));
		}
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}

	/**
	 * Called from the game side: {@code ClientModLoader.setupModResourcePacks(PackRepository)} inside
	 * {@code Minecraft.<init>}, redirected here by {@code ClientPackHookInjector}. This is the one correctly-timed
	 * handle on the live client {@code PackRepository} — before the first resource reload — so the kernel serves the
	 * ecosystem jars' assets (NeoForge's shaders, Forge mods' textures/models) here.
	 *
	 * <p>Both ecosystems' ClientModLoaders route here, so it may be called more than once;
	 * {@link KernelClientPacks#addTo} is cheap and the repository de-dups by pack id.
	 */
	public static void onClientResourcePacks(Object packRepository) {
		ClassLoader cl = gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
		List<Path> jars = new ArrayList<>(runtimeJars);
		jars.addAll(modJars);
		jars.addAll(kernelAssetJars);
		KernelClientPacks.addTo(packRepository, cl, jars);
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

	/** Variant for a traditional-Forge {@code ServerModLoader.load()V} (no-arg) redirect. */
	public static void onServerModLoadingNoArg() {
		onServerModLoading(true);
	}

	/**
	 * Fires the Fabric {@code client} entrypoints. Injected into {@code Minecraft.<init>} (by
	 * {@code ClientEntrypointHookInjector}) just before {@code Options} is created — after the {@code Minecraft}
	 * singleton is set, so {@code Minecraft.getInstance()} is live but {@code getInstance().options} is still null.
	 * That is the exact window Fabric uses and that keymapping registration (and other instance-touching client
	 * setup) requires; running them earlier, in the pre-{@code Minecraft} registration window, NPE'd on a null
	 * instance (Jade's keybinds). Best-effort — a failing entrypoint must not abort client startup.
	 */
	private static final java.util.concurrent.atomic.AtomicInteger CREATIVE_SKIPS =
			new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Called from the rewritten NeoForge creative-tab output when an entry collapses to an empty stack.
	 *
	 * <p>Diagnostic, not policy: a handful of skips is a mod feeding in a block with no item form, but skipping
	 * EVERY entry means the tab builds empty and Minecraft then hides it — which looks identical to "the tab was
	 * never registered". The count is what distinguishes those two.
	 */
	public static void onCreativeTabEntrySkipped() {
		int n = CREATIVE_SKIPS.incrementAndGet();
		if (n <= 3 || n % 50 == 0) {
			ForbricLog.warn("[Forbric/Creative] skipped %d empty creative-tab stack(s) so far", n);
		}
	}

	/**
	 * Runs the Fabric client entrypoints with the registries REOPENED, because registering content from
	 * {@code onInitializeClient} is ordinary Fabric practice and it has to keep working here.
	 *
	 * <p>On Fabric both entrypoint phases run at the head of {@code Minecraft.<init>}, and the freeze does not
	 * happen until after that: {@code fabric-registry-sync-v0}'s {@code BuiltInRegistriesMixin} CANCELS the
	 * {@code BuiltInRegistries.bootStrap()} vanilla performs during {@code Bootstrap}, and its {@code MainMixin}
	 * re-runs it later in {@code Main.main}. The kernel owns registration instead and suppresses both of those
	 * mixins, so by the time this hook fires its own window has already closed and every registry is frozen —
	 * a mod registering here died, and if it swallowed the failure the damage surfaced far away. Xaero's World Map
	 * registers its status effects from {@code loadCommon()} and catches Throwable into a field, so the only symptom
	 * was {@code WorldMap.events} still being null a hundred ticks later, inside {@code Minecraft.runTick}.
	 *
	 * <p>Reopening is the smaller half of the job: the ids assigned here have to be rebuilt into NeoForge's
	 * blockstate map and any late {@code BlockItem} linked back to its block, exactly as at the end of the main
	 * window — otherwise the first block update fails to encode. Both run in a {@code finally} so a mod throwing
	 * cannot leave the registries open.
	 */
	public static void onClientEntrypoints() {
		ClassLoader cl = gameLoader;
		ReopenedRegistries opened = ReopenedRegistries.NONE;
		boolean reopened = false;

		try {
			unfreeze(cl);
			rootRegistry(cl, true);
			opened = reopenForgeRegistries(cl);
			reopened = true;
			ForbricLog.info("[Forbric/Lifecycle] registries reopened for the Fabric client entrypoints "
					+ "(%d MinecraftForge gate(s) cleared)", opened.count());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not reopen the registries for the Fabric client "
					+ "entrypoints — a mod registering content from onInitializeClient will fail", unwrap(t));
		}

		try {
			KernelFabricEcosystem.runClientEntrypoints();
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] client entrypoints failed", unwrap(t));
		} finally {
			if (reopened) closeClientEntrypointWindow(cl, opened);
			// A CLIENT config registered from a Fabric client entrypoint missed the early pass entirely, and
			// nothing else opens one. Reading it then throws rather than returning a default.
			openLateConfigs(cl, Side.CLIENT, "the Fabric client entrypoints");
		}

	}

	/**
	 * Invoked from {@code Minecraft.<init>} at the merged base's own {@code ClientModLoader.finish()} call
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
		// The three CLIENT_INIT bridges are landed by class transformers in Minecraft and BlockColors, both defined
		// before this point, so this is the first moment their absence can be named with its cost rather than
		// noticed later as an empty Controls screen.
		EventBridges.verify(GameEventBridge.Pass.CLIENT_INIT);
		preloadClientResources(cl);
		fireClientSetupLifecycle(cl);
		// Common setup now runs in there, and registering a config is one of the things mods do from it. On the
		// server the pass right after the setup lifecycle catches those; the client had no equivalent once the
		// phases moved here, so a config registered from client-side common setup was registered and never
		// loaded — and reading it throws rather than returning a default.
		openLateConfigs(cl, Side.CLIENT, "the client setup lifecycle");
		// And only then close the payload registration phase — see step 3c for why it cannot precede setup.
		setupNeoForgeNetwork(cl, Side.CLIENT);
	}

	/**
	 * Fills the client {@code ResourceManager} with its selected packs before mod setup runs.
	 *
	 * <p>The window this hook sits in is genuine NeoForge's, and NeoForge does not do this — but MinecraftForge
	 * runs its whole mod-loading INSIDE the first resource reload, so a MinecraftForge mod reading one of its own
	 * assets from client setup is entitled to find it. See {@code KernelClientResources}.
	 */
	private static void preloadClientResources(ClassLoader cl) {
		try {
			Object count = Class.forName("net.forbric.kernel.runtime.KernelClientResources", true, cl)
					.getMethod("preload").invoke(null);
			int packs = count instanceof Integer i ? i : -2;
			if (packs >= 0) {
				ForbricLog.info("[Forbric/ClientResources] client resource manager preloaded with %d selected "
						+ "pack(s) before mod setup — MinecraftForge runs mod loading INSIDE the first resource "
						+ "reload, so a mod reading its own asset from client setup expects one that answers "
						+ "(-D%s=off to leave it empty until vanilla's reload)",
						packs, "forbric.clientResourcePreload");
			} else if (packs == -2) {
				ForbricLog.warn("[Forbric/ClientResources] could not preload the client resource manager — a mod "
						+ "reading its own asset from client setup will get an empty Optional, and several call "
						+ "get() on it without checking");
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientResources] client resource preload did not run", unwrap(t));
		}
	}

	/** Re-closes after the client entrypoints and redoes the id bookkeeping their registrations invalidated. */
	private static void closeClientEntrypointWindow(ClassLoader cl, ReopenedRegistries opened) {
		try {
			linkBlockItems(cl);
			recloseForgeRegistries(cl, opened);
			rootRegistry(cl, false);
			freeze(cl);
			rebuildNeoForgeBlockStateIds(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not re-close after the Fabric client entrypoints", unwrap(t));
		}
	}
}
