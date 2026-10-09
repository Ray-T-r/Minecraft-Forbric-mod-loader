/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.fabric.EntrypointDispatchScan;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Dispatch;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Phase;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;

/**
 * The case that was measured: Cherished Worlds' Fabric build bundles SpectreLib's Fabric build, Comforts' NeoForge
 * build bundles SpectreLib's NeoForge build, and arbitration loads the NeoForge one. Cherished Worlds then declares a
 * {@code spectrelib-config} entrypoint nobody calls, and its first config read throws "Cannot get config value
 * before config is loaded". Nothing here names SpectreLib to the kernel: the jars say it all.
 */
@ResourceLock("ModCatalog")
@ResourceLock("KernelFabricEcosystem")
class ArbitratedAwayDispatchersRealJarTest {
	private static final Path MODS = Path.of("build/sweep100-mac-network/mods");
	private static final Path CONSUMER = MODS.resolve("cherishedworlds-fabric-17.0.0+26.2.jar");
	private static final Path NEO_HOST = MODS.resolve("comforts-neoforge-16.0.0+26.2.jar");
	private static final String CONTRACT = "com/illusivesoulworks/spectrelib/config/SpectreConfigInitializer";

	@TempDir Path dir;

	@BeforeEach @AfterEach void reset() {
		ArbitratedAwayDispatchers.reset();
		CompatibilityFindings.reset();
	}

	private Path nested(Path host, String entry, String as) throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(host), "sweep100 mod fixture absent: " + host);
		try (ZipFile zip = new ZipFile(host.toFile()); InputStream in = zip.getInputStream(zip.getEntry(entry))) {
			Path out = dir.resolve(as);
			Files.write(out, in.readAllBytes());
			return out;
		}
	}

	private Path fabricBuild() throws Exception {
		return nested(CONSUMER, "META-INF/jars/spectrelib-fabric-0.22.0+26.2.jar", "spectrelib-fabric.jar");
	}

	private Path neoForgeBuild() throws Exception {
		return nested(NEO_HOST, "META-INF/jarjar/spectrelib-neoforge-0.22.0+26.2.jar", "spectrelib-neoforge.jar");
	}

	@Test void theFabricBuildsOwnCodeNamesTheKeyTheTypeTheMethodAndThePhase() throws Exception {
		Dispatch config = EntrypointDispatchScan.scan(fabricBuild()).stream()
				.filter(dispatch -> dispatch.key().equals("spectrelib-config")).findFirst().orElseThrow();
		assertEquals(CONTRACT, config.type());
		assertTrue(config.ownType());
		assertEquals("onInitializeConfig", config.method());
		// Its preLaunch reaches the dispatch through EntrypointUtils.invokeEntrypoints(String, Class, Consumer).
		assertTrue(config.phases().contains(Phase.PRE_INIT), config::toString);
		assertEquals(Phase.PRE_INIT, Phase.earliest(config.phases(), true));
		assertEquals(Phase.PRE_INIT, Phase.earliest(config.phases(), false));

		assertTrue(EntrypointDispatchScan.scan(neoForgeBuild()).isEmpty(), "the NeoForge build reads no Fabric key");
	}

	@Test void onlyWhenTheNeoForgeBuildWonIsTheKeyTheKernelsToDispatch() throws Exception {
		Path fabric = fabricBuild(), neo = neoForgeBuild();
		Set<String> declared = Set.of("client", "spectrelib-config");

		var orphans = ArbitratedAwayDispatchers.derive(Set.of(fabric), Map.of("spectrelib", neo), declared);
		assertEquals(List.of("spectrelib-config"), orphans.stream().map(ArbitratedAwayDispatchers.Orphan::key).toList());
		assertEquals("spectrelib", orphans.getFirst().library());

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(neo), Map.of("spectrelib", fabric), declared).isEmpty(),
				"the Fabric build loaded: it dispatches the key itself");
		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(), Map.of(), declared).isEmpty(), "SpectreLib not installed");
	}

	/**
	 * The look-alike: Sodium's Fabric build also dispatches a key of its own type ({@code sodium:config_api_user}), but
	 * its NeoForge build reads the same declaration as a {@code [modproperties]} entry — nothing was lost, so nothing
	 * is dispatched and nothing is reported.
	 */
	@Test void aWinnerThatReadsTheSameDeclarationItsOwnWayLostNothing() throws Exception {
		Path fabric = MODS.resolve("sodium-fabric-0.9.2+mc26.2.jar"), neo = MODS.resolve("sodium-neoforge-0.9.2+mc26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(fabric) && Files.isRegularFile(neo), "sweep100 Sodium builds absent");
		assertTrue(EntrypointDispatchScan.scan(fabric).stream().anyMatch(d -> d.key().equals("sodium:config_api_user") && d.ownType()),
				"the losing build does dispatch it");

		assertTrue(ArbitratedAwayDispatchers.derive(Set.of(fabric), Map.of("sodium", neo), Set.of("sodium:config_api_user")).isEmpty());
		assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
	}

	/** The consumer links against the type only the losing build has, and is of the type the dispatch asks for. */
	@Test void theRealConsumerIsAnEntrypointOfTheLosingBuildsTypeThroughTheRescuedClass() throws Exception {
		Path fabric = fabricBuild(), neo = neoForgeBuild();
		try (ForbricClassLoader game = new ForbricClassLoader(new URL[] {neo.toUri().toURL(), CONSUMER.toUri().toURL()},
				getClass().getClassLoader())) {
			game.setRescueJars(List.of(fabric.toUri().toURL()));
			var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
			constructor.setAccessible(true);
			KernelFabricLoader loader = constructor.newInstance(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
			loader.setGameLoader(game);
			try (ZipFile zip = new ZipFile(CONSUMER.toFile()); InputStream in = zip.getInputStream(zip.getEntry("fabric.mod.json"))) {
				loader.register(new KernelModContainer(FabricModMetadataParser.read(in), null, null));
			}
			loader.freeze();

			Class<?> contract = Class.forName(CONTRACT.replace('/', '.'), false, loader.entrypointLoader());
			var containers = loader.getEntrypointContainers("spectrelib-config", contract);
			assertEquals(1, containers.size());
			Object entrypoint = containers.getFirst().getEntrypoint();
			assertEquals("com.illusivesoulworks.cherishedworlds.CherishedWorldsConfigInitializer", entrypoint.getClass().getName());
			assertTrue(contract.isInstance(entrypoint));
			assertNotNull(contract.getMethod("onInitializeConfig"));
		}
	}
}
