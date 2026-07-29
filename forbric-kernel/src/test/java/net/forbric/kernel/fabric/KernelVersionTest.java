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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/** The kernel's Version parser, exercised on the exact strings the shipped ecosystem uses. */
class KernelVersionTest {
	@Test
	void parsesTheRealEcosystemVersions() throws VersionParsingException {
		// fabric-api, Jade, the game, and a nested fabric-api module respectively.
		assertTrue(KernelVersion.parse("0.154.0+26.2") instanceof SemanticVersion);
		assertTrue(KernelVersion.parse("26.2.9+fabric") instanceof SemanticVersion);
		assertTrue(KernelVersion.parse("26.2") instanceof SemanticVersion);
		assertTrue(KernelVersion.parse("2.0.4+ece063239e") instanceof SemanticVersion);
	}

	@Test
	void buildMetadataIsCarriedButDoesNotAffectOrdering() throws VersionParsingException {
		SemanticVersion v = KernelVersion.parseSemantic("0.154.0+26.2");

		assertEquals("26.2", v.getBuildKey().orElseThrow());
		assertEquals("0.154.0+26.2", v.getFriendlyString());
		// SemVer §10: build metadata is ignored in precedence.
		assertEquals(0, v.compareTo(KernelVersion.parse("0.154.0+other")));
	}

	@Test
	void absentTrailingComponentsReadAsZero() throws VersionParsingException {
		SemanticVersion v = KernelVersion.parseSemantic("26.2");

		assertEquals(2, v.getVersionComponentCount());
		assertEquals(26, v.getVersionComponent(0));
		assertEquals(2, v.getVersionComponent(1));
		assertEquals(0, v.getVersionComponent(2));
		assertEquals(0, v.compareTo(KernelVersion.parse("26.2.0")));
	}

	@Test
	void ordersNumericComponentsNumericallyNotLexically() throws VersionParsingException {
		assertTrue(KernelVersion.parse("1.10.0").compareTo(KernelVersion.parse("1.9.0")) > 0);
		assertTrue(KernelVersion.parse("0.154.0").compareTo(KernelVersion.parse("0.99.0")) > 0);
	}

	@Test
	void prereleaseSortsBelowItsRelease() throws VersionParsingException {
		assertTrue(KernelVersion.parse("1.0.0-beta.1").compareTo(KernelVersion.parse("1.0.0")) < 0);
		assertTrue(KernelVersion.parse("1.0.0-alpha").compareTo(KernelVersion.parse("1.0.0-beta")) < 0);
		// A numeric prerelease identifier has lower precedence than an alphanumeric one (SemVer §11.4.3).
		assertTrue(KernelVersion.parse("1.0.0-1").compareTo(KernelVersion.parse("1.0.0-alpha")) < 0);
		assertTrue(KernelVersion.parse("1.0.0-alpha.1").compareTo(KernelVersion.parse("1.0.0-alpha")) > 0);
	}

	@Test
	void wildcardsParseAndCompareAsAny() throws VersionParsingException {
		SemanticVersion v = KernelVersion.parseSemantic("1.21.x");

		assertTrue(v.hasWildcard());
		assertEquals(SemanticVersion.COMPONENT_WILDCARD, v.getVersionComponent(2));
		assertEquals(0, v.compareTo(KernelVersion.parse("1.21.7")));
	}

	@Test
	void nonSemverFallsBackToAStringVersionRatherThanFailing() throws VersionParsingException {
		Version v = KernelVersion.parse("not-a-semver");

		assertFalse(v instanceof SemanticVersion);
		assertEquals("not-a-semver", v.getFriendlyString());
	}

	@Test
	void strictSemanticParseRejectsNonSemver() {
		assertThrows(VersionParsingException.class, () -> KernelVersion.parseSemantic("abc"));
		assertThrows(VersionParsingException.class, () -> KernelVersion.parseSemantic("1..2"));
		assertThrows(VersionParsingException.class, () -> KernelVersion.parseSemantic("1.x.2"));
		assertThrows(VersionParsingException.class, () -> KernelVersion.parse("  "));
	}

	@Test
	void predicatesMatchTheFormsFabricModsDeclare() throws VersionParsingException {
		Version v = KernelVersion.parse("0.154.0");

		assertTrue(KernelMetadataSupport.matchesPredicate("*", v));
		assertTrue(KernelMetadataSupport.matchesPredicate(">=0.18.4", v));
		assertTrue(KernelMetadataSupport.matchesPredicate("<1.0.0", v));
		assertTrue(KernelMetadataSupport.matchesPredicate(">=0.100.0 <1.0.0", v));
		assertFalse(KernelMetadataSupport.matchesPredicate(">=1.0.0", v));

		// ~ pins major+minor; ^ pins major.
		assertTrue(KernelMetadataSupport.matchesPredicate("~0.154.0", KernelVersion.parse("0.154.3")));
		assertFalse(KernelMetadataSupport.matchesPredicate("~0.154.0", KernelVersion.parse("0.155.0")));
		assertTrue(KernelMetadataSupport.matchesPredicate("^0.154.0", KernelVersion.parse("0.155.0")));
		assertFalse(KernelMetadataSupport.matchesPredicate("^1.0.0", KernelVersion.parse("2.0.0")));

		// A malformed term must not throw — one bad constraint cannot abort a load.
		assertFalse(KernelMetadataSupport.matchesPredicate(">=@@@", v));
	}
}
