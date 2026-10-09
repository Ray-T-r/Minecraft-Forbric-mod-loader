/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;

/**
 * A Forge-family mod's {@code [modproperties]} value becomes a Fabric entrypoint only when it is one of the mod's own
 * classes: shaped like a class name (or a list of nothing but them) AND defined by the jar the mod came from. Anything
 * else under a namespaced key — a sentence, a path, a version, a domain that happens to look like a package, another
 * mod's or the JDK's class — stays a property and is never handed to a Fabric reader to construct.
 *
 * <p>The mods and keys are made up ({@code cinderlib}); the class names are spelled the ways a Fabric entrypoint can
 * spell one: plain, nested ({@code Outer$Inner}), with a {@code ::member} suffix.
 */
@ResourceLock("system-properties")
@ResourceLock("CrossEcosystemDeclarations")
@ResourceLock("ModCatalog")
class ForgeFamilyClassDeclarationsTest {
	@TempDir
	Path work;

	@BeforeEach
	@AfterEach
	void reset() {
		System.clearProperty(CrossEcosystemDeclarations.SWITCH);
		CrossEcosystemDeclarations.resetForTests();
	}

	private static final List<String> OWN_CLASSES = List.of("fixture/cinder/ForgePanel.class",
			"fixture/cinder/Hooks$Early.class", "fixture/cinder/Hooks$Late.class", "fixture/cinder/Registry.class");

