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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import net.forbric.api.Side;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pins the one-shot: {@code FMLEnvironment} must be decided by the kernel's seeding, not by whoever touched it
 * first.
 *
 * <p>Against the REAL staged MinecraftForge carrier, because the whole defect lives in bytecode the kernel does
 * not own. {@code FMLEnvironment.<clinit>} copies four {@code FMLLoader} getters into {@code public static final}
 * fields and has no exception table — it cannot fail, cannot be retried, and cannot be detected after the fact by
 * anything except reading the values back. That is unlike the {@code LoadingModList} holder, which at least went
 * ERRONEOUS; here a premature reader just quietly wins.
 *
 * <p>{@link #anEarlyReaderPoisonsDistPermanently} is the regression test for the defect itself, and it is the one
 * state no gate can reach once the fix is in: with the seeding hoisted ahead of Mixin, nothing in a real boot gets
 * to read {@code FMLEnvironment} first any more.
 *
 * <p>Self-skips when the carrier is not staged.
 */
class PassiveSeederForgeFmlEnvironmentTest {
	private static final Path FORGE_RUNTIME = Path.of(System.getProperty("user.dir"), "..",
			"forbric-loader", "run", "forge-runtime", "forge-runtime.jar").normalize();

	@TempDir
	Path tmp;

	@org.junit.jupiter.api.AfterEach
	void clearGuard() {
		PassiveSeeder.resetForgeIdentityForTests();
	}

	@Test
	void theKernelDecidesDistOnTheClientSide() throws Exception {
		ClassLoader cl = forgeLoader();
		PassiveSeeder.seedForgeFmlLoader(cl, tmp, Side.CLIENT);
		assertEquals("CLIENT", String.valueOf(dist(cl)));
		assertEquals("mojmap", environment(cl).getField("naming").get(null));
		assertEquals(true, environment(cl).getField("production").get(null));
	}

	@Test
	void andOnTheDedicatedServerSide() throws Exception {
		ClassLoader cl = forgeLoader();
		PassiveSeeder.seedForgeFmlLoader(cl, tmp, Side.DEDICATED_SERVER);
		assertEquals("DEDICATED_SERVER", String.valueOf(dist(cl)));
	}

	/**
	 * The defect. A guest mixin config plugin's {@code <clinit>} reading {@code FMLEnvironment.dist} during
	 * Mixin's {@code prepareConfigs} is exactly this: the initializer runs against an unseeded {@code FMLLoader},
	 * {@code dist} is decided null, and no later seeding can move it.
	 */
	@Test
	void anEarlyReaderPoisonsDistPermanently() throws Exception {
		ClassLoader cl = forgeLoader();
		Class.forName("net.minecraftforge.fml.loading.FMLEnvironment", true, cl); // the premature reader

		PassiveSeeder.seedForgeFmlLoader(cl, tmp, Side.CLIENT);

		assertNull(dist(cl), "static final: seeding FMLLoader afterwards cannot change what FMLEnvironment cached "
				+ "— which is why the seed has to run BEFORE Mixin prepares configs, not after");
		assertEquals("CLIENT", String.valueOf(
				Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl)
						.getMethod("getDist").invoke(null)),
				"and FMLLoader itself is seeded correctly, so nothing downstream of it can notice");
	}

	/**
	 * The completion flag is a COMPLETION flag, not "we got as far as the dist".
	 *
	 * <p>Outside a real boot the tail of the seed cannot run — {@code FMLPaths}/{@code FMLConfig} need libraries
	 * the carrier does not ship — and that is exactly the partial failure the flag has to get right: arming it
	 * early would turn {@code seedAll}'s repeat into a silent no-op and delete the retry. The dist is decided
	 * either way, which is the half that cannot be retried.
	 */
	@Test
	void aPartialSeedDoesNotArmTheGuardSoTheRepeatStillRetries() throws Exception {
		ClassLoader cl = forgeLoader();
		PassiveSeeder.seedForgeFmlLoader(cl, tmp, Side.CLIENT);

		assumeTrue(!Files.isRegularFile(tmp.resolve("config").resolve("fml.toml")),
				"the full seed succeeded here, so there is no partial failure to check");
		assertFalse(PassiveSeeder.forgeIdentitySeeded(),
				"the tail of the seed failed, so the next call must retry it — arming the guard here would make "
						+ "seedAll's repeat a no-op and lose FMLPaths and FMLConfig for the whole run");
		assertEquals("CLIENT", String.valueOf(dist(cl)),
				"and the half that CANNOT be retried — the FMLEnvironment one-shot — was still decided");
	}

	/** Armed, the guard short-circuits: nothing re-enters {@code FMLConfig.load}, which reopens the config file. */
	@Test
	void anArmedGuardShortCircuits() throws Exception {
		forceArmed();
		ClassLoader cl = forgeLoader();

		PassiveSeeder.seedForgeFmlLoader(cl, tmp, Side.CLIENT);

		java.lang.reflect.Field dist = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl)
				.getDeclaredField("dist");
		dist.setAccessible(true);
		assertNull(dist.get(null), "the guard must short-circuit before touching anything — a second "
				+ "FMLConfig.load() builds a fresh CommentedFileConfig over the live one without closing it");
	}

	private static void forceArmed() throws Exception {
		var f = PassiveSeeder.class.getDeclaredField("forgeIdentitySeeded");
		f.setAccessible(true);
		f.setBoolean(null, true);
	}

	private Object dist(ClassLoader cl) throws Exception {
		return environment(cl).getField("dist").get(null);
	}

	private Class<?> environment(ClassLoader cl) throws Exception {
		return Class.forName("net.minecraftforge.fml.loading.FMLEnvironment", true, cl);
	}

	/**
	 * A fresh loader over the staged MinecraftForge carrier. Fresh per test on purpose: the whole subject is a
	 * class initializer, so every case needs a process-like blank slate. The carrier links against slf4j and
	 * {@code com.mojang.logging} without shipping them; empty stand-ins satisfy the static initializers.
	 */
	private ClassLoader forgeLoader() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME),
				"staged forge-runtime.jar absent — skipping the real-bytecode FMLEnvironment check");
		Path stubs = Files.createDirectories(tmp.resolve("stubs"));
		writeClass(stubs, "org/slf4j/Logger", emptyInterface("org/slf4j/Logger"));
		writeClass(stubs, "org/slf4j/Marker", emptyInterface("org/slf4j/Marker"));
		writeClass(stubs, "org/slf4j/LoggerFactory", loggerFactory());
		writeClass(stubs, "com/mojang/logging/LogUtils", logUtils());
		// FMLLoader.<clinit> builds a Gson, which the carrier links against but does not ship — the same shape as
		// the logging types above, except gson is far too large to stand in for. Taken from the Minecraft library
		// tree the staged artifacts came from, and skipped when that tree is not here.
		Path gson = newestGson();
		assumeTrue(gson != null, "gson absent from the Minecraft library tree — skipping");
		ClassLoader cl = new URLClassLoader(
				new URL[] {stubs.toUri().toURL(), FORGE_RUNTIME.toUri().toURL(), gson.toUri().toURL()},
				ClassLoader.getPlatformClassLoader());
		assumeTrue(hasFmlEnvironment(cl), "this carrier has no MinecraftForge FMLEnvironment");
		return cl;
	}

	/** The same library tree {@code build.gradle} resolves brigadier from, with the same overrides. */
	private static Path newestGson() throws IOException {
		String configured = System.getProperty("forbric.mcLibraries");
		String env = System.getenv("MC_DIR");
		Path root = Path.of(configured != null ? configured
				: env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path gsonDir = root.resolve("com/google/code/gson/gson");
		if (!Files.isDirectory(gsonDir)) return null;
		try (var found = Files.walk(gsonDir)) {
			return found.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().reduce((a, b) -> b)
					.orElse(null);
		}
	}

	private static boolean hasFmlEnvironment(ClassLoader cl) {
		try {
			Class.forName("net.minecraftforge.fml.loading.FMLEnvironment", false, cl);
			return true;
		} catch (ClassNotFoundException absent) {
			return false;
		}
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

	private static byte[] logUtils() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/mojang/logging/LogUtils", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FATAL_MARKER", "Lorg/slf4j/Marker;", null, null)
				.visitEnd();
		nullLogger(cw, "getLogger", "()Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

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

	/** Not a stub: proves the class the seeder names exists under the name {@code ForeignType} carries for it. */
	@Test
	void theForeignTypeRowNamesARealClass() throws Exception {
		ClassLoader cl = forgeLoader();
		assertTrue(hasFmlEnvironment(cl));
		assertEquals("net.minecraftforge.fml.loading.FMLEnvironment",
				net.forbric.api.ForeignType.FML_ENVIRONMENT.binary(net.forbric.api.Ecosystem.FORGE));
	}
}
