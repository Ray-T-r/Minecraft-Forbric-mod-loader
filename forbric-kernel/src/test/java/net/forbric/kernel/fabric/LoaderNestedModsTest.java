/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.CrossEcosystemDeclarations;
import net.forbric.kernel.boot.DuplicateModArbiter;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.MultiLoaderArbiter;

/**
 * Fabric Loader nests a jar in its own ({@code "jars"} in its fabric.mod.json: MixinExtras), so on Fabric that jar is a
 * mod contained in {@code fabricloader}: in {@code getAllMods()}, answering {@code isModLoaded}, counted by Mod Menu. The
 * kernel's own game-side jars are registered the same way when they are Fabric mods. Names here are no real mod's.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("ModPresence")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("system-properties")
class LoaderNestedModsTest {
	private static final String SWITCH = "forbric.kernelBundledFirst";
	@TempDir Path gameDir;
	private Path mods, platform;
	private Object previousLoader;

	@BeforeEach
	void setUp() throws Exception {
		mods = Files.createDirectories(gameDir.resolve("mods"));
		platform = Files.createDirectories(gameDir.resolve("platform"));
		previousLoader = activeLoader().get(null);
		System.clearProperty(SWITCH);
		reset();
	}

	@AfterEach
	void tearDown() throws Exception {
		reset();
		activeLoader().set(null, previousLoader);
		System.clearProperty(SWITCH);
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

	private static final String TOOLKIT = """
			{"schemaVersion":1,"id":"toolkit","version":"%s","provides":["io_toolkit"],"mixins":["toolkit.init.mixins.json"],
			 "custom":{"modmenu":{"badges":["library"]}}}
			""";

	@Test
	void aPlatformJarThatIsAFabricModIsAModNestedInFabricLoader() throws Exception {
		Path own = jar(platform.resolve("toolkit.jar"), TOOLKIT.formatted("2.3.0"), null, null);
		Path library = jar(platform.resolve("runtime.jar"), null, null, null);
		jar(mods.resolve("widget.jar"), "{\"schemaVersion\":1,\"id\":\"widget\",\"version\":\"1.0.0\"}", null, null);

		KernelFabricLoader loader = build(List.of(own, library));

		ModContainer toolkit = loader.getModContainer("toolkit").orElseThrow();
		assertEquals("2.3.0", toolkit.getMetadata().getVersion().getFriendlyString());
		assertSame(loader.getModContainer("fabricloader").orElseThrow(), toolkit.getContainingMod().orElseThrow());
		assertTrue(loader.getModContainer("fabricloader").orElseThrow().getContainedMods().contains(toolkit));
		assertEquals(ModOrigin.Kind.NESTED, toolkit.getOrigin().getKind());
		assertEquals("fabricloader", toolkit.getOrigin().getParentModId());
		assertTrue(loader.isModLoaded("toolkit") && loader.isModLoaded("io_toolkit"));
		assertEquals(1, loader.getAllMods().stream().filter(m -> m.getMetadata().getId().equals("toolkit")).count());
		assertEquals(List.of("fabricloader", "java", "minecraft", "toolkit", "widget"),
				loader.getAllMods().stream().map(m -> m.getMetadata().getId()).sorted().toList(),
				"a platform jar without fabric.mod.json is a library, not a mod");
		assertTrue(KernelFabricEcosystem.mixinConfigs().stream()
				.anyMatch(c -> c.config().equals("toolkit.init.mixins.json") && c.modId().equals("toolkit")),
				"its declared mixin config is applied as Fabric Loader applies a nested mod's");
		assertFalse(ModPresence.fabricMods().stream().anyMatch(m -> m.getId().equals("toolkit")),
				"what the loader nests is platform, not a mod the Forge-family lists are seeded with");
		assertTrue(ModPresence.fabricMods().stream().anyMatch(m -> m.getId().equals("widget")));
	}

	@Test
	void aGuestCopyOfTheSameModIsLeftOutWhateverItsVersionWhileTheKernelsCopyRuns() throws Exception {
		Path own = jar(platform.resolve("toolkit.jar"), TOOLKIT.formatted("2.3.0"), null, null);
		for (String nested : List.of("1.9.0", "3.0.0")) {
			Files.deleteIfExists(mods.resolve("widget.jar"));
			reset();
			byte[] copy = jarBytes(TOOLKIT.formatted(nested));
			jar(mods.resolve("widget.jar"), """
					{"schemaVersion":1,"id":"widget","version":"1.0.0","jars":[{"file":"META-INF/jars/toolkit-%s.jar"}]}
					""".formatted(nested), "META-INF/jars/toolkit-" + nested + ".jar", copy);

			KernelFabricLoader loader = build(List.of(own));

			assertEquals(1, loader.getAllMods().stream().filter(m -> m.getMetadata().getId().equals("toolkit")).count(), nested);
			ModContainer toolkit = loader.getModContainer("toolkit").orElseThrow();
			assertEquals("2.3.0", toolkit.getMetadata().getVersion().getFriendlyString(),
					"the copy that answers is the one whose classes every mod gets, ahead of every guest copy");
			assertEquals("fabricloader", toolkit.getContainingMod().orElseThrow().getMetadata().getId());
			assertTrue(loader.getModContainer("widget").orElseThrow().getContainedMods().isEmpty(),
					"the left-out copy is nobody's contained mod");
		}
	}

	@Test
	void withGuestCopiesReadFirstAGuestCopyAnswersInstead() throws Exception {
		System.setProperty(SWITCH, "off");
		Path own = jar(platform.resolve("toolkit.jar"), TOOLKIT.formatted("2.3.0"), null, null);
		jar(mods.resolve("widget.jar"), """
				{"schemaVersion":1,"id":"widget","version":"1.0.0","jars":[{"file":"META-INF/jars/toolkit-1.9.0.jar"}]}
				""", "META-INF/jars/toolkit-1.9.0.jar", jarBytes(TOOLKIT.formatted("1.9.0")));

		KernelFabricLoader loader = build(List.of(own));

		ModContainer toolkit = loader.getModContainer("toolkit").orElseThrow();
		assertEquals("1.9.0", toolkit.getMetadata().getVersion().getFriendlyString());
		assertEquals("widget", toolkit.getContainingMod().orElseThrow().getMetadata().getId());
		assertTrue(loader.getModContainer("fabricloader").orElseThrow().getContainedMods().isEmpty());
	}

	private KernelFabricLoader build(List<Path> platformJars) {
		FabricModDiscovery discovery = KernelFabricEcosystem.scan(EnvType.CLIENT, gameDir, DuplicateModArbiter.Decision.none());
		KernelFabricEcosystem.build(discovery, EnvType.CLIENT, gameDir, "26.2", new String[0],
				DuplicateModArbiter.Decision.none(), null, platformJars);
		return KernelFabricLoader.getInstanceOrNull();
	}

	private static byte[] jarBytes(String manifest) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		write(bytes, manifest, null, null);
		return bytes.toByteArray();
	}

	private static Path jar(Path target, String manifest, String nestedName, byte[] nested) throws IOException {
		try (OutputStream out = Files.newOutputStream(target)) {
			write(out, manifest, nestedName, nested);
		}
		return target;
	}

	private static void write(OutputStream target, String manifest, String nestedName, byte[] nested) throws IOException {
		try (JarOutputStream out = new JarOutputStream(target)) {
			if (manifest != null) {
				out.putNextEntry(new JarEntry(FabricModDiscovery.MANIFEST));
				out.write(manifest.getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			} else {
				out.putNextEntry(new JarEntry("library/Marker.txt"));
				out.write("no fabric.mod.json".getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
			if (nestedName != null) {
				out.putNextEntry(new JarEntry(nestedName));
				out.write(nested);
				out.closeEntry();
			}
		}
	}

	private static Field activeLoader() throws NoSuchFieldException {
		Field field = KernelFabricEcosystem.class.getDeclaredField("loader");
		field.setAccessible(true);
		return field;
	}
}
