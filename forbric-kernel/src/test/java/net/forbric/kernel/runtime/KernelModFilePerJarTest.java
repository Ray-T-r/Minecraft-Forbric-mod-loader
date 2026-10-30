/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the walk from a NeoForge mod to its file and back answers, with one file per mod (the default) and with
 * {@code -Dforbric.modFilePerJar=on}, which builds them the way FML does.
 *
 * <p>Natively FML builds one {@code ModFile} and one {@code ModFileInfo} per jar: the file info lists every
 * {@code [[mods]]} entry of the jar's {@code neoforge.mods.toml} in file order, the file's id is the first entry's, and
 * {@code ModList.getAllScanData()} streams {@code sortedList → getOwningFile → getFile → distinct → getScanResult} —
 * the {@code distinct()} is by identity — so such a jar is listed once (javap on the carrier's {@code ModFile},
 * {@code ModFileInfo} and {@code ModList}). The kernel builds one file per MOD, and Stage 2 of the MinecraftForge
 * removal keeps it that way, so the switch is off by default and the first test pins that. The rest run with it on.
 * The index counts run the carrier's own {@code getAllScanData()} over kernel-built infos, so the dedupe judged is
 * NeoForge's, not a copy of it.
 *
 * <p>NeoForge infos are built through {@code KernelContainers.modInfo}, the entry point every NeoForge container's
 * info goes through; a Fabric mod's through the public constructor, which is what {@code KernelDeclarationReaders}
 * calls for the Fabric mods it describes.
 */
class KernelModFilePerJarTest {
	/** KernelModFile.PER_JAR_SWITCH; the game side is not on the test compile classpath. */
	private static final String PER_JAR_SWITCH = "forbric.modFilePerJar";
	@TempDir Path temp;

	@AfterEach
	void clear() {
		ModPresence.publishForgeFamily(List.of());
		System.clearProperty(PER_JAR_SWITCH);
	}

	/**
	 * Stage 2 changes nothing on the merged base: without the switch two mods of one jar keep a file each, each
	 * file's walk up lists its own mod alone, and NeoForge's own {@code distinct()} keeps both entries — the very same
	 * index object, which is why nothing was ever scanned twice, only walked twice. This is also gate-m21's default
	 * boot, measured here first.
	 */
	@Test
	void byDefaultEveryModKeepsAFileOfItsOwnAndTheIndexListsTheJarOncePerMod() throws Exception {
		Path both = neoJar("both.jar", "firstmod", "secondmod");
		try (URLClassLoader runtime = runtimeLoader()) {
			Object first = neo(runtime, "firstmod", both);
			Object second = neo(runtime, "secondmod", both);
			assertNotSame(file(first), file(second), "one file per mod, as the kernel has always built them");
			assertEquals(List.of(second), mods(fileInfoOf(file(second))), "the walk up lists the mod itself alone");
			assertEquals("secondmod", id(file(second)));
			assertEquals(List.of(), invoke(file(first), "getModInfos"), "a mod's own file answers getModInfos as before");
			List<?> scans = allScanData(runtime, List.of(first, second));
			assertEquals(2, scans.size(), "the old shape: one entry per mod");
			assertSame(scans.get(0), scans.get(1), "and both entries are the jar's one shared index");
		}
	}

	@Test
	void withTheSwitchOnTwoModsOfOneJarShareOneFileAndTheIndexListsTheJarOnce() throws Exception {
		System.setProperty(PER_JAR_SWITCH, "on");
		Path both = neoJar("both.jar", "firstmod", "secondmod");
		Path other = neoJar("other.jar", "othermod");
		try (URLClassLoader runtime = runtimeLoader()) {
			Object first = neo(runtime, "firstmod", both);
			Object second = neo(runtime, "secondmod", both);
			Object third = neo(runtime, "othermod", other);
			assertSame(file(first), file(second), "one jar is one IModFile, as FML builds it");
			assertSame(owning(first), owning(second), "and one file info, as FML builds it");
			assertNotSame(file(first), file(third), "two jars stay two files");
			// A presence alias has no jar to share, and each one stays its own placeholder.
			assertNotSame(file(neo(runtime, "alias", null)), file(neo(runtime, "alias", null)));

			assertEquals(2, allScanData(runtime, List.of(first, second, third)).size(),
					"getAllScanData() lists each JAR once: both.jar and other.jar");
		}
	}

