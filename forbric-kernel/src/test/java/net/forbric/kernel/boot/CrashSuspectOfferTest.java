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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.ui.DependencyDialogMain;
import net.forbric.kernel.ui.DependencyReport;

/**
 * The launch after a crash: offered once, answered through a stand-in for the dialog child, and what each answer
 * leaves behind on disk.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class CrashSuspectOfferTest {
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

	@TempDir
	Path game;

	private Path mods;
	private Locale original;
	/** Every isolation the stand-in dialog was asked about. */
	private final List<DependencyReport.Isolation> asked = new ArrayList<>();

	@BeforeEach
	void fresh() throws Exception {
		original = Locale.getDefault();
		Locale.setDefault(Locale.ENGLISH);
		mods = Files.createDirectories(game.resolve("mods"));
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
	}

	@AfterEach
	void restore() {
		Locale.setDefault(original);
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		ModCatalog.publish(List.of());
	}

	private CrashSuspectOffer.Dialog answering(int answer) {
		return isolation -> {
			asked.add(isolation);
			return answer;
		};
	}

	private Path pending() {
		return game.resolve(".forbric-kernel").resolve(CrashSuspectOffer.PENDING);
	}

	private Path offered() {
		return game.resolve(".forbric-kernel").resolve(CrashSuspectOffer.OFFERED);
	}

	private void crashPointingAt(String report, CrashAttribution.Suspect... suspects) throws Exception {
		Files.createDirectories(pending().getParent());
		Files.writeString(pending(), CrashAttribution.json(report, List.of(suspects)));
	}

	private static CrashAttribution.Suspect suspect(String id, String name, String jar) {
		return new CrashAttribution.Suspect(id, name, "1.0", "its code is in the crash", 3, jar);
	}

	private static CrashAttribution.Suspect clashing(String id, String name, String jar) {
		return new CrashAttribution.Suspect(id, name, "1.0", CrashAttribution.CLASH, 2, jar);
	}

	@Test
	void noCrashMeansNoQuestion() {
		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.QUIT), TODAY));
		assertEquals(List.of(), asked);
	}

	@Test
	void startingWithoutThemListsTheirJarsUnderADatedCommentAndArbitrationLeavesThemOut() throws Exception {
		Path sodium = fabricJar("sodium.jar", "sodium");
		Path iris = fabricJar("iris.jar", "iris");
		Path keep = fabricJar("keep.jar", "keep");
		crashPointingAt("crash-1.txt", suspect("sodium", "Sodium", "sodium.jar"), suspect("iris", "Iris", "iris.jar"));

		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY));

		assertEquals(1, asked.size());
		DependencyReport.Isolation isolation = asked.get(0);
		assertEquals("crash-1.txt", isolation.report());
		assertEquals("", isolation.kept());
		assertEquals(List.of(new DependencyReport.IsolationRow("sodium", "Sodium", "sodium.jar"),
				new DependencyReport.IsolationRow("iris", "Iris", "iris.jar")), isolation.without());
		String file = Files.readString(game.resolve(DisabledMods.FILE));
		assertTrue(file.contains("# 2026-10-03: switched off after the crash in crash-reports/crash-1.txt"), file);
		assertTrue(file.contains("\nsodium.jar\niris.jar\n"), file);
		assertFalse(Files.exists(pending()), "asked once: the pending file is retired whatever the answer");
		assertTrue(Files.exists(offered()));

		var decision = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);
		assertTrue(decision.suppressed(sodium) && decision.suppressed(iris), decision.toString());
		assertFalse(decision.suppressed(keep));
	}

	@Test
	void startingWithEverythingOrClosingTheWindowWritesNothing() throws Exception {
		fabricJar("sodium.jar", "sodium");
		crashPointingAt("crash-1.txt", suspect("sodium", "Sodium", "sodium.jar"));

		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.CONTINUE), TODAY));

		assertEquals(1, asked.size());
		assertFalse(Files.exists(game.resolve(DisabledMods.FILE)));
		assertFalse(Files.exists(pending()));
		assertTrue(Files.exists(offered()));
	}

	@Test
	void quitEndsTheLaunchAndIsNotAskedAgain() throws Exception {
		fabricJar("sodium.jar", "sodium");
		crashPointingAt("crash-1.txt", suspect("sodium", "Sodium", "sodium.jar"));

		assertFalse(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.QUIT), TODAY));
		assertFalse(Files.exists(game.resolve(DisabledMods.FILE)));
		assertTrue(Files.exists(offered()));

		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.QUIT), TODAY));
		assertEquals(1, asked.size(), "the next launch is not asked about the same crash");
	}

	@ExpectCaught("could not show the crash-suspects offer")
	@Test
	void aDialogThatFailsStartsWithEveryMod() throws Exception {
		fabricJar("sodium.jar", "sodium");
		crashPointingAt("crash-1.txt", suspect("sodium", "Sodium", "sodium.jar"));

		assertTrue(CrashSuspectOffer.run(game, null, isolation -> { throw new java.io.IOException("no java"); }, TODAY));

		assertFalse(Files.exists(game.resolve(DisabledMods.FILE)));
		assertTrue(Files.exists(offered()));
	}

	@Test
	void withNoWindowItOnlyLogsAndKeepsTheOfferForALaunchThatCanAsk() throws Exception {
		fabricJar("sodium.jar", "sodium");
		crashPointingAt("crash-1.txt", suspect("sodium", "Sodium", "sodium.jar"));

		String said = KernelLoadReportTest.capture(() ->
				assertTrue(CrashSuspectOffer.run(game, "not the client", answering(DependencyDialogMain.WITHOUT), TODAY)));

		assertEquals(List.of(), asked, "a server or a headless run must never be asked");
		assertTrue(said.contains("not the client") && said.contains("forbric-disabled.txt: sodium.jar"), said);
		assertFalse(Files.exists(game.resolve(DisabledMods.FILE)));
		assertTrue(Files.exists(pending()), "nobody was asked, so it is still pending");
	}

	@Test
	void aRenameThatFailedIsNotAskedAgainButANewerCrashIs() throws Exception {
		fabricJar("sodium.jar", "sodium");
		crashPointingAt("crash-1.txt", suspect("sodium", "Sodium", "sodium.jar"));
		Files.writeString(offered(), Files.readString(pending()));

		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY));
		assertEquals(List.of(), asked);
		assertFalse(Files.exists(pending()));

		crashPointingAt("crash-2.txt", suspect("sodium", "Sodium", "sodium.jar"));
		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.CONTINUE), TODAY));
		assertEquals(1, asked.size());
		assertEquals("crash-2.txt", asked.get(0).report());
	}

	@Test
	void onlyJarsStillInModsAndNotAlreadySwitchedOffAreOffered() throws Exception {
		fabricJar("sodium.jar", "sodium");
		fabricJar("iris.jar", "iris");
		Files.writeString(game.resolve(DisabledMods.FILE), "iris.jar\n");
		crashPointingAt("crash-1.txt", suspect("gone", "Gone", "gone.jar"), suspect("iris", "Iris", "iris.jar"),
				suspect("sodium", "Sodium", "sodium.jar"), suspect("nojar", "Bundled", ""));

		CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.CONTINUE), TODAY);

		assertEquals(List.of(new DependencyReport.IsolationRow("sodium", "Sodium", "sodium.jar")), asked.get(0).without());
	}

	@Test
	void aJarNameTheFileCannotHoldIsNeverOffered() throws Exception {
		fabricJar("sodium.jar", "sodium");
		Files.writeString(game.resolve("outside.jar"), "not in mods/");
		crashPointingAt("crash-1.txt", suspect("outside", "Outside", "../outside.jar"),
				suspect("hash", "Hash", "a#b.jar"), suspect("sodium", "Sodium", "sodium.jar"));

		CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY);

		assertEquals(List.of(new DependencyReport.IsolationRow("sodium", "Sodium", "sodium.jar")), asked.get(0).without());
		assertEquals(List.of("sodium.jar"), DisabledMods.parse(Files.readAllLines(game.resolve(DisabledMods.FILE))));
	}

	@Test
	void nothingLeftToSwitchOffAsksNothing() throws Exception {
		crashPointingAt("crash-1.txt", suspect("gone", "Gone", "gone.jar"));

		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY));

		assertEquals(List.of(), asked);
		assertFalse(Files.exists(game.resolve(DisabledMods.FILE)));
	}

	@Test
	void aClashKeepsTheFirstNamedSide() throws Exception {
		fabricJar("chloride.jar", "chloride");
		fabricJar("cwb.jar", "cwb");
		fabricJar("sodium.jar", "sodium");
		crashPointingAt("crash-1.txt", clashing("chloride", "Chloride", "chloride.jar"),
				clashing("cwb", "Cubes Without Borders", "cwb.jar"), suspect("sodium", "Sodium", "sodium.jar"));

		CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY);

		assertEquals("Chloride", asked.get(0).kept());
		assertEquals(List.of(new DependencyReport.IsolationRow("cwb", "Cubes Without Borders", "cwb.jar")),
				asked.get(0).without());
		assertEquals(List.of("cwb.jar"), DisabledMods.parse(Files.readAllLines(game.resolve(DisabledMods.FILE))));
	}

	@ExpectCaught("crash-suspects.json")
	@Test
	void aFileThisVersionCannotReadIsNotOffered() throws Exception {
		fabricJar("sodium.jar", "sodium");
		Files.createDirectories(pending().getParent());
		Files.writeString(pending(), "{\"schema\":2,\"report\":\"crash.txt\",\"suspects\":[]}");
		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY));
		Files.writeString(pending(), "not json at all");
		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY));
		assertEquals(List.of(), asked);
	}

	@Test
	void theWholeLoopFromACrashReportToASkippedJar() throws Exception {
		Path crashing = fabricJar("supermartijn642corelib-1.1.24a-forge-mc26.2.jar", "supermartijn642corelib");
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "supermartijn642corelib", "Core Lib", "1.0", "",
				List.of(), "supermartijn642corelib-1.1.24a-forge-mc26.2.jar", "", "")));
		Path reports = Files.createDirectories(game.resolve("crash-reports"));
		Files.writeString(reports.resolve("crash-2026-10-03.txt"), "java.lang.IllegalStateException: null menu type\n"
				+ "\tat forbric/com.supermartijn642.core.X.y(X.java:1) ~[supermartijn642corelib-1.1.24a-forge-mc26.2.jar:?] {}\n");
		CrashAttribution.setRunDir(game, 0);
		CrashAttribution.run();

		assertTrue(CrashSuspectOffer.run(game, null, answering(DependencyDialogMain.WITHOUT), TODAY));

		assertEquals("crash-2026-10-03.txt", asked.get(0).report());
		assertTrue(DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT).suppressed(crashing));
	}

	private Path fabricJar(String name, String id) throws Exception {
		Path jar = mods.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
