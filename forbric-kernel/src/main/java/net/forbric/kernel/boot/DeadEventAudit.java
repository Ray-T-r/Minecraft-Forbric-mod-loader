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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names the MinecraftForge game events that are still DEAD on the merged base, and which mod is listening for
 * one.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Every failure in this class of bug looks the same from the outside: the mod loads, reports nothing wrong,
 * registers its listener, and the listener never runs. There is no exception, no warning, and nothing in any log
 * connects the missing behaviour to the missing hook. The kernel has paid for that shape repeatedly — a Forge
 * mod's commands not existing, its key bindings doing nothing, its protection rules silently not applying — and
 * each time the diagnosis came from reading the merged base's bytecode by hand, months later.
 *
 * <p>So the inventory is turned around: instead of only recording what IS bridged, this records what is known to
 * be dead and not yet bridged, and says so once per boot, naming the event. It is a diagnosis printed before the
 * symptom rather than after it.
 *
 * <h2>How the list was established, and how to re-establish it</h2>
 *
 * <p>Each entry is a call site read out of the merged jar with {@code javap -p -c}, counting which family's hook
 * survived at it. For example {@code ServerPlayerGameMode} contains
 * {@code net/neoforged/neoforge/common/CommonHooks.fireBlockBreak}, {@code .onRightClickBlock},
 * {@code .onLeftClickBlock} and {@code .onItemRightClick} — and ZERO references to any
 * {@code net/minecraftforge/} hook. {@code ServerGamePacketListenerImpl} has NeoForge's
 * {@code getServerChatSubmittedDecorator} and no MinecraftForge chat hook; {@code Level} has NeoForge's
 * {@code onNeighborNotify}; {@code LivingEntity} has NeoForge's {@code onLivingFall}.
 *
 * <p>It is DATA, deliberately, not a scan: scanning a 30MB merged jar for hook references on every boot would
 * cost more than it is worth, and the answer only changes when the merged base is rebuilt. When it is rebuilt,
 * re-run the survey and update this map — the audit itself will not notice a base that grew a hook back, it will
 * only over-report, which is the safe direction.
 *
 * <p>Anything the kernel does bridge is subtracted live from {@link EventBridges#installed()}, so an entry does
 * not have to be deleted here the day a bridge for it lands.
 */
public final class DeadEventAudit {
	/** {@code -Dforbric.deadEventAudit=off} silences it. The gap does not go away; only the line does. */
	static final String PROPERTY = "forbric.deadEventAudit";

	/**
	 * MinecraftForge event class (internal name) → what a player loses, for events the merged base no longer
	 * posts and the kernel does not bridge. See the class javadoc for how each was established.
	 */
	/**
	 * Package-private so {@link HookCallSiteCensus} can be pointed at it: the rows are hand-read {@code javap}
	 * findings, and until something re-derived them from the bytecode they were claims nobody could check.
	 */
	static final Map<String, String> DEAD = deadEvents();

	private static Map<String, String> deadEvents() {
		Map<String, String> dead = new LinkedHashMap<>();
		// ServerPlayerGameMode: four NeoForge hooks, zero MinecraftForge ones.
		dead.put("net/minecraftforge/event/level/BlockEvent$BreakEvent",
				"block breaking is neither observed nor preventable — claim and protection mods do not protect, "
						+ "and block-logging mods record nothing");
		dead.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickBlock",
				"right-clicking a block is neither observed nor preventable");
		dead.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickBlock",
				"left-clicking a block is neither observed nor preventable");
		dead.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickItem",
				"using an item in hand is neither observed nor preventable");
		// ServerGamePacketListenerImpl carries NeoForge's chat decorator and no MinecraftForge chat hook.
		dead.put("net/minecraftforge/event/ServerChatEvent",
				"chat messages cannot be seen, edited or blocked — chat-formatting and moderation mods do nothing");
		// NeighborNotifyEvent and LivingFallEvent USED to be rows here, read off Level and LivingEntity by
		// hand. HookCallSiteCensus, run against the staged base, contradicts both:
		//
		//   ServerLevel#updateNeighborsAt            -> ForgeEventFactory.onNeighborNotify
		//   AbstractHorse#causeFallDamage            -> ForgeEventFactory.onLivingFall
		//   Llama#causeFallDamage                    -> ForgeEventFactory.onLivingFall
		//
		// The hand reading was not careless, it was PARTIAL: the call site is gone from the class that was
		// looked at and survives on another. But this table's claim is "the merged game never posts it", and
		// a mod subscribing to either one does receive events. Marking it DEGRADED on the Mods screen and in
		// load-report.txt is then a false accusation, which is worse than saying nothing — the reader who
		// checks one and finds it working stops believing the rest of the list.
		//
		// Partial deadness (live on one path, dead on the others) has no row shape here and is a real gap;
		// what it is not is this row.
		// ItemStack keeps MinecraftForge's onItemTooltip, so the dead one here is NeoForge's — recorded on the
		// other side of the ledger because the audit only walks MinecraftForge listeners today.
		// Client registration events that ForgeHooksClient.initClientHooks does NOT post and that no kernel bridge
		// carries yet. The merged consumers read only NeoForge's tables at these sites, so the Forge event would
		// be delivered into nothing; they are named here so a waiting mod is named at boot rather than found in
		// a screenshot. Each is a candidate adapter for a later round.
				dead.put("net/minecraftforge/client/event/AddFramePassEvent",
				"extra render frame passes a MinecraftForge mod adds never run — the merged frame graph asks only "
						+ "NeoForge's event");
		dead.put("net/minecraftforge/client/event/EntityRenderersEvent$CreateSkullModels",
				"custom skull block models a MinecraftForge mod registers never render — the merged skull renderer "
						+ "reads only NeoForge's map");
		dead.put("net/minecraftforge/client/event/EntityRenderersEvent$AddLayers",
				"render layers a MinecraftForge mod adds to existing entity renderers — armour, capes, overlays — "
						+ "never draw; the merged EntityRenderDispatcher posts only NeoForge's AddLayers");
		// The two fluid events the merged FlowingFluid/LavaFluid post only for NeoForge (EventHooks
		// .canCreateFluidSource at bc 110, fireFluidPlaceBlockEvent at three sites). Deliberately not bridged by the
		// fluid-rendering repair: that is event multiplexing, and it is named here instead so a listener is told.
		dead.put("net/minecraftforge/event/level/BlockEvent$CreateFluidSourceEvent",
				"infinite-source formation cannot be observed or vetoed — the merged FlowingFluid.canConvertToSource "
						+ "asks only NeoForge's EventHooks.canCreateFluidSource");
						// The other side of the ledger: the merged ItemStack.getTooltipLines calls only MinecraftForge's
		// ForgeEventFactory.onItemTooltip (javap: one invokestatic, none into net/neoforged), so NeoForge's event is
		// the dead one here. ItemStack#onDestroyed is NOT a row: it is an extension hook on both sides
		// (IForgeItemStack.onDestroyed survived) and cannot be a subscriber finding.
		dead.put("net/neoforged/neoforge/event/entity/player/ItemTooltipEvent",
				"item tooltips cannot be extended by NeoForge mods — the merged getTooltipLines calls only "
						+ "MinecraftForge's onItemTooltip");
		// EntityPlaceEvent was a row here too, for the reason routePlaceItemHookToNeoForge states: ItemStack.useOn
		// was sent to NeoForge's onPlaceItemIntoWorld so that placing anything works at all. True of useOn, and
		// the census finds ReplaceDisk#apply still calling ForgeEventFactory.onBlockPlace — so the event is
		// posted, and the row was unreachable anyway (BRIDGED is consulted first and carries this event).
		// Three rows were removed here once the census learned to look in the carriers as well as the game.
		// AddGuiOverlayLayersEvent and LootTableLoadEvent are BRIDGED, so they were delivered all along and the
		// rows were unreachable anyway; FluidPlaceBlockEvent is posted from MinecraftForge's own
		// FluidInteractionRegistry, which this layer cannot prove is reached but can no longer claim is not.
		// A row here says "the merged game never posts this", and none of the three could still say it.
		return Map.copyOf(dead);
	}

	/** The bridge that covers an event, when one does. Keeps an entry from being reported once it is bridged. */
	/**
	 * Events the merged game posts from SOME of the places it used to, and not the others.
	 *
	 * <h2>The state between the two the audit could describe</h2>
	 *
	 * <p>{@link #DEAD} says "never posted", and three rows were deleted from it for saying that about events
	 * that are posted. They were not careless: the hook is called from one class and not from another, because
	 * the merge took some of its call sites and left others. There was no row shape for that, so the choice was
	 * between a false claim and silence.
	 *
	 * <p>This is worse for a mod than either extreme, which is why it is worth its own row. A listener that
	 * never fires gets reported and investigated. One that fires for horses and llamas and not for anything
	 * else looks intermittent — the hardest kind of bug to report, and the easiest to blame on the mod.
	 *
	 * <p>Measured, not read: {@code HookCallSiteCensusStagedTest} recomputes this set by counting each hook's
	 * call sites in MinecraftForge's own patched game and in the merged base, and fails if the two disagree. The
	 * ratios in the sentences are that measurement.
	 *
	 * <p>An event that is also {@link #BRIDGED} is still listed here, so the generator can check the whole set,
	 * but the bridge is consulted first when judging a mod — a bridge may well be delivering the paths the merge
	 * took, and this layer cannot tell which.
	 */
	static final Map<String, String> PARTIAL = partiallyPosted();

	private static Map<String, String> partiallyPosted() {
		Map<String, String> partial = new LinkedHashMap<>();
		partial.put("net/minecraftforge/event/entity/living/LivingConversionEvent$Pre",
				"a conversion can be seen or prevented on 2 of the 10 paths that used to offer it — zombie to "
						+ "drowned, villager to witch and the rest mostly convert without asking");
		partial.put("net/minecraftforge/event/entity/living/LivingConversionEvent$Post",
				"a completed conversion is announced on 2 of its 7 paths, so a mod reacting to one will react "
						+ "to some conversions and not others");
		partial.put("net/minecraftforge/event/entity/player/PlayerDestroyItemEvent",
				"an item breaking is announced on 1 of its 4 paths — a mod that replaces or refunds broken "
						+ "tools will do it for some breakages only");
		partial.put("net/minecraftforge/event/level/BlockEvent$NeighborNotifyEvent",
				"block updates reaching neighbours are visible on 1 of the 4 paths that used to report them");
		partial.put("net/minecraftforge/event/level/BlockFeatureGrowEvent",
				"a feature growing is observable on 1 of its 3 paths — saplings and the rest differ");
		partial.put("net/minecraftforge/event/entity/living/LivingFallEvent",
				"fall damage can be modified on 2 of its 3 paths; the one that is gone is the one most entities "
						+ "take, so this reads as a mod that works for horses and llamas only");
		partial.put("net/minecraftforge/event/entity/living/MobEffectEvent$Applicable",
				"whether an effect may apply is asked on 1 of its 2 paths");
		partial.put("net/minecraftforge/event/level/BlockEvent$EntityPlaceEvent",
				"placement by an entity is announced on 1 of its 2 paths");
		return Map.copyOf(partial);
	}

	/**
	 * Events a merged-base REPAIR delivers, rather than a bridge.
	 *
	 * <h2>Why this is a third list and not a row in the other two</h2>
	 *
	 * <p>Some seams cannot be bridged. A bridge listens to the other ecosystem's event and re-posts this one, so
	 * it needs that other event to exist and be posted; where it does not, the only lever is redirecting the
	 * surviving call into the kernel and asking both sides there. Three of these now exist — fuel burn time,
	 * a mob's finalization (the spawner's call, and every call NeoForge's coremod redirects: NativeCoremodParity
	 * sends them through KernelFinalizeSpawn, which posts both families' events), and pack finders.
	 *
	 * <p>They belong here because everything that decides "is this event delivered" reads these tables, and the
	 * one that does not would go on listing them as work. That already happened once, from the other direction:
	 * the tick events were bridged and the worklist kept naming them because its matcher looked at one side
	 * only, and a work list that grows finished items is worse than none, because the first thing anyone does
	 * with it is spend a day on one.
	 */
	static final java.util.Set<String> REPAIRED = java.util.Set.of(
			"net/neoforged/neoforge/event/furnace/FurnaceFuelBurnTimeEvent",
			"net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn",
			"net/minecraftforge/event/AddPackFindersEvent",
			// ForgeDamageSeamsInjector: no NeoForge event sits where these were, so the pipeline posts them.
			"net/minecraftforge/event/entity/living/LivingHurtEvent",
			"net/minecraftforge/event/entity/living/LivingDamageEvent");

	/** Package-private for the same reason as {@link #DEAD}. */
	static final Map<String, GameEventBridge> BRIDGED = bridged();

	private static Map<String, GameEventBridge> bridged() {
		Map<String, GameEventBridge> map = new LinkedHashMap<>();
		map.put("net/minecraftforge/event/RegisterCommandsEvent", GameEventBridge.REGISTER_COMMANDS);
		map.put("net/minecraftforge/event/entity/player/PlayerEvent$PlayerLoggedInEvent",
				GameEventBridge.PLAYER_LOGGED_IN);
		map.put("net/minecraftforge/event/entity/player/PlayerEvent$PlayerLoggedOutEvent",
				GameEventBridge.PLAYER_LOGGED_OUT);
		map.put("net/minecraftforge/event/entity/living/LivingDeathEvent", GameEventBridge.LIVING_DEATH);
		map.put("net/minecraftforge/event/entity/living/LivingDropsEvent", GameEventBridge.LIVING_DROPS);
		map.put("net/minecraftforge/event/entity/living/LivingAttackEvent", GameEventBridge.LIVING_ATTACK);
		map.put("net/minecraftforge/event/entity/living/ShieldBlockEvent", GameEventBridge.SHIELD_BLOCK);
		map.put("net/minecraftforge/event/entity/living/LivingKnockBackEvent", GameEventBridge.LIVING_KNOCKBACK);
		map.put("net/minecraftforge/event/entity/living/LivingFallEvent", GameEventBridge.LIVING_FALL);
		map.put("net/minecraftforge/event/entity/player/PlayerEvent$Clone", GameEventBridge.PLAYER_CLONE);
		map.put("net/minecraftforge/event/entity/living/LivingExperienceDropEvent", GameEventBridge.EXPERIENCE_DROP);
		map.put("net/minecraftforge/event/level/ExplosionEvent$Detonate", GameEventBridge.EXPLOSION_DETONATE);
		map.put("net/minecraftforge/event/brewing/BrewingRecipeRegisterEvent", GameEventBridge.BREWING_RECIPES);
		map.put("net/minecraftforge/event/level/ChunkEvent$Load", GameEventBridge.CHUNK_LOAD);
		map.put("net/minecraftforge/event/level/ChunkEvent$Unload", GameEventBridge.CHUNK_UNLOAD);
		map.put("net/minecraftforge/event/entity/EntityLeaveLevelEvent", GameEventBridge.ENTITY_LEAVE_LEVEL);
		map.put("net/minecraftforge/event/entity/EntityEvent$EnteringSection", GameEventBridge.ENTERING_SECTION);
		map.put("net/minecraftforge/event/entity/player/PlayerWakeUpEvent", GameEventBridge.PLAYER_WAKE_UP);
		map.put("net/minecraftforge/event/TagsUpdatedEvent", GameEventBridge.TAGS_UPDATED);
		map.put("net/minecraftforge/event/entity/living/MobEffectEvent$Added", GameEventBridge.EFFECT_ADDED);
		map.put("net/minecraftforge/event/entity/living/MobEffectEvent$Expired", GameEventBridge.EFFECT_EXPIRED);
		map.put("net/minecraftforge/event/entity/living/MobEffectEvent$Applicable", GameEventBridge.EFFECT_APPLICABLE);
		map.put("net/minecraftforge/event/entity/living/LivingConversionEvent$Pre", GameEventBridge.CONVERSION_PRE);
		map.put("net/minecraftforge/event/entity/living/LivingConversionEvent$Post", GameEventBridge.CONVERSION_POST);
		map.put("net/minecraftforge/event/entity/ProjectileImpactEvent", GameEventBridge.PROJECTILE_IMPACT);
		map.put("net/minecraftforge/event/level/BlockEvent$FarmlandTrampleEvent", GameEventBridge.FARMLAND_TRAMPLE);
		map.put("net/minecraftforge/event/entity/player/PermissionsChangedEvent", GameEventBridge.PERMISSIONS_CHANGED);
		map.put("net/minecraftforge/event/CommandEvent", GameEventBridge.COMMAND);
		map.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$EntityInteractSpecific", GameEventBridge.ENTITY_INTERACT_SPECIFIC);
		map.put("net/minecraftforge/event/entity/living/LivingHealEvent", GameEventBridge.LIVING_HEAL);
		map.put("net/minecraftforge/event/entity/living/LivingEvent$LivingVisibilityEvent", GameEventBridge.LIVING_VISIBILITY);
		map.put("net/minecraftforge/event/entity/player/CriticalHitEvent", GameEventBridge.CRITICAL_HIT);
		map.put("net/minecraftforge/event/AnvilUpdateEvent", GameEventBridge.ANVIL_UPDATE);
		map.put("net/minecraftforge/event/entity/player/AnvilRepairEvent", GameEventBridge.ANVIL_REPAIR);
		map.put("net/minecraftforge/event/level/BlockEvent$BlockToolModificationEvent", GameEventBridge.TOOL_MODIFICATION);
		map.put("net/minecraftforge/event/entity/EntityJoinLevelEvent", GameEventBridge.ENTITY_JOIN_LEVEL);
		map.put("net/minecraftforge/event/level/BlockEvent$BreakEvent", GameEventBridge.BLOCK_BREAK);
		map.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickBlock",
				GameEventBridge.RIGHT_CLICK_BLOCK);
		map.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickBlock",
				GameEventBridge.LEFT_CLICK_BLOCK);
		map.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickItem",
				GameEventBridge.RIGHT_CLICK_ITEM);
		map.put("net/minecraftforge/event/LootTableLoadEvent", GameEventBridge.LOOT_TABLE_LOAD);
		map.put("net/neoforged/neoforge/event/entity/player/ItemTooltipEvent", GameEventBridge.ITEM_TOOLTIP);
		map.put("net/minecraftforge/client/event/AddGuiOverlayLayersEvent", GameEventBridge.GUI_OVERLAY_LAYERS);
		map.put("net/minecraftforge/event/level/BlockEvent$EntityPlaceEvent",
				GameEventBridge.ENTITY_PLACE_BLOCK);
		// The registration events lifted by the Phase 1 A repairs. ForgeHooksClient.initClientHooks posts the
		// first eleven (three directly, eight through the Forge managers' init()); the merged Minecraft.<init>
		// now calls it through KernelForgeClientInit. The next three have one redirect each.
		String client = "net/minecraftforge/client/event/";
		for (String event : List.of("RegisterKeyMappingsEvent", "EntityRenderersEvent$RegisterRenderers",
				"EntityRenderersEvent$RegisterLayerDefinitions", "RegisterClientTooltipComponentFactoriesEvent",
				"RegisterTextureAtlasSpriteLoadersEvent", "RegisterEntitySpectatorShadersEvent",
				"RegisterNamedRenderTypesEvent", "RegisterItemDecorationsEvent", "RegisterPresetEditorsEvent",
				"RegisterColorHandlersEvent$ColorResolvers", "ModelEvent$RegisterGeometryLoaders")) {
			map.put(client + event, GameEventBridge.CLIENT_INIT_HOOKS);
		}
		map.put(client + "RegisterParticleProvidersEvent", GameEventBridge.PARTICLE_PROVIDERS);
		map.put(client + "RegisterColorHandlersEvent$Block", GameEventBridge.BLOCK_TINT_SOURCES);
		map.put("net/minecraftforge/event/BuildCreativeModeTabContentsEvent", GameEventBridge.CREATIVE_TAB_CONTENTS);
		map.put("net/minecraftforge/event/entity/SpawnPlacementRegisterEvent", GameEventBridge.SPAWN_PLACEMENTS);
		return Map.copyOf(map);
	}

	private DeadEventAudit() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** One mod listening for one event that will never arrive. */
	public record Finding(String modId, String event, String cost) {
	}

	/**
	 * The findings for a set of (mod id → subscribed MinecraftForge event internal names).
	 *
	 * <p>Pure, so the reporting and the judgement can be tested apart. An event that IS bridged is not a finding
	 * even though it appears in the dead set, because the bridge is what makes it arrive.
	 */
	static List<Finding> audit(Map<String, Set<String>> subscribedByMod, Set<GameEventBridge> installed) {
		List<Finding> findings = new ArrayList<>();
		for (Map.Entry<String, Set<String>> mod : subscribedByMod.entrySet()) {
			for (String event : mod.getValue()) {
				GameEventBridge bridge = BRIDGED.get(event);
				if (bridge != null) {
					// A bridgeable event is dead exactly when its bridge is not in — which is the case worth
					// reporting, because a bridge can fail to install on a runtime the kernel did not expect and
					// nothing else would notice. Its cost sentence is already written, on the bridge.
					// A LATE pass has not run yet when this audit does (its sites live in classes defined after
					// mod construction), so its absence here means nothing; EventBridges.verify names it at the
					// pass's own moment instead. Counting it now would name every client mod as waiting, on every
					// boot, and train the reader to ignore the whole audit.
					if (!bridge.pass().lateInstalled() && !installed.contains(bridge)) {
						findings.add(new Finding(mod.getKey(), event, bridge.cost()));
					}
					continue;
				}
				// A repair delivers it, the same as a bridge would; reporting it would name a mod for an event
				// it does receive.
				if (REPAIRED.contains(event)) continue;
				String cost = DEAD.get(event);
				if (cost != null) {
					findings.add(new Finding(mod.getKey(), event, cost));
					continue;
				}
				// Neither bridged nor never posted: posted from some of the places it used to be. Reported with
				// the same weight, because a listener that fires on a third of its paths is not working.
				String partial = PARTIAL.get(event);
				if (partial == null) continue;
				findings.add(new Finding(mod.getKey(), event, partial));
			}
		}
		return findings;
	}

	/**
	 * Reports what a boot found. One line per event, naming every mod waiting on it, because the same dead event
	 * is usually subscribed by several mods and a line each would bury the list.
	 */
	public static void report(Map<String, Set<String>> subscribedByMod) {
		if (!enabled()) {
			ForbricLog.debug("[Forbric/DeadEvents] -D%s=off — not reporting merge-lost game events", PROPERTY);
			return;
		}
		// A bridgeable event is judged once the server is up, not here: most bridges install when their target class
		// is transformed, and RegisterCommandsEvent's target (Commands) loads AFTER mod construction on a dedicated
		// server — reporting it now named every Forge mod with a command as broken, on every boot, and since the
		// finding marks the row that was a false DEGRADED on the Mods screen. The listeners are kept and judged by
		// judgePending() from the ServerStarted hook, when every bridge has had its target.
		Map<String, Set<String>> deadNow = new LinkedHashMap<>(), bridgeable = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> mod : subscribedByMod.entrySet()) {
			for (String event : mod.getValue()) {
				(BRIDGED.containsKey(event) ? bridgeable : deadNow).computeIfAbsent(mod.getKey(), k -> new LinkedHashSet<>()).add(event);
			}
		}
		synchronized (PENDING) {
			for (Map.Entry<String, Set<String>> e : bridgeable.entrySet()) {
				PENDING.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
			}
		}
		if (!bridgeable.isEmpty()) {
			ForbricLog.info("[Forbric/DeadEvents] %d mod(s) listen for bridged Forge-family events; judged once the server is up",
					bridgeable.size());
		}
		report(audit(deadNow, EventBridges.installed()));
	}

	/** mod id → the bridgeable events it listens for, judged by {@link #judgePending()}. */
	private static final Map<String, Set<String>> PENDING = new LinkedHashMap<>();

	/** The deferred judgement: a bridgeable event whose bridge is STILL not installed once the server is up. */
	public static void judgePending() {
		judgePending(EventBridges.installed());
	}

	static List<Finding> judgePending(Set<GameEventBridge> installed) {
		if (!enabled()) return List.of();
		Map<String, Set<String>> pending;
		synchronized (PENDING) {
			pending = new LinkedHashMap<>();
			for (var e : PENDING.entrySet()) pending.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
			PENDING.clear();
		}
		List<Finding> findings = audit(pending, installed);
		report(findings);
		return findings;
	}

	static void resetPending() {
		synchronized (PENDING) {
			PENDING.clear();
		}
	}

	private static void report(List<Finding> findings) {
		if (findings.isEmpty()) return;

		Map<String, Set<String>> modsByEvent = new LinkedHashMap<>();
		Map<String, String> costByEvent = new LinkedHashMap<>();
		for (Finding finding : findings) {
			modsByEvent.computeIfAbsent(finding.event(), k -> new LinkedHashSet<>()).add(finding.modId());
			costByEvent.putIfAbsent(finding.event(), finding.cost());
		}

		// The Mods screen and load-report.txt read the catalogue; a listener keyed by class name (no owning mod
		// was found beside it) reaches mark() with a name no row carries, which invents nothing.
		for (Finding finding : findings) {
			ModCatalog.mark(finding.modId(), ModCatalog.Status.DEGRADED, "it listens for "
					+ finding.event().substring(finding.event().lastIndexOf('/') + 1).replace('$', '.')
					+ ", which this merged game never posts — " + finding.cost());
		}
		ForbricLog.warn("[Forbric/DeadEvents] %d Forge-family game event(s) the merged base no longer posts have "
				+ "listeners waiting on them. Those listeners will not run, and their mods are marked on the Mods screen.",
				modsByEvent.size());
		for (Map.Entry<String, Set<String>> entry : modsByEvent.entrySet()) {
			ForbricLog.warn("[Forbric/DeadEvents]   %s — %s (waiting: %s)",
					entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1),
					costByEvent.get(entry.getKey()), entry.getValue());
		}
	}
}
