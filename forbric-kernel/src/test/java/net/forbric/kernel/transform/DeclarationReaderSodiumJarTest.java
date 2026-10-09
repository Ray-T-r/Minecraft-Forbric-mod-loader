/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.ModPresence;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;

/**
 * The real Sodium NeoForge build registers a Fabric mod's options page through nothing but the general path: the Fabric
 * mod's {@code sodium:config_api_user} entrypoint, projected into its {@code [modproperties]}, met by Sodium's own walk
 * of {@code ModList} and its own {@code getModContainerById} for the page's name and version.
 *
 * <p>Sodium's {@code ConfigLoaderForge} runs unmodified except for the injector's generic edit, as do Sodium's
 * {@code ConfigManager} and everything it reaches; the Fabric mod ({@code lumen-panels}) is a made-up one. Of every
 * class in the jar, the injector edits only the two that read declarations out of {@code ModList}.
 */
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("system-properties")
@ExecutesInjector(DeclarationReaderModListInjector.class)
class DeclarationReaderSodiumJarTest {
	private static final String LOADER = "net.caffeinemc.mods.sodium.neoforge.config.ConfigLoaderForge";
	private static final String MANAGER = "net.caffeinemc.mods.sodium.client.config.ConfigManager";

	@TempDir
	Path work;

	private String savedSwitch;

	@BeforeEach
	void clearSwitch() {
		savedSwitch = System.getProperty(CrossEcosystemDeclarations.SWITCH);
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		CrossEcosystemDeclarations.resetForTests();
	}

	@AfterEach
	void reset() {
		if (savedSwitch == null) System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		else System.setProperty(CrossEcosystemDeclarations.SWITCH, savedSwitch);
		ModPresence.publishFabric(List.of());
		CrossEcosystemDeclarations.resetForTests();
	}

	@Test
	void sodiumNeoForgeRegistersTheFabricModsOptionsPage() throws Throwable {
		for (Path jar : sodiumJars()) {
			Result result = run(jar, true);
			assertEquals(List.of("lumen-panels"), result.users(), jar + ": the Fabric mod declaring the entrypoint, "
					+ "and only it");
			assertEquals("fixture.lumen.LumenSodiumPage", result.entryPointClass(), jar.toString());
			assertEquals(List.of("Lumen Panels", "1.4.0"), result.metadata(),
					jar + ": Sodium's own by-id lookup names the page");
			assertEquals(new TreeSet<>(List.of("net/caffeinemc/mods/sodium/neoforge/SodiumForgeMod",
					"net/caffeinemc/mods/sodium/neoforge/config/ConfigLoaderForge")), result.edited(),
					jar + ": only the classes that read declarations out of ModList are edited");
		}
	}

	@Test
	void switchedOffSodiumSeesOnlyItsOwnFamily() throws Throwable {
		for (Path jar : sodiumJars()) {
			System.setProperty(CrossEcosystemDeclarations.SWITCH, "off");
			assertEquals(List.of(), run(jar, false).users(), jar.toString());
		}
	}

	private record Result(List<String> users, String entryPointClass, List<String> metadata, TreeSet<String> edited) {
	}

	private Result run(Path outer, boolean resolve) throws Throwable {
		Path inner = innerJar(outer);
		Map<String, byte[]> classes = classesOf(inner);
		DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();
		TreeSet<String> edited = new TreeSet<>();
		Map<String, byte[]> transformed = new TreeMap<>();
		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			byte[] out = InjectorExecution.transform(injector, entry.getKey().replace('/', '.'), entry.getValue(),
					EnvType.CLIENT);
			if (out != entry.getValue()) edited.add(entry.getKey());
			transformed.put(entry.getKey(), out);
		}
		transformed.putAll(InjectorExecution.compile(work, Map.of("fixture.lumen.LumenSodiumPage", """
				package fixture.lumen;
				import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
				import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
				public final class LumenSodiumPage implements ConfigEntryPoint {
					@Override public void registerConfigLate(ConfigBuilder builder) { }
				}
				"""), List.of(inner)));

		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			// The list as PassiveSeeder.seedNeoForgeModList leaves it: no NeoForge mod, containers published (none).
			Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, game);
			Object list = modList.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
			Method setLoaded = modList.getDeclaredMethod("setLoadedMods", List.class);
			setLoaded.setAccessible(true);
			setLoaded.invoke(list, List.of());
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"lumen-panels","version":"1.4.0","name":"Lumen Panels",
					 "entrypoints":{"sodium:config_api_user":["fixture.lumen.LumenSodiumPage"],
					                "client":["fixture.lumen.Client"]}}
					""", """
					{"schemaVersion":1,"id":"quiet_fabric","version":"1.0.0","custom":{"modmenu":{"badges":[]}}}
					""");
			ClassLoader sodium = InjectorExecution.load(transformed, game);
			for (String name : edited) assertEquals("", InjectorExecution.verify(transformed.get(name), sodium), name);

			InjectorExecution.invokeStatic(sodium.loadClass(LOADER), "collectConfigEntryPoints");

			Class<?> manager = sodium.loadClass(MANAGER);
			List<String> users = new ArrayList<>();
			String entryPointClass = null;
			for (Object user : (Collection<?>) staticField(manager, "configUsers")) {
				users.add((String) accessor(user, "modId"));
				if (resolve) entryPointClass = ((Supplier<?>) accessor(user, "configEntrypoint")).get().getClass().getName();
			}
			List<String> metadata = List.of();
			if (resolve) {
				@SuppressWarnings("unchecked")
				Function<String, Object> info = (Function<String, Object>) staticField(manager, "modInfoFunction");
				Object page = info.apply("lumen-panels");
				metadata = List.of((String) accessor(page, "modName"), (String) accessor(page, "modVersion"));
			}
			return new Result(users, entryPointClass, metadata, edited);
		}
	}

	private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static Object accessor(Object record, String name) throws ReflectiveOperationException {
		Method method = record.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(record);
	}

	/** Every sodium-neoforge build in the third-party fixtures. */
	private static List<Path> sodiumJars() throws IOException {
		List<Path> jars = new ArrayList<>();
		for (Path root : List.of(Path.of("build/compat-inputs"), Path.of("run/client-merged-pack/mods"))) {
			if (!Files.isDirectory(root)) continue;
			// The real path: a worktree links these to the main checkout's, and a walk does not enter a link.
			try (Stream<Path> walk = Files.walk(root.toRealPath(), 3)) {
				walk.filter(p -> p.getFileName().toString().startsWith("sodium-neoforge-")
						&& p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
			}
		}
		TestFixtures.require(Fixture.THIRD_PARTY, !jars.isEmpty(),
				"no sodium-neoforge jar under build/compat-inputs/*/mods or run/client-merged-pack/mods");
		return jars;
	}

	/** The mod jar sodium-neoforge ships its classes in (jar-in-jar), written out so javac can compile against it. */
	private Path innerJar(Path outer) throws IOException {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jarjar/")
					&& e.getName().contains("sodium-neoforge") && e.getName().endsWith(".jar")).findFirst().orElse(null);
			if (nested == null) return outer;
			Path inner = Files.createTempFile(work, "sodium-inner-", ".jar");
			try (InputStream in = zip.getInputStream(nested)) {
				Files.write(inner, in.readAllBytes());
			}
			return inner;
		}
	}

	private static Map<String, byte[]> classesOf(Path jar) throws IOException {
		Map<String, byte[]> classes = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(jar)))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				String name = entry.getName();
				if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
				classes.put(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
			}
		}
		assertTrue(classes.containsKey(LOADER.replace('.', '/')), jar + " has no " + LOADER);
		return classes;
	}
}
