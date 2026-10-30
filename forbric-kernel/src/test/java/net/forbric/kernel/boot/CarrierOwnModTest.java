/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.metadata.forge.ForgeModEntry;
import net.forbric.kernel.metadata.forge.ModsTomlParser;

/**
 * NeoForge's own container ({@code "neoforge"}) is described by the entry NeoForge's own manifest declares for itself,
 * as genuine NeoForge's is: the name a mod reading {@code getModInfo().getDisplayName()} sees is {@code NeoForge}, not
 * the id, and the version is the carrier's, not {@code 0.0}.
 */
class CarrierOwnModTest {
	@TempDir
	Path tmp;

	@Test
	void theCarriersOwnEntryIsReadFromItsOwnManifest() throws Exception {
		Path forge = jar("forge-carrier.jar", "META-INF/mods.toml", toml("forge", "Forge", "1.0", ""));
		Path neo = jar("neo-carrier.jar", CarrierOwnMod.NEOFORGE_MANIFEST,
				toml("neoforge", "NeoForge", "26.2.0.99", "A loader."));
		DiscoveredMod own = CarrierOwnMod.neoForge(List.of(forge, neo));
		assertEquals("neoforge", own.getId());
		assertEquals(Ecosystem.NEOFORGE, own.getEcosystem());
		assertEquals("NeoForge", own.getDisplayName());
		assertEquals("26.2.0.99", own.getVersion());
		assertEquals("A loader.", own.getConfigElements().get("description"));
	}

	@Test
	void withNoCarrierDeclaringItTheFamilysOwnNameStands() throws Exception {
		Path forge = jar("forge-carrier.jar", "META-INF/mods.toml", toml("forge", "Forge", "1.0", ""));
		Path other = jar("other.jar", CarrierOwnMod.NEOFORGE_MANIFEST, toml("somethingelse", "Something", "2.0", ""));
		for (List<Path> jars : List.of(List.of(forge, other), List.<Path>of(), List.of(tmp.resolve("missing.jar")))) {
			DiscoveredMod own = CarrierOwnMod.neoForge(jars);
			assertEquals("neoforge", own.getId());
			assertEquals(Ecosystem.NEOFORGE.displayName(), own.getDisplayName(), "never the bare id: " + jars);
		}
	}

	/**
	 * The container itself, built by the kernel's own {@code KernelModInfo} from the staged NeoForge carrier: what its
	 * manifest says, field for field.
	 */
	@Test
	void neoForgesOwnContainerAnswersWhatTheStagedCarriersManifestSays() throws Exception {
		Path carrier = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar").normalize();
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		TestFixtures.requireFile(Fixture.STAGED, carrier, "staged carrier absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "staged runtime classes absent");
		ForgeModEntry manifest;
		try (ZipFile zip = new ZipFile(carrier.toFile())) {
			manifest = ModsTomlParser.parse(zip.getInputStream(zip.getEntry(CarrierOwnMod.NEOFORGE_MANIFEST))).getMods()
					.stream().filter(m -> m.getModId().equals("neoforge")).findFirst().orElseThrow();
		}
		assertEquals("NeoForge", manifest.getDisplayName(), "the fixture is NeoForge's own manifest");

		DiscoveredMod own = CarrierOwnMod.neoForge(List.of(carrier));
		try (URLClassLoader runtime = new URLClassLoader(new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL()},
				CarrierOwnModTest.class.getClassLoader())) {
			Object info = Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
					.getConstructor(String.class, Path.class, DiscoveredMod.class).newInstance("neoforge", null, own);
			assertEquals("NeoForge", info.getClass().getMethod("getDisplayName").invoke(info));
			assertEquals(manifest.getVersion(), info.getClass().getMethod("getVersion").invoke(info).toString());
			assertFalse(manifest.getVersion().isBlank());
			String description = (String) info.getClass().getMethod("getDescription").invoke(info);
			assertEquals(manifest.getDescription().replace("\r\n", "\n"), description);
			assertTrue(!description.isEmpty(), "NeoForge's manifest describes it");
		}
	}

	private static String toml(String id, String name, String version, String description) {
		return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"LGPL\"\n\n[[mods]]\nmodId=\"" + id + "\"\nversion=\""
				+ version + "\"\ndisplayName=\"" + name + "\"\n"
				+ (description.isEmpty() ? "" : "description=\"" + description + "\"\n");
	}

	private Path jar(String name, String entry, String content) throws IOException {
		Path jar = tmp.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(entry));
			zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
