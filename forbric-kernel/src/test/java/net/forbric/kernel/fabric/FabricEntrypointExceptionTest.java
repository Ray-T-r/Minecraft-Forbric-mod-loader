/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.EntrypointException;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * Fabric Loader's contract for an entrypoint that cannot be built: {@code getEntrypoints} builds every entry and then
 * throws one {@link EntrypointException} for the key, naming the first failing mod; a container's
 * {@code getEntrypoint()} throws one for its own entry. A mod handles that by catching the type — JEI's Fabric client
 * lifecycle catches it around its plugin lookup — so the class must exist (JEI's whole client startup died on
 * {@code NoClassDefFoundError} without it) and must be what is thrown.
 */
@ResourceLock("KernelFabricEcosystem")
class FabricEntrypointExceptionTest {
	static final List<String> BUILT = new ArrayList<>();

	@TempDir Path dir;
	private KernelFabricLoader loader;

	@BeforeEach void setUp() {
		KernelFabricLoader.resetForTests();
		BUILT.clear();
		loader = KernelFabricLoader.create(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(getClass().getClassLoader());
		register("abroken", Broken.class.getName());
		register("bhealthy", Healthy.class.getName());
		register("cbroken", Broken.class.getName());
		loader.freeze();
	}

	@Test void getEntrypointsBuildsEveryEntryThenThrowsOneExceptionForTheKey() {
		EntrypointException thrown = assertThrows(EntrypointException.class, () -> loader.getEntrypoints("jei_plugin", Runnable.class));
		assertEquals("jei_plugin", thrown.getKey());
		assertEquals("Exception while loading entries for entrypoint 'jei_plugin' provided by 'abroken'", thrown.getMessage());
		Throwable root = thrown.getCause();
		while (root != null && root.getCause() != null) root = root.getCause();
		assertInstanceOf(IllegalStateException.class, root, "the entry's own failure is what it was caused by");
		assertEquals(1, thrown.getSuppressed().length, "the second failing entry is suppressed into the first");
		assertEquals(List.of("healthy"), BUILT, "the healthy entry was still built");
	}

	@Test void aContainersEntrypointThrowsTheExceptionForItsOwnEntry() {
		List<EntrypointContainer<Runnable>> containers = loader.getEntrypointContainers("jei_plugin", Runnable.class);
		assertEquals(3, containers.size());
		EntrypointException thrown = assertThrows(EntrypointException.class, () -> containers.get(2).getEntrypoint());
		assertEquals("jei_plugin", thrown.getKey());
		assertTrue(thrown.getMessage().endsWith("provided by 'cbroken'"), thrown.getMessage());
		assertNotNull(containers.get(1).getEntrypoint());
	}

	/** What a mod's catch of the type compiles to: the exception table names the class, so it links only if it exists. */
	@Test void aModThatCatchesItLinksAndCatchesIt() {
		String outcome;
		try {
			loader.getEntrypoints("jei_plugin", Runnable.class);
			outcome = "no failure";
		} catch (EntrypointException handled) {
			outcome = "handled " + handled.getKey();
		}
		assertEquals("handled jei_plugin", outcome);
	}

	private void register(String id, String type) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"jei_plugin\":[\"" + type + "\"]}}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}

	public static final class Broken implements Runnable {
		public Broken() { throw new IllegalStateException("plugin constructor failed"); }
		@Override public void run() { }
	}

	public static final class Healthy implements Runnable {
		public Healthy() { BUILT.add("healthy"); }
		@Override public void run() { }
	}
}
