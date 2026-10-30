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

import net.forbric.api.Ecosystem;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
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
 * cost more than it is worth, and the answer only changes when the merged base is rebuilt. The data is checked
 * against the bytecode by {@code HookCallSiteCensusStagedTest}, every table and in both directions:
 * <ul>
 *   <li>no DEAD row may name an event the base still posts, the kernel constructs, or another table delivers;</li>
 *   <li>every event {@link HookCallSiteCensus} measures as never posted — over every hook class of both families, every
 *       carrier method counting only when something reached calls it or the runtime enters it ({@link CarrierCallGraph}),
 *       the game read as the kernel's merged-base repairs leave it — has a row (DEAD, PARTIAL, BRIDGED, REPAIRED or
 *       STAND_DOWN), unless its own family's patched game never posts it either;</li>
 *   <li>a row that silences the audit proves the delivery it claims: a BRIDGED or STAND_DOWN row's bridge is installed (by
 *       {@link GameEventMultiplexer}, through an install method that exists and is declared to {@link KernelRuntimeClasses},
 *       or recorded by the transformer or runtime class that lands it) and what it reaches posts the event; a REPAIRED
 *       row's event is posted by the kernel's own code, or by the game only as the merged-base repairs leave it. "Posts" is
 *       read from the bytecode: a construction of the kernel's that flows into a bus's {@code post} or {@code fire}, or one
 *       a family method the kernel reaches makes — an event's own constructor and instance methods not followed, so the
 *       harvest check MinecraftForge's {@code BreakEvent} constructor asks for is not the break bridge's delivery;</li>
 *   <li>{@link #PARTIAL} is exactly the events a hook of any hook class lost game call sites for (counted with the hooks
 *       that reach it, through the hooks they call and the constructors of the events they construct) and that are still
 *       posted somewhere, plus those the game never posts but the kernel or a guest's API call still does on some path;
 *       and a PARTIAL row a bridge or repair also carries — silent while its bridge is in — has every lost path covered:
 *       at each game method that lost the family's posting, the merged game posts what the bridge forwards, or calls the
 *       kernel that posts the event;</li>
 *   <li>{@link #NO_NATIVE_PATH} is exactly the bridged and stood-down events the merged game never posts itself.</li>
 * </ul>
 * Until 2026-10-24 only the first was checked, so the table could leave out a measured-dead event and nothing noticed:
 * MinecraftForge's {@code LivingDrownEvent} was measured dead and named nowhere, and its {@code LivingBreatheEvent} hid
 * behind a carrier hook nothing called. Until 2026-10-25 the rows that silence the audit proved nothing (moving an event
 * nothing delivers into BRIDGED, REPAIRED or STAND_DOWN left the suite green), and only the two event factories were
 * measured for lost call sites (five events that lost game paths were named nowhere); for one more round the proof
 * accepted an event the bridge only constructed (a drown bridge with its {@code post} taken out) or that another event's
 * constructor sets off (the harvest check filed under the break bridge).
 *
 * <h2>What the merge lost, against what was never there</h2>
 *
 * <p>A row here is an accusation: the mod is marked DEGRADED because "this merged game never posts" what it waits for.
 * That is only true against what its own loader does. Four events the census measures dead on the merged base are dead
 * on MinecraftForge 26.2's own patched game as well — {@code PotionBrewEvent$Pre}, {@code EntityTeleportEvent$ChorusFruit},
 * {@code ChunkDataEvent$Load} and {@code CustomizeGuiOverlayEvent$DebugText}: each one's only poster has no caller in
 * MinecraftForge's patched client or server, its installer's jars, or its runtime. A listener for one waits for nothing on
 * MinecraftForge too, so they have no row (the staged test forbids one), and a bridge for one would post what the native
 * loader never does. The same goes for a client event on a dedicated server: {@link #CLIENT_ONLY} names the rows whose
 * native posting lives only in code a dedicated server does not have.
 *
 * <p>And an event the game never posts is still posted in a pack where a guest posts it: a mod that calls the hook
 * itself (Tombstone swaps in its own phantom spawner, which calls {@code ForgeEventFactory.onPlayerSpawnPhantom}) or
 * constructs the event. {@link #report(Map, net.forbric.api.Side, java.util.function.Function)} asks the loaded jars
 * before it accuses anyone, and says nothing of an event a guest posts.
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
		// ServerPlayerGameMode: four NeoForge hooks, zero MinecraftForge ones. BreakEvent was a row here too; it is
		// BRIDGED (BLOCK_BREAK), which is consulted first, so the row was unreachable, and once the census began checking
		// that no row here names an event the kernel itself delivers, it was the one row that did. RightClickBlock was the
		// same and stayed one more round: BRIDGED as well (RIGHT_CLICK_BLOCK, which KernelGameBlockEvents constructs), and
		// the census never judged it — no hook class constructs it, so "no row names an event the kernel delivers" was
		// checked only against what the census measures. HookCallSiteCensusStagedTest now checks every row against what the
		// kernel constructs, and against the other tables, whatever the census can judge.
		// LeftClickBlock and RightClickItem were rows here too. The merge now keeps both families' edits in a method
		// both patched, and HookCallSiteCensus finds ServerPlayerGameMode (and MultiPlayerGameMode) calling
		// MinecraftForge's onLeftClickBlock and onItemRightClick again, so neither is "never posted". Both are BRIDGED as well, which is consulted first, so the rows were
		// unreachable from the audit anyway; where the native call shares a path with NeoForge's, the bridge stands
		// down for that event (NativeDualEventInjector) rather than posting it a second time.
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
		// AddFramePassEvent and EntityRenderersEvent$AddLayers were rows here as well, until the merge began keeping
		// both families' edits in a method both patched: the census now finds Minecraft.<init> calling
		// ForgeEventFactoryClient.onInitLevelRenderer and EntityRenderDispatcher.onResourceManagerReload calling its
		// onGatherLayers again, so neither row can still say the event is never posted.
		dead.put(ForeignType.CREATE_SKULL_MODELS_EVENT.internal(Ecosystem.FORGE),
				"custom skull block models a MinecraftForge mod registers never render — the merged skull renderer "
						+ "reads only NeoForge's map");
		// The two fluid events the merged FlowingFluid/LavaFluid post only for NeoForge (EventHooks
		// .canCreateFluidSource at bc 110, fireFluidPlaceBlockEvent at three sites). Deliberately not bridged by the
		// fluid-rendering repair: that is event multiplexing, and it is named here instead so a listener is told.
		dead.put("net/minecraftforge/event/level/BlockEvent$CreateFluidSourceEvent",
				"infinite-source formation cannot be observed or vetoed — the merged FlowingFluid.canConvertToSource "
						+ "asks only NeoForge's EventHooks.canCreateFluidSource");
		// NeoForge's ItemTooltipEvent was the one row on the other side of the ledger: the merged
		// ItemStack.getTooltipLines called only MinecraftForge's onItemTooltip. With both families' edits kept in a
		// method both patched it calls NeoForge's EventHooks.onItemTooltip too, so the event is posted natively; the
		// kernel's tooltip repair stands down there (ForbricMergedBaseCompatTransformer) instead of posting it again.
		// EntityPlaceEvent was a row here too, for the reason routePlaceItemHookToNeoForge states: ItemStack.useOn
		// was sent to NeoForge's onPlaceItemIntoWorld so that placing anything works at all. True of useOn, and
		// the census finds ReplaceDisk#apply still calling ForgeEventFactory.onBlockPlace — so the event is
		// posted, and the row was unreachable anyway (BRIDGED is consulted first and carries this event).
		// Three rows were removed here once the census learned to look in the carriers as well as the game.
		// AddGuiOverlayLayersEvent and LootTableLoadEvent are BRIDGED, so they were delivered all along and the
		// rows were unreachable anyway; FluidPlaceBlockEvent is posted from MinecraftForge's own
		// FluidInteractionRegistry, which this layer cannot prove is reached but can no longer claim is not.
		// A row here says "the merged game never posts this", and none of the three could still say it.
		// The two sound events were rows here for one round: once the splice stopped carrying a MinecraftForge rewrite
		// of the vanilla play call over NeoForge's own handling of the same sound (it dropped what NeoForge's listeners
		// set), every place the merged game plays a sound asked only NeoForge. KernelGameSoundEvents forwards both now,
		// so they are BRIDGED.
		//
		// The rest were added on 2026-10-24, when HookCallSiteCensusStagedTest began requiring a row for every event the
		// census measures as never posted. Each is posted only by MinecraftForge hooks that neither the merged game nor
		// a reached carrier method calls, the kernel delivers none of them, and MinecraftForge's own patched game DOES
		// call one of them: the merge lost it. The merged game posts NeoForge's event (or nothing) in their place. None
		// is bridged yet: these are the candidates, named so a waiting mod is told. Four more were rows here for one
		// round and are not: PotionBrewEvent$Pre, EntityTeleportEvent$ChorusFruit, ChunkDataEvent$Load and
		// CustomizeGuiOverlayEvent$DebugText are never posted by MinecraftForge 26.2 itself (see the class javadoc), so a
		// row accused their listeners of a loss the native loader has too.
		String forge = "net/minecraftforge/event/", client = "net/minecraftforge/client/event/";
		dead.put(forge + "entity/EntityMobGriefingEvent",
				"one mob cannot be allowed or forbidden to grief against the mobGriefing rule");
		dead.put(forge + "entity/EntityTeleportEvent$EnderPearl",
				"an ender-pearl teleport cannot be cancelled or redirected, nor its fall damage changed");
		dead.put(forge + "entity/EntityTeleportEvent$SpreadPlayersCommand",
				"a /spreadplayers move cannot be cancelled or redirected");
		dead.put(forge + "entity/item/ItemExpireEvent",
				"a dropped item cannot be kept from despawning");
		dead.put(forge + "entity/item/ItemTossEvent",
				"an item a player drops is neither seen nor preventable");
		dead.put(forge + "entity/living/AnimalTameEvent",
				"taming is neither seen nor preventable");
		dead.put(forge + "entity/living/LivingChangeTargetEvent",
				"what a mob decides to attack can be neither seen nor changed — mods that keep mobs off a player do nothing");
		dead.put(forge + "entity/living/LivingDestroyBlockEvent",
				"a mob breaking a block cannot be stopped");
		dead.put(forge + "entity/living/LivingEntityUseItemEvent$Start",
				"starting to use an item (eating, drawing a bow) can be neither stopped nor retimed");
		dead.put(forge + "entity/living/LivingGetProjectileEvent",
				"the ammunition a bow or crossbow uses cannot be chosen — quivers and custom ammunition do nothing");
		dead.put(forge + "entity/living/LivingPackSizeEvent",
				"how many mobs spawn together cannot be changed");
		dead.put(forge + "entity/living/LivingSwapItemsEvent$Hands",
				"swapping hands is neither seen nor preventable");
		dead.put(forge + "entity/living/LivingUseTotemEvent",
				"a totem of undying can be neither stopped nor stood in for by another item");
		dead.put(forge + "entity/living/MobSpawnEvent$PositionCheck",
				"a natural or spawner spawn cannot be allowed or denied at its position");
		dead.put(forge + "entity/living/MobSpawnEvent$SpawnPlacementCheck",
				"where a kind of mob may spawn cannot be overridden");
		dead.put(forge + "entity/player/EntityItemPickupEvent",
				"a player picking up an item can be neither stopped nor taken over");
		dead.put(forge + "entity/player/PlayerEvent$ItemPickupEvent",
				"a player picking up an item is not announced — pickup trackers and quests count nothing");
		dead.put(forge + "entity/player/PlayerEvent$NameFormat",
				"a player's displayed name cannot be changed");
		dead.put(forge + "entity/player/PlayerEvent$TabListNameFormat",
				"a player's name in the player list cannot be changed");
		dead.put(forge + "entity/player/PlayerInteractEvent$LeftClickEmpty",
				"a left click at nothing is not announced");
		dead.put(forge + "entity/player/PlayerSleepInBedEvent",
				"sleeping in a bed can be neither allowed nor refused");
		dead.put(forge + "entity/player/PlayerSpawnPhantomsEvent",
				"phantom spawns for a player can be neither stopped nor changed");
		dead.put(forge + "entity/player/PlayerXpEvent$LevelChange",
				"a player's level changes can be neither seen nor changed");
		dead.put(forge + "entity/player/PlayerXpEvent$XpChange",
				"experience a player gains can be neither seen nor changed");
		dead.put(forge + "entity/player/SleepingLocationCheckEvent",
				"a player cannot be kept asleep somewhere that is not a bed");
		dead.put(forge + "entity/player/SleepingTimeCheckEvent",
				"whether a player may sleep at this time of day cannot be decided");
		dead.put(forge + "level/AlterGroundEvent",
				"the ground a tree decorator places (podzol under a giant spruce) cannot be changed");
		// Posted natively only from ForgeHooks.onPlaceItemIntoWorld, for an item that places more than one block (a bed, a
		// door). The staged ItemStack.useOn still calls that hook; the kernel's routePlaceItemHookToNeoForge sends the call to
		// NeoForge's (the snapshot list it drains is NeoForge-typed), so at runtime nothing reaches it. The census reads the
		// game through the merged-base repairs and sees that now; read off the staged jar, it called the event posted.
		dead.put(forge + "level/BlockEvent$EntityMultiPlaceEvent",
				"placing a block that is more than one block (a bed, a door) is not announced as one placement and "
						+ "cannot be refused as one");
		dead.put(forge + "level/LevelEvent$PotentialSpawns",
				"the mobs that may spawn at a place can be neither added nor removed");
		dead.put(forge + "level/NoteBlockEvent$Change",
				"a note block's pitch being changed is neither seen nor preventable");
		dead.put(forge + "level/NoteBlockEvent$Play",
				"a note block playing can be neither changed nor stopped");
		dead.put(forge + "level/SleepFinishedTimeEvent",
				"the time a night of sleep skips to cannot be changed");
		dead.put(client + "GatherAtlasMetadataSectionsEvent",
				"extra texture metadata registered for atlas sprites is never read");
		dead.put(client + "InputEvent$MouseButton$Post",
				"a mouse button the game has handled is not announced");
		dead.put(client + "RenderArmEvent",
				"the first-person arm can be neither changed nor hidden");
		dead.put(client + "RenderAvatarEvent$Pre",
				"how a player is drawn can be neither changed nor stopped");
		dead.put(client + "RenderAvatarEvent$Post",
				"nothing is drawn after a player is drawn");
		dead.put(client + "RenderNameTagEvent",
				"name tags can be neither changed nor hidden");
		dead.put(client + "RenderTooltipEvent$Pre",
				"a tooltip can be neither moved nor cancelled before it is drawn");
		dead.put(client + "ScreenEvent$CharacterTyped$Pre",
				"a character typed into a screen is neither seen nor consumable before the screen takes it");
		dead.put(client + "ScreenEvent$CharacterTyped$Post",
				"a character typed into a screen is not announced after the screen takes it");
		dead.put(client + "ScreenEvent$MouseButtonPressed$Post",
				"a click a screen handled (or ignored) is not announced");
		dead.put(client + "ScreenEvent$MouseButtonReleased$Post",
				"a mouse release a screen handled (or ignored) is not announced");
		dead.put(client + "ScreenEvent$MouseDragged$Post",
				"a drag a screen handled is not announced");
		dead.put(client + "ScreenEvent$MouseScrolled$Pre",
				"scrolling in a screen is neither seen nor consumable before the screen takes it");
		dead.put(client + "ScreenEvent$RenderInventoryMobEffects",
				"the effect list beside the inventory can be neither moved, compacted nor hidden");
		dead.put(client + "sound/PlaySoundEvent",
				"a sound the client is about to play can be neither replaced nor silenced");
		return Map.copyOf(dead);
	}

	/**
	 * The {@link #DEAD} and {@link #PARTIAL} rows whose event MinecraftForge posts natively only on the client: every
	 * method of its patched game whose calls reach a hook constructing the event carries, on itself or its class, the
	 * {@code @OnlyIn(Dist.CLIENT)} that MinecraftForge's merge of the client and server jars puts on what only the client
	 * jar has (for these rows that is also exactly the set of posting classes absent from the vanilla server jar). A
	 * dedicated server never posts these under MinecraftForge, so a listener there waits for nothing natively too, and
	 * {@link #report} judges them on the client only.
	 *
	 * <h2>Why a table and not the package</h2>
	 *
	 * <p>The first version judged by package: an event under {@code net/minecraftforge/client/} was a client event. That
	 * is a name, and it was wrong both ways it could be: {@code PlayerInteractEvent$LeftClickEmpty} lives in the common
	 * {@code event/entity/player} package and is posted natively from {@code net/minecraft/client/Minecraft} alone, so a
	 * mod listening for it from a common subscriber was named DEGRADED on every dedicated server for a click no server
	 * ever announces. Measured, not read: {@code HookCallSiteCensusStagedTest} recomputes this set from each family's own
	 * patched game ({@link HookCallSiteCensus#postingSites}) and fails when the two disagree.
	 */
	static final Set<String> CLIENT_ONLY = clientOnly();

	private static Set<String> clientOnly() {
		String client = "net/minecraftforge/client/event/";
		Set<String> out = new java.util.TreeSet<>(List.of(
				"net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickEmpty"));
		out.add(ForeignType.CREATE_SKULL_MODELS_EVENT.internal(Ecosystem.FORGE));
		out.add("net/neoforged/neoforge/client/event/InitializeClientRegistriesEvent");
		for (String event : List.of("GatherAtlasMetadataSectionsEvent", "RenderTooltipEvent$GatherComponents", "RenderBlockScreenEffectEvent",
				"InputEvent$MouseButton$Post", "RenderArmEvent", "RenderAvatarEvent$Pre", "RenderAvatarEvent$Post",
				"RenderNameTagEvent", "RenderTooltipEvent$Pre", "ScreenEvent$CharacterTyped$Pre",
				"ScreenEvent$CharacterTyped$Post", "ScreenEvent$MouseButtonPressed$Post",
				"ScreenEvent$MouseButtonReleased$Post", "ScreenEvent$MouseDragged$Post", "ScreenEvent$MouseScrolled$Pre",
				"ScreenEvent$RenderInventoryMobEffects", "sound/PlaySoundEvent")) {
			out.add(client + event);
		}
		return Set.copyOf(out);
	}

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
		// Regenerated from HookCallSiteCensus.erosion once the merge began keeping both families' edits in a method
		// both patched: PlayerDestroyItemEvent, NeighborNotifyEvent, BlockFeatureGrowEvent and MobEffectEvent
		// $Applicable got every call site back and left; four that had lost all of theirs (LivingConversionEvent$Pre,
		// EntityLeaveLevelEvent, LevelEvent$Unload, PlayLevelSoundEvent$AtEntity) got some back and joined, and
		// LivingConversionEvent$Post went from 3 of 7 to 4. Regenerated again once the splice carried an insertion
		// statement by statement: ClientLevel's tracking end now posts EntityLeaveLevelEvent too (2 of 2), so it left,
		// and the pig's lightning conversion keeps MinecraftForge's onLivingConvert (Post is 5 of 7 now). Regenerated
		// once the splice stopped carrying runs whose answer the kept body drops: PlayLevelSoundEvent$AtEntity lost
		// its last paths and moved to DEAD (and is BRIDGED now); the level tick (MinecraftServer.tickChildren), the click that starts
		// mining a block on the client (MultiPlayerGameMode.startDestroyBlock) and a huge mushroom's growth
		// (MushroomBlock.growMushroom) are posted from fewer places and joined. Regenerated once the merge stopped
		// forcing NeoForge's copy of a lambda both sides compile as one vanilla lambda onto its capturer: the client's
		// finishing break (MultiPlayerGameMode.lambda$continueDestroyBlock$1) asks isLeftClickBlockCancelled again, and
		// HookCallSiteCensusStagedTest now checks each row's count against every hook that posts the event — the
		// LeftClickBlock row had left out onLeftClickBlockHold's path.
		partial.put("net/minecraftforge/event/entity/living/LivingConversionEvent$Post",
				"a completed conversion is announced on 5 of its 7 paths — not for hoglins or piglins — so a "
						+ "mod reacting to one will react to some conversions and not others");
		partial.put("net/minecraftforge/event/entity/living/LivingConversionEvent$Pre",
				"whether a mob may convert is asked on 5 of its 10 paths — not for lightning, a piglin or hoglin "
						+ "zombifying, or a zombie villager being cured — so a mod that stops conversions stops some");
		partial.put("net/minecraftforge/event/entity/living/LivingFallEvent",
				"fall damage can be modified on 2 of its 3 paths; the one that is gone is the one most entities "
						+ "take, so this reads as a mod that works for horses and llamas only");
		partial.put("net/minecraftforge/event/level/BlockEvent$EntityPlaceEvent",
				"placement by an entity is announced on 1 of its 3 paths — not for a player placing a block, which "
						+ "its bridge delivers");
		partial.put("net/minecraftforge/event/level/LevelEvent$Unload",
				"a level going away is announced on 2 of its 3 paths — not when a server stops — so what a mod "
						+ "releases on unload is kept at shutdown");
		partial.put("net/minecraftforge/event/TickEvent$LevelTickEvent$Pre",
				"the level tick is posted natively for the client's level only, not for a server level — server "
						+ "levels reach MinecraftForge mods only through the level-tick bridge");
		partial.put("net/minecraftforge/event/TickEvent$LevelTickEvent$Post",
				"as the level tick's pre half: posted natively for the client's level only");
		partial.put("net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickBlock",
				"a left click on a block is announced on 5 of its 6 paths — not when a survival player starts "
						+ "mining a block on the client");
		partial.put("net/minecraftforge/event/level/BlockFeatureGrowEvent",
				"a feature growing from a bonemealed plant can be changed or stopped on 2 of its 3 paths — not for a "
						+ "huge mushroom");
		// The rows below were added on 2026-10-25, when HookCallSiteCensusStagedTest began measuring every hook class of
		// both families (ForgeHooks, ForgeHooksClient and NeoForge's three, not only the two event factories), counting a
		// hook's call sites together with those of every hook that calls it, and reading the merged game as its repairs
		// leave it. Before that none of these was named anywhere, so a mod waiting on one was told nothing.
		String forge = "net/minecraftforge/event/", client = "net/minecraftforge/client/event/";
		partial.put(forge + "level/BlockEvent$CropGrowEvent$Pre",
				"whether a plant may grow is asked on 2 of its 10 paths — only for cactus and sugar cane; wheat and every "
						+ "other crop, stems, cocoa, nether wart, sweet berries, bamboo, chorus flowers, kelp and vines grow "
						+ "without asking, so a mod that slows or stops growth (seasons, fertility) does nothing for them");
		partial.put(forge + "level/BlockEvent$CropGrowEvent$Post",
				"a plant that grew is announced on 9 of its 10 paths — not for melon and pumpkin stems");
		partial.put(forge + "level/BlockEvent$FluidPlaceBlockEvent",
				"lava flowing into water and turning it to stone, and lava setting fire, are not announced (0 of its 3 paths) "
						+ "— only the fluid-interaction rules (lava placed beside water) still announce the block they place");
		partial.put(forge + "entity/player/PlayerContainerEvent$Open",
				"a container a player opens is announced on 0 of its 1 paths — never for a vanilla container or one opened "
						+ "the vanilla way, only for a menu a MinecraftForge mod opens with extra data through Forge's own "
						+ "openMenu; the close is announced, so a mod pairing the two sees closes without opens");
		partial.put(forge + "entity/player/PlayerEvent$HarvestCheck",
				"whether a player can harvest a block is asked on 0 of its 3 paths — the game never asks it (mining speed, "
						+ "drops); it is asked only when a block breaks, by the break event the break bridge posts");
		partial.put(forge + "entity/player/PlayerDestroyItemEvent",
				"an item used up is announced on 7 of its 8 paths — not when the last of a stack is placed as a block");
		partial.put(forge + "entity/EntityJoinLevelEvent",
				"an entity joining a level is posted natively on 2 of its 3 paths — not for one added to a server level's "
						+ "entity manager (every mob, item and projectile), which its bridge delivers");
		partial.put(forge + "entity/ProjectileImpactEvent",
				"a projectile's hit is posted natively on 1 of its 6 paths (fishing hooks), which its bridge makes up for");
		partial.put(client + "RenderTooltipEvent$GatherComponents",
				"the lines of a tooltip can be added to or changed on 3 of its 4 paths — not on the one every item's "
						+ "tooltip takes (container slots, villager trades, the stonecutter, an item hovered in chat), which "
						+ "posts NeoForge's event alone: of the game's own tooltips only the enchanting table's and an entity "
						+ "hovered in chat still post it");
		partial.put(client + "RenderBlockScreenEffectEvent",
				"the in-block overlay is posted natively on 2 of its 3 paths (water and fire, not a block the camera is "
						+ "inside), which its bridge makes up for");
		partial.put("net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent",
				"a spawner's mob being finalized is posted natively on 1 of its 2 paths; the kernel posts the other "
						+ "(KernelFinalizeSpawn)");
		partial.put("net/neoforged/neoforge/client/event/InitializeClientRegistriesEvent",
				"NeoForge's client registries are initialized natively on 1 of its 2 paths; the kernel posts the other "
						+ "(KernelForgeClientInit)");
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
			ForeignType.FURNACE_FUEL_BURN_TIME_EVENT.internal(Ecosystem.NEOFORGE),
			"net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn",
			"net/minecraftforge/event/AddPackFindersEvent",
			// ForgeDamageSeamsInjector: no NeoForge event sits where these were, so the pipeline posts them.
			"net/minecraftforge/event/entity/living/LivingHurtEvent",
			"net/minecraftforge/event/entity/living/LivingDamageEvent",
			// The four below were named nowhere until the census required a row for every event it measures as never
			// posted. Each is delivered by the kernel, not by the merged game: the server's reload is routed through
			// KernelForgeReload, which hands NeoForge's listener list to MinecraftForge's own onResourceReload; the
			// picture-in-picture map nothing wrote is filled by KernelForgePipRenderers, which posts MinecraftForge's
			// registration; MinecraftForge's configuration tasks are gathered by PayloadInterop through its
			// gatherLoginConfigTasks (by reflection, which the census reads as a call since 2026-10-25: the binary class
			// name and the method name beside a reflective lookup); and ItemStack.useOn is routed to
			// NeoForge's onPlaceItemIntoWorld (routePlaceItemHookToNeoForge, a transform the staged jar does not show),
			// which posts NeoForge's multi-block place through EventHooks.onMultiBlockPlace. The census reads the game
			// through that repair now and finds this one posted; the row stays because the repair is what posts it.
			"net/minecraftforge/event/AddReloadListenerEvent",
			"net/minecraftforge/client/event/RegisterPictureInPictureRendererEvent",
			"net/minecraftforge/event/network/GatherLoginConfigurationTasksEvent",
			"net/neoforged/neoforge/event/level/BlockEvent$EntityMultiPlaceEvent",
			// The eight below are delivered through a merged-base repair that moves the game's own hook call into the
			// kernel, so once the census read the game as those repairs leave it, the game no longer calls the hook and
			// the kernel's class does: Minecraft.<init>'s two initClientHooks calls go through KernelForgeClientInit
			// (restoreForgeClientInit), which posts both families' client registration; ReloadableServerResources hands
			// NeoForge's reload to KernelForgeReload (postMinecraftForgesReloadListenerEvent), which still asks NeoForge's
			// onResourceReload; and FuelValues.burnDuration asks both families through the kernel
			// (letBothEcosystemsSetBurnTime). Read off the staged bytes each looked posted by the game itself.
			"net/minecraftforge/client/event/RegisterClientReloadListenersEvent",
			"net/neoforged/neoforge/client/event/AddClientReloadListenersEvent",
			ForeignType.CREATE_SKULL_MODELS_EVENT.internal(Ecosystem.NEOFORGE),
			"net/neoforged/neoforge/client/event/EntityRenderersEvent$RegisterLayerDefinitions",
			"net/neoforged/neoforge/client/event/EntityRenderersEvent$RegisterRenderers",
			"net/neoforged/neoforge/client/renderstate/RegisterRenderStateModifiersEvent",
			"net/neoforged/neoforge/event/AddServerReloadListenersEvent",
			ForeignType.FURNACE_FUEL_BURN_TIME_EVENT.internal(Ecosystem.FORGE),
			// Two NeoForge events the census found eroded once it measured NeoForge's hook classes against NeoForge's own
			// game: the spawner's finalization goes through KernelFinalizeSpawn (NativeCoremodParity), and Minecraft.<init>'s
			// initClientHooks through KernelForgeClientInit, and each posts NeoForge's event itself on that path.
			"net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent",
			"net/neoforged/neoforge/client/event/InitializeClientRegistriesEvent");

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
		map.put(ForeignType.LIVING_BREATHE_EVENT.internal(Ecosystem.FORGE), GameEventBridge.LIVING_BREATHE);
		map.put(ForeignType.LIVING_DROWN_EVENT.internal(Ecosystem.FORGE), GameEventBridge.LIVING_DROWN);
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
		// A bridge per event, not one for the pair: the stand-down skips what a native delivery covered, and a merged
		// method that asks MinecraftForge about an entity's sound has not asked it about a position's.
		map.put("net/minecraftforge/event/PlayLevelSoundEvent$AtEntity", GameEventBridge.PLAY_SOUND_AT_ENTITY);
		map.put("net/minecraftforge/event/PlayLevelSoundEvent$AtPosition", GameEventBridge.PLAY_SOUND_AT_POSITION);
		// The level tick has been bridged all along (KernelGameTickEvents); a row here makes the audit consult the
		// bridge for it, and lets the bridge stand down where the merged game posts MinecraftForge's level tick
		// itself on the same path as NeoForge's (the client's Minecraft.tick), instead of delivering it twice.
		map.put("net/minecraftforge/event/TickEvent$LevelTickEvent$Pre", GameEventBridge.LEVEL_TICK_PRE);
		map.put("net/minecraftforge/event/TickEvent$LevelTickEvent$Post", GameEventBridge.LEVEL_TICK_POST);
		// Entity tracking is bridged too (KernelGamePlayerTrackingEvents). Rows here because this table is also what
		// NativeDualEventInjector and the bridge guard read: a bridge whose events are not listed can never stand
		// down where the merged game tracks natively, and these two were the first to need it.
		map.put("net/minecraftforge/event/entity/player/PlayerEvent$StartTracking", GameEventBridge.START_TRACKING);
		map.put("net/minecraftforge/event/entity/player/PlayerEvent$StopTracking", GameEventBridge.STOP_TRACKING);
		// The server lifecycle has been bridged all along (KernelGameServerAboutToStart, KernelGameServerLifecycle). A
		// merged initServer and runServer that keep both families' edits call MinecraftForge's hooks natively on the
		// same path as NeoForge's, and without these rows each forward delivered its event a second time: every
		// MinecraftForge mod's about-to-start, starting and started listeners ran twice, its SERVER configs loaded twice
		// with two watchers each, and PermissionAPI was initialised twice.
		String server = "net/minecraftforge/event/server/";
		map.put(server + "ServerAboutToStartEvent", GameEventBridge.SERVER_ABOUT_TO_START);
		map.put(server + "ServerStartingEvent", GameEventBridge.SERVER_STARTING);
		map.put(server + "ServerStartedEvent", GameEventBridge.SERVER_STARTED);
		map.put(server + "ServerStoppingEvent", GameEventBridge.SERVER_STOPPING);
		map.put(server + "ServerStoppedEvent", GameEventBridge.SERVER_STOPPED);
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

	/**
	 * The rest of what the bridges on NeoForge's buses re-post: every forwarding bridge without a {@link #BRIDGED} row,
	 * game bus or mod bus, with the MinecraftForge events its forward constructs (read from its game-side class and the
	 * hooks it calls).
	 *
	 * <p>The stand-down is only as complete as this inventory. {@code NativeDualEventInjector} looks only for these
	 * events on a path that also posts NeoForge's, and {@link GameEventMultiplexer#guarded} guards only a bridge with
	 * events here or in {@link #BRIDGED}. A bridge with neither could never stand down, and a merge that keeps both
	 * families' edits calls MinecraftForge's hook natively beside NeoForge's at many of these sites: measured with the
	 * event-chain audit on the edit-spliced base, every MinecraftForge mod received the server, player, client and
	 * render ticks, each level's load, save and unload, the client's log-in and log-out, system chat, mouse buttons,
	 * fog and block overlays twice — once from the game and once from the bridge.
	 *
	 * <p>Why these are not {@link #BRIDGED} rows is history. That table was once the dead-event judgement's alone, and a
	 * CLIENT_GAME_BUS row there named every mod listening for its event DEGRADED on every dedicated server, which never
	 * installs it. The judgement now reads the two alike ({@link #bridgeOf}: judged by the bridge, a client's bridge not on
	 * a dedicated server), and {@code HookCallSiteCensusStagedTest} holds both to the same proof: the bridge is installed and
	 * what it reaches constructs the event. A row here is not a claim that the merged game still posts the event natively,
	 * and the two tables do not split on that: measured on 2026-10-25, fifteen of the 44 rows here (the chat, screen key
	 * and mouse, fog colour, field-of-view, boss bar and item-use-finish forwards) have no native path left and their bridge
	 * is their only delivery, while 34 of BRIDGED's 72 rows are posted natively on some path. The portal bridge is not here
	 * either: {@code PortalSpawnInjector} composes its two families' calls itself.
	 */
	static final Map<String, GameEventBridge> STAND_DOWN = standDown();

	private static Map<String, GameEventBridge> standDown() {
		Map<String, GameEventBridge> map = new LinkedHashMap<>();
		String event = "net/minecraftforge/event/", client = "net/minecraftforge/client/event/";
		map.put(event + "TickEvent$ServerTickEvent$Pre", GameEventBridge.SERVER_TICK_PRE);
		map.put(event + "TickEvent$ServerTickEvent$Post", GameEventBridge.SERVER_TICK_POST);
		map.put(event + "TickEvent$PlayerTickEvent$Pre", GameEventBridge.PLAYER_TICK_PRE);
		map.put(event + "TickEvent$PlayerTickEvent$Post", GameEventBridge.PLAYER_TICK_POST);
		map.put(event + "entity/player/PlayerEvent$PlayerRespawnEvent", GameEventBridge.PLAYER_RESPAWN);
		map.put(event + "entity/player/PlayerEvent$PlayerChangedDimensionEvent", GameEventBridge.PLAYER_CHANGED_DIMENSION);
		map.put(event + "level/LevelEvent$Load", GameEventBridge.LEVEL_LOAD);
		map.put(event + "level/LevelEvent$Unload", GameEventBridge.LEVEL_UNLOAD);
		map.put(event + "level/LevelEvent$Save", GameEventBridge.LEVEL_SAVE);
		map.put(event + "entity/living/LivingEntityUseItemEvent$Finish", GameEventBridge.ITEM_USE_FINISH);
		map.put(event + "TickEvent$ClientTickEvent$Pre", GameEventBridge.CLIENT_TICK_PRE);
		map.put(event + "TickEvent$ClientTickEvent$Post", GameEventBridge.CLIENT_TICK_POST);
		map.put(event + "TickEvent$RenderTickEvent$Pre", GameEventBridge.RENDER_FRAME_PRE);
		map.put(event + "TickEvent$RenderTickEvent$Post", GameEventBridge.RENDER_FRAME_POST);
		map.put(client + "ScreenEvent$MouseButtonPressed$Pre", GameEventBridge.SCREEN_MOUSE_PRESSED_PRE);
		map.put(client + "ScreenEvent$MouseButtonReleased$Pre", GameEventBridge.SCREEN_MOUSE_RELEASED_PRE);
		map.put(client + "ScreenEvent$MouseDragged$Pre", GameEventBridge.SCREEN_MOUSE_DRAG_PRE);
		map.put(client + "ScreenEvent$MouseScrolled$Post", GameEventBridge.SCREEN_MOUSE_SCROLL_POST);
		map.put(client + "ClientPlayerNetworkEvent$LoggingIn", GameEventBridge.CLIENT_LOGGING_IN);
		map.put(client + "ClientPlayerNetworkEvent$LoggingOut", GameEventBridge.CLIENT_LOGGING_OUT);
		map.put(client + "ClientPlayerNetworkEvent$Clone", GameEventBridge.CLIENT_PLAYER_CLONE);
		map.put(client + "RegisterClientCommandsEvent", GameEventBridge.CLIENT_COMMANDS);
		map.put(client + "ClientChatReceivedEvent", GameEventBridge.CLIENT_CHAT_RECEIVED);
		map.put(client + "ClientChatReceivedEvent$Player", GameEventBridge.CLIENT_CHAT_RECEIVED);
		map.put(client + "SystemMessageReceivedEvent", GameEventBridge.CLIENT_CHAT_RECEIVED);
		map.put(client + "ClientChatEvent", GameEventBridge.CLIENT_CHAT_SEND);
		map.put(client + "InputEvent$Key", GameEventBridge.KEY_INPUT);
		map.put(client + "ScreenEvent$Init$Pre", GameEventBridge.SCREEN_INIT_PRE);
		map.put(client + "ScreenEvent$Init$Post", GameEventBridge.SCREEN_INIT_POST);
		map.put(client + "ScreenEvent$KeyPressed$Pre", GameEventBridge.SCREEN_KEY_PRESSED_PRE);
		map.put(client + "ScreenEvent$KeyPressed$Post", GameEventBridge.SCREEN_KEY_PRESSED_POST);
		map.put(client + "ScreenEvent$KeyReleased$Pre", GameEventBridge.SCREEN_KEY_RELEASED_PRE);
		map.put(client + "ScreenEvent$KeyReleased$Post", GameEventBridge.SCREEN_KEY_RELEASED_POST);
		map.put(client + "InputEvent$MouseButton$Pre", GameEventBridge.MOUSE_BUTTON_PRE);
		map.put(client + "InputEvent$InteractionKeyMappingTriggered", GameEventBridge.INTERACTION_KEY);
		map.put(client + "ViewportEvent$RenderFog", GameEventBridge.RENDER_FOG);
		map.put(client + "ViewportEvent$ComputeFogColor", GameEventBridge.FOG_COLOR);
		map.put(client + "ComputeFovModifierEvent", GameEventBridge.FOV_MODIFIER);
		map.put(client + "RenderBlockScreenEffectEvent", GameEventBridge.BLOCK_OVERLAY);
		map.put(client + "CustomizeGuiOverlayEvent$BossEventProgress", GameEventBridge.BOSS_EVENT_PROGRESS);
		map.put(client + "ScreenEvent$Render$Pre", GameEventBridge.SCREEN_RENDER_PRE);
		map.put(client + "ScreenEvent$Render$Post", GameEventBridge.SCREEN_RENDER_POST);
		// The two forwards on NeoForge's mod bus. The merged TextureAtlas.upload and ModelManager.apply call MinecraftForge's
		// hook as well as NeoForge's, so without these rows every MinecraftForge mod saw each atlas stitched, and the models
		// baked, twice.
		map.put(client + "TextureStitchEvent$Post", GameEventBridge.TEXTURE_STITCHED);
		map.put(client + "ModelEvent$BakingCompleted", GameEventBridge.MODELS_BAKED);
		return Map.copyOf(map);
	}

	/**
	 * The {@link #BRIDGED} and {@link #STAND_DOWN} events the merged game never posts itself: neither its own code nor a
	 * family method it (or the family's runtime) reaches constructs one, so the bridge is the only delivery.
	 *
	 * <h2>What a missing bridge costs</h2>
	 *
	 * <p>{@link #audit} names a mod whose event's bridge is not installed: a bridge that failed on a runtime the kernel did not
	 * expect, which is what the judgement once the server is up exists to catch. Until 2026-10-25 it said "which this merged
	 * game never posts" for every such event, and for most of them that was false: the merged game posts the server and player
	 * ticks, the level's load and save, logging in and out, and dozens more itself, and the bridge only stands down there —
	 * offline, on the popular pack with no bridge installed, 45 of the 76 listener and event pairs it would have called never
	 * posted are posted by the merged game itself. ({@code -Dforbric.unifiedEvents=off} does not reach this judgement: with
	 * the switch off {@code GameEventMultiplexer.install} returns before it installs MinecraftForge's server-started duties.) So a
	 * missing bridge is now judged by what the merged game does without
	 * it: an event in this set is never posted ("never posts", the bridge's own cost sentence); one the merged game posts
	 * itself is posted on the paths {@link #PARTIAL} counts when it has a row there, and on every path its family's game posts
	 * it, as far as the census counts, when it has none — the listener loses nothing measured, and is not named.
	 *
	 * <p>Measured, not read: {@code HookCallSiteCensusStagedTest} recomputes this set from the merged game as the kernel loads
	 * it and fails when the two disagree.
	 */
	static final Set<String> NO_NATIVE_PATH = noNativePath();

	private static Set<String> noNativePath() {
		Set<String> out = new java.util.TreeSet<>();
		String event = "net/minecraftforge/event/", client = "net/minecraftforge/client/event/";
		for (String e : List.of("AnvilUpdateEvent", "BuildCreativeModeTabContentsEvent", "CommandEvent",
				"PlayLevelSoundEvent$AtEntity", "PlayLevelSoundEvent$AtPosition",
				"brewing/BrewingRecipeRegisterEvent", "entity/living/LivingBreatheEvent",
				"entity/living/LivingDropsEvent", "entity/living/LivingDrownEvent",
				"entity/living/LivingEntityUseItemEvent$Finish", "entity/living/LivingExperienceDropEvent",
				"entity/living/LivingHealEvent", "entity/living/ShieldBlockEvent", "entity/player/AnvilRepairEvent",
				"entity/player/CriticalHitEvent", "entity/player/PlayerInteractEvent$RightClickBlock",
				"level/BlockEvent$BlockToolModificationEvent", "level/BlockEvent$BreakEvent",
				"level/BlockEvent$FarmlandTrampleEvent", "level/ExplosionEvent$Detonate",
				"server/ServerStoppedEvent")) {
			out.add(event + e);
		}
		for (String e : List.of("ClientChatEvent", "ClientChatReceivedEvent", "ClientChatReceivedEvent$Player",
				"ComputeFovModifierEvent", "CustomizeGuiOverlayEvent$BossEventProgress",
				"EntityRenderersEvent$RegisterLayerDefinitions", "EntityRenderersEvent$RegisterRenderers",
				"RegisterClientTooltipComponentFactoriesEvent", "RegisterColorHandlersEvent$ColorResolvers",
				"RegisterEntitySpectatorShadersEvent", "RegisterItemDecorationsEvent", "RegisterKeyMappingsEvent",
				"RegisterNamedRenderTypesEvent", "RegisterPresetEditorsEvent",
				"RegisterTextureAtlasSpriteLoadersEvent", "ScreenEvent$Init$Post", "ScreenEvent$Init$Pre",
				"ScreenEvent$KeyPressed$Post", "ScreenEvent$KeyPressed$Pre", "ScreenEvent$KeyReleased$Post",
				"ScreenEvent$KeyReleased$Pre", "ScreenEvent$MouseButtonPressed$Pre",
				"ScreenEvent$MouseButtonReleased$Pre", "ScreenEvent$MouseDragged$Pre",
				"ScreenEvent$MouseScrolled$Post", "ViewportEvent$ComputeFogColor")) {
			out.add(client + e);
		}
		return Set.copyOf(out);
	}

	/**
	 * The MinecraftForge events a bridge re-posts from a NeoForge event on a NeoForge bus: every {@link #BRIDGED} or
	 * {@link #STAND_DOWN} key in MinecraftForge's namespace whose bridge {@link GameEventMultiplexer} installs, and
	 * therefore guards. What {@code NativeDualEventInjector} looks for on a path that also posts NeoForge's. The rows
	 * landed by class transformers (client init, registration, the HUD, tooltips) forward from no NeoForge event and
	 * are left out.
	 */
	public static Set<String> forwardedForgeEvents() {
		Set<String> out = new java.util.TreeSet<>();
		for (Map<String, GameEventBridge> table : List.of(BRIDGED, STAND_DOWN)) {
			for (Map.Entry<String, GameEventBridge> row : table.entrySet()) {
				if (row.getKey().startsWith(FORGE) && forwards(row.getValue())) out.add(row.getKey());
			}
		}
		return Set.copyOf(out);
	}

	/** Whether {@code bridge} is installed on one of NeoForge's buses, game or mod, by {@link GameEventMultiplexer}. */
	private static boolean forwards(GameEventBridge bridge) {
		return bridge.pass() == GameEventBridge.Pass.GAME_BUS || bridge.pass() == GameEventBridge.Pass.CLIENT_GAME_BUS
				|| bridge.pass() == GameEventBridge.Pass.CLIENT_MOD_BUS;
	}

	/** The MinecraftForge events {@code bridge} re-posts, none when it has no {@link #BRIDGED} or {@link #STAND_DOWN} row. */
	static Set<String> forgeEventsOf(GameEventBridge bridge) {
		Set<String> out = new java.util.TreeSet<>();
		for (Map<String, GameEventBridge> table : List.of(BRIDGED, STAND_DOWN)) {
			for (Map.Entry<String, GameEventBridge> row : table.entrySet()) {
				if (row.getValue() == bridge && row.getKey().startsWith(FORGE) && forwards(bridge)) out.add(row.getKey());
			}
		}
		return Set.copyOf(out);
	}

	private static final String FORGE = "net/minecraftforge/";

	private DeadEventAudit() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * One mod listening for one event that will never arrive, or arrive only on some of its paths ({@code partial}): a
	 * {@link #PARTIAL} row. The two are worded apart where a player reads them — "never posts" said of an event that is
	 * posted on five of seven paths is a false statement on the Mods screen.
	 */
	public record Finding(String modId, String event, String cost, boolean partial) {
		public Finding(String modId, String event, String cost) {
			this(modId, event, cost, false);
		}
	}

	/** The bridge that delivers {@code event}: its {@link #BRIDGED} row, else its {@link #STAND_DOWN} row; null when neither. */
	static GameEventBridge bridgeOf(String event) {
		GameEventBridge bridge = BRIDGED.get(event);
		return bridge != null ? bridge : STAND_DOWN.get(event);
	}

	/** Whether {@code bridge} is installed only on a client: a dedicated server never has it, and never misses it. */
	static boolean clientBridge(GameEventBridge bridge) {
		return switch (bridge.pass()) {
			case CLIENT_GAME_BUS, CLIENT_MOD_BUS, CLIENT_INIT, CLIENT_HUD -> true;
			default -> false;
		};
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
				// A STAND_DOWN bridge delivers its event as surely as a BRIDGED one; it only also stands down where the merged
				// game posts it natively. Not consulting it named every mod waiting on LevelEvent$Unload "partly posted" for
				// the server-stop path its bridge forwards.
				GameEventBridge bridge = bridgeOf(event);
				if (bridge != null) {
					// A bridgeable event is dead exactly when its bridge is not in — which is the case worth
					// reporting, because a bridge can fail to install on a runtime the kernel did not expect and
					// nothing else would notice. Its cost sentence is already written, on the bridge.
					// A LATE pass has not run yet when this audit does (its sites live in classes defined after
					// mod construction), so its absence here means nothing; EventBridges.verify names it at the
					// pass's own moment instead. Counting it now would name every client mod as waiting, on every
					// boot, and train the reader to ignore the whole audit.
					if (!bridge.pass().lateInstalled() && !installed.contains(bridge)) {
						// Without its bridge the event arrives where the merged game posts it itself (NO_NATIVE_PATH): never,
						// on PARTIAL's paths, or on every path its family's game has.
						if (NO_NATIVE_PATH.contains(event)) findings.add(new Finding(mod.getKey(), event, bridge.cost()));
						else if (PARTIAL.containsKey(event)) findings.add(new Finding(mod.getKey(), event, PARTIAL.get(event), true));
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
				findings.add(new Finding(mod.getKey(), event, partial, true));
			}
		}
		return findings;
	}

	/**
	 * Reports what a boot found. One line per event, naming every mod waiting on it, because the same dead event
	 * is usually subscribed by several mods and a line each would bury the list.
	 */
	public static void report(Map<String, Set<String>> subscribedByMod, net.forbric.api.Side side) {
		report(subscribedByMod, side, events -> Map.of());
	}

	/**
	 * The same, told which of the events it is about to call never posted a loaded guest posts itself: event → the jars
	 * that post it, asked only for the {@link #DEAD} events someone listens for (see {@link GuestEventPosters}). An event a
	 * guest posts is posted in this pack, on that guest's paths at least, so its listeners are not accused; the line says
	 * which guest posts what instead.
	 */
	public static void report(Map<String, Set<String>> subscribedByMod, net.forbric.api.Side side,
			java.util.function.Function<Set<String>, Map<String, Set<String>>> postedByGuests) {
		report(subscribedByMod, side, postedByGuests, Map.of(), event -> null);
	}

	/**
	 * The same, also told what each mod's code registers ({@code inCode}, from {@link GuestListeners}) and how to ask an
	 * event's own bus whether a listener is on it ({@code listenersOnBus}: true, false, or null when it cannot tell). Every
	 * registering class counts as loaded; see the next overload.
	 */
	public static void report(Map<String, Set<String>> subscribedByMod, net.forbric.api.Side side,
			java.util.function.Function<Set<String>, Map<String, Set<String>>> postedByGuests, Map<String, Set<String>> inCode,
			java.util.function.Function<String, Boolean> listenersOnBus) {
		report(subscribedByMod, side, postedByGuests, inCode, Map.of(), type -> true, listenersOnBus);
	}

	/**
	 * The same, also told which classes register each mod's listeners in code ({@code registrars}: mod → event → the classes
	 * whose code makes the registration, or the class handed to a bus to register) and whether a class has been loaded.
	 *
	 * <h2>Why a listener found in code is judged later, and only where it was registered</h2>
	 *
	 * <p>An annotation subscriber declares its side ({@code @EventBusSubscriber(value = Dist.CLIENT)}); a registration in
	 * code does not: JourneyMap registers its handlers from its client setup listener, and nothing in the handler classes
	 * says they are a client's. Judged from the bytecode alone, a dedicated server would name JourneyMap for a fluid event
	 * whose listener it never registers there. So these are judged once every mod's setup has run — when loading completes,
	 * on either side ({@link #judgeCodeListeners}), or, for an event a bridge delivers, once the server is up with the
	 * annotation listeners ({@link #judgePending}) — and a registration counts only when it can have happened:
	 * <ul>
	 *   <li>one of the classes that make it has been loaded (a class whose code never ran registered nothing; JourneyMap's
	 *       handler classes on a dedicated server), and</li>
	 *   <li>the event's own static bus has a listener now ({@code listenersOnBus}); an event with no bus of its own to ask
	 *       (NeoForge's, a mod-bus event) is judged as found.</li>
	 * </ul>
	 * Until 2026-10-25 they were judged only from the {@code ServerStarted} hook, so a client that only ever joined a remote
	 * server never judged them at all, and the bus's answer stood alone: a mod that registers on the client only was named on
	 * a dedicated server whenever another mod listened for the same event there. The bus still cannot say who is on it; the
	 * loaded class is what ties the registration to this mod — only where that class registers on every side it loads on.
	 * A class both sides load that registers under a side check of its own (lucid-dreams' {@code @Mod} constructor tests
	 * {@code FMLEnvironment.dist} before its client listeners) counts as registering on a dedicated server too, and would be
	 * named there for a common event whenever anything else listens on that event's bus (no case today: what it registers
	 * under that check is for client-only rows). The listeners for an event a bridge delivers are judged by {@link #judgePending} alone, which a
	 * client that only ever joins a remote server never runs: there a missing client bridge is logged by
	 * {@code EventBridges.verify}, which names no mod.
	 */
	public static void report(Map<String, Set<String>> subscribedByMod, net.forbric.api.Side side,
			java.util.function.Function<Set<String>, Map<String, Set<String>>> postedByGuests, Map<String, Set<String>> inCode,
			Map<String, Map<String, Set<String>>> registrars, java.util.function.Predicate<String> loaded,
			java.util.function.Function<String, Boolean> listenersOnBus) {
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
		split(subscribedByMod, side, deadNow, bridgeable);
		Map<String, Set<String>> codeNow = new LinkedHashMap<>(), codeBridgeable = new LinkedHashMap<>();
		split(inCode, side, codeNow, codeBridgeable);
		synchronized (PENDING) {
			for (Map.Entry<String, Set<String>> e : bridgeable.entrySet()) {
				PENDING.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
			}
		}
		if (!bridgeable.isEmpty()) {
			ForbricLog.info("[Forbric/DeadEvents] %d mod(s) listen for bridged Forge-family events; judged once the server is up",
					bridgeable.size());
		}
		// A copy of every set: a mod in both maps must not have its code events added to the ones judged now.
		Map<String, Set<String>> asked = new LinkedHashMap<>();
		for (Map<String, Set<String>> source : List.of(deadNow, codeNow)) {
			source.forEach((mod, events) -> asked.computeIfAbsent(mod, k -> new LinkedHashSet<>()).addAll(events));
		}
		Map<String, Set<String>> posted = postedByGuests(asked, postedByGuests);
		if (!posted.isEmpty()) {
			ForbricLog.info("[Forbric/DeadEvents] %d game event(s) the merged base never posts are posted by a mod in this "
					+ "pack, so their listeners are not reported: %s", posted.size(), posted);
		}
		report(audit(withoutEvents(deadNow, posted.keySet()), EventBridges.satisfied()));
		Map<String, Set<String>> code = new LinkedHashMap<>(withoutEvents(codeNow, posted.keySet()));
		code.values().removeIf(events -> events.stream().noneMatch(DeadEventAudit::judged));
		Map<String, Set<String>> codeLater = new LinkedHashMap<>();
		codeBridgeable.forEach((mod, events) -> codeLater.computeIfAbsent(mod, k -> new LinkedHashSet<>()).addAll(events));
		synchronized (PENDING) {
			for (Map.Entry<String, Set<String>> e : code.entrySet()) {
				PENDING_CODE.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
			}
			for (Map.Entry<String, Set<String>> e : codeLater.entrySet()) {
				PENDING_CODE_BRIDGED.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
			}
			registrars.forEach((mod, byEvent) -> byEvent.forEach((event, classes) -> REGISTRARS
					.computeIfAbsent(mod, k -> new LinkedHashMap<>()).computeIfAbsent(event, k -> new java.util.TreeSet<>()).addAll(classes)));
			LISTENERS_ON_BUS[0] = listenersOnBus;
			LOADED[0] = loaded;
		}
		if (!code.isEmpty() || !codeLater.isEmpty()) {
			ForbricLog.info("[Forbric/DeadEvents] %d mod(s) register listeners in code for Forge-family events a table names; "
					+ "judged once loading completes (an event a bridge delivers, once the server is up), where a class that "
					+ "registers it has loaded and the event's bus has a listener", code.size() + codeLater.size());
		}
	}

	/** Whether any table speaks for {@code event}: only those are worth keeping for the later judgement. */
	private static boolean judged(String event) {
		return bridgeOf(event) != null || REPAIRED.contains(event) || DEAD.containsKey(event) || PARTIAL.containsKey(event);
	}

	/**
	 * mod id → the events its code registers for that a table names and no bridge delivers, judged by
	 * {@link #judgeCodeListeners()} once loading completes (guarded by PENDING).
	 */
	private static final Map<String, Set<String>> PENDING_CODE = new LinkedHashMap<>();
	/** The same for events a bridge delivers, judged by {@link #judgePending()} once the server is up (guarded by PENDING). */
	private static final Map<String, Set<String>> PENDING_CODE_BRIDGED = new LinkedHashMap<>();
	/** mod id → event → the classes that register it in code (guarded by PENDING). */
	private static final Map<String, Map<String, Set<String>>> REGISTRARS = new LinkedHashMap<>();
	/** How the code judgement asks an event's bus whether it has a listener (guarded by PENDING). */
	@SuppressWarnings("unchecked")
	private static final java.util.function.Function<String, Boolean>[] LISTENERS_ON_BUS = new java.util.function.Function[] {
			(java.util.function.Function<String, Boolean>) event -> null};
	/** Whether a class (internal name) has been loaded (guarded by PENDING). */
	@SuppressWarnings("unchecked")
	private static final java.util.function.Predicate<String>[] LOADED = new java.util.function.Predicate[] {
			(java.util.function.Predicate<String>) type -> true};

	/**
	 * Sorts each listener: an event a bridge delivers ({@link #BRIDGED} or {@link #STAND_DOWN}) into {@code bridgeable}
	 * (judged once the server is up), anything else into {@code deadNow} — except, on a dedicated server, an event
	 * MinecraftForge posts natively only on the client ({@link #CLIENT_ONLY}), or one whose bridge only a client installs.
	 * A listener there waits for nothing under its own loader either, and naming its mod DEGRADED for it would be a false
	 * accusation.
	 */
	static void split(Map<String, Set<String>> subscribedByMod, net.forbric.api.Side side, Map<String, Set<String>> deadNow,
			Map<String, Set<String>> bridgeable) {
		for (Map.Entry<String, Set<String>> mod : subscribedByMod.entrySet()) {
			for (String event : mod.getValue()) {
				GameEventBridge bridge = bridgeOf(event);
				if (!side.isClient() && (bridge == null ? CLIENT_ONLY.contains(event) : clientBridge(bridge))) continue;
				(bridge != null ? bridgeable : deadNow).computeIfAbsent(mod.getKey(), k -> new LinkedHashSet<>()).add(event);
			}
		}
	}

	/**
	 * Of the {@link #DEAD} events someone in {@code deadNow} listens for, those a loaded guest posts: event → the jars that
	 * post it. Nothing is asked when no such listener exists, so a pack without one pays for no scan.
	 */
	static Map<String, Set<String>> postedByGuests(Map<String, Set<String>> deadNow,
			java.util.function.Function<Set<String>, Map<String, Set<String>>> postedByGuests) {
		Set<String> asked = new java.util.TreeSet<>();
		for (Set<String> events : deadNow.values()) {
			for (String event : events) if (DEAD.containsKey(event)) asked.add(event);
		}
		if (asked.isEmpty()) return Map.of();
		Map<String, Set<String>> out = new java.util.TreeMap<>();
		postedByGuests.apply(asked).forEach((event, jars) -> {
			if (asked.contains(event) && !jars.isEmpty()) out.put(event, Set.copyOf(jars));
		});
		return out;
	}

	/** {@code subscribedByMod} without {@code events}. */
	static Map<String, Set<String>> withoutEvents(Map<String, Set<String>> subscribedByMod, Set<String> events) {
		if (events.isEmpty()) return subscribedByMod;
		Map<String, Set<String>> out = new LinkedHashMap<>();
		subscribedByMod.forEach((mod, subscribed) -> {
			Set<String> kept = new LinkedHashSet<>(subscribed);
			kept.removeAll(events);
			if (!kept.isEmpty()) out.put(mod, kept);
		});
		return out;
	}

	/** mod id → the bridgeable events it listens for, judged by {@link #judgePending()}. */
	private static final Map<String, Set<String>> PENDING = new LinkedHashMap<>();

	/** The deferred judgement: a bridgeable event whose bridge is STILL not installed once the server is up. */
	public static void judgePending() {
		judgePending(EventBridges.satisfied());
	}

	static List<Finding> judgePending(Set<GameEventBridge> installed) {
		if (!enabled()) return List.of();
		Map<String, Set<String>> pending, code;
		synchronized (PENDING) {
			pending = new LinkedHashMap<>();
			for (var e : PENDING.entrySet()) pending.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
			PENDING.clear();
			code = new LinkedHashMap<>();
			// The code listeners of a bridged event, and any not yet judged because loading never reported complete.
			for (Map<String, Set<String>> source : List.of(PENDING_CODE_BRIDGED, PENDING_CODE)) {
				source.forEach((mod, events) -> code.computeIfAbsent(mod, k -> new LinkedHashSet<>()).addAll(events));
				source.clear();
			}
		}
		List<Finding> findings = new ArrayList<>(audit(pending, installed));
		findings.addAll(audit(confirmed(code), installed));
		report(findings);
		return findings;
	}

	/**
	 * The judgement of the listeners mods register in code for events no bridge delivers, once loading has completed on
	 * this side (every mod's setup has run, so every registration that will happen has): from the end of the setup
	 * lifecycle, on a dedicated server and on a client alike — a client that only ever joins a remote server never runs
	 * {@link #judgePending()}.
	 */
	public static void judgeCodeListeners() {
		judgeCodeListeners(EventBridges.satisfied());
	}

	static List<Finding> judgeCodeListeners(Set<GameEventBridge> installed) {
		if (!enabled()) return List.of();
		Map<String, Set<String>> code = new LinkedHashMap<>();
		synchronized (PENDING) {
			for (var e : PENDING_CODE.entrySet()) code.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
			PENDING_CODE.clear();
		}
		List<Finding> findings = audit(confirmed(code), installed);
		report(findings);
		return findings;
	}

	/**
	 * Of {@code code} (mod → events registered in code), what can have been registered on this side: a class that registers
	 * it has loaded (or none was recorded), and the event's own bus has a listener now, or cannot be asked. Two mods
	 * registering for one event share its bus, so its answer is asked once; the loaded class is what is this mod's own.
	 */
	private static Map<String, Set<String>> confirmed(Map<String, Set<String>> code) {
		java.util.function.Function<String, Boolean> onBus;
		java.util.function.Predicate<String> loaded;
		Map<String, Map<String, Set<String>>> registrars = new LinkedHashMap<>();
		synchronized (PENDING) {
			onBus = LISTENERS_ON_BUS[0];
			loaded = LOADED[0];
			REGISTRARS.forEach((mod, byEvent) -> registrars.put(mod, new LinkedHashMap<>(byEvent)));
		}
		Map<String, Boolean> answers = new java.util.HashMap<>();
		Map<String, Set<String>> confirmed = new LinkedHashMap<>();
		code.forEach((mod, events) -> {
			for (String event : events) {
				Set<String> classes = registrars.getOrDefault(mod, Map.of()).getOrDefault(event, Set.of());
				if (!classes.isEmpty() && classes.stream().noneMatch(loaded)) continue;
				if (!answers.containsKey(event)) answers.put(event, onBus.apply(event));
				if (Boolean.FALSE.equals(answers.get(event))) continue;
				confirmed.computeIfAbsent(mod, k -> new LinkedHashSet<>()).add(event);
			}
		});
		return confirmed;
	}

	static void resetPending() {
		synchronized (PENDING) {
			PENDING.clear();
			PENDING_CODE.clear();
			PENDING_CODE_BRIDGED.clear();
			REGISTRARS.clear();
			LISTENERS_ON_BUS[0] = event -> null;
			LOADED[0] = type -> true;
		}
	}

	private static void report(List<Finding> findings) {
		if (findings.isEmpty()) return;

		Map<String, Set<String>> modsByEvent = new LinkedHashMap<>();
		Map<String, String> costByEvent = new LinkedHashMap<>();
		for (Finding finding : findings) {
			modsByEvent.computeIfAbsent(finding.event(), k -> new LinkedHashSet<>()).add(finding.modId());
			costByEvent.putIfAbsent(finding.event(), (finding.partial() ? "(partly posted) " : "") + finding.cost());
		}

		// The Mods screen and load-report.txt read the catalogue; a listener keyed by class name (no owning mod
		// was found beside it) reaches mark() with a name no row carries, which invents nothing.
		for (Finding finding : findings) {
			ModCatalog.mark(finding.modId(), ModCatalog.Status.DEGRADED, "it listens for "
					+ finding.event().substring(finding.event().lastIndexOf('/') + 1).replace('$', '.')
					+ (finding.partial() ? ", which this merged game posts only on some of its paths — "
							: ", which this merged game never posts — ") + finding.cost());
		}
		ForbricLog.warn("[Forbric/DeadEvents] %d Forge-family game event(s) the merged base no longer posts, or posts on "
				+ "only some of its paths, have listeners waiting on them. Those listeners will not run (or run on those "
				+ "paths only), and their mods are marked on the Mods screen.", modsByEvent.size());
		for (Map.Entry<String, Set<String>> entry : modsByEvent.entrySet()) {
			ForbricLog.warn("[Forbric/DeadEvents]   %s — %s (waiting: %s)",
					entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1),
					costByEvent.get(entry.getKey()), entry.getValue());
		}
	}
}
