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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.ui.DependencyReport.Row;

/** The pipe between the two processes, and the words the player is shown. */
class DependencyDialogTest {
	@TempDir
	Path tmp;

	/**
	 * English explicitly, everywhere a test reads words.
	 *
	 * <p>{@code Locale.getDefault()} decides the dialog's language, the build pins no locale for {@code test},
	 * and the machines that run this are whatever a contributor and CI happen to have. Asserting on English text
	 * while letting the system choose the table is a test that passes in London and fails in Shenzhen.
	 */
	private static final DialogLang EN = DialogLang.EN;

	private static Row absent() {
		return new Row("biomesoplenty", "Biomes O' Plenty", "FORGE", "terrablender", ">=26.2.0.0.1", null);
	}

	private static Row wrongVersion() {
		return new Row("iris", "Iris Shaders", "NEOFORGE", "sodium", "0.9.x", "0.8.1");
	}

	@Test
	void aReportSurvivesTheRoundTrip() throws Exception {
		Path file = tmp.resolve("report.tsv");
		DependencyReport.write(file, List.of(absent(), wrongVersion()));

		List<Row> back = DependencyReport.read(file);
		assertEquals(2, back.size());
		assertEquals(absent(), back.get(0));
		assertEquals(wrongVersion(), back.get(1));
	}

	@Test
	void absentStaysDistinctFromAVersionThatHappensToLookLikeOne() throws Exception {
		// The whole point of the two states: one says "install it", the other says "change its version". A
		// format that collapsed them would make the dialog give the wrong instruction.
		Path file = tmp.resolve("report.tsv");
		DependencyReport.write(file, List.of(absent(), wrongVersion()));

		List<Row> back = DependencyReport.read(file);
		assertNull(back.get(0).installedVersion());
		assertTrue(back.get(0).absent());
		assertEquals("0.8.1", back.get(1).installedVersion());
		assertFalse(back.get(1).absent());
	}

	@Test
	void aFieldThatCouldBreakTheFormatIsMadeSafeRatherThanTrusted() throws Exception {
		Path file = tmp.resolve("report.tsv");
		DependencyReport.write(file, List.of(
				new Row("weird\tid", "name\nwith break", "FABRIC", "dep", "*", null)));

		List<Row> back = DependencyReport.read(file);
		assertEquals(1, back.size(), "a tab inside a field must not split the row");
		assertEquals("weird id", back.get(0).requiredBy());
		assertEquals("name with break", back.get(0).requiredByName());
	}

	@Test
	void aBlankFieldBecomesAQuestionMarkRatherThanAnEmptyColumn() throws Exception {
		Path file = tmp.resolve("report.tsv");
		DependencyReport.write(file, List.of(new Row(null, "  ", "FABRIC", "dep", "*", null)));

		List<Row> back = DependencyReport.read(file);
		assertEquals("?", back.get(0).requiredBy());
		assertEquals("?", back.get(0).requiredByName());
	}

	@Test
	void aTruncatedLineIsDroppedRatherThanMisread() throws Exception {
		Path file = tmp.resolve("report.tsv");
		Files.writeString(file, "only\ttwo\n" + "a\tb\tc\td\te\tf\n");

		List<Row> back = DependencyReport.read(file);
		assertEquals(1, back.size(), "a row without six columns is not a row");
		assertEquals("a", back.get(0).requiredBy());
	}

	@Test
	void theSummaryNamesTheModAndWhatItWantedAndNothingElse() {
		String text = DependencyDialogMain.summary(EN, List.of(absent()), List.of());

		// What a player needs in the first five seconds: the name on the jar they downloaded, and the thing it
		// asked for.
		assertTrue(text.contains("Biomes O' Plenty"), text);
		assertTrue(text.contains("terrablender"), text);
		// And what they do NOT need there. Every one of these is true, is in the details, and is a reason the
		// previous dialog opened looking like a stack trace.
		assertFalse(text.contains(">=26.2.0.0.1"), "the version range belongs in the details: " + text);
		assertFalse(text.contains("FORGE"), "the ecosystem belongs in the fixes and the details: " + text);
		assertFalse(text.contains("biomesoplenty"), "the id belongs in the details: " + text);
	}

