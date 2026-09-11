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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.metadata.forge.ForgeVersionRangeTranslator;

/**
 * Covers the evaluator that makes a {@link UnifiedDependency}'s constraint mean something.
 *
 * <p>Before this existed the constraint was carried, printed and handed on, and never once asked a question of, so
 * every one of these cases was a shape the codebase would have accepted silently.
 */
class VersionPredicateTest {
	@Test
	void anythingItCannotJudgeCountsAsSatisfied() {
		// The house rule: these answers accuse mods of being misconfigured, so silence beats a guess.
		assertTrue(VersionPredicate.matches("*", "1.0.0"));
		assertTrue(VersionPredicate.matches("", "1.0.0"));
		assertTrue(VersionPredicate.matches(null, "1.0.0"));
		assertTrue(VersionPredicate.matches(">=1.0", null), "an unknown version is not evidence of a mismatch");
		assertTrue(VersionPredicate.matches("@@nonsense@@", "1.0.0"));
	}

	@Test
	void theFourComparisonsBound() {
		assertTrue(VersionPredicate.matches(">=0.15.0", "0.15.0"));
		assertFalse(VersionPredicate.matches(">=0.15.0", "0.14.9"));
		assertFalse(VersionPredicate.matches(">0.15.0", "0.15.0"));
		assertTrue(VersionPredicate.matches("<=47", "47"));
		assertFalse(VersionPredicate.matches("<47", "47"));
	}

	@Test
	void aSpaceIsAndAndDoublePipeIsOr() {
		assertTrue(VersionPredicate.matches(">=47 <48", "47.1"));
		assertFalse(VersionPredicate.matches(">=47 <48", "48.0"));
		assertFalse(VersionPredicate.matches(">=47 <48", "46.9"));

		assertTrue(VersionPredicate.matches(">=47 <48 || >=50", "51"));
		assertFalse(VersionPredicate.matches(">=47 <48 || >=50", "49"));
	}

	/**
	 * Read off {@code VersionComparisonOperator} in fabric-loader 0.19.5: {@code ~} is
	 * {@code SAME_TO_NEXT_MINOR} and {@code ^} is {@code SAME_TO_NEXT_MAJOR}, each additionally requiring the
	 * version to be at or above the floor. Both are implemented there as "same first N components", not as an
	 * upper bound, and the difference shows wherever a component is missing.
	 */
	@Test
	void tildePinsMajorAndMinorAndCaretPinsMajor() {
		assertTrue(VersionPredicate.matches("~1.2.3", "1.2.9"));
		assertFalse(VersionPredicate.matches("~1.2.3", "1.3.0"));
		assertFalse(VersionPredicate.matches("~1.2.3", "1.2.2"), "below the floor");

		assertTrue(VersionPredicate.matches("^1.2.3", "1.9.0"));
		assertFalse(VersionPredicate.matches("^1.2.3", "2.0.0"));
		assertFalse(VersionPredicate.matches("^1.2.3", "1.2.2"), "below the floor");
	}

	/**
	 * A component the floor does not write counts as 0, because that is what "same first N components" means when
	 * one side is shorter — so {@code ~1} pins the minor to 0 rather than admitting all of {@code 1.x}.
	 */
	@Test
	void aMissingComponentInTheFloorCountsAsZeroRatherThanAsAWildcard() {
		assertTrue(VersionPredicate.matches("~1.2", "1.2.9"));
		assertFalse(VersionPredicate.matches("~1.2", "1.3"));
		assertTrue(VersionPredicate.matches("~1", "1.0.5"));
		assertFalse(VersionPredicate.matches("~1", "1.9"));
	}

	/**
	 * The 0.x case, and the one this class used to get wrong: npm bounds a caret at the leftmost non-zero segment,
	 * so {@code ^0.15.0} would stop at {@code 0.16}. Fabric does not — {@code ^} is plainly
	 * {@code SAME_TO_NEXT_MAJOR}, and nearly every Fabric mod is 0.x, so the difference decides a great many real
	 * dependencies. Matching the ecosystem beats being right about semver: a mod that loads under Fabric has to
	 * load under Forbric.
	 */
	@Test
	void caretPinsOnlyTheMajorEvenWhenTheMajorIsZero() {
		assertTrue(VersionPredicate.matches("^0.15.0", "0.15.9"));
		assertTrue(VersionPredicate.matches("^0.15.0", "0.16.0"), "Fabric admits this; npm would not");
		assertTrue(VersionPredicate.matches("^0.0.3", "0.1.0"));
		assertFalse(VersionPredicate.matches("^0.15.0", "1.0.0"));
	}

