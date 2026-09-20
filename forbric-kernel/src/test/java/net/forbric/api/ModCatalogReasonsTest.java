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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a row's reason text says when the same mod is marked more than once.
 *
 * <p>Two things go wrong here and only one of them was covered. A row must carry a SECOND distinct reason — a
 * mod whose mixin was left out and whose deferred task then threw has two things wrong with it — and it must not
 * carry the same reason twice. The dedup compared the whole incoming text to each existing clause, so a reason
 * that was ITSELF several clauses (one repair naming two things it could not do) never matched, and arriving
 * twice printed it twice. fabric-item-api's tooltip row read that way on the Mods screen and in the load report.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class ModCatalogReasonsTest {
	private static final String TOOLTIPS = "tooltip provider ordering is decided by NeoForge's ItemTooltipHandler "
			+ "on this base; ItemComponentTooltipProviderRegistry entries are recorded but not applied";

	private List<ModCatalog.Entry> previous;

	@BeforeEach
	void publish() {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "itemapi", "Item API", "1", "",
				List.of(), "item-api.jar", "", "")));
	}

	@AfterEach
	void forget() {
		ModCatalog.publish(previous);
	}

	@Test
	void aMultiClauseReasonArrivingTwiceIsRecordedOnce() {
		ModCatalog.mark("itemapi", ModCatalog.Status.DEGRADED, TOOLTIPS);
		ModCatalog.mark("itemapi", ModCatalog.Status.DEGRADED, TOOLTIPS);

		assertEquals(TOOLTIPS, detail(), "the same reason twice is one reason, however many clauses it has");
	}

	@Test
	void aSecondDistinctReasonIsStillKept() {
		ModCatalog.mark("itemapi", ModCatalog.Status.DEGRADED, "its mixin A did not apply");
		ModCatalog.mark("itemapi", ModCatalog.Status.DEGRADED, "its deferred task threw");

		assertEquals("its mixin A did not apply; its deferred task threw", detail(),
				"two things wrong is two reasons — dropping the second would be the opposite bug");
	}

	/** The overlapping case: a reason that repeats one clause of an existing one adds only the new clause. */
	@Test
	void onlyTheClausesThatAreNewAreAdded() {
		ModCatalog.mark("itemapi", ModCatalog.Status.DEGRADED, "a; b");
		ModCatalog.mark("itemapi", ModCatalog.Status.DEGRADED, "b; c");

		assertEquals("a; b; c", detail());
	}

	private static String detail() {
		return ModCatalog.failures().stream().filter(e -> e.modId().equals("itemapi")).findFirst()
				.orElseThrow().statusDetail();
	}
}
