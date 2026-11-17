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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The check that the jars a launch was handed are a Forbric game, by content (issue #13), and that an install made
 * by Forbric 0.3.x or earlier -- a merged base with a MinecraftForge carrier -- is refused with the one line that says
 * how to fix it.
 *
 * <p>The jars here are built to the check's own definition -- {@link LaunchInputCheck#markers} for a carrier, a
 * {@code Block} with NeoForge's extension interface for the base, {@link LaunchInputCheck#OLD_FORGE_CARRIER} and
 * {@link LaunchInputCheck#OLD_FORGE_INDEX} for an old install -- and the staged test at the bottom holds that
 * definition to the real artifacts, so the two cannot agree with each other while disagreeing with the game.
 */
@ResourceLock("system-properties")
class LaunchInputCheckTest {
	private static final String NEO_EXTENSION = "net/neoforged/neoforge/common/extensions/IBlockExtension";
	/** What an old merged base's Block implemented besides NeoForge's interface. */
	private static final String FORGE_EXTENSION = "net/minecraftforge/common/extensions/IForgeBlock";

	@AfterEach
	void clearSwitch() {
		System.clearProperty(LaunchInputCheck.SWITCH);
	}

	/** A jar holding every marker of {@code families}. Entries are empty: the check reads names, not bytes. */
	private static Path carrier(Path jar, Ecosystem... families) throws IOException {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
			for (Ecosystem family : families) {
				for (String marker : LaunchInputCheck.markers(family)) put(zip, marker, new byte[0]);
			}
		}
		return jar;
	}

	/** A jar with exactly these (empty) entries. */
	private static Path entries(Path jar, String... names) throws IOException {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
			for (String name : names) put(zip, name, new byte[0]);
		}
		return jar;
	}

	/** An old install's MinecraftForge carrier (forge-runtime.jar, or the stage's forge-runtime-interop.jar). */
	private static Path forgeCarrier(Path jar) throws IOException {
		return entries(jar, LaunchInputCheck.OLD_FORGE_CARRIER.toArray(String[]::new));
	}

	/** A game jar whose {@code Block} implements {@code interfaces}, which is all the check reads of a base. */
	private static Path base(Path jar, String... interfaces) throws IOException {
		return base(jar, List.of(), interfaces);
	}

	/** The same, with more (empty) entries beside the Block. */
	private static Path base(Path jar, List<String> extra, String... interfaces) throws IOException {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/Block", null, "java/lang/Object",
				interfaces);
		cw.visitEnd();
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "version.json", "{\"id\":\"26.2\"}".getBytes(StandardCharsets.UTF_8));
			put(zip, LaunchInputCheck.BLOCK, cw.toByteArray());
			for (String name : extra) put(zip, name, new byte[0]);
		}
		return jar;
	}

	private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}

	/** NeoForge's patched game, as the installer stages it. */
	private static Path genuine(Path dir) throws IOException {
		return base(dir.resolve("patched-mc-neoforge-26.2.jar"), "net/minecraft/world/level/ItemLike", NEO_EXTENSION);
	}

	private static Path neo(Path dir) throws IOException {
		return carrier(dir.resolve("neoforge-runtime-26.2.0.93.jar"), Ecosystem.NEOFORGE);
	}

	private static String all(List<String> problems) {
		return String.join("\n", problems);
	}

	@Test
	void aCompleteInstallHasNothingToSay(@TempDir Path dir) throws IOException {
		List<Path> runtimes = List.of(neo(dir));

		assertEquals(List.of(), LaunchInputCheck.problems(List.of(genuine(dir)), runtimes));
		assertEquals(null, LaunchInputCheck.oldInstall(List.of(genuine(dir)), runtimes));
		assertDoesNotThrow(() -> LaunchInputCheck.require(List.of(genuine(dir)), runtimes));
	}

	@Test
	void theCarrierIsRecognisedByContentUnderAnyName(@TempDir Path dir) throws IOException {
		// A developer launch passes neoforge-runtime/neoforge-runtime.jar, an installed profile a versioned name.
		Path neo = carrier(dir.resolve("whatever.jar"), Ecosystem.NEOFORGE);
		assertEquals(List.of(), LaunchInputCheck.problems(List.of(genuine(dir)), List.of(neo)));
	}

	@Test
	void issue13RuntimeJarsHoldingNoNeoForgeStopTheLaunchNamingEachJarAndTheFix(@TempDir Path dir) throws IOException {
		// What the player's installer staged: right name, opened fine, nothing of NeoForge inside.
		Path neo = entries(dir.resolve("neoforge-runtime-26.2.0.93.jar"));
		List<String> problems = LaunchInputCheck.problems(List.of(genuine(dir)), List.of(neo));

		assertEquals(2, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar neoforge-runtime-26.2.0.93.jar does not contain NeoForge"),
				problems.get(0));
		assertTrue(problems.get(0).contains("net/neoforged/neoforgespi/language/IModInfo.class"), problems.get(0));
		assertTrue(problems.get(0).contains(neo.toString()), "the player has to be able to find the file");
		assertTrue(problems.get(1).contains("contains a complete NeoForge runtime"), problems.get(1));

		String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
				() -> LaunchInputCheck.require(List.of(genuine(dir)), List.of(neo))));
		// ForbricLog's fallback when log4j is absent, as it is on the test classpath. In the game these are the same
		// lines in latest.log.
		assertTrue(said.contains("[Forbric/ERROR] [Forbric/Install] the Forbric install is broken"), said);
		assertTrue(said.contains("neoforge-runtime-26.2.0.93.jar does not contain NeoForge"), said);
		assertTrue(said.contains("run the Forbric installer again with the same Game directory and leave "
				+ "\"Built artifacts\" EMPTY"), said);
		assertFalse(said.contains("merged"), "nothing of the merged base is left to build: " + said);
	}

	@Test
	void aRuntimeJarThatIsMissingOrIsNoJarIsNamed(@TempDir Path dir) throws IOException {
		Path absent = dir.resolve("nope/neoforge-runtime-26.2.0.93.jar");
		Path garbage = dir.resolve("neoforge-runtime.jar");
		Files.writeString(garbage, "<html>404 Not Found</html>");

		String problems = all(LaunchInputCheck.problems(List.of(genuine(dir)), List.of(absent, garbage)));

		assertTrue(problems.contains("runtime jar neoforge-runtime-26.2.0.93.jar does not exist (" + absent + ")"), problems);
		assertTrue(problems.contains("runtime jar neoforge-runtime.jar cannot be opened as a jar"), problems);
	}

	@Test
	void aCarrierWithOnlyPartOfNeoForgeIsCalledIncompleteAndSaysWhatIsMissing(@TempDir Path dir) throws IOException {
		List<String> neo = LaunchInputCheck.markers(Ecosystem.NEOFORGE);
		Path partial = entries(dir.resolve("neoforge-runtime-26.2.0.93.jar"), neo.get(1), neo.get(2), neo.get(3));

		List<String> problems = LaunchInputCheck.problems(List.of(genuine(dir)), List.of(partial));

		assertEquals(2, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar neoforge-runtime-26.2.0.93.jar is an incomplete NeoForge "
				+ "runtime: it is missing " + neo.get(0)), problems.get(0));
		assertTrue(problems.get(1).contains("contains a complete NeoForge runtime"), problems.get(1));
	}

	/**
	 * A carrier with everything but FMLEnvironment. The base's {@code SharedConstants.<clinit>} is the first game code
	 * that runs and calls NeoForge's; vanilla's {@code Main} catches what it throws, prints it to stderr and exits 249,
	 * so without the marker this launch passed the check and ended with nothing in latest.log.
	 */
	@Test
	void aCarrierWithoutFmlEnvironmentIsIncomplete(@TempDir Path dir) throws IOException {
		String environment = "net/neoforged/fml/loading/FMLEnvironment.class";
		assertTrue(LaunchInputCheck.markers(Ecosystem.NEOFORGE).contains(environment));
		assertEquals(List.of(Ecosystem.NEOFORGE), LaunchInputCheck.FAMILIES);

		Path stripped = entries(dir.resolve("neoforge-runtime.jar"), LaunchInputCheck.markers(Ecosystem.NEOFORGE)
				.stream().filter(m -> !m.equals(environment)).toArray(String[]::new));
		List<String> problems = LaunchInputCheck.problems(List.of(genuine(dir)), List.of(stripped));

		assertEquals(2, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar neoforge-runtime.jar is an incomplete NeoForge runtime: it is "
				+ "missing " + environment + " ("), problems.get(0));
	}

	/**
	 * A NeoForge mod under a runtime's name has NeoForge's manifest -- one of the markers -- and nothing else of it.
	 * Called an incomplete runtime, it read as a carrier missing its classes, which no mod has.
	 */
	@Test
	void aModJarUnderARuntimeNameIsCalledAModNotAnIncompleteRuntime(@TempDir Path dir) throws IOException {
		// The shape of sodium-neoforge-0.9.2+mc26.2.jar: the manifest, the mod itself nested, a few classes of its own.
		Path neoMod = entries(dir.resolve("neoforge-runtime-26.2.0.93.jar"), "META-INF/neoforge.mods.toml",
				"META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.9.2+mc26.2-mod.jar",
				"net/caffeinemc/mods/sodium/service/SodiumWorkarounds.class");

		List<String> problems = LaunchInputCheck.problems(List.of(genuine(dir)), List.of(neoMod));

		assertEquals(2, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar neoforge-runtime-26.2.0.93.jar looks like a NeoForge mod, not "
				+ "a runtime: it has META-INF/neoforge.mods.toml, which mods carry too, and none of the runtime's classes ["
				+ "net/neoforged/neoforgespi/language/IModInfo.class, "), problems.get(0));
		assertFalse(problems.get(0).contains("incomplete"), problems.get(0));
		assertTrue(problems.get(1).contains("contains a complete NeoForge runtime"), problems.get(1));

		// A manifest and a runtime class besides is a broken runtime, not a mod.
		Path broken = entries(dir.resolve("broken.jar"), "META-INF/neoforge.mods.toml",
				LaunchInputCheck.markers(Ecosystem.NEOFORGE).get(2));
		String said = LaunchInputCheck.problems(List.of(genuine(dir)), List.of(broken)).get(0);
		assertTrue(said.startsWith("runtime jar broken.jar is an incomplete NeoForge runtime"), said);

		// A MinecraftForge mod (its manifest only) is no NeoForge at all -- and no old carrier either.
		Path forgeMod = entries(dir.resolve("forge-mod.jar"), "META-INF/mods.toml");
		said = LaunchInputCheck.problems(List.of(genuine(dir)), List.of(forgeMod)).get(0);
		assertTrue(said.startsWith("runtime jar forge-mod.jar does not contain NeoForge"), said);
	}

	@Test
	void aLaunchWithNoRuntimeJarCannotStartAndSaysSo(@TempDir Path dir) throws IOException {
		// No --runtimeJar at all: this always died -- the base and the kernel's own game side name NeoForge classes --
		// but with a NoClassDefFoundError on stderr and nothing in latest.log.
		List<String> none = LaunchInputCheck.problems(List.of(genuine(dir)), List.of());
		assertEquals(1, none.size(), all(none));
		assertTrue(none.get(0).startsWith("nothing this launch was given contains a complete NeoForge runtime"), none.get(0));
		assertTrue(none.get(0).contains("no --runtimeJar was passed at all"), none.get(0));
	}

	@Test
	void theGameJarHasToBeNeoForgesPatchedGame(@TempDir Path dir) throws IOException {
		List<Path> runtimes = List.of(neo(dir));

		Path vanilla = base(dir.resolve("26.2.jar"), "net/minecraft/world/level/ItemLike");
		assertProblem(vanilla, runtimes, "game jar 26.2.jar is plain Minecraft, not NeoForge's patched game");

		// gson renamed to the base's name passes a name match and the installer's link check alike.
		Path notMinecraft = entries(dir.resolve("patched-mc-neoforge-26.2.jar"), "com/google/gson/Gson.class");
		assertProblem(notMinecraft, runtimes, "game jar patched-mc-neoforge-26.2.jar is not a Minecraft game jar: it "
				+ "has no " + LaunchInputCheck.BLOCK);

		Path brokenBlock = dir.resolve("broken.jar");
		try (OutputStream out = Files.newOutputStream(brokenBlock); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, LaunchInputCheck.BLOCK, "not a class".getBytes(StandardCharsets.UTF_8));
		}
		assertProblem(brokenBlock, runtimes, "game jar broken.jar cannot be read as a game jar");

		assertProblem(dir.resolve("gone.jar"), runtimes, "game jar gone.jar does not exist");
		assertTrue(all(LaunchInputCheck.problems(List.of(), runtimes)).contains("no game jar was given"));
	}

	private static void assertProblem(Path base, List<Path> runtimes, String expected) {
		List<String> problems = LaunchInputCheck.problems(List.of(base), runtimes);
		assertEquals(1, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith(expected), problems.get(0));
	}

	@Test
	void aCarrierPassedAsOneMoreGameJarIsOwnedAllTheSame(@TempDir Path dir) throws IOException {
		assertEquals(List.of(), LaunchInputCheck.problems(List.of(genuine(dir), neo(dir)), List.of()));
	}

	@Test
	void switchedOffItWarnsWithTheSameProblemsAndLaunchesAnyway(@TempDir Path dir) throws IOException {
		System.setProperty(LaunchInputCheck.SWITCH, "off");
		Path neo = entries(dir.resolve("neoforge-runtime-26.2.0.93.jar"));

		String said = KernelLoadReportTest.capture(() -> assertDoesNotThrow(
				() -> LaunchInputCheck.require(List.of(genuine(dir)), List.of(neo))));

		assertTrue(said.contains("[Forbric/WARN] [Forbric/Install] the Forbric install is broken"), said);
		assertTrue(said.contains("neoforge-runtime-26.2.0.93.jar does not contain NeoForge"), said);
		assertTrue(said.contains("-D" + LaunchInputCheck.SWITCH + "=off launches it anyway"), said);
		assertFalse(said.contains("[Forbric/ERROR]"), said);
	}

	@Test
	void aPercentSignInAPathIsPrintedNotFormatted(@TempDir Path dir) throws IOException {
		Path odd = Files.createDirectories(dir.resolve("100%d done"));
		Path neo = entries(odd.resolve("neoforge-runtime-26.2.0.93.jar"));

		String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
				() -> LaunchInputCheck.require(List.of(genuine(dir)), List.of(neo))));

		assertTrue(said.contains(neo.toString()), said);

		// And in an old install's evidence, which names files too.
		Path forge = forgeCarrier(odd.resolve("forge-runtime-%s-%d.jar"));
		String old = LaunchInputCheck.oldInstall(List.of(genuine(dir)), List.of(neo(dir), forge));
		assertTrue(old != null && old.contains("forge-runtime-%s-%d.jar is a complete MinecraftForge runtime"), old);
	}

	// --- an install made by Forbric 0.3.x or earlier (D9) ----------------------------------------------------

	/** The single problem an old install gets, with its evidence. */
	private static String oldInstallProblem(List<Path> gameJars, List<Path> runtimeJars) {
		List<String> problems = LaunchInputCheck.problems(gameJars, runtimeJars);
		assertEquals(1, problems.size(), "an old install is one problem, nothing else: " + all(problems));
		String problem = problems.get(0);
		assertTrue(problem.startsWith("this game was installed by Forbric 0.3.x or earlier (or a 0.4.0-dev build that "
				+ "still had MinecraftForge), still carries MinecraftForge ("), problem);
		assertTrue(problem.endsWith("); run the new installer again with the same game directory"), problem);
		assertEquals(problem, LaunchInputCheck.oldInstall(gameJars, runtimeJars));
		return problem;
	}

	/** Signal (1): the base's Block implements a MinecraftForge interface -- the merged base's shape. */
	@Test
	void aBaseWhoseBlockImplementsAMinecraftForgeInterfaceIsAnOldInstall(@TempDir Path dir) throws IOException {
		// The merged base is NeoForge-patched too, so the base check alone lets it through: the old-install check must.
		Path merged = base(dir.resolve("patched-mc-merged-26.2.jar"), "net/minecraft/world/level/ItemLike",
				NEO_EXTENSION, FORGE_EXTENSION);
		assertEquals(null, LaunchInputCheck.baseProblem(merged), "premise: the merged base carries NeoForge's changes");

		String problem = oldInstallProblem(List.of(merged), List.of(neo(dir)));
		assertTrue(problem.contains("game jar patched-mc-merged-26.2.jar's Block implements " + FORGE_EXTENSION), problem);

		// A MinecraftForge-only patched game names the same interface; it is no game of this Forbric either.
		Path forgeOnly = base(dir.resolve("patched-mc-forge-26.2.jar"), FORGE_EXTENSION);
		assertTrue(oldInstallProblem(List.of(forgeOnly), List.of(neo(dir))).contains("Block implements " + FORGE_EXTENSION));
	}

	/** Signal (2): the base embeds MinecraftForge's native-reference index. */
	@Test
	void aBaseEmbeddingMinecraftForgesNativeReferenceIndexIsAnOldInstall(@TempDir Path dir) throws IOException {
		Path indexed = base(dir.resolve("patched-mc-merged-26.2.jar"), List.of(LaunchInputCheck.OLD_FORGE_INDEX,
				"META-INF/forbric/native-reference/NEOFORGE/index.tsv"), NEO_EXTENSION);

		String problem = oldInstallProblem(List.of(indexed), List.of(neo(dir)));
		assertTrue(problem.contains("game jar patched-mc-merged-26.2.jar embeds " + LaunchInputCheck.OLD_FORGE_INDEX),
				problem);
		assertFalse(problem.contains("Block implements"), problem);
		// NeoForge's own half of that index is no signal: only MinecraftForge's is.
		Path neoIndex = base(dir.resolve("neo-indexed.jar"), List.of("META-INF/forbric/native-reference/NEOFORGE/index.tsv",
				"META-INF/forbric/native-reference/FABRIC/index.tsv"), NEO_EXTENSION);
		assertEquals(List.of(), LaunchInputCheck.problems(List.of(neoIndex), List.of(neo(dir))));
	}

	/**
	 * Signal (3): a {@code --runtimeJar}, or an extra {@code --gameJar}, is a complete MinecraftForge carrier -- in an
	 * old profile's one joined flag, a launcher's repeated flags, or one jar carrying both families.
	 */
	@Test
	void aCompleteMinecraftForgeCarrierBesideTheBaseIsAnOldInstall(@TempDir Path dir) throws IOException {
		Path forge = forgeCarrier(dir.resolve("forge-runtime-26.2-65.0.1.jar"));

		String problem = oldInstallProblem(List.of(genuine(dir)), List.of(forge, neo(dir)));
		assertTrue(problem.contains("runtime jar forge-runtime-26.2-65.0.1.jar is a complete MinecraftForge runtime"),
				problem);
		assertFalse(problem.contains("Block implements") || problem.contains(" embeds "), problem);

		assertTrue(oldInstallProblem(List.of(genuine(dir), forge), List.of(neo(dir)))
				.contains("game jar forge-runtime-26.2-65.0.1.jar is a complete MinecraftForge runtime"));

		Path both = entries(dir.resolve("both.jar"), java.util.stream.Stream.concat(
				LaunchInputCheck.markers(Ecosystem.NEOFORGE).stream(), LaunchInputCheck.OLD_FORGE_CARRIER.stream())
				.toArray(String[]::new));
		assertTrue(oldInstallProblem(List.of(genuine(dir)), List.of(both)).contains("runtime jar both.jar is a complete "
				+ "MinecraftForge runtime"));

		// Not complete, no signal: one marker short is a broken jar of some kind, judged by the ordinary checks.
		for (String left : LaunchInputCheck.OLD_FORGE_CARRIER) {
			Path partial = entries(dir.resolve("partial.jar"), LaunchInputCheck.OLD_FORGE_CARRIER.stream()
					.filter(entry -> !entry.equals(left)).toArray(String[]::new));
			assertEquals(null, LaunchInputCheck.oldInstall(List.of(genuine(dir)), List.of(partial, neo(dir))), left);
		}
	}

	/** All three at once -- the real merged install -- is still one problem, carrying every piece of evidence. */
	@Test
	void theWholeOldInstallIsOneProblemWithAllItsEvidence(@TempDir Path dir) throws IOException {
		Path merged = base(dir.resolve("patched-mc-merged-26.2.jar"), List.of(LaunchInputCheck.OLD_FORGE_INDEX),
				NEO_EXTENSION, FORGE_EXTENSION);
		Path forge = forgeCarrier(dir.resolve("forge-runtime-26.2-65.0.1.jar"));

		String problem = oldInstallProblem(List.of(merged), List.of(forge, neo(dir)));
		assertTrue(problem.contains("Block implements " + FORGE_EXTENSION + "; game jar patched-mc-merged-26.2.jar embeds "
				+ LaunchInputCheck.OLD_FORGE_INDEX + "; runtime jar forge-runtime-26.2-65.0.1.jar is a complete "
				+ "MinecraftForge runtime"), problem);

		String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
				() -> LaunchInputCheck.require(List.of(merged), List.of(forge, neo(dir)))));
		assertTrue(said.contains("[Forbric/ERROR] [Forbric/Install]   - this game was installed by Forbric 0.3.x or "
				+ "earlier (or a 0.4.0-dev build that still had MinecraftForge), still carries MinecraftForge ("), said);
		assertTrue(said.contains("(1 problem(s) below)"), said);
		assertTrue(said.contains("run the Forbric installer again with the same Game directory"), said);
	}

	/**
	 * Owner, 2026-11-17: 0.4.x is the first release without MinecraftForge, so the installs this refuses are named as the
	 * ones that had it -- every release up to v0.3.1-beta2 (0.3.x or earlier) and the 0.4.0-dev builds before the
	 * removal -- never as "0.4 or earlier", which a 0.4.x player would read as their own version. Both refusal lines go
	 * to {@code latest.log}, so like every kernel log line they are English in a Chinese locale too (gate-m17's control
	 * N matches them), which this holds with the dialogs' language forced to Chinese.
	 */
	@Test
	void theOldInstallIsNamedAsTheReleasesThatHadMinecraftForgeInEveryLocale(@TempDir Path dir) throws IOException {
		Path merged = base(dir.resolve("patched-mc-merged-26.2.jar"), NEO_EXTENSION, FORGE_EXTENSION);
		List<Path> runtimes = List.of(neo(dir));
		String english = oldInstallProblem(List.of(merged), runtimes);
		Locale before = Locale.getDefault();
		String languageBefore = System.getProperty("forbric.dialogLanguage");
		String zh;
		try {
			Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
			System.setProperty("forbric.dialogLanguage", "zh");
			zh = oldInstallProblem(List.of(merged), runtimes);
		} finally {
			Locale.setDefault(before);
			// Give back what the test JVM was started with, so a run that pins the dialogs' language keeps its pin.
			if (languageBefore == null) System.clearProperty("forbric.dialogLanguage");
			else System.setProperty("forbric.dialogLanguage", languageBefore);
		}
		assertEquals(english, zh, "the old-install line is a log line: the same in every locale");
		for (String line : List.of(english, LaunchInputCheck.OLD_INSTALL_SWITCH_OFF)) {
			assertTrue(line.contains("Forbric 0.3.x or earlier"), line);
			assertFalse(line.matches("(?s).*0\\.4 (or|and) (earlier|older).*"), line);
		}
	}

	/** D9: the switch that turns every other problem into a warning does not let an old install run. */
	@Test
	void switchedOffItStillRefusesAnOldInstall(@TempDir Path dir) throws IOException {
		System.setProperty(LaunchInputCheck.SWITCH, "off");
		Path merged = base(dir.resolve("patched-mc-merged-26.2.jar"), NEO_EXTENSION, FORGE_EXTENSION);

		for (List<Path> runtimes : List.of(List.of(neo(dir)), List.of(forgeCarrier(dir.resolve("forge.jar")), neo(dir)))) {
			String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
					() -> LaunchInputCheck.require(List.of(merged), runtimes)));
			assertTrue(said.contains("[Forbric/ERROR] [Forbric/Install] the Forbric install is broken"), said);
			assertTrue(said.contains("installed by Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had "
					+ "MinecraftForge), still carries MinecraftForge"), said);
			assertTrue(said.contains("[Forbric/ERROR] [Forbric/Install] -D" + LaunchInputCheck.SWITCH + "=off does not "
					+ "apply to an install made by Forbric 0.3.x or earlier: its game carries MinecraftForge, which this "
					+ "Forbric no longer has"), said);
			assertFalse(said.contains("[Forbric/WARN]"), said);
			assertFalse(said.contains("launches it anyway"), said);
		}
		// The signal from the runtime jar alone, too.
		List<Path> game = List.of(genuine(dir));
		List<Path> runtimes = List.of(forgeCarrier(dir.resolve("forge-runtime-26.2-65.0.1.jar")), neo(dir));
		String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
				() -> LaunchInputCheck.require(game, runtimes)));
		assertTrue(said.contains("forge-runtime-26.2-65.0.1.jar is a complete MinecraftForge runtime"), said);
	}

	// --- where the launch asks ------------------------------------------------------------------------------

	/** The steps of {@link KernelBoot#launch} that read the jars or the mods folder, each an empty answer for #13. */
	private static final String[][] READERS = {
			{"net/forbric/kernel/boot/KernelBoot", "detectGameVersion"},
			{"net/forbric/kernel/boot/DuplicateModArbiter", "arbitrate"},
			{"net/forbric/kernel/metadata/forge/EcosystemVersions", "record"},
			{"net/forbric/kernel/boot/KernelBoot", "discoverForgeFamilyModJars"},
			{"net/forbric/kernel/boot/PassiveSeeder", "arbitratedForgeFamilyMods"},
			// Reads the Forge-family jars' requirements when there is no plan, then KernelFabricEcosystem.scan.
			{"net/forbric/kernel/boot/KernelBoot", "scanFabricMods"},
			// The release line's: reads the selected nested jars' Forge-family manifests for cross-ecosystem presence.
			{"net/forbric/kernel/boot/KernelBoot", "publishNestedPresence"},
	};

	/**
	 * Everything above is worth something only if the launch asks, and the asking is one line in
	 * {@link KernelBoot#launch}. With that line gone every other test here still passes and issue #13 is back exactly:
	 * each reader in {@link #READERS} gives an empty answer for an empty carrier, and the boot dies later on stderr
	 * with five lines in latest.log. So the compiled launch is held to it: one call, ahead of the first call to every
	 * reader, and no handler around it that could catch the stop it throws.
	 */
	@Test
	void theLaunchChecksItsJarsBeforeAnythingReadsThem() throws IOException {
		MethodNode launch = launchMethod();
		List<Integer> checks = callSites(launch, "net/forbric/kernel/boot/LaunchInputCheck", "require");
		assertEquals(1, checks.size(), "KernelBoot.launch must call LaunchInputCheck.require exactly once: " + checks);
		int check = checks.get(0);

		for (String[] reader : READERS) {
			String name = reader[0].substring(reader[0].lastIndexOf('/') + 1) + "." + reader[1];
			List<Integer> sites = callSites(launch, reader[0], reader[1]);
			// Required, so a renamed reader fails here rather than silently dropping out of the comparison.
			assertFalse(sites.isEmpty(), "KernelBoot.launch no longer calls " + name + "; bring READERS up to date");
			assertTrue(check < sites.get(0), "KernelBoot.launch calls " + name + " before LaunchInputCheck.require; "
					+ "a jar with nothing in it is read before anyone has said so");
		}
		for (TryCatchBlockNode block : launch.tryCatchBlocks) {
			int start = launch.instructions.indexOf(block.start);
			int end = launch.instructions.indexOf(block.end);
			assertFalse(start <= check && check < end, "LaunchInputCheck.require sits inside a handler for "
					+ (block.type == null ? "any throwable" : block.type) + ", which can catch the stop it throws");
		}
	}

	/** Read from the class file the tests run against, so the check is of the launch that actually ships. */
	private static MethodNode launchMethod() throws IOException {
		String resource = "net/forbric/kernel/boot/KernelBoot.class";
		ClassNode node = new ClassNode();
		try (InputStream in = LaunchInputCheckTest.class.getClassLoader().getResourceAsStream(resource)) {
			assertTrue(in != null, "compiled class missing from the test classpath: " + resource);
			new ClassReader(in).accept(node, 0);
		}
		List<MethodNode> launches = node.methods.stream().filter(m -> m.name.equals("launch")).toList();
		assertEquals(1, launches.size(), "expected one KernelBoot.launch");
		return launches.get(0);
	}

	/** Instruction indexes of every call to {@code owner.name} in {@code method}, in order. */
	private static List<Integer> callSites(MethodNode method, String owner, String name) {
		List<Integer> sites = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
				sites.add(method.instructions.indexOf(insn));
			}
		}
		return sites;
	}

	// --- the real artifacts ---------------------------------------------------------------------------------

	private static Path staged(String relative) {
		return TestFixtures.stagedRoot().resolve(relative).normalize();
	}

	/**
	 * The definition above, held to the jars it describes: the staged base (NeoForge's patched game) and the one
	 * NeoForge carrier a launch passes as {@code --runtimeJar}, which is what the installer and tools/dev.py launch --
	 * and neither of them carries any of the old-install signals.
	 */
	@Test
	void theStagedBaseAndItsNeoForgeCarrierPass() {
		Path base = staged(TestFixtures.BASE_JAR);
		Path neo = staged(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.requireFiles(Fixture.STAGED,
				"staged game artifacts absent: " + base.getParent().getParent(), base, neo);

		assertEquals(null, LaunchInputCheck.oldInstall(List.of(base), List.of(neo)));
		assertEquals(List.of(), LaunchInputCheck.problems(List.of(base), List.of(neo)));
		assertEquals(java.util.Set.of(Ecosystem.NEOFORGE), LaunchInputCheck.read(neo).complete());
		assertDoesNotThrow(() -> LaunchInputCheck.require(List.of(base), List.of(neo)));
	}
}
