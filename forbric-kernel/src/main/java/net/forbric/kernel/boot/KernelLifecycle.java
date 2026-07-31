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

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

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
	public static void setModJars(List<Path> jars) {
		modJars = jars == null ? List.of() : jars;
	}

	/** The ecosystem runtime jars (forge/neoforge). Each is that ecosystem's OWN mod file — FML scans it too. */
	public static void setRuntimeJars(List<Path> jars) {
		runtimeJars = jars == null ? List.of() : jars;
	}

	/**
	 * Invoked from {@code net.minecraft.server.Main.main} (redirected from {@code ServerModLoader.load}) after
	 * {@code Bootstrap.bootStrap}. Drives native ecosystem registration. {@code dedicated} is the original argument.
	 */
	public static void onServerModLoading(boolean dedicated) {
		driveNativeRegistration("server");
	}

	/**
	 * Invoked from {@code net.minecraft.client.main.Main.main} (redirected from {@code ClientModLoader.begin}) after
	 * {@code Bootstrap.validate} (which asserts {@code Bootstrap.bootStrap} already ran). Same native registration
	 * as the server — the ecosystems' baselines + content are side-independent; the Fabric ecosystem runs its
	 * {@code client} entrypoints rather than {@code server} ones, driven by {@code KernelFabricLoader}'s env type.
	 */
	public static void onClientModLoading() {
		driveNativeRegistration("client");
	}

	private static void driveNativeRegistration(String side) {
		ClassLoader cl = gameLoader != null ? gameLoader : Thread.currentThread().getContextClassLoader();
		boolean client = "client".equals(side);
		ForbricLog.info("[Forbric/Lifecycle] kernel %s mod-loading window (native, no FancyModLoader) — "
				+ "registering ecosystem baselines", side);
		// Step 0: seed traditional Forge's empty LoadingModList (ServerStatusPing / client status touch it later).
		PassiveSeeder.seedForgeLoadingModList(cl);
		// Step 1: register NeoForge's baseline registries (neoforge:fluid_type, …) into the root. Correctly timed
		// now (post-Bootstrap), unlike the pre-Main attempt which tripped "Not bootstrapped".
		PassiveSeeder.seedNeoForgeRegistries(cl);
		// Step 1b: mark NeoForge's VANILLA_SYNC_REGISTRIES (item/block/fluid/recipe_serializer/…) as client-syncing.
		// NeoForge's ByteBufCodecs registry-ID sync path (getSyncableRegistryOrThrow → RegistryManager
		// .isNonSyncedBuiltInRegistry → Registry.doesSync()) throws "Cannot use ID syncing for non-synced built-in
		// registry: minecraft:item" when the vanilla registries carry doesSync()=false. NeoForgeRegistriesSetup
		// normally sets these; the kernel doesn't run that setup, so mark them here (BaseMappedRegistry.setSync(true)).
		// Without this, the player logs in but the clientbound update_recipes packet fails to encode → disconnect.
		markVanillaRegistriesSynced(cl);
		// Step 2: construct both ecosystem baselines + fire RegisterEvent so default content (e.g. minecraft:empty
		// FluidType, default attributes) registers, and run the Fabric main + side entrypoints in the same window.
		registerNeoForgeContent(cl, client);
		// Step 2b (client only): construct ClientNeoForgeMod on the baseline bus + populate the ModList with the
		// baseline container, so the game's ModLoader.postEvent(<client mod-bus event>) — fired from
		// ClientHooks.initClientHooks during Minecraft.<init> for reload listeners, entity renderers, sprite
		// sources, client extensions — reaches NeoForge's built-in client handlers.
		if (client) registerNeoForgeClientContent(cl);
		// Step 2c (client only): load the NeoForge config specs the baselines just registered. Client-side
		// listeners read CLIENT/COMMON values early (e.g. TagConventionLogWarningClient on ServerStartingEvent when
		// entering a singleplayer world reads a CLIENT value) — an unloaded spec throws "Cannot get config value
		// before config is loaded". Scoped to the client so the proven server path (gate-m3/m4) is untouched.
		if (client) loadClientConfigs(cl);
		// Step 2c2: wire NeoForge's OWN @EventBusSubscriber classes from its runtime jar. NeoForge ships as a mod and
		// FML scans its jar like any other; the kernel scanned only mod jars, so ~10 internal subscribers (network,
		// attachments, configuration tasks, model data, …) never fired. Must precede step 2d — the network setup posts
		// its Register*PayloadHandlersEvent to exactly these subscribers.
		KernelEventSubscribers.registerNeoForgeInternal(cl, runtimeJars, baselineBus, client);
		// Step 2c3 (client only): NeoForge won the client reload-listener path in the byte merge, so MinecraftForge's
		// RegisterClientReloadListenersEvent is never posted and a Forge mod's handler for it sits on a dead bus.
		// Bridge it off NeoForge's AddClientReloadListenersEvent, which ClientHooks.initClientHooks posts to the
		// baseline mod bus during Minecraft.<init> — i.e. after this point, which is why the listener goes on now.
		if (client) GameEventMultiplexer.installClientReloadBridge(cl, baselineBus);
		// Step 2d (client only): run NeoForge's two-phase network setup, which posts the Register*PayloadHandlersEvent
		// pair to the subscribers wired above so its built-in play payloads (neoforge:recipe_content, …) become
		// sendable AND have client handlers. Without it the player is kicked "Invalid player data" right after
		// spawning. Client-scoped (its integrated server shares these statics); the dedicated server path is untouched.
		if (client) setupNeoForgeNetwork(cl);
		// Step 3: register mods' @EventBusSubscriber game-event listeners (FML's AutomaticEventSubscriber, native).
		KernelEventSubscribers.registerAll(cl, modJars, client);
		// Step 3b: post the FML setup lifecycle at every NeoForge mod. Genuine NeoForge produces these inside
		// CommonModLoader.load(), whose only client caller is ClientModLoader.finish() — which the kernel neuters
		// because it also drives the discovery/registration the kernel owns. Nothing replaced the setup phases, so
		// they never fired for anyone: AppleSkin registers its food tooltip from FMLClientSetupEvent
		// (preInitClient -> TooltipOverlayHandler.init -> NeoForge.EVENT_BUS.register), which is why the tooltip
		// stayed missing even after mod-bus delivery was fixed.
		fireModSetupLifecycle(cl, client);
		// Step 4: start the game event buses so mods' game-event listeners actually dispatch — the buses buffer
		// until start()/startup().
		startGameBuses(cl);
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
		try {
			Class<?> gameData = Class.forName("net.neoforged.neoforge.registries.GameData", false, cl);
			Object idMap = gameData.getMethod("getBlockStateIDMap").invoke(null);
			Class<?> idMapper = Class.forName("net.minecraft.core.IdMapper", false, cl);
			if ((Integer) idMapper.getMethod("size").invoke(idMap) > 0) return; // NeoForge kept it — leave it alone
			java.lang.reflect.Method add = idMapper.getMethod("add", Object.class);

			Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", false, cl);
			java.lang.reflect.Method getStateDefinition = blockCls.getMethod("getStateDefinition");
			Class<?> stateDefCls = Class.forName("net.minecraft.world.level.block.state.StateDefinition", false, cl);
			java.lang.reflect.Method getPossibleStates = stateDefCls.getMethod("getPossibleStates");

			Object blockRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl)
					.getField("BLOCK").get(null);
			int states = 0;
			for (Object block : (Iterable<?>) blockRegistry) {
				for (Object state : (java.util.List<?>) getPossibleStates.invoke(getStateDefinition.invoke(block))) {
					add.invoke(idMap, state);
					states++;
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] rebuilt NeoForge blockstate→id map (%d states) — the registration "
					+ "window's clear callback had emptied it", states);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not rebuild NeoForge blockstate→id map "
					+ "(block_update packets will fail to encode)", unwrap(t));
		}
	}

	/**
	 * Wires NeoForge's built-in network payloads: subscribes {@code NetworkInitialization#register} (its
	 * {@code @EventBusSubscriber} payload handler, which the kernel's mod-jar-only scan misses) to the baseline mod
	 * bus, then runs {@code NetworkRegistry.setup()} — which posts {@code RegisterPayloadHandlersEvent} through
	 * {@code ModLoader} to that bus, populating {@code PAYLOAD_REGISTRATIONS} so payloads like
	 * {@code neoforge:recipe_content} become sendable. Best-effort; failure only leaves payloads unregistered.
	 */
	private static void setupNeoForgeNetwork(ClassLoader cl) {
		// Two-phase, in this order: NetworkRegistry.setup() posts RegisterPayloadHandlersEvent (payload TYPES + codecs,
		// incl. playToClient(neoforge:recipe_content)); ClientNetworkRegistry.setup() then posts
		// RegisterClientPayloadHandlersEvent (the CLIENT HANDLERS) and validates every to-client payload has one —
		// else the join negotiation rejects with "Incompatible client! (No Handler for …)". Both events reach
		// NeoForge's own handlers only because step 2c2 wired its runtime-jar @EventBusSubscribers.
		invokeNetworkSetup(cl, "net.neoforged.neoforge.network.registration.NetworkRegistry");
		invokeNetworkSetup(cl, "net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry");
	}

	/** Runs a NeoForge {@code *NetworkRegistry.setup()} — it posts its Register*PayloadHandlersEvent via ModLoader. */
	private static void invokeNetworkSetup(ClassLoader cl, String registryClass) {
		try {
			Class.forName(registryClass, false, cl).getMethod("setup").invoke(null);
			ForbricLog.info("[Forbric/Lifecycle] %s.setup() — payload handlers registered",
					registryClass.substring(registryClass.lastIndexOf('.') + 1));
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] " + registryClass + ".setup() failed — NeoForge payloads incomplete "
					+ "(join may fail 'No Handler for …')", unwrap(t));
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
	 * Loads NeoForge's STARTUP/COMMON/CLIENT config specs (registered by the baselines just constructed) from the
	 * config dir, so {@code ModConfigSpec.ConfigValue.get()} reads work later in the client/world lifecycle. Missing
	 * files are fine — NeoForge writes defaults. Best-effort per type; a failure is logged, not fatal.
	 */
	private static void loadClientConfigs(ClassLoader cl) {
		try {
			Class<?> trackerCls = Class.forName("net.neoforged.fml.config.ConfigTracker", false, cl);
			Object tracker = trackerCls.getField("INSTANCE").get(null);
			Class<?> typeCls = Class.forName("net.neoforged.fml.config.ModConfig$Type", false, cl);
			Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths", false, cl);
			Object configDirEnum = fmlPaths.getField("CONFIGDIR").get(null);
			java.nio.file.Path configDir = (java.nio.file.Path) fmlPaths.getMethod("get").invoke(configDirEnum);
			java.lang.reflect.Method loadConfigs =
					trackerCls.getMethod("loadConfigs", typeCls, java.nio.file.Path.class);
			for (String t : new String[] {"STARTUP", "COMMON", "CLIENT"}) {
				try {
					Object type = Enum.valueOf(typeCls.asSubclass(Enum.class), t);
					loadConfigs.invoke(tracker, type, configDir);
				} catch (Throwable t2) {
					ForbricLog.debug("[Forbric/Lifecycle] config load %s: %s", t, String.valueOf(unwrap(t2)));
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] loaded NeoForge configs (STARTUP+COMMON+CLIENT) from %s", configDir);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not load NeoForge client configs", unwrap(t));
		}
	}

	/** Starts NeoForge's global game bus + Forge's DEFAULT BusGroup so game-event listeners dispatch. */
	private static void startGameBuses(ClassLoader cl) {
		// Bridge merge-lost game events (Neo won the tick hook → forward to Forge) BEFORE starting the buses.
		GameEventMultiplexer.install(cl);
		try {
			Class<?> neoForge = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl);
			Object bus = neoForge.getField("EVENT_BUS").get(null);
			Class.forName("net.neoforged.bus.api.IEventBus", false, cl).getMethod("start").invoke(bus);
			ForbricLog.info("[Forbric/Lifecycle] started NeoForge.EVENT_BUS (game events now dispatch)");
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Lifecycle] could not start NeoForge.EVENT_BUS: %s", String.valueOf(unwrap(t)));
		}
		try {
			Class<?> busGroup = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, cl);
			Object def = busGroup.getField("DEFAULT").get(null);
			busGroup.getMethod("startup").invoke(def);
			ForbricLog.info("[Forbric/Lifecycle] started Forge BusGroup.DEFAULT (game events now dispatch)");
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Lifecycle] could not start Forge BusGroup.DEFAULT: %s", String.valueOf(unwrap(t)));
		}
	}

	/**
	 * Constructs NeoForge's baseline mod ({@code NeoForgeMod}) on a fresh mod-event bus — which registers its
	 * {@code DeferredRegister}s — then fires {@code RegisterEvent} per registry so those DeferredRegisters flush
	 * their default content (the empty/water/lava FluidTypes, default attributes, …). A single unfreeze/freeze
	 * window surrounds the registration; because the kernel drives ONE pass (no dual-ecosystem refreeze), the
	 * "Tags not bound" wall of the old weld does not arise.
	 */
	private static void registerNeoForgeContent(ClassLoader cl, boolean client) {
		try {
			Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", false, cl);
			Object dist = Enum.valueOf(distClass.asSubclass(Enum.class), client ? "CLIENT" : "DEDICATED_SERVER");

			// The NeoForge baseline mod on its own bus. Captured so the client step can add ClientNeoForgeMod to
			// the same bus + route the game's mod-bus events to its container.
			Object bus = KernelBusSupport.makeModBus(cl);
			Object container = KernelModContainerFactory.create((ForbricClassLoader) cl, cl, "neoforge", bus);
			baselineBus = bus;
			baselineContainer = container;
			Class<?> neoForgeMod = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", false, cl);
			Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> modContainer = Class.forName("net.neoforged.fml.ModContainer", false, cl);
			neoForgeMod.getConstructor(iEventBus, distClass, modContainer)
					.newInstance(bus, dist, container);
			ForbricLog.info("[Forbric/Lifecycle] constructed NeoForge baseline mod on a native bus (dist=%s)",
					client ? "CLIENT" : "DEDICATED_SERVER");
			Object baselineBus = bus;

			// Real Forge-family @Mods, each on its own bus.
			List<KernelModLoader.ConstructedMod> mods =
					KernelModLoader.constructMods((ForbricClassLoader) cl, cl, modJars, client);

			// Load the config specs those constructors just registered, BEFORE any RegisterEvent fires. Genuine
			// NeoForge loads STARTUP/COMMON right after construction and only then posts the registry events, and
			// mods rely on that: Mob Champions' MobChampionsEffects.<clinit> runs from its RegisterEvent listener
			// and reads a config value, so with the load still pending it threw "Cannot get config value before
			// config is loaded" — which, before the isolation below, aborted the whole window and left even the
			// NeoForge baseline unregistered (later surfacing as an unbound neoforge:fluid_type/water). Re-run after
			// the client baseline in driveNativeRegistration too, for specs registered later; loading twice is
			// harmless (each type is attempted independently and a redundant load is swallowed).
			loadClientConfigs(cl);

			// Each ecosystem's mods take their own RegisterEvent flavour: NeoForge's 2-arg event on an IEventBus, and
			// traditional Forge's 3-arg (key, ForgeRegistry, Registry) on a BusGroup. Split them here; both streams
			// run inside the one unfreeze/freeze window below.
			List<Object> buses = new ArrayList<>();
			buses.add(baselineBus);
			List<KernelForgeModContext.Handle> forgeHandles = new ArrayList<>();
			// Dedupe by IDENTITY: a NeoForge mod has ONE bus shared by all its @Mod classes (balm ships
			// NeoForgeBalm + NeoForgeBalmClient, FallingTree the same), so a per-entry list would fire
			// RegisterEvent twice on that bus and register the mod's content twice.
			java.util.Set<Object> seenBuses =
					java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			for (KernelModLoader.ConstructedMod m : mods) {
				if (m.bus() != null && seenBuses.add(m.bus())) buses.add(m.bus());
				if (m.forgeHandle() != null) forgeHandles.add(m.forgeHandle());
			}

			// Capture the post-Bootstrap vanilla registry state for NEOFORGE only, before the window opens. NeoForge's
			// unfreeze clear-callback empties its blockstate→id map, and BlockCallbacks.onBake only re-adds blocks that
			// onAdd saw during the window (none of vanilla's) — so without a snapshot to restore from, the map stays
			// empty and the first clientbound block_update cannot encode ("Can't find id for Block{minecraft:lava}").
			// NOT MinecraftForge's: its vanillaSnapshot LOCKS the vanilla wrappers, and every later register in this
			// window then throws "Can not register to a locked registry" (gate-m1 RED).
			invokeGameDataOn(cl, "net.neoforged.neoforge.registries.GameData", "vanillaSnapshot");
			unfreeze(cl);
			// MOD buses only — buses.get(0) is the baseline, whose registries PassiveSeeder already registered at
			// seed time; posting there re-collects them and fill() dies on "Attempted duplicate registration".
			postNeoNewRegistryEvent(cl, buses.subList(1, buses.size()));
			int n = fireRegisterEvents(cl, buses);
			// Traditional-Forge baseline: construct ForgeMod + fire the 3-arg Forge RegisterEvent so ForgeMod's own
			// DeferredRegisters (e.g. the empty forge:fluid_type read by EntityFluidInteraction) register. The real
			// Forge mods' buses ride along: their DeferredRegisters flush off the same event stream, and it can only
			// be fired once NewRegistryEvent (inside) has created Forge's custom registries.
			KernelForgeBaseline.register(cl, forgeHandles);
			// Fabric mods' onInitialize() calls Registry.register(...) directly, so it belongs in this same unfrozen
			// span. It runs BEFORE the bake below so the bake sees Fabric-registered content.
			KernelFabricEcosystem.runMainEntrypoints();
			// Bake the ForgeRegistries. Note a DeferredRegister's RegistryObjects bind during their OWN registry's
			// RegisterEvent above (DeferredRegister$EventDispatcher calls updateReference right after each register),
			// not here — so a mod reading another mod's RegistryObject during RegisterEvent depends on the dispatch
			// order, not on this bake.
			invokeGameDataOn(cl, "net.minecraftforge.registries.GameData", "postRegisterEvents");
			// NeoForge's postRegisterEvents is NOT the bake — it is the dispatch loop the kernel REPLACES: it walks
			// getRegistrationOrder() and re-fires RegisterEvent through ModLoader.postEventWrapContainerInModOrder.
			// While ModList was empty that was a silent no-op, so calling it looked harmless. Once the kernel
			// publishes its mods (KernelModLoader.publishNeoModList) it double-fires every DeferredRegister —
			// "Adding duplicate key 'neoforge:condition_codecs / balm:config'" — and its own error path then calls
			// RegistryManager.revertToVanilla(), ROLLING BACK the NeoForge registries: 21 baseline entries
			// (attribute_type, ticket_type, slot_display, entity_sub_predicate_type, …) silently disappeared.
			// Only its tail is wanted, so call that directly.
			invokeStaticOn(cl, "net.neoforged.neoforge.common.CommonHooks", "modifyAttributes");
			linkBlockItems(cl);
			freeze(cl);
			rebuildNeoForgeBlockStateIds(cl);
			sortNeoCreativeTabs(cl);
			if (Boolean.getBoolean("forbric.tabProbe")) startCreativeTabProbe(cl);
			ForbricLog.info("[Forbric/Lifecycle] fired RegisterEvent x%d on %d bus(es) [NeoForge baseline + %d mod(s)] "
					+ "+ %d traditional-Forge mod bus(es) + baked Forge registries", n, buses.size(),
					buses.size() - 1, forgeHandles.size());
			logRegisteredContent(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not register ecosystem content", unwrap(t));
		}
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
		try {
			Class<?> registry = Class.forName("net.neoforged.neoforge.common.CreativeModeTabRegistry", false, cl);
			java.lang.reflect.Method sorted = registry.getMethod("getSortedCreativeModeTabs");
			java.lang.reflect.Method name = registry.getMethod("getName", Class.forName(
					"net.minecraft.world.item.CreativeModeTab", false, cl));

			// sortTabs() REPLACES SORTED_TABS but only APPENDS to DEFAULT_TABS — it re-adds the four special tabs
			// (hotbar/search/op/inventory) on every call and never clears. The special tabs' screen column is
			// indexOf % (size/2) + 5, so a duplicated list (size 8) yields columns 5..8 and the tab-sprite array
			// (length 7) overflows: ArrayIndexOutOfBoundsException: Index 7 in extractTabButton the moment the
			// creative screen renders. The baseline bring-up already sorted once, so OUR re-sort is always a second
			// call — clear the list first to make the call idempotent.
			java.lang.reflect.Field defaults = registry.getDeclaredField("DEFAULT_TABS");
			defaults.setAccessible(true);
			((java.util.List<?>) defaults.get(null)).clear();

			int before = ((java.util.List<?>) sorted.invoke(null)).size();
			registry.getMethod("sortTabs").invoke(null);

			java.util.List<?> after = (java.util.List<?>) sorted.invoke(null);
			StringBuilder names = new StringBuilder();
			for (Object tab : after) {
				if (names.length() > 0) names.append(", ");
				names.append(name.invoke(null, tab));
			}
			ForbricLog.info("[Forbric/Lifecycle] re-sorted NeoForge creative tabs %d -> %d (special tabs: %d, must "
					+ "stay 4): [%s] (the creative screen's tab strip reads ONLY this list; the baseline sort "
					+ "predates the kernel's registration window, so window-registered tabs were searchable but "
					+ "had no tab)", before, after.size(), ((java.util.List<?>) defaults.get(null)).size(), names);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not re-sort NeoForge creative tabs "
					+ "(mod creative tabs may be missing from the tab strip)", unwrap(t));
		}
	}

	/**
	 * {@code -Dforbric.tabProbe} — dumps every non-vanilla creative tab's live state every 3s.
	 *
	 * <p>Pure diagnostic for the "tab registered + sorted + searchable, but the strip does not draw it" class of
	 * bug: the strip's render-time predicate is {@code shouldDisplay()} = {@code hasAnyItems()} for CATEGORY tabs,
	 * so this reports exactly the fields that predicate reads, straight from the live objects.
	 */
	private static void startCreativeTabProbe(ClassLoader cl) {
		Thread probe = new Thread(() -> {
			try {
				Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
				Class<?> tabCls = Class.forName("net.minecraft.world.item.CreativeModeTab", false, cl);
				Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
				Object tabRegistry = builtin.getField("CREATIVE_MODE_TAB").get(null);
				java.lang.reflect.Method getKey = registryCls.getMethod("getKey", Object.class);
				java.lang.reflect.Method shouldDisplay = tabCls.getMethod("shouldDisplay");
				java.lang.reflect.Method getType = tabCls.getMethod("getType");
				java.lang.reflect.Field display = tabCls.getDeclaredField("displayItems");
				java.lang.reflect.Field search = tabCls.getDeclaredField("displayItemsSearchTab");
				display.setAccessible(true);
				search.setAccessible(true);
				Class<?> sortReg = Class.forName("net.neoforged.neoforge.common.CreativeModeTabRegistry", false, cl);
				java.lang.reflect.Method sorted = sortReg.getMethod("getSortedCreativeModeTabs");

				while (true) {
					for (Object tab : (Iterable<?>) tabRegistry) {
						String key = String.valueOf(getKey.invoke(tabRegistry, tab));
						if (key.startsWith("minecraft:")) continue;

						java.util.Collection<?> d = (java.util.Collection<?>) display.get(tab);
						java.util.Collection<?> s = (java.util.Collection<?>) search.get(tab);
						boolean inSorted = ((java.util.List<?>) sorted.invoke(null)).contains(tab);
						ForbricLog.info("[Forbric/TabProbe] %s type=%s display=%d search=%d shouldDisplay=%s "
								+ "inSorted=%s identity=%08x", key, getType.invoke(tab),
								d == null ? -1 : d.size(), s == null ? -1 : s.size(),
								shouldDisplay.invoke(tab), inSorted, System.identityHashCode(tab));
					}
					Thread.sleep(3000);
				}
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/TabProbe] probe died", unwrap(t));
			}
		}, "forbric-tab-probe");
		probe.setDaemon(true);
		probe.start();
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
		try {
			Class<?> itemCls = Class.forName("net.minecraft.world.item.Item", false, cl);
			Class<?> blockItemCls = Class.forName("net.minecraft.world.item.BlockItem", false, cl);
			Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);

			@SuppressWarnings("unchecked")
			java.util.Map<Object, Object> byBlock =
					(java.util.Map<Object, Object>) itemCls.getField("BY_BLOCK").get(null);
			Object itemRegistry = builtin.getField("ITEM").get(null);
			java.lang.reflect.Method getBlock = blockItemCls.getMethod("getBlock");

			int linked = 0;
			for (Object item : (Iterable<?>) itemRegistry) {
				if (!blockItemCls.isInstance(item)) continue;

				Object block = getBlock.invoke(item);
				if (block != null && byBlock.putIfAbsent(block, item) == null) linked++;
			}
			if (linked > 0) {
				ForbricLog.info("[Forbric/Lifecycle] linked %d block->item mapping(s) that Forge's registry "
						+ "add-callback would have made (Block.asItem() returns AIR without them)", linked);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not link block->item mappings "
					+ "(modded blocks may have no item form)", unwrap(t));
		}
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
		try {
			Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
			Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Method keySet = registryCls.getMethod("keySet");

			// namespace -> registry -> count, skipping vanilla's own content (the overwhelming majority). Namespaces
			// come off the id's toString ("namespace:path") rather than a getNamespace() on a named ResourceLocation
			// class — the kernel must not pin a vanilla type name just to count things.
			Map<String, Map<String, Integer>> byNamespace = new java.util.TreeMap<>();
			for (Field f : builtin.getFields()) {
				if (!registryCls.isAssignableFrom(f.getType())) continue;
				Object registry = f.get(null);
				String regName = f.getName().toLowerCase(java.util.Locale.ROOT);
				for (Object id : (java.util.Set<?>) keySet.invoke(registry)) {
					String s = String.valueOf(id);
					int colon = s.indexOf(':');
					String ns = colon < 0 ? s : s.substring(0, colon);
					if ("minecraft".equals(ns)) continue;
					byNamespace.computeIfAbsent(ns, k -> new java.util.TreeMap<>())
							.merge(regName, 1, Integer::sum);
				}
			}

			if (byNamespace.isEmpty()) {
				ForbricLog.info("[Forbric/Lifecycle] registered content: none outside minecraft:");
				return;
			}
			for (Map.Entry<String, Map<String, Integer>> e : byNamespace.entrySet()) {
				int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
				ForbricLog.info("[Forbric/Lifecycle] registered content: %s: %d entr(ies) %s", e.getKey(), total,
						e.getValue());
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not summarise registered content", unwrap(t));
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
			Class<?> modContainer = Class.forName("net.neoforged.fml.ModContainer", false, cl);
			clientMod.getConstructor(iEventBus, modContainer).newInstance(baselineBus, baselineContainer);
			ForbricLog.info("[Forbric/Lifecycle] constructed ClientNeoForgeMod on the baseline bus");

			publishModBusDelivery(cl);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not register NeoForge client content — the client's mod-bus "
					+ "events (reload listeners, renderers) will not reach NeoForge", unwrap(t));
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
	 */
	private static void publishModBusDelivery(ClassLoader cl) throws Exception {
		Class<?> modListCls = Class.forName("net.neoforged.fml.ModList", false, cl);
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
			Field f = modListCls.getDeclaredField("indexedMods");
			f.setAccessible(true);
			f.set(modList, indexed);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Lifecycle] could not rebuild ModList.indexedMods: %s",
					String.valueOf(unwrap(t)));
		}

		// NeoForge's title-screen version-check overlay (NeoForgeVersionCheck.getStatus →
		// ModList.getModFileById("neoforge").getMods().get(0)) reads the `fileById` map, which our routing does not
		// otherwise touch. Seed it with the baseline's mod-file info so the main menu renders (getResult →
		// PENDING_CHECK, no network). Rebuild the map (it may be immutable) rather than mutate in place.
		try {
			Class<?> iModInfo = Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, cl);
			Object modInfo = modContainerClass(cl).getMethod("getModInfo").invoke(baselineContainer);
			Object fileInfo = iModInfo.getMethod("getOwningFile").invoke(modInfo);
			String modId = (String) iModInfo.getMethod("getModId").invoke(modInfo);
			if (fileInfo != null) {
				Field f = modListCls.getDeclaredField("fileById");
				f.setAccessible(true);
				@SuppressWarnings("unchecked")
				java.util.Map<String, Object> existing = (java.util.Map<String, Object>) f.get(modList);
				java.util.Map<String, Object> merged =
						new java.util.HashMap<>(existing == null ? java.util.Map.of() : existing);
				merged.put(modId, fileInfo);
				f.set(modList, merged);
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Lifecycle] could not seed ModList.fileById (title version-check may NPE): %s",
					String.valueOf(unwrap(t)));
		}
		ForbricLog.info("[Forbric/Lifecycle] NeoForge mod-bus delivery covers %d container(s) — baseline + every "
				+ "loaded mod (ModLoader.postEvent fans out over this list, and the Mods screen lists it)",
				containers.size());
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
	private static void fireModSetupLifecycle(ClassLoader cl, boolean client) {
		java.util.Map<String, KernelModLoader.NeoIdentity> mods = KernelModLoader.publishedNeoMods();
		if (mods.isEmpty()) return;

		fireSetupPhase(cl, mods, "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent", "common setup");
		if (client) {
			fireSetupPhase(cl, mods, "net.neoforged.fml.event.lifecycle.FMLClientSetupEvent", "client setup");
		}
		fireSetupPhase(cl, mods, "net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent", "load complete");
	}

	private static void fireSetupPhase(ClassLoader cl, java.util.Map<String, KernelModLoader.NeoIdentity> mods,
			String eventClassName, String label) {
		try {
			Class<?> eventClass = Class.forName(eventClassName, false, cl);
			Class<?> queueClass = Class.forName("net.neoforged.fml.DeferredWorkQueue", false, cl);
			Class<?> busClass = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Class<?> baseEvent = Class.forName("net.neoforged.bus.api.Event", false, cl);

			Object queue = queueClass.getConstructor(String.class).newInstance(label);
			java.lang.reflect.Constructor<?> ctor = eventClass.getConstructor(modContainerClass(cl), queueClass);
			Method post = busClass.getMethod("post", baseEvent);

			int fired = 0;
			for (java.util.Map.Entry<String, KernelModLoader.NeoIdentity> e : mods.entrySet()) {
				try {
					post.invoke(e.getValue().bus(), ctor.newInstance(e.getValue().container(), queue));
					fired++;
				} catch (Throwable perMod) {
					ForbricLog.warn("[Forbric/Lifecycle] " + e.getKey() + " failed during " + label,
							unwrap(perMod));
				}
			}
			queueClass.getMethod("runTasks").invoke(queue);
			ForbricLog.info("[Forbric/Lifecycle] posted FML %s to %d NeoForge mod(s)", label, fired);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", eventClassName, label);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not post FML " + label, unwrap(t));
		}
	}

	/** The game-side {@code net.neoforged.fml.ModContainer} class. */
	private static Class<?> modContainerClass(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.neoforged.fml.ModContainer", false, cl);
	}

	/** Fires RegisterEvent for every registry (vanilla BuiltInRegistries + NeoForgeRegistries) on each bus. */
	private static int fireRegisterEvents(ClassLoader cl, List<Object> buses) throws Exception {
		Class<?> registryCls = Class.forName("net.minecraft.core.Registry", false, cl);
		Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
		Class<?> eventCls = Class.forName("net.neoforged.bus.api.Event", false, cl);
		Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		Class<?> registerEventCls = Class.forName("net.neoforged.neoforge.registries.RegisterEvent", false, cl);

		Constructor<?> regEventCtor = registerEventCls.getDeclaredConstructor(resourceKeyCls, registryCls);
		regEventCtor.setAccessible(true);
		Method keyM = registryCls.getMethod("key");
		Method postM = busCls.getMethod("post", eventCls);

		List<Object> registries = new ArrayList<>();
		collectRegistryFields(cl, "net.minecraft.core.registries.BuiltInRegistries", registryCls, registries);
		collectRegistryFields(cl, "net.neoforged.neoforge.registries.NeoForgeRegistries", registryCls, registries);

		// REGISTRY-major / mod-minor, matching genuine NeoForge (RegistryManager walks registries, firing each mod's
		// bus per registry). A DeferredRegister's DeferredHolders resolve during their own registry's event, so a mod
		// registering minecraft:item must see every mod's blocks already registered — which only holds if all buses
		// fire for BLOCK before any fires for ITEM. (NeoForge content is explicitly namespaced by DeferredRegister, so
		// unlike the traditional-Forge twin there is no active-container to track here.)
		// One mod's listener must not take the window down with it. Genuine NeoForge wraps each container's dispatch
		// and collects the failure as a ModLoadingIssue, so the other mods AND the baseline still register; the
		// kernel let the exception propagate, so a single mod throwing (Mob Champions reading a not-yet-loaded
		// config) aborted registerNeoForgeContent entirely — the NeoForge baseline never registered its own content
		// and the client died much later, and misleadingly, on an unbound neoforge:fluid_type/water. Report each
		// once, then carry on.
		java.util.Set<Object> broken =
				java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Object registry : registries) {
			Object key = keyM.invoke(registry);
			for (Object bus : buses) {
				try {
					postM.invoke(bus, regEventCtor.newInstance(key, registry));
				} catch (Throwable t) {
					if (broken.add(bus)) {
						ForbricLog.warn("[Forbric/Lifecycle] a NeoForge mod's RegisterEvent listener failed on %s — "
								+ "that mod's remaining content is skipped, every other mod and the baseline still "
								+ "register", key, unwrap(t));
					}
				}
			}
		}
		return registries.size();
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
			Class<?> eventCls = Class.forName("net.neoforged.neoforge.registries.NewRegistryEvent", false, cl);
			Constructor<?> ctor = eventCls.getDeclaredConstructor();
			ctor.setAccessible(true);
			Object event = ctor.newInstance();

			Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
			Method post = busCls.getMethod("post", Class.forName("net.neoforged.bus.api.Event", false, cl));
			int delivered = 0;
			for (Object bus : buses) {
				try {
					post.invoke(bus, event);
					delivered++;
				} catch (Throwable perBus) {
					ForbricLog.warn("[Forbric/Lifecycle] a NeoForge mod's NewRegistryEvent listener failed — that "
							+ "mod's custom registries are skipped, the rest still register", unwrap(perBus));
				}
			}

			ForbricLog.info("[Forbric/Lifecycle] posted NewRegistryEvent to %d NeoForge mod bus(es) — mods create "
					+ "their own registries (and do their earliest mod-bus setup) before RegisterEvent", delivered);

			// Separate from the delivery above: mods have already been notified by this point, so a fill() failure
			// must not be reported as "could not post" — the earliest-phase setup mods hang off this event
			// (WhiteNoise's config load) has happened either way.
			try {
				Method fill = eventCls.getDeclaredMethod("fill");
				fill.setAccessible(true);
				fill.invoke(event);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/Lifecycle] NewRegistryEvent.fill failed — mod-declared custom registries "
						+ "may be missing (delivery to the mod buses itself succeeded)", unwrap(t));
			}
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no NewRegistryEvent type — skipping");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not post NeoForge NewRegistryEvent", unwrap(t));
		}
	}

	private static void collectRegistryFields(ClassLoader cl, String holder, Class<?> registryCls, List<Object> out)
			throws Exception {
		Class<?> c = Class.forName(holder, true, cl);
		for (Field f : c.getFields()) {
			if (registryCls.isAssignableFrom(f.getType())) {
				Object reg = f.get(null);
				if (reg != null && !out.contains(reg)) out.add(reg);
			}
		}
	}

	private static void unfreeze(ClassLoader cl) {
		invokeGameData(cl, "unfreezeData");
	}

	private static void freeze(ClassLoader cl) {
		invokeGameData(cl, "freezeData");
	}

	/**
	 * BOTH ecosystems ship their own {@code GameData} (same API, different registry bookkeeping) and the kernel's
	 * registration window must drive both — driving only MinecraftForge's left NeoForge's registry callbacks unfired,
	 * so {@code NeoForgeRegistryCallbacks$BlockCallbacks.onBake} never rebuilt its blockstate→id map and the first
	 * {@code clientbound/minecraft:block_update} failed to encode ("Can't find id for Block{minecraft:lava}").
	 */
	private static final String[] GAME_DATA_CLASSES = {
		"net.minecraftforge.registries.GameData",
		"net.neoforged.neoforge.registries.GameData",
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

	/** One ecosystem's {@code GameData} only — for steps whose semantics differ between the two families. */
	private static void invokeGameDataOn(ClassLoader cl, String className, String method) {
		try {
			Class.forName(className, false, cl).getMethod(method).invoke(null);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] %s absent — skipping %s", className, method);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Lifecycle] %s.%s unavailable: %s", className, method, String.valueOf(unwrap(t)));
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
		KernelClientPacks.addTo(packRepository, cl, jars);
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

	public static void onClientEntrypoints() {
		try {
			KernelFabricEcosystem.runClientEntrypoints();
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] client entrypoints failed", unwrap(t));
		}
	}
}
