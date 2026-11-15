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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.mixin.MixinOverlapLint;

/**
 * Naming the mods a crash report points at.
 *
 * <p>The inputs are real crash reports this loader produced rather than hand-written traces: the whole value of
 * this feature is that it works on what the game actually writes, and a fixture I wrote myself would agree with my
 * code by construction. They are copied into crash-attribution/ beside this class, with the machine's hardware
 * values blanked and every section kept, because the originals under run/ exist only on the machine that crashed,
 * and every test here that read them skipped everywhere else.
 */
class CrashAttributionTest {
	@TempDir
	Path tmp;

	/** supermartijn642corelib's own frame on top: "Container screen registered with null menu type!". */
	private static final String CORE_LIB = "crash-2026-09-20_17.17.35-client.txt";
	/** A worldgen crash ("Feature placement") with Minecraft's whole walkthrough below the trace. */
	private static final String WORLDGEN = "crash-2026-07-12_13.23.39-client.txt";
	/** Mixin naming the mod that failed to apply, from the old forbric-loader (the repository's crash/ folder). */
	private static final String MIXIN_APPLY = "crash-report.txt";
	/**
	 * NeoForge's mod-loading crash report from a Forbric server whose NeoForge mod's RegisterEvent listener threw
	 * (gate-m24's register-stop canary): above the divider only "Mod loading has failed"; the mod is named in
	 * {@code -- Mod loading issue for: forbricregisterstop --}.
	 */
	private static final String NEO_ISSUE_SERVER = "crash-2026-11-15_14.36.11-fml.txt";
	/** The same report for a throw inside NeoForge's registration try: {@code -- Mod loading issue --}, no mod attached. */
	private static final String NEO_ISSUE_UNATTACHED = "crash-2026-11-15_14.52.06-fml.txt";
	/** The client's crash report for the same listener: the mod only in the ModLoadingException's issue lines. */
	private static final String NEO_ISSUE_CLIENT = "crash-2026-11-15_14.57.44-client.txt";

