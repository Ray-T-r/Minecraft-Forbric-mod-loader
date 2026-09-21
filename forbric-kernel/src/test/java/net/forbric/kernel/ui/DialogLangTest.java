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

package net.forbric.kernel.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The ten tables, checked against each other.
 *
 * <p>A translation cannot be tested for being good. It CAN be tested for the two ways it silently goes wrong: a
 * key that was never translated, so one sentence of the dialog is in the wrong language, and a {@code {0}} that
 * was dropped, so the mod name the sentence is about never appears at all.
 */
class DialogLangTest {
	@Test
	void everyTableSaysEverythingEnglishSays() {
		List<String> broken = new ArrayList<>();
		for (DialogLang lang : DialogLang.all()) {
			if (!lang.missingKeys().isEmpty()) broken.add(lang.tag() + " is missing " + lang.missingKeys());
			if (!lang.strayKeys().isEmpty()) broken.add(lang.tag() + " has unknown " + lang.strayKeys());
		}
		// Missing is the loud half: the dialog falls back to English, so the failure is a Japanese window with an
		// English paragraph in the middle of it. Stray is the quiet half: a mistyped key is a string nothing will
		// ever read, and nothing else would ever notice.
		assertEquals(List.of(), broken, String.join("\n", broken));
	}

	@Test
	void everyTableKeepsEveryPlaceholder() {
		List<String> broken = new ArrayList<>();
		for (String key : DialogLang.EN.keys()) {
			Set<String> wanted = placeholders(DialogLang.EN.raw(key));
			for (DialogLang lang : DialogLang.all()) {
				String value = lang.raw(key);
				if (value == null) continue; // the completeness test above owns that failure
				Set<String> present = placeholders(value);
				if (!present.equals(wanted)) {
					broken.add(lang.tag() + "/" + key + ": expected " + wanted + " but found " + present
							+ " in \"" + value + "\"");
				}
			}
		}
		// A dropped {0} does not throw and does not look wrong: the sentence still reads, it has just stopped
		// being about any particular mod. That is the worst kind of bug for a dialog whose job is to name one.
		assertEquals(List.of(), broken, String.join("\n", broken));
	}

	@Test
	void noTableLeavesABracedPlaceholderOnScreen() {
		// substitute() replaces only the arguments it is given, so a translation that invented a {4} would put
		// a literal "{4}" in front of the player.
		for (DialogLang lang : DialogLang.all()) {
			for (String key : DialogLang.EN.keys()) {
				String value = lang.raw(key);
				if (value == null) continue;
				for (String found : placeholders(value)) {
					int index = Integer.parseInt(found.substring(1, found.length() - 1));
					assertTrue(index < 4, lang.tag() + "/" + key + " uses " + found
							+ ", which nothing passes an argument for");
				}
			}
		}
	}

	@Test
	void aKeyNoTableHasBecomesTheKeyRatherThanAnException() {
		// The runtime net, which exists because a dialog that throws is a dialog the player never sees. The
		// completeness test above is what keeps it from ever being reached in practice.
		assertEquals("forbric.no.such.key", DialogLang.of(Locale.JAPANESE).get("forbric.no.such.key"));
		assertEquals("forbric.no.such.key", DialogLang.EN.get("forbric.no.such.key", "unused"));
	}

	@Test
	void substitutionIsPositionalAndNotMessageFormat() {
		// An apostrophe is an escape character in a MessageFormat pattern, so "Biomes O' Plenty" in a French or
		// English sentence would quietly swallow the rest of it. This substitutes by hand for exactly that
		// reason, and this is the test that says so.
		assertEquals("Biomes O' Plenty needs terrablender, which is not installed",
				DialogLang.EN.get("bullet.absent", "Biomes O' Plenty", "terrablender"));
		// A placeholder repeated in one sentence — fix.install names the ecosystem twice — must be filled twice.
		String install = DialogLang.EN.get("fix.install", "terrablender", "Biomes O' Plenty", "FORGE");
		assertFalse(install.contains("{"), install);
		assertEquals(2, install.split("FORGE", -1).length - 1, install);
	}

