/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.KernelRegistryRevert;
import net.forbric.kernel.ExpectCaught;

/**
 * {@link RegistrySyncParityInjector}'s output, run against the kernel's real boot-side {@code KernelForgeWrapperSync}
 * and {@code KernelRegistryRevert}: a client joining a server takes the server's ids for a MinecraftForge-wrapped
 * registry through NeoForge's sync, and gets its own back on disconnect, where as merged NeoForge's
 * {@code registerIdMapping} threw on the wrapper's empty vanilla fields and the client was dropped with "Failed to sync
 * registries from the server". fabric-api's sync reaches the wrapper the same way, and MinecraftForge's pending tags
 * answer NeoForge's {@code contents()} instead of {@code AbstractMethodError}.
 *
 * <p>The stand-ins are the merged shapes: a {@code MappedRegistry} with NeoForge's protected id remapping over vanilla's
 * fields, MinecraftForge's {@code NamespacedWrapper} delegating every lookup to its {@code ForgeRegistry} (its third
 * anonymous class is the pending tags), MinecraftForge's {@code GameData.injectSnapshot} re-adding entries at the
 * snapshot's ids, NeoForge's {@code RegistryManager.applySnapshot} loop, and fabric-api's
 * {@code ClientRegistrySyncHandler.apply}. fastutil's {@code Object2IntMap} and Guava's {@code ImmutableMap} are not on
 * the test classpath, so they are stand-ins too.
 */
