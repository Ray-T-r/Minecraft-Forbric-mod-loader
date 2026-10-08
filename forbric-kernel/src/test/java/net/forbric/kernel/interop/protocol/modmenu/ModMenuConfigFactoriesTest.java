/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.modmenu;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;

/**
 * Fabric mods' config screens are read from their {@code "modmenu"} entrypoints exactly as Mod Menu 20.0.3 reads them.
 *
 * <p>{@link Api} and {@link Factory} have the shape of Mod Menu's {@code ModMenuApi} and {@code ConfigScreenFactory}
 * with {@code Object} for {@code Screen}: the reader asks by method name, so the semantics are pinned here off-game,
 * and the real-client run is what pins the stand-in itself.
 */
@ResourceLock("ModCatalog")
@ResourceLock("KernelFabricEcosystem")
class ModMenuConfigFactoriesTest {
	@TempDir Path directory;
	static final AtomicInteger SCREENS_BUILT = new AtomicInteger();

	@BeforeEach @AfterEach void reset() {
		SCREENS_BUILT.set(0);
		CompatibilityFindings.reset();
	}

	/** Mod Menu's {@code ModMenuApi}, by shape. */
	public interface Api {
		default Factory getModConfigScreenFactory() {
			return parent -> null;
		}

		default Map<String, Factory> getProvidedConfigScreenFactories() {
			return Map.of();
		}
	}

	/** Mod Menu's {@code ConfigScreenFactory}, by shape. */
	@FunctionalInterface
	public interface Factory {
		Object create(Object parent);
	}

	/** A screen, as far as these tests need one: who built it, over what. */
	public record Screen(String by, Object parent) {
	}

	private static Factory building(String by) {
		return parent -> {
			SCREENS_BUILT.incrementAndGet();
			return new Screen(by, parent);
		};
	}

	public static final class OwnConfig implements Api {
		@Override public Factory getModConfigScreenFactory() { return building("own"); }
	}

	/** Xaero's library in the player's pack: an entrypoint that overrides nothing. */
	public static final class NoConfig implements Api {
	}

	public static final class Library implements Api {
		@Override public Map<String, Factory> getProvidedConfigScreenFactories() {
			return Map.of("own", building("library"), "dependent", building("library"));
		}
	}

	public static final class ThrowsOnConstruction implements Api {
		public ThrowsOnConstruction() { throw new IllegalStateException("its config library is missing"); }
	}

	public static final class ThrowsWhenAsked implements Api {
		@Override public Factory getModConfigScreenFactory() { throw new NoClassDefFoundError("dev/example/ConfigLib"); }
	}

	public static final class ProvidedThrows implements Api {
		@Override public Factory getModConfigScreenFactory() { return building("provided-throws"); }
		@Override public Map<String, Factory> getProvidedConfigScreenFactories() { throw new IllegalStateException("boom"); }
	}

	/** Overrides, and answers "no screen after all" — a conditional integration whose config library is absent. */
	public static final class NullFactory implements Api {
		@Override public Factory getModConfigScreenFactory() { return null; }
	}

	public static abstract class ConfigLibraryBase implements Api {
		@Override public Factory getModConfigScreenFactory() { return building("base"); }
	}

	public static final class ThroughABaseClass extends ConfigLibraryBase {
	}

	public static final class First implements Api {
		@Override public Factory getModConfigScreenFactory() { return building("first"); }
	}

	public static final class Second implements Api {
		@Override public Factory getModConfigScreenFactory() { return building("second"); }
	}

	public static final class NotAnApi {
	}

	/**
	 * Overrides, and falls back to the interface's default — what ForgeConfigAPIPort's entrypoint does outside a
	 * development environment, and several mods do when cloth-config is absent. That is Mod Menu's "no screen".
	 */
	public static final class FallsBackToTheDefault implements Api {
		@Override public Factory getModConfigScreenFactory() { return Api.super.getModConfigScreenFactory(); }
	}

	/** A config library that offers a screen for a mod only once that mod has registered with it, later on. */
	public static final class LateLibrary implements Api {
		static final Map<String, Factory> OFFERED = new java.util.concurrent.ConcurrentHashMap<>();
		@Override public Map<String, Factory> getProvidedConfigScreenFactories() { return Map.copyOf(OFFERED); }
	}

