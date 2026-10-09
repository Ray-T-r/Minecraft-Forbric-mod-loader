/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.kernel.classloading.LoaderProbePolicy.Family;
import net.forbric.kernel.transform.InjectorExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The merged base's ancestor bridge is a build-time artifact: one platform carrier's class rebased onto another
 * carrier's equivalent ancestor. The loader reports that edge as it defines it, from the bytes and carriers it actually
 * serves. The fixtures share no name with any platform or mod: two made-up carriers, a made-up merged child, a mod
 * jar and look-alikes.
 */
class PlatformAncestorBridgesTest {
	@TempDir Path work;

	private static final Map<String, String> SOURCES = new LinkedHashMap<>();
	static {
		// carrier "beta": the ancestor the bridge points at, and a same-carrier control pair
		SOURCES.put("unrelated.beta.Shape", "package unrelated.beta; public abstract class Shape { protected Shape() {} }");
		SOURCES.put("unrelated.beta.Base", "package unrelated.beta; public class Base {}");
		SOURCES.put("unrelated.beta.Local", "package unrelated.beta; public class Local extends Base {}");
		// carrier "alpha": its Shape was rebased onto beta's (the bridge); the others are look-alikes
		SOURCES.put("unrelated.alpha.Shape", "package unrelated.alpha; public abstract class Shape extends unrelated.beta.Shape { protected Shape() {} }");
		SOURCES.put("unrelated.alpha.Base", "package unrelated.alpha; public class Base {}");
		SOURCES.put("unrelated.alpha.Local", "package unrelated.alpha; public class Local extends Base {}");
		SOURCES.put("unrelated.alpha.OnGame", "package unrelated.alpha; public class OnGame extends unrelated.game.Root {}");
		// unowned game jar: a merged child that keeps alpha's ancestor, and a plain root
		SOURCES.put("unrelated.game.Root", "package unrelated.game; public class Root {}");
		SOURCES.put("unrelated.game.Child", "package unrelated.game; public class Child extends unrelated.alpha.Shape {}");
		// a mod jar of one family whose class extends the other carrier's type: not a carrier, not a bridge
		SOURCES.put("unrelated.addon.Lookalike", "package unrelated.addon; public abstract class Lookalike extends unrelated.beta.Shape {}");
	}

	@Test void theCrossCarrierEdgeIsReportedOnceAsTheLoaderDefinesIt() throws Exception {
		try (ForbricClassLoader loader = loader()) {
			Class<?> child = loader.loadClass("unrelated.game.Child");
			Class<?> alpha = loader.loadClass("unrelated.alpha.Shape"), beta = loader.loadClass("unrelated.beta.Shape");
			assertSame(loader, child.getClassLoader());
			assertTrue(alpha.isAssignableFrom(child) && beta.isAssignableFrom(child), "the merged child is both carriers' type");
			assertEquals(List.of(new PlatformAncestorBridges.Bridge("unrelated.alpha.Shape", Family.FORGE, "unrelated.beta.Shape", Family.NEOFORGE)),
					loader.platformAncestorBridges(), "defining the child's ancestor is when the bridge takes effect");
			loader.loadClass("unrelated.alpha.Shape");
			assertEquals(1, loader.platformAncestorBridges().size(), "reported once");
			String line = loader.platformAncestorBridges().getFirst().describe();
			assertTrue(line.startsWith("[Forbric/Hierarchy] unrelated.alpha.Shape, served by the FORGE runtime, extends unrelated.beta.Shape, "
					+ "served by the NEOFORGE runtime"), line);
		}
	}

	@Test void edgesWithinOneCarrierOutsideTheCarriersOrOntoTheGameAreLeftAlone() throws Exception {
		try (ForbricClassLoader loader = loader()) {
			for (String name : List.of("unrelated.alpha.Local", "unrelated.beta.Local", "unrelated.alpha.OnGame", "unrelated.addon.Lookalike", "unrelated.game.Root"))
				assertSame(loader, loader.loadClass(name).getClassLoader(), name);
			assertEquals(List.of(), loader.platformAncestorBridges(),
					"same-carrier, carrier-onto-game and mod-jar-onto-carrier edges are not merge bridges");
		}
	}

	@Test void aLoaderWithNoCarriersObservesNothing() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		Path everything = jar("everything", classes, prefix -> true);
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[]{everything.toUri().toURL()}, getClass().getClassLoader())) {
			assertNotNull(loader.loadClass("unrelated.game.Child"));
			assertEquals(List.of(), loader.platformAncestorBridges(), "without carrier provenance there is no platform to compare");
		}
	}

	@Test void theDecisionReadsTheDefinitionAndTheServingCarrierOnly() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		Function<String, Family> carriers = internal -> internal.startsWith("unrelated/beta/") ? Family.NEOFORGE
				: internal.startsWith("unrelated/alpha/") ? Family.FORGE : null;
		assertEquals(new PlatformAncestorBridges.Bridge("unrelated.alpha.Shape", Family.FORGE, "unrelated.beta.Shape", Family.NEOFORGE),
				PlatformAncestorBridges.of(classes.get("unrelated/alpha/Shape"), Family.FORGE, carriers));
		assertNull(PlatformAncestorBridges.of(classes.get("unrelated/alpha/Shape"), Family.NEOFORGE, carriers),
				"the same bytes served from the ancestor's own carrier are an ordinary edge");
		assertNull(PlatformAncestorBridges.of(classes.get("unrelated/alpha/Shape"), null, carriers), "not served by a carrier");
		assertNull(PlatformAncestorBridges.of(classes.get("unrelated/alpha/Local"), Family.FORGE, carriers));
		assertNull(PlatformAncestorBridges.of(classes.get("unrelated/alpha/OnGame"), Family.FORGE, carriers));
		assertNull(PlatformAncestorBridges.of(new byte[]{1, 2, 3}, Family.FORGE, carriers), "unreadable bytes are no evidence");
		assertNull(PlatformAncestorBridges.of(null, Family.FORGE, carriers));
	}

	/** alpha and beta are registered as two platforms' runtime carriers; the addon jar is a mod of alpha's family. */
	private ForbricClassLoader loader() throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		Path alpha = jar("renamed-carrier-a", classes, name -> name.startsWith("unrelated/alpha/"));
		Path beta = jar("renamed-carrier-b", classes, name -> name.startsWith("unrelated/beta/"));
		Path game = jar("renamed-game", classes, name -> name.startsWith("unrelated/game/"));
		Path addon = jar("renamed-addon", classes, name -> name.startsWith("unrelated/addon/"));
		ForbricClassLoader loader = new ForbricClassLoader(new URL[]{game.toUri().toURL(), alpha.toUri().toURL(),
				beta.toUri().toURL(), addon.toUri().toURL()}, getClass().getClassLoader());
		loader.setJarFamilies(Map.of(addon, Family.FORGE));
		loader.addRuntimeJarFamily(alpha, Family.FORGE);
		loader.addRuntimeJarFamily(beta, Family.NEOFORGE);
		return loader;
	}

	private Path jar(String name, Map<String, byte[]> classes, java.util.function.Predicate<String> include) throws Exception {
		Path jar = work.resolve(name + ".jar");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			for (var entry : classes.entrySet()) {
				if (!include.test(entry.getKey())) continue;
				out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return jar;
	}
}
