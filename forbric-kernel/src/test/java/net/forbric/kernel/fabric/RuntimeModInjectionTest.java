/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Essential's loader registers each jar it unpacks at {@code preLaunch} as a mod through Fabric Loader's internals: it parses
 * the jar's fabric.mod.json with {@code ModMetadataParser}, builds {@code new ModContainerImpl(ModCandidateImpl.createPlain(...))},
 * finds {@code modMap}, {@code mods}, {@code entrypointStorage} and {@code adapterMap} on {@code FabricLoader.getInstance()}'s
 * class, adds the container to the first two, its entrypoints to the storage, and its mixin configs to Mixin. None of that
 * existed in the kernel, so every one of its 21 jars stayed out ("Failed to add dummy mod container" on every boot): Essential
 * missing from the mod list, and its bundled fabric-networking's mixins never registered. {@link #essentialLikeInjection} is
 * that sequence, reflective call for reflective call.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("system-properties")
class RuntimeModInjectionTest {
	static final List<String> RAN = new ArrayList<>();

	@TempDir Path dir;
	private KernelFabricLoader loader;

	@BeforeEach void setUp() {
		KernelFabricLoader.resetForTests();
		RAN.clear();
		loader = KernelFabricLoader.create(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(getClass().getClassLoader());
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(
				"{\"schemaVersion\":1,\"id\":\"wrapper\",\"version\":\"1\"}")), null, null));
		loader.freeze();
	}

	@AfterEach void tearDown() {
		KernelFabricLoader.resetForTests();
		KernelFabricEcosystem.resetPhasesForTests();
		KernelLanguageAdapters.reset();
		RAN.clear();
	}

	@Test void aModRegisteredAtRunTimeIsAModWithItsEntrypointsAndMixinConfigs() throws Exception {
		Path jar = jar("unpacked.jar", "{\"schemaVersion\":1,\"id\":\"unpacked\",\"version\":\"2.0\",\"name\":\"Unpacked\","
				+ "\"entrypoints\":{\"main\":[\"" + UnpackedMain.class.getName() + "\"]},"
				+ "\"mixins\":[\"unpacked.mixins.json\",{\"config\":\"unpacked.server.mixins.json\",\"environment\":\"server\"}]}");
		Collection<String> configs = essentialLikeInjection(jar);

		assertEquals(List.of("unpacked.mixins.json"), List.copyOf(configs), "the client's configs, as Fabric Loader lists them");
		assertTrue(loader.isModLoaded("unpacked"));
		assertEquals("2.0", loader.getModContainer("unpacked").orElseThrow().getMetadata().getVersion().getFriendlyString());
		assertTrue(loader.getAllMods().stream().anyMatch(m -> m.getMetadata().getId().equals("unpacked")));
		assertFalse(loader.getModContainer("unpacked").orElseThrow().getRootPaths().isEmpty(), "its jar is its root");

		List<String> adopted = loader.adoptFabricStorage(Set.of("preLaunch"));
		assertEquals(List.of("main:unpacked->" + UnpackedMain.class.getName()), adopted);
		for (EntrypointContainer<ModInitializer> c : loader.getEntrypointContainers("main", ModInitializer.class)) c.getEntrypoint().onInitialize();
		assertEquals(List.of("UnpackedMain"), RAN, "the added entrypoint runs in its phase, as natively");
	}

	/** Essential stage2's LoaderInternals: parseModMetadata, createCandidate, injectFakeMod — by reflection, as it calls them. */
	@SuppressWarnings("unchecked")
	private Collection<String> essentialLikeInjection(Path jar) throws Exception {
		Class<?> parser = Class.forName("net.fabricmc.loader.impl.metadata.ModMetadataParser");
		Method parse = parser.getDeclaredMethod("parseMetadata", InputStream.class, String.class, List.class, VersionOverrides.class,
				DependencyOverrides.class, boolean.class);
		Object metadata;
		try (var zip = new java.util.zip.ZipFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry("fabric.mod.json"))) {
			metadata = parse.invoke(null, in, jar.toString(), Collections.emptyList(), VersionOverrides.class.getConstructor().newInstance(),
					DependencyOverrides.class.getConstructor(Path.class).newInstance(Paths.get("_invalid_")), FabricLoader.getInstance().isDevelopmentEnvironment());
		}
		Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
		createPlain.setAccessible(true);
		Object candidate = createPlain.invoke(null, Collections.singletonList(jar), metadata, false, Collections.emptyList());
		Object container = ModContainerImpl.class.getConstructor(candidate.getClass()).newInstance(candidate);

		Object fabricLoader = FabricLoader.getInstance();
		Class<?> owner = fabricLoader.getClass();
		while (owner != null && !hasAll(owner)) owner = owner.getSuperclass();
		assertNotNull(owner, "Could not find the required fields [modMap, mods, entrypointStorage, adapterMap]");
		Field modMap = owner.getDeclaredField("modMap"), mods = owner.getDeclaredField("mods"),
				storage = owner.getDeclaredField("entrypointStorage"), adapters = owner.getDeclaredField("adapterMap");
		for (Field f : List.of(modMap, mods, storage, adapters)) f.setAccessible(true);
		((List<Object>) mods.get(fabricLoader)).add(container);
		((Map<String, Object>) modMap.get(fabricLoader)).put(((LoaderModMetadata) metadata).getId(), container);
		Object entrypoints = storage.get(fabricLoader);
		Method add = entrypoints.getClass().getDeclaredMethod("add", ModContainerImpl.class, String.class, EntrypointMetadata.class, Map.class);
		add.setAccessible(true);
		Method keys = LoaderModMetadata.class.getDeclaredMethod("getEntrypointKeys"), byKey = LoaderModMetadata.class.getDeclaredMethod("getEntrypoints", String.class);
		for (String key : (Collection<String>) keys.invoke(metadata))
			for (Object entry : (List<Object>) byKey.invoke(metadata, key)) add.invoke(entrypoints, container, key, entry, adapters.get(fabricLoader));
		return (Collection<String>) LoaderModMetadata.class.getDeclaredMethod("getMixinConfigs", EnvType.class).invoke(metadata, EnvType.CLIENT);
	}

	private static boolean hasAll(Class<?> type) {
		try {
			for (String name : List.of("modMap", "mods", "entrypointStorage", "adapterMap")) type.getDeclaredField(name);
			return true;
		} catch (NoSuchFieldException absent) {
			return false;
		}
	}

	private Path jar(String name, String fabricModJson) throws Exception {
		Path jar = dir.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new JarEntry("fabric.mod.json"));
			out.write(fabricModJson.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return jar;
	}

	public static final class UnpackedMain implements ModInitializer {
		@Override public void onInitialize() {
			RAN.add("UnpackedMain");
		}
	}
}
