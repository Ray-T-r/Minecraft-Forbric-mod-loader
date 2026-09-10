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

package net.forbric.loader.impl.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ForgeVersionRangeTranslatorTest {
	private static String t(String mavenRange) {
		return ForgeVersionRangeTranslator.toFabricPredicate(mavenRange);
	}

	@Test
	void emptyAndNullMeanAny() {
		assertEquals("*", t(null));
		assertEquals("*", t(""));
		assertEquals("*", t("   "));
	}

	@Test
	void bareVersionIsAFloor() {
		assertEquals(">=47", t("47"));
		assertEquals(">=1.20.1", t("1.20.1"));
	}

	@Test
	void halfOpenAndClosedRanges() {
		assertEquals(">=47", t("[47,)"));
		assertEquals(">=1.20.1 <1.21", t("[1.20.1,1.21)"));
		assertEquals(">=1.20.1 <=1.21", t("[1.20.1,1.21]"));
		assertEquals(">1.0 <2.0", t("(1.0,2.0)"));
	}

	@Test
	void openLowerBound() {
		assertEquals("<=1.16", t("(,1.16]"));
		assertEquals("<1.17", t("(,1.17)"));
	}

	@Test
	void exactPin() {
		assertEquals("=1.0", t("[1.0]"));
	}

	@Test
	void multipleGroupsAreOrJoined() {
		assertEquals("<=1.0 || >=1.2", t("(,1.0],[1.2,)"));
	}
}
