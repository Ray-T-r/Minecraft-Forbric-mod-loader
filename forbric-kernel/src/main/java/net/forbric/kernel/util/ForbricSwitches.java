/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one place a {@code -Dforbric.*} switch is looked up under a name it used to have.
 *
 * <p>A switch is part of the launch arguments a player or a pack already carries — copied from an issue reply, kept
 * in a launcher profile. When a switch is renamed, a reader that asks {@link System#getProperty} for the new name
 * alone ignores the old flag without a word: the repair the player turned off comes back on, and nothing in the log
 * says why. A switch whose mechanism is gone is the same trap seen from the other side: the flag still looks like it
 * does something.
 *
 * <p>So every renamed switch keeps its old name in {@link #RENAMED}, and the readers of the new name ask {@link #get}
 * instead of {@link System#getProperty}. When only the old name is set its value is used and a warning names the
 * switch to use instead; when both are set the current name wins and the warning says the old one was ignored. A
 * switch in {@link #RETIRED} has no reader at all, so {@link #announce} — called once as the kernel boots — is what
 * warns that it has no effect (and warns about any renamed one on the command line, whether or not its reader runs
 * in this session). Each old name warns once per run, whichever of the two reaches it first.
 */
public final class ForbricSwitches {
	/** Old name → the switch that now controls the same mechanism. */
	static final Map<String, String> RENAMED = Map.ofEntries(
			// Stage 4: with MinecraftForge's registry wrappers gone the alias-target warning's only repair is NeoForge's
			// alias-first order, which the outer switch already gated; one switch is left for it.
			Map.entry("forbric.aliasPresenceParity.deferredRegister", "forbric.aliasPresenceParity"),
			Map.entry("forbric.barrelRollCamera", "forbric.cameraRollCallbacks"),
			Map.entry("forbric.blockInfoCaches", "forbric.registryElementCallbacks"),
			Map.entry("forbric.c2meBlockUpdates", "forbric.chunkStatusRetarget"),
			Map.entry("forbric.carpetMixins", "forbric.playerWorldCallbacks"),
			Map.entry("forbric.compatPluginPlatforms", "forbric.mixinPluginPlatforms"),
			Map.entry("forbric.continuitySpriteSources", "forbric.spriteLoaderCallbacks"),
			Map.entry("forbric.corpseNameTag", "forbric.zeroNameTagMigration"),
			Map.entry("forbric.createContextualBlocks", "forbric.blockQueryAdapters"),
			Map.entry("forbric.createEntitySounds", "forbric.entitySoundCallbacks"),
			Map.entry("forbric.createHudMixin", "forbric.hudContextCallbacks"),
			Map.entry("forbric.createInjectionAdapters", "forbric.carrierCallbackAdapters"),
			Map.entry("forbric.createInteractionMixins", "forbric.blockInteractionAdapters"),
			Map.entry("forbric.createKeyboardMixin", "forbric.keyActionCallbacks"),
			Map.entry("forbric.createStructureMixin", "forbric.structurePlacementCallbacks"),
			// Both kept a config plugin's platform provider from defining game classes during Mixin's preparation; the
			// general mechanism that replaced them is the loader's.
			Map.entry("forbric.earlyGameDirectory", "forbric.deferLinkTimeTypes"),
			Map.entry("forbric.irisEarlyGamePath", "forbric.deferLinkTimeTypes"),
			// Gave a Fabric mod's non-drowning fluid vanilla's refill in the breathing hook; every fluid a body breathes in gets it now.
			Map.entry("forbric.foreignFluidBreathing", "forbric.vanillaAirRefill"),
			Map.entry("forbric.portingLayerAbi", "forbric.configApiAbi"),
			Map.entry("forbric.sodiumConfigUsers", "forbric.crossEcosystemDeclarations"));

	/** Retired name → why setting it changes nothing. */
	static final Map<String, String> RETIRED = Map.ofEntries(
			Map.entry("forbric.neoRegistrationOrder", "RegisterEvent is posted by NeoForge's own GameData.postRegisterEvents, which "
					+ "walks NeoForge's registration order itself; the kernel has no order of its own left to switch off"),
			Map.entry("forbric.dragonParts", "the multipart entity hierarchy is the base's own (NeoForge's PartEntity), "
					+ "so there is no runtime rewrite left to switch off"),
			Map.entry("forbric.fluidInteractionCallbacks", "a fluid callback on vanilla's spread decision is no longer moved onto the carriers' "
					+ "registry calls; it runs on NeoForge's neighbour change from a wrap of the registry question, as vanilla asks it there "
					+ "(-Dforbric.fluidReactionNeighbourChange.head)"),
			Map.entry("forbric.createFluidMixins", "a fluid callback on vanilla's spread decision is no longer moved onto the carriers' "
					+ "registry calls; it runs on NeoForge's neighbour change from a wrap of the registry question, as vanilla asks it there "
					+ "(-Dforbric.fluidReactionNeighbourChange.head)"),
			Map.entry("forbric.hudBridge", "fabric-api's HUD elements are no longer redrawn from NeoForge's layers: fabric-rendering-v1's "
					+ "own HudMixin dispatches every root, each wrap landing in the body the kernel gives the method reference"),
			Map.entry("forbric.fabricPlayChannels", "the kernel no longer records a client's Fabric PLAY channels itself: Fabric's own "
					+ "c:register receiver runs on the payload as Fabric reads it and records them (-Dforbric.commonNetworkInterop)"),
			Map.entry("forbric.fabricFluidFlow", "a fluid callback on vanilla's spread decision is no longer moved onto the carriers' "
					+ "registry calls; it runs on NeoForge's neighbour change from a wrap of the registry question, as vanilla asks it there "
					+ "(-Dforbric.fluidReactionNeighbourChange.head)"),
			Map.entry("forbric.breathingCallbacks", "a mod's wrap of vanilla's breathing checks is no longer served by a kernel callback scope: "
					+ "the native checks are asked again ahead of the carrier's breathing hook (-Dforbric.nativeDecisionIsland)"),
			Map.entry("forbric.createBreathingMixin", "a mod's wrap of vanilla's breathing checks is no longer served by a kernel callback scope: "
					+ "the native checks are asked again ahead of the carrier's breathing hook (-Dforbric.nativeDecisionIsland)"),
			Map.entry("forbric.balmEffectVeto", "a clear-all veto is no longer re-hosted on the carrier's per-effect question: the native clear is put back on a view of "
					+ "what the carrier lets go and the mod's own wrap runs on it (-Dforbric.scopedClearRelay)"),
			Map.entry("forbric.effectClearVeto", "a clear-all veto is no longer re-hosted on the carrier's per-effect question: the native clear is put back on a view of "
					+ "what the carrier lets go and the mod's own wrap runs on it (-Dforbric.scopedClearRelay)"),
			Map.entry("forbric.fabricElytraTickAnchor", "a mod's cancellable injector behind a guard the carrier added is moved ahead of that guard by structure, not by name "
					+ "(-Dforbric.carrierGuardedPoints)"),
			Map.entry("forbric.fabricSoundContracts", "fabric-api's sound stream redirect is no longer neutralised and replaced by a rewrite of NeoForge's "
					+ "default: it moves onto the carrier's SoundInstance.getStream and runs that default with fabric-api's own handler wherever a "
					+ "sound inherits it (-Dforbric.carrierDefaultChains)"),
			Map.entry("forbric.fabricServerLanguage", "a mod's injector on a vanilla decision the carrier replaced is moved onto the carrier's decision by structure, not "
					+ "by name (-Dforbric.carrierDecisions)"),
			Map.entry("forbric.fabricBlockBreak", "fabric-api's AFTER break handler is no longer wrapped by name: an injector at a vanilla call the carrier makes "
					+ "in a helper that answers whether it made it runs on that answer, by structure (-Dforbric.carrierHelperResults)"),
			Map.entry("forbric.fabricEntityAnchors", "fabric-api's bed occupancy redirect is no longer replaced by a rewrite of its handler: it moves onto the state's "
					+ "setBedOccupied and runs NeoForge's inherited default with fabric-api's own handler wherever a bed inherits it "
					+ "(-Dforbric.carrierDefaultChains); a NeoForge bed that overrides the setter keeps its own and no longer asks Fabric's event"),
			// Stage 4: the NeoForge-to-MinecraftForge event forward pass, its ledger and its audits are gone with MinecraftForge.
			Map.entry("forbric.unifiedEvents", "Forbric loads no MinecraftForge mod, so no NeoForge event is forwarded to MinecraftForge's "
					+ "buses any more; the kernel's own server duties never depended on it"),
			Map.entry("forbric.deadEventAudit", "Forbric loads no MinecraftForge mod, so there are no MinecraftForge listeners left to audit"),
			Map.entry("forbric.eventChainAudit", "Forbric loads no MinecraftForge mod, so there is no second event bus to pair posts across"),
			Map.entry("forbric.forgeBreakExperience", "Forbric loads no MinecraftForge mod, so nothing decides a broken block's experience "
					+ "for MinecraftForge; the Fabric experience relay is -Dforbric.uncalledValueRelay"),
			Map.entry("forbric.forgeInternalSubscribers", "Forbric loads no MinecraftForge runtime, so it has no internal subscribers to wire"),
			Map.entry("forbric.portalSpawn", "a nether portal asks NeoForge's own portal hook, as on NeoForge; there is no MinecraftForge "
					+ "half to compose with it"),
			Map.entry("forbric.clientHookMixinAnchors", "NeoForge's own initClientHooks and onRegisterParticleProviders calls run in "
					+ "Minecraft's constructor, so a mod's mixin on them binds there with no relay to move it onto"),
			Map.entry("forbric.forgeReloadListeners", "a datapack reload posts NeoForge's own reload-listener event alone (EventHooks.onResourceReload), as on "
					+ "NeoForge; there is no MinecraftForge AddReloadListenerEvent to post beside it"),
			// Stage 4: MinecraftForge's registry wrappers are gone with its carrier, and every rewrite of them with them.
			Map.entry("forbric.forgeWrapperSync", "Forbric loads no MinecraftForge runtime, so there are no MinecraftForge registry wrappers to stage "
					+ "the server's ids into; NeoForge's own snapshot applies them"),
			Map.entry("forbric.publicRegistryWrappers", "Forbric loads no MinecraftForge runtime, so there are no MinecraftForge registry wrappers to make "
					+ "public"),
			Map.entry("forbric.soundRegistryIdentity", "Forbric loads no MinecraftForge runtime, so there is no MinecraftForge sound registry wrapper to "
					+ "give an identity map"),
			Map.entry("forbric.wrapperEntryEvents", "Forbric loads no MinecraftForge runtime, so no registry wrapper bypasses fabric-registry-sync's "
					+ "entry-added event"),
			Map.entry("forbric.blockItemLinks", "Forbric loads no MinecraftForge runtime, so there is no MinecraftForge item add-callback to point at "
					+ "Item.BY_BLOCK"),
			Map.entry("forbric.kernel.registryRedirect", "Forbric loads no MinecraftForge runtime, so there is no MinecraftForge registry wrapper lookup to "
					+ "redirect"),
			Map.entry("forbric.registryAliasParity", "Forbric loads no MinecraftForge runtime, so no registry wrapper overrides the lookups "
					+ "fabric-registry-sync resolves aliases in"),
			// Stage 4: MinecraftForge's capability composition, its enum and token plugins and its transfer audit are gone with its carrier.
			Map.entry("forbric.transferShapeDump", "Forbric loads no MinecraftForge runtime, so the MinecraftForge transfer audit that dumped the shapes "
					+ "it refused is gone"),
			// Stage 4: MinecraftForge's fluid and particle repairs are gone with its carrier.
			Map.entry("forbric.fluidInteractions", "Forbric loads no MinecraftForge runtime, so there is no MinecraftForge fluid-interaction registry to "
					+ "ask beside NeoForge's"),
			Map.entry("forbric.forgeParticleGroups", "Forbric loads no MinecraftForge runtime, so there are no MinecraftForge particle groups to hand "
					+ "NeoForge's particle engine"),
			Map.entry("forbric.liquidBlockFluid", "Forbric loads no MinecraftForge runtime, so no liquid block keeps its fluid in a MinecraftForge "
					+ "supplier"),
			// Stage 4: the MinecraftForge spawner and finalization hooks and its field getters are gone with its carrier.
			Map.entry("forbric.spawnerFinalize", "Forbric loads no MinecraftForge mod, so no spawner needs MinecraftForge's ValueInput; a spawner's "
					+ "mob is finalized by NeoForge's own hook, as on NeoForge"),
			Map.entry("forbric.forgeFieldGetters", "Forbric loads no MinecraftForge runtime, so no liquid block or bucket keeps its fluid in a "
					+ "MinecraftForge supplier"),
			// Stage 4: MinecraftForge's client repairs are gone with its carrier.
			Map.entry("forbric.forgePartTracking", "Forbric loads no MinecraftForge mod, so no multipart entity needs MinecraftForge's part tracking; "
					+ "NeoForge's parts are tracked as on NeoForge"),
			Map.entry("forbric.forgeModelData", "Forbric loads no MinecraftForge mod, so no block model asks MinecraftForge's model data; the client "
					+ "level keeps NeoForge's own"),
			Map.entry("forbric.forgeModelDataEdge", "Forbric loads no MinecraftForge mod, so no injector needs an edge twin of MinecraftForge's "
					+ "model-data overload"),
			// Stage 4: MinecraftForge's server-side and loader-side repairs are gone with its carrier.
			Map.entry("forbric.clearableLazyRace", "Forbric loads no MinecraftForge runtime, so there is no MinecraftForge ClearableLazy whose lock "
					+ "hands a waiting thread null"),
			Map.entry("forbric.forgeAttributeValidation", "Forbric loads no MinecraftForge mod, so no freeze waits for MinecraftForge's held attribute events"),
			Map.entry("forbric.forgeBrewingRecipes", "Forbric loads no MinecraftForge mod, so no MinecraftForge brewing recipe needs wrapping as "
					+ "NeoForge's"),
			Map.entry("forbric.forgeDamageSeams", "Forbric loads no MinecraftForge mod, so there are no MinecraftForge Hurt, Damage or player-Attack "
					+ "events to post; vanilla's damage read stays (-Dforbric.vanillaDamageRead)"),
			Map.entry("forbric.forgeLauncherInfo", "Forbric loads no MinecraftForge runtime, so there is no MinecraftForge FMLLoader whose launcher "
					+ "methods need answering"),
			// Stage 4: MinecraftForge's fluid types, entity fluids and fluid models are gone with its carrier.
			Map.entry("forbric.forgeEntityFluids", "Forbric loads no MinecraftForge mod, so no entity's fluid walk needs MinecraftForge's half; NeoForge's "
					+ "own EntityFluidInteraction runs as on NeoForge"),
			Map.entry("forbric.forgeFluidOwnType", "Forbric loads no MinecraftForge mod, so no fluid has a MinecraftForge type of its own to show NeoForge; a "
					+ "fluid NeoForge cannot type gets the type its tags imply"),
			Map.entry("forbric.forgeFluidModels", "Forbric loads no MinecraftForge mod, so no fluid chooses its model and tint through MinecraftForge's "
					+ "client extensions"),
			// Stage 4: MinecraftForge's client consumers, creative tabs and HUD overlay tree are gone with its carrier.
			Map.entry("forbric.forgeClientConsumers", "Forbric loads no MinecraftForge mod, so NeoForge's layer definitions, tooltip components, preset editors "
					+ "and tint caches are filled by NeoForge's own hooks alone"),
			Map.entry("forbric.forgeCreativeTabs", "Forbric loads no MinecraftForge mod, so a creative tab's contents are built by NeoForge's own event alone"),
			Map.entry("forbric.forgeOverlayLayers", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge HUD overlay tree to lay over "
					+ "NeoForge's GUI layers"),
			// Stage 4: MinecraftForge's resource conditions and ingredient types are gone with its carrier.
			Map.entry("forbric.forgeConditions", "Forbric loads no MinecraftForge mod, so no MinecraftForge condition is decoded; a forge:condition key is "
					+ "data NeoForge does not read, as on NeoForge"),
			Map.entry("forbric.forgeConditionFunnel", "Forbric loads no MinecraftForge mod, so NeoForge's ConditionalOps funnel judges NeoForge's conditions "
					+ "alone, as on NeoForge"),
			Map.entry("forbric.forgeConditionContext", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge condition context to hand a reload"),
			Map.entry("forbric.forgePoolConditions", "Forbric loads no MinecraftForge mod, so no loot pool is judged by MinecraftForge's conditions"),
			Map.entry("forbric.forgeIngredients", "Forbric loads no MinecraftForge mod, so NeoForge's ingredient codec decodes NeoForge's and vanilla's "
					+ "ingredient types alone, as on NeoForge"),
			// Stage 4: MinecraftForge's capabilities, spawn placements and worldgen modifiers are gone with its carrier.
			Map.entry("forbric.forgeCapabilities", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge capability provider to compose onto "
					+ "entities, levels or block entities"),
			Map.entry("forbric.forgeSpawnPlacements", "Forbric loads no MinecraftForge mod, so spawn placements are registered by NeoForge's own event alone"),
			Map.entry("forbric.forgeWorldgen", "Forbric loads no MinecraftForge mod, so there are no forge:biome_modifier or forge:structure_modifier "
					+ "registries to declare; NeoForge's own modifiers run in its single pass"),
			Map.entry("forbric.forgeWorldgen.lenient", "Forbric loads no MinecraftForge mod, so no MinecraftForge worldgen modifier is decoded, leniently or not"),
			Map.entry("forbric.worldgenAuditNormalise", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge worldgen round-trip audit to normalise"),
			// Stage 4: MinecraftForge's picture-in-picture registration event is gone with its carrier.
			Map.entry("forbric.forgePipRenderers", "Forbric loads no MinecraftForge mod, so no picture-in-picture renderer is registered through "
					+ "MinecraftForge's event; vanilla's plain map holds the constructor list's renderers"),
			// Stage 4: MinecraftForge's mod construction, loading state, registry gates and pre-Mixin seeds are gone with its carrier.
			Map.entry("forbric.forgeClientInit", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge loading state to open for "
					+ "event delivery; NeoForge's client init runs as on NeoForge"),
			Map.entry("forbric.forgeLoadingStates", "Forbric loads no MinecraftForge mod, so there are no MinecraftForge gather states to record as "
					+ "completed"),
			Map.entry("forbric.forgeCtorGameInstance", "Forbric loads no MinecraftForge mod, so no mod constructor waits for the Minecraft.<init> window; "
					+ "NeoForge's mods are constructed where NeoForge constructs them"),
			Map.entry("forbric.freezeNeoForgeFirst", "only NeoForge's GameData freezes the registries now, so there is no second family's freeze to "
					+ "order it against"),
			Map.entry("forbric.forgeScanData", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge annotation index to fill; NeoForge's "
					+ "has its own switch (-Dforbric.neoScanData)"),
			Map.entry("forbric.forgeDatapackRegistries", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge datapack-registry "
					+ "declaration event to fire; NeoForge's own declares its registries"),
			Map.entry("forbric.forgeHandshake", "Forbric loads no MinecraftForge mod, so there is no MinecraftForge login handshake, channel list "
					+ "or config sync to restore; NeoForge's own configuration tasks run as on NeoForge"),
			// Stage 4: repairs of NeoForge hooks the merged base had lost; NeoForge's own base calls them itself.
			Map.entry("forbric.neoScreenEvents", "NeoForge's own Gui.setScreen posts ScreenEvent.Opening and Closing itself, so the kernel "
					+ "has nothing to add"),
			Map.entry("forbric.neoConversionPost", "NeoForge's own conversion lambdas post LivingConversionEvent.Post themselves, so there "
					+ "is no MinecraftForge lambda to send through the kernel"),
			Map.entry("forbric.itemTooltipBridge", "NeoForge's own ItemStack.getTooltipLines posts ItemTooltipEvent itself, so the kernel "
					+ "posts no second one beside a MinecraftForge call"),
			// Stage 4: repairs of merged-base damage that NeoForge's own base does not have.
			Map.entry("forbric.mipmapLowering", "NeoForge's own SpriteLoader lowers an atlas's mip level exactly as vanilla's does, with "
					+ "no setting gating it, so there is no allocation left to bound"),
			Map.entry("forbric.keyModifierSuffix", "NeoForge's own Options.processOptions splits a binding's \":MODIFIER\" off before "
					+ "it asks InputConstants.getKey for the key, so no key name with a suffix reaches the parse it guarded"),
			Map.entry("forbric.lootPoolFields", "NeoForge's own LootPool has one name field and one codec that reads only its own fields, "
					+ "so there is no second family's field for a constructor to fill"),
			Map.entry("forbric.nativeEntryRelay", "NeoForge's own base still asks a native decision method where vanilla does (LiquidBlock.onPlace "
					+ "asks shouldSpreadLiquid), so nothing is asked again; a HEAD handler on shouldSpreadLiquid runs on a neighbour change "
					+ "from MixinFluidReactionAdapter's wrap instead (-Dforbric.fluidReactionNeighbourChange.head)"),
			Map.entry("forbric.builderStubRebind", "NeoForge's own base keeps vanilla's bodies where MinecraftForge left a builder stub "
					+ "(UnbakedCuboidGeometry.bake), so an injector binds the body it was written for and nothing moves"),
			Map.entry("forbric.mixinMergedTwins", "NeoForge's own base has one class per name, with no renamed $forbricneo twin "
					+ "beside it, so a mixin applies to the class it names and gets no second target"),
			Map.entry("forbric.pruneDuplicateLambdas", "NeoForge's own base carries no lambda name twice, so there is no orphaned "
					+ "body to drop before a mod's mixin selects a lambda by name"),
			Map.entry("forbric.clientPartTracking", "NeoForge's own ClientLevel tracking callback reads NeoForge's getParts() "
					+ "itself, so a NeoForge mod's multipart entity is tracked with no MinecraftForge body to repair"),
			Map.entry("forbric.creativeSearchTrees", "NeoForge's own SessionSearchTrees has no MinecraftForge getSearchTree "
					+ "reader, so there is no hanging future to route to NeoForge's trees"),
			Map.entry("forbric.furnaceTickCalls", "NeoForge's own furnace tick calls its own static canBurn, consumeFuel and "
					+ "burn, so there is no MinecraftForge instance method called as static to rebind"),
			Map.entry("forbric.mergedRecordDefaults", "NeoForge's own ServerStatus has no MinecraftForge forgeData component, and "
					+ "every constructor of every record on its base sets each Optional component, so there is nothing to "
					+ "default"),
			Map.entry("forbric.neoDataMapFallback", "NeoForge's own reload path loads and applies its data maps on every server, "
					+ "dedicated or integrated, so the kernel has no about-to-start load of its own to switch"),
			Map.entry("forbric.clientResourcePreload", "NeoForge runs its client setup before the first resource reload and "
					+ "Forbric loads no MinecraftForge mod, which ran its mod loading inside that reload, so the client "
					+ "resource manager is left as NeoForge leaves it"),
			Map.entry("forbric.blockStateIdRefill", "NeoForge's own block bake callback, which every registry freeze runs, maps "
					+ "the state of every registered block, so the kernel no longer rebuilds the blockstate→id map after it"));

	private static final ForbricSwitches SYSTEM =
			new ForbricSwitches(RENAMED, RETIRED, System::getProperty, ForbricLog::warn);

	/** Current name → its old names, in a fixed order so two set old names always resolve the same way. */
	private final Map<String, List<String>> formerNames;
	private final Map<String, String> renamed;
	private final Map<String, String> retired;
	private final UnaryOperator<String> properties;
	private final Consumer<String> warn;
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	ForbricSwitches(Map<String, String> renamed, Map<String, String> retired, UnaryOperator<String> properties,
			Consumer<String> warn) {
		Map<String, List<String>> former = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : new TreeMap<>(renamed).entrySet()) {
			String old = entry.getKey(), current = entry.getValue();
			// A chain would point the warning at a name that is itself deprecated; a retired name cannot also live on.
			if (old.equals(current) || renamed.containsKey(current) || retired.containsKey(current) || retired.containsKey(old)) {
				throw new IllegalArgumentException("switch alias " + old + " -> " + current + " does not end at a live switch");
			}
			former.computeIfAbsent(current, k -> new ArrayList<>()).add(old);
		}
		former.replaceAll((current, olds) -> List.copyOf(olds));
		this.formerNames = Collections.unmodifiableMap(former);
		this.renamed = Map.copyOf(renamed);
		this.retired = Map.copyOf(retired);
		this.properties = properties;
		this.warn = warn;
	}

	/** The value of switch {@code name}, read under its current name or else under a name it used to have. */
	public static String get(String name, String fallback) {
		return SYSTEM.lookup(name, fallback);
	}

	/** {@link #get(String, String)} with no fallback: null when the switch is set under none of its names. */
	public static String get(String name) {
		return SYSTEM.lookup(name, null);
	}

	/** Warns about every renamed or retired switch on the command line. Called once, as the kernel boots. */
	public static void announce() {
		SYSTEM.announceSet();
	}

	/** Which spelling of a switch applies, and its value. */
	private record Setting(String name, String value) { }

	/** The setting that applies to {@code current}: the current name when set, else the first set old name. */
	private Setting applied(String current) {
		String value = properties.apply(current);
		if (value != null) return new Setting(current, value);
		for (String old : formerNames.getOrDefault(current, List.of())) {
			value = properties.apply(old);
			if (value != null) return new Setting(old, value);
		}
		return null;
	}

	String lookup(String name, String fallback) {
		List<String> olds = formerNames.get(name);
		if (olds == null) {
			String value = properties.apply(name);
			return value != null ? value : fallback;
		}
		Setting applied = applied(name);
		for (String old : olds) {
			String value = properties.apply(old);
			if (value != null) deprecated(old, name, value, applied);
		}
		return applied != null ? applied.value() : fallback;
	}

	void announceSet() {
		for (Map.Entry<String, String> entry : new TreeMap<>(renamed).entrySet()) {
			String value = properties.apply(entry.getKey());
			if (value != null) deprecated(entry.getKey(), entry.getValue(), value, applied(entry.getValue()));
		}
		for (Map.Entry<String, String> entry : new TreeMap<>(retired).entrySet()) {
			String value = properties.apply(entry.getKey());
			if (value != null && warned.add(entry.getKey())) {
				warn.accept(String.format("[Forbric/Switches] -D%s=%s has no effect: %s. Remove it from the launch "
						+ "arguments.", entry.getKey(), value, entry.getValue()));
			}
		}
	}

	private void deprecated(String old, String current, String value, Setting applied) {
		if (!warned.add(old)) return;
		if (applied.name().equals(old)) {
			warn.accept(String.format("[Forbric/Switches] -D%s is a renamed switch; its value (%s) is applied as -D%s. "
					+ "Use -D%s=%s in the launch arguments instead.", old, value, current, current, value));
		} else if (applied.name().equals(current)) {
			warn.accept(String.format("[Forbric/Switches] -D%s=%s is a renamed switch and is ignored: -D%s=%s is also "
					+ "set and takes precedence. Remove -D%s from the launch arguments.", old, value, current,
					applied.value(), old));
		} else {
			warn.accept(String.format("[Forbric/Switches] -D%s=%s is a renamed switch and is ignored: -D%s=%s, another "
					+ "old name of -D%s, is applied instead. Use -D%s alone in the launch arguments.", old, value,
					applied.name(), applied.value(), current, current));
		}
	}
}
