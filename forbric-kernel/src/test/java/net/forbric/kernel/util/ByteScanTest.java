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

package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The cheap first question several transformers ask of every class the game loads.
 *
 * <p>A "no" from this has to be FINAL, not a guess: the callers skip the class outright on a no, so a false
 * negative silently drops a repair. A false positive only costs a parse the caller was going to reject anyway.
 */
class ByteScanTest {

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	void findsANeedleAnywhereInTheHaystack() {
		byte[] haystack = bytes("....net/minecraft/Thing....");

		assertTrue(ByteScan.contains(haystack, ByteScan.needle("net/minecraft/Thing")));
		assertTrue(ByteScan.contains(bytes("start"), ByteScan.needle("start")));
		assertTrue(ByteScan.contains(bytes("xxend"), ByteScan.needle("end")));
	}

	@Test
	void anyOfSeveralNeedlesIsEnough() {
		byte[][] needles = {ByteScan.needle("alpha"), ByteScan.needle("omega")};

		assertTrue(ByteScan.containsAny(bytes("...omega..."), needles));
		assertTrue(ByteScan.containsAny(bytes("alpha"), needles));
		assertFalse(ByteScan.containsAny(bytes("...beta..."), needles));
	}

	@Test
	void aNeedleLongerThanTheHaystackIsNotFound() {
		// The bounds case: a short class must not walk off the end while testing a long name.
		assertFalse(ByteScan.contains(bytes("ab"), ByteScan.needle("abcdef")));
	}

	@Test
	void aNeedleThatOverlapsTheEndIsNotAMatch() {
		// "abcd" ends with "abc"; looking for "abcz" must not match by running past the last byte.
		assertFalse(ByteScan.contains(bytes("abcd"), ByteScan.needle("abcz")));
		assertFalse(ByteScan.contains(bytes("abc"), ByteScan.needle("abcd")));
	}

	@Test
	void aShorterNeedleStillMatchesNearTheEndWhenAnotherIsLonger() {
		// The loop bounds are driven by the SHORTEST needle; a longer one must not stop the shorter one from
		// matching in the last few bytes, and must not read past the end while trying.
		byte[][] needles = {ByteScan.needle("aVeryLongNameIndeed"), ByteScan.needle("xy")};

		assertTrue(ByteScan.containsAny(bytes(".......xy"), needles));
	}

	@Test
	void nothingToSearchAnswersNo() {
		assertFalse(ByteScan.contains(null, ByteScan.needle("a")));
		assertFalse(ByteScan.contains(new byte[0], ByteScan.needle("a")));
		assertFalse(ByteScan.containsAny(bytes("abc"), null));
		assertFalse(ByteScan.containsAny(bytes("abc"), new byte[][] {}));
		assertFalse(ByteScan.containsAny(bytes("abc"), new byte[][] {null, new byte[0]}));
	}
}
