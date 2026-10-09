/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
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
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;

/**
 * Which Fabric entrypoint names a Forge-family reader is offered is decided by every reader of the key together, and a
 * Fabric mod that ends up offering nothing is not in any reader's list.
 *
 * <p>The library is made up ({@code prismlib}) and written unlike the other declaration fixtures: two separate reader
 * classes share one key, one reading it as a name ({@code getOrDefault} kept in a local, then {@code instanceof String})
 * and one as a table (a {@code (Map)} cast — a name there would be a {@code ClassCastException}); a third key is read
 * by name through a plain {@code (String)} cast. The readers walk {@code getSortedMods()} and {@code forEachModContainer}.
 * The class names involved are never given to the injector or the hooks: everything is decided from the readers'
 * bytecode as each class is defined.
 */
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("system-properties")
@ExecutesInjector(DeclarationReaderModListInjector.class)
class DeclarationReaderNameOfferTest {
	@TempDir
	Path work;

	@BeforeEach
	@AfterEach
	void reset() {
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
		CrossEcosystemDeclarations.resetForTests();
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
	}

	static final String NAME_READER = "fixture/prism/PrismRendererIndex";
	static final String TABLE_READER = "fixture/prism/PrismRendererTables";

	static final Map<String, String> LIBRARY = Map.of(
			"fixture.prism.PrismRendererIndex", """
					package fixture.prism;
					import java.util.*;
					import net.neoforged.fml.ModContainer;
					import net.neoforged.fml.ModList;
					public final class PrismRendererIndex {
						public static List<String> renderers() {
							List<String> out = new ArrayList<>();
							for (ModContainer container : ModList.get().getSortedMods()) {
								Map<String, Object> declared = container.getModInfo().getModProperties();
								Object value = declared.getOrDefault("prismlib:renderer", null);
								if (value instanceof String className) out.add(container.getModId() + "=" + className);
							}
							return out;
						}
						public static List<String> palettes() {
							List<String> out = new ArrayList<>();
							for (ModContainer container : ModList.get().getSortedMods()) {
								String palette = (String) container.getModInfo().getModProperties().get("prismlib:palette");
								if (palette != null) out.add(container.getModId() + "=" + palette);
							}
							return out;
						}
						public static List<String> everyone() {
							List<String> out = new ArrayList<>();
							for (ModContainer container : ModList.get().getSortedMods()) out.add(container.getModId());
							return out;
						}
						public static boolean knows(String id) {
							return ModList.get().getModContainerById(id).isPresent();
						}
					}
					""",
			"fixture.prism.PrismRendererTables", """
					package fixture.prism;
					import java.util.*;
					import net.neoforged.fml.ModList;
					public final class PrismRendererTables {
						public static Map<String, Integer> tables() {
							Map<String, Integer> out = new TreeMap<>();
							ModList.get().forEachModContainer((id, container) -> {
								Object raw = container.getModInfo().getModProperties().get("prismlib:renderer");
								if (raw == null) return;
								Map<?, ?> table = (Map<?, ?>) raw;
								out.put(id, table.size());
							});
							return out;
						}
					}
					""");

	/**
	 * Three Fabric mods: one naming classes under both keys; one whose only declaration is a class under a key no reader
	 * reads at all; one whose only declaration is a class under the key the two readers disagree on.
	 */
	private static void publishFabric() {
		DeclarationReaderModListInjectorTest.publishFabricMods("""
				{"schemaVersion":1,"id":"aurora-lights","version":"1.2.0","name":"Aurora Lights",
				 "entrypoints":{"prismlib:renderer":["fixture.aurora.AuroraRenderer"],
				                "prismlib:palette":["fixture.aurora.AuroraPalette"]}}
				""", """
				{"schemaVersion":1,"id":"drift-notes","version":"0.3.0",
				 "entrypoints":{"prismlib:changelog":["fixture.drift.Notes"],"client":["fixture.drift.Client"]}}
				""", """
				{"schemaVersion":1,"id":"ember-glass","version":"5.0.0",
				 "entrypoints":{"prismlib:renderer":["fixture.ember.GlassRenderer"]}}
				""");
	}