	@Test
	void chineseSplitsOnScriptAndNothingElseDoes() {
		// The one language here where the region changes which table is right: Traditional text handed to a
		// Simplified reader is a different language, not a dialect. Everywhere else the region is noise.
		assertSame(DialogLang.ZH_CN, DialogLang.of(Locale.SIMPLIFIED_CHINESE));
		assertSame(DialogLang.ZH_CN, DialogLang.of(Locale.of("zh", "SG")));
		assertSame(DialogLang.ZH_TW, DialogLang.of(Locale.TRADITIONAL_CHINESE));
		assertSame(DialogLang.ZH_TW, DialogLang.of(Locale.of("zh", "HK")));
		assertSame(DialogLang.ZH_TW, DialogLang.of(Locale.forLanguageTag("zh-Hant")));

		assertSame(DialogLang.DE, DialogLang.of(Locale.of("de", "AT")));
		assertSame(DialogLang.PT_BR, DialogLang.of(Locale.of("pt", "PT")),
				"one Portuguese beats English for a Portuguese reader");
		assertSame(DialogLang.EN, DialogLang.of(Locale.of("is", "IS")));
		assertSame(DialogLang.EN, DialogLang.of(null));
	}

	@Test
	void theSwitchNamesATableAndAnUnknownNameIsNotAnError() {
		String before = System.getProperty(DialogLang.SWITCH);
		try {
			System.setProperty(DialogLang.SWITCH, "ja");
			assertSame(DialogLang.JA, DialogLang.ofSystem());
			System.setProperty(DialogLang.SWITCH, "PT-BR");
			assertSame(DialogLang.PT_BR, DialogLang.ofSystem(), "case and hyphen must not decide this");
			System.setProperty(DialogLang.SWITCH, "klingon");
			// Falls through to the system language rather than throwing. A misspelt developer flag must not be
			// able to stop a player's launch.
			assertNotNull(DialogLang.ofSystem());
		} finally {
			if (before == null) System.clearProperty(DialogLang.SWITCH);
			else System.setProperty(DialogLang.SWITCH, before);
		}
	}

	@Test
	void everyTableHasItsOwnTagAndItsOwnWords() {
		Set<String> tags = new LinkedHashSet<>();
		for (DialogLang lang : DialogLang.all()) {
			assertTrue(tags.add(lang.tag()), "two tables filed under " + lang.tag());
			if (lang == DialogLang.EN) continue;
			// A table that is a verbatim copy of English is a table nobody translated. Checked on a sentence
			// long enough that no language would coincide with it.
			assertFalse(DialogLang.EN.raw("note.mixins").equals(lang.raw("note.mixins")),
					lang.tag() + " still carries the English text");
		}
		assertEquals(10, tags.size(), tags.toString());
	}

	@Test
	void theLiteralsThatAreNotWordsSurviveEveryTranslation() {
		// Paths, log tags and folder names are not prose. A translated "logs/latest.log" sends the player looking
		// for a file that does not exist.
		for (DialogLang lang : DialogLang.all()) {
			assertTrue(lang.raw("details.log").contains("logs/latest.log"), lang.tag());
			assertTrue(lang.raw("details.log").contains("[Forbric/Deps]"), lang.tag());
			assertTrue(lang.raw("fix.remove").contains("mods"), lang.tag());
			for (String key : List.of("title.deps", "title.mixins", "title.both")) {
				assertTrue(lang.raw(key).contains("Forbric"), lang.tag() + "/" + key);
			}
		}
	}

	private static Set<String> placeholders(String value) {
		Set<String> found = new LinkedHashSet<>();
		for (int i = 0; i + 2 < value.length(); i++) {
			if (value.charAt(i) == '{' && value.charAt(i + 2) == '}'
					&& Character.isDigit(value.charAt(i + 1))) {
				found.add(value.substring(i, i + 3));
			}
		}
		return found;
	}
}
