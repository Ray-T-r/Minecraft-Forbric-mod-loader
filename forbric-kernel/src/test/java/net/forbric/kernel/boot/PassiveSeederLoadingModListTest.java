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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.DiscoveredMod;

/**
 * Verifies, against the STAGED neoforge-runtime bytecode, that the kernel seeds a {@code LoadingModList} a mod can
 * actually find itself in — the defect being that an empty one answers {@code null} to
 * {@code getModFileById(myId)} and NPEs the caller (Iris, inside {@code Minecraft.<init>}).
 *
 * <p>The three claims that matter are each a separate failure mode:
 * <ul>
 *   <li>{@code getModFileById(id).versionString()} returns the mod's REAL declared version — a placeholder there
 *       is the same bug, only quieter (mods render this string);</li>
 *   <li>{@code getMods()}' elements are assignable to the CONCRETE {@code ModInfo} — every consumer's per-element
 *       access is a {@code checkcast ModInfo}, so a dynamic proxy would blow up at the reader, not here;</li>
 *   <li>a jar the {@link MultiLoaderArbiter} handed to Fabric is absent — a universal jar must contribute under
 *       exactly the one ecosystem it was arbitrated to.</li>
 * </ul>
 *
 * <p>Everything needing the staged jar self-skips when it is absent; the arbitration case is pure kernel code and
 * always runs.
 */
class PassiveSeederLoadingModListTest {
	private static final Path NEO_RUNTIME = Path.of(System.getProperty("user.dir"), "..",
			"forbric-loader", "run", "neoforge-runtime", "neoforge-runtime.jar").normalize();

	@TempDir
	Path tmp;

	/** Stands in for {@code FMLLoader}: the seeder only ever touches its {@code loadingModList} field. */
	static final class FakeFmlLoader {
		private Object loadingModList;
	}

	@AfterEach
	void clearGlobals() {
		System.clearProperty(PassiveSeeder.SEED_SWITCH);
		System.clearProperty("forbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
	}

	@Test
	void seedsARealModFileInfoAndModInfoForANeoForgeJar() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "a LoadingModList must have been seeded");

		Object fileInfo = call(list, "getModFileById", String.class, "kerneltestmod");
		assertNotNull(fileInfo, "the mod must resolve itself through getModFileById");
		assertEquals("4.12.2", call(fileInfo, "versionString"),
				"versionString() must be the mod's DECLARED version, not a placeholder");

		List<?> seeded = (List<?>) call(list, "getMods");
		assertEquals(1, seeded.size(), "getMods() carries the one discovered mod");

		Class<?> modInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.ModInfo", false, game);
		assertTrue(modInfo.isInstance(seeded.get(0)),
				"elements must be the CONCRETE ModInfo — every reader checkcasts to it");

