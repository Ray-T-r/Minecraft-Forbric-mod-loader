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

import static net.forbric.kernel.boot.PassiveSeederLoadingModListTest.call;
import static net.forbric.kernel.boot.PassiveSeederLoadingModListTest.seededList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.api.ModPresence;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.ModFileScanner;

/**
 * The {@code ModFile}s in the seeded NeoForge {@code LoadingModList} answer {@code getScanResult()} — against the
 * STAGED neoforge-runtime bytecode, whose getter throws "Scanning of this mod file has not started yet." whenever
 * {@code futureScanResult} is null.
 *
 * <p>What paid for it: RollingGate's constructor walks {@code LoadingModList.getModFiles()} and reads every entry's
 * {@code getFile().getScanResult().getAnnotations()} for its rule containers. Seeded files had no scan, so it threw
 * out of its own constructor and every RollingGate and Server++ rule was missing.
 *
 * <p>The claims, each its own failure mode: a NeoForge jar answers with ITS index, and with the same object
 * {@code KernelModFile} hands out for that jar; nothing is scanned until a mod asks; a Fabric presence entry and a
 * MinecraftForge entry answer empty, never with the index of a jar another ecosystem is running; a jar that cannot
 * be indexed answers empty instead of throwing; and {@code -Dforbric.seededScanData=off} is the old throw.
 */
class PassiveSeederScanResultTest {
	private static final String RULES = "Lprobe/Rules;";

	@TempDir
	Path tmp;

	@AfterEach
	void clearGlobals() {
		System.clearProperty(ModFileScanner.SEEDED_INDEX_PROPERTY);
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
		MultiLoaderArbiter.reset();
	}

	@Test
	void aSeededNeoForgeFileAnswersWithItsJarsIndexAndOtherEcosystemsAnswerEmpty() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path neoJar = mods.resolve("neoprobe.jar");
			writeJar(neoJar, "META-INF/neoforge.mods.toml", toml("neoprobe"), "probe/neo/RuleContainer");
			// MinecraftForge jar, annotated the same way: it is in this list for presence, and its index is the
			// other FML's business.
			writeJar(mods.resolve("forgeprobe.jar"), "META-INF/mods.toml", toml("forgeprobe"),
					"probe/forge/RuleContainer");
			// A Fabric jar, listed for presence only; outside mods/ as the Fabric side's own jars are.
			Path fabricJar = Files.createDirectories(tmp.resolve("fabric")).resolve("fabricprobe.jar");
			writeJar(fabricJar, "fabric.mod.json",
					"{\"schemaVersion\":1,\"id\":\"fabricprobe\",\"version\":\"1.0.0\"}", "probe/fabric/RuleContainer");
			ModPresence.publishFabric(new ForbricModDiscoverer().discoverJar(fabricJar));

			Object list = seed(game, mods);

			Object neoScan = scanOf(list, "neoprobe");
			Set<?> annotations = (Set<?>) call(neoScan, "getAnnotations");
			assertEquals(List.of("probe.neo.RuleContainer"), membersAnnotatedWith(annotations, RULES),
					"the NeoForge jar's own index, in FML's shape (TYPE members are dotted names)");
			assertSame(neoScan, scanOf(list, "neoprobe"), "computed once, then the same object");

			Object kernelFile = Class.forName("net.forbric.kernel.runtime.KernelModFile", true, game)
					.getConstructor(String.class, Path.class).newInstance("neoprobe", neoJar);
			assertSame(neoScan, call(kernelFile, "getScanResult"),
					"ModList's file for the same jar must read the same index, as one native ModFile would");

