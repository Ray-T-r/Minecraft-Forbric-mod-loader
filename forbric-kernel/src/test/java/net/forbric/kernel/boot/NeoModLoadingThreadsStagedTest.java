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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.api.Side;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * NeoForge mods are constructed on NeoForge's own {@code modloading-worker} threads with their own mod active in
 * {@code ModLoadingContext} there, a mod only after the mods it requires — on the real NeoForge carrier (26.2.0.93).
 *
 * <p>Native NeoForge's {@code ModLoader.constructMods} and {@code dispatchParallelEvent} hand every container to
 * {@code ModWorkManager.parallelExecutor()}, whose threads are named {@code modloading-worker-<n>}; on the native .93
 * server every probe mod's constructor, {@code FMLConstructModEvent} and setup listener ran on {@code modloading-worker-0}
 * (Stage 4 C5 lane THR, VERIFY run 2's C-7). The kernel ran them on the caller's thread — {@code main}, or the render
 * thread on a client — one mod after another.
 */
class NeoModLoadingThreadsStagedTest {
	private static final Path STAGED = TestFixtures.stagedRoot().normalize();
	private static final String PREFIX = "forbric.thrtest.";
	private static final List<String> IDS = List.of("thrtest_lib", "thrtest_dep", "thrtest_free");

	@AfterEach
	void reset() {
		MultiLoaderArbiter.reset();
		for (String key : Set.copyOf(System.getProperties().stringPropertyNames())) {
			if (key.startsWith(PREFIX)) System.clearProperty(key);
		}
	}

	@Test
	void eachModIsConstructedOnAModloadingWorkerWithItsOwnContainerActive() throws Exception {
		ClassLoader game = game();
		ModPresence.publishForgeFamily(List.of(declared("thrtest_lib"),
				declared("thrtest_dep", new UnifiedDependency("thrtest_lib", "*", true)), declared("thrtest_free")));
		try {
			KernelModLoader.constructMods(game, List.of(modsJar), Side.DEDICATED_SERVER);
		} finally {
			ModPresence.publishForgeFamily(List.of());
		}

		String caller = Thread.currentThread().getName();
		for (String id : IDS) {
			String thread = System.getProperty(PREFIX + id + ".thread");
			assertTrue(thread != null, id + "'s constructor ran");
			assertNotEquals(caller, thread, id + " is not constructed on the caller's thread");
			assertTrue(thread.startsWith("modloading-worker"),
					id + " is constructed on NeoForge's modloading-worker pool, as natively: " + thread);
			assertEquals(id, System.getProperty(PREFIX + id + ".active"),
					"ModLoadingContext on that worker names the mod being constructed");
		}
		assertEquals("true", System.getProperty(PREFIX + "thrtest_dep.libFirst"),
				"a mod is constructed only after the mod it requires");
	}

	private static DiscoveredMod declared(String id, UnifiedDependency... deps) {
		return new DiscoveredMod(Ecosystem.NEOFORGE, id, "1.0", id, List.of(deps), List.of(), null, "threads.jar");
	}

	// One game loader for the class: KernelModContainerFactory memoises its game-side method once per process.
	@TempDir
	static Path shared;
	private static URLClassLoader gameLoader;
	private static Path modsJar;

	private static ClassLoader game() throws Exception {
		if (gameLoader == null) {
			modsJar = threadsJar(shared.resolve("mods/threads.jar"));
			gameLoader = gameLoader(modsJar);
			PassiveSeeder.seedNeoForgeModList(gameLoader);
		}
		return gameLoader;
	}

	@AfterAll
	static void forgetTheGameLoader() throws Exception {
		if (gameLoader != null) gameLoader.close();
		gameLoader = null;
		for (String memo : new String[] {"containerMethod", "modInfoMethod", "minecraftMethod"}) {
			Field field = KernelModContainerFactory.class.getDeclaredField(memo);
			field.setAccessible(true);
			field.set(null, null);
		}
	}

	/**
	 * Three mods in one {@code neoforge.mods.toml}, each with an {@code @Mod} class whose constructor writes down its thread
	 * and the namespace {@code ModLoadingContext} names there; {@code thrtest_dep} requires {@code thrtest_lib} and writes
	 * down whether {@code thrtest_lib} had been constructed first.
	 */
	private static Path threadsJar(Path jar) throws IOException {
		Files.createDirectories(jar.getParent());
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
				+ "[[mods]]\nmodId=\"thrtest_lib\"\nversion=\"1.0\"\n"
				+ "[[mods]]\nmodId=\"thrtest_dep\"\nversion=\"1.0\"\n"
				+ "[[mods]]\nmodId=\"thrtest_free\"\nversion=\"1.0\"\n"
				+ "[[dependencies.thrtest_dep]]\nmodId=\"thrtest_lib\"\ntype=\"required\"\nversionRange=\"[1,)\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml", toml);
			for (String id : IDS) {
				String name = "thrtest/" + id.substring("thrtest_".length()).toUpperCase() + "Mod";
				zip.putNextEntry(new ZipEntry(name + ".class"));
				zip.write(modClass(name, id, id.equals("thrtest_dep") ? "thrtest_lib" : null));
				zip.closeEntry();
			}
		}
		return jar;
	}

	/**
	 * {@code @Mod(id) public class <name> { public <name>() { sleep(100); System.setProperty(PREFIX+id+".thread",
	 * Thread.currentThread().getName()); System.setProperty(PREFIX+id+".active",
	 * ModLoadingContext.get().getActiveNamespace()); [System.setProperty(PREFIX+id+".libFirst",
	 * String.valueOf(System.getProperty(PREFIX+waitsFor+".thread") != null));] } }}
	 */
	private static byte[] modClass(String internalName, String modId, String waitsFor) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		AnnotationVisitor mod = cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
		mod.visit("value", modId);
		mod.visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, new String[] {"java/lang/Exception"});
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (waitsFor != null) {
			// Before the wait below: was the mod it requires already constructed when this one started?
			init.visitLdcInsn(PREFIX + modId + ".libFirst");
			init.visitLdcInsn(PREFIX + waitsFor + ".thread");
			init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;", false);
			org.objectweb.asm.Label absent = new org.objectweb.asm.Label(), done = new org.objectweb.asm.Label();
			init.visitJumpInsn(Opcodes.IFNULL, absent);
			init.visitLdcInsn("true");
			init.visitJumpInsn(Opcodes.GOTO, done);
			init.visitLabel(absent);
			init.visitLdcInsn("false");
			init.visitLabel(done);
			setProperty(init);
		}
		// Long enough that a dependent started at the same time would see this one unfinished.
		init.visitLdcInsn(100L);
		init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
		init.visitLdcInsn(PREFIX + modId + ".thread");
		init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
		init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName", "()Ljava/lang/String;", false);
		setProperty(init);
		init.visitLdcInsn(PREFIX + modId + ".active");
		init.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/fml/ModLoadingContext", "get",
				"()Lnet/neoforged/fml/ModLoadingContext;", false);
		init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/fml/ModLoadingContext", "getActiveNamespace",
				"()Ljava/lang/String;", false);
		setProperty(init);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void setProperty(MethodVisitor mv) {
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
				"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
		mv.visitInsn(Opcodes.POP);
	}

	/** The game side as a boot sees it: the kernel's runtime classes, the NeoForge carrier and the mod jar. */
	private static URLClassLoader gameLoader(Path... modJars) throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path neo = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.requireFiles(Fixture.STAGED, "staged runtime classes/carrier absent", neo);
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "staged runtime classes/carriers absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(shared.resolve("stubs"));
		Set<URL> urls = new LinkedHashSet<>(List.of(compiled.toUri().toURL(), stubs.toUri().toURL(),
				neo.toUri().toURL()));
		for (Path modJar : modJars) urls.add(modJar.toUri().toURL());
		String log4j = System.getProperty("forbric.log4jApiForTests", "");
		assertTrue(!log4j.isEmpty() && Files.isRegularFile(Path.of(log4j)),
				"log4j-api for the event bus absent: '" + log4j + "'");
		urls.add(Path.of(log4j).toUri().toURL());
		return new URLClassLoader(urls.toArray(URL[]::new), NeoModLoadingThreadsStagedTest.class.getClassLoader());
	}
}
