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

package net.forbric.kernel.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ForgeVersionRangeTest {

	/**
	 * The case this class was written for, kept literal so it cannot drift into an abstraction. The carrier has
	 * since moved to 26.2.0.38-beta, which is why the last assertion is here: the fix for the JEI case has to be
	 * expressible in this comparator, or the bump only looked correct.
	 */
	@Test
	void theJeiCase() {
		assertFalse(ForgeVersionRange.satisfies("[26.2.0.16-beta,)", "26.2.0.7-beta"),
				"JEI declares [26.2.0.16-beta,) and the carrier was 26.2.0.7-beta — nine releases short");
		assertTrue(ForgeVersionRange.satisfies("[26.2.0.16-beta,)", "26.2.0.16-beta"));
		assertTrue(ForgeVersionRange.satisfies("[26.2.0.16-beta,)", "26.2.0.20-beta"));
		assertTrue(ForgeVersionRange.satisfies("[26.2.0.16-beta,)", "26.2.0.38-beta"),
				"and 26.2.0.38-beta, the carrier the audit's finding first moved us to, does satisfy it");
	}

	/**
	 * The same case a carrier later, and the reason the pin moved again: a release version against ranges written
	 * in betas. JEI 30.32 declares {@code [26.2.0.67,)}, which {@code 26.2.0.38-beta} does not satisfy — the old
	 * pin had become the thing holding the pack back — and the sophisticated* pair declare
	 * {@code [26.2.0.53-beta,26.3.0)}, which a release-suffixed {@code 26.2.0.88} has to satisfy on both bounds.
	 */
	@Test
	void theJeiCaseOneCarrierLater() {
		assertFalse(ForgeVersionRange.satisfies("[26.2.0.67,)", "26.2.0.38-beta"),
				"the old pin does not satisfy what JEI now asks for");
		assertTrue(ForgeVersionRange.satisfies("[26.2.0.67,)", "26.2.0.88"));
		assertTrue(ForgeVersionRange.satisfies("[26.2.0.53-beta,26.3.0)", "26.2.0.88"),
				"a release build must compare above a beta lower bound, and below the 26.3.0 ceiling");
		assertFalse(ForgeVersionRange.satisfies("[26.2.0.53-beta,26.3.0)", "26.3.0"));
	}

	@Test
	void boundsAreHonouredOnBothSides() {
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0)", "1.0"));
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0)", "1.9.9"));
		assertFalse(ForgeVersionRange.satisfies("[1.0,2.0)", "2.0"));
		assertFalse(ForgeVersionRange.satisfies("[1.0,2.0)", "0.9"));

		assertTrue(ForgeVersionRange.satisfies("(1.0,2.0]", "2.0"));
		assertFalse(ForgeVersionRange.satisfies("(1.0,2.0]", "1.0"));

		assertTrue(ForgeVersionRange.satisfies("(,1.0]", "0.5"));
		assertFalse(ForgeVersionRange.satisfies("(,1.0]", "1.1"));
	}

	@Test
	void aPinnedVersionMatchesOnlyItself() {
		assertTrue(ForgeVersionRange.satisfies("[1.0]", "1.0"));
		assertFalse(ForgeVersionRange.satisfies("[1.0]", "1.0.1"));
		assertFalse(ForgeVersionRange.satisfies("[1.0]", "0.9"));
	}

	@Test
	void aUnionIsSatisfiedByAnyInterval() {
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0),[3.0,)", "1.5"));
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0),[3.0,)", "3.1"));
		assertFalse(ForgeVersionRange.satisfies("[1.0,2.0),[3.0,)", "2.5"));
	}

	/** Maven's "soft" requirement: a bare version is a floor, which is how Forge reads it. */
	@Test
	void aBareVersionIsAFloor() {
		assertTrue(ForgeVersionRange.satisfies("1.0", "1.0"));
		assertTrue(ForgeVersionRange.satisfies("1.0", "1.5"));
		assertFalse(ForgeVersionRange.satisfies("1.0", "0.9"));
	}

	@Test
	void segmentsCompareNumericallyNotLexicographically() {
		assertTrue(ForgeVersionRange.compare("26.2.0.16", "26.2.0.7") > 0, "16 > 7, not \"16\" < \"7\"");
		assertTrue(ForgeVersionRange.compare("1.10", "1.9") > 0);
		assertTrue(ForgeVersionRange.compare("1.0.0", "1.0") == 0, "a missing segment reads as 0");
	}

	/** Semver's pre-release rule, which is what "-beta" means on a NeoForge version. */
	@Test
	void aTrailingQualifierSortsBeforeTheReleaseButATrailingNumberAfterIt() {
		assertTrue(ForgeVersionRange.compare("1.0-beta", "1.0") < 0);
		assertTrue(ForgeVersionRange.compare("26.2.0.7.1", "26.2.0.7") > 0);
		assertTrue(ForgeVersionRange.compare("1.0.1", "1.0.beta") > 0, "a number outranks a qualifier in place");
	}

	/** It exists to explain a failure, so anything it cannot read must not manufacture one. */
	@Test
	void anythingUnreadableFailsOpen() {
		assertTrue(ForgeVersionRange.satisfies(null, "1.0"));
		assertTrue(ForgeVersionRange.satisfies("", "1.0"));
		assertTrue(ForgeVersionRange.satisfies("   ", "1.0"));
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0", "9.9"), "unterminated interval");
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0)", null));
		assertTrue(ForgeVersionRange.satisfies("[1.0,2.0)", ""));
	}
}
