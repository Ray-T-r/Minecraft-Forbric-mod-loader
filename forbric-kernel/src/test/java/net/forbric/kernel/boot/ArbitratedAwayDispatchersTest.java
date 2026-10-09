/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.fabric.DispatchFixtures;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Phase;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;

/**
 * A library installed as a Fabric build and a NeoForge build, the NeoForge one loaded: the Fabric build was the only
 * code that dispatched its custom entrypoint key, so the kernel must dispatch it in its place — and must not when the
 * library is absent, when its Fabric build is the one loaded, or when the loaded build dispatches the key itself.
 *
 * <p>The library ("nebula") and its consumer ("orbit") exist nowhere; the library dispatches from {@code main} with a
 * key held in a {@code static final} and a local, through {@code getEntrypoints} and a plain loop.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ResourceLock("KernelFabricEcosystem")
class ArbitratedAwayDispatchersTest {
	private static final String KEY = "nebula:warmup";
	private static final String HOOK = "io/nebula/api/WarmupHook";
	private static final String CONSUMER = "org/orbit/OrbitWarmup";

	@TempDir Path dir;

	@BeforeEach @AfterEach void reset() {
		ArbitratedAwayDispatchers.reset();
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		CompatibilityFindings.reset();
		ModCatalog.publish(List.of());
	}

	/** The library's Fabric build: the hook type and the code that dispatches it. */
	private static Map<String, byte[]> fabricBuild() {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("fabric.mod.json", DispatchFixtures.fabricModJson("nebula", Map.of("main", List.of("io.nebula.fabric.NebulaInit"))));
		entries.put(HOOK, DispatchFixtures.contract(HOOK, "warmUp()V"));
		entries.put("io/nebula/fabric/NebulaInit", DispatchFixtures.mainLoopDispatcher("io/nebula/fabric/NebulaInit", KEY, HOOK, "warmUp"));
		return entries;
	}

	/** The library's NeoForge build: no hook type, no Fabric query. */
	private static Map<String, byte[]> neoForgeBuild() {
		return Map.of("META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml("nebula"),
				"io/nebula/neoforge/NebulaMod", DispatchFixtures.plain("io/nebula/neoforge/NebulaMod"));
	}

	private Path consumerJar() throws Exception {
		return DispatchFixtures.write(dir.resolve("orbit.jar"), Map.of(
				"fabric.mod.json", DispatchFixtures.fabricModJson("orbit", Map.of(KEY, List.of(CONSUMER.replace('/', '.')))),
				CONSUMER, DispatchFixtures.consumer(CONSUMER, HOOK, "warmUp")));
	}

	@Test void theLosingBuildsKeyIsDispatchedInItsPlaceThroughItsOwnTypeAndMethodInItsOwnPhase() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), fabricBuild());
		Path winning = DispatchFixtures.write(dir.resolve("nebula-neoforge.jar"), neoForgeBuild());
		Path consumer = consumerJar();

		try (ForbricClassLoader game = new ForbricClassLoader(new URL[] {winning.toUri().toURL(), consumer.toUri().toURL()},
				getClass().getClassLoader())) {
			game.setRescueJars(List.of(losing.toUri().toURL()));
			KernelFabricLoader fabric = loader(game, "orbit", KEY, CONSUMER);
			withFabric(fabric, () -> {
				var orphans = ArbitratedAwayDispatchers.record(decision(losing, winning), KernelFabricEcosystem.declaredEntrypointKeys());
				assertEquals(1, orphans.size(), orphans::toString);
				assertEquals("nebula", orphans.getFirst().library());
				assertEquals(KEY, orphans.getFirst().key());

				assertEquals(0, KernelFabricEcosystem.dispatchArbitratedAwayKeys(Phase.PRE_INIT), "its build dispatched it from main");
				assertEquals(0, calls(game));
				assertEquals(1, KernelFabricEcosystem.dispatchArbitratedAwayKeys(Phase.MAIN));
				assertEquals(1, calls(game));
				assertEquals(0, KernelFabricEcosystem.dispatchArbitratedAwayKeys(Phase.MAIN), "once per boot, as the library did");
				assertEquals(1, calls(game));
			});
			// The consumer links against the hook type the NeoForge build does not have: the losing build lends it.
			assertNull(game.getResource(HOOK + ".class"), "no loaded jar has the hook type");
			assertNotNull(game.loadClass(HOOK.replace('/', '.')));
		}
	}

	@Test void aLibraryThatIsNotInstalledLeavesItsConsumersKeyUndispatched() throws Exception {
		Path consumer = consumerJar();
		try (ForbricClassLoader game = new ForbricClassLoader(new URL[] {consumer.toUri().toURL()}, getClass().getClassLoader())) {
			KernelFabricLoader fabric = loader(game, "orbit", KEY, CONSUMER);
			withFabric(fabric, () -> {
				DuplicateModArbiter.Decision nothingArbitrated = new DuplicateModArbiter.Decision(Set.of(), Map.of(), List.of());
				assertTrue(ArbitratedAwayDispatchers.record(nothingArbitrated, KernelFabricEcosystem.declaredEntrypointKeys()).isEmpty());
				for (Phase phase : Phase.values()) assertEquals(0, KernelFabricEcosystem.dispatchArbitratedAwayKeys(phase));
			});
		}
	}

	@Test void whenTheFabricBuildIsTheOneLoadedTheKernelDispatchesNothing() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("nebula-neoforge.jar"), neoForgeBuild());
		Path winning = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), fabricBuild());
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("nebula", winning), Set.of(KEY)).isEmpty(),
				"the loaded Fabric build dispatches the key itself");
	}

	@Test void aWinningBuildThatDispatchesTheKeyItselfLostNothing() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), fabricBuild());
		Map<String, byte[]> universal = new LinkedHashMap<>(neoForgeBuild());
		universal.put("io/nebula/common/Bridge", DispatchFixtures.directDispatcher("io/nebula/common/Bridge", KEY, HOOK));
		Path winning = DispatchFixtures.write(dir.resolve("nebula-neoforge.jar"), universal);
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("nebula", winning), Set.of(KEY)).isEmpty());
	}

	/** A winner reading the same declaration through its own platform's metadata, under the same name, lost nothing. */
	@Test void aWinningBuildThatReadsTheKeyThroughAnotherApiLostNothing() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), fabricBuild());
		Map<String, byte[]> bundled = Map.of("io/nebula/neoforge/PropertyReader",
				DispatchFixtures.propertyReader("io/nebula/neoforge/PropertyReader", KEY));
		Map<String, byte[]> wrapper = new LinkedHashMap<>(neoForgeBuild());
		wrapper.put("META-INF/jarjar/nebula-impl.jar", DispatchFixtures.jar(bundled));
		Path winning = DispatchFixtures.write(dir.resolve("nebula-neoforge.jar"), wrapper);
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("nebula", winning), Set.of(KEY)).isEmpty(),
				"the key is a constant of a class the winning build bundles");
	}

	/** Every build names its own mod id; a key spelled the same is still lost unless the winner queries it. */
	@Test void aKeySpelledLikeTheLibrarysOwnIdIsLostEvenThoughTheWinnerNamesItsId() throws Exception {
		String hook = "dev/zephyr/api/ZephyrSetup";
		Map<String, byte[]> fabric = new LinkedHashMap<>();
		fabric.put("fabric.mod.json", DispatchFixtures.fabricModJson("zephyr", Map.of("client", List.of("dev.zephyr.ZephyrClient"))));
		fabric.put(hook, DispatchFixtures.contract(hook, "setUp()V"));
		fabric.put("dev/zephyr/ZephyrClient", DispatchFixtures.directDispatcher("dev/zephyr/ZephyrClient", "zephyr", hook,
				"net/fabricmc/api/ClientModInitializer", "onInitializeClient"));
		Path losing = DispatchFixtures.write(dir.resolve("zephyr-fabric.jar"), fabric);
		Path winning = DispatchFixtures.write(dir.resolve("zephyr-neoforge.jar"), Map.of(
				"META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml("zephyr"),
				"dev/zephyr/neoforge/ZephyrMod", DispatchFixtures.propertyReader("dev/zephyr/neoforge/ZephyrMod", "zephyr")));

		var orphans = ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("zephyr", winning), Set.of("zephyr"));
		assertEquals(1, orphans.size(), orphans::toString);
		assertEquals("setUp", orphans.getFirst().dispatch().method());
		assertEquals(Set.of(Phase.CLIENT), orphans.getFirst().dispatch().phases());
	}

	@Test void aLosingBuildWithoutAWinnerOfItsIdIsNotEvidenceTheLibraryIsInstalled() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), fabricBuild());
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("someoneelse", losing), Set.of(KEY)).isEmpty());
	}

	@Test void aKeyNoLoadedModDeclaresIsNotWorkedOut() throws Exception {
		Path losing = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), fabricBuild());
		Path winning = DispatchFixtures.write(dir.resolve("nebula-neoforge.jar"), neoForgeBuild());
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("nebula", winning), Set.of("main", "client")).isEmpty());
	}

	@Test void aDispatchTheKernelCannotDeriveIsNamedNotGuessed() throws Exception {
		Map<String, byte[]> deferred = new LinkedHashMap<>(fabricBuild());
		deferred.put("io/nebula/fabric/NebulaInit", DispatchFixtures.callbackDispatcher("io/nebula/fabric/NebulaInit", KEY, HOOK));
		Path losing = DispatchFixtures.write(dir.resolve("nebula-fabric.jar"), deferred);
		Path winning = DispatchFixtures.write(dir.resolve("nebula-neoforge.jar"), neoForgeBuild());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(losing), Map.of("nebula", winning), Set.of(KEY)).isEmpty());
		var finding = CompatibilityFindings.all().stream().filter(f -> f.id().equals("arbitration:entrypoint:" + KEY)).findFirst();
		assertTrue(finding.isPresent(), CompatibilityFindings.all()::toString);
		assertEquals("nebula", finding.get().modId());
		assertFalse(finding.get().required());
	}

	/**
	 * The precondition comes from arbitration itself: the losing build is the nested Fabric copy a Fabric consumer
	 * bundles, superseded by the NeoForge build installed at top level.
	 */
	@Test void theLosingBuildAndItsWinnerAreTheOnesArbitrationChose() throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		DispatchFixtures.write(mods.resolve("nebula-neoforge.jar"), neoForgeBuild());
		DispatchFixtures.write(mods.resolve("orbit-fabric.jar"), Map.of(
				"fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"orbit\",\"version\":\"1.0.0\",\"jars\":[{\"file\":"
						+ "\"META-INF/jars/nebula-fabric.jar\"}],\"entrypoints\":{\"" + KEY + "\":[\"org.orbit.OrbitWarmup\"]}}")
						.getBytes(StandardCharsets.UTF_8),
				"META-INF/jars/nebula-fabric.jar", DispatchFixtures.jar(fabricBuild()),
				CONSUMER, DispatchFixtures.consumer(CONSUMER, HOOK, "warmUp")));

		DuplicateModArbiter.Decision decision = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);
		var orphans = ArbitratedAwayDispatchers.derive(decision.rescueJars(), decision.ownerByModId(), Set.of(KEY));
		assertEquals(1, orphans.size(), () -> decision + " " + orphans);
		assertEquals(Set.of(Phase.MAIN), orphans.getFirst().dispatch().phases());

		// Without the NeoForge build the bundled Fabric build is the one loaded, and dispatches its key itself.
		Files.delete(mods.resolve("nebula-neoforge.jar"));
		DuplicateModArbiter.reset();
		DuplicateModArbiter.Decision alone = DuplicateModArbiter.arbitrate(mods, EnvType.CLIENT);
		assertTrue(ArbitratedAwayDispatchers.derive(alone.rescueJars(), alone.ownerByModId(), Set.of(KEY)).isEmpty());
	}

	private static DuplicateModArbiter.Decision decision(Path losing, Path winning) {
		return new DuplicateModArbiter.Decision(Set.of(losing), Map.of("nebula", winning), List.of(), Set.of(losing));
	}

	private static int calls(ClassLoader game) throws Exception {
		return game.loadClass(CONSUMER.replace('/', '.')).getField("CALLS").getInt(null);
	}

	private KernelFabricLoader loader(ClassLoader game, String id, String key, String entrypoint) throws Exception {
		var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
		constructor.setAccessible(true);
		KernelFabricLoader loader = constructor.newInstance(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(game);
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"" + key + "\":[\""
				+ entrypoint.replace('/', '.') + "\"]}}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
		loader.freeze();
		return loader;
	}

	private interface Body {
		void run() throws Exception;
	}

	private static void withFabric(KernelFabricLoader fabric, Body body) throws Exception {
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		String shim = System.getProperty(KernelForeignShimContext.SWITCH);
		try {
			active.set(null, fabric);
			System.setProperty(KernelForeignShimContext.SWITCH, "off");
			body.run();
		} finally {
			active.set(null, previous);
			if (shim == null) System.clearProperty(KernelForeignShimContext.SWITCH); else System.setProperty(KernelForeignShimContext.SWITCH, shim);
		}
	}
}
