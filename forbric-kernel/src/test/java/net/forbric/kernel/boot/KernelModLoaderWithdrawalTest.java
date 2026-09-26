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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * Covers {@link KernelModLoader#keepConstructed} — which containers stay in the mod list after construction.
 *
 * <p>A container left standing for a mod whose constructor threw passes {@code instanceof} and hands out an
 * event bus that nothing will ever post to, so a library mod resolving it registers into nothing and the failure
 * surfaces later somewhere that names neither mod. The MinecraftForge half withdrew; the NeoForge half did not,
 * and both now go through this.
 */
class KernelModLoaderWithdrawalTest {

	@AfterEach
	void clearSwitch() {
		System.clearProperty(KernelModLoader.NEO_TWIN_SWITCH);
	}

	private static Map<String, String> published(String... ids) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String id : ids) out.put(id, "container:" + id);
		return out;
	}

	@Test
	void keepsOnlyWhatConstructed() {
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(
				published("jei", "broken", "jade"), Set.of("jei", "jade"), dropped);

		assertEquals(List.of("jei", "jade"), List.copyOf(kept.keySet()));
		assertEquals(List.of("broken"), dropped);
	}

	@Test
	void keepsPublicationOrder() {
		// The list is handed to setLoadedMods, and order is what decides which mod's registrations run first.
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(
				published("zeta", "alpha", "mid"), Set.of("zeta", "alpha", "mid"), dropped);

		assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(kept.keySet()));
		assertTrue(dropped.isEmpty());
	}

	@Test
	void everyModFailingLeavesNothingStanding() {
		// The case the allowEmpty flag exists for: an empty result must be published, not treated as "no change".
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(published("a", "b"), Set.of(), dropped);

		assertTrue(kept.isEmpty());
		assertEquals(List.of("a", "b"), dropped);
	}

	@Test
	void anIdThatConstructedButWasNeverPublishedChangesNothing() {
		// Presence aliases construct nothing and are re-added by the caller, never carried in here; a stray id in
		// the constructed set must not invent an entry.
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(
				published("jei"), Set.of("jei", "ghost"), dropped);

		assertEquals(List.of("jei"), List.copyOf(kept.keySet()));
		assertTrue(dropped.isEmpty());
	}

	@Test
	void theKeptValuesAreTheOnesThatWerePublished() {
		// Identity matters: the value is the live container, and rebuilding one instead of carrying it across
		// would hand mods a second container for the same mod.
		Map<String, String> input = published("jei");
		List<String> dropped = new ArrayList<>();

		Map<String, String> kept = KernelModLoader.keepConstructed(input, Set.of("jei"), dropped);

		assertEquals(input.get("jei"), kept.get("jei"));
	}

	// --- settleNeo / neoNeedsWithdrawal: one mod id, several @Mod classes -------------------------------------
	//
	// RollingGate ships a common dev.anvilcraft.rg.RollingGate and a dist = CLIENT RollingGateClient under the one
	// id rolling_gate. The common constructor threw; the client class put the id in the other-side set; the id
	// then counted as settled, nothing was withdrawn and the compatibility report said OK.

	@Test
	void aCommonClassThatThrewIsNotCoveredByItsClientOnlyTwinOnAServer() {
		KernelModLoader.NeoSettlement settled =
				KernelModLoader.settleNeo(Set.of("rolling_gate"), Set.of(), Set.of("rolling_gate"));

		assertFalse(settled.kept().contains("rolling_gate"), "nothing of it ran on the server, and something threw");
		assertTrue(KernelModLoader.neoNeedsWithdrawal(Set.of("rolling_gate"), settled.kept()));
		List<String> dropped = new ArrayList<>();
		KernelModLoader.keepConstructed(published("rolling_gate"), settled.kept(), dropped);
		assertEquals(List.of("rolling_gate"), dropped, "withdrawn, which markWithdrawn then reports as FAILED");
	}

	@Test
	void onAClientWhereTheTwinRanTheModStaysButIsDegraded() {
		// One id is one container and one mod bus: RollingGateClient's listeners are on it, so withdrawing
		// rolling_gate would cut the half that did construct.
		KernelModLoader.NeoSettlement settled =
				KernelModLoader.settleNeo(Set.of(), Set.of("rolling_gate"), Set.of("rolling_gate"));

		assertTrue(settled.kept().contains("rolling_gate"));
		assertFalse(KernelModLoader.neoNeedsWithdrawal(Set.of("rolling_gate"), settled.kept()));
		assertEquals(Set.of("rolling_gate"), settled.degraded(), "kept, but not reported as OK");

		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.NEOFORGE, "rolling_gate", "RollingGate",
					"1.1.0", "", List.of(), "RollingGate.jar", "", "")));
			KernelModLoader.markPartlyConstructed(settled.degraded());
			ModCatalog.Entry row = ModCatalog.all().get(0);
			assertEquals(ModCatalog.Status.DEGRADED, row.status(), "the Mods screen and load report must stop saying OK");
			assertEquals("one of its @Mod constructors threw", row.statusDetail());
		} finally {
			ModCatalog.publish(previous);
		}
	}

	@Test
	void aModWhoseOnlyClassIsOtherSideIsKeptAndNotMarked() {
		// sodium_extra on a dedicated server: only a CLIENT @Mod. Installed, simply not run here.
		KernelModLoader.NeoSettlement settled =
				KernelModLoader.settleNeo(Set.of("sodium_extra"), Set.of(), Set.of());

		assertEquals(Set.of("sodium_extra"), settled.kept());
		assertTrue(settled.degraded().isEmpty());
		assertFalse(KernelModLoader.neoNeedsWithdrawal(Set.of("sodium_extra"), settled.kept()));
	}

	@Test
	void aConstructedModWithAClientOnlyTwinIsSimplyKept() {
		// notenoughcrashes on a server: the common class constructs, the client one is other-side. Nothing threw.
		KernelModLoader.NeoSettlement settled = KernelModLoader.settleNeo(Set.of("notenoughcrashes"),
				Set.of("notenoughcrashes"), Set.of());

		assertEquals(Set.of("notenoughcrashes"), settled.kept());
		assertTrue(settled.degraded().isEmpty());
	}

	@Test
	void anUnpublishedOtherSideIdNoLongerCancelsOutAFailure() {
		// "ghost" is other-side but never got a container, so it is not in the published map; "broken" threw.
		// The size check read 1 kept == 1 published and left broken's dead container standing.
		KernelModLoader.NeoSettlement settled =
				KernelModLoader.settleNeo(Set.of("ghost"), Set.of(), Set.of("broken"));

		assertTrue(KernelModLoader.neoNeedsWithdrawal(Set.of("broken"), settled.kept()));
	}

	@Test
	void offSwitchLetsTheClientOnlyTwinMaskTheFailureAgain() {
		System.setProperty(KernelModLoader.NEO_TWIN_SWITCH, "off");

		KernelModLoader.NeoSettlement settled =
				KernelModLoader.settleNeo(Set.of("rolling_gate"), Set.of(), Set.of("rolling_gate"));

		assertTrue(settled.kept().contains("rolling_gate"), "off => the old rule: any other-side class settles the id");
		assertFalse(KernelModLoader.neoNeedsWithdrawal(Set.of("rolling_gate"), settled.kept()));
		assertTrue(settled.degraded().isEmpty());
		assertFalse(KernelModLoader.neoNeedsWithdrawal(Set.of("broken"),
				KernelModLoader.settleNeo(Set.of("ghost"), Set.of(), Set.of("broken")).kept()),
				"off => the old count");
	}
}
