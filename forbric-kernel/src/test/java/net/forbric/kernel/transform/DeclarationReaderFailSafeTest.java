/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;

/**
 * A declaration reader is a library's own startup code — typically a config loader running inside
 * {@code Minecraft.<init>} with no handler around its walk — so the NeoForge hooks must never turn the walk into a
 * crash the native {@code ModList} could not have caused. Whatever fails while the Fabric mods are added, the reader is
 * handed the native answer unchanged and the failure is a {@code SUSPECTED} finding naming the reader.
 *
 * <p>The library is made up ({@code halolib}) and its readers are written differently from the other declaration
 * tests: every one of the seven redirected {@code ModList} members, a by-id read through {@code Optional.map}, and a
 * reader that needs the event bus of every mod declaring its key — which a declaring Fabric mod, natively absent from
 * the list, does not have. Two kinds of failure are covered: one inside the hook (a Fabric mod's declarations cannot be
 * read — injected through the published table, since nothing in a healthy boot throws there), and one in the reader's
 * own callback, applied by the hook to a Fabric mod's bus-less container.
 */
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ExecutesInjector(DeclarationReaderModListInjector.class)
class DeclarationReaderFailSafeTest {
	@TempDir
	Path work;

	@BeforeEach
	@AfterEach
	void reset() {
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
		CrossEcosystemDeclarations.resetForTests();
		CompatibilityFindings.reset();
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
	}

	static final Map<String, String> LIBRARY = Map.of(
			"fixture.halo.HaloAddonWalk", """
					package fixture.halo;
					import java.util.*;
					import java.util.stream.*;
					import net.neoforged.fml.ModContainer;
					import net.neoforged.fml.ModList;
					import net.neoforged.neoforgespi.language.IModInfo;
					public final class HaloAddonWalk {
						private static final String KEY = "halolib:addon";
						public static List<String> addons() {
							List<String> out = new ArrayList<>();
							for (IModInfo info : ModList.get().getMods()) {
								Object addon = info.getModProperties().get(KEY);
								if (addon != null) out.add(info.getModId());
							}
							return out;
						}
						public static int sorted() {
							return ModList.get().getSortedMods().size();
						}
						public static String name(String id) {
							return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getDisplayName()).orElse("<none>");
						}
						public static boolean file(String id) {
							return ModList.get().getModFileById(id) != null;
						}
						public static List<String> each() {
							List<String> out = new ArrayList<>();
							ModList.get().forEachModContainer((id, container) -> out.add(id));
							return out;
						}
						public static List<String> inOrder() {
							List<String> out = new ArrayList<>();
							ModList.get().forEachModInOrder(container -> out.add(container.getModId()));
							return out;
						}
						public static List<String> applied() {
							return ModList.get().applyForEachModContainer(ModContainer::getModId).collect(Collectors.toList());
						}
					}
					""",
			"fixture.halo.HaloBusWiring", """
					package fixture.halo;
					import java.util.*;
					import java.util.stream.*;
					import net.neoforged.fml.ModContainer;
					import net.neoforged.fml.ModList;
					public final class HaloBusWiring {
						private static String wire(ModContainer container) {
							if (container.getEventBus() == null) throw new IllegalStateException(container.getModId() + " has no event bus");
							return container.getModId();
						}
						private static boolean declares(ModContainer container) {
							return container.getModInfo().getModProperties().containsKey("halolib:addon");
						}
						public static List<String> each() {
							List<String> out = new ArrayList<>();
							ModList.get().forEachModContainer((id, container) -> { if (declares(container)) out.add(wire(container)); });
							return out;
						}
						public static List<String> inOrder() {
							List<String> out = new ArrayList<>();
							ModList.get().forEachModInOrder(container -> { if (declares(container)) out.add(wire(container)); });
							return out;
						}
						public static List<String> applied() {
							return ModList.get().applyForEachModContainer(c -> declares(c) ? wire(c) : null)
									.filter(Objects::nonNull).collect(Collectors.toList());
						}
						public static List<String> declared() {
							List<String> out = new ArrayList<>();
							for (ModContainer container : ModList.get().getSortedMods()) if (declares(container)) out.add(container.getModId());
							return out;
						}
					}
					""");