	/** Every way a namespaced value can look, beside the ones that really are the mod's classes. */
	private static Map<String, Object> properties() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("cinderlib:panel", "fixture.cinder.ForgePanel");
		properties.put("cinderlib:hooks", List.of("fixture.cinder.Hooks$Early", "fixture.cinder.Hooks$Late"));
		properties.put("cinderlib:registry", "fixture.cinder.Registry::INSTANCE");
		properties.put("cinderlib:homepage", "cinder.example");
		properties.put("cinderlib:tagline", "Warm light for cold caves");
		properties.put("cinderlib:icon", "assets/cinder/icon.png");
		properties.put("cinderlib:version", "1.4.2");
		properties.put("cinderlib:flag", "true");
		properties.put("cinderlib:borrowed", "java.util.ArrayList");
		properties.put("cinderlib:partial", List.of("fixture.cinder.ForgePanel", "fixture.cinder.Missing"));
		properties.put("cinderlib:package", "fixture.cinder");
		return properties;
	}

	private static final Map<String, List<EntrypointDecl>> OWN = Map.of(
			"cinderlib:panel", List.of(new EntrypointDecl("default", "fixture.cinder.ForgePanel")),
			"cinderlib:hooks", List.of(new EntrypointDecl("default", "fixture.cinder.Hooks$Early"),
					new EntrypointDecl("default", "fixture.cinder.Hooks$Late")),
			"cinderlib:registry", List.of(new EntrypointDecl("default", "fixture.cinder.Registry::INSTANCE")));

	@Test
	void onlyTheClassesTheModsOwnJarDefinesBecomeEntrypoints() throws IOException {
		Path jar = jar(work.resolve("cinder-forge.jar"), OWN_CLASSES);
		assertEquals(OWN, CrossEcosystemDeclarations.fabricEntrypoints(mod(Ecosystem.FORGE, jar.toString())),
				"a sentence, a path, a version, a literal, a domain or a package spelled like a class, the JDK's class and "
						+ "a list with one class the jar lacks are not the mod's classes");
	}

	@Test
	void aClassDirectoryIsLookedInTheSameWay() throws IOException {
		Path classes = Files.createDirectories(work.resolve("cinder-classes"));
		for (String entry : OWN_CLASSES) {
			Path file = classes.resolve(entry);
			Files.createDirectories(file.getParent());
			Files.write(file, new byte[] {(byte) 0xCA, (byte) 0xFE});
		}
		assertEquals(OWN, CrossEcosystemDeclarations.fabricEntrypoints(mod(Ecosystem.NEOFORGE, classes.toString())));
	}

	@Test
	void aClassOnlyAMultiReleaseVersionDefinesIsTheJarsToo() throws IOException {
		Path jar = jar(work.resolve("cinder-mr.jar"), List.of("META-INF/versions/21/fixture/cinder/ForgePanel.class"));
		assertEquals(Map.of("cinderlib:panel", List.of(new EntrypointDecl("default", "fixture.cinder.ForgePanel"))),
				CrossEcosystemDeclarations.fabricEntrypoints(mod(Ecosystem.NEOFORGE, jar.toString())));
	}

	@Test
	void aJarThatCannotBeReadDefinesNothing() throws IOException {
		assertEquals(Map.of(), CrossEcosystemDeclarations.fabricEntrypoints(
				mod(Ecosystem.FORGE, work.resolve("gone.jar").toString())));
		Path notAJar = Files.writeString(work.resolve("broken.jar"), "not a zip");
		assertEquals(Map.of(), CrossEcosystemDeclarations.fabricEntrypoints(mod(Ecosystem.FORGE, notAJar.toString())));
	}

	@Test
	void aTableWithNothingClassShapedDeclaresNoEntrypointWhateverItsSource() {
		Map<String, Object> words = Map.of("cinderlib:tagline", "Warm light", "cinderlib:enabled", true);
		DiscoveredMod mod = new DiscoveredMod(Ecosystem.FORGE, "cinder_forge", "1.0.0", "Cinder", List.of(), List.of(),
				null, "\0 not a path").withModProperties(words);
		assertEquals(Map.of(), CrossEcosystemDeclarations.fabricEntrypoints(mod));
	}

	@Test
	void switchedOffNothingIsProjected() throws IOException {
		Path jar = jar(work.resolve("cinder-forge.jar"), OWN_CLASSES);
		System.setProperty(CrossEcosystemDeclarations.SWITCH, "off");
		assertEquals(Map.of(), CrossEcosystemDeclarations.fabricEntrypoints(mod(Ecosystem.FORGE, jar.toString())));
	}

	@Test
	void classShapeIsHowAFabricEntrypointSpellsAClass() {
		for (String name : List.of("a.B", "fixture.cinder.Hooks$Early", "fixture.cinder.Registry::INSTANCE", "Main",
				"_x.$y.Z1", "été.Classe")) {
			assertTrue(CrossEcosystemDeclarations.classShaped(name), name);
		}
		for (String value : List.of("", " ", "a b", "a.b.", ".a.b", "a..b", "1.2.3", "a/b/C", "a-b.C", "true", "null",
				"a.class.B", "a.B::", "a.B::1x", "::x", "a.B ", "https://cinder.example")) {
			assertFalse(CrossEcosystemDeclarations.classShaped(value), value);
		}
	}

	/**
	 * On the real mod jars the kernel is tested with, the own-jar rule takes away nothing the shape alone gave: every
	 * namespaced class-shaped property there ({@code sodium:config_api_user} in Iris, Sodium Extra, Reese's Sodium
	 * Options and others) names a class its own jar defines.
	 */
	@Test
	void onTheRealModJarsEveryClassShapedDeclarationIsTheModsOwn() throws IOException {
		Path root = Path.of("build/compat-inputs");
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY,
				Files.isDirectory(root), "build/compat-inputs is absent");
		List<Path> jars;
		// The real path: a worktree links build/compat-inputs to the main checkout's, and a walk does not enter a link.
		try (var walk = Files.walk(root.toRealPath(), 3)) {
			jars = walk.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("mods")
					&& p.getFileName().toString().endsWith(".jar")).sorted().toList();
		}
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY, !jars.isEmpty(),
				"no mod jars under build/compat-inputs/*/mods");
		net.forbric.kernel.discovery.ForbricModDiscoverer discoverer = new net.forbric.kernel.discovery.ForbricModDiscoverer();
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.TreeSet<String> declared = new java.util.TreeSet<>();
		try {
			for (Path jar : jars) {
				if (!seen.add(jar.getFileName().toString())) continue;
				List<DiscoveredMod> mods;
				try {
					mods = discoverer.discoverJar(jar);
				} catch (IOException unopenable) {
					continue;
				}
				for (DiscoveredMod mod : mods) {
					if (!mod.getEcosystem().isForgeFamily()) continue;
					Map<String, List<EntrypointDecl>> byShape = CrossEcosystemDeclarations.fabricEntrypoints(mod.getModProperties());
					assertEquals(byShape, CrossEcosystemDeclarations.fabricEntrypoints(mod),
							mod.getId() + " in " + jar.getFileName() + " names a class its own jar does not define");
					byShape.keySet().forEach(key -> declared.add(mod.getId() + " " + key));
				}
			}
		} finally {
			// Real jars include malformed manifests; what reading them recorded is not this test's business.
			net.forbric.kernel.discovery.MetadataFailures.reset();
			net.forbric.api.CompatibilityFindings.reset();
		}
		assertTrue(declared.stream().anyMatch(d -> d.endsWith(" sodium:config_api_user")),
				"the census reached the real declarations: " + declared);
	}

	// ---- fixtures ----

	private static DiscoveredMod mod(Ecosystem ecosystem, String source) {
		return new DiscoveredMod(ecosystem, "cinder_forge", "1.0.0", "Cinder", List.of(), List.of(), null, source)
				.withModProperties(properties());
	}

	private static Path jar(Path file, List<String> entries) throws IOException {
		try (OutputStream target = Files.newOutputStream(file); JarOutputStream out = new JarOutputStream(target)) {
			for (String entry : entries) {
				out.putNextEntry(new JarEntry(entry));
				out.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
				out.closeEntry();
			}
		}
		return file;
	}
}
