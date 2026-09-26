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

package net.fabricmc.loader.impl.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * pets-mod capitalises pet names with Fabric Loader's internal {@code StringUtil.capitalize}; opening its config screen
 * threw {@code NoClassDefFoundError} here. Every expectation below is what fabric-loader 0.19.5's own
 * {@code capitalize} returned for the same input — including WHICH inputs come back as the very same string.
 */
class StringUtilTest {
	@Test
	void capitalisesTheFirstLetterOrDigit() {
		assertEquals("Cat", StringUtil.capitalize("cat"));
		assertEquals("X", StringUtil.capitalize("x"));
		assertEquals("Pet_name", StringUtil.capitalize("pet_name"));
		assertEquals("Émile", StringUtil.capitalize("émile"));
		assertEquals("Ǆ", StringUtil.capitalize("ǆ"), "a titlecase-able digraph goes to its upper case");
	}

	@Test
	void leavesWhatComesBeforeIt() {
		assertEquals("_Cat", StringUtil.capitalize("_cat"));
		assertEquals("  Hello world", StringUtil.capitalize("  hello world"));
		assertEquals("-X", StringUtil.capitalize("-x"));
	}

	@Test
	void handlesSupplementaryCharacters() {
		assertEquals("𐐀abc", StringUtil.capitalize("𐐨abc"));
		assertEquals("!𐐀", StringUtil.capitalize("!𐐨"));
	}

	/** Fabric returns its argument itself when nothing changes; so must this. */
	@Test
	void returnsTheSameStringWhenThereIsNothingToChange() {
		for (String unchanged : new String[] {"", "123", "1abc", "Cat", "_", "__", "ß", "𝒜bc"}) {
			assertSame(unchanged, StringUtil.capitalize(unchanged), unchanged);
		}
	}
}
