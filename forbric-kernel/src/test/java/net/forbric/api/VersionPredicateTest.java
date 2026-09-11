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

	@Test
	void tildeHoldsTheMinorAndCaretHoldsTheMajor() {
		assertTrue(VersionPredicate.matches("~1.2.3", "1.2.9"));
		assertFalse(VersionPredicate.matches("~1.2.3", "1.3.0"));
		assertFalse(VersionPredicate.matches("~1.2.3", "1.2.2"));

		assertTrue(VersionPredicate.matches("^1.2.3", "1.9.0"));
		assertFalse(VersionPredicate.matches("^1.2.3", "2.0.0"));
	}

	/**
	 * {@code ~} bounds at the next minor however many segments were written; only {@code ~1} has no minor to bump
	 * and falls back to the major.
	 */
	@Test
	void tildeBoundsAtTheMinorEvenWhenThePatchWasNotWritten() {
		assertTrue(VersionPredicate.matches("~1.2", "1.2.9"));
		assertFalse(VersionPredicate.matches("~1.2", "1.3"));
		assertTrue(VersionPredicate.matches("~1", "1.9"));
		assertFalse(VersionPredicate.matches("~1", "2.0"));
	}

	/**
	 * The case that actually matters on this platform: nearly every Fabric mod is versioned {@code 0.x}, and a
	 * caret read as "below the next major" would accept every future breaking release of exactly those mods,
	 * because their major never moves.
	 */
	@Test
	void caretBoundsAtTheLeftmostNonZeroSegment() {
		assertTrue(VersionPredicate.matches("^0.15.0", "0.15.9"));
		assertFalse(VersionPredicate.matches("^0.15.0", "0.16.0"));
		assertTrue(VersionPredicate.matches("^0.0.3", "0.0.3"));
		assertFalse(VersionPredicate.matches("^0.0.3", "0.1.0"));
	}

	@Test
	void anExactVersionAndAWildcardSegment() {
		assertTrue(VersionPredicate.matches("1.2.3", "1.2.3"));
		assertFalse(VersionPredicate.matches("1.2.3", "1.2.4"));
		assertTrue(VersionPredicate.matches("=1.2.3", "1.2.3"));
		assertTrue(VersionPredicate.matches("1.2.x", "1.2.7"));
		assertFalse(VersionPredicate.matches("1.2.x", "1.3.0"));
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
