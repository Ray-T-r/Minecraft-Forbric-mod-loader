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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * MinecraftForge's {@code ImmediateWindowHandler} must never be seen empty by the game.
 *
 * <p>Genuine FML calls {@code load} and {@code acceptGameLayer} before the game starts; the kernel runs neither, so
 * the handler's {@code provider} stayed null and a merged {@code Window.<init>} that keeps MinecraftForge's
 * {@code if (!positionWindow(...))} guard died on an NPE inside {@code Minecraft.<init>}. The staged-carrier cases run
 * Forge's real handler in a loader of its own and ask it that exact question; the synthetic cases pin the bytecode
 * replay that binds the provider's fallbacks, so they run on a checkout with no carrier at all.
 */
class PassiveSeederForgeImmediateWindowTest {
	private static final Path FORGE_RUNTIME = TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar")
			.toAbsolutePath().normalize();
	private static final String HANDLER = PassiveSeeder.FORGE_IMMEDIATE_WINDOW_HANDLER;

	@TempDir
	Path tmp;

	/** The crash, on Forge's own bytecode: an unseeded handler cannot answer the question Window.<init> asks. */
	@Test
	void anUnseededHandlerThrowsOnTheCallWindowInitMakes() throws Exception {
		ClassLoader cl = forgeLoader();
		InvocationTargetException crash = assertThrows(InvocationTargetException.class,
				() -> positionWindow(cl, new ArrayList<>()));
		assertInstanceOf(NullPointerException.class, crash.getCause(),
				"the defect: provider is null, and positionWindow dereferences it");
	}

	/**
	 * Forge's load alone is not enough: its DummyProvider answers through fallbacks that only acceptGameLayer binds,
	 * and an unbound one throws "How did you get here?" on the same call.
	 */
	@Test
	void loadWithoutTheFallbacksBoundStillThrows() throws Exception {
		ClassLoader cl = forgeLoader();
		Class.forName(HANDLER, false, cl).getMethod("load", String.class, String[].class)
				.invoke(null, PassiveSeeder.NO_EARLY_WINDOW_TARGET, (Object) new String[0]);
		InvocationTargetException crash = assertThrows(InvocationTargetException.class,
				() -> positionWindow(cl, new ArrayList<>()));
		assertInstanceOf(IllegalStateException.class, crash.getCause());
	}

	@Test
	void theSeededHandlerAnswersFalseAndLeavesTheWindowToVanilla() throws Exception {
		ClassLoader cl = forgeLoader();
		PassiveSeeder.seedForgeImmediateWindow(cl);

		List<Integer> written = new ArrayList<>();
		assertEquals(Boolean.FALSE, positionWindow(cl, written),
				"Forge's no-window answer: false, so the vanilla positioning the guard wraps runs");
		assertTrue(written.isEmpty(), "and it positioned nothing itself: " + written);

		Class<?> handler = Class.forName(HANDLER, false, cl);
		IntConsumer record = written::add;
		handler.getMethod("updateFBSize", IntConsumer.class, IntConsumer.class).invoke(null, record, record);
		handler.getMethod("renderTick").invoke(null);
		assertTrue(written.isEmpty(), "the framebuffer hook and the render tick are no-ops without an early window");
		assertEquals("3.2", handler.getMethod("getGLVersion").invoke(null),
				"the version fallback answers too (no window yet, so Forge's default)");
		assertEquals("dummyprovider", providerName(cl), "Forge's own provider, not a stand-in");
	}

