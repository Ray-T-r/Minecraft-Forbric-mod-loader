/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;

/**
 * A NeoForge library that finds its users by reading {@code [modproperties]} out of {@code ModList} meets the Fabric mods
 * that declare the same key — whatever the library is called and however it is written.
 *
 * <p>The library here is a made-up one ({@code glowkit}), written five different ways: a stream with lambdas, a
 * {@code forEachModContainer} callback reading a table, method references only ({@code ModList::getMods},
 * {@code IModInfo::getModProperties}), a container stream with the property read in a private helper, and a by-id lookup
 * through {@code Optional.map}. Two look-alikes that are NOT the problem are left alone: a class that enumerates
 * {@code ModList} but never reads a declaration (a mod counter — it is asking what the family loaded), and one that
 * reads declarations but never asks {@code ModList}.
 *
 * <p>Runs the transformed classes against the real staged NeoForge {@code ModList} and the real game-side hooks.
 */
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("system-properties")
@ExecutesInjector(DeclarationReaderModListInjector.class)
class DeclarationReaderModListInjectorTest {
	private static final String PLUGIN_KEY = "glowkit:plugin";

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
		ModPresence.publishForgeFamily(List.of());
		CrossEcosystemDeclarations.resetForTests();
	}

	static final Map<String, String> LIBRARY = Map.of(
			"fixture.glow.GlowPluginScanner", """
					package fixture.glow;
					import java.util.*;
					import java.util.stream.*;
					import net.neoforged.fml.ModContainer;
					import net.neoforged.fml.ModList;
					import net.neoforged.neoforgespi.language.IModInfo;
					public final class GlowPluginScanner {
						public static List<String> plugins() {
							return ModList.get().getMods().stream()
									.filter(info -> info.getModProperties().get("glowkit:plugin") instanceof String)
									.map(info -> info.getModId() + "=" + info.getModProperties().get("glowkit:plugin"))
									.collect(Collectors.toList());
						}
						public static String nameOf(String id) {
							return ModList.get().getModContainerById(id).map(ModContainer::getModInfo)
									.map(IModInfo::getDisplayName).orElse("<none>");
						}
					}
					""",
			"fixture.glow.GlowThemeReader", """
					package fixture.glow;
					import java.util.*;
					import com.electronwill.nightconfig.core.CommentedConfig;
					import net.neoforged.fml.ModList;
					public final class GlowThemeReader {
						public static Map<String, String> accents() {
							Map<String, String> out = new TreeMap<>();
							ModList list = ModList.get();
							list.forEachModContainer((id, container) -> {
								Object theme = container.getModInfo().getModProperties().get("glowkit:theme");
								if (theme instanceof CommentedConfig table) out.put(id, table.get("accent"));
							});
							return out;
						}
					}
					""",
			"fixture.glow.GlowRefReader", """
					package fixture.glow;
					import java.util.*;
					import java.util.function.Function;
					import net.neoforged.fml.ModList;
					import net.neoforged.neoforgespi.language.IModInfo;
					public final class GlowRefReader {
						public static List<String> ids() {
							List<IModInfo> mods = Optional.ofNullable(ModList.get()).map(ModList::getMods).orElse(List.of());
							Function<IModInfo, Map<String, Object>> declared = IModInfo::getModProperties;
							List<String> out = new ArrayList<>();
							for (IModInfo info : mods) if (declared.apply(info).containsKey("glowkit:plugin")) out.add(info.getModId());
							return out;
						}
					}
					""",
			"fixture.glow.GlowContainerReader", """
					package fixture.glow;
					import java.util.*;
					import java.util.stream.*;
					import net.neoforged.fml.ModContainer;
					import net.neoforged.fml.ModList;
					import net.neoforged.neoforgespi.language.IModInfo;
					public final class GlowContainerReader {
						public static List<String> declared() {
							return ModList.get().applyForEachModContainer(ModContainer::getModInfo)
									.filter(GlowContainerReader::declares).map(IModInfo::getModId).collect(Collectors.toList());
						}
						private static boolean declares(IModInfo info) {
							return info.getModProperties().containsKey("glowkit:plugin");
						}
						public static int sortedCount() {
							return ModList.get().getSortedMods().size();
						}
						public static boolean hasFile(String id) {
							return ModList.get().getModFileById(id) != null;
						}
						public static List<String> inOrder() {
							List<String> out = new ArrayList<>();
							ModList.get().forEachModInOrder(container -> out.add(container.getModId()));
							return out;
						}
					}
					""",
			"fixture.glow.GlowModCounter", """
					package fixture.glow;
					import net.neoforged.fml.ModList;
					public final class GlowModCounter {
						public static int count() {
							return ModList.get().getMods().size();
						}
						public static boolean has(String id) {
							return ModList.get().getModContainerById(id).isPresent();
						}
					}
					""",
			"fixture.glow.GlowHookTables", """
					package fixture.glow;
					import java.util.*;
					import net.neoforged.fml.ModList;
					import net.neoforged.neoforgespi.language.IModInfo;
					public final class GlowHookTables {
						public static List<String> tables() {
							List<String> out = new ArrayList<>();
							for (IModInfo info : ModList.get().getMods()) {
								Object hooks = info.getModProperties().get("glowkit:hooks");
								if (hooks != null) out.add(info.getModId() + ":" + ((Map<?, ?>) hooks).size());
							}
							return out;
						}
					}
					""",
			"fixture.glow.GlowInfoInspector", """
					package fixture.glow;
					import net.neoforged.neoforgespi.language.IModInfo;
					public final class GlowInfoInspector {
						public static Object plugin(IModInfo info) {
							return info.getModProperties().get("glowkit:plugin");
						}
					}
					""");

	@Test
	void everyWayOfWritingTheReaderMeetsTheDeclaringFabricMod() throws Throwable {
		try (URLClassLoader game = neoGameSide()) {
			ClassLoader library = library(game, Map.of(
					"fixture/glow/GlowPluginScanner", true, "fixture/glow/GlowThemeReader", true,
					"fixture/glow/GlowRefReader", true, "fixture/glow/GlowContainerReader", true,
					"fixture/glow/GlowHookTables", true,
					"fixture/glow/GlowModCounter", false, "fixture/glow/GlowInfoInspector", false));
			nativeModList(game);
			publishFabric();

			Class<?> scanner = library.loadClass("fixture.glow.GlowPluginScanner");
			assertEquals(List.of("native_glow=fixture.neo.NativePlugin", "lumen-panels=fixture.lumen.GlowPlugin"),
					InjectorExecution.invokeStatic(scanner, "plugins"),
					"the native mod first, then the Fabric mod declaring the key; the Fabric mod that declares nothing, "
							+ "and the one whose id ModList already answers for, are not added");
			assertEquals("Lumen Panels", InjectorExecution.invokeStatic(scanner, "nameOf", "lumen-panels"),
					"a mod found in the list resolves again by id, to the same mod");
			assertEquals("Lumen Panels", InjectorExecution.invokeStatic(scanner, "nameOf", "lumen_panels"),
					"and under NeoForge's spelling of the id, which is the only one a NeoForge reader can ask with");
			assertEquals("<none>", InjectorExecution.invokeStatic(scanner, "nameOf", "quiet_fabric"),
					"a Fabric mod that declares nothing is still not a NeoForge mod");

			assertEquals(Map.of("lumen-panels", "amber"),
					InjectorExecution.invokeStatic(library.loadClass("fixture.glow.GlowThemeReader"), "accents"),
					"a table arrives as the CommentedConfig a TOML reader casts to");
			assertEquals(List.of("native_glow", "lumen-panels"),
					InjectorExecution.invokeStatic(library.loadClass("fixture.glow.GlowRefReader"), "ids"),
					"method references are calls too");

			Class<?> containers = library.loadClass("fixture.glow.GlowContainerReader");
			assertEquals(List.of("native_glow", "lumen-panels"), InjectorExecution.invokeStatic(containers, "declared"));
			assertEquals(2, InjectorExecution.invokeStatic(containers, "sortedCount"));
			assertEquals(true, InjectorExecution.invokeStatic(containers, "hasFile", "lumen-panels"));
			assertEquals(false, InjectorExecution.invokeStatic(containers, "hasFile", "quiet_fabric"));
			assertEquals(List.of("native_glow", "lumen-panels"), InjectorExecution.invokeStatic(containers, "inOrder"));

			assertEquals(List.of(), InjectorExecution.invokeStatic(library.loadClass("fixture.glow.GlowHookTables"), "tables"),
					"lumen-panels names a class under glowkit:hooks, but this reader casts that key to a Map: a name "
							+ "there would be a ClassCastException, so none is offered");

			Class<?> counter = library.loadClass("fixture.glow.GlowModCounter");
			assertEquals(1, InjectorExecution.invokeStatic(counter, "count"),
					"a class that never reads a declaration keeps what the family loaded");
			assertEquals(false, InjectorExecution.invokeStatic(counter, "has", "lumen-panels"));
		}
	}

	@Test
	void switchedOffTheReaderSeesTheNativeListAgain() throws Throwable {
		try (URLClassLoader game = neoGameSide()) {
			ClassLoader library = library(game, Map.of("fixture/glow/GlowPluginScanner", true));
			nativeModList(game);
			publishFabric();
			System.setProperty(CrossEcosystemDeclarations.SWITCH, "off");
			Class<?> scanner = library.loadClass("fixture.glow.GlowPluginScanner");
			assertEquals(List.of("native_glow=fixture.neo.NativePlugin"), InjectorExecution.invokeStatic(scanner, "plugins"));
			assertEquals("<none>", InjectorExecution.invokeStatic(scanner, "nameOf", "lumen-panels"));
		}
	}

	/**
	 * A list nobody has published containers into yet throws on a by-id lookup. A reader that only enumerates it worked
	 * natively, and must still work: the view decides "already listed" from the mod infos then.
	 */
	@Test
	void aListWhoseContainersAreNotPublishedYetStillAnswersAnEnumeration() throws Throwable {
		try (URLClassLoader game = neoGameSide()) {
			ClassLoader library = library(game, Map.of("fixture/glow/GlowRefReader", true));
			Class.forName("net.neoforged.fml.ModList", true, game).getMethod("of", List.class, List.class)
					.invoke(null, List.of(), List.of());
			publishFabric();
			assertEquals(List.of("lumen-panels", "native_glow"),
					InjectorExecution.invokeStatic(library.loadClass("fixture.glow.GlowRefReader"), "ids"),
					"nothing is native here, so the Fabric mod under an id NeoForge would own is a declarer too");
		}
	}

	@Test
	void theKernelsOwnClassesAndClassesWithoutModListAreNeverEdited() throws Exception {
		Path carrier = carrier();
		Map<String, byte[]> compiled = InjectorExecution.compile(work, LIBRARY, List.of(carrier));
		byte[] scanner = compiled.get("fixture/glow/GlowPluginScanner");
		DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();
		assertSame(scanner, InjectorExecution.transform(injector, "net.forbric.kernel.runtime.Anything", scanner,
				EnvType.CLIENT), "a kernel class is never rewritten, even one shaped like a reader");
		byte[] inspector = compiled.get("fixture/glow/GlowInfoInspector");
		assertSame(inspector, InjectorExecution.transform(injector, "fixture.glow.GlowInfoInspector", inspector,
				EnvType.SERVER));
	}

	// ---- fixtures ----

	/** Compiles {@link #LIBRARY}, runs the injector over the named classes, asserts which were edited, and loads them. */
	private ClassLoader library(ClassLoader game, Map<String, Boolean> expectEdited) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, LIBRARY, List.of(carrier()));
		DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();
		Map<String, byte[]> out = new TreeMap<>();
		for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
			String name = entry.getKey();
			byte[] result = InjectorExecution.transform(injector, name.replace('/', '.'), entry.getValue(), EnvType.CLIENT);
			Boolean edited = expectEdited.get(name);
			if (edited != null) {
				if (edited) assertNotSame(entry.getValue(), result, name + " reads declarations out of ModList");
				else assertSame(entry.getValue(), result, name + " is not a declaration reader of ModList");
			}
			out.put(name, result);
		}
		ClassLoader loader = InjectorExecution.load(out, game);
		for (Map.Entry<String, byte[]> entry : out.entrySet()) {
			if (!expectEdited.getOrDefault(entry.getKey(), false)) continue;
			assertEquals("", InjectorExecution.verify(entry.getValue(), loader), entry.getKey());
		}
		return loader;
	}

	/** A NeoForge {@code ModList} holding one native mod, which declares the key itself. */
	private static void nativeModList(ClassLoader game) throws Exception {
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, game);
		Object list = modList.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
		DiscoveredMod declared = new DiscoveredMod(Ecosystem.NEOFORGE, "native_glow", "2.0.0", "Native Glow", List.of(),
				List.of(), null, null).withModProperties(Map.of(PLUGIN_KEY, "fixture.neo.NativePlugin"));
		Object info = Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, game)
				.getConstructor(String.class, Path.class, DiscoveredMod.class).newInstance("native_glow", null, declared);
		Object container = Class.forName("net.forbric.kernel.runtime.KernelModContainer", true, game)
				.getConstructor(Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, game),
						Class.forName("net.neoforged.bus.api.IEventBus", false, game))
				.newInstance(info, null);
		Field sorted = modList.getDeclaredField("sortedList");
		sorted.setAccessible(true);
		sorted.set(list, List.of(info));
		Method setLoaded = modList.getDeclaredMethod("setLoadedMods", List.class);
		setLoaded.setAccessible(true);
		setLoaded.invoke(list, List.of(container));
	}

	/**
	 * Three Fabric mods, published the way a boot publishes them: one declaring the key (an entrypoint) and a table (a
	 * custom value), one declaring nothing, and one under an id the native list already answers for.
	 */
	static void publishFabric() {
		List<DiscoveredMod> fabric = new ArrayList<>();
		publishFabricMods("""
				{"schemaVersion":1,"id":"lumen-panels","version":"1.4.0","name":"Lumen Panels",
				 "entrypoints":{"glowkit:plugin":["fixture.lumen.GlowPlugin"],"glowkit:hooks":["fixture.lumen.Hooks"],
				                "main":["fixture.lumen.Main"]},
				 "custom":{"glowkit:theme":{"accent":"amber"}}}
				""", """
				{"schemaVersion":1,"id":"quiet_fabric","version":"1.0.0","entrypoints":{"main":["fixture.quiet.Main"]}}
				""", """
				{"schemaVersion":1,"id":"native_glow","version":"9.9.9","entrypoints":{"glowkit:plugin":["fixture.dup.Plugin"]}}
				""");
	}

	/** Publishes Fabric mods exactly as {@code KernelFabricEcosystem} does: custom values on each, names beside them. */
	static void publishFabricMods(String... manifests) {
		List<DiscoveredMod> fabric = new ArrayList<>();
		Map<String, Map<String, Object>> names = new LinkedHashMap<>();
		for (String json : manifests) {
			KernelModMetadata meta = FabricModMetadataParser.read(new StringReader(json));
			Map<String, Object> declared = CrossEcosystemDeclarations.entrypointNames(meta.getEntrypoints());
			if (!declared.isEmpty()) names.put(meta.getId(), declared);
			fabric.add(fabricMod(meta));
		}
		CrossEcosystemDeclarations.publishFabricEntrypointNames(names);
		ModPresence.publishFabric(fabric);
	}

	/** A Fabric mod as {@code KernelFabricEcosystem} publishes it: its custom values as properties. */
	static DiscoveredMod fabricMod(KernelModMetadata meta) {
		return new DiscoveredMod(Ecosystem.FABRIC, meta.getId(), meta.getVersion().getFriendlyString(), meta.getName(),
				List.of(), List.of(), null, null)
				.withModProperties(CrossEcosystemDeclarations.customProperties(meta.getCustomValues()));
	}

	static Path carrier() {
		Path carrier = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(carrier), "the staged NeoForge carrier is absent");
		return carrier;
	}

	/**
	 * The game side as the kernel ships it, over the staged NeoForge carrier, with the logging libraries Minecraft
	 * supplies: {@code ModList}'s constructor registers a crash-report section through Mojang's {@code LogUtils}, and
	 * {@code ModContainer} keeps a log4j logger.
	 */
	static URLClassLoader neoGameSide() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game side is not compiled");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), carrier().toUri().toURL()));
		for (String library : List.of("com/mojang/logging", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api")) {
			Path jar = newestUnder(library);
			TestFixtures.require(Fixture.MC_LIBRARIES, jar != null, "Minecraft's " + library + " library is absent");
			urls.add(jar.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(URL[]::new), DeclarationReaderModListInjectorTest.class.getClassLoader());
	}

	private static Path newestUnder(String pattern) throws java.io.IOException {
		Path under = TestFixtures.minecraftDir().resolve("libraries").resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