		Class<?> modFileInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFileInfo", false, game);
		assertTrue(modFileInfo.isInstance(fileInfo), "getModFileById checkcasts to the concrete ModFileInfo");
		assertEquals("Kernel Test Mod", call(seeded.get(0), "getDisplayName"));
		assertEquals("kerneltestmod", call(seeded.get(0), "getModId"));
		// getConfig() is dereferenced unguarded by FeatureFlagLoader during Bootstrap on every boot.
		assertNotNull(call(seeded.get(0), "getConfig"), "ModInfo.getConfig() must never be null");
		// The synthetic ModFile is what keeps toString()/getFilePath() from NPE-ing on a mod-loading error path.
		assertNotNull(call(fileInfo, "getFile"), "the ModFileInfo must carry a file");
		assertEquals("kerneltestmod", fileInfo.toString(), "ModFileInfo.toString() is modFile.getId()");
	}

	@Test
	void offSwitchRestoresTheEmptyList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		System.setProperty(PassiveSeeder.SEED_SWITCH, "off");
		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "the off switch still seeds a structurally valid list, just an empty one");
		assertTrue(((List<?>) call(list, "getMods")).isEmpty(), "off => zero mods");
		assertNull(call(list, "getModFileById", String.class, "kerneltestmod"), "off => nothing resolves");
	}

	@Test
	void zeroForgeFamilyModsKeepsTheEmptyList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "a zero-mod boot must behave exactly as before: an empty but present list");
		assertTrue(((List<?>) call(list, "getMods")).isEmpty());
	}

	@Test
	void aJarArbitratedToFabricIsNotInTheNeoForgeList() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("universal.jar"), "universalmod", "1.2.3", "Universal Mod", true);

		// Control: with the documented default preference the universal jar is claimed by NeoForge and IS listed.
		MultiLoaderArbiter.reset();
		List<DiscoveredMod> claimed = PassiveSeeder.arbitratedForgeFamilyMods(mods);
		assertEquals(1, claimed.size(), "a universal jar contributes exactly once by default");
		assertEquals("universalmod", claimed.get(0).getId());

		// Hand the same jar to Fabric: its Forge-family manifest must then contribute NOTHING here.
		System.setProperty("forbric.multiLoaderPreference", "fabric");
		MultiLoaderArbiter.reset();
		assertTrue(PassiveSeeder.arbitratedForgeFamilyMods(mods).isEmpty(),
				"a jar the arbiter gave to FABRIC must not appear in the NeoForge list");
	}

	// --- helpers ---

	private static Object seededList(FakeFmlLoader loader) throws Exception {
		Field field = FakeFmlLoader.class.getDeclaredField("loadingModList");
		field.setAccessible(true);
		return field.get(loader);
	}

	private static Object call(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static Object call(Object target, String method, Class<?> argType, Object arg) throws Exception {
		return target.getClass().getMethod(method, argType).invoke(target, arg);
	}

	/**
	 * A classloader over the staged neoforge-runtime jar plus the two logging types it links against but does not
	 * ship ({@code com.mojang.logging.LogUtils} / {@code org.slf4j}), which come from the Minecraft library tree at
	 * runtime and are not on the unit-test classpath. Only the static initializers need them, so empty stand-ins
	 * are enough; the test self-skips when the staged jar is absent.
	 */
	private ClassLoader neoForgeLoader() throws Exception {
		assumeTrue(Files.isRegularFile(NEO_RUNTIME),
				"staged neoforge-runtime.jar absent — skipping real-bytecode LoadingModList seeding check");
		Path stubs = Files.createDirectories(tmp.resolve("stubs"));
		writeClass(stubs, "org/slf4j/Logger", emptyInterface("org/slf4j/Logger"));
		writeClass(stubs, "org/slf4j/Marker", emptyInterface("org/slf4j/Marker"));
		writeClass(stubs, "org/slf4j/LoggerFactory", loggerFactory());
		writeClass(stubs, "com/mojang/logging/LogUtils", logUtils());
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), NEO_RUNTIME.toUri().toURL()},
				ClassLoader.getPlatformClassLoader());
	}

	private static void writeClass(Path root, String internalName, byte[] bytes) throws IOException {
		Path out = root.resolve(internalName + ".class");
		Files.createDirectories(out.getParent());
		Files.write(out, bytes);
	}

	private static byte[] emptyInterface(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, internalName,
				null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code class LogUtils { public static Marker FATAL_MARKER; public static Logger getLogger(){ return null; } }} */
	private static byte[] logUtils() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/mojang/logging/LogUtils", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FATAL_MARKER", "Lorg/slf4j/Marker;", null, null)
				.visitEnd();
		nullLogger(cw, "getLogger", "()Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code class LoggerFactory { public static Logger getLogger(Class|String){ return null; } }} */
	private static byte[] loggerFactory() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/slf4j/LoggerFactory", null, "java/lang/Object", null);
		nullLogger(cw, "getLogger", "(Ljava/lang/Class;)Lorg/slf4j/Logger;");
		nullLogger(cw, "getLogger", "(Ljava/lang/String;)Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void nullLogger(ClassWriter cw, String name, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	/** A minimal mod jar: a NeoForge {@code mods.toml}, optionally plus a {@code fabric.mod.json} (universal jar). */
	private static void writeModJar(Path jar, String modId, String version, String displayName, boolean alsoFabric)
			throws IOException {
		String toml = "modLoader=\"javafml\"\n"
				+ "loaderVersion=\"[1,)\"\n"
				+ "license=\"Apache-2.0\"\n"
				+ "\n"
				+ "[[mods]]\n"
				+ "modId=\"" + modId + "\"\n"
				+ "version=\"" + version + "\"\n"
				+ "displayName=\"" + displayName + "\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			put(zip, "META-INF/neoforge.mods.toml", toml);
			if (alsoFabric) {
				put(zip, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\""
						+ version + "\",\"name\":\"" + displayName + "\"}");
			}
		}
	}

	private static void put(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		OutputStream out = zip;
		out.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