	@Test
	void aKeyOneReaderReadsAsANameAndAnotherAsATableIsGivenNoClassName() throws Throwable {
		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			// Both reader classes are defined (and so have said how they read) before either runs.
			ClassLoader library = library(game, Set.of(NAME_READER, TABLE_READER));
			nativeModList(game);
			publishFabric();

			Class<?> index = library.loadClass("fixture.prism.PrismRendererIndex");
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "renderers"),
					"prismlib:renderer is read as a name by one class and cast to a Map by another: no Fabric class "
							+ "name is offered under it");
			assertEquals(Map.of(), InjectorExecution.invokeStatic(library.loadClass("fixture.prism.PrismRendererTables"),
					"tables"), "so the class casting it to a Map never meets a String there");
			assertEquals(List.of("native_prism=fixture.neo.NativePalette", "aurora-lights=fixture.aurora.AuroraPalette"),
					InjectorExecution.invokeStatic(index, "palettes"),
					"prismlib:palette is read only as a name, by a plain cast: the Fabric mod's class is offered there");
		}
	}

	@Test
	void theNameIsWithdrawnOnceALaterReaderReadsTheKeyAsSomethingElse() throws Throwable {
		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			Map<String, byte[]> compiled = compiled();
			nativeModList(game);
			publishFabric();
			// Only the name reader is defined at first.
			ClassLoader first = load(game, compiled, Set.of(NAME_READER));
			Class<?> index = first.loadClass("fixture.prism.PrismRendererIndex");
			assertEquals(List.of("aurora-lights=fixture.aurora.AuroraRenderer", "ember-glass=fixture.ember.GlassRenderer"),
					InjectorExecution.invokeStatic(index, "renderers"), "a key read only as a name offers the names");

			// The table reader is defined later — a library loading its second reader class — and runs safely.
			ClassLoader second = load(game, compiled, Set.of(TABLE_READER));
			assertEquals(Map.of(), InjectorExecution.invokeStatic(second.loadClass("fixture.prism.PrismRendererTables"),
					"tables"));
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "renderers"),
					"from then on the first reader is not offered them either");
		}
	}

	@Test
	void aFabricModWhoseOnlyDeclarationsAreNamesNobodyIsOfferedIsInNoReadersList() throws Throwable {
		try (URLClassLoader game = DeclarationReaderModListInjectorTest.neoGameSide()) {
			ClassLoader library = library(game, Set.of(NAME_READER, TABLE_READER));
			nativeModList(game);
			publishFabric();

			Class<?> index = library.loadClass("fixture.prism.PrismRendererIndex");
			assertEquals(List.of("native_prism", "aurora-lights"), InjectorExecution.invokeStatic(index, "everyone"),
					"drift-notes names a class only under a key no reader reads; ember-glass only under a key no name is "
							+ "offered for. Neither declares anything a reader can see, so neither is added");
			for (String id : List.of("drift-notes", "drift_notes", "ember-glass", "ember_glass")) {
				assertEquals(false, InjectorExecution.invokeStatic(index, "knows", id), id + " is not found by id either");
			}
			assertEquals(true, InjectorExecution.invokeStatic(index, "knows", "aurora_lights"),
					"while the mod that does declare something is");
		}
	}

	// ---- fixtures ----

	private Map<String, byte[]> compiled() throws Exception {
		return InjectorExecution.compile(work, LIBRARY, List.of(DeclarationReaderModListInjectorTest.carrier()));
	}

	private ClassLoader library(ClassLoader game, Set<String> classes) throws Exception {
		return load(game, compiled(), classes);
	}

	/** Runs the injector over {@code classes} — which is when each says how it reads its keys — and defines them. */
	private static ClassLoader load(ClassLoader game, Map<String, byte[]> compiled, Set<String> classes) {
		DeclarationReaderModListInjector injector = new DeclarationReaderModListInjector();
		Map<String, byte[]> out = new TreeMap<>();
		for (String name : classes) {
			byte[] bytes = compiled.get(name);
			byte[] result = InjectorExecution.transform(injector, name.replace('/', '.'), bytes, EnvType.CLIENT);
			assertNotSame(bytes, result, name + " reads declarations out of ModList");
			out.put(name, result);
		}
		ClassLoader loader = InjectorExecution.load(out, game);
		for (Map.Entry<String, byte[]> entry : out.entrySet()) {
			assertEquals("", InjectorExecution.verify(entry.getValue(), loader), entry.getKey());
		}
		return loader;
	}

	/** One native NeoForge mod, declaring a palette class of its own. */
	private static void nativeModList(ClassLoader game) throws Exception {
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, game);
		Object list = modList.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
		DiscoveredMod declared = new DiscoveredMod(Ecosystem.NEOFORGE, "native_prism", "1.0.0", "Native Prism", List.of(),
				List.of(), null, null).withModProperties(Map.of("prismlib:palette", "fixture.neo.NativePalette"));
		Object info = Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, game)
				.getConstructor(String.class, Path.class, DiscoveredMod.class).newInstance("native_prism", null, declared);
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
}
