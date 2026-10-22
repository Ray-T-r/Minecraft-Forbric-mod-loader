/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.InjectorExecution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * NeoForge's {@code GraphicsBootstrapper} services run as genuine FML runs them, chosen by the SPI and never by a mod.
 *
 * <p>The synthetic cases are mods with unrelated names, each a jar with a {@code META-INF/services} file naming the SPI,
 * run against a stand-in of the SPI with the carrier's binary name and shape. The staged case reads the SPI out of the
 * real NeoForge carrier, and the config case runs the carrier's own {@code FMLConfig}.
 */
@ResourceLock("system-properties")
class NeoGraphicsBootstrappersTest {
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar")
			.toAbsolutePath().normalize();

	@TempDir
	Path work;

	@AfterEach
	void reset() {
		System.clearProperty(NeoGraphicsBootstrappers.PROPERTY);
	}

	private static final Map<String, String> SOURCES = new LinkedHashMap<>(Map.of(
			"net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper", """
					package net.neoforged.neoforgespi.earlywindow;
					/** The carrier's SPI, by its own name and shape. */
					public interface GraphicsBootstrapper {
						String name();
						void bootstrap(String[] arguments);
					}
					""",
			"org.emberforge.ledger.Ledger", """
					package org.emberforge.ledger;
					/** What each provider did, in order: a shared library the test reads back. */
					public final class Ledger {
						public static final java.util.List<String> CALLS = new java.util.ArrayList<>();
					}
					""",
			"org.emberforge.kiln.KilnProbe", """
					package org.emberforge.kiln;
					public class KilnProbe implements net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper {
						public String name() { return "kiln"; }
						public void bootstrap(String[] arguments) {
							org.emberforge.ledger.Ledger.CALLS.add("kiln " + String.join(",", arguments)
									+ " ctx=" + (Thread.currentThread().getContextClassLoader() == getClass().getClassLoader()));
						}
					}
					""",
			"org.emberforge.kiln.Cracked", """
					package org.emberforge.kiln;
					public class Cracked implements net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper {
						public String name() { return "cracked"; }
						public void bootstrap(String[] arguments) {
							org.emberforge.ledger.Ledger.CALLS.add("cracked");
							throw new IllegalStateException("cracked kiln");
						}
					}
					""",
			"org.emberforge.kiln.NotOne", """
					package org.emberforge.kiln;
					public class NotOne {
					}
					""",
			"org.quarrymen.forge.QuarryProbe", """
					package org.quarrymen.forge;
					public class QuarryProbe implements net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper {
						public String name() { return "quarry"; }
						public void bootstrap(String[] arguments) { org.emberforge.ledger.Ledger.CALLS.add("quarry"); }
					}
					""",
			"org.loamworks.late.LateProbe", """
					package org.loamworks.late;
					public class LateProbe implements net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper {
						public String name() { return "late"; }
						public void bootstrap(String[] arguments) { org.emberforge.ledger.Ledger.CALLS.add("late"); }
					}
					"""));

	private record Mods(URLClassLoader loader, Path kiln, Path ash, Path quarry, Path classes) {
	}

