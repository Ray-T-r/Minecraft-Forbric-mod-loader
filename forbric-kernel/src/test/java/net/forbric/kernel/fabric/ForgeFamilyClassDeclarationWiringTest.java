/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
import net.forbric.kernel.boot.DuplicateModArbiter;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.MultiLoaderArbiter;

/**
 * Through the boot path itself ({@link KernelFabricEcosystem#build}): a MinecraftForge mod discovered from a real jar
 * names, under namespaced {@code [modproperties]} keys, one class its jar defines and several values that are not its
 * classes. A Fabric reader of each key meets exactly the one class; every other value stays a custom value, and asking
 * for those keys' entrypoints raises no "cannot load entrypoint" finding against the mod — a value that was never a
 * class name is not a broken class.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class ForgeFamilyClassDeclarationWiringTest {
	@TempDir
	Path gameDir;

	private Object previousLoader;

	@BeforeEach
	void setUp() throws Exception {
		Files.createDirectories(gameDir.resolve("mods"));
		previousLoader = activeLoader().get(null);
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		reset();
	}

	@AfterEach
	void tearDown() throws Exception {
		reset();
		activeLoader().set(null, previousLoader);
	}

	private static void reset() {
		KernelFabricLoader.resetForTests();
		KernelFabricEcosystem.resetPhasesForTests();
		KernelLanguageAdapters.reset();
		MultiLoaderArbiter.reset();
		DuplicateModArbiter.reset();
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
		CrossEcosystemDeclarations.resetForTests();
		CompatibilityFindings.reset();
	}

	/** The MinecraftForge mod's own class, which its jar carries. */
	public static final class CinderPanel implements Supplier<String> {
		@Override
		public String get() {
			return "cinder";
		}
	}

	@Test
	void aFabricReaderMeetsOnlyTheClassesTheForgeModsJarDefines() throws Exception {
		Path jar = gameDir.resolve("cinder-forge.jar");
		writeJarWith(jar, CinderPanel.class);
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("cinderlib:panel", CinderPanel.class.getName());
		properties.put("cinderlib:homepage", "cinder.example");
		properties.put("cinderlib:tagline", "Warm light for cold caves");
		properties.put("cinderlib:borrowed", "java.util.ArrayList");
		properties.put("cinderlib:partial", List.of(CinderPanel.class.getName(), "fixture.cinder.Missing"));
		ModPresence.publishForgeFamily(List.of(new DiscoveredMod(Ecosystem.FORGE, "cinder_forge", "1.0.0", "Cinder",
				List.of(), List.of(), null, jar.toString()).withModProperties(properties)));

		build();

		KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();
		assertEquals(List.of("cinder_forge=cinder"), loader.getEntrypointContainers("cinderlib:panel", Supplier.class)
				.stream().map(c -> c.getProvider().getMetadata().getId() + "=" + c.getEntrypoint().get()).toList(),
				"the class the mod's own jar defines is an entrypoint of its key");
		for (String key : List.of("cinderlib:homepage", "cinderlib:tagline", "cinderlib:borrowed", "cinderlib:partial")) {
			assertEquals(List.of(), loader.getEntrypointContainers(key, Object.class),
					key + " is not one of the mod's classes, so no Fabric reader is handed something to construct there");
		}
		assertEquals(List.of(), CompatibilityFindings.all().stream().filter(f -> f.id().startsWith("entrypoint:"))
				.map(CompatibilityFinding::key).toList(),
				"and reading those keys raises no unloadable-entrypoint finding against the mod");

		var metadata = loader.getModContainer("cinder_forge").orElseThrow().getMetadata();
		assertNotNull(metadata.getCustomValue("cinderlib:homepage"));
		assertEquals("cinder.example", metadata.getCustomValue("cinderlib:homepage").getAsString(),
				"what is not a class stays what it was: a custom value under its key");
	}

	// ---- fixtures ----

	private void build() throws Exception {
		FabricModDiscovery discovery = KernelFabricEcosystem.scan(EnvType.CLIENT, gameDir,
				DuplicateModArbiter.Decision.none());
		KernelFabricEcosystem.build(discovery, EnvType.CLIENT, gameDir, "26.2", new String[0],
				DuplicateModArbiter.Decision.none(), null);
		KernelFabricEcosystem.bindGameLoader(getClass().getClassLoader());
	}

	/** A Forge-family jar outside {@code mods/}, as an extracted nested jar sits: a manifest and its own class. */
	private static void writeJarWith(Path file, Class<?> own) throws IOException {
		String entry = own.getName().replace('.', '/') + ".class";
		try (OutputStream target = Files.newOutputStream(file); JarOutputStream out = new JarOutputStream(target);
				InputStream bytes = own.getClassLoader().getResourceAsStream(entry)) {
			out.putNextEntry(new JarEntry("META-INF/mods.toml"));
			out.write("modLoader=\"javafml\"\n".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new JarEntry(entry));
			out.write(bytes.readAllBytes());
			out.closeEntry();
		}
	}

	private static Field activeLoader() throws NoSuchFieldException {
		Field field = KernelFabricEcosystem.class.getDeclaredField("loader");
		field.setAccessible(true);
		return field;
	}
}