	/**
	 * A Fabric mod's published declarations that throw when read. Nothing in a healthy boot throws there; this stands for
	 * any failure inside a hook — a container that cannot be built, a view that cannot be read.
	 */
	static final class UnreadableTable extends AbstractMap<String, Object> {
		@Override
		public Set<Entry<String, Object>> entrySet() {
			throw new IllegalStateException("this declaration table cannot be read");
		}
	}

	@Test
	void aHookThatCannotAddTheFabricModsHandsTheReaderTheNativeAnswer() throws Throwable {
		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			ClassLoader library = library(game);
			nativeModList(game);
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"flare-addons","version":"2.0.0","name":"Flare Addons",
					 "custom":{"halolib:addon":{"slot":"left"}}}
					""");
			// A second Fabric mod whose declarations cannot be read: building the declarers throws.
			List<DiscoveredMod> fabric = new java.util.ArrayList<>(ModPresence.fabricMods());
			fabric.add(new DiscoveredMod(Ecosystem.FABRIC, "murky-addons", "1.0.0", "Murky Addons", List.of(), List.of(),
					null, null));
			ModPresence.publishFabric(fabric);
			CrossEcosystemDeclarations.publishFabricEntrypointNames(Map.of("murky-addons", new UnreadableTable()));

			Class<?> walk = library.loadClass("fixture.halo.HaloAddonWalk");
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(walk, "addons"));
			assertEquals(1, InjectorExecution.invokeStatic(walk, "sorted"));
			assertEquals("<none>", InjectorExecution.invokeStatic(walk, "name", "flare-addons"));
			assertEquals("Native Halo", InjectorExecution.invokeStatic(walk, "name", "native_halo"),
					"a by-id lookup the native list answers never reaches the guarded part");
			assertEquals(false, InjectorExecution.invokeStatic(walk, "file", "flare-addons"));
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(walk, "each"));
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(walk, "inOrder"));
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(walk, "applied"));

			assertEquals(Set.of("getMods", "getSortedMods", "getModContainerById", "getModFileById", "forEachModContainer",
					"forEachModInOrder", "applyForEachModContainer"), suspectedHooks("fixture.halo.HaloAddonWalk"),
					"each hook that fell back says so once, naming the reader");
			for (CompatibilityFinding finding : CompatibilityFindings.all()) {
				assertEquals(CompatibilityFinding.Confidence.SUSPECTED, finding.confidence(), finding.toString());
				assertTrue(finding.evidence().stream().anyMatch(e -> e.contains("this declaration table cannot be read")),
						finding.toString());
			}

			// The failure was the published table; once it reads again, the same reader meets the declaring mod.
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"flare-addons","version":"2.0.0","name":"Flare Addons",
					 "custom":{"halolib:addon":{"slot":"left"}}}
					""");
			assertEquals(List.of("native_halo", "flare-addons"), InjectorExecution.invokeStatic(walk, "addons"));
			assertEquals("Flare Addons", InjectorExecution.invokeStatic(walk, "name", "flare-addons"));
		}
	}

	@Test
	void aReaderCallbackThatCannotHandleAFabricModsContainerLeavesThatModOut() throws Throwable {
		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			ClassLoader library = library(game);
			nativeModList(game);
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"flare-addons","version":"2.0.0","name":"Flare Addons",
					 "custom":{"halolib:addon":{"slot":"left"}}}
					""");

			Class<?> wiring = library.loadClass("fixture.halo.HaloBusWiring");
			assertEquals(List.of("native_halo", "flare-addons"), InjectorExecution.invokeStatic(wiring, "declared"),
					"the reader does meet the declaring Fabric mod");
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(wiring, "each"),
					"its callback needs an event bus the Fabric mod's container does not have: that mod is skipped, the "
							+ "walk is not thrown out of");
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(wiring, "inOrder"));
			assertEquals(List.of("native_halo"), InjectorExecution.invokeStatic(wiring, "applied"),
					"the stream stays lazy, and the element the function cannot produce is left out");
			assertEquals(Set.of("forEachModContainer", "forEachModInOrder", "applyForEachModContainer"),
					suspectedHooks("fixture.halo.HaloBusWiring"));
			assertTrue(CompatibilityFindings.all().stream().allMatch(f -> f.evidence().stream()
					.anyMatch(e -> e.contains("flare-addons has no event bus"))));
		}
	}

	@Test
	void whatTheNativeCallThrowsIsStillTheReadersOwn() throws Throwable {
		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			ClassLoader library = library(game);
			// A list nobody has published containers into: a native by-id lookup throws on it.
			Class.forName("net.neoforged.fml.ModList", true, game).getMethod("of", List.class, List.class)
					.invoke(null, List.of(), List.of());
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"flare-addons","version":"2.0.0","custom":{"halolib:addon":"x"}}
					""");
			Class<?> walk = library.loadClass("fixture.halo.HaloAddonWalk");
			Throwable thrown = null;
			try {
				InjectorExecution.invokeStatic(walk, "name", "flare-addons");
			} catch (Throwable expected) {
				thrown = expected;
			}
			assertTrue(thrown instanceof RuntimeException, "the native call's own failure is not swallowed: " + thrown);
			assertEquals(Set.of(), suspectedHooks("fixture.halo.HaloAddonWalk"), "and it is not the hook's failure");
		}
	}

	// ---- fixtures ----

	private static Set<String> suspectedHooks(String reader) {
		Set<String> hooks = new TreeSet<>();
		for (CompatibilityFinding finding : CompatibilityFindings.suspected()) {
			String prefix = "declaration-readers:";
			if (!finding.id().startsWith(prefix) || !finding.id().endsWith(":" + reader)) continue;
			assertEquals("forbric", finding.modId());
			hooks.add(finding.id().substring(prefix.length(), finding.id().length() - reader.length() - 1));
		}
		return hooks;
	}

	private ClassLoader library(ClassLoader game) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, LIBRARY,
				List.of(DeclarationReaderModListInjectorTest.carrier()));
		DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();
		Map<String, byte[]> out = new TreeMap<>();
		for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
			byte[] result = InjectorExecution.transform(injector, entry.getKey().replace('/', '.'), entry.getValue(),
					EnvType.CLIENT);
			if (entry.getKey().equals("fixture/halo/HaloAddonWalk") || entry.getKey().equals("fixture/halo/HaloBusWiring")) {
				assertNotSame(entry.getValue(), result, entry.getKey() + " reads declarations out of ModList");
			}
			out.put(entry.getKey(), result);
		}
		ClassLoader loader = InjectorExecution.load(out, game);
		assertEquals("", InjectorExecution.verify(out.get("fixture/halo/HaloAddonWalk"), loader));
		assertEquals("", InjectorExecution.verify(out.get("fixture/halo/HaloBusWiring"), loader));
		return loader;
	}

	/** One native NeoForge mod declaring the key, in a container that has an event bus, as a NeoForge mod's does. */
	private static void nativeModList(ClassLoader game) throws Exception {
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, game);
		Object list = modList.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
		DiscoveredMod declared = new DiscoveredMod(Ecosystem.NEOFORGE, "native_halo", "3.0.0", "Native Halo", List.of(),
				List.of(), null, null).withModProperties(Map.of("halolib:addon", "fixture.neo.NativeAddon"));
		Object info = Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, game)
				.getConstructor(String.class, Path.class, DiscoveredMod.class).newInstance("native_halo", null, declared);
		Class<?> busType = Class.forName("net.neoforged.bus.api.IEventBus", false, game);
		Object bus = Proxy.newProxyInstance(game, new Class<?>[] {busType}, (proxy, method, args) -> switch (method.getName()) {
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			case "toString" -> "native_halo's bus";
			default -> null;
		});
		Object container = Class.forName("net.forbric.kernel.runtime.KernelModContainer", true, game)
				.getConstructor(Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, game), busType)
				.newInstance(info, bus);
		Field sorted = modList.getDeclaredField("sortedList");
		sorted.setAccessible(true);
		sorted.set(list, List.of(info));
		Method setLoaded = modList.getDeclaredMethod("setLoadedMods", List.class);
		setLoaded.setAccessible(true);
		setLoaded.invoke(list, List.of(container));
	}
}
