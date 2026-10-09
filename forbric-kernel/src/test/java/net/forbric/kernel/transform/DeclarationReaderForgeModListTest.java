/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.ForgeLoadingList;
import net.forbric.api.ModPresence;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;

/**
 * The MinecraftForge twin: a MinecraftForge library reading {@code [modproperties]} out of its static {@code ModList}
 * meets the declaring Fabric mod, through MinecraftForge's own bus-less {@code LowCodeModContainer}.
 *
 * <p>No MinecraftForge mod among the jars measured reads properties this way; the library here ({@code emberlib}) is
 * made up, and the family is covered so that the next one does not need a second fix.
 */
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("ForgeLoadingList")
@ExecutesInjector(DeclarationReaderModListInjector.class)
class DeclarationReaderForgeModListTest {
	private static final String HOLDER = "net.minecraftforge.fml.loading.LoadingModListImpl$1LazyInit";

	@TempDir
	Path work;

	@BeforeEach
	@AfterEach
	void reset() throws Exception {
		ModPresence.publishFabric(List.of());
		CrossEcosystemDeclarations.resetForTests();
		Method reset = ForgeLoadingList.class.getDeclaredMethod("reset");
		reset.setAccessible(true);
		reset.invoke(null);
	}

	private static final Map<String, String> LIBRARY = Map.of(
			"fixture.ember.EmberAddonIndex", """
					package fixture.ember;
					import java.util.*;
					import net.minecraftforge.fml.ModContainer;
					import net.minecraftforge.fml.ModList;
					import net.minecraftforge.forgespi.language.IModInfo;
					public final class EmberAddonIndex {
						public static List<String> addons() {
							List<String> out = new ArrayList<>();
							for (IModInfo info : ModList.getMods()) {
								Object addon = info.getModProperties().get("emberlib:addon");
								if (addon instanceof String name) out.add(info.getModId() + "=" + name);
							}
							return out;
						}
						public static String nameOf(String id) {
							return ModList.getModContainerById(id).map(c -> c.getModInfo().getDisplayName()).orElse("<none>");
						}
						public static List<String> loaded() {
							List<String> out = new ArrayList<>();
							for (ModContainer container : ModList.getLoadedMods()) out.add(container.getModId());
							return out;
						}
					}
					""",
			"fixture.ember.EmberModCounter", """
					package fixture.ember;
					import net.minecraftforge.fml.ModList;
					public final class EmberModCounter {
						public static int count() {
							return ModList.getMods().size();
						}
					}
					""");

	@Test
	void aMinecraftForgeReaderMeetsTheDeclaringFabricMod() throws Throwable {
		Path forge = TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(forge), "the staged MinecraftForge carrier is absent");
		Map<String, byte[]> compiled = InjectorExecution.compile(work, LIBRARY, List.of(forge));
		DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();
		Map<String, byte[]> out = new TreeMap<>();
		for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
			out.put(entry.getKey(), InjectorExecution.transform(injector, entry.getKey().replace('/', '.'),
					entry.getValue(), EnvType.SERVER));
		}
		assertNotSame(compiled.get("fixture/ember/EmberAddonIndex"), out.get("fixture/ember/EmberAddonIndex"));
		assertSame(compiled.get("fixture/ember/EmberModCounter"), out.get("fixture/ember/EmberModCounter"));

		try (URLClassLoader game = forgeGameSide(forge)) {
			// No MinecraftForge mod at all: the list the kernel publishes for such an instance.
			ForgeLoadingList.publish(List.of(), List.of());
			Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", true, game);
			Method setLoaded = modList.getDeclaredMethod("setLoadedMods", List.class);
			setLoaded.setAccessible(true);
			setLoaded.invoke(null, List.of());
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"cinder-tools","version":"3.1.0","name":"Cinder Tools",
					 "entrypoints":{"emberlib:addon":["fixture.cinder.Addon"]}}
					""", """
					{"schemaVersion":1,"id":"quiet_fabric","version":"1.0.0"}
					""");

			ClassLoader library = InjectorExecution.load(out, game);
			assertEquals("", InjectorExecution.verify(out.get("fixture/ember/EmberAddonIndex"), library));
			Class<?> index = library.loadClass("fixture.ember.EmberAddonIndex");
			assertEquals(List.of("cinder-tools=fixture.cinder.Addon"), InjectorExecution.invokeStatic(index, "addons"));
			assertEquals("Cinder Tools", InjectorExecution.invokeStatic(index, "nameOf", "cinder_tools"));
			assertEquals("<none>", InjectorExecution.invokeStatic(index, "nameOf", "quiet_fabric"));
			assertEquals(List.of("cinder-tools"), InjectorExecution.invokeStatic(index, "loaded"));
			assertEquals(0, InjectorExecution.invokeStatic(library.loadClass("fixture.ember.EmberModCounter"), "count"),
					"a class that never reads a declaration keeps MinecraftForge's own (empty) list");
		}
	}

	/**
	 * The game side over both carriers and the merged base, with MinecraftForge's lazy loading-list holder rewritten
	 * the way the kernel's chain rewrites it, so its {@code ModList} initialises from the published list.
	 */
	private static URLClassLoader forgeGameSide(Path forge) throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path run = TestFixtures.stagedRoot();
		Path neo = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(neo) && Files.isRegularFile(merged),
				"the staged artifacts are absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game side is not compiled");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forge.toUri().toURL(), neo.toUri().toURL(),
				merged.toUri().toURL()));
		for (String library : List.of("com/mojang/logging", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				"com/google/guava/guava", "it/unimi/dsi/fastutil", "org/apache/commons/commons-lang3")) {
			Path jar = newestUnder(library);
			TestFixtures.require(Fixture.MC_LIBRARIES, jar != null, "Minecraft's " + library + " library is absent");
			urls.add(jar.toUri().toURL());
		}
		ForgeLoadingListHolderInjector holder = new ForgeLoadingListHolderInjector();
		return new URLClassLoader(urls.toArray(URL[]::new), DeclarationReaderForgeModListTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!HOLDER.equals(name)) return super.findClass(name);
				try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (in == null) throw new ClassNotFoundException(name);
					byte[] bytes = InjectorExecution.transform(holder, name, in.readAllBytes(), EnvType.SERVER);
					return defineClass(name, bytes, 0, bytes.length);
				} catch (IOException unreadable) {
					throw new ClassNotFoundException(name, unreadable);
				}
			}
		};
	}

	private static Path newestUnder(String pattern) throws IOException {
		Path under = TestFixtures.minecraftDir().resolve("libraries").resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
