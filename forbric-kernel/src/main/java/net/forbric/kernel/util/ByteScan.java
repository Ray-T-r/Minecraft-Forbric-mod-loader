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

import java.nio.charset.StandardCharsets;

/**
 * Looks for ASCII needles in raw class bytes, without parsing or copying them.
 *
 * <h2>Why this exists as a shared thing</h2>
 *
 * <p>Several transformers run for EVERY class the game loads and want the same cheap first question: does this
 * class even mention the name I care about? A class file stores type and method names in its constant pool as
 * modified UTF-8, and every name these transformers look for is plain ASCII, so it appears in the bytes
 * verbatim. A "no" is therefore final, not a guess — the class provably does not name it.
 *
 * <p>Three copies of this had grown, each slightly different, and one of them turned the whole class into a
 * {@code String} first: an allocation the size of the class, for every class, to ask a question that needs none.
 */
public final class ByteScan {

	private ByteScan() {
	}

	/** The bytes of an ASCII needle, for the constants a caller holds. */
	public static byte[] needle(String ascii) {
		return ascii.getBytes(StandardCharsets.US_ASCII);
	}

	/** Whether {@code haystack} contains {@code needle}. */
	public static boolean contains(byte[] haystack, byte[] needle) {
		return containsAny(haystack, new byte[][] {needle});
	}

	/**
	 * Whether {@code haystack} contains any of {@code needles}, in ONE pass over the bytes.
	 *
	 * <p>One pass rather than one per needle: the answer is almost always no, and a scan per needle walks the
	 * whole class once per needle to say so. Testing every needle at each position is the same number of
	 * comparisons over a fraction of the memory traffic.
	 */
	public static boolean containsAny(byte[] haystack, byte[][] needles) {
		if (haystack == null || haystack.length == 0 || needles == null) return false;

		int shortest = Integer.MAX_VALUE;
		for (byte[] needle : needles) {
			if (needle != null && needle.length > 0) shortest = Math.min(shortest, needle.length);
		}
		if (shortest == Integer.MAX_VALUE || haystack.length < shortest) return false;

		for (int at = 0; at + shortest <= haystack.length; at++) {
			for (byte[] needle : needles) {
				if (needle == null || needle.length == 0) continue;
				if (at + needle.length > haystack.length) continue;
				if (matchesAt(haystack, at, needle)) return true;
			}
		}
		return false;
	}

	private static boolean matchesAt(byte[] haystack, int at, byte[] needle) {
		for (int i = 0; i < needle.length; i++) {
			if (haystack[at + i] != needle[i]) return false;
		}
		return true;
	}
}
