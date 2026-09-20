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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	private static final String CHAT = "net/minecraftforge/event/ServerChatEvent";
	private static final String COMMANDS = "net/minecraftforge/event/RegisterCommandsEvent";
	private static final String DEATH = "net/minecraftforge/event/entity/living/LivingDeathEvent";
	private static final String ALIVE = "net/minecraftforge/event/entity/living/LivingEvent$LivingTickEvent";
	private static final String KEYS = "net/minecraftforge/client/event/RegisterKeyMappingsEvent";
	private static final String TABS = "net/minecraftforge/event/BuildCreativeModeTabContentsEvent";
	private static final String OVERLAYS = "net/minecraftforge/client/event/AddGuiOverlayLayersEvent";
	private static final String CREATE_FLUID_SOURCE = "net/minecraftforge/event/level/BlockEvent$CreateFluidSourceEvent";
	private static final String FLUID_PLACE_BLOCK = "net/minecraftforge/event/level/BlockEvent$FluidPlaceBlockEvent";
	private static final String NEO_TOOLTIP = "net/neoforged/neoforge/event/entity/player/ItemTooltipEvent";
	private static final String ENTITY_PLACE = "net/minecraftforge/event/level/BlockEvent$EntityPlaceEvent";

	@Test
	void aNeoForgeTooltipListenerIsAFinding() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("tipmod", Set.of(NEO_TOOLTIP)), EnumSet.allOf(GameEventBridge.class));
		assertEquals(1, findings.size(), "the merged getTooltipLines calls only MinecraftForge's onItemTooltip");
		assertEquals("tipmod", findings.get(0).modId());
		assertTrue(findings.get(0).cost().contains("tooltip"), findings.get(0).cost());
	}

	@Test
	void aForgeEntityPlaceListenerIsAFinding() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("logmod", Set.of(ENTITY_PLACE)), EnumSet.allOf(GameEventBridge.class));
		assertEquals(1, findings.size(), "routePlaceItemHookToNeoForge's stated cost");
		assertTrue(findings.get(0).cost().contains("placement"), findings.get(0).cost());
	}

	@Test
	void aBridgeableEventIsJudgedOnceTheServerIsUpNotAtConstruction() {
		List<net.forbric.api.ModCatalog.Entry> previous = net.forbric.api.ModCatalog.everything();
		try {
			DeadEventAudit.resetPending();
			net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(
					net.forbric.api.Ecosystem.FORGE, "cmdmod", "Cmd", "1", "", List.of(), "cmd.jar", "", "")));
			DeadEventAudit.report(Map.of("cmdmod", Set.of(COMMANDS)));
			assertTrue(net.forbric.api.ModCatalog.failures().isEmpty(),
					"Commands loads after mod construction on a dedicated server; judging now marked every mod with a command");
			assertEquals(List.of(), DeadEventAudit.judgePending(EnumSet.of(GameEventBridge.REGISTER_COMMANDS)),
					"the bridge installed by the time the server was up: nothing to say");
			assertTrue(net.forbric.api.ModCatalog.failures().isEmpty());

			DeadEventAudit.report(Map.of("cmdmod", Set.of(COMMANDS)));
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
			subscribed.put("claimmod", Set.of(BREAK));
			subscribed.put("a.b.OrphanSubscriber", Set.of(BREAK));
			DeadEventAudit.report(subscribed);
			assertEquals(1, net.forbric.api.ModCatalog.failures().size(), "the class-keyed listener invents no row");
			net.forbric.api.ModCatalog.Entry claim = net.forbric.api.ModCatalog.failures().get(0);
			assertEquals("claimmod", claim.modId());
			assertEquals(net.forbric.api.ModCatalog.Status.DEGRADED, claim.status());
			assertTrue(claim.statusDetail().contains("BlockEvent.BreakEvent") && claim.statusDetail().contains("protect"), claim.statusDetail());
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

	/** The five client events no bridge carries yet: a mod waiting on one is named, with what it loses. */
	@Test
	void aStillDeadClientEventNamesTheMod() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("hudmod", Set.of(OVERLAYS)), EnumSet.noneOf(GameEventBridge.class));

		assertEquals(1, findings.size());
		assertEquals("hudmod", findings.get(0).modId());
		assertTrue(findings.get(0).cost().contains("never draw"), findings.get(0).cost());
	}

	/** The two fluid events the fluid-rendering repair deliberately does not bridge: a listener is told, per event. */
	@Test
	void theTwoDeadFluidEventsNameTheModWithTheirCost() {
		List<DeadEventAudit.Finding> findings = DeadEventAudit.audit(
				Map.of("fluidmod", Set.of(CREATE_FLUID_SOURCE, FLUID_PLACE_BLOCK)), EnumSet.noneOf(GameEventBridge.class));

		assertEquals(2, findings.size(), "both fluid events must be findings");
		for (DeadEventAudit.Finding finding : findings) {
			assertEquals("fluidmod", finding.modId());
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
