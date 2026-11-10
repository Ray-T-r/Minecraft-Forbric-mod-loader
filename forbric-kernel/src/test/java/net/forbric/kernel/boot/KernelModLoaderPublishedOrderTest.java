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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

import net.forbric.api.Side;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link KernelModLoader#publishedNeoMods()} walks in {@code ModList}'s order.
 *
 * <p>Every NeoForge setup phase the kernel posts — {@code FMLConstructModEvent}, common setup, the sided setup, both IMC
 * phases and load complete ({@code KernelNeoSetup.firePhase}) — walks that map. It was a {@code Map.copyOf}, so the walk
 * was a hash order salted per JVM: a mod could take its common setup before the library it depends on, and in a
 * different order on the next launch. Native NeoForge dispatches each phase over {@code ModList.getSortedMods()} with
 * every container waiting for its dependencies ({@code ModLoader.dispatchParallelTask}). ModList is published in the
 * kernel's dependency order, so walking it keeps "a dependency first" (Stage 4 C5 lane K3b).
 */
class KernelModLoaderPublishedOrderTest {
	private static final Path STAGED = TestFixtures.stagedRoot().normalize();

	/**
	 * The jar's eight mods. The staged case does not rely on this order (the scanner may visit the classes in its own
	 * order): it compares the published map with ModList itself. Eight ids, so a hash order matches ModList's by chance
	 * once in 8! = 40320 runs.
	 */
	private static final List<String> SCAN_ORDER = List.of("ordertest_e", "ordertest_b", "ordertest_h", "ordertest_a",
			"ordertest_g", "ordertest_c", "ordertest_f", "ordertest_d");

	@AfterEach
	void reset() {
		MultiLoaderArbiter.reset();
	}

	@Test
	void thePublishedMapKeepsTheOrderItIsGiven() {
		Map<String, Integer> constructed = new LinkedHashMap<>();
		for (int i = 0; i < SCAN_ORDER.size(); i++) constructed.put(SCAN_ORDER.get(i), i);
		Map<String, Integer> classless = new LinkedHashMap<>();
		classless.put("zz_data", 100);
		classless.put("aa_data", 101);

		Map<String, Integer> published = KernelModLoader.published(constructed, classless);

		List<String> expected = new ArrayList<>(SCAN_ORDER);
		expected.addAll(List.of("zz_data", "aa_data"));
		assertEquals(expected, List.copyOf(published.keySet()),
				"the constructed mods in ModList's order, then the mods with no @Mod class, as publishNeoModList hands "
						+ "them to ModList — a hash order here is the setup phases' delivery order");
		assertThrows(UnsupportedOperationException.class, () -> published.put("x", 0),
				"what the lifecycle walks is read-only");
	}

	/**
	 * Against the real carrier: after {@code constructMods}, walking {@code publishedNeoMods()} is walking
	 * {@code ModList.getSortedMods()}.
	 */
	@Test
	void walkingThePublishedModsIsWalkingModList() throws Exception {
		ClassLoader game = game();
		KernelModLoader.constructMods(game, List.of(modsJar), Side.DEDICATED_SERVER);

		Class<?> modListCls = Class.forName("net.neoforged.fml.ModList", false, game);
		Object modList = modListCls.getMethod("get").invoke(null);
		List<String> modListOrder = new ArrayList<>();
		for (Object container : (List<?>) modListCls.getMethod("getSortedMods").invoke(modList)) {
			String id = (String) container.getClass().getMethod("getModId").invoke(container);
			if (KernelModLoader.publishedNeoMods().containsKey(id)) modListOrder.add(id);
		}

		assertEquals(Set.copyOf(SCAN_ORDER), KernelModLoader.publishedNeoMods().keySet(),
				"every mod of the jar is constructed and published");
		assertEquals(SCAN_ORDER.size(), modListOrder.size(), "and each is in ModList: " + modListOrder);
		assertEquals(modListOrder, List.copyOf(KernelModLoader.publishedNeoMods().keySet()),
				"the setup phases go to the NeoForge mods in ModList's order, as native NeoForge dispatches them");
	}

	// One game loader for the class. KernelModContainerFactory memoises its game-side method once per process —
	// a boot has exactly one game loader — so a second loader would be handed the first one's method.
	@TempDir
	static Path shared;
	private static URLClassLoader gameLoader;
	private static Path modsJar;

	private static ClassLoader game() throws Exception {
		if (gameLoader == null) {
			modsJar = eightModJar(shared.resolve("mods/eight.jar"));
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

	/** One {@code neoforge.mods.toml} with eight mods, each with its {@code @Mod} class, written in {@link #SCAN_ORDER}. */
	private static Path eightModJar(Path jar) throws IOException {
		Files.createDirectories(jar.getParent());
		StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n");
		for (String id : SCAN_ORDER) toml.append("[[mods]]\nmodId=\"").append(id).append("\"\nversion=\"1.0\"\n");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml", toml.toString());
			for (String id : SCAN_ORDER) {
				String name = "ordertest/" + Character.toUpperCase(id.charAt(id.length() - 1)) + "Mod";
				zip.putNextEntry(new ZipEntry(name + ".class"));
				zip.write(modClass(name, id));
				zip.closeEntry();
			}
		}
		return jar;
	}

	/** {@code @Mod(id) public class <name> { public <name>() {} }} */
	private static byte[] modClass(String internalName, String modId) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		AnnotationVisitor mod = cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
		mod.visit("value", modId);
		mod.visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The game side as a boot sees it: the kernel's runtime classes, the NeoForge carrier and the mod jar. */
	private static URLClassLoader gameLoader(Path modJar) throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path neo = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.requireFiles(Fixture.STAGED, "staged runtime classes/carrier absent", neo);
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "staged runtime classes/carriers absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(shared.resolve("stubs"));
		Set<URL> urls = new LinkedHashSet<>(List.of(compiled.toUri().toURL(), stubs.toUri().toURL(),
				neo.toUri().toURL(), modJar.toUri().toURL()));
		String log4j = System.getProperty("forbric.log4jApiForTests", "");
		assertTrue(!log4j.isEmpty() && Files.isRegularFile(Path.of(log4j)),
				"log4j-api for the event bus absent: '" + log4j + "'");
		urls.add(Path.of(log4j).toUri().toURL());
		return new URLClassLoader(urls.toArray(URL[]::new), KernelModLoaderPublishedOrderTest.class.getClassLoader());
	}
}