	private Mods mods() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work.resolve("src"), new HashMap<>(SOURCES));
		Path shared = work.resolve("shared");
		for (String name : List.of("net/neoforged/neoforgespi/earlywindow/GraphicsBootstrapper", "org/emberforge/ledger/Ledger")) {
			Path out = shared.resolve(name + ".class");
			Files.createDirectories(out.getParent());
			Files.write(out, classes.get(name));
		}
		// A NeoForge mod: one provider, with a comment and a blank line in its services file, as ServiceLoader allows.
		Path kiln = jar("kiln-2.0.jar", Map.of("org/emberforge/kiln/KilnProbe", classes.get("org/emberforge/kiln/KilnProbe"),
				"org/emberforge/kiln/Cracked", classes.get("org/emberforge/kiln/Cracked"),
				"org/emberforge/kiln/NotOne", classes.get("org/emberforge/kiln/NotOne")),
				"# the kiln's early probe\norg.emberforge.kiln.KilnProbe  # trailing comment\n\n");
		// A second NeoForge mod listing the same provider again (it runs once), one that throws, and one that is no provider.
		Path ash = jar("ash-1.0.jar", Map.of(), "org.emberforge.kiln.KilnProbe\norg.emberforge.kiln.Cracked\norg.emberforge.kiln.NotOne\n");
		// A MinecraftForge mod listing NeoForge's SPI: not NeoForge's to run.
		Path quarry = jar("quarry-1.0.jar", Map.of("org/quarrymen/forge/QuarryProbe", classes.get("org/quarrymen/forge/QuarryProbe")),
				"org.quarrymen.forge.QuarryProbe\n");
		// A provider only a nested jar would list: never handed in, so never run (FML scans mods/ to depth one).
		Path nested = jar("loam-nested.jar", Map.of("org/loamworks/late/LateProbe", classes.get("org/loamworks/late/LateProbe")),
				"org.loamworks.late.LateProbe\n");
		URLClassLoader loader = new URLClassLoader(new URL[] {shared.toUri().toURL(), kiln.toUri().toURL(), ash.toUri().toURL(),
				quarry.toUri().toURL(), nested.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
		return new Mods(loader, kiln, ash, quarry, shared);
	}

	private Path jar(String name, Map<String, byte[]> classes, String services) throws IOException {
		Path jar = work.resolve("mods").resolve(name);
		Files.createDirectories(jar.getParent());
		try (OutputStream raw = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(raw)) {
			out.putNextEntry(new JarEntry(NeoGraphicsBootstrappers.SERVICES));
			out.write(services.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			for (Map.Entry<String, byte[]> e : classes.entrySet()) {
				out.putNextEntry(new JarEntry(e.getKey() + ".class"));
				out.write(e.getValue());
				out.closeEntry();
			}
		}
		return jar;
	}

	@SuppressWarnings("unchecked")
	private static List<String> ledger(ClassLoader loader) throws Exception {
		return (List<String>) loader.loadClass("org.emberforge.ledger.Ledger").getField("CALLS").get(null);
	}

	@Test
	void theServicesFileIsReadAsServiceLoaderReadsIt() throws Exception {
		Mods f = mods();
		List<NeoGraphicsBootstrappers.Declared> declared = NeoGraphicsBootstrappers.declared(List.of(f.kiln(), f.ash()));
		assertEquals(List.of("org.emberforge.kiln.KilnProbe", "org.emberforge.kiln.Cracked", "org.emberforge.kiln.NotOne"),
				declared.stream().map(NeoGraphicsBootstrappers.Declared::provider).toList(),
				"comments and blank lines skipped, a class listed twice once, in jar order then file order");
		assertEquals(f.kiln(), declared.get(0).jar(), "the first jar to list it is the one it is attributed to");
	}

	@Test
	@ExpectCaught("GraphicsBootstrapper org.emberforge.kiln.")
	void everyNeoForgeProviderRunsOnceWithTheGameArgumentsAndOnlyThose() throws Exception {
		Mods f = mods();
		Thread thread = Thread.currentThread();
		ClassLoader outer = thread.getContextClassLoader();
		thread.setContextClassLoader(f.loader());
		List<NeoGraphicsBootstrappers.Outcome> outcomes;
		try {
			outcomes = NeoGraphicsBootstrappers.run(f.loader(), List.of(f.kiln(), f.ash(), f.quarry()),
					jar -> !jar.equals(f.quarry()), new String[] {"--width", "854"});
		} finally {
			thread.setContextClassLoader(outer);
		}
		assertEquals(List.of("kiln --width,854 ctx=true", "cracked"), ledger(f.loader()),
				"the kiln's probe ran once, with the game's arguments and the game loader as context loader; the cracked one "
						+ "ran and threw; the MinecraftForge mod's and the nested jar's never ran");
		assertEquals(List.of("kiln", "cracked", "org.emberforge.kiln.NotOne"),
				outcomes.stream().map(NeoGraphicsBootstrappers.Outcome::name).toList());
		assertTrue(outcomes.get(0).ran());
		assertInstanceOf(IllegalStateException.class, outcomes.get(1).failure(), "the provider's own exception, unwrapped");
		assertInstanceOf(ClassCastException.class, outcomes.get(2).failure(), "listed but no GraphicsBootstrapper");
	}

	@Test
	void switchedOffNothingRuns() throws Exception {
		Mods f = mods();
		System.setProperty(NeoGraphicsBootstrappers.PROPERTY, "off");
		assertEquals(List.of(), NeoGraphicsBootstrappers.run(f.loader(), List.of(f.kiln()), jar -> true, new String[0]));
		assertEquals(List.of(), ledger(f.loader()));
	}

	@Test
	void noDeclarationNoWork() throws Exception {
		Mods f = mods();
		Path plain = jar("plain.jar", Map.of(), "");
		assertEquals(List.of(), NeoGraphicsBootstrappers.run(f.loader(), List.of(plain), jar -> true, new String[0]));
	}

	/** The SPI the kernel reads is the one the NeoForge carrier declares, with the two methods genuine FML calls. */
	@Test
	void theCarrierDeclaresTheSpiByThatNameAndShape() throws Exception {
		byte[] spi = TestFixtures.requireEntry(Fixture.STAGED, NEO_RUNTIME, NeoGraphicsBootstrappers.SPI.replace('.', '/') + ".class");
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(spi).accept(node, 0);
		assertTrue((node.access & Opcodes.ACC_INTERFACE) != 0);
		Set<String> methods = new java.util.HashSet<>();
		node.methods.forEach(m -> methods.add(m.name + m.desc));
		assertEquals(Set.of("name()Ljava/lang/String;", "bootstrap([Ljava/lang/String;)V"), methods);
		byte[] handler = TestFixtures.requireEntry(Fixture.STAGED, NEO_RUNTIME, "net/neoforged/fml/loading/ImmediateWindowHandler.class");
		assertTrue(new String(handler, StandardCharsets.ISO_8859_1).contains("Running graphics bootstrap plugin {}"),
				"genuine FML runs them from ImmediateWindowHandler.load, the line the kernel's log mirrors");
	}

	/**
	 * The carrier's own FMLConfig: unloaded it promises an early window (the enum's default); seeded it says there is
	 * none, and every other entry answers exactly as before. Nothing is written.
	 */
	@Test
	void neoForgesConfigStopsPromisingAnEarlyWindowAndNothingElseChanges() throws Exception {
		ClassLoader cl = configLoader();
		Class<?> config = Class.forName(PassiveSeeder.NEO_FML_CONFIG, true, cl);
		Class<?> value = Class.forName(PassiveSeeder.NEO_FML_CONFIG + "$ConfigValue", true, cl);
		Object control = value.getField(PassiveSeeder.NEO_EARLY_WINDOW_CONTROL).get(null);
		java.lang.reflect.Method bool = config.getMethod("getBoolConfigValue", value);
		assertEquals(Boolean.TRUE, bool.invoke(null, control), "premise: with no config loaded it promises a window");
		Map<String, Object> before = new LinkedHashMap<>();
		for (Object v : value.getEnumConstants()) before.put(((Enum<?>) v).name(), answer(config, value, v));

		PassiveSeeder.seedNeoForgeNoEarlyWindow(cl);

		assertEquals(Boolean.FALSE, bool.invoke(null, control));
		for (Object v : value.getEnumConstants()) {
			if (v == control) continue;
			assertEquals(before.get(((Enum<?>) v).name()), answer(config, value, v), ((Enum<?>) v).name() + " unchanged");
		}
		assertEquals(List.of(), listFiles(work), "in memory only: no fml.toml written");

		PassiveSeeder.seedNeoForgeNoEarlyWindow(cl);
		assertEquals(Boolean.FALSE, bool.invoke(null, control), "a second seed leaves a loaded config alone");
	}

	/** What FMLConfig's typed getters return for {@code v}: the value's own read of the config FMLConfig holds. */
	private static Object answer(Class<?> config, Class<?> value, Object v) throws Exception {
		java.lang.reflect.Field instance = config.getDeclaredField("INSTANCE");
		instance.setAccessible(true);
		java.lang.reflect.Field data = config.getDeclaredField("configData");
		data.setAccessible(true);
		java.lang.reflect.Method read = value.getDeclaredMethod("getConfigValue", data.getType());
		read.setAccessible(true);
		return read.invoke(v, data.get(instance.get(null)));
	}

	private static List<Path> listFiles(Path dir) throws IOException {
		try (var files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".toml")).toList();
		}
	}

	private ClassLoader configLoader() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RUNTIME), "staged neoforge-runtime.jar absent");
		TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25,
				"the carrier's classes are class-file 69, which only Java 25 links");
		Path nightConfig = newestLibrary("com/electronwill/night-config/core");
		TestFixtures.require(Fixture.MC_LIBRARIES, nightConfig != null, "night-config core absent from the Minecraft library tree");
		Path stubs = Files.createDirectories(work.resolve("stubs"));
		writeClass(stubs, "org/slf4j/Logger", emptyInterface("org/slf4j/Logger"));
		writeClass(stubs, "org/slf4j/Marker", emptyInterface("org/slf4j/Marker"));
		writeClass(stubs, "com/mojang/logging/LogUtils", logUtils());
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), NEO_RUNTIME.toUri().toURL(), nightConfig.toUri().toURL()},
				ClassLoader.getPlatformClassLoader());
	}

	private static Path newestLibrary(String relative) throws IOException {
		String configured = System.getProperty("forbric.mcLibraries");
		Path root = configured != null ? Path.of(configured) : TestFixtures.minecraftDir().resolve("libraries");
		Path dir = root.resolve(relative);
		if (!Files.isDirectory(dir)) return null;
		try (var found = Files.walk(dir)) {
			return found.filter(f -> f.getFileName().toString().endsWith(".jar"))
					.filter(f -> !f.getFileName().toString().contains("sources")).sorted().reduce((a, b) -> b).orElse(null);
		}
	}

	private static void writeClass(Path root, String internalName, byte[] bytes) throws IOException {
		Path out = root.resolve(internalName + ".class");
		Files.createDirectories(out.getParent());
		Files.write(out, bytes);
	}

	private static byte[] emptyInterface(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, internalName, null,
				"java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code LogUtils.getLogger()} answering null: FMLConfig only logs at trace from load(), which nothing calls here. */
	private static byte[] logUtils() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/mojang/logging/LogUtils", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getLogger", "()Lorg/slf4j/Logger;", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