	/** The register-stop canary and the two healthy mods staged beside it in those runs. */
	private static void publishRegisterStopRun() {
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "forbricregisterstop", "Forbric Register Stop Canary", "1.0.0", "",
						List.of(), "forbricregisterstop.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "forbricneolive", "Forbric Neo Live", "1.0.0", "", List.of(),
						"forbricneolive.jar", "", ""),
				mod("forbricfabriclive", "Forbric Fabric Live", "forbricfabriclive.jar")));
	}

	@AfterEach
	void clearCatalogue() {
		ModCatalog.publish(List.of());
		MixinOverlapLint.publish(List.of());
	}

	/** An overlap of {@code rule} between {@code first} and {@code second} on {@code ClassInstanceMultiMap.find}. */
	private static MixinOverlapLint.Overlap overlap(MixinOverlapLint.Rule rule, String first, String second) {
		String owner = "net/minecraft/util/ClassInstanceMultiMap";
		String desc = "(Ljava/lang/Class;)Ljava/util/Collection;";
		boolean call = rule == MixinOverlapLint.Rule.R4;
		return new MixinOverlapLint.Overlap(rule,
				new MixinOverlapLint.Claim(first, first + ".mixins.json", "a.FindMixin", "find", call ? "Redirect" : "Overwrite",
						owner, "find", desc, call ? "INVOKE" : null, call ? "Lx/Y;z()V" : null, -1, "mixins", first),
				new MixinOverlapLint.Claim(second, second + ".mixins.json", "b.FindMixin", "find",
						call ? "WrapOperation" : "Overwrite", owner, "find", desc, call ? "INVOKE" : null,
						call ? "Lx/Y;z()V" : null, -1, "mixins", second));
	}

	/** A trace whose only frame of interest is the overwritten method, under Minecraft's jar and name. */
	private static final String THROUGH_FIND = "java.lang.ClassCastException: class a cannot be cast to class b\n"
			+ "\tat forbric/net.minecraft.util.ClassInstanceMultiMap.find(ClassInstanceMultiMap.java:62) "
			+ "~[patched-mc-merged-26.2.jar:?] {}\n"
			+ "\tat forbric/net.minecraft.world.level.entity.EntitySection.getEntities(EntitySection.java:40) "
			+ "~[patched-mc-merged-26.2.jar:?] {}\n";

	private static ModCatalog.Entry mod(String id, String name, String jar) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, name, "1.0", "", List.of(), jar, "", "");
	}

	/**
	 * The fixture {@code name}, which must still carry Minecraft's own sections: several tests here assert that
	 * the attribution does NOT read them, and against a file without them those assertions would pass vacuously.
	 */
	private static String report(String name) throws Exception {
		try (InputStream in = CrashAttributionTest.class.getResourceAsStream("crash-attribution/" + name)) {
			assertNotNull(in, "crash-attribution/" + name + " is a committed test resource");
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(text.contains("-- System Details --") && text.contains("Mod List:"),
					name + " must keep the System Details section and its Mod List");
			return text;
		}
	}

	@Test
	void everyFixtureKeepsTheSectionsTheAttributionMustIgnore() throws Exception {
		for (String name : List.of(CORE_LIB, WORLDGEN, MIXIN_APPLY, NEO_ISSUE_SERVER, NEO_ISSUE_UNATTACHED, NEO_ISSUE_CLIENT)) {
			String text = report(name);
			assertTrue(CrashAttribution.exceptionChain(text).length() < text.length(), name);
		}
	}

	@Test
	void theTopFrameSJarNamesTheMod() throws Exception {
		ModCatalog.publish(List.of(
				mod("supermartijn642corelib", "SuperMartijn642's Core Lib",
						"supermartijn642corelib-1.1.24a-forge-mc26.2.jar"),
				mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar")));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(report(CORE_LIB));

		assertFalse(suspects.isEmpty(), "the top frame of this crash is a mod jar");
		assertEquals("supermartijn642corelib", suspects.get(0).modId(),
				"the frame that threw is a better suspect than the frame that called it");
		// A mod that is installed but nowhere in the trace is not a suspect.
		assertTrue(suspects.stream().noneMatch(s -> s.modId().equals("sodium")), suspects.toString());
	}

	@Test
	void theKernelAndTheGameAreNeverSuspects() throws Exception {
		// Nothing published at all: the catalogue is the deny-list. Every frame in this report belongs to the
		// merged base, a runtime carrier, the kernel jar or the JDK, and none of those is a mod.
		ModCatalog.publish(List.of());

		assertEquals(List.of(), CrashAttribution.suspects(report(CORE_LIB)),
				"patched-mc-merged, the carriers and forbric-kernel's own jar are not mods");
	}

	@Test
	void aJarNameIsNeverTurnedIntoAModId() throws Exception {
		// The jar is in the trace and the catalogue has a mod, but the mod's jar is spelled differently. A
		// file name is not a mod id -- xaeroworldmap-*.jar carries xaerominimap-family ids, and jars renamed to
		// a content hash are real -- so this must find nothing rather than guess from the name.
		ModCatalog.publish(List.of(mod("supermartijn642corelib", "Core Lib", "some-other-name.jar")));

		assertEquals(List.of(), CrashAttribution.suspects(report(CORE_LIB)));
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
	void modsTheErrorNamesAsClashingComeBeforeTheModWhoseCodeThrew() {
		// Verbatim head of a real report: chloride + Cubes Without Borders + sodium-neoforge on this loader. Both
		// override Sodium's fullscreen option and Sodium refuses the pair; its own handler frame is the first one.
		ModCatalog.publish(List.of(
				mod("chloride", "Chloride", "chloride-NEOFORGE-mc26.2-v1.8.1.jar"),
				mod("cwb", "Cubes Without Borders", "cwb-4.1.0+26.2.jar"),
				mod("sodium", "Sodium", "sodium-neoforge-0.9.2+mc26.2.jar")));
		String report = "---- Minecraft Crash Report ----\n"
				+ "Description: Failed to build config options\n\n"
				+ "java.lang.IllegalArgumentException: Multiple overrides for option 'sodium:general.fullscreen_mode'! "
				+ "Sources: chloride and cwb\n"
				+ "\tat forbric/net.caffeinemc.mods.sodium.client.config.structure.Config.applyOptionChanges(Config.java:131) "
				+ "~[net.caffeinemc.sodium-neoforge-0.9.2+mc26.2-mod.jar:?] {}\n"
				+ "\tat forbric/net.minecraft.client.Minecraft.handler$zca000$sodium$postInit(Minecraft.java:5117) "
				+ "[patched-mc-merged-26.2.jar:?] {}\n";

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(report);

		assertEquals(List.of("chloride", "cwb", "sodium"), suspects.stream().map(CrashAttribution.Suspect::modId).toList());
		assertEquals(CrashAttribution.CLASH, suspects.get(0).reason());
		assertEquals(CrashAttribution.CLASH, suspects.get(1).reason());
		String en = CrashAttribution.render(false, "crash.txt", suspects);
		assertTrue(en.contains("clash with each other") && en.contains("Chloride, Cubes Without Borders cannot be installed together"), en);
		assertFalse(en.contains("Take the first one out"), en);
		String zh = CrashAttribution.render(true, "crash.txt", suspects);
		assertTrue(zh.contains("互相冲突") && zh.contains("Chloride、Cubes Without Borders 不能装在一起"), zh);
	}

	@Test
	void aSourcesListNamingOnlyOneInstalledModIsNotAClash() {
		ModCatalog.publish(List.of(mod("cwb", "Cubes Without Borders", "cwb.jar")));
		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(
				"java.lang.IllegalArgumentException: Multiple overrides for option 'x'! Sources: somethingelse and cwb\n");
		assertEquals(List.of("cwb"), suspects.stream().map(CrashAttribution.Suspect::modId).toList());
		assertFalse(CrashAttribution.render(false, "crash.txt", suspects).contains("clash with each other"),
				"one named side is not a pair the player can choose between");
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
		ModCatalog.publish(List.of(mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar")));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(report(MIXIN_APPLY));

		// "Mixin [sodium-common.mixins.json:...LevelExtractorMixin from mod sodium] ... FAILED during APPLY".
		// The whole crash is sodium's, and the Suspected Mods line in that very file says NONE.
		assertEquals("sodium", suspects.get(0).modId(), suspects.toString());
		assertEquals("Mixin named it", suspects.get(0).reason());
	}

	@Test
	void onlyTheExceptionChainIsRead() throws Exception {
		String whole = report(WORLDGEN);
		String chain = CrashAttribution.exceptionChain(whole);

		assertTrue(chain.length() < whole.length(), "the walkthrough divider must cut the file");
		assertFalse(chain.contains("-- System Details --"), chain);
		// Everything below the divider is Minecraft's own report -- thread dumps, the mod lists, the graphics
		// card. A mod named there is named for being installed, not for being involved.
		assertFalse(chain.contains("Mod List:"), "the mod list is not evidence");
	}

	@Test
	void neoForgesModLoadingIssueSectionNamesTheMod() throws Exception {
		publishRegisterStopRun();

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(report(NEO_ISSUE_SERVER));

		assertEquals(List.of("forbricregisterstop"), suspects.stream().map(CrashAttribution.Suspect::modId).toList(),
				"NeoForge wrote the failing mod under its issue section, and nothing above the divider: " + suspects);
		assertEquals(CrashAttribution.NEOFORGE_ISSUE, suspects.get(0).reason());
		assertEquals("forbricregisterstop.jar", suspects.get(0).jar());
		assertTrue(CrashAttribution.render(false, NEO_ISSUE_SERVER, suspects).contains("(forbricregisterstop)"));
		assertTrue(CrashAttribution.render(true, NEO_ISSUE_SERVER, suspects).contains("NeoForge 的模组加载报错里点了它的名字"));
	}

	@Test
	void anIssueNoModIsAttachedToIsReadThroughItsOwnTrace() throws Exception {
		publishRegisterStopRun();

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(report(NEO_ISSUE_UNATTACHED));

		// "-- Mod loading issue --" carries no id; its stack trace is the failure's, and the top frame is the mod's
		// mixin handler inside NeoForge's GameData.
		assertEquals(List.of("forbricregisterstop"), suspects.stream().map(CrashAttribution.Suspect::modId).toList(),
				suspects.toString());
		assertEquals("its mixin was running", suspects.get(0).reason());
	}

	@Test
	void aClientsModLoadingErrorNamesTheModInItsIssueLines() throws Exception {
		publishRegisterStopRun();

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(report(NEO_ISSUE_CLIENT));

		// "net.neoforged.fml.ModLoadingException: Loading errors encountered:" then "\t- Forbric Register Stop Canary
		// (forbricregisterstop) encountered an error while dispatching ..." — every frame is NeoForge's or the kernel's.
		assertEquals(List.of("forbricregisterstop"), suspects.stream().map(CrashAttribution.Suspect::modId).toList(),
				suspects.toString());
		assertEquals(CrashAttribution.NEOFORGE_ISSUE, suspects.get(0).reason());
	}

	@Test
	void ofTheWalkthroughOnlyNeoForgesIssueSectionsAreRead() throws Exception {
		String whole = report(NEO_ISSUE_SERVER);
		String issues = CrashAttribution.modLoadingIssues(whole);

		assertTrue(issues.contains("-- Mod loading issue for: forbricregisterstop --"), issues);
		assertFalse(issues.contains("-- Head --"), "the head section repeats a frame; it is not read: " + issues);
		assertFalse(issues.contains("-- System Details --") || issues.contains("Mod List:"), issues);
		// A report Minecraft wrote, without NeoForge's sections, reads exactly as before.
		assertEquals("", CrashAttribution.modLoadingIssues(report(WORLDGEN)));
	}

	@Test
	void parenthesesOutsideNeoForgesIssueLinesNameNobody() {
		publishRegisterStopRun();
		String report = "java.lang.IllegalStateException: broke while (forbricregisterstop) was mentioned\n"
				+ "\tat forbric/net.minecraft.Foo.bar(Foo.java:1) ~[patched-mc-neoforge-26.2.jar:?] {}\n"
				+ "\n\nA detailed walkthrough of the error, its code path and all known details is as follows:\n"
				+ "---------------------------------------------------------------------------------------\n\n"
				+ "-- System Details --\nDetails:\n\tMod List: \n\t\tforbricregisterstop.jar |Forbric Register Stop Canary |forbricregisterstop |1.0.0\n";

		assertEquals(List.of(), CrashAttribution.suspects(report),
				"an id in parentheses counts only on a NeoForge issue line; the mod list counts never");
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
	void aCrashThroughAMethodTwoModsOverwriteNamesBothOfThem() {
		ModCatalog.publish(List.of(mod("lithium", "Lithium", "lithium-fabric-0.25.3+mc26.2.jar"),
				mod("vmp", "Very Many Players", "vmp-fabric-mc26.2-0.2.0.jar"),
				mod("minecraft", "Minecraft", "patched-mc-merged-26.2.jar")));
		MixinOverlapLint.publish(List.of(overlap(MixinOverlapLint.Rule.R1, "lithium", "vmp")));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(THROUGH_FIND);

		// Without the overlap the frame's jar is the only signal, and it names Minecraft.
		assertEquals(List.of("lithium", "vmp", "minecraft"), suspects.stream().map(CrashAttribution.Suspect::modId).toList());
		assertEquals(CrashAttribution.OVERLAP, suspects.get(0).reason());
		assertEquals(CrashAttribution.OVERLAP, suspects.get(1).reason());
		assertEquals("Very Many Players", suspects.get(0).collision().other());
		assertEquals("ClassInstanceMultiMap.find", suspects.get(0).collision().method());
		String en = CrashAttribution.render(false, "crash.txt", suspects);
		assertTrue(en.contains("Lithium and Very Many Players both change ClassInstanceMultiMap.find"), en);
		assertTrue(en.contains("its mixin and one from Lithium both change ClassInstanceMultiMap.find"), en);
		String zh = CrashAttribution.render(true, "crash.txt", suspects);
		assertTrue(zh.contains("Lithium 和 Very Many Players 都改了 ClassInstanceMultiMap.find"), zh);
	}

	@Test
	void aNoteOrAnotherMethodNamesNobody() {
		ModCatalog.publish(List.of(mod("lithium", "Lithium", "lithium.jar"), mod("vmp", "Very Many Players", "vmp.jar")));
		// A redirect beside a WrapOperation is how MixinExtras is meant to be used: not something to accuse.
		MixinOverlapLint.publish(List.of(overlap(MixinOverlapLint.Rule.R4, "lithium", "vmp")));
		assertEquals(List.of(), CrashAttribution.suspects(THROUGH_FIND));

		MixinOverlapLint.publish(List.of(overlap(MixinOverlapLint.Rule.R1, "lithium", "vmp")));
		assertEquals(List.of(), CrashAttribution.suspects(THROUGH_FIND.replace("ClassInstanceMultiMap.find(",
				"ClassInstanceMultiMap.getAllInstances(")));
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
		Path dir = tmp.resolve("crash-reports");
		Files.createDirectories(dir);
		Path staged = Files.writeString(dir.resolve(CORE_LIB), report(CORE_LIB));
		Files.setLastModifiedTime(staged, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		ModCatalog.publish(List.of(mod("supermartijn642corelib", "SuperMartijn642's Core Lib",
				"supermartijn642corelib-1.1.24a-forge-mc26.2.jar")));

		CrashAttribution.setRunDir(tmp, 0);
		CrashAttribution.run();

		Path written = tmp.resolve(".forbric-kernel").resolve("crash-analysis.txt");
		assertTrue(Files.isRegularFile(written), "nothing was written");
		String text = Files.readString(written, StandardCharsets.UTF_8);
		assertTrue(text.contains("SuperMartijn642's Core Lib"), text);
		assertTrue(text.contains(CORE_LIB), "it must point at the real report: " + text);
	}

	@Test
	void aRunThatDidNotCrashWritesNothing() throws Exception {
		ModCatalog.publish(List.of(mod("sodium", "Sodium", "sodium.jar")));
		CrashAttribution.setRunDir(tmp, 0);
		CrashAttribution.run();

		assertFalse(Files.exists(tmp.resolve(".forbric-kernel").resolve("crash-analysis.txt")),
				"a file that appears only when something went wrong is a file whose presence means something");
		assertFalse(Files.exists(tmp.resolve(".forbric-kernel").resolve(CrashAttribution.JSON)),
				"and the next launch must not be offered anything about a crash that did not happen");
	}

	@Test
	void theWholePathAlsoWritesTheSuspectsForTheNextLaunch() throws Exception {
		Path dir = tmp.resolve("crash-reports");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("crash-clash.txt"), CLASH_REPORT);
		publishClash();

		CrashAttribution.setRunDir(tmp, 0);
		CrashAttribution.run();

		String json = Files.readString(tmp.resolve(".forbric-kernel").resolve(CrashAttribution.JSON), StandardCharsets.UTF_8);
		var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser()
				.parse(new java.io.StringReader(json));
		assertEquals(1, ((Number) parsed.get("schema")).intValue());
		assertEquals("crash-clash.txt", parsed.get("report"));
		assertEquals(Boolean.TRUE, parsed.get("clash"));
		List<?> suspects = parsed.get("suspects");
		assertEquals(3, suspects.size(), json);
		var first = (com.electronwill.nightconfig.core.UnmodifiableConfig) suspects.get(0);
		assertEquals("chloride", first.get("modId"));
		assertEquals("Chloride", first.get("name"));
		assertEquals("chloride-NEOFORGE-mc26.2-v1.8.1.jar", first.get("jar"));
		assertEquals(CrashAttribution.CLASH, first.get("reason"));
		assertTrue(((Number) first.get("depth")).intValue() > 0, json);
	}

	@Test
	void aClashIsStartedWithoutEverySideButTheFirstNamed() {
		publishClash();
		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(CLASH_REPORT);

		// Chloride is kept, as the advice above says; Sodium threw the error but is not a side of the clash.
		assertEquals(List.of("cwb-4.1.0+26.2.jar"), CrashAttribution.startWithout(suspects));
		String en = CrashAttribution.render(false, "crash.txt", suspects);
		assertTrue(en.contains("with these lines in forbric-disabled.txt") && en.contains("\n    cwb-4.1.0+26.2.jar\n"), en);
		assertFalse(en.contains("    chloride-NEOFORGE-mc26.2-v1.8.1.jar"), en);
		assertTrue(en.contains("delete a line to turn that mod back on"), en);
		String zh = CrashAttribution.render(true, "crash.txt", suspects);
		assertTrue(zh.contains("forbric-disabled.txt") && zh.contains("\n    cwb-4.1.0+26.2.jar\n"), zh);
		assertTrue(zh.contains("删掉一行就能重新启用"), zh);
	}

	@Test
	void otherwiseEverySuspectsJarIsListedOnce() {
		ModCatalog.publish(List.of(
				mod("fabric-api", "Fabric API", "fabric-api-0.161.jar"),
				new ModCatalog.Entry(Ecosystem.FABRIC, "fabric-api-base", "Fabric API Base", "1.0", "", List.of(),
						"fabric-api-base-1.0.jar", "", "fabric-api"),
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "orphan", "Orphan", "1.0", "", List.of(), "orphan.jar", "", "?"),
				mod("sodium", "Sodium", "sodium-fabric-0.9.0.jar")));
		String trace = "java.lang.RuntimeException\n"
				+ "\tat forbric/a.B.c(B.java:1) ~[fabric-api-base-1.0.jar:?] {}\n"
				+ "\tat forbric/a.B.c(B.java:1) ~[orphan.jar:?] {}\n"
				+ "\tat forbric/a.B.c(B.java:1) ~[sodium-fabric-0.9.0.jar:?] {}\n"
				+ "\tat forbric/a.B.c(B.java:1) ~[fabric-api-0.161.jar:?] {}\n";

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(trace);

		// A bundled module has no line of its own: its file is extracted, not installed. Switching it off means
		// switching off the jar that carries it, and a carrier nobody can name contributes no line at all.
		assertEquals("fabric-api-0.161.jar", suspects.get(0).jar());
		assertEquals("", suspects.get(1).jar());
		assertEquals(List.of("fabric-api-0.161.jar", "sodium-fabric-0.9.0.jar"), CrashAttribution.startWithout(suspects));
	}

	@Test
	void namingNothingSuggestsNoLines() {
		assertEquals(List.of(), CrashAttribution.startWithout(List.of()));
		assertFalse(CrashAttribution.render(false, "crash.txt", List.of()).contains("forbric-disabled.txt"));
		String json = CrashAttribution.json("crash.txt", List.of());
		assertTrue(json.contains("\"suspects\":[]") && json.contains("\"clash\":false"), json);
	}

	/** Verbatim head of a real report: chloride + Cubes Without Borders + sodium-neoforge on this loader. */
	private static final String CLASH_REPORT = "---- Minecraft Crash Report ----\n"
			+ "Description: Failed to build config options\n\n"
			+ "java.lang.IllegalArgumentException: Multiple overrides for option 'sodium:general.fullscreen_mode'! "
			+ "Sources: chloride and cwb\n"
			+ "\tat forbric/net.caffeinemc.mods.sodium.client.config.structure.Config.applyOptionChanges(Config.java:131) "
			+ "~[net.caffeinemc.sodium-neoforge-0.9.2+mc26.2-mod.jar:?] {}\n"
			+ "\tat forbric/net.minecraft.client.Minecraft.handler$zca000$sodium$postInit(Minecraft.java:5117) "
			+ "[patched-mc-merged-26.2.jar:?] {}\n";

	private static void publishClash() {
		ModCatalog.publish(List.of(
				mod("chloride", "Chloride", "chloride-NEOFORGE-mc26.2-v1.8.1.jar"),
				mod("cwb", "Cubes Without Borders", "cwb-4.1.0+26.2.jar"),
				mod("sodium", "Sodium", "sodium-neoforge-0.9.2+mc26.2.jar")));
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
