/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.KernelRegistryDirectories;

/**
 * {@link RegistryDirectoryOwnerInjector}'s output, run against the kernel's real boot-side
 * {@code KernelRegistryDirectories}: {@code Registries.registryDirPath} answers a Fabric mod's registry with vanilla's
 * directory, which fabric-registry-sync's modifier then prefixes (or WorldWeaver's own mixin keeps as it is) — where as
 * merged NeoForge's body prefixed it first, and WorldWeaver's world presets and biome data were read from a directory
 * nothing ships. A NeoForge mod's registry and vanilla's keep the merged answer, and so does every registry when
 * fabric-registry-sync's modifier is not in {@code Registries}.
 *
 * <p>The stand-in {@code Registries} has NeoForge's body (the namespace put in front by {@code CommonHooks}) and an
 * {@code elementsDirPath} whose return a merged return-value modifier, under a name of no loader's, prefixes with the
 * namespace after the body — what fabric-registry-sync's mixin leaves. The hook asks {@code elementsDirPath} itself
 * whether that happens. Mod ownership comes from {@code ModPresence}, published as a boot publishes it.
 */
@ExecutesInjector(RegistryDirectoryOwnerInjector.class)
@ResourceLock("system-properties")
@ResourceLock("ModPresence")
class RegistryDirectoryOwnerInjectorExecutionTest {
	private static final String REGISTRIES = RegistryDirectoryOwnerInjector.TARGET;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public final class Identifier {
						private final String namespace;
						private final String path;

						public Identifier(String namespace, String path) {
							this.namespace = namespace;
							this.path = path;
						}

						public String getNamespace() {
							return namespace;
						}

						public String getPath() {
							return path;
						}

						public static Identifier fromNamespaceAndPath(String namespace, String path) {
							return new Identifier(namespace, path);
						}
					}
					""",
			"net.minecraft.resources.ResourceKey", """
					package net.minecraft.resources;

					public final class ResourceKey<T> {
						private final Identifier identifier;

						public ResourceKey(Identifier identifier) {
							this.identifier = identifier;
						}

						public Identifier identifier() {
							return identifier;
						}

						public static <T> ResourceKey<T> createRegistryKey(Identifier identifier) {
							return new ResourceKey<>(identifier);
						}
					}
					""",
			"net.neoforged.neoforge.common.CommonHooks", """
					package net.neoforged.neoforge.common;

					import net.minecraft.resources.Identifier;

					public class CommonHooks {
						public static String prefixNamespace(Identifier id) {
							return id.getNamespace().equals("minecraft") ? id.getPath() : id.getNamespace() + "/" + id.getPath();
						}
					}
					""",
			REGISTRIES, """
					package net.minecraft.core.registries;

					import net.minecraft.resources.ResourceKey;
					import net.neoforged.neoforge.common.CommonHooks;

					public class Registries {
						/** NeoForge's body. */
						public static String registryDirPath(ResourceKey<?> key) {
							return CommonHooks.prefixNamespace(key.identifier());
						}

						public static String elementsDirPath(ResourceKey<?> key) {
							return quarry$prependNamespace(registryDirPath(key), key);
						}

						/** A return-value modifier as Mixin merges one, after the body, under a name of no loader's. */
						private static String quarry$prependNamespace(String path, ResourceKey<?> key) {
							String namespace = key.identifier().getNamespace();
							return namespace.equals("minecraft") ? path : namespace + "/" + path;
						}
					}
					""");

	/** The same Registries with nothing after the body: no modifier puts the namespace back. */
	private static final String WITHOUT_MODIFIER = """
			package net.minecraft.core.registries;

			import net.minecraft.resources.ResourceKey;
			import net.neoforged.neoforge.common.CommonHooks;

			public class Registries {
				/** NeoForge's body. */
				public static String registryDirPath(ResourceKey<?> key) {
					return CommonHooks.prefixNamespace(key.identifier());
				}

				public static String elementsDirPath(ResourceKey<?> key) {
					return registryDirPath(key);
				}
			}
			""";

	@BeforeEach @AfterEach void reset() {
		System.clearProperty(RegistryDirectoryOwnerInjector.PROPERTY);
		ModPresence.publishForgeFamily(List.of());
		ModPresence.publishFabric(List.of());
		// Unknown, as on a fresh boot: the hook asks the calling Registries whether something puts the namespace back.
		KernelRegistryDirectories.resetForTests(null);
	}

	private static DiscoveredMod mod(Ecosystem ecosystem, String id) {
		return new DiscoveredMod(ecosystem, id, "1.0.0", id, List.of(), List.of(), null, id + ".jar");
	}

	/** A pack with WorldWeaver on Fabric and a NeoForge mod. */
	private static void publish() {
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "wover")));
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.NEOFORGE, "examplemod")));
	}

	private static String dir(ClassLoader loader, String namespace, String path) throws Throwable {
		Object id = InjectorExecution.construct(loader.loadClass("net.minecraft.resources.Identifier"), namespace, path);
		Object key = InjectorExecution.construct(loader.loadClass("net.minecraft.resources.ResourceKey"), id);
		return (String) InjectorExecution.invokeStatic(loader.loadClass(REGISTRIES), "registryDirPath", key);
	}

	private static Map<String, byte[]> owned(Map<String, byte[]> original) {
		String internal = REGISTRIES.replace('.', '/');
		byte[] out = InjectorExecution.transform(new RegistryDirectoryOwnerInjector(), REGISTRIES, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), out, "every return was handed to the owner");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, out);
		return classes;
	}

	@Test void aFabricRegistryGetsVanillasDirectoryForItsOwnersMixins(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = owned(original);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(REGISTRIES.replace('.', '/')), loader));
		publish();

		assertEquals("worldgen/world_preset", dir(loader, "wover", "worldgen/world_preset"),
				"WorldWeaver's registry gets vanilla's directory, as on native Fabric");
		assertEquals("examplemod/machines", dir(loader, "examplemod", "machines"), "a NeoForge mod's registry keeps NeoForge's");
		assertEquals("worldgen/biome", dir(loader, "minecraft", "worldgen/biome"));

		assertEquals("wover/worldgen/world_preset", dir(InjectorExecution.load(original), "wover", "worldgen/world_preset"),
				"premise: as merged, NeoForge's body prefixed the Fabric registry before its owner's mixins could decide");
		byte[] once = classes.get(REGISTRIES.replace('.', '/'));
		assertSame(once, InjectorExecution.transform(new RegistryDirectoryOwnerInjector(), REGISTRIES, once, EnvType.SERVER), "edited once");
	}

	@Test void withoutAModifierAfterTheBodyEveryRegistryKeepsTheMergedDirectory(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(REGISTRIES, WITHOUT_MODIFIER);
		Map<String, byte[]> without = InjectorExecution.compile(work, sources);
		publish();
		assertEquals("wover/worldgen/world_preset", dir(InjectorExecution.load(owned(without)), "wover", "worldgen/world_preset"),
				"nothing would put the namespace back, so the hook keeps the merged answer");
	}

	@Test void switchedOffRegistriesIsLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, STAND_INS).get(REGISTRIES.replace('.', '/'));
		System.setProperty(RegistryDirectoryOwnerInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new RegistryDirectoryOwnerInjector(), REGISTRIES, bytes, EnvType.SERVER));
	}
}