	@Test void aModsOwnFactoryGivesItAConfigAndBuildsItsScreen() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "own", OwnConfig.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);

		assertTrue(read.has("own"));
		Object parent = new Object();
		assertEquals(new Screen("own", parent), read.create("own", parent));
		assertFalse(read.has("absent"));
		assertNull(read.create("absent", parent));
	}

	/** The interface's default produces no screen, so it is not a config — no Config button that opens nothing. */
	@Test void theDefaultFactoryIsNotAConfig() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "xaerolib", NoConfig.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);

		assertFalse(read.has("xaerolib"));
		assertEquals(1, read.entrypoints());
		assertEquals(0, read.broken());
	}

	/** Under Mod Menu an instance of the default factory is skipped, override or not: no button that opens nothing. */
	@Test void anOverrideThatReturnsTheDefaultIsNotAConfigAndALibraryMayFillIt() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "fallsback", FallsBackToTheDefault.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);
		assertFalse(read.has("fallsback"), "the interface's default, returned by an override, is still the default");

		KernelFabricLoader withLibrary = loader();
		register(withLibrary, "fallsback", FallsBackToTheDefault.class);
		LateLibrary.OFFERED.put("fallsback", building("library"));
		try {
			register(withLibrary, "configlib", LateLibrary.class);
			ModMenuConfigFactories filled = ModMenuConfigFactories.read(withLibrary, Api.class);
			assertEquals(new Screen("library", null), filled.create("fallsback", null),
					"a default stored for the mod does not keep a library's screen out, as under Mod Menu");
		} finally {
			LateLibrary.OFFERED.clear();
		}
	}

	/** Mod Menu merges the provided factories on every lookup, so one registered after the first question counts. */
	@Test void aFactoryALibraryProvidesLaterIsFound() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "configlib", LateLibrary.class);
		try {
			ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);
			assertFalse(read.has("dependent"));
			LateLibrary.OFFERED.put("dependent", building("late"));
			assertTrue(read.has("dependent"), "asked again after the library registered it");
			assertEquals(new Screen("late", "p"), read.create("dependent", "p"));
		} finally {
			LateLibrary.OFFERED.clear();
		}
	}

	/**
	 * A library's provided factories fill in only what no mod claimed for itself, and they are merged AFTER every
	 * mod's own — so declaration order does not decide it: the library here is declared first and still loses.
	 */
	@Test void providedFactoriesFillOnlyWhatNoModClaimsItself() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "configlib", Library.class);
		register(loader, "own", OwnConfig.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);

		assertEquals(new Screen("own", null), read.create("own", null), "the mod's own factory wins");
		assertEquals(new Screen("library", null), read.create("dependent", null), "the library supplies the rest");
		assertFalse(read.has("configlib"), "the library itself overrides nothing");
	}

	@Test void aBrokenEntrypointIsSkippedAndCostsNoOtherMod() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "ctor", ThrowsOnConstruction.class);
		register(loader, "asked", ThrowsWhenAsked.class);
		register(loader, "providedthrows", ProvidedThrows.class);
		register(loader, "own", OwnConfig.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);

		assertFalse(read.has("ctor"));
		assertFalse(read.has("asked"));
		assertTrue(read.has("providedthrows"), "its own factory was read before its provided map threw");
		assertTrue(read.has("own"));
		assertEquals(4, read.entrypoints());
		assertEquals(3, read.broken());
	}

	/** Asking whether a mod has a config must not run the mod's screen code: it is asked on every selection change. */
	@Test void readingAndAskingBuildNoScreen() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "own", OwnConfig.class);
		register(loader, "configlib", Library.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);
		for (String id : List.of("own", "dependent", "configlib")) read.has(id);

		assertEquals(0, SCREENS_BUILT.get());
	}

	/** Under Mod Menu, an overriding entrypoint that returns null means no screen — and a library may then fill it. */
	@Test void aNullFactoryIsNoConfigAndALibraryMayStillProvideOne() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "dependent", NullFactory.class);
		assertFalse(ModMenuConfigFactories.read(loader, Api.class).has("dependent"));

		register(loader, "configlib", Library.class);
		assertEquals(new Screen("library", null), ModMenuConfigFactories.read(loader, Api.class).create("dependent", null));
	}

	@Test void anOverrideInheritedFromAConfigLibrarysBaseClassCounts() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "viabase", ThroughABaseClass.class);
		assertEquals(new Screen("base", null), ModMenuConfigFactories.read(loader, Api.class).create("viabase", null));
	}

	/** {@code put}, as under Mod Menu: a mod declaring two entrypoints ends with the last one's factory. */
	@Test void aModsLastEntrypointWins() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "twice", First.class.getName(), Second.class.getName());
		assertEquals(new Screen("second", null), ModMenuConfigFactories.read(loader, Api.class).create("twice", null));
	}

	/** A {@code "modmenu"} entrypoint of another type is not read, exactly as Mod Menu's typed lookup skips it. */
	@Test void anEntrypointOfAnotherTypeIsNotRead() throws Exception {
		KernelFabricLoader loader = loader();
		register(loader, "other", NotAnApi.class);
		ModMenuConfigFactories read = ModMenuConfigFactories.read(loader, Api.class);
		assertEquals(0, read.entrypoints());
		assertFalse(read.has("other"));
	}

	private KernelFabricLoader loader() throws Exception {
		var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
		constructor.setAccessible(true);
		KernelFabricLoader loader = constructor.newInstance(EnvType.CLIENT, directory, directory.resolve("config"), new String[0], "26.2");
		Field gameLoader = KernelFabricLoader.class.getDeclaredField("gameLoader");
		gameLoader.setAccessible(true);
		gameLoader.set(loader, getClass().getClassLoader());
		return loader;
	}

	private static void register(KernelFabricLoader loader, String id, Class<?> entrypoint) {
		register(loader, id, entrypoint.getName());
	}

	private static void register(KernelFabricLoader loader, String id, String... entrypoints) {
		String values = String.join("\",\"", entrypoints);
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"modmenu\":[\"" + values + "\"]}}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}
}
