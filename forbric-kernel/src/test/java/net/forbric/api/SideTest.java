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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.forbric.api.UnifiedDependency.SideScope;

/** Covers the one physical-side vocabulary, and its relationship to the dependency scope it is NOT. */
class SideTest {
	/**
	 * The constant names are what gets handed to {@code Enum.valueOf} on a Forge-family {@code Dist}, so they are
	 * an ABI, not a label. Renaming one would seed the wrong dist and be visible only as a mod misbehaving.
	 */
	@Test
	void theDistNamesAreTheForgeFamiliesOwnSpelling() {
		assertEquals("CLIENT", Side.CLIENT.distName());
		assertEquals("DEDICATED_SERVER", Side.DEDICATED_SERVER.distName());
	}

	@Test
	void theIntegratedServerIsNotTheDedicatedOne() {
		assertTrue(Side.CLIENT.isClient());
		assertFalse(Side.DEDICATED_SERVER.isClient());
	}

	/** Both spellings of the server side parse, because this is the seam where Fabric's meets the Forge families'. */
	@Test
	void bothSpellingsOfTheServerSideParse() {
		assertEquals(Side.DEDICATED_SERVER, Side.parse("SERVER"), "Fabric's EnvType spelling");
		assertEquals(Side.DEDICATED_SERVER, Side.parse("DEDICATED_SERVER"), "the Dist spelling");
		assertEquals(Side.CLIENT, Side.parse("client"));
	}

	/**
	 * An unreadable side is {@code null}, never a real one. Every use branches on which half of the game is
	 * running, so guessing would silently run the wrong half.
	 */
	@Test
	void anUnreadableSideIsNullRatherThanADefault() {
		assertNull(Side.parse("holodeck"));
		assertNull(Side.parse(null));
		assertNull(Side.parse(""));
	}

	/**
	 * A scope is a SET of sides and a side is one of them; {@code BOTH} has no counterpart on the {@link Side}
	 * side of the line. Keeping them separate types is what makes that impossible to write down wrongly — it is
	 * how the compiler caught {@code DependencyAudit} comparing a scope against a physical side.
	 */
	@Test
	void aScopeIncludesTheSidesItCoversAndBothCoversEverything() {
		assertTrue(SideScope.BOTH.includes(Side.CLIENT));
		assertTrue(SideScope.BOTH.includes(Side.DEDICATED_SERVER));

		assertTrue(SideScope.CLIENT.includes(Side.CLIENT));
		assertFalse(SideScope.CLIENT.includes(Side.DEDICATED_SERVER));

		assertTrue(SideScope.SERVER.includes(Side.DEDICATED_SERVER));
		assertFalse(SideScope.SERVER.includes(Side.CLIENT));
	}

	/** An unknown physical side means "do not judge this" — the same fail-open rule the rest of the API keeps. */
	@Test
	void anUnknownPhysicalSideIsIncludedByEveryScope() {
		assertTrue(SideScope.CLIENT.includes(null));
		assertTrue(SideScope.SERVER.includes(null));
	}
}
