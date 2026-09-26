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

/**
 * The one helper from Fabric Loader's internal {@code StringUtil} a mod is observed to call.
 *
 * <p>pets-mod capitalises pet names with {@code StringUtil.capitalize} in its config screen ({@code PetsConfigScreen}
 * and its {@code DropdownMenu}). It is internal to Fabric Loader, but it links there, and here it did not: opening
 * the config screen threw {@code NoClassDefFoundError} out of {@code Screen.init}. The rest of Fabric's class is
 * deliberately left out, so a call to it still fails by name. {@code -Dforbric.fabricImpl=off} withholds this class.
 */
public final class StringUtil {
	private StringUtil() {
	}

	/**
	 * Upper-cases the first letter or digit, leaving anything before it untouched: {@code "cat" -> "Cat"},
	 * {@code "_cat" -> "_Cat"}. Fabric Loader 0.19.5's behaviour, down to stepping one {@code char} at a time while
	 * reading code points.
	 */
	public static String capitalize(String s) {
		if (s.isEmpty()) return s;

		int pos = 0;
		while (pos < s.length()) {
			if (Character.isLetterOrDigit(s.codePointAt(pos))) break;
			pos++;
		}
		if (pos == s.length()) return s;

		int codePoint = s.codePointAt(pos);
		int upper = Character.toUpperCase(codePoint);
		if (upper == codePoint) return s;

		StringBuilder out = new StringBuilder(s.length());
		out.append(s, 0, pos);
		out.appendCodePoint(upper);
		out.append(s, pos + Character.charCount(codePoint), s.length());
		return out.toString();
	}
}
