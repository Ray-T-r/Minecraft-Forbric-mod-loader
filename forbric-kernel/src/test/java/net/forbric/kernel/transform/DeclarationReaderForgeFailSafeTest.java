/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
import net.forbric.api.ForgeLoadingList;
import net.forbric.api.ModPresence;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;

/**
 * The MinecraftForge twin of {@link DeclarationReaderFailSafeTest} and {@link DeclarationReaderNameOfferTest}, against the
 * real static {@code ModList}: a hook that cannot add the Fabric mods hands the reader MinecraftForge's own answer; the
 * reader's callback that cannot handle a Fabric mod's bus-less {@code LowCodeModContainer} leaves that mod out; and a
 * Fabric mod that offers the reader nothing is not in its list. The library ({@code kilnlib}) is made up.
 */
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("ForgeLoadingList")
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ExecutesInjector(DeclarationReaderModListInjector.class)
class DeclarationReaderForgeFailSafeTest {
	private static final String HOLDER = "net.minecraftforge.fml.loading.LoadingModListImpl$1LazyInit";
	private static final String READER = "fixture.kiln.KilnGlazeIndex";

	@TempDir
	Path work;

	@BeforeEach
	@AfterEach
	void reset() throws Exception {
		ModPresence.publishFabric(List.of());
		CrossEcosystemDeclarations.resetForTests();
		CompatibilityFindings.reset();
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		Method reset = ForgeLoadingList.class.getDeclaredMethod("reset");
		reset.setAccessible(true);
		reset.invoke(null);
	}

	private static final Map<String, String> LIBRARY = Map.of(
			"fixture.kiln.KilnGlazeIndex", """
					package fixture.kiln;
					import java.util.*;
					import java.util.stream.*;
					import net.minecraftforge.fml.ModContainer;
					import net.minecraftforge.fml.ModList;
					import net.minecraftforge.forgespi.language.IModInfo;
					public final class KilnGlazeIndex {
						private static boolean declares(ModContainer container) {
							return container.getModInfo().getModProperties().get("kilnlib:glaze") != null;
						}
						private static String wire(ModContainer container) {
							if (container.getModBusGroup() == null) throw new IllegalStateException(container.getModId() + " has no bus group");
							return container.getModId();
						}
						public static List<String> glazes() {
							List<String> out = new ArrayList<>();
							for (IModInfo info : ModList.getMods()) if (info.getModProperties().containsKey("kilnlib:glaze")) out.add(info.getModId());
							return out;
						}
						public static List<String> everyone() {
							List<String> out = new ArrayList<>();
							for (ModContainer container : ModList.getLoadedMods()) out.add(container.getModId());
							return out;
						}
						public static boolean knows(String id) {
							return ModList.getModContainerById(id).isPresent();
						}
						public static boolean file(String id) {
							return ModList.getModFileById(id) != null;
						}
						public static List<String> each() {
							List<String> out = new ArrayList<>();
							ModList.forEachModContainer((id, container) -> out.add(id));
							return out;
						}
						public static List<String> inOrder() {
							List<String> out = new ArrayList<>();
							ModList.forEachModInOrder(container -> out.add(container.getModId()));
							return out;
						}
						public static List<String> applied() {
							return ModList.applyForEachModContainer(ModContainer::getModId).collect(Collectors.toList());
						}
						public static List<String> wiredEach() {
							List<String> out = new ArrayList<>();
							ModList.forEachModContainer((id, container) -> { if (declares(container)) out.add(wire(container)); });
							return out;
						}
						public static List<String> wiredApplied() {
							return ModList.applyForEachModContainer(c -> declares(c) ? wire(c) : null)
									.filter(Objects::nonNull).collect(Collectors.toList());
						}
					}
					""");

