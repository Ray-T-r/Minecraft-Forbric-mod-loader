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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * Naming the mods a crash report points at.
 *
 * <p>The inputs are real crash reports this loader produced, kept in {@code run/}, rather than hand-written
 * traces: the whole value of this feature is that it works on what the game actually writes, and a fixture I
 * wrote myself would agree with my code by construction.
 */
class CrashAttributionTest {
	@TempDir
	Path tmp;

	private static final Path RUN = Path.of("run");

	@AfterEach
	void clearCatalogue() {
		ModCatalog.publish(List.of());
	}

	private static ModCatalog.Entry mod(String id, String name, String jar) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, name, "1.0", "", List.of(), jar, "", "");
	}

	private static String read(Path report) throws Exception {
		return Files.readString(report, StandardCharsets.UTF_8);
	}

	/** The first crash report under {@code run/} whose text contains {@code marker}, or null. */
	private static Path find(String marker) throws Exception {
		if (!Files.isDirectory(RUN)) return null;
		try (var walk = Files.walk(RUN, 3)) {
			for (Path p : walk.filter(Files::isRegularFile)
					.filter(p -> p.getParent() != null
							&& p.getParent().getFileName().toString().equals("crash-reports"))
					.toList()) {
				try {
					if (Files.readString(p, StandardCharsets.UTF_8).contains(marker)) return p;
				} catch (Exception unreadable) {
					// A half-written report from an interrupted gate is not this test's problem.
				}
			}
		}
		return null;
	}

	@Test
	void theTopFrameSJarNamesTheMod() throws Exception {
		Path report = find("supermartijn642corelib");
		org.junit.jupiter.api.Assumptions.assumeTrue(report != null, "no such crash report in run/");
		ModCatalog.publish(List.of(
				mod("supermartijn642corelib", "SuperMartijn642's Core Lib",
						"supermartijn642corelib-1.1.24a-forge-mc26.2.jar"),
				mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar")));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(read(report));

		assertFalse(suspects.isEmpty(), "the top frame of this crash is a mod jar");
		assertEquals("supermartijn642corelib", suspects.get(0).modId(),
				"the frame that threw is a better suspect than the frame that called it");
		// A mod that is installed but nowhere in the trace is not a suspect.
		assertTrue(suspects.stream().noneMatch(s -> s.modId().equals("sodium")), suspects.toString());
	}

	@Test
	void theKernelAndTheGameAreNeverSuspects() throws Exception {
		Path report = find("supermartijn642corelib");
		org.junit.jupiter.api.Assumptions.assumeTrue(report != null, "no such crash report in run/");
		// Nothing published at all: the catalogue is the deny-list. Every frame in this report belongs to the
		// merged base, a runtime carrier, the kernel jar or the JDK, and none of those is a mod.
		ModCatalog.publish(List.of());

		assertEquals(List.of(), CrashAttribution.suspects(read(report)),
				"patched-mc-merged, the carriers and forbric-kernel's own jar are not mods");
	}

	@Test
	void aJarNameIsNeverTurnedIntoAModId() throws Exception {
		Path report = find("supermartijn642corelib");
		org.junit.jupiter.api.Assumptions.assumeTrue(report != null, "no such crash report in run/");
		// The jar is in the trace and the catalogue has a mod, but the mod's jar is spelled differently. A
		// file name is not a mod id -- xaeroworldmap-*.jar carries xaerominimap-family ids, and jars renamed to
		// a content hash are real -- so this must find nothing rather than guess from the name.
		ModCatalog.publish(List.of(mod("supermartijn642corelib", "Core Lib", "some-other-name.jar")));

		assertEquals(List.of(), CrashAttribution.suspects(read(report)));
	}

	@Test
	void aMixinHandlerInsideAVanillaClassBlamesTheModAndNotMinecraft() {
		// The one way the jar bracket gets it backwards: this is sodium's code, under a Minecraft class name,
		// carrying the merged base's jar.
		ModCatalog.publish(List.of(
				mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar"),
				mod("minecraft", "Minecraft", "patched-mc-merged-26.2.jar")));
		String trace = "java.lang.NullPointerException\n"
				+ "\tat forbric/net.minecraft.client.Minecraft.handler$chm000$sodium$loadConfig"
				+ "(Minecraft.java:11106) ~[patched-mc-merged-26.2.jar:?] {}\n";

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(trace);

		assertEquals("sodium", suspects.get(0).modId(), suspects.toString());
		assertEquals("its mixin was running", suspects.get(0).reason());
	}

	@Test
	void aHandlerWhoseTokenIsNotAModIdSaysNothing() {
		// About half the handler frames in run/ carry a method name in that position rather than a mod id --
		// handler$zpf000$mutableSpecialElementRenderers. The token is Mixin's convention, not a guarantee, so
		// it counts only when the catalogue already knows it.
		ModCatalog.publish(List.of(mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar")));
		String trace = "java.lang.IllegalStateException\n"
				+ "\tat forbric/net.minecraft.client.Minecraft.handler$zpf000$mutableSpecialElementRenderers"
				+ "(Minecraft.java:900) ~[patched-mc-merged-26.2.jar:?] {}\n";

		assertEquals(List.of(), CrashAttribution.suspects(trace));
	}

	@Test
	void mixinsOwnWordsAreBelievedWhenTheyNameAMod() throws Exception {
		Path report = Path.of("..", "crash", "crash-report.txt");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(report), "no captured crash report");
		ModCatalog.publish(List.of(mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar")));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(read(report));

		// "Mixin [sodium-common.mixins.json:...LevelExtractorMixin from mod sodium] ... FAILED during APPLY".
		// The whole crash is sodium's, and the Suspected Mods line in that very file says NONE.
		assertEquals("sodium", suspects.get(0).modId(), suspects.toString());
		assertEquals("Mixin named it", suspects.get(0).reason());
	}

	@Test
	void onlyTheExceptionChainIsRead() throws Exception {
		Path report = find("-- Head --");
		org.junit.jupiter.api.Assumptions.assumeTrue(report != null, "no such crash report in run/");
		String whole = read(report);
		String chain = CrashAttribution.exceptionChain(whole);

		assertTrue(chain.length() < whole.length(), "the walkthrough divider must cut the file");
		assertFalse(chain.contains("-- System Details --"), chain);
		// Everything below the divider is Minecraft's own report -- thread dumps, the mod lists, the graphics
		// card. A mod named there is named for being installed, not for being involved.
		assertFalse(chain.contains("Mod List:"), "the mod list is not evidence");
	}

	@Test
	void theAnswerIsAHandfulOfNamesRatherThanASecondList() {
		List<ModCatalog.Entry> many = new java.util.ArrayList<>();
		StringBuilder trace = new StringBuilder("java.lang.RuntimeException\n");
		for (int i = 0; i < 20; i++) {
			many.add(mod("mod" + i, "Mod " + i, "mod" + i + ".jar"));
			trace.append("\tat forbric/com.x.Y.z(Y.java:1) ~[mod").append(i).append(".jar:?] {}\n");
		}
		ModCatalog.publish(many);

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(trace.toString());
		assertEquals(CrashAttribution.MOST, suspects.size(), "past a handful this stops being an answer");
		assertEquals("mod0", suspects.get(0).modId(), "topmost frame first");
	}

	@Test
	void bothRenderingsSayWhatToDoAndThatItIsAGuess() {
		List<CrashAttribution.Suspect> one =
				List.of(new CrashAttribution.Suspect("sodium", "Sodium", "0.9.0", "its mixin was running", 2));

		String en = CrashAttribution.render(false, "crash-2026-09-20_17.17.35-client.txt", one);
		assertTrue(en.contains("Sodium 0.9.0"), en);
		assertTrue(en.contains("mods folder"), "a player needs to be told what to do next: " + en);
		assertTrue(en.contains("This is a guess"), "it must not claim more than it knows: " + en);
		assertTrue(en.contains("crash-2026-09-20_17.17.35-client.txt"), en);

		String zh = CrashAttribution.render(true, "crash-2026-09-20_17.17.35-client.txt", one);
		assertTrue(zh.contains("Sodium 0.9.0"), zh);
		assertTrue(zh.contains("mods 文件夹"), zh);
		assertTrue(zh.contains("只是个猜测"), zh);
	}

	@Test
	void namingNothingIsSaidOutLoudRatherThanLeftBlank() {
		// A crash with no mod in it is a real answer -- it may not be a mod at all -- and a file that just
		// stopped would read as broken.
		String en = CrashAttribution.render(false, "crash.txt", List.of());
		assertTrue(en.contains("No mod you installed appears in this crash"), en);
		assertTrue(CrashAttribution.render(true, "crash.txt", List.of()).contains("说不准是哪个 mod"));
	}

	@Test
	void theNewestReportIsTheOneThisRunWrote() throws Exception {
		Path dir = tmp.resolve("crash-reports");
		Files.createDirectories(dir);
		Path old = Files.writeString(dir.resolve("crash-old.txt"), "old");
		Path recent = Files.writeString(dir.resolve("crash-new.txt"), "new");
		Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
		Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.fromMillis(2_000_000));

		assertEquals(recent, CrashAttribution.crashReportFromThisRun(dir, 1_500_000));
		// A rundir that never crashed has no directory at all, and that is not an error.
		assertEquals(null, CrashAttribution.crashReportFromThisRun(tmp.resolve("nope"), 0));
	}

	@Test
	void aCrashReportFromAnEarlierRunIsNotThisRunsCrash() throws Exception {
		// A rundir keeps every crash report it has ever produced and nothing deletes them. Taking "the newest
		// one" would make every CLEAN quit announce last week's crash — worse than silence, because it teaches
		// the player that the file means nothing.
		Path dir = tmp.resolve("crash-reports");
		Files.createDirectories(dir);
		Path lastWeek = Files.writeString(dir.resolve("crash-old.txt"), "old");
		Files.setLastModifiedTime(lastWeek, java.nio.file.attribute.FileTime.fromMillis(1_000_000));

		assertEquals(null, CrashAttribution.crashReportFromThisRun(dir, 2_000_000),
				"this run started after that report was written");
	}

	@Test
	void theWholePathWritesAFileBesideTheCrashReport() throws Exception {
		// The write path end to end, on a real crash report, without needing to crash a game: stage it in a
		// rundir, say the run started before it, and run the shutdown hook's body.
		Path source = find("supermartijn642corelib");
		org.junit.jupiter.api.Assumptions.assumeTrue(source != null, "no such crash report in run/");
		Path dir = tmp.resolve("crash-reports");
		Files.createDirectories(dir);
		Path staged = dir.resolve(source.getFileName().toString());
		Files.copy(source, staged);
		Files.setLastModifiedTime(staged, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		ModCatalog.publish(List.of(mod("supermartijn642corelib", "SuperMartijn642's Core Lib",
				"supermartijn642corelib-1.1.24a-forge-mc26.2.jar")));

		CrashAttribution.setRunDir(tmp, 0);
		CrashAttribution.run();

		Path written = tmp.resolve(".forbric-kernel").resolve("crash-analysis.txt");
		assertTrue(Files.isRegularFile(written), "nothing was written");
		String text = Files.readString(written, StandardCharsets.UTF_8);
		assertTrue(text.contains("SuperMartijn642's Core Lib"), text);
		assertTrue(text.contains(source.getFileName().toString()), "it must point at the real report: " + text);
	}

	@Test
	void aRunThatDidNotCrashWritesNothing() throws Exception {
		ModCatalog.publish(List.of(mod("sodium", "Sodium", "sodium.jar")));
		CrashAttribution.setRunDir(tmp, 0);
		CrashAttribution.run();

		assertFalse(Files.exists(tmp.resolve(".forbric-kernel").resolve("crash-analysis.txt")),
				"a file that appears only when something went wrong is a file whose presence means something");
	}

	@Test
	void theSwitchTurnsItOff() {
		String before = System.getProperty(CrashAttribution.SWITCH);
		try {
			assertTrue(CrashAttribution.enabled(), "on by default: the player who needs this passes no flags");
			System.setProperty(CrashAttribution.SWITCH, "off");
			assertFalse(CrashAttribution.enabled());
		} finally {
			if (before == null) System.clearProperty(CrashAttribution.SWITCH);
			else System.setProperty(CrashAttribution.SWITCH, before);
		}
	}
}
