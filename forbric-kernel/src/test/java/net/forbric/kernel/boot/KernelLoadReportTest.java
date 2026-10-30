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

import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The file a player reads when a mod did not load.
 *
 * <p>Rendering is tested rather than writing, so both languages can be asserted without a locale dance and so
 * the wording — which is the part that can be wrong in a way that costs someone an afternoon — is pinned.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelLoadReportTest {
	@org.junit.jupiter.api.BeforeEach
	@org.junit.jupiter.api.AfterEach
	void clearCompatibilityEvidence() { net.forbric.api.CompatibilityFindings.reset(); }

	private List<ModCatalog.Entry> previous;

	@Test void machineReportDistinguishesStrictFromExplicitContinuation(
			@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		String key = net.forbric.kernel.ui.CompatibilityDecision.PROPERTY, previousPolicy = System.getProperty(key);
		try {
			for (String policy : List.of("strict", "continue")) {
				System.setProperty(key, policy); KernelLoadReport.writeTo(dir.resolve("load-report.txt"));
				String json = java.nio.file.Files.readString(dir.resolve("compatibility-report.json"));
				assertTrue(json.contains("\"policy\":\"" + policy.toUpperCase(java.util.Locale.ROOT) + "\""));
			}
		} finally { if (previousPolicy == null) System.clearProperty(key); else System.setProperty(key, previousPolicy); }
	}

	@Test
	void concurrentReportWritersNeverExposeATruncatedJsonToAReader(
			@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		ModCatalog.publish(List.of());
		var report = dir.resolve("load-report.txt");
		var machine = dir.resolve("compatibility-report.json");
		KernelLoadReport.writeTo(report);
		try (var workers = java.util.concurrent.Executors.newFixedThreadPool(3)) {
			var first = workers.submit(() -> { for (int i = 0; i < 40; i++) KernelLoadReport.writeTo(report); });
			var second = workers.submit(() -> { for (int i = 0; i < 40; i++) KernelLoadReport.writeTo(report); });
			var reader = workers.submit(() -> {
				for (int i = 0; i < 160; i++) {
					try {
						var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(
								new java.io.StringReader(java.nio.file.Files.readString(machine)));
						assertEquals(1, ((Number) parsed.get("schemaVersion")).intValue());
						assertTrue(parsed.contains("findings"));
					} catch (java.io.IOException unreadable) { throw new AssertionError(unreadable); }
				}
			});
			first.get(); second.get(); reader.get();
		}
	}

	@Test
	void machineEvidenceIncludesSuspicionsAndResolvedLossesWithoutMarkingThemAsFailures(
			@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		java.nio.file.Path text = dir.resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha")));
		net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
				"mixin:alpha", "alpha", "rendering", "mixin:alpha.json",
				net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, true, "preflight miss", List.of("anchor absent")));
		String said = capture(() -> KernelLoadReport.writeTo(text));
		// A suspicion alone is a clean boot. The file's presence is what says something failed -- push-and-run
		// counts every load-report.txt as a named failure and the M9/M24/M30 controls read "no file" as clean --
		// and fabric-api by itself brings two dozen preflight suspicions to every boot. The machine report carries
		// them; the file carries them only beside a real failure (see the test below).
		assertFalse(java.nio.file.Files.exists(text), "a suspicion alone must not write the failure report");
		assertTrue(ModCatalog.failures().isEmpty(), "a suspicion marks no mod");
		assertTrue(said.contains("every mod finished loading"), "a suspicion does not take the success line away: " + said);
		assertFalse(said.contains("possible problem"), "a clean boot says one INFO line: " + said);
		String machine = java.nio.file.Files.readString(dir.resolve("compatibility-report.json"));
		assertTrue(machine.contains("SUSPECTED"));
		assertTrue(machine.contains("\"confirmedRequired\":0"));
		net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
				"mixin:alpha", "alpha", "rendering", "mixin:alpha.json",
				net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, true, "apply failed", List.of("InvalidInjectionException")));
		KernelLoadReport.writeTo(text);
		assertTrue(java.nio.file.Files.readString(text).contains("apply failed"));
		net.forbric.api.CompatibilityFindings.resolve("mixin:alpha", "alpha", "kernel replacement verified");
		KernelLoadReport.writeTo(text);
		assertFalse(java.nio.file.Files.exists(text), "an old failure report must not survive a proved resolution");
		assertTrue(java.nio.file.Files.readString(dir.resolve("compatibility-report.json")).contains("RESOLVED"));
	}

	@org.junit.jupiter.api.BeforeEach
	void fresh() {
		previous = ModCatalog.everything();
		KernelLoadReport.reset();
	}

	@org.junit.jupiter.api.AfterEach
	void restore() {
		System.clearProperty(KernelLoadReport.REWRITE_PROPERTY);
		KernelLoadReport.reset();
		ModCatalog.publish(previous);
	}

	@Test
	void aFailureAfterTheFirstWriteReachesTheFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		java.nio.file.Path file = dir.resolve(".forbric-kernel").resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha"), entry("beta")));
		KernelLoadReport.writeTo(file);
		assertFalse(java.nio.file.Files.exists(file), "a clean run writes no file");

		ModCatalog.mark("alpha", ModCatalog.Status.DEGRADED, "its mixin AlphaMixin failed to apply at world creation");
		KernelLoadReport.writeTo(file);
		String first = java.nio.file.Files.readString(file);
		assertTrue(first.contains("alpha") && first.contains("AlphaMixin"), first);
		assertFalse(first.contains("beta"));

		ModCatalog.mark("beta", ModCatalog.Status.DEGRADED, "one of its deferred setup tasks threw");
		KernelLoadReport.writeTo(file);
		String second = java.nio.file.Files.readString(file);
		assertTrue(second.contains("alpha") && second.contains("beta"), "a failure after the first write reaches the file: " + second);
		assertEquals(2, KernelLoadReport.writes());

		KernelLoadReport.writeTo(file);
		assertEquals(2, KernelLoadReport.writes(), "nothing changed, nothing written");
	}

	@Test
	void theOneShotIsRestoredByTheFlag(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		System.setProperty(KernelLoadReport.REWRITE_PROPERTY, "off");
		java.nio.file.Path file = dir.resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha"), entry("beta")));
		ModCatalog.mark("alpha", ModCatalog.Status.DEGRADED, "first");
		KernelLoadReport.writeTo(file);
		ModCatalog.mark("beta", ModCatalog.Status.DEGRADED, "second");
		KernelLoadReport.writeTo(file);
		assertEquals(1, KernelLoadReport.writes());
		assertFalse(java.nio.file.Files.readString(file).contains("beta"), "the first write won");
	}

	@Test
	void theServerStartedHookWritesTheReportAgain() throws Exception {
		java.nio.file.Path runtime = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
				"net", "forbric", "kernel", "runtime").normalize();
		java.nio.file.Path duties = runtime.resolve("KernelServerDuties.class"), lifecycle = runtime.resolve("KernelGameServerLifecycle.class");
		TestFixtures.require(Fixture.GAME_SIDE, java.nio.file.Files.isRegularFile(duties) && java.nio.file.Files.isRegularFile(lifecycle),
				"runtime helper not compiled");
		// The report is the kernel's own work at server start, not part of the forward to MinecraftForge's hook (which stands
		// down where the merged game already called that hook) and not part of MinecraftForge's own started duties (which go
		// wherever MinecraftForge goes): it is KernelServerDuties', installed whatever else is there.
		assertTrue(lambdaWrites(duties, "lambda$installStartedDuties$"), "the kernel's server-started work writes the report");
		assertFalse(lambdaWrites(lifecycle, "lambda$installStarted$"), "the forward to MinecraftForge's handleServerStarted, which "
				+ "stands down where the base calls it natively, does not carry the report");
		assertFalse(lambdaWrites(lifecycle, "lambda$installStartedDuties$"), "nor do MinecraftForge's own started duties, which go "
				+ "with MinecraftForge");
		// AFTER MinecraftForge's handleServerStarted and its started duties (the dead-event judgement the report carries): the
		// work is registered after the whole bridge pass, on the same event and priority, and a NeoForge bus runs one priority's
		// listeners in the order they were added.
		String multiplexer = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("user.dir"), "src", "main", "java",
				"net", "forbric", "kernel", "boot", "GameEventMultiplexer.java"));
		int forwardInstall = multiplexer.indexOf("\"installStarted\")"), forgeDuties = multiplexer.indexOf("\"installStartedDuties\")");
		assertTrue(forwardInstall >= 0 && forgeDuties > forwardInstall, "MinecraftForge's started duties are installed after its forward");
		String lifecycleSource = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("user.dir"), "src", "main", "java",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.java"));
		int bridges = lifecycleSource.indexOf("GameEventMultiplexer.install(cl, side.isClient());"),
				kernelDuties = lifecycleSource.indexOf("KernelServerDutiesInstaller.install(cl);");
		assertTrue(bridges >= 0 && kernelDuties > bridges, "the kernel's work is installed after the whole bridge pass");
	}

	/** Whether a lambda of {@code compiled} whose name starts with {@code prefix} calls {@code KernelLoadReport.write}. */
	private static boolean lambdaWrites(java.nio.file.Path compiled, String prefix) throws Exception {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(compiled)).accept(node, 0);
		for (org.objectweb.asm.tree.MethodNode m : node.methods) {
			if (!m.name.startsWith(prefix)) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && "net/forbric/kernel/boot/KernelLoadReport".equals(call.owner)
						&& "write".equals(call.name)) return true;
			}
		}
		return false;
	}

	@Test
	void aFailedLibraryNamesTheModsThatSaidTheyNeedIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		// What a player sees when a library fails is not the library: it is the dozen mods that quietly stopped
		// doing anything, and until now nothing named those anywhere.
		java.nio.file.Path file = dir.resolve("load-report.txt");
		net.forbric.api.ModPresence.publishForgeFamily(List.of(
				new net.forbric.api.DiscoveredMod(Ecosystem.NEOFORGE, "balm", "1.0", "Balm",
						List.of(), List.of(), null, "balm.jar"),
				new net.forbric.api.DiscoveredMod(Ecosystem.NEOFORGE, "waystones", "1.0", "Waystones",
						List.of(new net.forbric.api.UnifiedDependency("balm", "*", true)),
						List.of(), null, "waystones.jar")));
		try {
			ModCatalog.publish(List.of(entry("balm")));
			ModCatalog.mark("balm", ModCatalog.Status.FAILED, "its constructor threw");
			KernelLoadReport.writeTo(file);
			String report = java.nio.file.Files.readString(file);
			assertTrue(report.contains("Waystones"), "the dependant has to be named: " + report);
			assertTrue(report.contains("require this one") || report.contains("需要这个"), report);
		} finally {
			net.forbric.api.ModPresence.publishForgeFamily(List.of());
		}
	}

	@Test
	void aConfirmedLossNoInstalledModOwnsIsListedAndKeepsTheFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		java.nio.file.Path text = dir.resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha")));
		net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding("transfer-initialization",
				"forbric", "Item and fluid transfer", "KernelTransferInterop",
				net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, true, "transfer bridge could not be installed",
				List.of("install threw")));
		net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding("mixin:shared.mixins.json:M",
				"config:shared.mixins.json", "Mixin M", "mixin:shared.mixins.json",
				net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, true, "apply failed", List.of("two mods claim it")));
		String said = capture(() -> KernelLoadReport.writeTo(text));
		assertTrue(java.nio.file.Files.exists(text), "a confirmed loss must not delete the report: " + said);
		String report = java.nio.file.Files.readString(text);
		assertTrue(report.contains("transfer-initialization") && report.contains("transfer bridge could not be installed"), report);
		assertTrue(report.contains("config:shared.mixins.json") && report.contains("apply failed"), report);
		assertFalse(said.contains("every mod finished loading"), "the log must not contradict the prompt: " + said);
		assertTrue(said.contains("2 confirmed compatibility finding(s) belong to no installed mod"), said);
		var machine = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(
				new java.io.StringReader(java.nio.file.Files.readString(dir.resolve("compatibility-report.json"))));
		long listed = report.lines().filter(line -> line.strip().startsWith("transfer-initialization")
				|| line.strip().startsWith("mixin:shared.mixins.json:M")).count();
		assertEquals(((Number) machine.get("confirmedRequired")).intValue(), listed,
				"the gate, the list and the text report read the same facts");
	}

	@Test
	void besideARealFailureTheFileAlsoListsTheSuspicionsAsNotes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		java.nio.file.Path text = dir.resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha"), entry("beta")));
		ModCatalog.mark("alpha", ModCatalog.Status.DEGRADED, "one of its deferred setup tasks threw");
		net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(
				"mixin:beta.mixins.json:beta.mixin.BetaMixin", "beta", "Mixin beta.mixin.BetaMixin", "mixin:beta.mixins.json",
				net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, true, "1/2 anchors resolve", List.of("anchor absent")));
		String said = capture(() -> KernelLoadReport.writeTo(text));
		String report = java.nio.file.Files.readString(text);
		// The reader troubleshooting alpha is the reader these notes are for; beta is named, and not called broken.
		assertTrue(report.contains("one of its deferred setup tasks threw"), report);
		// This reads a file `writeTo` wrote, and that file is in the system language. The two wordings below are
		// therefore accepted in either one, exactly as the dependant line above is: pinning the English words on
		// a file the machine chose the language for is a test that passes in London and fails in Shenzhen
		// (DependencyDialogTest states the rule where it explains the same trap). The wording itself is pinned
		// by the render tests below, which name the language instead of asking the system for it.
		assertTrue((report.contains("not confirmed") || report.contains("未确认"))
				&& report.contains("1/2 anchors resolve")
				&& report.contains("mixin:beta.mixins.json:beta.mixin.BetaMixin"), report);
		assertEquals(1, report.lines().filter(line -> line.contains("partly did not run")
				|| line.contains("有一部分没有跑起来")).count(), report);
		assertTrue(said.contains("1 mod(s) did not finish loading: alpha"), said);
	}

	@Test
	void theSuspicionNotesAreWordedInBothLanguages() {
		// The test above reads a file `writeTo` wrote, so it accepts the words in either language. What each
		// language actually says is pinned here, where the language is named rather than asked for.
		ModCatalog.publish(List.of(entry("alpha"), entry("beta")));
		ModCatalog.mark("alpha", ModCatalog.Status.DEGRADED, "one of its deferred setup tasks threw");
		net.forbric.api.CompatibilityFinding suspected = new net.forbric.api.CompatibilityFinding(
				"mixin:beta.mixins.json:beta.mixin.BetaMixin", "beta", "Mixin beta.mixin.BetaMixin", "mixin:beta.mixins.json",
				net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, true, "1/2 anchors resolve", List.of("anchor absent"));

		String en = KernelLoadReport.render(false, ModCatalog.failures(), List.of(), List.of(suspected));
		String zh = KernelLoadReport.render(true, ModCatalog.failures(), List.of(), List.of(suspected));

		assertTrue(en.contains("Possible problems (not confirmed)"), en);
		assertTrue(en.contains("partly did not run"), en);
		assertTrue(zh.contains("可能的问题（未确认）"), zh);
		assertTrue(zh.contains("有一部分没有跑起来"), zh);
		assertFalse(zh.contains("not confirmed") || en.contains("未确认"), "the two renderings must not bleed into each other");
	}

	@Test
	void theBoundaryBeforeAnyModRunsNeverSaysEveryModFinishedLoading(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		java.nio.file.Path file = dir.resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha")));
		String early = capture(() -> KernelLoadReport.writeTo(file, false));
		assertFalse(early.contains("every mod finished loading"),
				"nothing has initialised at the pre-launch boundary: " + early);
		String end = capture(() -> KernelLoadReport.writeTo(file));
		assertTrue(end.contains("[Forbric/Load] every mod finished loading"),
				"the real end of loading must still be able to say it: " + end);
		assertFalse(capture(() -> KernelLoadReport.writeTo(file)).contains("every mod finished loading"), "said once");
	}

	@Test
	void aModThatFailsAfterThePreLaunchBoundaryIsNeverPrecededByASuccessLine(
			@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		java.nio.file.Path file = dir.resolve("load-report.txt");
		ModCatalog.publish(List.of(entry("alpha")));
		String log = capture(() -> {
			KernelLoadReport.writeTo(file, false);
			ModCatalog.mark("alpha", ModCatalog.Status.FAILED, "its main entrypoint threw");
			KernelLoadReport.writeTo(file);
		});
		assertTrue(log.contains("1 mod(s) did not finish loading"), log);
		assertFalse(log.contains("every mod finished loading"), "the log must not contradict itself: " + log);
	}

	@Test
	void theLaunchBoundaryAndTheShutdownHookWriteEvidenceOnly() throws Exception {
		java.nio.file.Path classes = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot").normalize();
		org.objectweb.asm.tree.MethodNode launch = method(classes.resolve("KernelBoot.class"), "launch");
		org.objectweb.asm.tree.MethodNode setRunDir = method(classes.resolve("KernelLoadReport.class"), "setRunDir");
		assertTrue(calls(launch, "writeEvidence"), "KernelBoot.launch writes the pre-launch evidence");
		assertFalse(calls(launch, "write"), "KernelBoot.launch runs before any mod initialises; it must not claim the end of loading");
		boolean hookIsEvidence = false;
		for (org.objectweb.asm.tree.AbstractInsnNode insn : setRunDir.instructions) {
			if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof org.objectweb.asm.Handle handle && "net/forbric/kernel/boot/KernelLoadReport".equals(handle.getOwner())) {
						assertEquals("writeEvidence", handle.getName(), "a process going down has not seen loading finish");
						hookIsEvidence = true;
					}
				}
			}
		}
		assertTrue(hookIsEvidence, "the shutdown hook still writes the report");
	}

	private static org.objectweb.asm.tree.MethodNode method(java.nio.file.Path file, String name) throws Exception {
		assertTrue(java.nio.file.Files.isRegularFile(file), "compiled class missing: " + file);
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(file)).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static boolean calls(org.objectweb.asm.tree.MethodNode method, String name) {
		for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
			if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
					&& "net/forbric/kernel/boot/KernelLoadReport".equals(call.owner) && name.equals(call.name)) return true;
		}
		return false;
	}

	static String capture(Runnable body) {
		java.io.PrintStream originalOut = System.out, originalErr = System.err;
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		java.io.PrintStream sink = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private static ModCatalog.Entry entry(String id) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, id, "1.0", "", List.of(), id + ".jar", "", "");
	}


	@Test
	void everyFailedModAppearsWithItsReasonAndItsJar() {
		String text = KernelLoadReport.render(false, List.of(
				failed("alpha", "Alpha Mod", "alpha-1.0.jar", "its @Mod constructor threw")));

		assertTrue(text.contains("Alpha Mod"), text);
		assertTrue(text.contains("alpha-1.0.jar"), "the jar is what a player removes, so it has to be named");
		assertTrue(text.contains("its @Mod constructor threw"), text);
		assertTrue(text.contains("1 mod(s) did not finish loading"), text);
	}

	@Test
	void aModThatOnlyDegradedIsNotCalledBroken() {
		String degraded = KernelLoadReport.render(false, List.of(
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "beta", "Beta", "1.0", "", List.of(), "beta.jar", "", "",
						ModCatalog.Status.DEGRADED, "it threw during common setup")));

		assertTrue(degraded.contains("partly did not run"), degraded);
		assertFalse(degraded.contains("did not finish loading\n"),
				"collapsing DEGRADED into FAILED would tell a player their mod is not there when most of it is");

		// And the other direction, so this is not passing on wording that never differs.
		String failed = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha", "a.jar", "x")));
		assertTrue(failed.contains("did not finish loading"), failed);
	}

	@Test
	void theReportSaysTheModIsStillPartlyPresent() {
		// The one claim in here that is easy to get wrong and expensive when it is. A withdrawn mod's classes ARE
		// loaded and its mixins ARE applied; isLoaded(id) deliberately still answers true. Saying "not running"
		// would send someone to reinstall what is already there.
		String text = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha", "a.jar", "x")));

		assertTrue(text.contains("still partly present"), text);
		assertTrue(text.contains("records loading results"), "the report must describe loading without claiming the game started");
		assertFalse(text.contains("the game did start"), "strict startup can stop before a game is ready");
		assertFalse(text.contains("is not running"), text);
	}

	@Test
	void theReportIsWrittenInTheSystemLanguage() {
		String zh = KernelLoadReport.render(true, List.of(failed("alpha", "Alpha", "a.jar", "x")));
		String en = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha", "a.jar", "x")));

		assertTrue(zh.contains("没有完成加载"), zh);
		assertTrue(zh.contains("怎么办"), "the what-to-do section is the reason the file exists");
		assertTrue(en.contains("What to do"), en);
		assertFalse(en.contains("没有完成加载"), "the two renderings must not bleed into each other");
		// The Chinese half of the claim the test above pins in English; gate-m24 accepts either on a real boot.
		assertTrue(zh.contains("还有一部分留在游戏里"), zh);
	}

	@Test
	void theFileItselfAsksTheSystemWhichLanguage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		// The test above names the language; writeTo asks the system for it, and that choice is what this pins. The
		// English words once asserted on writeTo's file covered it by accident, on an English machine only, and
		// accepting either language there -- which is right for that test -- left it to nobody.
		ModCatalog.publish(List.of(entry("alpha")));
		ModCatalog.mark("alpha", ModCatalog.Status.DEGRADED, "one of its deferred setup tasks threw");
		java.util.Locale original = java.util.Locale.getDefault();
		String zh, en;
		try {
			java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
			KernelLoadReport.writeTo(dir.resolve("zh").resolve("load-report.txt"));
			zh = java.nio.file.Files.readString(dir.resolve("zh").resolve("load-report.txt"));
			java.util.Locale.setDefault(java.util.Locale.ENGLISH);
			KernelLoadReport.reset();
			KernelLoadReport.writeTo(dir.resolve("en").resolve("load-report.txt"));
			en = java.nio.file.Files.readString(dir.resolve("en").resolve("load-report.txt"));
		} finally {
			java.util.Locale.setDefault(original);
		}

		assertTrue(zh.contains("Forbric 加载报告") && zh.contains("有一部分没有跑起来"), zh);
		assertFalse(zh.contains("partly did not run"), zh);
		assertTrue(en.contains("Forbric load report") && en.contains("partly did not run"), en);
		assertFalse(en.contains("有一部分没有跑起来"), en);
	}

	@Test
	void aModWhoseNameIsItsIdIsNotPrintedTwice() {
		String same = KernelLoadReport.render(false, List.of(failed("alpha", "alpha", "a.jar", "x")));
		String different = KernelLoadReport.render(false, List.of(failed("alpha", "Alpha Mod", "a.jar", "x")));

		assertFalse(same.contains("alpha  (alpha)"), "'alpha (alpha)' reads like two different things");
		assertTrue(different.contains("Alpha Mod  (alpha)"),
				"when they differ, the id is what appears in the log the player is about to search");
	}

	@Test
	void aCleanRunRendersNothingToShow() {
		assertTrue(KernelLoadReport.render(false, List.of()).contains("0 mod(s)"),
				"the caller is what decides not to write a file; the renderer must still be total");
	}

	private static ModCatalog.Entry failed(String id, String name, String jar, String why) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, name, "1.0", "", List.of(), jar, "", "",
				ModCatalog.Status.FAILED, why);
	}
}
