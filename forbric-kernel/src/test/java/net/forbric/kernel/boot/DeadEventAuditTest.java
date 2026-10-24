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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.forbric.api.GameEventBridge;

/**
 * The audit that turns a silent absence into a line.
 *
 * <p>Every bug in this class looks identical from outside: the mod loads, reports nothing wrong, registers its
 * listener, and the listener never runs. The whole value of the audit is that it is printed BEFORE the symptom,
 * so what is tested here is the judgement — which subscriptions are a finding, and which are not because a bridge
 * now carries them.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class DeadEventAuditTest {
	private static final String BREAK = "net/minecraftforge/event/level/BlockEvent$BreakEvent";
	/**
	 * An event with NO bridge, for the two cases that need one.
	 *
	 * <p>{@code BREAK} stopped being that the day {@link GameEventBridge#BLOCK_BREAK} landed: an event the kernel
	 * bridges is reported through the PENDING path instead, judged once the server is up, so a test written on it
	 * was silently testing the other half. Chat has no bridge and the merged base posts only NeoForge's decorator.
	 */
	private static final String CHAT = "net/minecraftforge/event/ServerChatEvent";
	private static final String COMMANDS = "net/minecraftforge/event/RegisterCommandsEvent";
	private static final String DEATH = "net/minecraftforge/event/entity/living/LivingDeathEvent";
	private static final String ALIVE = "net/minecraftforge/event/entity/living/LivingEvent$LivingTickEvent";
	private static final String KEYS = "net/minecraftforge/client/event/RegisterKeyMappingsEvent";
	private static final String TABS = "net/minecraftforge/event/BuildCreativeModeTabContentsEvent";
	/**
	 * A CLIENT event with no bridge that the merged game still never posts.
	 *
	 * <p>It used to be {@code AddGuiOverlayLayersEvent}; that one is bridged now, and a bridged event is reported
	 * through the pending path instead, so a test written on it would silently be testing the other half. Then it was
	 * {@code AddFramePassEvent}, until the merge kept both families' edits in Minecraft's constructor and posted it again.
	 */
	private static final String SKULLS = "net/minecraftforge/client/event/EntityRenderersEvent$CreateSkullModels";
	private static final String CREATE_FLUID_SOURCE = "net/minecraftforge/event/level/BlockEvent$CreateFluidSourceEvent";
	private static final String FLUID_PLACE_BLOCK = "net/minecraftforge/event/level/BlockEvent$FluidPlaceBlockEvent";
	private static final String NEO_TOOLTIP = "net/neoforged/neoforge/event/entity/player/ItemTooltipEvent";
	private static final String ENTITY_PLACE = "net/minecraftforge/event/level/BlockEvent$EntityPlaceEvent";

	/**
	 * Tooltips are bridged now, and by a pass that lands only when a player hovers an item.
	 *
	 * <p>So a NeoForge tooltip listener is never a FINDING: "the bridge has not fired yet" and "the bridge is
	 * missing" are the same observation until someone hovers something, and accusing the mod on either is a false
	 * alarm. What must survive is the sentence — the cost a player would pay if the bridge really were gone is
	 * what names the mod when the bridges are switched off, and a bridge that carries no cost explains nothing.
	 */
	@Test
	void aNeoForgeTooltipListenerIsNeverAccusedOnceItsBridgeExists() {
		assertTrue(DeadEventAudit.audit(Map.of("tipmod", Set.of(NEO_TOOLTIP)),
				EnumSet.noneOf(GameEventBridge.class)).isEmpty(),
				"an on-demand bridge has no moment at which its absence is provable, so this must not be a finding");
		assertTrue(DeadEventAudit.audit(Map.of("tipmod", Set.of(NEO_TOOLTIP)),
				EnumSet.allOf(GameEventBridge.class)).isEmpty(),
				"and with the bridge installed the listener runs");

		assertTrue(GameEventBridge.ITEM_TOOLTIP.cost().contains("tooltip"),
				GameEventBridge.ITEM_TOOLTIP.cost());
	}

	/**
	 * Placing a block is bridged now, so it is a finding only when the bridge is NOT installed.
	 *
	 * <p>Both halves, because each alone passes on a broken audit: the row must still exist and still say what a
	 * player loses — that is what names the mod when someone turns the bridges off — and it must stop being
	 * reported the moment the bridge is there, or the Mods screen marks a mod degraded for a gap that is closed.
	 */
	@Test
	void aForgeEntityPlaceListenerIsAFindingOnlyWithoutItsBridge() {
		List<DeadEventAudit.Finding> unbridged = DeadEventAudit.audit(
				Map.of("logmod", Set.of(ENTITY_PLACE)), EnumSet.noneOf(GameEventBridge.class));
		assertEquals(1, unbridged.size(), "routePlaceItemHookToNeoForge's stated cost");
		// The cost now comes from the BRIDGE, not the dead row: an event with a bridge that did not install is
		// reported with the bridge's own sentence, which is the one that says what this particular mod loses.
		assertTrue(unbridged.get(0).cost().contains("refuse a block being placed"), unbridged.get(0).cost());

		assertTrue(DeadEventAudit.audit(Map.of("logmod", Set.of(ENTITY_PLACE)),
				EnumSet.allOf(GameEventBridge.class)).isEmpty(),
				"with the bridge installed the listener runs, so naming its mod would be a false alarm");
	}

	@Test
	void aBridgeableEventIsJudgedOnceTheServerIsUpNotAtConstruction() {
		List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			DeadEventAudit.resetPending();
			net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(
					net.forbric.api.Ecosystem.FORGE, "cmdmod", "Cmd", "1", "", List.of(), "cmd.jar", "", "")));
			DeadEventAudit.report(Map.of("cmdmod", Set.of(COMMANDS)), net.forbric.api.Side.DEDICATED_SERVER);
			assertTrue(net.forbric.api.ModCatalog.failures().isEmpty(),
					"Commands loads after mod construction on a dedicated server; judging now marked every mod with a command");
			assertEquals(List.of(), DeadEventAudit.judgePending(EnumSet.of(GameEventBridge.REGISTER_COMMANDS)),
					"the bridge installed by the time the server was up: nothing to say");
			assertTrue(net.forbric.api.ModCatalog.failures().isEmpty());

			DeadEventAudit.report(Map.of("cmdmod", Set.of(COMMANDS)), net.forbric.api.Side.DEDICATED_SERVER);
			List<DeadEventAudit.Finding> still = DeadEventAudit.judgePending(EnumSet.noneOf(GameEventBridge.class));
			assertEquals(1, still.size(), "a bridge STILL absent once the server is up is the real finding");
			assertEquals("cmdmod", net.forbric.api.ModCatalog.failures().get(0).modId());
			assertTrue(DeadEventAudit.judgePending(EnumSet.noneOf(GameEventBridge.class)).isEmpty(), "judged once");
		} finally {
			DeadEventAudit.resetPending();
			net.forbric.api.ModCatalog.publish(previous);
		}
	}

	@Test
	void aFindingMarksTheModDegradedAndAClassKeyedOneMarksNobody() {
		List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(
					net.forbric.api.Ecosystem.FORGE, "claimmod", "Claim", "1", "", List.of(), "claim.jar", "", "")));
			Map<String, Set<String>> subscribed = new java.util.LinkedHashMap<>();
			subscribed.put("claimmod", Set.of(CHAT));
			subscribed.put("a.b.OrphanSubscriber", Set.of(CHAT));
			DeadEventAudit.report(subscribed, net.forbric.api.Side.DEDICATED_SERVER);
			assertEquals(1, net.forbric.api.ModCatalog.failures().size(), "the class-keyed listener invents no row");
			net.forbric.api.ModCatalog.Entry claim = net.forbric.api.ModCatalog.failures().get(0);
			assertEquals("claimmod", claim.modId());
			assertEquals(net.forbric.api.ModCatalog.Status.DEGRADED, claim.status());
			assertTrue(claim.statusDetail().contains("ServerChatEvent") && claim.statusDetail().contains("chat"),
					claim.statusDetail());
		} finally {
			net.forbric.api.ModCatalog.publish(previous);
		}
	}

	@Test
	void aDeadEventWithAListenerIsReported() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("claimmod", Set.of(BREAK)), EnumSet.noneOf(GameEventBridge.class));

		assertEquals(1, findings.size());
		assertEquals("claimmod", findings.get(0).modId());
		assertEquals(BREAK, findings.get(0).event());
		assertTrue(findings.get(0).cost().contains("protect"),
				"the finding must say what the player loses, not just that an event is dead — that sentence is "
						+ "the difference between a diagnosis and a count");
	}

	/**
	 * The point of consulting the live bridge set rather than deleting entries: the day a bridge lands, the event
	 * stops being a finding without anyone having to remember to edit the dead list.
	 */
	@Test
	void anEventThatIsNowBridgedIsNotAFinding() {
		Map<String, Set<String>> subscribed = Map.of("somemod", Set.of(COMMANDS, DEATH));

		assertTrue(DeadEventAudit.audit(subscribed,
						EnumSet.of(GameEventBridge.REGISTER_COMMANDS, GameEventBridge.LIVING_DEATH)).isEmpty(),
				"a bridged event arrives, so a listener on it is not waiting for anything");
	}

	/** And the inverse, which is the case that matters when a bridge fails to install on some runtime. */
	@Test
	void aBridgeableEventIsAFindingWhenItsBridgeDidNotInstall() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("somemod", Set.of(DEATH)), EnumSet.noneOf(GameEventBridge.class));

		assertEquals(1, findings.size(),
				"if the bridge did not install, the listener really is waiting for an event that will not come, "
						+ "and that is exactly when the line is worth printing");
	}

	/**
	 * The client-init and registration bridges are landed by class transformers in classes the game defines AFTER
	 * this audit runs, so "not installed yet" is the normal state here and must not become a finding — the pass's
	 * own verify line is what names one that stood down.
	 */
	@Test
	void aLatePassBridgeIsNotAFindingBeforeItsPassRuns() {
		assertTrue(DeadEventAudit.audit(Map.of("keymod", Set.of(KEYS), "tabmod", Set.of(TABS)),
						EnumSet.noneOf(GameEventBridge.class)).isEmpty(),
				"a late pass has not run when the audit does; reporting it would name every client mod on every boot");
	}

	/**
	 * MinecraftForge's drown event is bridged now: a finding only when the bridge did not install, with the bridge's own
	 * sentence. Until 2026-10-24 it was in no table at all, so a mod waiting on it was never named either way.
	 */
	@Test
	void aForgeDrownListenerIsAFindingOnlyWithoutItsBridge() {
		String drown = "net/minecraftforge/event/entity/living/LivingDrownEvent";
		assertEquals(GameEventBridge.LIVING_DROWN, DeadEventAudit.BRIDGED.get(drown));
		List<DeadEventAudit.Finding> unbridged = DeadEventAudit.audit(Map.of("gillmod", Set.of(drown)), EnumSet.noneOf(GameEventBridge.class));
		assertEquals(1, unbridged.size());
		assertTrue(unbridged.get(0).cost().contains("drowning"), unbridged.get(0).cost());
		assertTrue(DeadEventAudit.audit(Map.of("gillmod", Set.of(drown)), EnumSet.of(GameEventBridge.LIVING_DROWN)).isEmpty());
	}

	/**
	 * A client event the merged game never posts is judged on the client only. A dedicated server never posts it under
	 * either family, so a listener there waits for nothing natively too, and naming its mod would be a false accusation.
	 */
	@Test
	void aDeadClientEventIsAFindingOnTheClientOnly() {
		List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(
					net.forbric.api.Ecosystem.FORGE, "skullmod", "Skull", "1", "", List.of(), "skull.jar", "", "")));
			DeadEventAudit.report(Map.of("skullmod", Set.of(SKULLS)), net.forbric.api.Side.DEDICATED_SERVER);
			assertTrue(net.forbric.api.ModCatalog.failures().isEmpty(), "a dedicated server never posts a client event natively either");
			DeadEventAudit.report(Map.of("skullmod", Set.of(SKULLS)), net.forbric.api.Side.CLIENT);
			assertEquals(1, net.forbric.api.ModCatalog.failures().size());
			assertEquals("skullmod", net.forbric.api.ModCatalog.failures().get(0).modId());
			assertTrue(DeadEventAudit.CLIENT_ONLY.contains(SKULLS));
			assertFalse(DeadEventAudit.CLIENT_ONLY.contains(CHAT));
		} finally {
			net.forbric.api.ModCatalog.publish(previous);
		}
	}

	/**
	 * The side is judged by where MinecraftForge posts the event, not by its package. {@code LeftClickEmpty} lives in the
	 * common {@code event/entity/player} package and is posted natively from {@code Minecraft} alone; judged by package it
	 * named a mod DEGRADED on every dedicated server for a click no server announces.
	 */
	@Test
	void aCommonPackageEventPostedOnlyOnTheClientIsJudgedOnTheClientOnly() {
		String leftClickEmpty = "net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickEmpty";
		assertTrue(DeadEventAudit.DEAD.containsKey(leftClickEmpty));
		Map<String, Set<String>> deadNow = new java.util.LinkedHashMap<>(), bridgeable = new java.util.LinkedHashMap<>();
		DeadEventAudit.split(Map.of("clickmod", Set.of(leftClickEmpty, CHAT)), net.forbric.api.Side.DEDICATED_SERVER, deadNow, bridgeable);
		assertEquals(Map.of("clickmod", Set.of(CHAT)), deadNow, "a dedicated server never announces a click at nothing natively");
		deadNow.clear();
		DeadEventAudit.split(Map.of("clickmod", Set.of(leftClickEmpty)), net.forbric.api.Side.CLIENT, deadNow, bridgeable);
		assertEquals(Map.of("clickmod", Set.of(leftClickEmpty)), deadNow, "the client does announce it natively");
		assertEquals(Map.of(), bridgeable);
	}

	/**
	 * An event the merged game never posts is posted in a pack where a guest posts it (Tombstone's phantom spawner calls
	 * ForgeEventFactory.onPlayerSpawnPhantom), so a mod listening for it there is not accused. The guests are asked only
	 * about the DEAD events someone listens for, and a pack where no guest posts one is reported as before.
	 */
	@Test
	void anEventAGuestPostsIsNotReported() {
		String phantoms = "net/minecraftforge/event/entity/player/PlayerSpawnPhantomsEvent";
		assertTrue(DeadEventAudit.DEAD.containsKey(phantoms));
		List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(
					net.forbric.api.Ecosystem.FORGE, "nightmod", "Night", "1", "", List.of(), "night.jar", "", "")));
			List<Set<String>> asked = new ArrayList<>();
			Map<String, Set<String>> subscribed = Map.of("nightmod", Set.of(phantoms, CHAT, COMMANDS));
			DeadEventAudit.report(subscribed, net.forbric.api.Side.DEDICATED_SERVER, events -> {
				asked.add(Set.copyOf(events));
				return Map.of(phantoms, Set.of("tombstone.jar"));
			});
			assertEquals(List.of(Set.of(phantoms, CHAT)), asked, "asked about the DEAD events listened for, and nothing else");
			assertEquals(1, net.forbric.api.ModCatalog.failures().size());
			assertTrue(net.forbric.api.ModCatalog.failures().get(0).statusDetail().contains("ServerChatEvent"),
					net.forbric.api.ModCatalog.failures().get(0).statusDetail());
			assertFalse(net.forbric.api.ModCatalog.failures().get(0).statusDetail().contains("PlayerSpawnPhantomsEvent"));

			net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(
					net.forbric.api.Ecosystem.FORGE, "nightmod", "Night", "1", "", List.of(), "night.jar", "", "")));
			DeadEventAudit.report(Map.of("nightmod", Set.of(phantoms)), net.forbric.api.Side.DEDICATED_SERVER, events -> Map.of());
			assertEquals(1, net.forbric.api.ModCatalog.failures().size(), "no guest posts it: the listener is reported");
			assertTrue(net.forbric.api.ModCatalog.failures().get(0).statusDetail().contains("PlayerSpawnPhantomsEvent"));

			asked.clear();
			DeadEventAudit.report(Map.of("nightmod", Set.of(COMMANDS)), net.forbric.api.Side.DEDICATED_SERVER, events -> {
				asked.add(Set.copyOf(events));
				return Map.of();
			});
			assertEquals(List.of(), asked, "no DEAD event is listened for: the guests are not scanned at all");
		} finally {
			DeadEventAudit.resetPending();
			net.forbric.api.ModCatalog.publish(previous);
		}
	}

	/** A client event no bridge carries and the merged game never posts: a mod waiting on one is named, with its cost. */
	@Test
	void aStillDeadClientEventNamesTheMod() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("skullmod", Set.of(SKULLS)), EnumSet.noneOf(GameEventBridge.class));

		assertEquals(1, findings.size());
		assertEquals("skullmod", findings.get(0).modId());
		assertTrue(findings.get(0).cost().contains("never render"), findings.get(0).cost());
	}

	/**
	 * What the stand-down looks for: the MinecraftForge events a bridge on one of NeoForge's buses re-posts. A row landed
	 * by a class transformer forwards from no NeoForge event, so it is not in it; entity tracking and the level tick are,
	 * now that their bridges are rows, and so are the two forwards on the client mod bus.
	 */
	@Test
	void theForwardedSetIsTheGameBusBridgesMinecraftForgeEvents() {
		Set<String> forwarded = DeadEventAudit.forwardedForgeEvents();
		assertTrue(forwarded.contains(DEATH));
		assertTrue(forwarded.contains("net/minecraftforge/event/entity/player/PlayerEvent$StartTracking"));
		assertTrue(forwarded.contains("net/minecraftforge/event/entity/player/PlayerEvent$StopTracking"));
		assertTrue(forwarded.contains("net/minecraftforge/event/TickEvent$LevelTickEvent$Pre"));
		assertFalse(forwarded.contains(KEYS), "client-init rows are landed by a transformer, not forwarded from NeoForge");
		assertFalse(forwarded.contains(TABS), "registration rows likewise");
		for (String event : forwarded) {
			GameEventBridge bridge = DeadEventAudit.BRIDGED.containsKey(event) ? DeadEventAudit.BRIDGED.get(event) : DeadEventAudit.STAND_DOWN.get(event);
			assertTrue(DeadEventAudit.forgeEventsOf(bridge).contains(event), event + " must be guarded on " + bridge);
		}
		assertEquals(Set.of("net/minecraftforge/event/TickEvent$ServerTickEvent$Pre"), DeadEventAudit.forgeEventsOf(GameEventBridge.SERVER_TICK_PRE),
				"the server tick's bridge stands down for exactly its own event");
		assertEquals(Set.of("net/minecraftforge/client/event/TextureStitchEvent$Post"), DeadEventAudit.forgeEventsOf(GameEventBridge.TEXTURE_STITCHED),
				"a forward on the mod bus stands down for exactly its own event too");
		assertEquals(Set.of("net/minecraftforge/client/event/ModelEvent$BakingCompleted"), DeadEventAudit.forgeEventsOf(GameEventBridge.MODELS_BAKED));
		assertEquals(Set.of(), DeadEventAudit.forgeEventsOf(GameEventBridge.CLIENT_RELOAD_LISTENERS),
				"the reload-listener bridge hands MinecraftForge's own registrations to NeoForge's graph and posts nothing");
	}

	/**
	 * Every bridge on NeoForge's game bus can stand down. A bridge whose events are in neither table is handed NeoForge's
	 * bus unguarded and its events are never looked for, so wherever a merged method calls its MinecraftForge hook natively
	 * beside NeoForge's, a mod receives the event twice — measured on an edit-spliced base for the server, player, client
	 * and render ticks and a dozen more, all bridges no row named; and on the client mod bus, each atlas stitched and the
	 * models baked. The portal is the one exception on the game bus, composed by {@code PortalSpawnInjector} instead;
	 * the reload-listener bridge is the one on the mod bus, and posts no event of its own.
	 */
	@Test
	void everyBridgeOnTheGameBusHasEventsItCanStandDownFor() {
		List<GameEventBridge> unguarded = new ArrayList<>();
		for (GameEventBridge bridge : GameEventBridge.values()) {
			boolean onABus = bridge.pass() == GameEventBridge.Pass.GAME_BUS || bridge.pass() == GameEventBridge.Pass.CLIENT_GAME_BUS
					|| bridge.pass() == GameEventBridge.Pass.CLIENT_MOD_BUS;
			if (onABus && bridge != GameEventBridge.PORTAL_SPAWN && bridge != GameEventBridge.CLIENT_RELOAD_LISTENERS
					&& DeadEventAudit.forgeEventsOf(bridge).isEmpty()) unguarded.add(bridge);
		}
		assertEquals(List.of(), unguarded, "bridges that can never stand down for the game's own delivery of their event");
		assertEquals(Set.of(), DeadEventAudit.forgeEventsOf(GameEventBridge.PORTAL_SPAWN), "the portal's composition is its injector's");
	}

	/**
	 * A stand-down row is judged by its bridge, as a bridged row is: its bridge delivers the event, and only stands down
	 * where the merged game posts it natively. So a listener is a finding exactly while that bridge is missing — with the
	 * bridge's own cost — and never while it is installed, not even for an event that is also partly posted
	 * ({@code LevelEvent$Unload}: its bridge forwards the server-stop path the merge took, and naming its listeners "partly
	 * posted" was a false accusation). On a dedicated server a client bridge's event is not judged at all: it never
	 * installs there and its event is a client's. No event is in both bridge tables.
	 */
	@Test
	void standDownRowsAreJudgedByTheirBridge() {
		Set<String> both = new java.util.TreeSet<>(DeadEventAudit.STAND_DOWN.keySet());
		both.retainAll(DeadEventAudit.BRIDGED.keySet());
		assertEquals(Set.of(), both, "an event in both tables");
		String unload = "net/minecraftforge/event/level/LevelEvent$Unload";
		assertTrue(DeadEventAudit.PARTIAL.containsKey(unload), "the level unload is a partly-posted row as well");
		Map<String, Set<String>> subscribed = Map.of("somemod", DeadEventAudit.STAND_DOWN.keySet());
		Set<GameEventBridge> all = EnumSet.allOf(GameEventBridge.class);
		assertEquals(List.of(), DeadEventAudit.audit(subscribed, all), "every bridge installed, yet a stand-down row is a finding");
		List<DeadEventAudit.Finding> missing = DeadEventAudit.audit(subscribed, Set.of());
		for (DeadEventAudit.Finding finding : missing) {
			GameEventBridge bridge = DeadEventAudit.STAND_DOWN.get(finding.event());
			assertEquals(bridge.cost(), finding.cost(), "a missing bridge is reported with its own cost: " + finding);
			assertFalse(finding.partial(), finding.event());
		}
		assertTrue(missing.stream().anyMatch(f -> f.event().equals(unload)), "the level-unload bridge missing is a finding");
		Map<String, Set<String>> deadNow = new java.util.LinkedHashMap<>(), bridgeable = new java.util.LinkedHashMap<>();
		DeadEventAudit.split(Map.of("somemod", Set.of(unload, "net/minecraftforge/client/event/ClientChatEvent")),
				net.forbric.api.Side.DEDICATED_SERVER, deadNow, bridgeable);
		assertEquals(Map.of("somemod", Set.of(unload)), bridgeable, "a game-bus stand-down bridge is judged once the server is "
				+ "up; a client bridge's event is not judged on a dedicated server");
		assertEquals(Map.of(), deadNow);
	}

	/**
	 * The sound events: never posted natively on the merged base, so each is a bridged row with a bridge of its own. A
	 * listener is a finding exactly while its own bridge is missing, and is guarded by its own bridge only: one bridge
	 * for the pair would let a native entity sound stand down a position sound's forward.
	 */
	@Test
	void eachSoundEventIsBridgedOnItsOwnAndNamedOnlyWhileItsBridgeIsMissing() {
		String atEntity = "net/minecraftforge/event/PlayLevelSoundEvent$AtEntity";
		String atPosition = "net/minecraftforge/event/PlayLevelSoundEvent$AtPosition";
		assertFalse(DeadEventAudit.DEAD.containsKey(atEntity), "a bridged event is not also claimed dead");
		assertFalse(DeadEventAudit.DEAD.containsKey(atPosition));
		assertEquals(GameEventBridge.PLAY_SOUND_AT_ENTITY, DeadEventAudit.BRIDGED.get(atEntity));
		assertEquals(GameEventBridge.PLAY_SOUND_AT_POSITION, DeadEventAudit.BRIDGED.get(atPosition));
		assertEquals(Set.of(atEntity), DeadEventAudit.forgeEventsOf(GameEventBridge.PLAY_SOUND_AT_ENTITY));
		assertEquals(Set.of(atPosition), DeadEventAudit.forgeEventsOf(GameEventBridge.PLAY_SOUND_AT_POSITION));
		assertTrue(DeadEventAudit.forwardedForgeEvents().containsAll(Set.of(atEntity, atPosition)),
				"the stand-down looks for both on a NeoForge delivery's path");

		Map<String, Set<String>> subscribed = Map.of("soundmod", Set.of(atEntity, atPosition));
		assertTrue(DeadEventAudit.audit(subscribed,
						EnumSet.of(GameEventBridge.PLAY_SOUND_AT_ENTITY, GameEventBridge.PLAY_SOUND_AT_POSITION)).isEmpty(),
				"both bridges in: the mod is asked about every sound");
		List<DeadEventAudit.Finding> half = DeadEventAudit.audit(subscribed, EnumSet.of(GameEventBridge.PLAY_SOUND_AT_ENTITY));
		assertEquals(1, half.size(), "only the event whose bridge did not install is waiting: " + half);
		assertEquals(atPosition, half.get(0).event());
		assertEquals(GameEventBridge.PLAY_SOUND_AT_POSITION.cost(), half.get(0).cost());
	}

	/** The two fluid events the fluid-rendering repair deliberately does not bridge: a listener is told, per event. */
	@Test
	void theDeadFluidEventNamesTheModWithItsCostAndThePartlyPostedOneSaysSo() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("fluidmod", Set.of(CREATE_FLUID_SOURCE, FLUID_PLACE_BLOCK)), EnumSet.noneOf(GameEventBridge.class));

		// FLUID_PLACE_BLOCK left this table once the census looked in the carriers: MinecraftForge's own
		// FluidInteractionRegistry still posts it, for what the fluid-interaction rules place. Once every hook class was
		// measured against MinecraftForge's own game it came back as partly posted: LavaFluid's three call sites (lava
		// turning water to stone as it flows, lava setting fire) are gone, and that is worded as partial, not as dead.
		assertEquals(2, findings.size(), "both are findings, each in its own words: " + findings);
		Map<String, DeadEventAudit.Finding> byEvent = new java.util.HashMap<>();
		for (DeadEventAudit.Finding f : findings) byEvent.put(f.event(), f);
		assertEquals("fluidmod", byEvent.get(CREATE_FLUID_SOURCE).modId());
		assertFalse(byEvent.get(CREATE_FLUID_SOURCE).partial(), "the source event is never posted");
		assertTrue(byEvent.get(FLUID_PLACE_BLOCK).partial(), "the placement event is posted on some paths");
		for (DeadEventAudit.Finding finding : findings) {
			assertTrue(finding.cost() != null && !finding.cost().isBlank(), finding.event() + " must state its cost");
		}
	}

	@Test
	void anEventTheMergedBaseStillPostsIsNotAFinding() {
		assertTrue(DeadEventAudit.audit(Map.of("tickmod", Set.of(ALIVE)),
						EnumSet.noneOf(GameEventBridge.class)).isEmpty(),
				"LivingEntity still calls ForgeEventFactory.onLivingTick, so that listener runs — reporting it "
						+ "would train the reader to ignore the whole audit");
	}

	@Test
	void everyModWaitingOnTheSameDeadEventIsNamed() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("a", Set.of(CHAT), "b", Set.of(CHAT)), EnumSet.noneOf(GameEventBridge.class));

		assertEquals(2, findings.size(),
				"the same dead event is usually subscribed by several mods, and a reader needs to know which of "
						+ "their features are affected");
	}
}
