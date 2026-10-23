/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.ui.ModListRows;

/**
 * NeoForge's own row on the Mods screen for a universal jar, decided by the family arbitration gave that jar: the
 * catalogue is built the way the boot builds it ({@link PassiveSeeder#arbitratedForgeFamilyMods}, then each Fabric mod
 * whose id is not there yet, through {@link KernelModCatalog#publish}), and the rows are planned from what it holds.
 *
 * <p>The jar has no initializer for either family, so {@link MultiLoaderArbiter}'s order alone decides: NeoForge by
 * default, Fabric when the preference says so. The ids are invented.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class LoaderRowCatalogueTest {
	@TempDir
	Path tmp;

	@AfterEach
	void clear() {
		System.clearProperty("forbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
		ModCatalog.publish(List.of());
	}

	@Test
	void aUniversalJarNeoForgeWonListsNeoForgesOwnRow() throws Exception {
		Path mods = mods();
		MultiLoaderArbiter.reset();
		assertEquals(Ecosystem.NEOFORGE, MultiLoaderArbiter.ownerOf(mods.resolve("lantern.jar")),
				"the default order gives the universal jar to NeoForge");
		assertEquals(List.of("compass", "lantern", "game", "loader"), listed(mods));
	}

	/** The control: the same two jars, the universal one handed to Fabric, is a pure-Fabric pack. */
	@Test
	void theSameJarFabricWonListsNone() throws Exception {
		Path mods = mods();
		System.setProperty("forbric.multiLoaderPreference", "fabric");
		MultiLoaderArbiter.reset();
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(mods.resolve("lantern.jar")));
		assertEquals(List.of("compass", "lantern", "game"), listed(mods));
	}

	/** {@code lantern.jar}, universal (a fabric.mod.json and a neoforge.mods.toml), beside {@code compass.jar}, Fabric only. */
	private Path mods() throws IOException {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(mods.resolve("lantern.jar")))) {
			PassiveSeederLoadingModListTest.put(zip, "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n"
					+ "loaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n[[mods]]\nmodId=\"lantern\"\nversion=\"1.0.0\"\n"
					+ "displayName=\"Lantern\"\n");
			PassiveSeederLoadingModListTest.put(zip, "fabric.mod.json",
					"{\"schemaVersion\":1,\"id\":\"lantern\",\"version\":\"1.0.0\",\"name\":\"Lantern\"}");
		}
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(mods.resolve("compass.jar")))) {
			PassiveSeederLoadingModListTest.put(zip, "fabric.mod.json",
					"{\"schemaVersion\":1,\"id\":\"compass\",\"version\":\"1.0.0\",\"name\":\"Compass\"}");
		}
		return mods;
	}

	/**
	 * The rows for {@code mods}, with the game and NeoForge's own entry ({@code loader}) offered as platform entries. A
	 * Fabric mod is one discovery read from a jar the arbiter gave to Fabric, as Fabric's own discovery takes it.
	 */
	private static List<String> listed(Path mods) throws Exception {
		List<DiscoveredMod> presence = new ArrayList<>(PassiveSeeder.arbitratedForgeFamilyMods(mods));
		Set<String> ids = new HashSet<>();
		for (DiscoveredMod mod : presence) ids.add(mod.getId());
		List<Path> jars;
		try (Stream<Path> listing = Files.list(mods)) {
			jars = listing.sorted().toList();
		}
		ForbricModDiscoverer discoverer = new ForbricModDiscoverer();
		for (Path jar : jars) {
			if (MultiLoaderArbiter.ownerOf(jar) != Ecosystem.FABRIC) continue;
			for (DiscoveredMod mod : discoverer.discoverJar(jar)) {
				if (mod.getEcosystem() == Ecosystem.FABRIC && ids.add(mod.getId())) presence.add(mod);
			}
		}
		KernelModCatalog.publish(presence, mods);

		ModCatalog.Entry game = new ModCatalog.Entry(Ecosystem.FABRIC, "game", "Minecraft", "26.2", "", List.of(), "", "", "");
		ModCatalog.Entry loader = new ModCatalog.Entry(Ecosystem.NEOFORGE, "loader", "NeoForge", "1", "", List.of(), "", "", "");
		List<String> out = new ArrayList<>();
		for (ModListRows.Row row : ModListRows.plan(ModCatalog.everything(),
				List.of(new ModListRows.Platform(game, null), new ModListRows.Platform(loader, Ecosystem.NEOFORGE)),
				e -> false, id -> null)) {
			out.add(row.id());
		}
		return out;
	}
}
