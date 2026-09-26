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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.Optional;
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
import net.forbric.api.Side;

/**
 * A NeoForge mod that declares no {@code @Mod} class still gets the container NeoForge gives every mod it lists.
 *
 * <p>LibJF Translate is exactly that shape — a {@code javafml} jar-in-jar with no entry class — and LibJF finds its
 * configs by enumerating {@code ModList}. With no container, the config native NeoForge registers for it
 * ("Registering config for libjf_translate_v1") never existed. The Modrinth datapack wrappers
 * ({@code mr_fall_effects}) are the same shape again, in {@code mods/} this time.
 */
class KernelModLoaderClasslessTest {
	private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		System.clearProperty(KernelModLoader.CLASSLESS_SWITCH);
		MultiLoaderArbiter.reset();
	}

	// --- which declared mods get one ------------------------------------------------------------------------

	@Test
	void everyUnclaimedJavaOrLowCodeNeoForgeModGetsOne() {
		Map<String, KernelModLoader.Declared> declared = new LinkedHashMap<>();
		declared.put("with_class", declared("with_class", Ecosystem.NEOFORGE, "a.jar"));
		declared.put("translate", declared("translate", Ecosystem.NEOFORGE, "b.jar"));
		declared.put("lowcode", declared("lowcode", Ecosystem.NEOFORGE, "c.jar"));
		declared.put("kotlin", declared("kotlin", Ecosystem.NEOFORGE, "d.jar"));
		declared.put("forge_only", declared("forge_only", Ecosystem.FORGE, "e.jar"));
		declared.put("aliased", declared("aliased", Ecosystem.NEOFORGE, "f.jar"));
		Map<String, String> languages = Map.of("a.jar", "javafml", "b.jar", "javafml", "c.jar", "lowcodefml",
				"d.jar", "kotlinforforge", "e.jar", "lowcodefml", "f.jar", "javafml");

		List<KernelModLoader.Declared> classless = KernelModLoader.declaredWithoutClass(declared,
				Set.of("with_class", "aliased"), Ecosystem.NEOFORGE,
				entry -> languages.get(entry.jar().getFileName().toString()));

		assertEquals(List.of("translate", "lowcode"), ids(classless),
				"javafml and lowcodefml get one; an @Mod or an alias already answers for its id; a language the "
						+ "kernel has no provider for gets none; a MinecraftForge mod is not NeoForge's to give one");
	}

	@Test
	void aManifestThatCannotBeReadGivesNone() {
		Map<String, KernelModLoader.Declared> declared = Map.of("x", declared("x", Ecosystem.NEOFORGE, "x.jar"));
		assertTrue(KernelModLoader.declaredWithoutClass(declared, Set.of(), Ecosystem.NEOFORGE, entry -> null)
				.isEmpty());
	}

	@Test
	void theyComeInDependencyOrderAmongThemselves() {
		Map<String, KernelModLoader.Declared> declared = new LinkedHashMap<>();
		declared.put("manipulation", new KernelModLoader.Declared(new DiscoveredMod(Ecosystem.NEOFORGE,
				"manipulation", "1", "Manipulation", List.of(new net.forbric.api.UnifiedDependency("unsafe", "*",
						true)), List.of(), null, List.of(), "m.jar"),
				Path.of("m.jar")));
		declared.put("unsafe", declared("unsafe", Ecosystem.NEOFORGE, "u.jar"));

		assertEquals(List.of("unsafe", "manipulation"), ids(KernelModLoader.declaredWithoutClass(declared,
				Set.of(), Ecosystem.NEOFORGE, entry -> "javafml")));
	}

	@Test
	void switchedOffNothingGetsOne() {
		System.setProperty(KernelModLoader.CLASSLESS_SWITCH, "off");
		Map<String, KernelModLoader.Declared> declared = Map.of("x", declared("x", Ecosystem.NEOFORGE, "x.jar"));
		assertTrue(KernelModLoader.declaredWithoutClass(declared, Set.of(), Ecosystem.NEOFORGE, entry -> "javafml")
				.isEmpty());
	}

	@Test
	void theLanguageIsReadFromTheFamilysOwnManifest() throws Exception {
		Path lowcode = KernelModLoaderDeclaredTest.neoJar(tmp.resolve("lowcode.jar"), "lowcodefml", "x", "1", "");
		assertEquals("lowcodefml", KernelModLoader.languageOf(lowcode, Ecosystem.NEOFORGE));
		assertNull(KernelModLoader.languageOf(lowcode, Ecosystem.FORGE), "it has no mods.toml at all");

		Path unnamed = tmp.resolve("unnamed.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unnamed))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml",
					"loaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"y\"\nversion=\"1\"\n");
		}
		assertEquals("javafml", KernelModLoader.languageOf(unnamed, Ecosystem.NEOFORGE),
				"a manifest that names no modLoader is a Java one");
		assertNull(KernelModLoader.languageOf(Files.writeString(tmp.resolve("junk.jar"), "junk"), Ecosystem.NEOFORGE));
	}

	@Test
	void theRegistryEventsReachADeclaredOnlyModsBusToo() {
		Object shared = new Object();
		Object other = new Object();
		Object classlessBus = new Object();
		List<KernelModLoader.ConstructedMod> constructed = List.of(
				new KernelModLoader.ConstructedMod("balm", "NeoForgeBalm", Ecosystem.NEOFORGE, shared, null),
				new KernelModLoader.ConstructedMod("balm", "NeoForgeBalmClient", Ecosystem.NEOFORGE, shared, null),
				new KernelModLoader.ConstructedMod("forge", "ForgeMod", Ecosystem.FORGE, null, null),
				new KernelModLoader.ConstructedMod("other", "Other", Ecosystem.NEOFORGE, other, null));

		List<Object> buses = KernelLifecycle.registrationBuses(constructed,
				List.of(new KernelModLoader.NeoIdentity(classlessBus, "container")));

		assertEquals(3, buses.size(), "one bus per mod, however many @Mod classes share it: " + buses);
		assertTrue(buses.get(0) == shared && buses.get(1) == other, "constructed mods first, in their order");
		assertTrue(buses.get(2) == classlessBus,
				"a mod with no @Mod class is still posted RegisterEvent, as FML posts it to every container");
	}

	// --- against the real carrier: the container is in ModList and describes the mod ----------------------

	@Test
	void aClasslessModIsInModListBesideTheModsWithAClass() throws Exception {
		ClassLoader game = game();
		KernelModLoader.constructMods(game, List.of(pairJar), Side.DEDICATED_SERVER);

		Object withClass = container(game, "classlesstest_main");
		assertNotNull(withClass, "the @Mod half is constructed exactly as before");
		Object classless = container(game, "classlesstest_data");
		assertNotNull(classless, "the mod with no @Mod class must be in ModList too — NeoForge lists it");

		Object info = classless.getClass().getMethod("getModInfo").invoke(classless);
		Map<?, ?> properties = (Map<?, ?>) info.getClass().getMethod("getModProperties").invoke(info);
		assertTrue(properties.containsKey("libjf:entrypoints"),
				"its container describes it with its own [modproperties], where LibJF looks: " + properties);
		assertNotNull(classless.getClass().getMethod("getEventBus").invoke(classless),
				"it has a bus of its own, for its @EventBusSubscriber classes and the lifecycle to reach");

		assertTrue(KernelModLoader.publishedNeoMods().containsKey("classlesstest_data"),
				"the lifecycle posts at publishedNeoMods, and an @EventBusSubscriber resolves its bus there");
		assertEquals(Set.of("classlesstest_data"), KernelModLoader.classlessNeoMods().keySet());
		assertTrue(KernelModLoader.publishedNeoMods().containsKey("classlesstest_main"));
		assertEquals("classlesstest_data", KernelModLoader.soleClasslessNeoModIn(pairJar),
				"an unnamed @EventBusSubscriber with no @Mod class beside it resolves to the jar's class-less mod");
		assertNull(KernelModLoader.soleClasslessNeoModIn(shared.resolve("elsewhere.jar")));
	}

	@Test
	void switchedOffTheClasslessModIsAbsentAgain() throws Exception {
		System.setProperty(KernelModLoader.CLASSLESS_SWITCH, "off");
		ClassLoader game = game();
		KernelModLoader.constructMods(game, List.of(pairJar), Side.DEDICATED_SERVER);

		assertNotNull(container(game, "classlesstest_main"));
		assertNull(container(game, "classlesstest_data"), "off => the old behaviour, no container");
		assertFalse(KernelModLoader.publishedNeoMods().containsKey("classlesstest_data"));
		assertTrue(KernelModLoader.classlessNeoMods().isEmpty());
		assertNull(KernelModLoader.soleClasslessNeoModIn(pairJar), "and no subscriber falls back to it");
	}

	// One game loader for the class. KernelModContainerFactory memoises its game-side method once per process —
	// a boot has exactly one game loader — so a second loader would be handed the first one's method.
	@TempDir
	static Path shared;
	private static URLClassLoader gameLoader;
	private static Path pairJar;

	private static ClassLoader game() throws Exception {
		if (gameLoader == null) {
			pairJar = twoModJar(shared.resolve("mods/pair.jar"));
			gameLoader = gameLoader(pairJar);
			PassiveSeeder.seedNeoForgeModList(gameLoader);
		}
		return gameLoader;
	}

	@AfterAll
	static void forgetTheGameLoader() throws Exception {
		if (gameLoader != null) gameLoader.close();
		gameLoader = null;
		// And the factory's memo of it, so no later test class is handed a method of this closed loader.
		for (String memo : new String[] {"containerMethod", "modInfoMethod", "minecraftMethod"}) {
			Field field = KernelModContainerFactory.class.getDeclaredField(memo);
			field.setAccessible(true);
			field.set(null, null);
		}
	}

	// --- helpers --------------------------------------------------------------------------------------------

	private static KernelModLoader.Declared declared(String id, Ecosystem family, String jar) {
		return new KernelModLoader.Declared(new DiscoveredMod(family, id, "1", id, List.of(), List.of(), null,
				List.of(), jar), Path.of(jar));
	}

	private static List<String> ids(List<KernelModLoader.Declared> entries) {
		List<String> out = new ArrayList<>();
		for (KernelModLoader.Declared entry : entries) out.add(entry.mod().getId());
		return out;
	}

	private static Object container(ClassLoader game, String id) throws Exception {
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", false, game);
		Object list = modList.getMethod("get").invoke(null);
		Optional<?> found = (Optional<?>) modList.getMethod("getModContainerById", String.class).invoke(list, id);
		return found.orElse(null);
	}

	/**
	 * One {@code neoforge.mods.toml} declaring two mods, one of which has an {@code @Mod} class — the shape of a
	 * jar that ships a code mod and a data-only companion together.
	 */
	private static Path twoModJar(Path jar) throws IOException {
		Files.createDirectories(jar.getParent());
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
				+ "[[mods]]\nmodId=\"classlesstest_main\"\nversion=\"1.0\"\n"
				+ "[[mods]]\nmodId=\"classlesstest_data\"\nversion=\"2.0\"\n"
				+ "[[modproperties.classlesstest_data.\"libjf:entrypoints\".\"libjf:config\"]]\n"
				+ "value = \"example.Config\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml", toml);
			zip.putNextEntry(new ZipEntry("classlesstest/MainMod.class"));
			zip.write(modClass("classlesstest/MainMod", "classlesstest_main"));
			zip.closeEntry();
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

	/** The game side as a boot sees it: the kernel's runtime classes, both carriers and the mod jar. */
	private static URLClassLoader gameLoader(Path modJar) throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path neo = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path forge = STAGED.resolve("forge-runtime/forge-runtime.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(neo) && Files.isRegularFile(forge),
				"staged runtime classes/carriers absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(shared.resolve("stubs"));
		Set<URL> urls = new LinkedHashSet<>(List.of(compiled.toUri().toURL(), stubs.toUri().toURL(),
				neo.toUri().toURL(), forge.toUri().toURL(), modJar.toUri().toURL()));
		// NeoForge's event bus logs through log4j, which the unit-test classpath deliberately does not carry.
		String log4j = System.getProperty("forbric.log4jApiForTests", "");
		assumeTrue(!log4j.isEmpty() && Files.isRegularFile(Path.of(log4j)), "log4j-api for the event bus absent");
		urls.add(Path.of(log4j).toUri().toURL());
		return new URLClassLoader(urls.toArray(URL[]::new), KernelModLoaderClasslessTest.class.getClassLoader());
	}
}