	/** Every fallback field the provider declares is bound, each to a static method of the class its bytecode names. */
	@Test
	void everyFallbackFieldOfForgesProviderIsBound() throws Exception {
		ClassLoader cl = forgeLoader();
		PassiveSeeder.seedForgeImmediateWindow(cl);
		Class<?> provider = provider(cl).getClass();
		int fields = 0;
		for (Field field : provider.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Method.class) continue;
			fields++;
			field.setAccessible(true);
			Method bound = (Method) field.get(null);
			assertNotNull(bound, field.getName() + " left unbound");
			assertTrue(Modifier.isStatic(bound.getModifiers()), field.getName() + " bound to an instance method");
		}
		assertTrue(fields > 0, "this carrier's provider has no fallback fields — the subject of this test is gone");
	}

	@Test
	void aProviderAlreadyThereIsLeftAlone() throws Exception {
		ClassLoader cl = forgeLoader();
		PassiveSeeder.seedForgeImmediateWindow(cl);
		Object first = provider(cl);
		PassiveSeeder.seedForgeImmediateWindow(cl);
		assertSame(first, provider(cl));
	}

	/** The pairing comes from the provider's bytecode, not from field names the kernel knows. */
	@Test
	void theBindingFollowsTheProvidersOwnFieldNames() throws Exception {
		Path classes = Files.createDirectories(tmp.resolve("synthetic"));
		writeClass(classes, "p/Fallback", fallbackClass());
		writeClass(classes, "p/Provider", providerClass("FIRST", "SECOND"));
		try (URLClassLoader cl = new URLClassLoader(new URL[] {classes.toUri().toURL()},
				PassiveSeederForgeImmediateWindowTest.class.getClassLoader())) {
			Class<?> provider = Class.forName("p.Provider", false, cl);
			assertEquals(2, PassiveSeeder.bindWindowFallbacks(cl, provider));
			assertEquals("alpha", staticMethod(provider, "FIRST").getName());
			assertEquals("beta", staticMethod(provider, "SECOND").getName());
			assertNull(staticMethod(provider, "UNPAIRED"),
					"a Method field stored without the name-lookup shape is not the binding's to fill");
			assertEquals(0, PassiveSeeder.bindWindowFallbacks(cl, provider), "bound fields are left alone");
		}
	}

	@Test
	void theReaderFindsTheFallbackClassAndOnlyTheLookupStores() {
		PassiveSeeder.WindowFallbackBinding binding =
				PassiveSeeder.readWindowFallbackBinding(providerClass("ONE", "TWO"));
		assertEquals("p.Fallback", binding.fallbackClass());
		assertEquals(Map.of("ONE", "alpha", "TWO", "beta"), binding.fieldToMethod());
	}

	/** A provider whose binding cannot be read is refused whole rather than half-bound. */
	@Test
	void aProviderWithNoReadableBindingIsRefused() throws Exception {
		Path classes = Files.createDirectories(tmp.resolve("unreadable"));
		writeClass(classes, "p/Provider", emptyProvider());
		try (URLClassLoader cl = new URLClassLoader(new URL[] {classes.toUri().toURL()},
				PassiveSeederForgeImmediateWindowTest.class.getClassLoader())) {
			Class<?> provider = Class.forName("p.Provider", false, cl);
			assertThrows(IllegalStateException.class, () -> PassiveSeeder.bindWindowFallbacks(cl, provider));
		}
	}

	// --- the staged carrier -----------------------------------------------------------------------------------

	private static Object positionWindow(ClassLoader cl, List<Integer> written) throws Exception {
		IntConsumer record = written::add;
		return Class.forName(HANDLER, false, cl).getMethod("positionWindow", Optional.class, IntConsumer.class,
				IntConsumer.class, IntConsumer.class, IntConsumer.class)
				.invoke(null, Optional.empty(), record, record, record, record);
	}

	private static Object provider(ClassLoader cl) throws Exception {
		Field field = Class.forName(HANDLER, false, cl).getDeclaredField("provider");
		field.setAccessible(true);
		return field.get(null);
	}

	private static String providerName(ClassLoader cl) throws Exception {
		Object provider = provider(cl);
		Method name = provider.getClass().getMethod("name");
		name.setAccessible(true);
		return String.valueOf(name.invoke(provider));
	}

	/**
	 * A fresh loader over the staged MinecraftForge carrier, per test: the subject is static state. The handler logs
	 * through log4j and FMLLoader's initializer builds a Gson, neither shipped by the carrier, so both come from the
	 * Minecraft library tree; slf4j and com.mojang.logging get empty stand-ins, as in the FMLEnvironment test.
	 */
	private ClassLoader forgeLoader() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(FORGE_RUNTIME),
				"staged forge-runtime.jar absent — skipping the real-bytecode ImmediateWindowHandler check");
		TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25,
				"the carrier's classes are class-file 69, which only Java 25 links");
		Path gson = newestLibrary("com/google/code/gson/gson");
		Path log4j = newestLibrary("org/apache/logging/log4j/log4j-api");
		TestFixtures.require(Fixture.MC_LIBRARIES, gson != null && log4j != null,
				"gson or log4j-api absent from the Minecraft library tree — skipping");
		Path stubs = Files.createDirectories(tmp.resolve("stubs"));
		writeClass(stubs, "org/slf4j/Logger", emptyInterface("org/slf4j/Logger"));
		writeClass(stubs, "org/slf4j/Marker", emptyInterface("org/slf4j/Marker"));
		writeClass(stubs, "org/slf4j/LoggerFactory", loggerFactory());
		writeClass(stubs, "com/mojang/logging/LogUtils", logUtils());
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), FORGE_RUNTIME.toUri().toURL(),
				gson.toUri().toURL(), log4j.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
	}

	private static Path newestLibrary(String relative) throws IOException {
		String configured = System.getProperty("forbric.mcLibraries");
		Path root = configured != null ? Path.of(configured) : TestFixtures.minecraftDir().resolve("libraries");
		Path dir = root.resolve(relative);
		if (!Files.isDirectory(dir)) return null;
		try (var found = Files.walk(dir)) {
			return found.filter(f -> f.getFileName().toString().endsWith(".jar"))
					.filter(f -> !f.getFileName().toString().contains("sources")).sorted().reduce((a, b) -> b)
					.orElse(null);
		}
	}

	private static Method staticMethod(Class<?> owner, String field) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		return (Method) f.get(null);
	}

	// --- synthetic classes ------------------------------------------------------------------------------------

	private static byte[] fallbackClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/Fallback", null, "java/lang/Object", null);
		for (String name : new String[] {"alpha", "beta"}) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Forge's shape with other names: a lambda resolving the fallback class through {@code Class.forName(Module,
	 * String)}, a binding method storing {@code map.get(name)} into each field, and one Method field stored some
	 * other way, which is not part of the binding.
	 */
	private static byte[] providerClass(String first, String second) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/Provider", null, "java/lang/Object", null);
		for (String field : new String[] {first, second, "UNPAIRED"}) {
			cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, field, "Ljava/lang/reflect/Method;", null, null)
					.visitEnd();
		}
		MethodVisitor lookup = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$find$0",
				"(Ljava/lang/Module;)Ljava/lang/Class;", null, null);
		lookup.visitCode();
		lookup.visitVarInsn(Opcodes.ALOAD, 0);
		lookup.visitLdcInsn("p.Fallback");
		lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
				"(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;", false);
		lookup.visitInsn(Opcodes.ARETURN);
		lookup.visitMaxs(0, 0);
		lookup.visitEnd();
		MethodVisitor bind = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bind", "(Ljava/util/Map;)V",
				null, null);
		bind.visitCode();
		String[][] pairs = {{"alpha", first}, {"beta", second}};
		for (String[] pair : pairs) {
			bind.visitVarInsn(Opcodes.ALOAD, 0);
			bind.visitLdcInsn(pair[0]);
			bind.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
					"(Ljava/lang/Object;)Ljava/lang/Object;", true);
			bind.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/reflect/Method");
			bind.visitFieldInsn(Opcodes.PUTSTATIC, "p/Provider", pair[1], "Ljava/lang/reflect/Method;");
		}
		bind.visitInsn(Opcodes.ACONST_NULL);
		bind.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/reflect/Method");
		bind.visitFieldInsn(Opcodes.PUTSTATIC, "p/Provider", "UNPAIRED", "Ljava/lang/reflect/Method;");
		bind.visitInsn(Opcodes.RETURN);
		bind.visitMaxs(0, 0);
		bind.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] emptyProvider() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/Provider", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "LONE", "Ljava/lang/reflect/Method;", null, null)
				.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
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
}
