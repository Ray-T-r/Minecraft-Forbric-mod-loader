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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the cross-ecosystem id fallback to "only when it cannot be wrong".
 *
 * <p>The feature is one line of resolution and three refusals, and the refusals are the part worth testing: this
 * answers for a dialog that tells a player which mod to go and download, so the cost of a wrong match is that a
 * genuinely missing dependency is never reported at all.
 */
class ModIdsTest {
	@Test
	void theOtherEcosystemsSpellingOfTheSameLibraryIsFound() {
		// Exactly the Cloth Config case: cpa (NeoForge) requires cloth_config; the Fabric build is what is installed.
		assertEquals("the fabric cloth config",
				ModIds.underAnotherSpelling("cloth_config", index("cloth-config", "the fabric cloth config")));
	}

	@Test
	void punctuationIsTheOnlyDifferenceItForgives() {
		Map<String, String> installed = index("cloth-config", "cloth");
		// A different library whose name merely starts the same way is not the same library.
		assertNull(ModIds.underAnotherSpelling("clothconfigextras", installed));
		assertNull(ModIds.underAnotherSpelling("cloth", installed));
	}

	@Test
	void twoCandidatesMeanItDeclinesRatherThanGuesses() {
		Map<String, String> installed = new LinkedHashMap<>();
		installed.put("some-mod", "the hyphenated one");
		installed.put("some_mod", "the underscored one");

		assertNull(ModIds.underAnotherSpelling("somemod", installed),
				"two installed mods collapse to this key; picking either would silence a real missing dependency");
	}

	/**
	 * A mod that declares BOTH spellings itself — its id plus a {@code provides} alias — is one candidate, and the
	 * naive "count the matching entries" version of this refused precisely the mods that tried hardest to be found.
	 */
	@Test
	void aModIndexedUnderBothItsOwnSpellingsIsStillOneCandidate() {
		Map<String, String> installed = new LinkedHashMap<>();
		installed.put("libjf-base", "libjf");
		installed.put("libjf_base", "libjf");

		assertEquals("libjf", ModIds.underAnotherSpelling("libjf.base", installed));
	}

	@Test
	void anExactlyMatchingIdIsLeftToTheCallersOwnLookup() {
		assertNull(ModIds.underAnotherSpelling("cloth-config", index("cloth-config", "cloth")),
				"the caller already resolved this; answering again would make a plain hit look cross-ecosystem");
	}

	@Test
	void theSwitchRestoresExactIdMatching() {
		String previous = System.getProperty(ModIds.SWITCH);
		System.setProperty(ModIds.SWITCH, "off");
		try {
			assertNull(ModIds.underAnotherSpelling("cloth_config", index("cloth-config", "cloth")));
		} finally {
			if (previous == null) System.clearProperty(ModIds.SWITCH);
			else System.setProperty(ModIds.SWITCH, previous);
		}
	}

	private static Map<String, String> index(String id, String value) {
		Map<String, String> installed = new LinkedHashMap<>();
		installed.put(id, value);
		return installed;
	}
}
