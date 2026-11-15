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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.boot.DuplicateModArbiter.Claim;
import net.forbric.kernel.boot.DuplicateModArbiter.Decision;

/**
 * {@code forbric-disabled.txt}: a jar listed there stays in {@code mods/} and is loaded by nobody.
 *
 * <p>Driven through the real {@code arbitrate(Path, EnvType)} with synthetic jars, and then through the readers
 * that decide what actually loads — Fabric discovery, Forge-family discovery and the NeoForge seeder — because a
 * decision that says "suppressed" while one of them still walks the jar is the failure this exists to prevent.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class DisabledModsTest {
	@TempDir
	Path rundir;

	private Locale original;

	@BeforeEach
	void fresh() {
		original = Locale.getDefault();
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		KernelLoadReport.reset();
		net.forbric.api.CompatibilityFindings.reset();
		System.clearProperty(DuplicateModArbiter.SWITCH);
	}

	@AfterEach
	void restore() {
		Locale.setDefault(original);
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		KernelLoadReport.reset();
		net.forbric.api.CompatibilityFindings.reset();
		System.clearProperty(DuplicateModArbiter.SWITCH);
		ModCatalog.publish(List.of());
	}

	@Test
	void theFileIsReadLikeTheOverrideFileAndABadLineCostsOnlyItself() {
		List<String> names = DisabledMods.parse(List.of(
				"# switched off after a crash",
				"",
				"   sodium-fabric-0.6.0.jar   # trailing comment",
				"mods/iris.jar",
				"C:\\mods\\iris.jar",
				"sodium",
				".jar",
				"Chloride v1.8.jar",
				"sodium-fabric-0.6.0.jar"));

		// A path, a mod id and a bare extension are not jar file names; guessing which jar they meant would
		// switch off the wrong one. A name with a space in it is a real file name.
		assertEquals(List.of("sodium-fabric-0.6.0.jar", "Chloride v1.8.jar"), names);
	}

	@Test
	void aListedJarIsSuppressedButNeverARescueJarAndNothingDiscoversIt() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		Path keep = fabricJar(mods, "keep.jar", "keepme");
		Path off = fabricJar(mods, "off.jar", "offmod");
		Path neoOff = neoJar(mods, "neo-off.jar", "neooff");
		Path neoKeep = neoJar(mods, "neo-keep.jar", "neokeep");
		Files.writeString(rundir.resolve(DisabledMods.FILE), "off.jar\nneo-off.jar\n");

		Decision d = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);

		assertTrue(d.suppressed(off) && d.suppressed(neoOff), d.toString());
		assertFalse(d.suppressed(keep) || d.suppressed(neoKeep), d.toString());
		// The class loader serves a missing class out of a rescue jar; a mod the player switched off must not
		// run piecemeal that way.
		assertFalse(d.rescueJars().contains(off.toAbsolutePath()) || d.rescueJars().contains(neoOff.toAbsolutePath()),
				d.rescueJars().toString());
		assertEquals(List.of("off.jar", "neo-off.jar"), DisabledMods.switchedOff());

		// Fabric discovery reads the whole-instance plan, which never saw the switched-off jar.
		var fabric = KernelFabricEcosystem.scan(EnvType.CLIENT, rundir, d);
		assertEquals(List.of("keepme"), fabric.getContainers().stream().map(c -> c.getMetadata().getId()).toList());
		assertFalse(fabric.getClasspathJars().contains(off), fabric.getClasspathJars().toString());
		// Forge-family discovery walks mods/ itself and asks the decision.
		var forge = KernelBoot.discoverForgeFamilyModJars(mods, d);
		assertEquals(List.of(neoKeep), forge.jars());
		// The seeder asks current(), long after the decision was made.
		assertEquals(List.of("neokeep"), seededIds(mods));
	}

	@Test
	void theOffSwitchStillHonoursTheFileAndPublishesItForTheSeeder() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		Path neoOff = neoJar(mods, "neo-off.jar", "neooff");
		neoJar(mods, "neo-keep.jar", "neokeep");
		Path fabricOff = fabricJar(mods, "fabric-off.jar", "fabricoff");
		fabricJar(mods, "fabric-keep.jar", "fabrickeep");
		Files.writeString(rundir.resolve(DisabledMods.FILE), "neo-off.jar\nfabric-off.jar\n");
		System.setProperty(DuplicateModArbiter.SWITCH, "off");

		Decision d = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);

		assertEquals(Set.of(neoOff.toAbsolutePath(), fabricOff.toAbsolutePath()), d.suppressedJars());
		assertEquals(Set.of(), d.rescueJars());
		assertEquals(d, DuplicateModArbiter.current(), "the seeder reads current(), not the returned value");
		assertEquals(List.of("neokeep"), seededIds(mods));
		var fabric = KernelFabricEcosystem.scan(EnvType.CLIENT, rundir, d);
		assertEquals(List.of("fabrickeep"), fabric.getContainers().stream().map(c -> c.getMetadata().getId()).toList());
		assertEquals(d, DuplicateModArbiter.arbitrateNested(EnvType.CLIENT, List.of()),
				"the nested pass with the switch off is phase one's answer, switched-off jars included");
	}

	@Test
	void withTheSwitchOffAndNoFileNothingIsCached() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		fabricJar(mods, "a.jar", "a");
		System.setProperty(DuplicateModArbiter.SWITCH, "off");

		assertEquals(Decision.none(), DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT));
		assertEquals(Decision.none(), DuplicateModArbiter.current());
	}

	@Test
	void switchingOffOneBuildOfADuplicateLeavesTheOtherUncontested() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		Path fabric = fabricJar(mods, "thing-fabric.jar", "duplicated");
		Path neo = neoJar(mods, "thing-neoforge.jar", "duplicated");
		Files.writeString(rundir.resolve(DisabledMods.FILE), "thing-neoforge.jar\n");

		Decision d = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);

		assertTrue(d.suppressed(neo));
		assertFalse(d.suppressed(fabric), "the build that is not switched off is the one that loads");
		assertEquals(Map.of(), d.ownerByModId(), "a switched-off jar is not a claim, so nothing was contested");
		assertFalse(Files.exists(rundir.resolve(DuplicateModArbiter.OVERRIDE_FILE)),
				"no duplicate is left to pick between, so there is no override template to write");
	}

	@Test
	void aNameWithNoJarBehindItSwitchesNothingOffAndCasingIsForgiven() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		Path sodium = fabricJar(mods, "sodium.jar", "sodium");
		Files.writeString(rundir.resolve(DisabledMods.FILE), "Sodium.JAR\nlong-gone.jar\n");

		Decision d = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);

		assertEquals(Set.of(sodium.toAbsolutePath()), d.suppressedJars());
		assertEquals(List.of("sodium.jar"), DisabledMods.switchedOff(), "the report names the file as it is on disk");
	}

	@Test
	void anUnreadableFileSwitchesNothingOff() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		fabricJar(mods, "a.jar", "a");
		// A directory where the file should be: reading it throws, and that must cost nothing but the list.
		Files.createDirectories(rundir.resolve(DisabledMods.FILE));

		Decision d = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);

		assertEquals(Set.of(), d.suppressedJars());
		assertEquals(List.of(), DisabledMods.switchedOff());
	}

	@Test
	void appendingKeepsWhatThePlayerWroteAndNeverListsAJarTwice() throws Exception {
		Locale.setDefault(Locale.ENGLISH);
		DisabledMods.append(rundir, List.of("a.jar", "b.jar"), "2026-10-03: first");
		String first = Files.readString(rundir.resolve(DisabledMods.FILE));
		assertTrue(first.startsWith("# The jars listed here stay in your mods folder"), first);
		assertTrue(first.contains("# 2026-10-03: first\na.jar\nb.jar\n"), first);

		Files.writeString(rundir.resolve(DisabledMods.FILE), first + "mine.jar # the player's own line");
		DisabledMods.append(rundir, List.of("b.jar", "c.jar"), "2026-10-04: second");

		String second = Files.readString(rundir.resolve(DisabledMods.FILE));
		assertTrue(second.startsWith(first + "mine.jar # the player's own line\n"), second);
		assertTrue(second.endsWith("# 2026-10-04: second\nc.jar\n"), second);
		assertEquals(List.of("a.jar", "b.jar", "mine.jar", "c.jar"), DisabledMods.parse(second.lines().toList()));
	}

	@Test
	void theNestedPassDoesNotTurnASwitchedOffJarIntoARescueJar() {
		Path off = Path.of("/mods/off.jar").toAbsolutePath();
		Path fabricLib = Path.of("/cache/lib-fabric.jar").toAbsolutePath();
		Path neoLib = Path.of("/cache/lib-neoforge.jar").toAbsolutePath();
		Decision phase1 = new Decision(Set.of(off), Map.of(), List.of(), Set.of());

		Decision d = DuplicateModArbiter.arbitrateNested(phase1, List.of(),
				List.of(new Claim(fabricLib, Ecosystem.FABRIC, List.of("lib")), new Claim(neoLib, Ecosystem.NEOFORGE, List.of("lib"))));

		assertTrue(d.suppressed(off), "phase one's answer is kept");
		assertFalse(d.rescueJars().contains(off), d.rescueJars().toString());
		// The nested loser is still what it always was: another build of a mod that did load.
		assertTrue(d.rescueJars().contains(fabricLib) || d.rescueJars().contains(neoLib), d.toString());
	}

	@Test
	void theLoadReportNamesTheSwitchedOffJarsInBothLanguages() {
		String en = KernelLoadReport.render(false, List.of(), List.of(), List.of(), List.of("off.jar", "neo-off.jar"));
		assertTrue(en.contains("2 mod(s) switched off in forbric-disabled.txt: off.jar, neo-off.jar"), en);
		assertTrue(en.contains("delete its line"), "it must say how to undo it: " + en);
		assertFalse(en.contains("did not finish loading"), "nothing failed: " + en);

		String zh = KernelLoadReport.render(true, List.of(), List.of(), List.of(), List.of("off.jar", "neo-off.jar"));
		assertTrue(zh.contains("forbric-disabled.txt 里关掉了 2 个 mod：off.jar、neo-off.jar"), zh);
		assertTrue(zh.contains("删掉"), zh);
		assertFalse(zh.contains("没有完成加载"), zh);
	}

	@Test
	void aBootWithOnlySwitchedOffModsKeepsTheSuccessLineAndWritesTheFile() throws Exception {
		Path mods = Files.createDirectories(rundir.resolve("mods"));
		fabricJar(mods, "off.jar", "offmod");
		Files.writeString(rundir.resolve(DisabledMods.FILE), "off.jar\n");
		DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);
		ModCatalog.publish(List.of());
		Path report = rundir.resolve("load-report.txt");

		// The pre-game boundary writes first, as evidence, and must not spend the success line the end of loading
		// says: gate-m24c caught exactly that order.
		String said = KernelLoadReportTest.capture(() -> {
			KernelLoadReport.writeTo(report, false);
			KernelLoadReport.writeTo(report, true);
		});

		assertTrue(said.contains("every mod finished loading"), "switching a mod off is not a failure: " + said);
		assertTrue(said.contains("1 mod(s) switched off in forbric-disabled.txt: off.jar"), said);
		assertTrue(Files.readString(report).contains("off.jar"), "a jar in mods/ that never loads must be named");

		DuplicateModArbiter.reset();
		KernelLoadReport.writeTo(report);
		assertFalse(Files.exists(report), "with nothing switched off and nothing failed, a clean boot keeps no file");
	}

	private static List<String> seededIds(Path mods) throws Exception {
		return PassiveSeeder.arbitratedForgeFamilyMods(mods).stream().map(DiscoveredMod::getId).toList();
	}

	private static Path fabricJar(Path mods, String name, String id) throws Exception {
		return jar(mods.resolve(name), "fabric.mod.json",
				"{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"}");
	}

	private static Path neoJar(Path mods, String name, String id) throws Exception {
		return jar(mods.resolve(name), "META-INF/neoforge.mods.toml",
				"modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"x\"\n[[mods]]\nmodId=\"" + id
						+ "\"\nversion=\"1.0.0\"\n");
	}

	private static Path jar(Path jar, String entry, String content) throws Exception {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(entry));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	/**
	 * A new forbric-disabled.txt explains itself in the system language; the names under it are the same either way.
	 * Red-checked: with {@code DisabledMods.header()} asking {@code Locale.ROOT} instead of the default locale, and with
	 * its Chinese lines replaced by the English ones, the zh assertion failed.
	 */
	@Test
	void aNewFileExplainsItselfInTheSystemLanguage(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		Path zhDir = Files.createDirectories(dir.resolve("zh"));
		Path enDir = Files.createDirectories(dir.resolve("en"));
		Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
		DisabledMods.append(zhDir, List.of("a.jar"), "2026-11-16: first");
		Locale.setDefault(Locale.ENGLISH);
		DisabledMods.append(enDir, List.of("a.jar"), "2026-11-16: first");

		String zh = Files.readString(zhDir.resolve(DisabledMods.FILE));
		String en = Files.readString(enDir.resolve(DisabledMods.FILE));
		assertTrue(zh.startsWith("# 这里列出的 jar 文件留在 mods 文件夹里，但 Forbric 不会加载它们。\n"), zh);
		assertTrue(zh.contains("# 一行写一个文件名。想重新启用哪个，就把它那一行删掉。\n"), zh);
		assertTrue(en.startsWith("# The jars listed here stay in your mods folder, but Forbric does not load them.\n"), en);
		assertEquals(DisabledMods.parse(zh.lines().toList()), DisabledMods.parse(en.lines().toList()));
		assertEquals(List.of("a.jar"), DisabledMods.parse(zh.lines().toList()));
	}
}
