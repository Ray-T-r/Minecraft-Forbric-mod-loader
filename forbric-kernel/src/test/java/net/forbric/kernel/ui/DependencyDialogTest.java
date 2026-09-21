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
		assertTrue(change.contains("Change sodium to a version that matches 0.9.x"), change);
		assertTrue(change.contains("0.8.1"), change);

		// The constraint is not always a range: ">=2.0.0" and "*" are as common as "[1.0,2.0)". "a version
		// inside >=2.0.0" is not a sentence, and a suggestion that reads as broken English is a suggestion a
		// player discounts.
		String operator = DependencyDialogMain.fixes(EN,
				List.of(new Row("x", "X", "FABRIC", "dep", ">=2.0.0", "1.0.0")), List.of());
		assertTrue(operator.contains("a version that matches >=2.0.0"), operator);
		assertFalse(operator.contains("inside >=2.0.0"), operator);
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
	void theCallSiteHandsSwingTheContinueOptionAsTheDefault() {
		// The invariant that matters, read back off the REAL JOptionPane rather than off a helper that returns
		// options[0] by construction. The previous version of this test asserted initialOption == options[0],
		// which cannot fail, and left the call site — the only place the value actually reaches Swing —
		// unguarded: passing options[1] there would make Enter quit the game in all ten languages and every
		// test would still pass. Swing components are safe to build headless, so this runs on CI.
		for (DialogLang lang : DialogLang.all()) {
			javax.swing.JOptionPane pane = DependencyDialogMain.optionPane(lang, new javax.swing.JLabel("x"));
			assertEquals(lang.get("button.continue"), pane.getInitialValue(),
					lang.tag() + ": Enter must not be able to quit the game");
			assertEquals(lang.get("button.continue"), pane.getOptions()[0], lang.tag());
			assertEquals(lang.get("button.quit"), pane.getOptions()[1], lang.tag());
		}
	}

	@Test
	void onlyTheQuitOptionMeansQuit() {
		// The other half of the same contract. Every value a JOptionPane can hand back that is not exactly the
		// quit option — a closed window, Escape, a value nothing set — has to mean launch.
		for (DialogLang lang : DialogLang.all()) {
			assertEquals(DependencyDialogMain.QUIT,
					DependencyDialogMain.answerFrom(lang, lang.get("button.quit")), lang.tag());
			assertEquals(DependencyDialogMain.CONTINUE,
					DependencyDialogMain.answerFrom(lang, lang.get("button.continue")), lang.tag());
			assertEquals(DependencyDialogMain.CONTINUE, DependencyDialogMain.answerFrom(lang, null), lang.tag());
			assertEquals(DependencyDialogMain.CONTINUE,
					DependencyDialogMain.answerFrom(lang, javax.swing.JOptionPane.UNINITIALIZED_VALUE), lang.tag());
			// The quit label of ANOTHER language must not quit this one: the comparison is against the object
			// that went into this pane's array, not against "some label that means quit somewhere".
			assertEquals(DependencyDialogMain.CONTINUE,
					DependencyDialogMain.answerFrom(lang, "\u0412\u044b\u0445\u043e\u0434 \u2014 not this table"), lang.tag());
		}
	}

	@Test
	void theWindowStacksWhatIsWrongThenWhatToDoThenTheCaveat() {
		// The ordering the redesign is about, asserted on the list show() actually iterates. The previous test
		// only checked that the caveat was in notes() and not in summary(), which stayed green if show() put the
		// caveat back on top or dropped the suggestions from the window entirely.
		List<String> blocks = DependencyDialogMain.blocks(EN, List.of(absent()), List.of(mixinBreak()));
		assertEquals(3, blocks.size());
		assertEquals(DependencyDialogMain.summary(EN, List.of(absent()), List.of(mixinBreak())), blocks.get(0));
		assertEquals(DependencyDialogMain.fixes(EN, List.of(absent()), List.of(mixinBreak())), blocks.get(1));
		assertEquals(DependencyDialogMain.notes(EN, List.of(absent()), List.of(mixinBreak())), blocks.get(2));
		assertTrue(blocks.get(1).contains("What might fix it"), blocks.get(1));
		assertTrue(blocks.get(2).contains("Forbric will launch anyway"), blocks.get(2));
		assertTrue(blocks.get(2).contains("worth fixing before you play"), blocks.get(2));
		// And the caveat is only in the block it belongs to.
		assertFalse(blocks.get(0).contains("launch anyway"),
				"the caveat does not belong in the list of what is wrong: " + blocks.get(0));
		// Nothing to caveat when there is nothing of that kind to report.
		assertEquals("", DependencyDialogMain.notes(EN, List.of(), List.of()));
	}

	@Test
	void aModWithSeveralUnmetRequirementsIsStillOneMod() {
		// A Row is one REQUIREMENT. Counting rows told the player to go and fix three mods when there was one,
		// and put a singular window title over a plural list.
		List<Row> three = List.of(
				new Row("biomesoplenty", "Biomes O' Plenty", "FORGE", "terrablender", "*", null),
				new Row("biomesoplenty", "Biomes O' Plenty", "FORGE", "glitchcore", "*", null),
				new Row("biomesoplenty", "Biomes O' Plenty", "FORGE", "curios", "*", null));

		String summary = DependencyDialogMain.summary(EN, three, List.of());
		assertTrue(summary.startsWith("One mod is missing something it requires:"), summary);
		assertFalse(summary.contains("3 mods"), summary);
		// Every requirement is still listed — the count is what was wrong, not the detail.
		assertTrue(summary.contains("terrablender") && summary.contains("glitchcore")
				&& summary.contains("curios"), summary);
		assertTrue(DependencyDialogMain.title(EN, three, List.of()).contains("a mod is missing"),
				"the title must not argue with the line under it");
	}

	@Test
	void twoModsEachMissingSomethingAreTwoModsAndSayItInTheTitle() {
		List<Row> two = List.of(absent(), wrongVersion());
		assertTrue(DependencyDialogMain.summary(EN, two, List.of()).startsWith("2 mods are missing"),
				DependencyDialogMain.summary(EN, two, List.of()));
		assertTrue(DependencyDialogMain.title(EN, two, List.of()).contains("some mods are missing"),
				DependencyDialogMain.title(EN, two, List.of()));
	}

	@Test
	void oneModsThreeBrokenMixinsAreOneMod() {
		// A MixinRow is one mixin CLASS, and one mod's config routinely breaks in several places at once — the
		// Iris/Sodium case in ForeignMixinBreaks' own javadoc is exactly that. Counting rows reported one mod as
		// three, printed its name three times, and spent three of the six summary slots on one sentence.
		List<DependencyReport.MixinRow> three = List.of(
				new DependencyReport.MixinRow("iris", "MixinRenderRegionManager", "a"),
				new DependencyReport.MixinRow("iris", "MixinChunkRenderer", "b"),
				new DependencyReport.MixinRow("iris", "MixinSodiumWorldRenderer", "c"));

		String summary = DependencyDialogMain.summary(EN, List.of(), three);
		assertTrue(summary.startsWith("One mod could not attach"), summary);
		assertEquals(1, summary.split("could not attach to the mod it was built for", -1).length - 1, summary);

		String fixes = DependencyDialogMain.fixes(EN, List.of(), three);
		assertEquals(1, fixes.split("only their builds do not match", -1).length - 1,
				"the same suggestion three times is three wasted slots: " + fixes);
		// All three mixin classes are still in the details, where a bug report needs them.
		String details = DependencyDialogMain.details(EN, List.of(), three);
		assertTrue(details.contains("MixinRenderRegionManager") && details.contains("MixinChunkRenderer")
				&& details.contains("MixinSodiumWorldRenderer"), details);
	}

	@Test
	void aModInBothSectionsIsNamedOnceAndByItsName() {
		// Dependency rows carry a display name, mixin rows carry a mod id. A mod with both kinds of finding was
		// listed twice, in two spellings — "take Iris Shaders, iris out of your mods folder" — sending the
		// player to look for a second jar that does not exist.
		Row row = new Row("iris", "Iris Shaders", "NEOFORGE", "sodium", "0.9.x", "0.8.1");
		DependencyReport.MixinRow mixin = new DependencyReport.MixinRow("iris", "MixinRenderRegionManager", "a");

		String fixes = DependencyDialogMain.fixes(EN, List.of(row), List.of(mixin));
		String[] lines = fixes.strip().split("\n");
		String remove = lines[lines.length - 1];
		assertTrue(remove.contains("Iris Shaders"), remove);
		assertFalse(remove.contains(", iris"), "one jar, one name: " + remove);

		// And the bullet calls it by the name on the jar, not by the id.
		assertTrue(DependencyDialogMain.summary(EN, List.of(row), List.of(mixin))
				.contains("Iris Shaders could not attach"),
				DependencyDialogMain.summary(EN, List.of(row), List.of(mixin)));
	}

	@Test
	void aModNameThatLooksLikeAPlaceholderIsShownAsItIs() {
		// Display names come out of a third party's manifest. A mod calling itself "Cool {3} Mod" must reach the
		// player under the name on its jar — it is the one identifier this dialog exists to hand them.
		String text = DependencyDialogMain.summary(EN,
				List.of(new Row("cool", "Cool {3} Mod", "FABRIC", "coolid", "[1.0,2.0)", "0.9")), List.of());
		assertTrue(text.contains("Cool {3} Mod"), text);
	}

	@Test
	void theTwoAnswersNeverReadTheSame() {
		// answerFrom() tells them apart by their labels, so two tables' worth of identical strings would make
		// both buttons quit — or both launch — with nothing else in the code able to notice.
		for (DialogLang lang : DialogLang.all()) {
			assertNotEquals(lang.get("button.continue"), lang.get("button.quit"), lang.tag());
		}
	}

	@Test
	void theMessageAndTheDetailsTogetherLeaveRoomForTheAnswerButtons() {
		// The defect this guards: the message used to be laid out at its own preferred height, and a stack of
		// wrapping text areas reports a MINIMUM height equal to its preferred one. On a screen too short for it,
		// JOptionPane's BoxLayout had nothing it could compress and put "Launch anyway" and "Quit" past the
		// bottom edge of a window that was already as tall as the screen — a warning with no reachable answer.
		javax.swing.JScrollPane head = new javax.swing.JScrollPane(tall(4000));
		javax.swing.JScrollPane details = new javax.swing.JScrollPane(tall(4000));
		details.setVisible(false);

		// A 1366x768 laptop with a taskbar.
		java.awt.Rectangle usable = new java.awt.Rectangle(0, 0, 1366, 728);
		int toggle = 37;
		DependencyDialogMain.budget(head, details, usable, toggle);
		assertTrue(head.getPreferredSize().height + toggle + DependencyDialogMain.CHROME <= usable.height,
				"collapsed: " + head.getPreferredSize().height);

		details.setVisible(true);
		DependencyDialogMain.budget(head, details, usable, toggle);
		int total = head.getPreferredSize().height + details.getPreferredSize().height + toggle
				+ DependencyDialogMain.CHROME;
		assertTrue(total <= usable.height, "expanded: " + total + " > " + usable.height);
		assertTrue(details.getPreferredSize().height > 0, "the details must still get room to be read in");
	}

	@Test
	void aShortMessageIsNotPaddedOutToTheScreen() {
		// The other direction: the budget is a ceiling, not a target. A one-finding dialog must stay small.
		javax.swing.JScrollPane head = new javax.swing.JScrollPane(tall(120));
		javax.swing.JScrollPane details = new javax.swing.JScrollPane(tall(120));
		details.setVisible(false);
		DependencyDialogMain.budget(head, details, new java.awt.Rectangle(0, 0, 1920, 1080), 37);
		assertEquals(120, head.getPreferredSize().height);
	}

	private static javax.swing.JComponent tall(int height) {
		javax.swing.JPanel panel = new javax.swing.JPanel();
		panel.setPreferredSize(new java.awt.Dimension(640, height));
		return panel;
	}

	@Test
	void aBulletIsWrappedAtTheWidthItIsActuallyGiven() {
		// item() used to wrap the body at TEXT_WIDTH minus a CONSTANT marker column, while BorderLayout gave the
		// marker its real preferred width — insets plus the bullet glyph's advance at that font. Above about
		// 18pt the two disagree, the body wraps onto a line the already-pinned row height has no room for, and
		// the last line of the sentence is silently cut off. Font metrics work headless, so this runs on CI.
		String text = "Just Enough Items necesita jei, que no está instalado, y sin él la pantalla de recetas "
				+ "no aparece en el juego.";
		for (int size : new int[] { 11, 13, 16, 20, 24, 28 }) {
			java.awt.Font font = new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, size);
			javax.swing.JPanel row = DependencyDialogMain.item("•", text, font);
			row.setSize(row.getPreferredSize());
			row.doLayout();

			javax.swing.JTextArea area = (javax.swing.JTextArea) ((java.awt.BorderLayout) row.getLayout())
					.getLayoutComponent(java.awt.BorderLayout.CENTER);
			// The invariant, stated structurally: the width the text was WRAPPED at and the width BorderLayout
			// GIVES it are the same number. Any gap between them is a height computed for a layout that will not
			// happen, and the row's height is already pinned when the real layout disagrees.
			int wrappedAt = area.getPreferredSize().width;
			assertEquals(wrappedAt, area.getWidth(),
					size + "pt: wrapped at " + wrappedAt + "px, laid out at " + area.getWidth() + "px");

			// And the consequence, measured: what it needs at that width still fits the row.
			area.setSize(new java.awt.Dimension(area.getWidth(), Short.MAX_VALUE));
			int needed = area.getPreferredSize().height;
			assertTrue(row.getPreferredSize().height >= needed,
					size + "pt: the row is " + row.getPreferredSize().height + "px for text that needs "
							+ needed + "px at " + area.getWidth() + "px wide — the last line is cut off");
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