			assertTrue(((Set<?>) call(scanOf(list, "fabricprobe"), "getAnnotations")).isEmpty(),
					"a Fabric presence entry must not expose the annotations of a jar Fabric is running");
			assertTrue(((Set<?>) call(scanOf(list, "forgeprobe"), "getAnnotations")).isEmpty(),
					"a MinecraftForge entry must not expose its jar to NeoForge annotation walkers");
		}
	}

	@Test
	void nothingIsScannedUntilAModAsks() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path jar = mods.resolve("lazyprobe.jar");
			writeJar(jar, "META-INF/neoforge.mods.toml", toml("lazyprobe"), null);

			Object list = seed(game, mods);

			// The jar gains its annotated class only AFTER seeding. An index built at seed time would not have it.
			writeJar(jar, "META-INF/neoforge.mods.toml", toml("lazyprobe"), "probe/lazy/RuleContainer");
			Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

			assertEquals(List.of("probe.lazy.RuleContainer"),
					membersAnnotatedWith((Set<?>) call(scanOf(list, "lazyprobe"), "getAnnotations"), RULES),
					"the scan must run when getScanResult() is first called, not when the list is seeded");
		}
	}

	@Test
	void aJarThatCannotBeIndexedAnswersEmptyInsteadOfThrowing() throws Exception {
		// A game loader without the kernel's game-side classes, so the index cannot be materialised: the one way
		// ModFileScanner.scan hands back null for a perfectly readable jar.
		assumeTrue(Files.isRegularFile(PassiveSeederLoadingModListTest.NEO_RUNTIME), "staged neoforge-runtime absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		try (URLClassLoader game = new URLClassLoader(new URL[] {stubs.toUri().toURL(),
				PassiveSeederLoadingModListTest.NEO_RUNTIME.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			writeJar(mods.resolve("brokenprobe.jar"), "META-INF/neoforge.mods.toml", toml("brokenprobe"),
					"probe/broken/RuleContainer");

			Object list = seed(game, mods);

			Object scan = scanOf(list, "brokenprobe");
			assertTrue(((Set<?>) call(scan, "getAnnotations")).isEmpty(), "an unindexable jar reads as empty");
			assertSame(scan, scanOf(list, "brokenprobe"), "and says so once: the empty answer is kept");
		}
	}

	@Test
	void offSwitchIsTheOldNotStartedYetThrow() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			writeJar(mods.resolve("offprobe.jar"), "META-INF/neoforge.mods.toml", toml("offprobe"),
					"probe/off/RuleContainer");

			System.setProperty(ModFileScanner.SEEDED_INDEX_PROPERTY, "off");
			Object list = seed(game, mods);

			InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
					() -> scanOf(list, "offprobe"));
			assertInstanceOf(IllegalStateException.class, thrown.getCause());
			assertEquals("Scanning of this mod file has not started yet.", thrown.getCause().getMessage());
		}
	}

	// --- helpers ---

	private static Object seed(ClassLoader game, Path mods) throws Exception {
		PassiveSeederLoadingModListTest.FakeFmlLoader loader = new PassiveSeederLoadingModListTest.FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, PassiveSeederLoadingModListTest.FakeFmlLoader.class, loader,
				mods);
		Object list = seededList(loader);
		assumeTrue(list != null, "no LoadingModList was seeded");
		return list;
	}

	/** Exactly the walk RollingGate makes: {@code getModFileById(id).getFile().getScanResult()}. */
	private static Object scanOf(Object list, String modId) throws Exception {
		Object fileInfo = call(list, "getModFileById", String.class, modId);
		Object file = fileInfo.getClass().getMethod("getFile").invoke(fileInfo);
		return file.getClass().getMethod("getScanResult").invoke(file);
	}

	private static List<String> membersAnnotatedWith(Set<?> annotations, String descriptor) throws Exception {
		List<String> members = new ArrayList<>();
		for (Object data : annotations) {
			Object type = data.getClass().getMethod("annotationType").invoke(data);
			if (descriptor.equals(type.getClass().getMethod("getDescriptor").invoke(type))) {
				members.add((String) data.getClass().getMethod("memberName").invoke(data));
			}
		}
		return members;
	}

	/**
	 * The staged neoforge-runtime plus the kernel's compiled game side, child of this test's loader so the
	 * boot-side scanner is ONE class on both sides, the way {@code DelegationPolicy} pins it in a real boot.
	 */
	private URLClassLoader runtimeLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(PassiveSeederLoadingModListTest.NEO_RUNTIME),
				"staged neoforge-runtime or compiled game side absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), compiled.toUri().toURL(),
				PassiveSeederLoadingModListTest.NEO_RUNTIME.toUri().toURL()},
				PassiveSeederScanResultTest.class.getClassLoader());
	}

	private static String toml(String modId) {
		return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n[[mods]]\nmodId=\"" + modId
				+ "\"\nversion=\"1.0.0\"\n";
	}

	/** A jar with one metadata file and, optionally, one class carrying a CLASS-retention {@code @probe.Rules}. */
	private static void writeJar(Path jar, String metadataName, String metadata, String annotatedClass)
			throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			PassiveSeederLoadingModListTest.put(zip, metadataName, metadata);
			if (annotatedClass != null) {
				zip.putNextEntry(new ZipEntry(annotatedClass + ".class"));
				zip.write(annotated(annotatedClass));
				zip.closeEntry();
			}
		}
	}

	private static byte[] annotated(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		// Invisible, as RollingGate's container annotation and @JeiPlugin are: the index reads bytecode.
		AnnotationVisitor rules = cw.visitAnnotation(RULES, false);
		rules.visit("value", "probe_rules");
		rules.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
