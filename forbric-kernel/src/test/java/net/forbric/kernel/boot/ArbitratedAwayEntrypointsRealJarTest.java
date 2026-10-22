/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The measured case: Cherished Worlds' Fabric build bundles SpectreLib's Fabric build, Comforts' NeoForge build bundles
 * SpectreLib's NeoForge build, and the NeoForge one loads. SpectreLib's Fabric build declares a {@code modmenu} plugin
 * (config screens for every mod whose configs SpectreLib manages) that the NeoForge build cannot offer Mod Menu. Nothing
 * names SpectreLib to the kernel: the two jars decide it.
 */
class ArbitratedAwayEntrypointsRealJarTest {
	private static final Path MODS = Path.of("build/sweep100-mac-network/mods");
	private static final Path CONSUMER = MODS.resolve("cherishedworlds-fabric-17.0.0+26.2.jar");
	private static final Path NEO_HOST = MODS.resolve("comforts-neoforge-16.0.0+26.2.jar");

	@TempDir Path dir;

	private Path nested(Path host, String entry, String as) throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(host), "sweep100 mod fixture absent: " + host);
		try (ZipFile zip = new ZipFile(host.toFile()); InputStream in = zip.getInputStream(zip.getEntry(entry))) {
			Path out = dir.resolve(as);
			Files.write(out, in.readAllBytes());
			return out;
		}
	}

	@Test void theModMenuPluginIsKeptAndTheLifecycleEntrypointsAreNot() throws Exception {
		Path fabric = nested(CONSUMER, "META-INF/jars/spectrelib-fabric-0.22.0+26.2.jar", "spectrelib-fabric.jar");
		Path neoForge = nested(NEO_HOST, "META-INF/jarjar/spectrelib-neoforge-0.22.0+26.2.jar", "spectrelib-neoforge.jar");
		Map<String, String> got = new TreeMap<>();
		for (var decision : ArbitratedAwayEntrypoints.decide("spectrelib", List.of(fabric), neoForge, Set.of())) {
			got.put(decision.key() + " " + decision.entrypoint().value(), decision.kept() ? "kept" : decision.refusal());
		}
		assertEquals(Map.of("modmenu com.illusivesoulworks.spectrelib.integration.ModMenuPlugin", "kept"), got,
				"its main/client/preLaunch are the library's own initialisation, which the NeoForge build did; the Mod Menu "
						+ "plugin exists only in the Fabric build and everything it uses of the library the NeoForge build has");
	}
}
