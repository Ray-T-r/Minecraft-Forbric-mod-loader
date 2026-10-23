/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;
import net.forbric.kernel.boot.DuplicateModArbiter;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.MultiLoaderArbiter;

/**
 * What Fabric's registry says about a mod another family loads, through the boot path itself
 * ({@link KernelFabricEcosystem#build} over real jars in {@code mods/}).
 *
 * <ul>
 *   <li>A universal jar a Forge family won is registered on the Fabric side as a presence identity, and that identity
 *       now shows itself the way the jar's own {@code fabric.mod.json} does: name, description, authors and icon. Mod
 *       Menu draws every row from exactly these, and the bare id made the mod a nameless, iconless row there.</li>
 *   <li>The {@code fabricloader} built-in declares the kernel's own {@code "modmenu"} entrypoint on a client, which is
 *       how Mod Menu is offered those mods' config screens, and nothing on a server, where nothing reads one.</li>
 * </ul>
 *
 * <p>The ids are invented: the rule is "a Fabric build discovery read and did not register", not any mod's name.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
class FabricAliasDisplayTest {
	@TempDir
	Path gameDir;

	private Path mods;
	private Object previousLoader;

	@BeforeEach
	void setUp() throws Exception {
		mods = Files.createDirectories(gameDir.resolve("mods"));
		previousLoader = activeLoader().get(null);
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
	}

	@Test
	void aUniversalJarsFabricIdentityShowsItselfAsItsOwnFabricBuildDoes() throws Exception {
		Path jar = universal("glimmer.jar", "glimmer", """
				{"schemaVersion":1,"id":"glimmer","version":"3.1.0","name":"Glimmer Lanterns",
				 "description":"Lanterns that glimmer.","authors":["Ada"],"icon":"assets/glimmer/icon.png"}
				""");
		assertEquals(Ecosystem.NEOFORGE, MultiLoaderArbiter.ownerOf(jar), "the fixture is a universal jar NeoForge wins");
		ModPresence.publishForgeFamily(List.of(new net.forbric.api.DiscoveredMod(Ecosystem.NEOFORGE, "glimmer", "3.1.0",
				"glimmer", List.of(), List.of(), null, jar.toString())));

		build(EnvType.CLIENT, new DuplicateModArbiter.Alias("glimmer", Ecosystem.FABRIC, "3.1.0"));

		ModContainer alias = KernelFabricLoader.getInstanceOrNull().getModContainer("glimmer").orElseThrow();
		assertNull(((KernelModContainer) alias).getJar(), "still a presence identity: no code or assets of its own");
		assertEquals("Glimmer Lanterns", alias.getMetadata().getName());
		assertEquals("Lanterns that glimmer.", alias.getMetadata().getDescription());
		assertEquals(List.of("Ada"), alias.getMetadata().getAuthors().stream().map(Person::getName).toList());
		assertEquals(Optional.of("assets/glimmer/icon.png"), alias.getMetadata().getIconPath(64));
		assertTrue(alias.findPath("assets/glimmer/icon.png").isPresent(), "and the icon is read from the jar that loaded it");
		assertEquals(Set.of(), metadata("glimmer").getEntrypoints().keySet(),
				"its losing build's entrypoints are not the identity's: that build does not run");
	}

	@Test
	void anIdentityWithNoFabricBuildToShowKeepsItsId() throws Exception {
		universal("plain.jar", "plain", null);
		build(EnvType.CLIENT, new DuplicateModArbiter.Alias("plain", Ecosystem.FABRIC, "1.0.0"));
		ModContainer alias = KernelFabricLoader.getInstanceOrNull().getModContainer("plain").orElseThrow();
		assertEquals("plain", alias.getMetadata().getName());
		assertEquals(Optional.empty(), alias.getMetadata().getIconPath(64));
	}

	@Test
	void fabricLoaderDeclaresTheKernelsModMenuEntrypointOnAClientOnly() throws Exception {
		build(EnvType.CLIENT);
		Map<String, List<KernelModMetadata.EntrypointDecl>> client = metadata("fabricloader").getEntrypoints();
		assertEquals(List.of(KernelFabricEcosystem.MOD_MENU_PLUGIN),
				client.getOrDefault("modmenu", List.of()).stream().map(KernelModMetadata.EntrypointDecl::value).toList());

		reset();
		build(EnvType.SERVER);
		assertEquals(Set.of(), metadata("fabricloader").getEntrypoints().keySet(), "a server declares nothing");
	}

	private static KernelModMetadata metadata(String id) {
		return (KernelModMetadata) KernelFabricLoader.getInstanceOrNull().getModContainer(id).orElseThrow().getMetadata();
	}

	private void build(EnvType env, DuplicateModArbiter.Alias... aliases) throws Exception {
		DuplicateModArbiter.Decision dupes = new DuplicateModArbiter.Decision(Set.of(), Map.of(), List.of(aliases));
		FabricModDiscovery discovery = KernelFabricEcosystem.scan(env, gameDir, dupes);
		KernelFabricEcosystem.build(discovery, env, gameDir, "26.2", new String[0], dupes, null);
	}

	/** A jar with a NeoForge manifest for {@code id} and, when given, a fabric.mod.json -- plus an icon file. */
	private Path universal(String name, String id, String fabricManifest) throws IOException {
		Path jar = mods.resolve(name);
		try (OutputStream target = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(target)) {
			if (fabricManifest != null) {
				out.putNextEntry(new JarEntry(FabricModDiscovery.MANIFEST));
				out.write(fabricManifest.getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
			out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
			out.write(("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
					+ "\"\nversion=\"1.0.0\"\n").getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new JarEntry("assets/" + id + "/icon.png"));
			out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
			out.closeEntry();
		}
		return jar;
	}

	private static Field activeLoader() throws NoSuchFieldException {
		Field field = KernelFabricEcosystem.class.getDeclaredField("loader");
		field.setAccessible(true);
		return field;
	}
}