	@Test
	void aHookThatCannotAddTheFabricModsHandsTheReaderMinecraftForgesOwnAnswer() throws Throwable {
		try (URLClassLoader game = forgeGameSide()) {
			Class<?> index = reader(game);
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"slip-tiles","version":"1.0.0","custom":{"kilnlib:glaze":"celadon"}}
					""");
			List<DiscoveredMod> fabric = new ArrayList<>(ModPresence.fabricMods());
			fabric.add(new DiscoveredMod(Ecosystem.FABRIC, "murky-tiles", "1.0.0", "Murky Tiles", List.of(), List.of(),
					null, null));
			ModPresence.publishFabric(fabric);
			CrossEcosystemDeclarations.publishFabricEntrypointNames(
					Map.of("murky-tiles", new DeclarationReaderFailSafeTest.UnreadableTable()));

			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "glazes"));
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "everyone"));
			assertEquals(false, InjectorExecution.invokeStatic(index, "knows", "slip-tiles"));
			assertEquals(false, InjectorExecution.invokeStatic(index, "file", "slip-tiles"));
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "each"));
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "inOrder"));
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "applied"));
			assertEquals(Set.of("getMods", "getLoadedMods", "getModContainerById", "getModFileById", "forEachModContainer",
					"forEachModInOrder", "applyForEachModContainer"), suspectedHooks());

			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"slip-tiles","version":"1.0.0","custom":{"kilnlib:glaze":"celadon"}}
					""");
			assertEquals(List.of("slip-tiles"), InjectorExecution.invokeStatic(index, "glazes"),
					"once the declarations read again, the same reader meets the declaring mod");
		}
	}

	@Test
	void aCallbackThatCannotHandleAFabricModsContainerLeavesThatModOut() throws Throwable {
		try (URLClassLoader game = forgeGameSide()) {
			Class<?> index = reader(game);
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"slip-tiles","version":"1.0.0","custom":{"kilnlib:glaze":"celadon"}}
					""");
			assertEquals(List.of("slip-tiles"), InjectorExecution.invokeStatic(index, "each"));
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "wiredEach"),
					"the reader wants a bus group a LowCodeModContainer does not have: that mod is skipped");
			assertEquals(List.of(), InjectorExecution.invokeStatic(index, "wiredApplied"));
			assertEquals(Set.of("forEachModContainer", "applyForEachModContainer"), suspectedHooks());
		}
	}

	@Test
	void aFabricModWhoseOnlyDeclarationsAreNamesNobodyIsOfferedIsInNoReadersList() throws Throwable {
		try (URLClassLoader game = forgeGameSide()) {
			Class<?> index = reader(game);
			DeclarationReaderModListInjectorTest.publishFabricMods("""
					{"schemaVersion":1,"id":"slip-tiles","version":"1.0.0","custom":{"kilnlib:glaze":"celadon"}}
					""", """
					{"schemaVersion":1,"id":"quiet-kiln","version":"2.0.0",
					 "entrypoints":{"kilnlib:firing":["fixture.quiet.Firing"],"main":["fixture.quiet.Main"]}}
					""");
			assertEquals(List.of("slip-tiles"), InjectorExecution.invokeStatic(index, "everyone"),
					"quiet-kiln names a class only under a key no reader reads by name: it declares nothing here");
			assertEquals(false, InjectorExecution.invokeStatic(index, "knows", "quiet-kiln"));
			assertEquals(false, InjectorExecution.invokeStatic(index, "knows", "quiet_kiln"));
			assertEquals(true, InjectorExecution.invokeStatic(index, "knows", "slip_tiles"));
			assertEquals(Set.of(), suspectedHooks(), "and nothing failed");
		}
	}

	// ---- fixtures ----

	private static Set<String> suspectedHooks() {
		Set<String> hooks = new TreeSet<>();
		String prefix = "declaration-readers:";
		for (CompatibilityFinding finding : CompatibilityFindings.suspected()) {
			if (!finding.id().startsWith(prefix) || !finding.id().endsWith(":" + READER)) continue;
			hooks.add(finding.id().substring(prefix.length(), finding.id().length() - READER.length() - 1));
		}
		return hooks;
	}

	/** Compiles and rewrites the reader, then publishes an empty MinecraftForge list — no MinecraftForge mod at all. */
	private Class<?> reader(URLClassLoader game) throws Exception {
		Path forge = forgeCarrier();
		Map<String, byte[]> compiled = InjectorExecution.compile(work, LIBRARY, List.of(forge));
		Map<String, byte[]> out = new TreeMap<>();
		for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
			out.put(entry.getKey(), InjectorExecution.transform(new DeclarationReaderModListInjector(),
					entry.getKey().replace('/', '.'), entry.getValue(), EnvType.SERVER));
		}
		String internal = READER.replace('.', '/');
		assertNotSame(compiled.get(internal), out.get(internal));
		ForgeLoadingList.publish(List.of(), List.of());
		Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", true, game);
		Method setLoaded = modList.getDeclaredMethod("setLoadedMods", List.class);
		setLoaded.setAccessible(true);
		setLoaded.invoke(null, List.of());
		ClassLoader library = InjectorExecution.load(out, game);
		assertEquals("", InjectorExecution.verify(out.get(internal), library));
		return library.loadClass(READER);
	}

	private static Path forgeCarrier() {
		Path forge = TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(forge), "the staged MinecraftForge carrier is absent");
		return forge;
	}

	/** As {@code DeclarationReaderForgeModListTest}: both carriers, the merged base, and the lazy holder rewritten. */
	private static URLClassLoader forgeGameSide() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path run = TestFixtures.stagedRoot();
		Path neo = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(neo) && Files.isRegularFile(merged),
				"the staged artifacts are absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game side is not compiled");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forgeCarrier().toUri().toURL(),
				neo.toUri().toURL(), merged.toUri().toURL()));
		for (String library : List.of("com/mojang/logging", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				"com/google/guava/guava", "it/unimi/dsi/fastutil", "org/apache/commons/commons-lang3")) {
			Path jar = newestUnder(library);
			TestFixtures.require(Fixture.MC_LIBRARIES, jar != null, "Minecraft's " + library + " library is absent");
			urls.add(jar.toUri().toURL());
		}
		ForgeLoadingListHolderInjector holder = new ForgeLoadingListHolderInjector();
		return new URLClassLoader(urls.toArray(URL[]::new), DeclarationReaderForgeFailSafeTest.class.getClassLoader()) {
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
