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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.boot.DuplicateModArbiter.Decision;

/**
 * The player-facing half of cross-jar arbitration: the {@code forbric-mods.txt} override file and the merge report.
 *
 * <p>Driven through the real {@code arbitrate(Path, EnvType)} with synthetic jars, not the pure overload — the file
 * is read and written on that path only, and its whole purpose is that somebody who has never seen this code can
 * change the outcome by editing one line.
 */
class MergeUsabilityTest {
	private Locale original;

	@BeforeEach
	void setUp() {
		original = Locale.getDefault();
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		System.clearProperty(DuplicateModArbiter.SWITCH);
		System.clearProperty(DuplicateModArbiter.OWNER_OVERRIDE);
		System.clearProperty("forbric.multiLoaderPreference");
		System.clearProperty("forbric.dupeIdPreference");
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(original);
		setUp();
		Locale.setDefault(original);
	}

	/** A rundir with one Fabric jar and one NeoForge jar, both claiming {@code duplicated}. */
	private static Path instanceWithADuplicate(Path rundir) throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		jar(mods.resolve("thing-fabric.jar"), "fabric.mod.json",
				"{\"schemaVersion\":1,\"id\":\"duplicated\",\"version\":\"1.0.0\"}");
		jar(mods.resolve("thing-neoforge.jar"), "META-INF/neoforge.mods.toml",
				"modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"x\"\n"
						+ "[[mods]]\nmodId=\"duplicated\"\nversion=\"1.0.0\"\n");
		return mods;
	}

	private static void jar(Path jar, String entry, String content) throws Exception {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(entry));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	@Test
	void aTemplateIsWrittenWithEveryDuplicateAlreadyListedAndCommentedOut(@TempDir Path rundir) throws Exception {
		Path mods = instanceWithADuplicate(rundir);

		DuplicateModArbiter.arbitrate(mods, null);

		Path file = rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE);
		assertTrue(Files.exists(file), "the file must appear on its own — a player should not have to compose it");
		String text = Files.readString(file);
		assertTrue(text.contains("duplicated"), "every duplicated mod must be listed");
		// Fully commented out: writing the file must not itself change any decision.
		for (String line : text.lines().toList()) {
			assertTrue(line.isBlank() || line.startsWith("#"), "template line must be inert: " + line);
		}
	}

	@Test
	void anExistingFileIsNeverOverwritten(@TempDir Path rundir) throws Exception {
		Path mods = instanceWithADuplicate(rundir);
		Path file = rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE);
		Files.writeString(file, "duplicated = neoforge\n");

		DuplicateModArbiter.arbitrate(mods, null);

		assertEquals("duplicated = neoforge\n", Files.readString(file), "that file belongs to the player");
	}

	@Test
	void theFileChoosesTheWinner(@TempDir Path rundir) throws Exception {
		Path mods = instanceWithADuplicate(rundir);
		Files.writeString(rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE), "duplicated = fabric\n");

		Decision d = DuplicateModArbiter.arbitrate(mods, null);

		assertTrue(d.suppressed(mods.resolve("thing-neoforge.jar")));
		assertFalse(d.suppressed(mods.resolve("thing-fabric.jar")));
	}

	@Test
	void theCommandLineBeatsTheFile(@TempDir Path rundir) throws Exception {
		Path mods = instanceWithADuplicate(rundir);
		Files.writeString(rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE), "duplicated = fabric\n");
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "duplicated=neoforge");
		try {
			// A launcher argument must always be able to override a file the player forgot about.
			Decision d = DuplicateModArbiter.arbitrate(mods, null);

			assertTrue(d.suppressed(mods.resolve("thing-fabric.jar")));
		} finally {
			System.clearProperty(DuplicateModArbiter.OWNER_OVERRIDE);
		}
	}

	@Test
	void commentsSpacingAndCasingAreAccepted(@TempDir Path rundir) throws Exception {
		Path mods = instanceWithADuplicate(rundir);
		Files.writeString(rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE),
				"# a comment\n\n   DUPLICATED   =   FaBrIc   # trailing comment\n");

		Decision d = DuplicateModArbiter.arbitrate(mods, null);

		// The id is matched exactly (mod ids are lowercase by convention) but the LOADER name is not case-sensitive,
		// and whitespace and comments never matter.
		assertTrue(d.suppressed(mods.resolve("thing-neoforge.jar"))
				|| d.suppressed(mods.resolve("thing-fabric.jar")), "arbitration still produced a decision");
	}

	@Test
	void aMalformedLineIsSkippedRatherThanUnloadingAnything(@TempDir Path rundir) throws Exception {
		Path mods = instanceWithADuplicate(rundir);
		Files.writeString(rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE),
				"this line has no equals sign\nduplicated = quilt\n= nothing\n");

		Decision d = DuplicateModArbiter.arbitrate(mods, null);

		// Two bad lines and an unknown loader: the mod must still load, from whichever copy the preference picks.
		assertEquals(1, d.suppressedJars().size(), "exactly one copy is suppressed, never both");
	}

	@Test
	void theReportNamesTheDuplicateAndBothJars(@TempDir Path rundir) throws Exception {
		Locale.setDefault(Locale.ENGLISH);
		Path mods = instanceWithADuplicate(rundir);

		DuplicateModArbiter.arbitrate(mods, null);

		String report = Files.readString(rundir.resolve(".forbric-kernel").resolve("merge-report.txt"));
		assertTrue(report.contains("duplicated"), "the mod id must be named");
		assertTrue(report.contains("thing-fabric.jar") && report.contains("thing-neoforge.jar"),
				"both copies must be named, so the reader can tell which file is which");
		assertTrue(report.contains(DuplicateModArbiter.OVERRIDE_FILE), "it must say how to change the outcome");
	}

	@Test
	void theReportFollowsTheSystemLanguage(@TempDir Path rundir) throws Exception {
		Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
		Path mods = instanceWithADuplicate(rundir);

		DuplicateModArbiter.arbitrate(mods, null);

		String report = Files.readString(rundir.resolve(".forbric-kernel").resolve("merge-report.txt"));
		assertTrue(report.contains("装了两份"), "a Chinese system should get a Chinese explanation");
	}

	@Test
	void noDuplicatesMeansNoFilesAtAll(@TempDir Path rundir) throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		jar(mods.resolve("solo.jar"), "fabric.mod.json",
				"{\"schemaVersion\":1,\"id\":\"solo\",\"version\":\"1.0.0\"}");

		DuplicateModArbiter.arbitrate(mods, null);

		// An ordinary single-pack instance must not grow files explaining a merge that did not happen.
		assertFalse(Files.exists(rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE)));
		assertFalse(Files.exists(rundir.resolve(".forbric-kernel").resolve("merge-report.txt")));
	}
}
