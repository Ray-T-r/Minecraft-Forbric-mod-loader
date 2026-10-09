/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;
import net.forbric.kernel.boot.DuplicateModArbiter;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.MultiLoaderArbiter;

/**
 * Both directions, through the boot path itself ({@link KernelFabricEcosystem#build} over real jars in {@code mods/}):
 * a Fabric mod's declarations reach the Forge-family side as {@code [modproperties]} on its published
 * {@link DiscoveredMod} (what the seeded {@code LoadingModList} and every kernel-built {@code IModInfo} describe it
 * from), and a Forge-family mod's class declared under a namespaced property reaches a Fabric reader of that key's
 * entrypoints.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("system-properties")
class CrossEcosystemDeclarationWiringTest {
	@TempDir
	Path gameDir;

	private Path mods;
	private Object previousLoader;
	private String savedSwitch;

	@BeforeEach
	void setUp() throws Exception {
		mods = Files.createDirectories(gameDir.resolve("mods"));
		previousLoader = activeLoader().get(null);
		savedSwitch = System.getProperty(CrossEcosystemDeclarations.SWITCH);
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		reset();
	}

	@AfterEach
	void tearDown() throws Exception {
		reset();
		activeLoader().set(null, previousLoader);
		if (savedSwitch == null) System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		else System.setProperty(CrossEcosystemDeclarations.SWITCH, savedSwitch);
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

	/** What a Forge-family mod's declared class is constructed as on the Fabric side. */
	public static final class EmberPanel implements Supplier<String> {
		@Override
		public String get() {
			return "ember";
		}
	}

	public static final class LumenPanel implements Supplier<String> {
		@Override
		public String get() {
			return "lumen";
		}
	}

	@Test
	void eachFamilysDeclarationsReachTheOtherFamilysReaders() throws Exception {
		jar("lumen.jar", """
				{"schemaVersion":1,"id":"lumen-panels","version":"1.4.0","name":"Lumen Panels",
				 "entrypoints":{"glowkit:panel":["%s"]},
				 "custom":{"glowkit:theme":{"accent":"amber"}}}
				""".formatted(LumenPanel.class.getName()));
		ModPresence.publishForgeFamily(List.of(new DiscoveredMod(Ecosystem.NEOFORGE, "ember_neo", "2.0.0", "Ember Neo",
				List.of(), List.of(), null, null).withModProperties(Map.of(
						"glowkit:panel", EmberPanel.class.getName(),
						"glowkit:enabled", true,
						"fabric:provides", List.of("ember")))));

		build();

		DiscoveredMod lumen = ModPresence.fabricMods().stream().filter(m -> m.getId().equals("lumen-panels")).findFirst()
				.orElseThrow();
		assertTrue(lumen.getModProperties().get("glowkit:theme") instanceof com.electronwill.nightconfig.core.CommentedConfig,
				"the Fabric mod is published with its custom values as properties");
		Map<String, Object> declared = CrossEcosystemDeclarations.declarationsOf(lumen);
		assertEquals(null, declared.get("glowkit:panel"), "no reader has asked for a name under glowkit:panel yet");
		CrossEcosystemDeclarations.noteRead("glowkit:panel", CrossEcosystemDeclarations.Read.NAME, "fixture.Reader");
		assertEquals(LumenPanel.class.getName(), declared.get("glowkit:panel"),
				"once one has, the class the Fabric mod names under that entrypoint key is there");

		KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();
		List<String> panels = loader.getEntrypointContainers("glowkit:panel", Supplier.class).stream()
				.map(c -> c.getProvider().getMetadata().getId() + "=" + c.getEntrypoint().get()).sorted().toList();
		assertEquals(List.of("ember_neo=ember", "lumen-panels=lumen"), panels,
				"a Fabric reader of the key meets the NeoForge mod's declared class beside the Fabric mod's own");
		assertEquals(List.of(), loader.getEntrypointContainers("fabric:provides", Object.class),
				"a Fabric metadata field written as a property is not an entrypoint");
		assertEquals(List.of(), loader.getEntrypointContainers("glowkit:enabled", Object.class));
	}

	@Test
	void switchedOffNeitherDirectionCrosses() throws Exception {
		System.setProperty(CrossEcosystemDeclarations.SWITCH, "off");
		jar("lumen.jar", """
				{"schemaVersion":1,"id":"lumen-panels","version":"1.4.0","entrypoints":{"glowkit:panel":["%s"]}}
				""".formatted(LumenPanel.class.getName()));
		ModPresence.publishForgeFamily(List.of(new DiscoveredMod(Ecosystem.NEOFORGE, "ember_neo", "2.0.0", "Ember Neo",
				List.of(), List.of(), null, null).withModProperties(Map.of("glowkit:panel", EmberPanel.class.getName()))));

		build();

		CrossEcosystemDeclarations.noteRead("glowkit:panel", CrossEcosystemDeclarations.Read.NAME, "fixture.Reader");
		assertTrue(ModPresence.fabricMods().stream()
				.allMatch(m -> CrossEcosystemDeclarations.declarationsOf(m).isEmpty()));
		List<? extends EntrypointContainer<Supplier>> panels =
				KernelFabricLoader.getInstanceOrNull().getEntrypointContainers("glowkit:panel", Supplier.class);
		assertEquals(List.of("lumen-panels"), panels.stream().map(c -> c.getProvider().getMetadata().getId()).toList());
	}

	private void build() throws Exception {
		FabricModDiscovery discovery = KernelFabricEcosystem.scan(EnvType.CLIENT, gameDir,
				DuplicateModArbiter.Decision.none());
		KernelFabricEcosystem.build(discovery, EnvType.CLIENT, gameDir, "26.2", new String[0],
				DuplicateModArbiter.Decision.none(), null);
		KernelFabricEcosystem.bindGameLoader(getClass().getClassLoader());
	}

	private void jar(String name, String manifest) throws IOException {
		try (OutputStream target = Files.newOutputStream(mods.resolve(name));
				JarOutputStream out = new JarOutputStream(target)) {
			out.putNextEntry(new JarEntry(FabricModDiscovery.MANIFEST));
			out.write(manifest.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}

	private static Field activeLoader() throws NoSuchFieldException {
		Field field = KernelFabricEcosystem.class.getDeclaredField("loader");
		field.setAccessible(true);
		return field;
	}
}