	/**
	 * The two failure directions, on the same engine. A diagnostic must not accuse a mod over a predicate it could
	 * not read; a resolver must not admit a dependency it could not check.
	 */
	@Test
	void anUnreadablePredicateIsOpenForMatchesAndClosedForMatchesStrictly() {
		assertTrue(VersionPredicate.matches("@@nonsense@@", "1.0.0"));
		assertFalse(VersionPredicate.matchesStrictly("@@nonsense@@", "1.0.0"));

		assertTrue(VersionPredicate.matches(">=@@@", "1.0.0"));
		assertFalse(VersionPredicate.matchesStrictly(">=@@@", "1.0.0"));

		// A predicate both CAN read still answers the same either way.
		assertTrue(VersionPredicate.matches(">=1.0", "1.1"));
		assertTrue(VersionPredicate.matchesStrictly(">=1.0", "1.1"));
		assertFalse(VersionPredicate.matches(">=2.0", "1.1"));
		assertFalse(VersionPredicate.matchesStrictly(">=2.0", "1.1"));
	}

	@Test
	void anExactVersionAndAWildcardSegment() {
		assertTrue(VersionPredicate.matches("1.2.3", "1.2.3"));
		assertFalse(VersionPredicate.matches("1.2.3", "1.2.4"));
		assertTrue(VersionPredicate.matches("=1.2.3", "1.2.3"));
		assertTrue(VersionPredicate.matches("1.2.x", "1.2.7"));
		assertFalse(VersionPredicate.matches("1.2.x", "1.3.0"));
	}

	/**
	 * Semver's two suffixes are not the same thing, and getting them confused was not academic: fabric-api stamps
	 * a build hash onto every module version, so reading {@code 6.3.3+72073ef09e} as a PRE-release of
	 * {@code 6.3.3} made it fail {@code >=6.3.3} — and on a real pack that meant several mods were reported as
	 * having unmet dependencies that were in fact met.
	 */
	@Test
	void buildMetadataIsIgnoredWhilePreReleaseStillOrdersBelow() {
		assertEquals(0, VersionPredicate.compare("6.3.3+72073ef09e", "6.3.3"));
		assertTrue(VersionPredicate.matches(">=6.3.3", "6.3.3+72073ef09e"));
		assertTrue(VersionPredicate.matches("<=0.9.1", "0.9.1+mc26.2"));
		assertTrue(VersionPredicate.matches("=1.2.3", "1.2.3+build7"));

		assertTrue(VersionPredicate.compare("1.0-beta", "1.0") < 0, "a dash is still a pre-release");
		assertFalse(VersionPredicate.matches(">=1.0", "1.0-beta"));
	}

	@Test
	void trailingZerosArePaddingAndAQualifierPrecedesTheBareVersion() {
		assertEquals(0, VersionPredicate.compare("1.0.0", "1.0"));
		assertTrue(VersionPredicate.compare("26.2.0.7.1", "26.2.0.7") > 0);
		assertTrue(VersionPredicate.compare("1.0-beta", "1.0") < 0, "the semver pre-release rule");
		assertTrue(VersionPredicate.compare("1.0.1", "1.0.beta") > 0, "a number outranks a qualifier");
	}

	/**
	 * The two dialects meet here: a Forge {@code mods.toml} Maven range is translated to a predicate, and this is
	 * what evaluates it. If the translator and the evaluator disagree about a shape, the requirement is judged
	 * against something the mod author never wrote — so walk a real range end to end.
	 */
	@Test
	void aTranslatedMavenRangeEvaluatesTheWayTheRangeMeant() {
		String predicate = ForgeVersionRangeTranslator.toFabricPredicate("[47,48)");
		assertTrue(VersionPredicate.matches(predicate, "47.1.3"));
		assertFalse(VersionPredicate.matches(predicate, "48.0.0"));
		assertFalse(VersionPredicate.matches(predicate, "46.9.9"));

		String soft = ForgeVersionRangeTranslator.toFabricPredicate("47");
		assertTrue(VersionPredicate.matches(soft, "49"), "a bare Maven version is a floor, not an equality");

		String exact = ForgeVersionRangeTranslator.toFabricPredicate("[47]");
		assertTrue(VersionPredicate.matches(exact, "47"));
		assertFalse(VersionPredicate.matches(exact, "47.1"));
	}
}