@ExecutesInjector(RegistrySyncParityInjector.class)
@ResourceLock("KernelForgeWrapperSync")
class RegistrySyncParityInjectorExecutionTest {
	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";
	private static final String PENDING = WRAPPER + "$3";
	private static final String MANAGER = "net.neoforged.neoforge.registries.RegistryManager";
	private static final String FABRIC = "net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler";
	private static final List<String> TARGETS = List.of(WRAPPER, PENDING, MANAGER, FABRIC);

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"net.minecraft.resources.Identifier", "package net.minecraft.resources; public record Identifier(String id) { }",
			"net.minecraft.resources.ResourceKey", "package net.minecraft.resources; public record ResourceKey(Identifier identifier) { }",
			"com.google.common.collect.ImmutableMap", """
					package com.google.common.collect;

					import java.util.AbstractMap;
					import java.util.Map;
					import java.util.Set;

					public final class ImmutableMap<K, V> extends AbstractMap<K, V> {
						private final Map<K, V> entries;

						private ImmutableMap(Map<K, V> entries) {
							this.entries = Map.copyOf(entries);
						}

						public static <K, V> ImmutableMap<K, V> copyOf(Map<K, V> entries) {
							return new ImmutableMap<>(entries);
						}

						@Override
						public Set<Map.Entry<K, V>> entrySet() {
							return entries.entrySet();
						}
					}
					""",
			"it.unimi.dsi.fastutil.objects.Object2IntMap", """
					package it.unimi.dsi.fastutil.objects;

					public interface Object2IntMap<K> extends java.util.Map<K, Integer> {
					}
					""",
			"it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap", """
					package it.unimi.dsi.fastutil.objects;

					public class Object2IntLinkedOpenHashMap<K> extends java.util.LinkedHashMap<K, Integer> implements Object2IntMap<K> {
						public int put(K key, int value) {
							Integer old = super.put(key, value);
							return old == null ? -1 : old;
						}
					}
					""",
			"net.fabricmc.fabric.impl.registry.sync.RemappableRegistry", """
					package net.fabricmc.fabric.impl.registry.sync;

					import it.unimi.dsi.fastutil.objects.Object2IntMap;

					public interface RemappableRegistry {
						enum RemapMode { AUTHORITATIVE, REMOTE, EXACT }

						void remap(Object2IntMap<?> ids, RemapMode mode);

						void unmap();
					}
					""",
			"net.neoforged.neoforge.registries.PendingTagsExtension", """
					package net.neoforged.neoforge.registries;

					/** Stands in for NeoForge's extension of Registry.PendingTags. */
					public interface PendingTagsExtension {
						java.util.Map<?, ?> contents();
					}
					""",
			"net.minecraft.core.IdMap", """
					package net.minecraft.core;

					public interface IdMap<T> extends Iterable<T> {
						int getId(T value);
					}
					""",
			"net.minecraft.core.Registry", """
					package net.minecraft.core;

					import net.minecraft.resources.Identifier;
					import net.minecraft.resources.ResourceKey;
					import net.neoforged.neoforge.registries.PendingTagsExtension;

					public interface Registry<T> extends IdMap<T> {
						ResourceKey key();

						T getValue(Identifier id);

						Identifier getKey(T value);

						interface PendingTags<T> extends PendingTagsExtension {
							int size();
						}
					}
					""",
			"net.neoforged.neoforge.registries.BaseMappedRegistry", """
					package net.neoforged.neoforge.registries;

					import net.minecraft.resources.ResourceKey;

					/** NeoForge's additions, reached by RegistryManager from its own package. */
					public abstract class BaseMappedRegistry {
						protected abstract void unfreeze(boolean clearTags);

						protected abstract void clear(boolean full);

						protected abstract void registerIdMapping(ResourceKey key, int id);

						protected abstract void freeze();

						protected abstract boolean containsKey(ResourceKey key);
					}
					"""));

	static {
		STAND_INS.put("net.minecraft.core.MappedRegistry", """
				package net.minecraft.core;

				import java.util.ArrayList;
				import java.util.HashMap;
				import java.util.Iterator;
				import java.util.Map;
				import it.unimi.dsi.fastutil.objects.Object2IntMap;
				import net.fabricmc.fabric.impl.registry.sync.RemappableRegistry;
				import net.minecraft.resources.Identifier;
				import net.minecraft.resources.ResourceKey;
				import net.neoforged.neoforge.registries.BaseMappedRegistry;

				public class MappedRegistry<T> extends BaseMappedRegistry implements Registry<T>, RemappableRegistry {
					public record Holder<T>(T value) {
					}

					private final ResourceKey key;
					/** Vanilla's fields, which MinecraftForge's wrapper leaves empty for life. */
					private final Map<Identifier, Holder<T>> byKey = new HashMap<>();
					private final Map<T, Integer> toId = new HashMap<>();

					public MappedRegistry(ResourceKey key) {
						this.key = key;
					}

					public ResourceKey key() {
						return key;
					}

					public T getValue(Identifier id) {
						Holder<T> holder = byKey.get(id);
						return holder == null ? null : holder.value();
					}

					public Identifier getKey(T value) {
						for (var entry : byKey.entrySet()) if (entry.getValue().value() == value) return entry.getKey();
						return null;
					}

					public int getId(T value) {
						return toId.getOrDefault(value, -1);
					}

					public Iterator<T> iterator() {
						return new ArrayList<>(toId.keySet()).iterator();
					}

					protected void unfreeze(boolean clearTags) {
					}

					protected void clear(boolean full) {
						toId.clear();
					}

					/** NeoForge's: through vanilla's fields. */
					protected void registerIdMapping(ResourceKey key, int id) {
						Holder<T> holder = byKey.get(key.identifier());
						toId.put(holder.value(), id);
					}

					protected void freeze() {
					}

					protected boolean containsKey(ResourceKey key) {
						return byKey.containsKey(key.identifier());
					}

					/** fabric-api's mixin method: through the same fields. */
					public void remap(Object2IntMap<?> ids, RemappableRegistry.RemapMode mode) {
						for (var entry : ids.entrySet()) {
							Holder<T> holder = byKey.get((Identifier) entry.getKey());
							if (holder != null) toId.put(holder.value(), entry.getValue());
						}
					}

					public void unmap() {
					}
				}
				""");
		STAND_INS.put("net.minecraftforge.registries.ForgeRegistry", """
				package net.minecraftforge.registries;

				import java.util.ArrayList;
				import java.util.LinkedHashMap;
				import java.util.List;
				import java.util.Map;
				import net.minecraft.resources.Identifier;

				public class ForgeRegistry<T> {
					public static class Snapshot {
						public final it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap<Identifier> ids =
								new it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap<>();
					}

					final Map<Identifier, T> byName = new LinkedHashMap<>();
					final Map<T, Integer> ids = new LinkedHashMap<>();

					public T register(String name, T value) {
						byName.put(new Identifier(name), value);
						ids.put(value, ids.size());
						return value;
					}

					/** What injectSnapshot's sync does: every entry re-added at the snapshot's id. */
					void loadIds(Snapshot snapshot) {
						ids.clear();
						for (var entry : snapshot.ids.entrySet()) ids.put(byName.get(entry.getKey()), entry.getValue());
					}

					List<T> values() {
						return new ArrayList<>(ids.keySet());
					}
				}
				""");
		STAND_INS.put("net.minecraftforge.registries.GameData", """
				package net.minecraftforge.registries;

				import java.util.ArrayList;
				import java.util.HashMap;
				import java.util.List;
				import java.util.Map;
				import net.minecraft.resources.Identifier;

				public class GameData {
					static final Map<Identifier, ForgeRegistry<?>> REGISTRIES = new HashMap<>();

					/** MinecraftForge's own remap path, what a MinecraftForge client runs on login. */
					public static List<Identifier> injectSnapshot(Map<Identifier, ForgeRegistry.Snapshot> snapshots, boolean injectFrozenData,
							boolean isLocalWorld) {
						for (var entry : snapshots.entrySet()) REGISTRIES.get(entry.getKey()).loadIds(entry.getValue());
						return new ArrayList<>();
					}
				}
				""");
		STAND_INS.put(WRAPPER, """
				package net.minecraftforge.registries;

				import java.util.Iterator;
				import java.util.function.Supplier;
				import com.google.common.collect.ImmutableMap;
				import net.minecraft.core.MappedRegistry;
				import net.minecraft.core.Registry;
				import net.minecraft.resources.Identifier;
				import net.minecraft.resources.ResourceKey;

				/** MinecraftForge's: one store with the ForgeRegistry, every lookup delegated. */
				public class NamespacedWrapper<T> extends MappedRegistry<T> {
					private final ForgeRegistry<T> delegate;

					public NamespacedWrapper(ForgeRegistry<T> delegate, ResourceKey key) {
						super(key);
						this.delegate = delegate;
						GameData.REGISTRIES.put(key.identifier(), delegate);
					}

					@Override
					public T getValue(Identifier id) {
						return delegate.byName.get(id);
					}

					@Override
					public Identifier getKey(T value) {
						for (var entry : delegate.byName.entrySet()) if (entry.getValue() == value) return entry.getKey();
						return null;
					}

					@Override
					public int getId(T value) {
						return delegate.ids.getOrDefault(value, -1);
					}

					@Override
					public Iterator<T> iterator() {
						return delegate.values().iterator();
					}

					@Override
					protected boolean containsKey(ResourceKey key) {
						return delegate.byName.containsKey(key.identifier());
					}

					public Supplier<String> first() {
						return new Supplier<>() {
							public String get() {
								return "first";
							}
						};
					}

					public Supplier<String> second() {
						return new Supplier<>() {
							public String get() {
								return "second";
							}
						};
					}

					/** The third anonymous class: MinecraftForge's pending tags, compiled before NeoForge's contents(). */
					public Registry.PendingTags<T> prepareTagReload(ImmutableMap<Identifier, String> newBindings) {
						return new Registry.PendingTags<T>() {
							public int size() {
								return newBindings.size();
							}

							public java.util.Map<?, ?> contents() {
								throw new AssertionError("renamed away in the class file");
							}
						};
					}
				}
				""");
		STAND_INS.put(MANAGER, """
				package net.neoforged.neoforge.registries;

				import java.util.HashMap;
				import java.util.LinkedHashMap;
				import java.util.Map;
				import java.util.Set;
				import java.util.TreeMap;
				import net.minecraft.core.MappedRegistry;
				import net.minecraft.resources.Identifier;
				import net.minecraft.resources.ResourceKey;

				public class RegistryManager {
					public enum SnapshotType { SYNC_TO_CLIENT, FULL }

					public static final class RegistrySnapshot {
						private final Map<Integer, Identifier> ids;

						public RegistrySnapshot(Map<Integer, Identifier> ids) {
							this.ids = new TreeMap<>(ids);
						}

						public Map<Integer, Identifier> getIds() {
							return ids;
						}
					}

					public static final Map<Identifier, MappedRegistry<?>> REGISTRIES = new LinkedHashMap<>();

					@SuppressWarnings({"rawtypes", "unchecked"})
					public static Map<Identifier, RegistrySnapshot> takeSnapshot(SnapshotType type) {
						Map<Identifier, RegistrySnapshot> snapshot = new HashMap<>();
						for (var entry : REGISTRIES.entrySet()) {
							MappedRegistry registry = entry.getValue();
							Map<Integer, Identifier> ids = new HashMap<>();
							for (Object value : registry) ids.put(registry.getId(value), registry.getKey(value));
							snapshot.put(entry.getKey(), new RegistrySnapshot(ids));
						}
						return snapshot;
					}

					/** NeoForge's client half of the configuration-phase sync. */
					public static Set<ResourceKey> applySnapshot(Map<Identifier, RegistrySnapshot> snapshots, boolean isLocalWorld) {
						Set<ResourceKey> missing = new java.util.HashSet<>();
						for (var entry : snapshots.entrySet()) {
							BaseMappedRegistry registry = REGISTRIES.get(entry.getKey());
							registry.unfreeze(false);
							registry.clear(false);
							for (var id : entry.getValue().getIds().entrySet()) {
								ResourceKey key = new ResourceKey(id.getValue());
								if (!registry.containsKey(key)) {
									missing.add(key);
									continue;
								}
								registry.registerIdMapping(key, id.getKey());
							}
							registry.freeze();
						}
						return missing;
					}

					/** NeoForge's disconnect revert, to a snapshot freezeData takes, which the kernel's freeze never does. */
					public static void revertToFrozen() {
						throw new IllegalStateException("no frozen snapshot");
					}
				}
				""");
		STAND_INS.put(FABRIC, """
				package net.fabricmc.fabric.impl.client.registry.sync;

				import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
				import net.fabricmc.fabric.impl.registry.sync.RemappableRegistry;
				import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
				import net.minecraft.resources.Identifier;

				public class ClientRegistrySyncHandler {
					/** fabric-api's client sync: remap every synced registry to the server's ids. */
					public static void apply(RegistrySyncPayload payload) {
						for (var registry : payload.ids().entrySet()) {
							Object2IntLinkedOpenHashMap<Identifier> ids = new Object2IntLinkedOpenHashMap<>();
							ids.putAll(registry.getValue());
							((RemappableRegistry) payload.registries().get(registry.getKey())).remap(ids, RemappableRegistry.RemapMode.REMOTE);
						}
					}
				}
				""");
		STAND_INS.put("net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload", """
				package net.fabricmc.fabric.impl.registry.sync.packet;

				import java.util.Map;
				import net.minecraft.resources.Identifier;

				public record RegistrySyncPayload(Map<Identifier, Map<Identifier, Integer>> ids, Map<Identifier, Object> registries) {
				}
				""");
		STAND_INS.put("fixture.Items", """
				package fixture;

				import net.minecraft.resources.Identifier;
				import net.minecraft.resources.ResourceKey;
				import net.minecraftforge.registries.ForgeRegistry;
				import net.minecraftforge.registries.NamespacedWrapper;
				import net.neoforged.neoforge.registries.RegistryManager;

				/** A client's item registry, Forge-wrapped: three entries at local ids 0, 1, 2. */
				public class Items {
					public static final ForgeRegistry<String> FORGE = new ForgeRegistry<>();
					public static final NamespacedWrapper<String> ITEM = new NamespacedWrapper<>(FORGE, new ResourceKey(new Identifier("minecraft:item")));
					public static final String STONE = FORGE.register("minecraft:stone", "stone");
					public static final String DIRT = FORGE.register("minecraft:dirt", "dirt");
					public static final String GEAR = FORGE.register("forgemod:gear", "gear");

					static {
						RegistryManager.REGISTRIES.put(new Identifier("minecraft:item"), ITEM);
					}
				}
				""");
	}

	/** The boot-side hooks keep JVM-wide state; a capture one test leaves behind must not be another's revert. */
	@AfterEach void forgetTheCapturedIds() throws ReflectiveOperationException {
		for (String field : List.of("originals", "reverting")) {
			java.lang.reflect.Field state = KernelRegistryRevert.class.getDeclaredField(field);
			state.setAccessible(true);
			state.set(null, field.equals("reverting") ? Boolean.FALSE : null);
		}
	}

	/** The pending-tags class's own contents() exists only to make it compile; the merged one has none. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		String internal = PENDING.replace('.', '/');
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(classes.get(internal)).accept(node, 0);
		assertTrue(node.interfaces.contains("net/minecraft/core/Registry$PendingTags"), "the third anonymous class is the pending tags");
		assertTrue(node.methods.removeIf(m -> m.name.equals("contents")));
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		classes.put(internal, writer.toByteArray());
		return classes;
	}

	private static Map<String, byte[]> repaired(Map<String, byte[]> original) {
		return repaired(original, original::containsKey);
	}

	/** {@code gameHasClass} is what the injector is told the game loader has, as KernelBoot tells it from its loader. */
	private static Map<String, byte[]> repaired(Map<String, byte[]> original, java.util.function.Predicate<String> gameHasClass) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new RegistrySyncParityInjector(gameHasClass), target, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target + " is the merged shape");
			classes.put(internal, out);
		}
		return classes;
	}

	private static Object identifier(ClassLoader loader, String id) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.resources.Identifier"), id);
	}

	/** The item registry's ids as {@code [stone, dirt, gear]}, read through the wrapper. */
	private static List<Object> ids(ClassLoader loader) throws Throwable {
		Class<?> items = loader.loadClass("fixture.Items");
		Object wrapper = InjectorExecution.getStatic(items, "ITEM");
		List<Object> ids = new java.util.ArrayList<>();
		for (String item : List.of("STONE", "DIRT", "GEAR")) ids.add(InjectorExecution.invoke(wrapper, "getId", InjectorExecution.getStatic(items, item)));
		return ids;
	}

	/** NeoForge's sync, from a server whose ids are stone 2, dirt 0, gear 1. */
	private static Object neoForgeSync(ClassLoader loader) throws Throwable {
		Class<?> manager = loader.loadClass(MANAGER);
		Class<?> snapshot = loader.loadClass(MANAGER + "$RegistrySnapshot");
		Map<Integer, Object> server = Map.of(2, identifier(loader, "minecraft:stone"), 0, identifier(loader, "minecraft:dirt"),
				1, identifier(loader, "forgemod:gear"));
		Object snapshots = Map.of(identifier(loader, "minecraft:item"), InjectorExecution.construct(snapshot, server));
		return InjectorExecution.invokeStatic(manager, "applySnapshot", snapshots, false);
	}

	// The stub game has no BuiltInRegistries for fabric-api's unmap pass to walk.
	@ExpectCaught("fabric-api's unmap pass failed part-way")
	@Test void aClientTakesTheServersIdsForAWrappedRegistryAndGetsItsOwnBack(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		Map<String, byte[]> classes = repaired(original);
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		assertEquals(List.of(0, 1, 2), ids(loader));
		assertEquals(Set.of(), neoForgeSync(loader), "nothing missing");
		assertEquals(List.of(2, 0, 1), ids(loader), "the wrapped registry follows the server's ids, through MinecraftForge's injectSnapshot");
		InjectorExecution.invokeStatic(loader.loadClass(MANAGER), "revertToFrozen");
		assertEquals(List.of(0, 1, 2), ids(loader), "and disconnecting puts the client's own ids back");

		Object wrapper = InjectorExecution.getStatic(loader.loadClass("fixture.Items"), "ITEM");
		Object bindings = InjectorExecution.invokeStatic(loader.loadClass("com.google.common.collect.ImmutableMap"), "copyOf",
				Map.of(identifier(loader, "c:ores"), "stone"));
		Object pending = InjectorExecution.invoke(wrapper, "prepareTagReload", bindings);
		assertSame(bindings, loader.loadClass("net.neoforged.neoforge.registries.PendingTagsExtension").getMethod("contents").invoke(pending),
				"MinecraftForge's pending tags answer NeoForge's contents() with the tags they are about to bind");

		ClassLoader stock = InjectorExecution.load(original);
		assertEquals(List.of(0, 1, 2), ids(stock));
		NullPointerException dropped = assertThrows(NullPointerException.class, () -> neoForgeSync(stock),
				"premise: as merged, NeoForge's registerIdMapping reads the wrapper's empty vanilla fields");
		assertTrue(dropped.getMessage().contains("MappedRegistry$Holder.value()"), dropped.getMessage());
		Object stockPending = InjectorExecution.invoke(InjectorExecution.getStatic(stock.loadClass("fixture.Items"), "ITEM"), "prepareTagReload",
				InjectorExecution.invokeStatic(stock.loadClass("com.google.common.collect.ImmutableMap"), "copyOf", Map.of()));
		Throwable missing = assertThrows(java.lang.reflect.InvocationTargetException.class,
				() -> stock.loadClass("net.neoforged.neoforge.registries.PendingTagsExtension").getMethod("contents").invoke(stockPending)).getCause();
		assertInstanceOf(AbstractMethodError.class, missing, "premise: as merged, contents() is not implemented");
		for (String target : List.of(WRAPPER, PENDING)) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new RegistrySyncParityInjector(classes::containsKey), target, once, EnvType.CLIENT),
					target + " already has the contract and is left alone");
		}
	}

	// The stub game has no BuiltInRegistries for fabric-api's unmap pass to walk.
	@ExpectCaught("fabric-api's unmap pass failed part-way")
	@Test void fabricApisSyncReachesTheWrappedRegistryToo(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		ClassLoader loader = InjectorExecution.load(repaired(original));
		assertEquals(List.of(1, 2, 0), fabricSync(loader), "fabric-api's remap is staged and applied when its apply returns");
		InjectorExecution.invokeStatic(loader.loadClass(MANAGER), "revertToFrozen");
		assertEquals(List.of(0, 1, 2), ids(loader), "and disconnecting puts the client's own ids back");
		assertEquals(List.of(0, 1, 2), fabricSync(InjectorExecution.load(original)),
				"premise: as merged, fabric-api's remap writes the wrapper's unused vanilla fields and the ids never move");
	}

	/**
	 * A game without fabric-api's remap types gets a wrapper without fabric-api's {@code remap} (a public method naming an
	 * absent class breaks {@code getMethods()} and every {@code getMethod} that reaches the wrapper), and NeoForge's sync
	 * and the disconnect revert work exactly as with it.
	 */
	// The stub game has no BuiltInRegistries for fabric-api's unmap pass to walk.
	@ExpectCaught("fabric-api's unmap pass failed part-way")
	@Test void withoutFabricApiTheWrapperKeepsNeoForgesSyncAndHasNoRemap(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		Map<String, byte[]> classes = repaired(original, type -> !type.startsWith("net/fabricmc/") && original.containsKey(type));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(WRAPPER.replace('.', '/')), loader), WRAPPER);
		Class<?> wrapper = loader.loadClass(WRAPPER);
		assertThrows(NoSuchMethodException.class, () -> wrapper.getDeclaredMethod("remap",
				loader.loadClass("it.unimi.dsi.fastutil.objects.Object2IntMap"),
				loader.loadClass("net.fabricmc.fabric.impl.registry.sync.RemappableRegistry$RemapMode")));
		assertNotNull(wrapper.getDeclaredMethod("registerIdMapping", loader.loadClass("net.minecraft.resources.ResourceKey"), int.class));

		assertEquals(List.of(0, 1, 2), ids(loader));
		assertEquals(Set.of(), neoForgeSync(loader), "nothing missing");
		assertEquals(List.of(2, 0, 1), ids(loader), "NeoForge's sync moves the wrapped registry's ids without fabric-api's half");
		InjectorExecution.invokeStatic(loader.loadClass(MANAGER), "revertToFrozen");
		assertEquals(List.of(0, 1, 2), ids(loader), "and disconnecting puts the client's own ids back");
	}

	/**
	 * The injector KernelBoot registers, {@link RegistrySyncParityInjector#forGameLoader}, decides from the class files
	 * on the game's loader: one that has fabric-api's registry-sync types gets {@code remap}, one that does not gets no
	 * {@code remap}, and NeoForge's half either way. No stand-in predicate between the test and the decision; the games
	 * are directories of the stand-ins' class files, with and without {@code net/fabricmc/}.
	 */
	@Test void theInjectorKernelBootRegistersAsksTheGameLoaderForRemapsClassFiles(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work.resolve("compiled"));
		byte[] wrapper = original.get(WRAPPER.replace('.', '/'));
		for (boolean fabricApi : List.of(true, false)) {
			Path game = work.resolve(fabricApi ? "with-fabric-api" : "without-fabric-api");
			for (var entry : original.entrySet()) {
				if (!fabricApi && entry.getKey().startsWith("net/fabricmc/")) continue;
				Path file = game.resolve(entry.getKey() + ".class");
				Files.createDirectories(file.getParent());
				Files.write(file, entry.getValue());
			}
			try (URLClassLoader loader = new URLClassLoader(new URL[] {game.toUri().toURL()}, null)) {
				assertEquals(fabricApi, loader.getResource("net/fabricmc/fabric/impl/registry/sync/RemappableRegistry$RemapMode.class") != null,
						"premise: the game " + (fabricApi ? "has" : "lacks") + " fabric-api's RemapMode");
				org.objectweb.asm.tree.ClassNode out = new org.objectweb.asm.tree.ClassNode();
				new org.objectweb.asm.ClassReader(InjectorExecution.transform(RegistrySyncParityInjector.forGameLoader(loader), WRAPPER,
						wrapper, EnvType.CLIENT)).accept(out, 0);
				Set<String> declared = new java.util.TreeSet<>();
				for (org.objectweb.asm.tree.MethodNode m : out.methods) declared.add(m.name);
				assertTrue(declared.containsAll(Set.of("clear", "registerIdMapping")), "NeoForge's sync contract either way: " + declared);
				assertEquals(fabricApi, declared.contains("remap"), (fabricApi ? "with" : "without")
						+ " fabric-api's types on the game loader, fabric-api's remap is " + (fabricApi ? "added" : "left out") + ": " + declared);
			}
		}
	}

	/** fabric-api's sync, from a server whose ids are stone 1, dirt 2, gear 0: the item ids after it. */
	private static List<Object> fabricSync(ClassLoader loader) throws Throwable {
		Object item = identifier(loader, "minecraft:item");
		Map<Object, Integer> server = Map.of(identifier(loader, "minecraft:stone"), 1, identifier(loader, "minecraft:dirt"), 2,
				identifier(loader, "forgemod:gear"), 0);
		Object payload = InjectorExecution.construct(loader.loadClass("net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload"),
				Map.of(item, server), Map.of(item, InjectorExecution.getStatic(loader.loadClass("fixture.Items"), "ITEM")));
		InjectorExecution.invokeStatic(loader.loadClass(FABRIC), "apply", payload);
		return ids(loader);
	}
}