	/**
	 * The walk up from either mod lists every mod of the jar, in {@code [[mods]]} order, whichever was built first —
	 * the review's case: the second mod's walk listed only the first mod, which did not contain itself. Built here
	 * second-entry first, with a {@code @Mod}-only id (no entry of its own, which the kernel still gives a container,
	 * like the canary's client-only mod) joining between them; it goes after every manifest entry.
	 */
	@Test
	void theWalkUpFromEveryModOfAJarListsAllOfThemInManifestOrder() throws Exception {
		System.setProperty(PER_JAR_SWITCH, "on");
		Path both = neoJar("both.jar", "firstmod", "secondmod");
		try (URLClassLoader runtime = runtimeLoader()) {
			Object second = neo(runtime, "secondmod", both);
			Object classOnly = neo(runtime, "classonlymod", both);
			Object first = neo(runtime, "firstmod", both);
			List<Object> expected = List.of(first, second, classOnly);
			for (Object mod : expected) {
				Object walkedUp = fileInfoOf(file(mod));
				assertSame(owning(mod), walkedUp, "the file's info is the mod's own owning file");
				assertEquals(expected, mods(walkedUp), "getModFileInfo().getMods() from " + mod);
				assertEquals(expected, invoke(file(mod), "getModInfos"), "ModFile.getModInfos() is the info's mods");
			}
			// versionString is getMods().getFirst()'s, so the jar's first entry's version, not the first built.
			assertEquals("1.0.0", invoke(owning(second), "versionString"));
		}
	}

	/** {@code ModFile}'s constructor sets {@code id = modFileInfo.getMods().getFirst().getModId()}: the first entry. */
	@Test
	void theSharedFileIsNamedAfterTheJarsFirstModsEntryWhicheverModIsBuiltFirst() throws Exception {
		System.setProperty(PER_JAR_SWITCH, "on");
		Path both = neoJar("both.jar", "firstmod", "secondmod");
		try (URLClassLoader runtime = runtimeLoader()) {
			Object second = neo(runtime, "secondmod", both);
			assertEquals("firstmod", id(file(second)), "named after the first [[mods]] entry, before that mod exists");
			neo(runtime, "firstmod", both);
			assertEquals("firstmod", id(file(second)));
		}
	}

	/**
	 * A universal jar's Fabric half and NeoForge half never share a file. The kernel describes the Fabric mod as an
	 * {@code IModInfo} too ({@code KernelDeclarationReaders}), over the same jar, and built first it used to become
	 * the shared file's owner: its id named the file, and the NeoForge mod's walk up listed the Fabric mod. Natively no
	 * NeoForge file lists a Fabric mod. Nor does a NeoForge-path info whose declaration names another ecosystem join.
	 */
	@Test
	void aUniversalJarsFabricModNeverJoinsItsNeoForgeModsFile() throws Exception {
		System.setProperty(PER_JAR_SWITCH, "on");
		Path universal = universalJar("universal.jar", "fabricmod", "neomod");
		DiscoveredMod fabricDeclared = discovered(universal, Ecosystem.FABRIC);
		try (URLClassLoader runtime = runtimeLoader()) {
			Object fabric = fabric(runtime, "fabricmod", universal, fabricDeclared);
			Object neo = neo(runtime, "neomod", universal);
			assertNotSame(file(fabric), file(neo), "the Fabric declarer keeps a file of its own");
			assertEquals("neomod", id(file(neo)), "the NeoForge file is named after its own first entry");
			assertEquals(List.of(neo), mods(fileInfoOf(file(neo))), "and lists the NeoForge mod alone");
			assertEquals(List.of(fabric), mods(fileInfoOf(file(fabric))));

			Object routedFabric = neoForgePath(runtime, "fabricmod", universal, fabricDeclared);
			assertNotSame(file(neo), file(routedFabric), "a Fabric declaration is not a NeoForge one on any path");
			assertFalse(mods(fileInfoOf(file(neo))).contains(routedFabric));
		}
	}

	// --- reflection over the runtime source set, which the test compile classpath does not carry ---------------

