/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.fabric.DispatchFixtures;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * A library installed as a Fabric build and a NeoForge build, the NeoForge one loaded: the entrypoints its Fabric build
 * offered other mods under a custom key are still declared, under its id, where the loaded build can satisfy them — and
 * never its lifecycle entrypoints, a key the loaded build declares itself, or one the loaded build cannot link.
 *
 * <p>"lanternlib" and its consumer "emberwick" exist nowhere. The custom key {@code lantern:menu} plays the part a Mod
 * Menu plugin plays: a reader asks every mod for it and the library answers for its consumers.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class ArbitratedAwayEntrypointsTest {
	private static final String MENU = "lantern:menu";

	@TempDir Path dir;

	@BeforeEach @AfterEach void reset() {
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		CompatibilityFindings.reset();
		ModCatalog.publish(List.of());
		System.clearProperty(ArbitratedAwayEntrypoints.PROPERTY);
	}

	/** Classes both builds ship (the library's common half) and each build's own. */
	private static final Map<String, String> COMMON = Map.of(
			"org.lantern.config.Tracker", """
					package org.lantern.config;
					public final class Tracker {
						public static final Tracker INSTANCE = new Tracker();
						private final java.util.Map<String, Integer> byMod = new java.util.TreeMap<>();
						public void track(String mod, int configs) { byMod.put(mod, configs); }
						public java.util.Map<String, Integer> configsByMod() { return byMod; }
					}
					""",
			"org.lantern.screen.Select", """
					package org.lantern.screen;
					public final class Select {
						public final String title;
						public Select(String mod, int configs) { this.title = mod + " (" + configs + ")"; }
					}
					""",
			"org.lantern.common.SharedProbe", """
					package org.lantern.common;
					public class SharedProbe implements java.util.function.Supplier<String> {
						public String get() { return "shared"; }
					}
					""");

	private static final Map<String, String> FABRIC_ONLY = Map.of(
			"org.lantern.fabric.LanternFabric", """
					package org.lantern.fabric;
					/** The library's Fabric initialisation: what the loaded build does its own way. */
					public class LanternFabric implements net.fabricmc.api.ModInitializer {
						public void onInitialize() {
							Paths.gameDir = "set by main";
							org.lantern.config.Tracker.INSTANCE.track("twice", 0);
							// Its own protocol: the platform implementation it asks Fabric Loader for.
							for (Runnable platform : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypoints("lantern:platform", Runnable.class)) platform.run();
						}
					}
					""",
			"org.lantern.fabric.FabricPlatform", """
					package org.lantern.fabric;
					public class FabricPlatform implements Runnable { public void run() { } }
					""",
			"org.lantern.fabric.LanternComponents", """
					package org.lantern.fabric;
					/** What it registers with a framework it requires: its Fabric side's data model. */
					public class LanternComponents implements Runnable { public void run() { } }
					""",
			"org.lantern.fabric.Paths", """
					package org.lantern.fabric;
					public final class Paths { public static String gameDir; }
					""",
			"org.lantern.compat.LanternMenu", """
					package org.lantern.compat;
					/** The plugin a reader asks for: one screen per consumer the library tracks. */
					public class LanternMenu implements java.util.function.Supplier<java.util.Map<String, String>> {
						public java.util.Map<String, String> get() {
							java.util.Map<String, String> screens = new java.util.TreeMap<>();
							org.lantern.config.Tracker.INSTANCE.configsByMod().forEach((mod, configs) ->
									screens.put(mod, new org.lantern.screen.Select(mod, configs).title));
							return screens;
						}
					}
					""",
			"org.lantern.compat.Vanished", """
					package org.lantern.compat;
					/** Uses a member only the Fabric build's Tracker had (compiled against it; the NeoForge one lacks it). */
					public class Vanished implements Runnable {
						public void run() { org.lantern.config.Tracker.INSTANCE.forget(); }
					}
					""",
			"org.lantern.compat.ReachBack", """
					package org.lantern.compat;
					/** Reads state only the Fabric build's own initialisation sets. */
					public class ReachBack implements java.util.function.Supplier<String> {
						public String get() { return org.lantern.fabric.Paths.gameDir; }
					}
					""");

	private static final Map<String, String> NEO_ONLY = Map.of(
			"org.lantern.neoforge.LanternNeo", """
					package org.lantern.neoforge;
					public class LanternNeo { }
					""");

	/** The Fabric build's Tracker: the same, plus the member Vanished calls. */
	private static final String FABRIC_TRACKER = """
			package org.lantern.config;
			public final class Tracker {
				public static final Tracker INSTANCE = new Tracker();
				private final java.util.Map<String, Integer> byMod = new java.util.TreeMap<>();
				public void track(String mod, int configs) { byMod.put(mod, configs); }
				public java.util.Map<String, Integer> configsByMod() { return byMod; }
				public void forget() { byMod.clear(); }
			}
			""";

	private Map<String, byte[]> compile(String name, Map<String, String> sources) throws Exception {
		return InjectorExecution.compile(dir.resolve("src-" + name), new HashMap<>(sources));
	}

	private static String fabricModJson(String id, Map<String, List<String>> entrypoints, String extra) {
		StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"" + extra + ",\"entrypoints\":{");
		boolean first = true;
		for (var e : entrypoints.entrySet()) {
			if (!first) json.append(',');
			first = false;
			json.append('"').append(e.getKey()).append("\":[");
			for (int i = 0; i < e.getValue().size(); i++) json.append(i == 0 ? "" : ",").append('"').append(e.getValue().get(i)).append('"');
			json.append(']');
		}
		return json.append("}}").toString();
	}

	private Map<String, byte[]> fabricBuild() throws Exception {
		Map<String, String> sources = new HashMap<>(COMMON);
		sources.putAll(FABRIC_ONLY);
		sources.put("org.lantern.config.Tracker", FABRIC_TRACKER);
		Map<String, byte[]> entries = new TreeMap<>();
		compile("fabric", sources).forEach((name, bytes) -> entries.put(name, bytes));
		Map<String, List<String>> entrypoints = new LinkedHashMap<>();
		entrypoints.put("main", List.of("org.lantern.fabric.LanternFabric"));
		entrypoints.put("client", List.of("org.lantern.fabric.LanternFabric"));
		entrypoints.put("preLaunch", List.of("org.lantern.fabric.LanternFabric"));
		entrypoints.put(MENU, List.of("org.lantern.compat.LanternMenu"));
		entrypoints.put("lantern:shared", List.of("org.lantern.common.SharedProbe"));
		entrypoints.put("lantern:vanished", List.of("org.lantern.compat.Vanished"));
		entrypoints.put("lantern:reach", List.of("org.lantern.compat.ReachBack"));
		entrypoints.put("lantern:platform", List.of("org.lantern.fabric.FabricPlatform"));
		entrypoints.put("corekit:components", List.of("org.lantern.fabric.LanternComponents"));
		entries.put("fabric.mod.json", fabricModJson("lanternlib", entrypoints, ",\"depends\":{\"corekit\":\"*\"}")
				.getBytes(StandardCharsets.UTF_8));
		return entries;
	}

	private Map<String, byte[]> neoForgeBuild() throws Exception {
		Map<String, String> sources = new HashMap<>(COMMON);
		sources.putAll(NEO_ONLY);
		Map<String, byte[]> entries = new TreeMap<>();
		compile("neo", sources).forEach((name, bytes) -> entries.put(name, bytes));
		entries.put("META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml("lanternlib"));
		return entries;
	}

	private static Map<String, String> decisions(List<ArbitratedAwayEntrypoints.Decision> decisions) {
		Map<String, String> out = new TreeMap<>();
		for (var d : decisions) out.put(d.key(), d.kept() ? "kept" : d.refusal());
		return out;
	}

	@Test void customKeysTheLoadedBuildCanSatisfyAreKeptAndLifecycleKeysNever() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("lanternlib-fabric.jar"), fabricBuild());
		Path winning = DispatchFixtures.write(dir.resolve("lanternlib-neoforge.jar"), neoForgeBuild());
		Map<String, String> got = decisions(ArbitratedAwayEntrypoints.decide("lanternlib", List.of(losing), winning, Set.of()));
		assertEquals(Set.of(MENU, "lantern:shared", "lantern:vanished", "lantern:reach", "corekit:components"), got.keySet(),
				"main, client and preLaunch are the library's initialisation, which the loaded build did, and lantern:platform "
						+ "is a protocol of its own (its Fabric build asks for it): " + got);
		assertEquals("kept", got.get(MENU), "only the losing build has the plugin, and all it uses the loaded build has");
		assertEquals("kept", got.get("lantern:shared"), "its class is the loaded build's own");
		assertTrue(got.get("lantern:vanished").contains("method org.lantern.config.Tracker.forget()V"), got.get("lantern:vanished"));
		assertTrue(got.get("lantern:reach").contains("org.lantern.fabric.Paths.gameDir"), got.get("lantern:reach"));

		assertFalse(decisions(ArbitratedAwayEntrypoints.decide("lanternlib", List.of(losing), winning, Set.of(MENU)))
				.containsKey(MENU), "a key the loaded build declares itself is its own to answer");

		// corekit: a framework the Fabric build requires, which asks every mod for its components.
		Path corekit = DispatchFixtures.write(dir.resolve("corekit.jar"), corekitBuild());
		assertEquals("kept", got.get("corekit:components"), "with the framework not loaded nothing tells it apart");
		Map<String, String> withFramework = decisions(ArbitratedAwayEntrypoints.decide("lanternlib", List.of(losing), winning,
				Set.of(), id -> id.equals("corekit") ? corekit : null));
		assertFalse(withFramework.containsKey("corekit:components"),
				"a key asked for by a mod the losing build requires is that framework's, which the loaded build replaced: " + withFramework);
		assertEquals("kept", withFramework.get(MENU), "Mod Menu-like integrations stay: the library does not require their reader");
	}

	/** A framework mod that asks every mod for its {@code corekit:components} entrypoints. */
	private Map<String, byte[]> corekitBuild() throws Exception {
		Map<String, byte[]> entries = new TreeMap<>(compile("corekit", Map.of("org.corekit.CoreKit", """
				package org.corekit;
				public class CoreKit implements net.fabricmc.api.ModInitializer {
					public void onInitialize() {
						for (Runnable r : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypoints("corekit:components", Runnable.class)) r.run();
					}
				}
				""")));
		entries.put("fabric.mod.json", fabricModJson("corekit", Map.of("main", List.of("org.corekit.CoreKit")), "")
				.getBytes(StandardCharsets.UTF_8));
		return entries;
	}

	/** Through real arbitration, the presence alias's declarations, Fabric Loader's index and the rescue class path. */
	@Test void theReaderMeetsThePluginUnderTheLibrarysIdAndItAnswersFromTheLoadedBuild() throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path winning = DispatchFixtures.write(mods.resolve("lanternlib-neoforge.jar"), neoForgeBuild());
		DispatchFixtures.write(mods.resolve("emberwick-fabric.jar"), Map.of(
				"fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"emberwick\",\"version\":\"1.0.0\",\"jars\":[{\"file\":"
						+ "\"META-INF/jars/lanternlib-fabric.jar\"}]}").getBytes(StandardCharsets.UTF_8),
				"META-INF/jars/lanternlib-fabric.jar", DispatchFixtures.jar(fabricBuild())));

		DuplicateModArbiter.Decision decision = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);
		assertEquals(1, decision.rescueJars().size(), decision::toString);
		Map<String, List<KernelModMetadata.EntrypointDecl>> adopted = ArbitratedAwayEntrypoints.adopted("lanternlib", decision, Map.of());
		assertEquals(Set.of(MENU, "lantern:shared", "corekit:components"), adopted.keySet(),
				"no framework loaded here, so its key is kept like any other: " + adopted);
		assertEquals(1, CompatibilityFindings.all().stream().filter(f -> f.id().equals("arbitration:declared-entrypoint:lantern:vanished")).count());

		Path losing = decision.rescueJars().iterator().next();
		try (ForbricClassLoader game = new ForbricClassLoader(new URL[] {decision.ownerByModId().get("lanternlib").toUri().toURL()},
				getClass().getClassLoader())) {
			game.setRescueJars(List.of(losing.toUri().toURL()));
			var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
			constructor.setAccessible(true);
			KernelFabricLoader fabric = constructor.newInstance(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
			fabric.setGameLoader(game);
			fabric.register(KernelModContainer.presence(KernelModMetadata.builtin("lanternlib", "1.0.0", "lanternlib", Map.of(), adopted),
					winning));
			fabric.freeze();

			game.loadClass("org.lantern.config.Tracker").getMethod("track", String.class, int.class)
					.invoke(game.loadClass("org.lantern.config.Tracker").getField("INSTANCE").get(null), "emberwick", 2);
			@SuppressWarnings("rawtypes")
			List<EntrypointContainer<java.util.function.Supplier>> menus = fabric.getEntrypointContainers(MENU, java.util.function.Supplier.class);
			assertEquals(1, menus.size());
			assertEquals("lanternlib", menus.getFirst().getProvider().getMetadata().getId(), "offered under the library's own id");
			assertEquals(Map.of("emberwick", "emberwick (2)"), menus.getFirst().getEntrypoint().get(),
					"the plugin (lent by the losing build) answers from the loaded build's tracker and screen");
			assertSame(game.loadClass("org.lantern.config.Tracker").getClassLoader(), game, "the tracker is the loaded build's");
		}
	}

	/**
	 * What runs is read method by method: the library's Fabric initialisation never runs here, so a field only it writes is
	 * unwritten even where the entrypoint reads that very class's field, or calls another of its methods — and a field the
	 * entrypoint's own code writes (directly, through a static helper of the initialiser's class, or in a screen it creates
	 * and the game later draws) is written. Each kept entrypoint is then asked, as a reader would: none throws.
	 */
	@Test void onlyWhatTheEntrypointReachesWritesAFieldItReads() throws Exception {
		Map<String, String> sources = new HashMap<>(Map.of(
				"org.tallow.fabric.TallowFabric", """
						package org.tallow.fabric;
						public class TallowFabric implements net.fabricmc.api.ModInitializer {
							public static java.util.Map<String, String> SCREENS;
							public static String name;
							public void onInitialize() { SCREENS = new java.util.TreeMap<>(); Paths.gameDir = "set by main"; }
							public static void touch() { }
							public static String name() { if (name == null) name = "tallow"; return name; }
						}
						""",
				"org.tallow.fabric.Paths", """
						package org.tallow.fabric;
						public final class Paths { public static String gameDir; }
						""",
				"org.tallow.compat.OwnField", """
						package org.tallow.compat;
						/** Reads the initialiser's own field, which only its onInitialize writes. */
						public class OwnField implements java.util.function.Supplier<String> {
							public String get() { return String.valueOf(org.tallow.fabric.TallowFabric.SCREENS.size()); }
						}
						""",
				"org.tallow.compat.Touching", """
						package org.tallow.compat;
						/** ReachBack's shape, plus one call into the initialiser's class. */
						public class Touching implements java.util.function.Supplier<String> {
							public String get() { org.tallow.fabric.TallowFabric.touch(); return org.tallow.fabric.Paths.gameDir.trim(); }
						}
						""",
				"org.tallow.compat.SelfServed", """
						package org.tallow.compat;
						/** Reads a field of the initialiser's class that a static helper it calls writes first. */
						public class SelfServed implements java.util.function.Supplier<String> {
							public String get() { org.tallow.fabric.TallowFabric.name(); return org.tallow.fabric.TallowFabric.name.trim(); }
						}
						""",
				"org.tallow.compat.Drawn", """
						package org.tallow.compat;
						/** Hands out a screen; the game calls its init before it draws, which reads what init wrote. */
						public class Drawn implements java.util.function.Supplier<Runnable> {
							public Runnable get() { Screen screen = new Screen(); screen.init(); return screen; }
						}
						""",
				"org.tallow.compat.Screen", """
						package org.tallow.compat;
						public class Screen implements Runnable {
							private String title;
							public void init() { title = "tallow"; }
							public void run() { title.trim(); }
						}
						"""));
		Map<String, byte[]> fabric = new TreeMap<>(compile("tallow-fabric", sources));
		Map<String, List<String>> entrypoints = new LinkedHashMap<>();
		entrypoints.put("main", List.of("org.tallow.fabric.TallowFabric"));
		entrypoints.put("tallow:own", List.of("org.tallow.compat.OwnField"));
		entrypoints.put("tallow:touching", List.of("org.tallow.compat.Touching"));
		entrypoints.put("tallow:self", List.of("org.tallow.compat.SelfServed"));
		entrypoints.put("tallow:drawn", List.of("org.tallow.compat.Drawn"));
		fabric.put("fabric.mod.json", fabricModJson("tallowlib", entrypoints, "").getBytes(StandardCharsets.UTF_8));
		Path losing = DispatchFixtures.write(dir.resolve("tallowlib-fabric.jar"), fabric);
		Map<String, byte[]> neo = new TreeMap<>(compile("tallow-neo", Map.of("org.tallow.neoforge.TallowNeo",
				"package org.tallow.neoforge; public class TallowNeo { }")));
		neo.put("META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml("tallowlib"));
		Path winning = DispatchFixtures.write(dir.resolve("tallowlib-neoforge.jar"), neo);

		Map<String, String> got = decisions(ArbitratedAwayEntrypoints.decide("tallowlib", List.of(losing), winning, Set.of()));
		assertTrue(got.get("tallow:own").contains("org.tallow.fabric.TallowFabric.SCREENS"), got.get("tallow:own"));
		assertTrue(got.get("tallow:touching").contains("org.tallow.fabric.Paths.gameDir"), got.get("tallow:touching"));
		assertEquals("kept", got.get("tallow:self"), "the helper it calls writes the field: " + got);
		assertEquals("kept", got.get("tallow:drawn"), "the screen it creates writes its own field: " + got);

		// What a reader then meets: the losing build's onInitialize never runs, and neither kept entrypoint throws.
		try (java.net.URLClassLoader game = new java.net.URLClassLoader(new URL[] {winning.toUri().toURL(), losing.toUri().toURL()},
				getClass().getClassLoader())) {
			for (String type : List.of("org.tallow.compat.SelfServed", "org.tallow.compat.Drawn")) {
				Object answer = ((java.util.function.Supplier<?>) game.loadClass(type).getDeclaredConstructor().newInstance()).get();
				if (answer instanceof Runnable screen) screen.run();
			}
			// …and the refused ones would have: the control for what the refusal spares the reader.
			for (String type : List.of("org.tallow.compat.OwnField", "org.tallow.compat.Touching")) {
				var entry = (java.util.function.Supplier<?>) game.loadClass(type).getDeclaredConstructor().newInstance();
				assertThrows(NullPointerException.class, entry::get, type);
			}
		}
	}

	@Test void switchedOffNothingIsAdopted() throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		DispatchFixtures.write(mods.resolve("lanternlib-neoforge.jar"), neoForgeBuild());
		DispatchFixtures.write(mods.resolve("emberwick-fabric.jar"), Map.of(
				"fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"emberwick\",\"version\":\"1.0.0\",\"jars\":[{\"file\":"
						+ "\"META-INF/jars/lanternlib-fabric.jar\"}]}").getBytes(StandardCharsets.UTF_8),
				"META-INF/jars/lanternlib-fabric.jar", DispatchFixtures.jar(fabricBuild())));
		DuplicateModArbiter.Decision decision = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);
		System.setProperty(ArbitratedAwayEntrypoints.PROPERTY, "off");
		assertEquals(Map.of(), ArbitratedAwayEntrypoints.adopted("lanternlib", decision, Map.of()));
	}
}
