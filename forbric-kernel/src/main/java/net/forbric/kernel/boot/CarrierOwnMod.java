/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.metadata.forge.ForgeMetadataMapper;
import net.forbric.kernel.metadata.forge.ForgeModsToml;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.Caught;

/**
 * What a loader's own runtime jar declares about the loader itself: the {@code [[mods]]} entry its own manifest has for
 * its own id, read the way every mod's entry is read.
 *
 * <p>The kernel builds NeoForge's own container ({@code "neoforge"}, the one {@code NeoForgeMod} is constructed with)
 * itself, and described it from discovery, which never lists a runtime jar as a mod -- so the container answered its id as
 * its display name and {@code "0.0"} as its version. Genuine NeoForge reads both out of the {@code neoforge.mods.toml}
 * inside its own jar ({@code displayName="NeoForge"}, its release version, its description and authors), and a mod reading
 * {@code ModList.getModContainerById("neoforge").getModInfo()} -- a version check, a mod list, the kernel's own Mods
 * screen -- reads those. This hands that entry to the container, so it answers what the carrier's manifest says.
 */
public final class CarrierOwnMod {
	public static final String NEOFORGE_ID = "neoforge";
	static final String NEOFORGE_MANIFEST = "META-INF/neoforge.mods.toml";

	private CarrierOwnMod() {
	}

	/**
	 * NeoForge's own entry, from the first of {@code runtimeJars} whose {@code neoforge.mods.toml} declares
	 * {@code neoforge}. When none does, or none can be read, an entry carrying the family's own name and the version
	 * {@code EcosystemVersions} recorded (none, then: it reads the same file), so the container still says what it is.
	 */
	public static DiscoveredMod neoForge(List<Path> runtimeJars) {
		DiscoveredMod declared = declared(runtimeJars, NEOFORGE_ID, Ecosystem.NEOFORGE, NEOFORGE_MANIFEST);
		if (declared != null) return declared;
		return new DiscoveredMod(Ecosystem.NEOFORGE, NEOFORGE_ID,
				net.forbric.kernel.metadata.forge.EcosystemVersions.provided(NEOFORGE_ID), Ecosystem.NEOFORGE.displayName(),
				List.of(), List.of(), null, null);
	}

	/** The entry {@code manifest} in one of {@code runtimeJars} declares for {@code modId}, or null when none does. */
	static DiscoveredMod declared(List<Path> runtimeJars, String modId, Ecosystem family, String manifest) {
		if (runtimeJars == null) return null;
		for (Path jar : runtimeJars) {
			if (jar == null || !Files.isRegularFile(jar)) continue;
			try (JarFile file = new JarFile(jar.toFile())) {
				ZipEntry entry = file.getEntry(manifest);
				if (entry == null) continue;
				ForgeModsToml toml;
				try (InputStream in = file.getInputStream(entry)) {
					toml = ModsTomlParser.parse(in);
				}
				Manifest jarManifest = file.getManifest();
				String jarVersion = jarManifest == null ? null
						: jarManifest.getMainAttributes().getValue("Implementation-Version");
				for (DiscoveredMod mod : ForgeMetadataMapper.toDiscoveredMods(toml, jarVersion, jar.toString(),
						toml.getAccessTransformers(), List.of(), config -> true, family)) {
					if (modId.equals(mod.getId())) return mod;
				}
			} catch (IOException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Lifecycle] could not read %s from %s; %s's own container is described "
						+ "without it", manifest, jar.getFileName(), modId);
			}
		}
		return null;
	}
}
