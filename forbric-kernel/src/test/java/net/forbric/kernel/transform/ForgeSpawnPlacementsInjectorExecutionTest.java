/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeSpawnPlacementsInjector}'s output, run: NeoForge's {@code SpawnPlacements.fireSpawnPlacementEvent} serves
 * MinecraftForge's spawn placement registration before NeoForge's, so a MinecraftForge mod's mob gets its spawn rule in
 * the game's table — where as merged it never reached the table. NeoForge's own registrations, and NeoForge's table
 * construction and write-back, are as before.
 *
 * <p>The stand-in {@code SpawnPlacements} keeps the shape the edit keys on: one event built from the table and posted
 * straight to {@code ModLoader.postEvent}, then written back. The kernel's {@code KernelForgeSpawnPlacements} bridges
 * MinecraftForge's own {@code SpawnPlacementRegisterEvent} by reflection over both carriers' builders; a stand-in under
 * its name posts a MinecraftForge event into NeoForge's map and then NeoForge's, which is what its
 * {@code postBothFamilies} promises.
 */
@ExecutesInjector(ForgeSpawnPlacementsInjector.class)
@ResourceLock("system-properties")
class ForgeSpawnPlacementsInjectorExecutionTest {
	private static final String TARGET = "net.minecraft.world.entity.SpawnPlacements";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event { }",
			"net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent", """
					package net.neoforged.neoforge.event.entity;

					import java.util.Map;
					import net.neoforged.bus.api.Event;

					public class RegisterSpawnPlacementsEvent extends Event {
						private final Map<String, String> map;

						public RegisterSpawnPlacementsEvent(Map<String, String> map) {
							this.map = map;
						}

						public void register(String type, String rule) {
							map.put(type, rule);
						}
					}
					""",
			"net.neoforged.fml.ModLoader", """
					package net.neoforged.fml;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Consumer;
					import net.neoforged.bus.api.Event;

					public class ModLoader {
						public static final List<Consumer<Event>> modBus = new ArrayList<>();

						public static void postEvent(Event event) {
							for (Consumer<Event> listener : modBus) listener.accept(event);
						}
					}
					""",
			"net.minecraftforge.event.entity.SpawnPlacementRegisterEvent", """
					package net.minecraftforge.event.entity;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.Map;
					import java.util.function.Consumer;

					public class SpawnPlacementRegisterEvent {
						public static final List<Consumer<SpawnPlacementRegisterEvent>> BUS = new ArrayList<>();
						private final Map<String, String> map;

						public SpawnPlacementRegisterEvent(Map<String, String> map) {
							this.map = map;
						}

						public void register(String type, String rule) {
							map.put(type, rule);
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelForgeSpawnPlacements", """
					package net.forbric.kernel.runtime;

					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
					import net.neoforged.bus.api.Event;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

					/** MinecraftForge's registration into NeoForge's event, then NeoForge's own post. */
					public final class KernelForgeSpawnPlacements {
						public static void postBothFamilies(Event event) {
							if (event instanceof RegisterSpawnPlacementsEvent registration) {
								Map<String, String> forge = new LinkedHashMap<>();
								SpawnPlacementRegisterEvent forgeEvent = new SpawnPlacementRegisterEvent(forge);
								SpawnPlacementRegisterEvent.BUS.forEach(listener -> listener.accept(forgeEvent));
								forge.forEach(registration::register);
							}
							ModLoader.postEvent(event);
						}
					}
					""",
			TARGET, """
					package net.minecraft.world.entity;

					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

					public class SpawnPlacements {
						public static final Map<String, String> DATA_BY_TYPE = new LinkedHashMap<>(Map.of("minecraft:zombie", "on ground, dark"));

						/** NeoForge's: the table into the event, the event to the mod bus, the result back into the table. */
						public static void fireSpawnPlacementEvent() {
							Map<String, String> map = new LinkedHashMap<>(DATA_BY_TYPE);
							ModLoader.postEvent(new RegisterSpawnPlacementsEvent(map));
							DATA_BY_TYPE.putAll(map);
						}
					}
					""",
			"fixture.Mods", """
					package fixture;

					import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

					/** One mod of each Forge family registering its mob's spawn rule. */
					public class Mods {
						public static void register() {
							SpawnPlacementRegisterEvent.BUS.add(event -> event.register("forgemod:yeti", "on snow"));
							ModLoader.modBus.add(event -> {
								if (event instanceof RegisterSpawnPlacementsEvent neo) neo.register("neomod:crab", "on sand");
							});
						}
					}
					""");

	@AfterEach void reset() throws Throwable {
		System.clearProperty(ForgeSpawnPlacementsInjector.PROPERTY);
	}

	private static Object spawnTable(ClassLoader loader) throws Throwable {
		InjectorExecution.invokeStatic(loader.loadClass("fixture.Mods"), "register");
		Class<?> placements = loader.loadClass(TARGET);
		InjectorExecution.invokeStatic(placements, "fireSpawnPlacementEvent");
		return new java.util.TreeMap<>((Map<?, ?>) InjectorExecution.getStatic(placements, "DATA_BY_TYPE"));
	}

	@Test void aMinecraftForgeModsMobGetsItsSpawnRule(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = TARGET.replace('.', '/');
		byte[] routed = InjectorExecution.transform(new ForgeSpawnPlacementsInjector(), TARGET, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), routed, "the post was routed");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, routed);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(routed, loader));

		assertEquals(Map.of("forgemod:yeti", "on snow", "minecraft:zombie", "on ground, dark", "neomod:crab", "on sand"), spawnTable(loader),
				"both families' rules are in the game's spawn table");
		assertEquals(Map.of("minecraft:zombie", "on ground, dark", "neomod:crab", "on sand"), spawnTable(InjectorExecution.load(original)),
				"premise: as merged, the MinecraftForge mod's rule never reaches the table");
		assertSame(routed, InjectorExecution.transform(new ForgeSpawnPlacementsInjector(), TARGET, routed, EnvType.SERVER),
				"a routed post is left alone");
	}

	@Test void switchedOffTheTableIsLeftToNeoForge(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, STAND_INS).get(TARGET.replace('.', '/'));
		System.setProperty(ForgeSpawnPlacementsInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new ForgeSpawnPlacementsInjector(), TARGET, bytes, EnvType.SERVER));
	}
}
