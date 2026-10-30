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
 * The check that the jars a launch was handed are a Forbric game, by content (issue #13).
 *
 * <p>The jars here are built to the check's own definition -- {@link LaunchInputCheck#markers} for a carrier, a
 * {@code Block} with the families' extension interfaces for the base -- and the staged test at the bottom holds that
 * definition to the real artifacts, so the two cannot agree with each other while disagreeing with the game.
 */
@ResourceLock("system-properties")
class LaunchInputCheckTest {
	private static final String NEO_EXTENSION = "net/neoforged/neoforge/common/extensions/IBlockExtension";
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

	/** A jar with only some of a family's markers. */
	private static Path entries(Path jar, String... names) throws IOException {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
			for (String name : names) put(zip, name, new byte[0]);
		}
		return jar;
	}

	/** A game jar whose {@code Block} implements {@code interfaces}, which is all the check reads of a base. */
	private static Path base(Path jar, String... interfaces) throws IOException {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/Block", null, "java/lang/Object",
				interfaces);
		cw.visitEnd();
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			put(zip, "version.json", "{\"id\":\"26.2\"}".getBytes(StandardCharsets.UTF_8));
			put(zip, LaunchInputCheck.BLOCK, cw.toByteArray());
		}
		return jar;
	}

	private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}

	private static Path merged(Path dir) throws IOException {
		return base(dir.resolve("patched-mc-merged-26.2.jar"), "net/minecraft/world/level/ItemLike", NEO_EXTENSION,
				FORGE_EXTENSION);
	}

	private static String all(List<String> problems) {
		return String.join("\n", problems);
	}

	@Test
	void aCompleteInstallHasNothingToSay(@TempDir Path dir) throws IOException {
		List<Path> runtimes = List.of(carrier(dir.resolve("forge-runtime-26.2.jar"), Ecosystem.FORGE),
				carrier(dir.resolve("neoforge-runtime-26.2.jar"), Ecosystem.NEOFORGE));

		assertEquals(List.of(), LaunchInputCheck.problems(List.of(merged(dir)), runtimes));
		assertDoesNotThrow(() -> LaunchInputCheck.require(List.of(merged(dir)), runtimes));
	}

	@Test
	void theCarriersAreRecognisedByContentInAnyOrderAndUnderAnyName(@TempDir Path dir) throws IOException {
		// A developer launch passes merged-base/forge-runtime-interop.jar; the order is whatever the launcher kept.
		Path forge = carrier(dir.resolve("forge-runtime-interop.jar"), Ecosystem.FORGE);
		Path neo = carrier(dir.resolve("whatever.jar"), Ecosystem.NEOFORGE);
		assertEquals(List.of(), LaunchInputCheck.problems(List.of(merged(dir)), List.of(neo, forge)));

		Path both = carrier(dir.resolve("both.jar"), Ecosystem.NEOFORGE, Ecosystem.FORGE);
		assertEquals(List.of(), LaunchInputCheck.problems(List.of(merged(dir)), List.of(both)));
	}

	@Test
	void issue13RuntimeJarsHoldingNeitherFamilyStopTheLaunchNamingEachJarAndTheFix(@TempDir Path dir)
			throws IOException {
		// What the player's installer staged: right names, opened fine, nothing of either family inside.
		Path forge = entries(dir.resolve("forge-runtime-26.2.jar"));
		Path neo = entries(dir.resolve("neoforge-runtime-26.2.jar"));
		List<String> problems = LaunchInputCheck.problems(List.of(merged(dir)), List.of(forge, neo));

		assertEquals(4, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar forge-runtime-26.2.jar contains neither NeoForge nor "
				+ "MinecraftForge"), problems.get(0));
		assertTrue(problems.get(0).contains("net/neoforged/neoforgespi/language/IModInfo.class"), problems.get(0));
		assertTrue(problems.get(0).contains(forge.toString()), "the player has to be able to find the file");
		assertTrue(problems.get(1).startsWith("runtime jar neoforge-runtime-26.2.jar contains neither"), problems.get(1));
		assertTrue(problems.get(2).contains("contains a complete NeoForge runtime"), problems.get(2));
		assertTrue(problems.get(3).contains("contains a complete MinecraftForge runtime"), problems.get(3));

		String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
				() -> LaunchInputCheck.require(List.of(merged(dir)), List.of(forge, neo))));
		// ForbricLog's fallback when log4j is absent, as it is on the test classpath. In the game these are the same
		// lines in latest.log.
		assertTrue(said.contains("[Forbric/ERROR] [Forbric/Install] the Forbric install is broken"), said);
		assertTrue(said.contains("forge-runtime-26.2.jar contains neither"), said);
		assertTrue(said.contains("run the Forbric installer again with the same Game directory and leave "
				+ "\"Built artifacts\" EMPTY"), said);
	}

	@Test
	void aRuntimeJarThatIsMissingOrIsNoJarIsNamed(@TempDir Path dir) throws IOException {
		Path absent = dir.resolve("nope/forge-runtime-26.2.jar");
		Path garbage = dir.resolve("neoforge-runtime-26.2.jar");
		Files.writeString(garbage, "<html>404 Not Found</html>");

		String problems = all(LaunchInputCheck.problems(List.of(merged(dir)), List.of(absent, garbage)));

		assertTrue(problems.contains("runtime jar forge-runtime-26.2.jar does not exist (" + absent + ")"), problems);
		assertTrue(problems.contains("runtime jar neoforge-runtime-26.2.jar cannot be opened as a jar"), problems);
	}

	@Test
	void aCarrierWithOnlyPartOfItsFamilyIsCalledIncompleteAndSaysWhatIsMissing(@TempDir Path dir) throws IOException {
		List<String> neo = LaunchInputCheck.markers(Ecosystem.NEOFORGE);
		Path partial = entries(dir.resolve("neoforge-runtime-26.2.jar"), neo.get(1), neo.get(2), neo.get(3));
		Path forge = carrier(dir.resolve("forge-runtime-26.2.jar"), Ecosystem.FORGE);

		List<String> problems = LaunchInputCheck.problems(List.of(merged(dir)), List.of(forge, partial));

		assertEquals(2, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar neoforge-runtime-26.2.jar is an incomplete NeoForge "
				+ "runtime: it is missing " + neo.get(0)), problems.get(0));
		assertTrue(problems.get(1).contains("contains a complete NeoForge runtime"), problems.get(1));
	}

	/**
	 * A carrier with everything but FMLEnvironment. The merged base's {@code SharedConstants.<clinit>} is the first
	 * game code that runs and calls NeoForge's; vanilla's {@code Main} catches what it throws, prints it to stderr and
	 * exits 249, so without the marker this launch passed the check and ended with nothing in latest.log.
	 */
	@Test
	void aCarrierWithoutFmlEnvironmentIsIncompleteForEitherFamily(@TempDir Path dir) throws IOException {
		assertTrue(LaunchInputCheck.markers(Ecosystem.NEOFORGE).contains("net/neoforged/fml/loading/FMLEnvironment.class"));
		assertTrue(LaunchInputCheck.markers(Ecosystem.FORGE).contains("net/minecraftforge/fml/loading/FMLEnvironment.class"));

		for (Ecosystem family : LaunchInputCheck.FAMILIES) {
			String environment = LaunchInputCheck.markers(family).stream().filter(m -> m.endsWith("/FMLEnvironment.class"))
					.findFirst().orElseThrow();
			Path stripped = entries(dir.resolve(family.name() + "-runtime-26.2.jar"), LaunchInputCheck.markers(family)
					.stream().filter(m -> !m.equals(environment)).toArray(String[]::new));
			Ecosystem other = family == Ecosystem.NEOFORGE ? Ecosystem.FORGE : Ecosystem.NEOFORGE;
			Path complete = carrier(dir.resolve(other.name() + "-runtime-26.2.jar"), other);

			List<String> problems = LaunchInputCheck.problems(List.of(merged(dir)), List.of(complete, stripped));

			assertEquals(2, problems.size(), all(problems));
			assertTrue(problems.get(0).startsWith("runtime jar " + stripped.getFileName() + " is an incomplete "
					+ family.displayName() + " runtime: it is missing " + environment + " ("), problems.get(0));
		}
	}

	/**
	 * A NeoForge or MinecraftForge mod under a runtime's name has the family's manifest -- one of the markers -- and
	 * nothing else of it. Called an incomplete runtime, it read as a carrier missing its classes, which no mod has.
	 */
	@Test
	void aModJarUnderARuntimeNameIsCalledAModNotAnIncompleteRuntime(@TempDir Path dir) throws IOException {
		Path forge = carrier(dir.resolve("forge-runtime-26.2.jar"), Ecosystem.FORGE);
		// The shape of sodium-neoforge-0.9.2+mc26.2.jar: the manifest, the mod itself nested, a few classes of its own.
		Path neoMod = entries(dir.resolve("neoforge-runtime-26.2.jar"), "META-INF/neoforge.mods.toml",
				"META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.9.2+mc26.2-mod.jar",
				"net/caffeinemc/mods/sodium/service/SodiumWorkarounds.class");

		List<String> problems = LaunchInputCheck.problems(List.of(merged(dir)), List.of(forge, neoMod));

		assertEquals(2, problems.size(), all(problems));
		assertTrue(problems.get(0).startsWith("runtime jar neoforge-runtime-26.2.jar looks like a NeoForge mod, not a "
				+ "runtime: it has META-INF/neoforge.mods.toml, which mods carry too, and none of the runtime's classes ["
				+ "net/neoforged/neoforgespi/language/IModInfo.class, "), problems.get(0));
		assertFalse(problems.get(0).contains("incomplete"), problems.get(0));
		assertTrue(problems.get(1).contains("contains a complete NeoForge runtime"), problems.get(1));

		Path neo = carrier(dir.resolve("neoforge-runtime.jar"), Ecosystem.NEOFORGE);
		Path forgeMod = entries(dir.resolve("forge-runtime-interop.jar"), "META-INF/mods.toml");
		String said = LaunchInputCheck.problems(List.of(merged(dir)), List.of(forgeMod, neo)).get(0);
		assertTrue(said.startsWith("runtime jar forge-runtime-interop.jar looks like a MinecraftForge mod, not a "
				+ "runtime: it has META-INF/mods.toml,"), said);

		// A mod declaring both manifests -- and one that has a runtime class besides is a broken runtime, not a mod.
		Path both = entries(dir.resolve("both.jar"), "META-INF/neoforge.mods.toml", "META-INF/mods.toml");
		said = LaunchInputCheck.problems(List.of(merged(dir)), List.of(both)).get(0);
		assertTrue(said.startsWith("runtime jar both.jar looks like a NeoForge and MinecraftForge mod, not a runtime: "
				+ "it has META-INF/neoforge.mods.toml and META-INF/mods.toml,"), said);
		Path broken = entries(dir.resolve("broken.jar"), "META-INF/neoforge.mods.toml",
				LaunchInputCheck.markers(Ecosystem.NEOFORGE).get(2));
		said = LaunchInputCheck.problems(List.of(merged(dir)), List.of(broken)).get(0);
		assertTrue(said.startsWith("runtime jar broken.jar is an incomplete NeoForge runtime"), said);
	}

	@Test
	void aLaunchWithNoRuntimeJarOrOnlyOneOfTheTwoCannotStartAndSaysSo(@TempDir Path dir) throws IOException {
		// No --runtimeJar at all: this always died -- the merged base and the kernel's own game side both name NeoForge
		// and MinecraftForge classes -- but with a NoClassDefFoundError on stderr and nothing in latest.log.
		String none = all(LaunchInputCheck.problems(List.of(merged(dir)), List.of()));
		assertTrue(none.contains("nothing this launch was given contains a complete NeoForge runtime"), none);
		assertTrue(none.contains("nothing this launch was given contains a complete MinecraftForge runtime"), none);
		assertTrue(none.contains("no --runtimeJar was passed at all"), none);

		// A launcher that keeps only the last of two repeated --runtimeJar flags.
		Path neo = carrier(dir.resolve("neoforge-runtime-26.2.jar"), Ecosystem.NEOFORGE);
		List<String> one = LaunchInputCheck.problems(List.of(merged(dir)), List.of(neo));
		assertEquals(1, one.size(), all(one));
		assertTrue(one.get(0).startsWith("nothing this launch was given contains a complete MinecraftForge runtime"),
				one.get(0));
		assertTrue(one.get(0).contains("[neoforge-runtime-26.2.jar]"), one.get(0));
	}

	@Test
	void theGameJarHasToBeTheMergedBase(@TempDir Path dir) throws IOException {
		List<Path> runtimes = List.of(carrier(dir.resolve("forge-runtime-26.2.jar"), Ecosystem.FORGE),
				carrier(dir.resolve("neoforge-runtime-26.2.jar"), Ecosystem.NEOFORGE));

		Path vanilla = base(dir.resolve("26.2.jar"), "net/minecraft/world/level/ItemLike");
		assertProblem(vanilla, runtimes, "game jar 26.2.jar is plain Minecraft, not Forbric's merged game");

		Path neoOnly = base(dir.resolve("neo-patched.jar"), NEO_EXTENSION);
		assertProblem(neoOnly, runtimes,
				"game jar neo-patched.jar carries only NeoForge's changes and not MinecraftForge's");

		Path forgeOnly = base(dir.resolve("forge-patched.jar"), FORGE_EXTENSION);
		assertProblem(forgeOnly, runtimes,
				"game jar forge-patched.jar carries only MinecraftForge's changes and not NeoForge's");

		// gson renamed to the merged base's name passes a name match and the installer's link check alike.
		Path notMinecraft = entries(dir.resolve("patched-mc-merged-26.2.jar"), "com/google/gson/Gson.class");
		assertProblem(notMinecraft, runtimes, "game jar patched-mc-merged-26.2.jar is not a Minecraft game jar: it "
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
		Path forge = carrier(dir.resolve("forge-runtime-26.2.jar"), Ecosystem.FORGE);
		Path neo = carrier(dir.resolve("neoforge-runtime-26.2.jar"), Ecosystem.NEOFORGE);

		assertEquals(List.of(), LaunchInputCheck.problems(List.of(merged(dir), forge, neo), List.of()));
	}

	@Test
	void switchedOffItWarnsWithTheSameProblemsAndLaunchesAnyway(@TempDir Path dir) throws IOException {
		System.setProperty(LaunchInputCheck.SWITCH, "off");
		Path forge = entries(dir.resolve("forge-runtime-26.2.jar"));

		String said = KernelLoadReportTest.capture(() -> assertDoesNotThrow(
				() -> LaunchInputCheck.require(List.of(merged(dir)), List.of(forge))));

		assertTrue(said.contains("[Forbric/WARN] [Forbric/Install] the Forbric install is broken"), said);
		assertTrue(said.contains("forge-runtime-26.2.jar contains neither"), said);
		assertTrue(said.contains("-D" + LaunchInputCheck.SWITCH + "=off launches it anyway"), said);
		assertFalse(said.contains("[Forbric/ERROR]"), said);
	}

	@Test
	void aPercentSignInAPathIsPrintedNotFormatted(@TempDir Path dir) throws IOException {
		Path odd = Files.createDirectories(dir.resolve("100%d done"));
		Path forge = entries(odd.resolve("forge-runtime-26.2.jar"));

		String said = KernelLoadReportTest.capture(() -> assertThrows(LaunchInputCheck.Rejected.class,
				() -> LaunchInputCheck.require(List.of(merged(dir)), List.of(forge))));

		assertTrue(said.contains(forge.toString()), said);
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
	 * The definition above, held to the jars it describes. Both MinecraftForge carrier spellings, because both can be
	 * launched: the installer and tools/dev.py use the interop one (the installer stages forge-runtime-interop.jar
	 * under the forge-runtime coordinate, as forge-runtime-26.2.jar), and run/launch-kernel-*.sh fall back to the plain
	 * forge-runtime.jar when the interop one has not been built.
	 */
	@Test
	void theStagedMergedBaseAndBothStagedCarrierSpellingsPass() {
		Path base = staged(TestFixtures.BASE_JAR);
		Path neo = staged("neoforge-runtime/neoforge-runtime.jar");
		Path forge = staged("forge-runtime/forge-runtime.jar");
		Path interop = staged("merged-base/forge-runtime-interop.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base) && Files.isRegularFile(neo) && Files.isRegularFile(forge),
				"staged game artifacts absent: " + base.getParent().getParent());

		assertEquals(List.of(), LaunchInputCheck.problems(List.of(base), List.of(forge, neo)));
		if (Files.isRegularFile(interop)) {
			assertEquals(List.of(), LaunchInputCheck.problems(List.of(base), List.of(interop, neo)));
		}
		// And the carriers are not mistaken for each other, which is what would let one stand in for both.
		assertEquals(java.util.Set.of(Ecosystem.NEOFORGE), LaunchInputCheck.read(neo).complete());
		assertEquals(java.util.Set.of(Ecosystem.FORGE), LaunchInputCheck.read(forge).complete());
	}
}
