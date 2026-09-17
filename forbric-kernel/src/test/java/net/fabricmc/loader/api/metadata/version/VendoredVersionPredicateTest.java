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

package net.fabricmc.loader.api.metadata.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * The static entry point mods call directly, with no dependency object involved.
 *
 * <p>This package was left out of the vendored API surface on the argument that nothing reads a dependency's
 * requirement objects — true, and about the instance side. {@code VersionPredicate.parse} is an
 * {@code invokestatic} in ShoulderSurfing-Fabric's {@code Platform.parseVersionPredicateSilent} and in
 * conditional-mixin's version gate, and absent it raised {@code NoClassDefFoundError}, which walks straight past
 * the {@code catch (Exception)} those call sites wrap themselves in.
 */
class VendoredVersionPredicateTest {
	private static Version v(String text) throws VersionParsingException {
		return Version.parse(text);
	}

	@Test
	void aLowerBoundAdmitsWhatIsAboveIt() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse(">=6.0.0");
		assertTrue(predicate.test(v("6.0.1")));
		assertTrue(predicate.test(v("6.0.0")));
		assertFalse(predicate.test(v("5.9.9")));
	}

	@Test
	void theWildcardAdmitsEverything() throws Exception {
		assertTrue(VersionPredicate.parse("*").test(v("0.0.1")));
		assertTrue(VersionPredicate.parse("*").test(v("99.0.0")));
	}

	/** {@code ^} is same-to-next-major, the rule the kernel's own matcher already implements. */
	@Test
	void theCaretStopsAtTheNextMajor() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("^1.2.0");
		assertTrue(predicate.test(v("1.9.0")));
		assertFalse(predicate.test(v("2.0.0")));
	}

	@Test
	void severalRequirementsParseTogether() throws Exception {
		assertEquals(2, VersionPredicate.parse(List.of(">=1.0", "<2.0")).size());
	}

	@Test
	void anEmptyRequirementIsRejectedRatherThanSilentlyAdmittingEverything() {
		assertThrows(VersionParsingException.class, () -> VersionPredicate.parse("  "));
		assertThrows(VersionParsingException.class, () -> VersionPredicate.parse((String) null));
	}

	/** A mod that enumerates the terms gets the operator and the reference it wrote. */
	@Test
	void theTermsCarryTheOperatorAndReference() throws Exception {
		List<? extends VersionPredicate.PredicateTerm> terms =
				List.copyOf(VersionPredicate.parse(">=6.0.0").getTerms());

		assertEquals(1, terms.size());
		assertEquals(VersionComparisonOperator.GREATER_EQUAL, terms.get(0).getOperator());
		assertEquals("6.0.0", terms.get(0).getReferenceVersion().getFriendlyString());
	}

	/** The operator's own test must agree with the predicate built from the same text. */
	@Test
	void theOperatorAgreesWithThePredicate() throws Exception {
		assertTrue(VersionComparisonOperator.GREATER_EQUAL.test(v("6.0.1"), v("6.0.0")));
		assertFalse(VersionComparisonOperator.GREATER_EQUAL.test(v("5.0.0"), v("6.0.0")));
		assertTrue(VersionComparisonOperator.LESS.test(v("1.0.0"), v("2.0.0")));
	}

	/** The interval type exists so a mod naming it links; the one instance it can hold admits everything. */
	@Test
	void theInfiniteIntervalIsUnbounded() {
		assertNull(VersionInterval.INFINITE.getMin());
		assertNull(VersionInterval.INFINITE.getMax());
		assertFalse(VersionInterval.INFINITE.isSemantic());
	}
}
