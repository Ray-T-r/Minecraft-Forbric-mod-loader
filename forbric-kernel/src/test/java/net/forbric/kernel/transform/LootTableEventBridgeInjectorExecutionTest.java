/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.LootTableEventDispatch;

/**
 * {@link LootTableEventBridgeInjector}'s output, run: a Fabric mod's {@code LootTableEvents.REPLACE}, {@code MODIFY} and
 * {@code ALL_LOADED} listeners are called during a loot table reload, from NeoForge's own load seam and after the tags
 * load — where as merged they were registered and never called. A table NeoForge's hook cancels never reaches them.
 *
 * <p>Both hooks are real: the game-side {@code KernelLootBridge}, compiled from {@code src/runtime/java}, and the
 * boot-side {@code LootTableEventDispatch}, bound to the test's loader as KernelBoot binds it to the game's. Everything
 * they reach is a stand-in: fabric-loot-api-v3's events, sources and builder, a loot table, NeoForge's
 * {@code EventHooks.loadLootTable}, {@code TagLoader}, and a {@code ReloadableServerRegistries} that makes the two calls
 * the bridge keys on.
 */
@ExecutesInjector(LootTableEventBridgeInjector.class)
@ResourceLock("system-properties")
@ResourceLock("LootTableEventDispatch")
class LootTableEventBridgeInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelLootBridge.java");
	private static final String TARGET = LootTableEventBridgeInjector.TARGET;

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"net.minecraft.resources.Identifier", "package net.minecraft.resources; public record Identifier(String id) { }",
			"net.minecraft.resources.ResourceKey", """
					package net.minecraft.resources;

					import net.minecraft.core.Registry;

					public record ResourceKey<T>(Identifier registry, Identifier location) {
						public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier location) {
							return new ResourceKey<>(registry.location(), location);
						}
					}
					""",
			"net.minecraft.core.Registry", """
					package net.minecraft.core;

					import net.minecraft.resources.ResourceKey;

					public interface Registry<T> {
						ResourceKey<? extends Registry<T>> key();
						java.util.stream.Stream<Holder.Reference<T>> listElements();
					}
					""",
			"net.minecraft.core.WritableRegistry", """
					package net.minecraft.core;

					public interface WritableRegistry<T> extends Registry<T> {
						int size();
					}
					""",
			"net.minecraft.core.HolderLookup", "package net.minecraft.core; public interface HolderLookup { interface Provider { } }",
			"net.minecraft.core.registries.Registries", """
					package net.minecraft.core.registries;

					import net.minecraft.core.Registry;
					import net.minecraft.resources.Identifier;
					import net.minecraft.resources.ResourceKey;
					import net.minecraft.world.level.storage.loot.LootTable;

					public class Registries {
						public static final ResourceKey<Registry<LootTable>> LOOT_TABLE =
								new ResourceKey<>(new Identifier("minecraft:root"), new Identifier("minecraft:loot_table"));
					}
					""",
			"net.minecraft.server.packs.resources.ResourceManager", "package net.minecraft.server.packs.resources; public interface ResourceManager { }",
			"net.minecraft.world.level.storage.loot.LootTable", """
					package net.minecraft.world.level.storage.loot;

					import java.util.ArrayList;
					import java.util.List;

					public class LootTable implements net.fabricmc.fabric.impl.loot.FabricLootTable {
						public net.minecraft.core.Holder<?> holder;
						public void fabric$setHolder(net.minecraft.core.Holder<?> holder) { this.holder = holder; }
						public final List<String> pools;

						public LootTable(List<String> pools) {
							this.pools = List.copyOf(pools);
						}

						public static class Builder {
							private final List<String> pools;

							public Builder(List<String> pools) {
								this.pools = new ArrayList<>(pools);
							}

							public Builder pool(String pool) {
								pools.add(pool);
								return this;
							}

							public LootTable build() {
								return new LootTable(pools);
							}
						}
					}
					""",
			"net.minecraft.tags.TagLoader", """
					package net.minecraft.tags;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.core.WritableRegistry;
					import net.minecraft.server.packs.resources.ResourceManager;

					public class TagLoader {
						public static final List<String> log = new ArrayList<>();

						public static void loadTagsForRegistry(ResourceManager resources, WritableRegistry<?> registry) {
							log.add("tags");
						}
					}
					"""));

	static {
		STAND_INS.put("net.minecraft.core.Holder", "package net.minecraft.core; public interface Holder<T> { public static class Reference<T> implements Holder<T> { private final T value; public Reference(T value) { this.value=value; } public T value() { return value; } } }");
		STAND_INS.put("net.fabricmc.fabric.impl.loot.FabricLootTable", "package net.fabricmc.fabric.impl.loot; public interface FabricLootTable { void fabric$setHolder(net.minecraft.core.Holder<?> holder); }");
		STAND_INS.put("net.neoforged.neoforge.event.EventHooks", """
				package net.neoforged.neoforge.event;

				import net.minecraft.core.HolderLookup;
				import net.minecraft.resources.Identifier;
				import net.minecraft.world.level.storage.loot.LootTable;

				public class EventHooks {
					/** NeoForge's LootTableLoadEvent: a NeoForge listener cancels one table. */
					public static LootTable loadLootTable(HolderLookup.Provider provider, Identifier id, LootTable table) {
						return id.id().equals("minecraft:cancelled") ? null : table;
					}
				}
				""");
		STAND_INS.put("net.minecraftforge.event.ForgeEventFactory", """
				package net.minecraftforge.event;

				import net.minecraft.resources.Identifier;
				import net.minecraft.world.level.storage.loot.LootTable;

				public class ForgeEventFactory {
					public static LootTable onLoadLootTable(Identifier id, LootTable table) {
						return table;
					}
				}
				""");
		STAND_INS.put("net.fabricmc.fabric.api.event.Event", """
				package net.fabricmc.fabric.api.event;

				import java.util.ArrayList;
				import java.util.List;
				import java.util.function.Function;

				public class Event<T> {
					private final List<T> listeners = new ArrayList<>();
					private final Function<List<T>, T> factory;

					public Event(Function<List<T>, T> factory) {
						this.factory = factory;
					}

					public void register(T listener) {
						listeners.add(listener);
					}

					public T invoker() {
						return factory.apply(List.copyOf(listeners));
					}
				}
				""");
		STAND_INS.put("net.fabricmc.fabric.api.loot.v3.LootTableSource",
				"package net.fabricmc.fabric.api.loot.v3; public enum LootTableSource { VANILLA, DATA_PACK, REPLACED }");
		STAND_INS.put("net.fabricmc.fabric.api.loot.v3.FabricLootTableBuilder", """
				package net.fabricmc.fabric.api.loot.v3;

				import net.minecraft.world.level.storage.loot.LootTable;

				public interface FabricLootTableBuilder {
					static LootTable.Builder copyOf(LootTable table) {
						return new LootTable.Builder(table.pools);
					}
				}
				""");
		STAND_INS.put("net.fabricmc.fabric.impl.loot.LootUtil", """
				package net.fabricmc.fabric.impl.loot;

				import java.util.HashMap;
				import java.util.Map;
				import net.fabricmc.fabric.api.loot.v3.LootTableSource;
				import net.minecraft.resources.Identifier;

				public final class LootUtil {
					public static final ThreadLocal<Map<Identifier, LootTableSource>> SOURCES = ThreadLocal.withInitial(HashMap::new);
				}
				""");
		STAND_INS.put("net.fabricmc.fabric.api.loot.v3.LootTableEvents", """
				package net.fabricmc.fabric.api.loot.v3;

				import net.fabricmc.fabric.api.event.Event;
				import net.minecraft.core.HolderLookup;
				import net.minecraft.core.Registry;
				import net.minecraft.resources.ResourceKey;
				import net.minecraft.server.packs.resources.ResourceManager;
				import net.minecraft.world.level.storage.loot.LootTable;

				public final class LootTableEvents {
					public interface Replace {
						LootTable replaceLootTable(ResourceKey<LootTable> key, LootTable original, LootTableSource source, HolderLookup.Provider registries);
					}

					public interface Modify {
						void modifyLootTable(ResourceKey<LootTable> key, LootTable.Builder builder, LootTableSource source, HolderLookup.Provider registries);
					}

					public interface Loaded {
						void onLootTablesLoaded(ResourceManager resourceManager, Registry<LootTable> registry);
					}

					public static final Event<Replace> REPLACE = new Event<>(listeners -> (key, original, source, registries) -> {
						for (Replace listener : listeners) {
							LootTable replaced = listener.replaceLootTable(key, original, source, registries);
							if (replaced != null) return replaced;
						}
						return null;
					});
					public static final Event<Modify> MODIFY = new Event<>(listeners -> (key, builder, source, registries) -> {
						for (Modify listener : listeners) listener.modifyLootTable(key, builder, source, registries);
					});
					public static final Event<Loaded> ALL_LOADED = new Event<>(listeners -> (resourceManager, registry) -> {
						for (Loaded listener : listeners) listener.onLootTablesLoaded(resourceManager, registry);
					});
				}
				""");
		STAND_INS.put(TARGET, """
				package net.minecraft.server;

				import java.util.LinkedHashMap;
				import java.util.Map;
				import net.minecraft.core.HolderLookup;
				import net.minecraft.core.WritableRegistry;
				import net.minecraft.resources.Identifier;
				import net.minecraft.server.packs.resources.ResourceManager;
				import net.minecraft.tags.TagLoader;
				import net.minecraft.world.level.storage.loot.LootTable;
				import net.neoforged.neoforge.event.EventHooks;

				public class ReloadableServerRegistries {
					/** The two calls that bracket loot table loading, as NeoForge's merged body makes them. */
					public static Map<Identifier, LootTable> scheduleRegistryLoad(HolderLookup.Provider provider, ResourceManager resources,
							Map<Identifier, LootTable> parsed, WritableRegistry<LootTable> registry) {
						Map<Identifier, LootTable> loaded = new LinkedHashMap<>();
						parsed.forEach((id, table) -> {
							LootTable kept = EventHooks.loadLootTable(provider, id, table);
							if (kept != null) loaded.put(id, kept);
						});
						TagLoader.loadTagsForRegistry(resources, registry);
						return loaded;
					}
				}
				""");
		STAND_INS.put("fixture.FabricLootMod", """
				package fixture;

				import java.util.ArrayList;
				import java.util.List;
				import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
				import net.minecraft.world.level.storage.loot.LootTable;
				import net.minecraft.tags.TagLoader;

				/** A Fabric mod: replaces the village chest, adds a pool to every table, counts the reload. */
				public class FabricLootMod {
					public static final List<String> seen = new ArrayList<>();

					public static void register() {
						LootTableEvents.REPLACE.register((key, original, source, registries) -> {
							seen.add("replace " + key.location().id() + " from " + source);
							return key.location().id().equals("minecraft:chests/village") ? new LootTable(List.of("fabric village loot")) : null;
						});
						LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
							builder.pool("fabric pool");
							seen.add("modify " + key.location().id() + " from " + source);
						});
						LootTableEvents.ALL_LOADED.register((resources, registry) -> seen.add("all loaded after " + TagLoader.log));
					}
				}
				""");
	}

	@AfterEach void reset() {
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
		// JVM-wide, like the game's: leave nothing bound to this test's loader.
		LootTableEventDispatch.bind(null);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelLootBridge.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	/** An instance of {@code type} answering {@code answers} by method name, and identity for Object's methods. */
	private static Object stub(Class<?> type, Map<String, Object> answers) {
		return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> type.getSimpleName();
			default -> answers.get(method.getName());
		});
	}

	/** One reload of three tables, with the Fabric mod listening: the tables' pools, then what the mod saw. */
	private static List<Object> reload(ClassLoader loader) throws Throwable {
		LootTableEventDispatch.bind(loader);
		InjectorExecution.invokeStatic(loader.loadClass("fixture.FabricLootMod"), "register");
		Class<?> table = loader.loadClass("net.minecraft.world.level.storage.loot.LootTable");
		Class<?> id = loader.loadClass("net.minecraft.resources.Identifier");
		Map<Object, Object> parsed = new LinkedHashMap<>();
		for (String name : List.of("minecraft:chests/village", "minecraft:blocks/stone", "minecraft:cancelled")) {
			parsed.put(InjectorExecution.construct(id, name), InjectorExecution.construct(table, List.of("vanilla pool")));
		}
		Class<?> registryType = loader.loadClass("net.minecraft.core.WritableRegistry");
		Object registries = InjectorExecution.getStatic(loader.loadClass("net.minecraft.core.registries.Registries"), "LOOT_TABLE");
		Object registry = stub(registryType, Map.of("key", registries, "size", 2, "listElements", java.util.stream.Stream.empty()));
		Object provider = stub(loader.loadClass("net.minecraft.core.HolderLookup$Provider"), Map.of());
		Object resources = stub(loader.loadClass("net.minecraft.server.packs.resources.ResourceManager"), Map.of());
		Map<?, ?> loaded = (Map<?, ?>) InjectorExecution.invokeStatic(loader.loadClass(TARGET), "scheduleRegistryLoad", provider, resources, parsed,
				registry);
		Map<String, Object> pools = new LinkedHashMap<>();
		for (var entry : loaded.entrySet()) pools.put(entry.getKey().toString(), table.getField("pools").get(entry.getValue()));
		return List.of(pools, List.copyOf((List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.FabricLootMod"), "seen")));
	}

	@Test void aFabricModsLootListenersRunOnReload(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = TARGET.replace('.', '/');
		byte[] routed = InjectorExecution.transform(new LootTableEventBridgeInjector(), TARGET, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), routed, "both seams were found");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, routed);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(routed, loader));

		List<Object> reloaded = reload(loader);
		assertEquals(Map.of("Identifier[id=minecraft:chests/village]", List.of("fabric village loot", "fabric pool"),
				"Identifier[id=minecraft:blocks/stone]", List.of("vanilla pool", "fabric pool")), reloaded.get(0),
				"REPLACE took the village chest, MODIFY reached every table NeoForge kept, the cancelled one stayed out");
		assertEquals(List.of("replace minecraft:chests/village from DATA_PACK", "modify minecraft:chests/village from REPLACED",
				"replace minecraft:blocks/stone from DATA_PACK", "modify minecraft:blocks/stone from DATA_PACK",
				"all loaded after [tags]"), reloaded.get(1), "in fabric-api's order, ALL_LOADED once the tags are in");

		List<Object> merged = reload(InjectorExecution.load(original));
		assertEquals(Map.of("Identifier[id=minecraft:chests/village]", List.of("vanilla pool"), "Identifier[id=minecraft:blocks/stone]",
				List.of("vanilla pool")), merged.get(0), "premise: as merged, the tables load as NeoForge returns them");
		assertEquals(List.of(), merged.get(1), "premise: and no Fabric listener is ever called");
		assertSame(routed, InjectorExecution.transform(new LootTableEventBridgeInjector(), TARGET, routed, EnvType.SERVER),
				"routed sites are not routed again");
	}

	@Test void switchedOffTheClassIsLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(TARGET.replace('.', '/'));
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new LootTableEventBridgeInjector(), TARGET, bytes, EnvType.SERVER));
	}
}
