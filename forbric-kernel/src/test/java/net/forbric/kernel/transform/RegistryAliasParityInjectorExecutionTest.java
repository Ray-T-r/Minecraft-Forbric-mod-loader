/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.KernelRegistryAliases;

/**
 * {@link RegistryAliasParityInjector}'s output, run: a MinecraftForge registry wrapper answers a lookup through an
 * alias recorded in fabric-api's map, by {@code Identifier} and by {@code ResourceKey}, where as merged its overrides
 * miss it ({@code waystones:waystone} failing to parse, and the tags that name it dropping).
 *
 * <p>The stand-in {@code MappedRegistry} carries an alias map as a mixin adds one ({@code @MixinMerged}, ids to ids, under
 * a name no loader knows); the wrapper overrides
 * the lookups into its own entries, as {@code NamespacedWrapper} does. The hook is the kernel's real
 * {@link KernelRegistryAliases}.
 */
@ExecutesInjector(RegistryAliasParityInjector.class)
@ResourceLock("system-properties")
class RegistryAliasParityInjectorExecutionTest {
	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";
	private static final String DEFAULTED = "net.minecraftforge.registries.NamespacedDefaultedWrapper";
	private static final String IDENTIFIER = "net.minecraft.resources.Identifier";
	private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";

	private static final Map<String, String> STAND_INS = Map.of(
			IDENTIFIER, """
					package net.minecraft.resources;

					public record Identifier(String namespace, String path) {
					}
					""",
			RESOURCE_KEY, """
					package net.minecraft.resources;

					public record ResourceKey(Identifier registry, Identifier identifier) {
						public static ResourceKey create(ResourceKey registryKey, Identifier id) {
							return new ResourceKey(registryKey.identifier(), id);
						}
					}
					""",
			"net.minecraft.core.MappedRegistry", """
					package net.minecraft.core;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.resources.Identifier;
					import net.minecraft.resources.ResourceKey;

					public class MappedRegistry {
						/** What an alias mixin adds (fabric-registry-sync's is one), here under a name of no loader's; marked merged below. */
						private final Map<Identifier, Identifier> quarry$links = new HashMap<>();
						private final ResourceKey key;

						public MappedRegistry(ResourceKey key) {
							this.key = key;
						}

						public ResourceKey key() {
							return key;
						}

						public void addAlias(Identifier from, Identifier to) {
							quarry$links.put(from, to);
						}
					}
					""",
			WRAPPER, """
					package net.minecraftforge.registries;

					import java.util.HashMap;
					import java.util.Map;
					import java.util.Optional;
					import net.minecraft.core.MappedRegistry;
					import net.minecraft.resources.Identifier;
					import net.minecraft.resources.ResourceKey;

					public class NamespacedWrapper extends MappedRegistry {
						protected final Map<Identifier, String> entries = new HashMap<>();

						public NamespacedWrapper(ResourceKey key) {
							super(key);
						}

						public void register(Identifier id, String value) {
							entries.put(id, value);
						}

						public String getValue(Identifier id) {
							return entries.get(id);
						}

						public boolean containsKey(Identifier id) {
							return entries.containsKey(id);
						}

						public Optional<String> getOptional(Identifier id) {
							return Optional.ofNullable(entries.get(id));
						}

						public String getValue(ResourceKey key) {
							return entries.get(key.identifier());
						}

						public boolean containsKey(ResourceKey key) {
							return entries.containsKey(key.identifier());
						}

						/** Not one of the lookups: an alias must not leak into it. */
						public String describe(Identifier id) {
							return id.namespace() + ":" + id.path();
						}
					}
					""",
			DEFAULTED, """
					package net.minecraftforge.registries;

					import net.minecraft.resources.Identifier;
					import net.minecraft.resources.ResourceKey;

					public class NamespacedDefaultedWrapper extends NamespacedWrapper {
						public NamespacedDefaultedWrapper(ResourceKey key) {
							super(key);
						}

						@Override
						public String getValue(Identifier id) {
							String value = entries.get(id);
							return value != null ? value : "minecraft:air";
						}
					}
					""");

