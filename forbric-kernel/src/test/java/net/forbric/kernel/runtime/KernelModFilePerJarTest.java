/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.api.ModPresence;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A jar that declares two NeoForge mods is ONE file to NeoForge, and {@code ModList.getAllScanData()} lists it once.
 *
 * <p>Natively FML builds one {@code ModFile} per jar, and {@code getAllScanData()} streams {@code sortedList →
 * getOwningFile → getFile → distinct → getScanResult} — the {@code distinct()} is by identity. The kernel built one
 * {@code KernelModFile} per MOD, so the NeoForge canary (two {@code @Mod} classes in one jar) printed "2 file(s)" with
 * one jar staged, and a mod walking the index met every class of such a jar twice. These run the carrier's own
 * {@code ModList.getAllScanData()} over kernel-built mod infos, so the dedupe judged is NeoForge's, not a copy of it.
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

	@Test
	void twoModsOfOneJarShareOneFileAndTheIndexListsTheJarOnce() throws Exception {
		Path both = jar("both.jar", "firstmod", "secondmod");
		Path other = jar("other.jar", "othermod");
		try (URLClassLoader runtime = runtimeLoader()) {
			Object first = info(runtime, "firstmod", both);
			Object second = info(runtime, "secondmod", both);
			Object third = info(runtime, "othermod", other);
			assertSame(file(first), file(second), "one jar is one IModFile, as FML builds it");
			assertNotSame(file(first), file(third), "two jars stay two files");
			// The first mod of the jar names the file, as the first [[mods]] entry does natively.
			assertEquals("firstmod", file(second).getClass().getMethod("getId").invoke(file(second)));
			// A presence alias has no jar to share, and each one stays its own placeholder.
			assertNotSame(file(info(runtime, "alias", null)), file(info(runtime, "alias", null)));

			assertEquals(2, allScanData(runtime, List.of(first, second, third)).size(),
					"getAllScanData() lists each JAR once: both.jar and other.jar");
		}
	}

	/**
	 * The RED control gate-m21 uses for the count, measured here first: one file per mod again, and NeoForge's own
	 * {@code distinct()} keeps both of the jar's entries. The two entries are the very same index object, which is why
	 * nothing was ever scanned twice — only walked twice.
	 */
	@Test
	void theOffSwitchPutsBackOneFilePerModAndTheJarIsListedTwice() throws Exception {
		Path both = jar("both.jar", "firstmod", "secondmod");
		System.setProperty(PER_JAR_SWITCH, "off");
		try (URLClassLoader runtime = runtimeLoader()) {
			Object first = info(runtime, "firstmod", both);
			Object second = info(runtime, "secondmod", both);
			assertNotSame(file(first), file(second));
			List<?> scans = allScanData(runtime, List.of(first, second));
			assertEquals(2, scans.size(), "the old shape: one entry per mod");
			assertSame(scans.get(0), scans.get(1), "and both entries are the jar's one shared index");
		}
	}

	private Object info(ClassLoader runtime, String modId, Path jar) throws Exception {
		if (jar != null) ModPresence.publishForgeFamily(new ForbricModDiscoverer().discoverJar(jar));
		return Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
				.getConstructor(String.class, Path.class).newInstance(modId, jar);
	}

	private static Object file(Object info) throws Exception {
		Object owning = info.getClass().getMethod("getOwningFile").invoke(info);
		return owning.getClass().getMethod("getFile").invoke(owning);
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

	private Path jar(String name, String... modIds) throws Exception {
		Path path = temp.resolve(name);
		StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n");
		for (String id : modIds) toml.append("[[mods]]\nmodId=\"").append(id).append("\"\nversion=\"1.0.0\"\n");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
			zip.write(toml.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
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
