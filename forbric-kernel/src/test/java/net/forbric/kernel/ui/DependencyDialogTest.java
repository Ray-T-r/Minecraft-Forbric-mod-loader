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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.ui.DependencyReport.Row;

/** The pipe between the two processes, and the words the player is shown. */
class DependencyDialogTest {
	@TempDir
	Path tmp;

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
	void theTextTellsThePlayerWhichEcosystemsBuildToDownload() {
		String text = DependencyDialogMain.describe(List.of(absent()));

		// On a merged instance the pack does not tell you whether to fetch the Fabric build or the Forge one,
		// and downloading the wrong half is the most likely way to "fix" this and still be broken.
		assertTrue(text.contains("FORGE"), text);
		assertTrue(text.contains("terrablender"), text);
		assertTrue(text.contains(">=26.2.0.0.1"), text);
		assertTrue(text.contains("NOT INSTALLED"), text);
	}

	@Test
	void aVersionMismatchIsWordedAsAVersionMismatch() {
		String text = DependencyDialogMain.describe(List.of(wrongVersion()));

		assertFalse(text.contains("NOT INSTALLED"), "sodium IS installed — telling them to install it is wrong");
		assertTrue(text.contains("installed: 0.8.1"), text);
	}

	@Test
	void continuingIsTheKeyboardDefault() throws Exception {
		// Read from the source, because JOptionPane's initial value is not observable without showing the
		// dialog, and the thing that must not regress is which button Enter triggers. The first screenshot of
		// this dialog had "Quit" highlighted: a player holding Enter would have lost the launch, which is the
		// opposite of the policy it is built on.
		String source = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/net/forbric/kernel/ui/DependencyDialogMain.java"));
		int options = source.indexOf("new String[] { \"Launch anyway\", \"Quit\" }");
		assertTrue(options > 0, "the two buttons moved — check which one is now the initial value");
		String afterOptions = source.substring(options);
		assertTrue(afterOptions.startsWith("new String[] { \"Launch anyway\", \"Quit\" }, \"Launch anyway\""),
				"the initial value must be Launch anyway, so the keyboard default cannot quit the game");
	}

	@Test
	void theForkRunsAndItsExitCodeIsTheAnswer() throws Exception {
		// Drives the real ProcessBuilder path. The child is handed a display-less environment, so it takes its
		// own HeadlessException branch and exits CONTINUE — which is the fail-open this whole feature rests on:
		// a dialog that cannot be shown must never be able to stop a launch that would otherwise have worked.
		int answer = DependencyDialog.ask(List.of(absent()), List.of("-Djava.awt.headless=true"));
		assertEquals(DependencyDialogMain.CONTINUE, answer);
	}
}