	@BeforeEach
	@AfterEach
	void reset() throws Throwable {
		InjectorExecution.invokeStatic(KernelRegistryAliases.class, "resetForTests");
	}

	private static Object id(ClassLoader loader, String namespace, String path) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(IDENTIFIER), namespace, path);
	}

	/** A block registry holding waystones' renamed block, with the old id recorded as an alias for it. */
	private static Object blocks(ClassLoader loader, String type) throws Throwable {
		Object key = InjectorExecution.construct(loader.loadClass(RESOURCE_KEY), id(loader, "minecraft", "root"),
				id(loader, "minecraft", "block"));
		Object registry = InjectorExecution.construct(loader.loadClass(type), key);
		InjectorExecution.invoke(registry, "register", id(loader, "waystones", "andesite_waystone"), "andesite waystone");
		InjectorExecution.invoke(registry, "addAlias", id(loader, "waystones", "waystone"), id(loader, "waystones", "andesite_waystone"));
		return registry;
	}

	private static Object key(ClassLoader loader, Object registry, String namespace, String path) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass(RESOURCE_KEY), "create", InjectorExecution.invoke(registry, "key"),
				id(loader, namespace, path));
	}

	/** The stand-ins, with the alias map marked as Mixin marks a field it merged. */
	private static Map<String, byte[]> compiled(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		org.objectweb.asm.tree.ClassNode registry = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(classes.get("net/minecraft/core/MappedRegistry")).accept(registry, 0);
		for (org.objectweb.asm.tree.FieldNode field : registry.fields) {
			if (!field.name.equals("quarry$links")) continue;
			org.objectweb.asm.tree.AnnotationNode merged = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
			merged.values = new java.util.ArrayList<>(List.of("mixin", "org.quarry.registry.mixin.LinksMixin", "priority", 1000, "sessionId", "test"));
			field.visibleAnnotations = new java.util.ArrayList<>(List.of(merged));
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		registry.accept(writer);
		classes.put("net/minecraft/core/MappedRegistry", writer.toByteArray());
		return classes;
	}

	@Test void aForgeWrappedRegistryAnswersThroughAnAliasByIdAndByKey(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compiled(work);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(WRAPPER, DEFAULTED)) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new RegistryAliasParityInjector(), target, original.get(internal),
					EnvType.SERVER));
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(WRAPPER, DEFAULTED)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}

		Object wrapper = blocks(loader, WRAPPER);
		Object old = id(loader, "waystones", "waystone");
		assertEquals("andesite waystone", InjectorExecution.invoke(wrapper, "getValue", old), "the old id resolves");
		assertEquals(true, InjectorExecution.invoke(wrapper, "containsKey", old));
		assertEquals(Optional.of("andesite waystone"), InjectorExecution.invoke(wrapper, "getOptional", old),
				"getOptional, which the wrapper overrides out of Registry's default, resolves too");
		assertEquals("andesite waystone", InjectorExecution.invoke(wrapper, "getValue", key(loader, wrapper, "waystones", "waystone")),
				"and by ResourceKey, rebuilt in the same registry");
		assertEquals(true, InjectorExecution.invoke(wrapper, "containsKey", key(loader, wrapper, "waystones", "waystone")));
		assertEquals(false, InjectorExecution.invoke(wrapper, "containsKey", id(loader, "waystones", "missing")));
		assertEquals("waystones:waystone", InjectorExecution.invoke(wrapper, "describe", old), "only lookups are rewritten");
		assertEquals("andesite waystone", InjectorExecution.invoke(blocks(loader, DEFAULTED), "getValue", old),
				"the defaulted wrapper's own override resolves as well");

		ClassLoader merged = InjectorExecution.load(original);
		assertNull(InjectorExecution.invoke(blocks(merged, WRAPPER), "getValue", id(merged, "waystones", "waystone")),
				"premise: as merged, the alias is recorded and never read");
		assertEquals("minecraft:air", InjectorExecution.invoke(blocks(merged, DEFAULTED), "getValue", id(merged, "waystones", "waystone")));
	}
}