	private Object neo(ClassLoader runtime, String modId, Path jar) throws Exception {
		if (jar != null) ModPresence.publishForgeFamily(new ForbricModDiscoverer().discoverJar(jar));
		return Class.forName("net.forbric.kernel.runtime.KernelContainers", true, runtime)
				.getMethod("modInfo", String.class, Path.class).invoke(null, modId, jar);
	}

	private static Object fabric(ClassLoader runtime, String modId, Path jar, DiscoveredMod declared) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
				.getConstructor(String.class, Path.class, DiscoveredMod.class).newInstance(modId, jar, declared);
	}

	/** {@code KernelModInfo.neoForge}, package-private and in another loader, so reached by reflection. */
	private static Object neoForgePath(ClassLoader runtime, String modId, Path jar, DiscoveredMod declared)
			throws Exception {
		Method method = Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
				.getDeclaredMethod("neoForge", String.class, Path.class, DiscoveredMod.class);
		method.setAccessible(true);
		return method.invoke(null, modId, jar, declared);
	}

	private static DiscoveredMod discovered(Path jar, Ecosystem ecosystem) throws Exception {
		for (DiscoveredMod mod : new ForbricModDiscoverer().discoverJar(jar)) {
			if (mod.getEcosystem() == ecosystem) return mod;
		}
		throw new AssertionError("no " + ecosystem + " declaration in " + jar);
	}

	private static Object owning(Object info) throws Exception {
		return invoke(info, "getOwningFile");
	}

	private static Object file(Object info) throws Exception {
		return invoke(owning(info), "getFile");
	}

	private static Object fileInfoOf(Object file) throws Exception {
		return invoke(file, "getModFileInfo");
	}

	private static Object id(Object file) throws Exception {
		return invoke(file, "getId");
	}

	private static List<?> mods(Object fileInfo) throws Exception {
		return new ArrayList<>((List<?>) invoke(fileInfo, "getMods"));
	}

	private static Object invoke(Object target, String method) throws Exception {
		Object result = target.getClass().getMethod(method).invoke(target);
		return result instanceof List<?> list ? new ArrayList<>(list) : result;
	}

	/**
	 * The carrier's {@code ModList.getAllScanData()} over {@code infos}, with {@code sortedList} filled as
	 * KernelModLoader.fillModInfos fills it. The list is allocated without its constructor, which registers a crash
	 * report callable through Mojang's logging library — not on this classpath, and nothing the method reads:
	 * {@code getAllScanData()} touches {@code sortedList} and its own memo field alone.
	 */
	private static List<?> allScanData(ClassLoader runtime, List<Object> infos) throws Exception {
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, runtime);
		Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object list = ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(modList);
		Field sorted = modList.getDeclaredField("sortedList");
		sorted.setAccessible(true);
		sorted.set(list, infos);
		Method all = modList.getMethod("getAllScanData");
		return (List<?>) all.invoke(list);
	}

	/** A jar declaring {@code modIds} as NeoForge mods, the first at 1.0.0 and the rest at 2.0.0. */
	private Path neoJar(String name, String... modIds) throws Exception {
		return jar(name, neoToml(modIds), null);
	}

	private Path universalJar(String name, String fabricId, String neoId) throws Exception {
		return jar(name, neoToml(neoId), "{\"schemaVersion\":1,\"id\":\"" + fabricId + "\",\"version\":\"3.0.0\"}");
	}

	private static String neoToml(String... modIds) {
		StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n");
		for (int i = 0; i < modIds.length; i++) {
			toml.append("[[mods]]\nmodId=\"").append(modIds[i]).append("\"\nversion=\"")
					.append(i == 0 ? "1.0.0" : "2.0.0").append("\"\n");
		}
		return toml.toString();
	}

	private Path jar(String name, String neoToml, String fabricJson) throws Exception {
		Path path = temp.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry(ForbricModDiscoverer.NEOFORGE_MANIFEST));
			zip.write(neoToml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			if (fabricJson != null) {
				zip.putNextEntry(new ZipEntry(ForbricModDiscoverer.FABRIC_MANIFEST));
				zip.write(fabricJson.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		assertTrue(Files.isRegularFile(path));
		return path;
	}

	private static URLClassLoader runtimeLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFile(Fixture.STAGED, carrier, "staged carrier absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "staged runtime classes absent");
		return new URLClassLoader(new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL()},
				KernelModFilePerJarTest.class.getClassLoader());
	}
}