	@Test
	void theDetailsTellThePlayerWhichEcosystemsBuildToDownload() {
		String text = DependencyDialogMain.details(EN, List.of(absent()), List.of());

		// On a merged instance the pack does not tell you whether to fetch the Fabric build or the Forge one,
		// and downloading the wrong half is the most likely way to "fix" this and still be broken.
		assertTrue(text.contains("FORGE"), text);
		assertTrue(text.contains("terrablender"), text);
		assertTrue(text.contains(">=26.2.0.0.1"), text);
		assertTrue(text.contains("NOT INSTALLED"), text);
	}

	@Test
	void aVersionMismatchIsWordedAsAVersionMismatch() {
		String summary = DependencyDialogMain.summary(EN, List.of(wrongVersion()), List.of());
		String details = DependencyDialogMain.details(EN, List.of(wrongVersion()), List.of());

		assertFalse(details.contains("NOT INSTALLED"), "sodium IS installed — telling them to install it is wrong");
		assertTrue(details.contains("installed: 0.8.1"), details);
		assertTrue(summary.contains("0.8.1"), "the summary must say what they actually have: " + summary);
	}

	@Test
	void anAbsentDependencyIsOfferedAnInstallAndAPresentOneIsOfferedAVersionChange() {
		// The two states of a finding are the two different things a player can do about it, and the suggestion
		// is the only place the dialog says which. Getting this backwards sends them to download a mod they
		// already have.
		String install = DependencyDialogMain.fixes(EN, List.of(absent()), List.of());
		assertTrue(install.contains("Install terrablender"), install);
		assertTrue(install.contains("FORGE"), "which build to fetch is the whole value of the suggestion: " + install);

		String change = DependencyDialogMain.fixes(EN, List.of(wrongVersion()), List.of());
		assertFalse(change.contains("Install sodium"), "sodium is installed: " + change);
		assertTrue(change.contains("Change sodium to a version inside 0.9.x"), change);
		assertTrue(change.contains("0.8.1"), change);
	}

	@Test
	void everySuggestionIsOfferedAsAPossibilityRatherThanAPromise() {
		// The kernel knows a mod id is not installed. It does NOT know that installing it fixes this pack, and
		// the moment this dialog promises an outcome it cannot establish, it stops being worth believing.
		String text = DependencyDialogMain.fixes(EN, List.of(absent(), wrongVersion()), List.of(mixinBreak()));
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		assertFalse(lower.contains("will fix"), text);
		assertFalse(lower.contains("this fixes"), text);
		assertTrue(lower.contains("may fix") || lower.contains("might fix"), text);
	}

	@Test
	void theLastSuggestionIsTheOneThatAlwaysWorks() {
		// Taking the mod out is the only suggestion here that is certain, and it is last because it costs the
		// player the mod. It must also say what Forbric does next, or it reads as "give up".
		String text = DependencyDialogMain.fixes(EN, List.of(absent()), List.of());
		String[] lines = text.strip().split("\n");
		String last = lines[lines.length - 1];
		assertTrue(last.contains("mods folder"), last);
		assertTrue(last.contains("Biomes O' Plenty"), "it must name what they would be removing: " + last);
		assertTrue(last.contains("rest of your mods still work"), last);
	}

	@Test
	void aLongReportIsCappedInTheSummaryAndWholeInTheDetails() {
		List<Row> many = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			many.add(new Row("mod" + i, "Mod " + i, "FABRIC", "dep" + i, "*", null));
		}
		String summary = DependencyDialogMain.summary(EN, many, List.of());
		String details = DependencyDialogMain.details(EN, many, List.of());

		assertTrue(summary.contains("Mod 0"), summary);
		assertFalse(summary.contains("Mod 39"), "forty bullets is the wall of text the details button exists for");
		assertTrue(summary.contains("and " + (40 - DependencyDialogMain.SUMMARY_BULLETS) + " more"), summary);
		// Capped, never truncated: everything is still one click away.
		assertTrue(details.contains("Mod 39"), "the details must carry every finding");
		assertTrue(details.contains("dep39"), details);
	}

	@Test
	void aSearchIsOfferedForTheIdRatherThanAGuessedModPage() {
		// The kernel knows an id. An id is not a slug on either site, so a mod-page URL built from it would be
		// wrong more often than right — and a dialog that hands a player a dead link has spent its credibility.
		List<String> urls = DependencyDialogMain.searchUrls("terrablender");
		assertEquals(2, urls.size(), "both sites: a great many Forge mods have never been on Modrinth");
		assertTrue(urls.get(0).endsWith("?q=terrablender"), urls.toString());
		assertTrue(urls.get(1).endsWith("?search=terrablender"), urls.toString());
		assertTrue(DependencyDialogMain.details(EN, List.of(absent()), List.of()).contains(urls.get(0)));
	}

	private static DependencyReport.MixinRow mixinBreak() {
		return new DependencyReport.MixinRow("iris", "MixinRenderRegionManager",
				"@At(INVOKE) RenderRegionManager.clearAllCachedBatches in uploadResults");
	}

	@Test
	void bothKindsSurviveTheRoundTripAndStaySeparate() throws Exception {
		Path file = tmp.resolve("both.tsv");
		DependencyReport.write(file, List.of(absent()), List.of(mixinBreak()));

		assertEquals(List.of(absent()), DependencyReport.read(file), "the section marker must not become a row");
		assertEquals(List.of(mixinBreak()), DependencyReport.readMixins(file));
	}

	@Test
	void aMixinOnlyReportStillCarriesItsSection() throws Exception {
		Path file = tmp.resolve("mixinonly.tsv");
		DependencyReport.write(file, List.of(), List.of(mixinBreak()));

		assertTrue(DependencyReport.read(file).isEmpty());
		assertEquals(1, DependencyReport.readMixins(file).size());
	}

	@Test
	void theMixinSectionDoesNotClaimItWillCrash() {
		String summary = DependencyDialogMain.summary(EN, List.of(), List.of(mixinBreak()));
		String notes = DependencyDialogMain.notes(EN, List.of(), List.of(mixinBreak()));

		// The kernel knows an anchor did not resolve. It does NOT know what that costs at runtime, and a dialog
		// that says "will crash" states something its own layer cannot establish.
		assertFalse((summary + notes).toLowerCase(java.util.Locale.ROOT).contains("crash"), summary + notes);
		assertTrue(summary.contains("could not attach"), summary);
		// And it must say why no dependency check caught this, or the player will assume one should have.
		assertTrue(notes.contains("inside the version range"), notes);
	}

	@Test
	void theCaveatComesAfterTheSuggestionsRatherThanBeforeThem() {
		// Ordering, asserted because it is the whole readability change: a player who reads two blocks and acts
		// has read what is wrong and what to do. "What happens if you ignore this" is the block that matters
		// least, and it is what the previous dialog opened with.
		assertFalse(DependencyDialogMain.summary(EN, List.of(absent()), List.of()).contains("launch anyway"),
				"the caveat does not belong in the list of what is wrong");
		String notes = DependencyDialogMain.notes(EN, List.of(absent()), List.of());
		assertTrue(notes.contains("Forbric will launch anyway"), notes);
		assertTrue(notes.contains("worth fixing before you play"), notes);
		// Nothing to caveat when there is nothing of that kind to report.
		assertEquals("", DependencyDialogMain.notes(EN, List.of(), List.of()));
	}

	@Test
	void aMixinBreakIsNamedByTheModRatherThanByItsMixinClass() {
		// "iris" is the name on the jar the player downloaded. "MixinRenderRegionManager" is not a thing they
		// have ever seen, and it is the detail, not the finding.
		String summary = DependencyDialogMain.summary(EN, List.of(), List.of(mixinBreak()));
		assertTrue(summary.contains("iris"), summary);
		assertFalse(summary.contains("MixinRenderRegionManager"), summary);
		assertTrue(DependencyDialogMain.details(EN, List.of(), List.of(mixinBreak()))
				.contains("MixinRenderRegionManager"));
	}

	@Test
	void theTitleMatchesWhatIsActuallyInTheDialog() {
		// A fixed "missing something it requires" is false when the only finding is a mixin that did not attach:
		// both mods are installed. A player who reads the title and stops would hunt for a download that is
		// already there.
		assertTrue(DependencyDialogMain.title(EN, List.of(), List.of(mixinBreak())).contains("do not fit"));
		assertTrue(DependencyDialogMain.title(EN, List.of(absent()), List.of()).contains("missing something"));
		String both = DependencyDialogMain.title(EN, List.of(absent()), List.of(mixinBreak()));
		assertTrue(both.contains("missing") && both.contains("do not fit"), both);
	}

	@Test
	void continuingIsTheKeyboardDefaultInEveryLanguage() {
		// The invariant, asserted on the values the dialog is actually built from rather than on the source text
		// that used to carry them. The first screenshot of this dialog had "Quit" highlighted: a player holding
		// Enter would have lost the launch, which is the opposite of the policy it is built on. Ten languages is
		// ten more chances to get that wrong, so it is checked for all of them.
		for (DialogLang lang : DialogLang.all()) {
			Object[] options = DependencyDialogMain.options(lang);
			assertEquals(2, options.length, lang.tag());
			assertEquals(options[0], DependencyDialogMain.initialOption(lang),
					lang.tag() + ": the keyboard default must be the option that CONTINUES");
			assertEquals(lang.get("button.continue"), options[0], lang.tag());
			assertEquals(lang.get("button.quit"), options[1], lang.tag());
			// The answer is read by comparing the returned value against options[1]. Two identical labels would
			// make "quit" and "continue" indistinguishable, and the dialog would quit on either button.
			assertNotEquals(options[0], options[1], lang.tag() + ": the two answers must not read the same");
		}
	}

	@Test
	void theForkRunsAndItsExitCodeIsTheAnswer() throws Exception {
		// Drives the real ProcessBuilder path. The child is handed a display-less environment, so it takes its
		// own HeadlessException branch and exits CONTINUE — which is the fail-open this whole feature rests on:
		// a dialog that cannot be shown must never be able to stop a launch that would otherwise have worked.
		int answer = DependencyDialog.ask(List.of(absent()), List.of("-Djava.awt.headless=true"));
		assertEquals(DependencyDialogMain.CONTINUE, answer);
	}

	@Test
	void aForkedChildWithNoDisplayStillContinuesWhenTheReportIsLarge() throws Exception {
		// The same fail-open, with the inputs that reach the code paths a one-row report never does: the summary
		// cap, the mixin section, and the details pane's sizing against a screen that is not there. Building the
		// dialog is what throws HeadlessException, and it throws in a different place for each of them.
		List<Row> many = new ArrayList<>();
		for (int i = 0; i < 40; i++) many.add(new Row("mod" + i, "Mod " + i, "FABRIC", "dep" + i, "*", null));
		int answer = DependencyDialog.ask(many, List.of(mixinBreak()), List.of("-Djava.awt.headless=true"));
		assertEquals(DependencyDialogMain.CONTINUE, answer);
	}

	@Test
	void theChildIsToldWhichLanguageToUseOnlyWhenTheParentWasTold() throws Exception {
		// A child JVM inherits the OS locale but not the parent's -D flags, so the switch has to be forwarded or
		// it does nothing in the one process the player reads. Driven through the real fork; the child cannot
		// draw, so what is asserted is that forwarding does not break the fail-open.
		String before = System.getProperty(DialogLang.SWITCH);
		try {
			System.setProperty(DialogLang.SWITCH, "ja");
			assertEquals(DependencyDialogMain.CONTINUE,
					DependencyDialog.ask(List.of(absent()), List.of("-Djava.awt.headless=true")));
		} finally {
			if (before == null) System.clearProperty(DialogLang.SWITCH);
			else System.setProperty(DialogLang.SWITCH, before);
		}
	}
}
