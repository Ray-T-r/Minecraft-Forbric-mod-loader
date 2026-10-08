/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import net.fabricmc.api.EnvType;

/**
 * {@link ClientPartTrackingInjector}'s output, run: the client starts tracking a NeoForge mod's multipart entity and its
 * parts go into {@code dragonParts}, where as merged MinecraftForge's loop met the null its {@code getParts()} answers
 * and the client disconnected. A MinecraftForge mod's entity keeps its tracking in {@code partEntities}, and the Ender
 * Dragon's parts are added once, by its own case.
 *
 * <p>The stand-in callback is MinecraftForge's body: the dragon's case, then
 * {@code if (entity.isMultipartEntity()) for (part : entity.getParts())}. The merged {@code Entity} has one
 * {@code getParts()} per family, differing only in return type, so NeoForge's is written as {@code getNeoParts} and
 * renamed in the class files, overrides included.
 */
@ExecutesInjector(ClientPartTrackingInjector.class)
@ResourceLock("system-properties")
class ClientPartTrackingInjectorExecutionTest {
	private static final String CALLBACKS = ClientPartTrackingInjector.CALLBACKS;
	private static final String CALLBACKS_INTERNAL = ClientPartTrackingInjector.CALLBACKS_INTERNAL;

	private static String partEntity(String pkg) {
		return """
				package %s;

				import net.minecraft.world.entity.Entity;

				public class PartEntity<T extends Entity> extends Entity {
					public PartEntity(int id) {
						super(id);
					}
				}
				""".formatted(pkg);
	}

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.entity.Entity", """
					package net.minecraft.world.entity;

					public class Entity {
						private final int id;

						public Entity(int id) {
							this.id = id;
						}

						public int getId() {
							return id;
						}

						public boolean isMultipartEntity() {
							return false;
						}

						/** MinecraftForge's interface default. */
						public net.minecraftforge.entity.PartEntity<?>[] getParts() {
							return null;
						}

						/** NeoForge's interface default, renamed getParts in the class file. */
						public net.neoforged.neoforge.entity.PartEntity<?>[] getNeoParts() {
							return null;
						}
					}
					""",
			"net.minecraftforge.entity.PartEntity", partEntity("net.minecraftforge.entity"),
			"net.neoforged.neoforge.entity.PartEntity", partEntity("net.neoforged.neoforge.entity"),
			"net.minecraft.world.entity.boss.enderdragon.EnderDragon", """
					package net.minecraft.world.entity.boss.enderdragon;

					import net.minecraft.world.entity.Entity;
					import net.neoforged.neoforge.entity.PartEntity;

					/** As DragonPartsInjector leaves it: NeoForge's getParts() has the parts, MinecraftForge's is empty. */
					public class EnderDragon extends Entity {
						public final PartEntity<?>[] subEntities = {new PartEntity<>(11), new PartEntity<>(12)};

						public EnderDragon() {
							super(10);
						}

						public PartEntity<?>[] getSubEntities() {
							return subEntities;
						}

						@Override
						public boolean isMultipartEntity() {
							return true;
						}

						@Override
						public PartEntity<?>[] getNeoParts() {
							return subEntities;
						}

						@Override
						public net.minecraftforge.entity.PartEntity<?>[] getParts() {
							return new net.minecraftforge.entity.PartEntity<?>[0];
						}
					}
					""",
			"net.minecraft.client.multiplayer.ClientLevel", """
					package net.minecraft.client.multiplayer;

					import java.util.ArrayList;
					import java.util.Arrays;
					import java.util.HashMap;
					import java.util.List;
					import java.util.Map;
					import net.minecraft.world.entity.Entity;
					import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
					import net.minecraftforge.entity.PartEntity;

					public class ClientLevel {
						public final List<Object> dragonParts = new ArrayList<>();
						public final Map<Integer, PartEntity<?>> partEntities = new HashMap<>();
						public final EntityCallbacks callbacks = new EntityCallbacks();

						public final class EntityCallbacks {
							/** MinecraftForge's body. */
							public void onTrackingStart(Entity entity) {
								if (entity instanceof EnderDragon dragon) {
									ClientLevel.this.dragonParts.addAll(Arrays.asList(dragon.getSubEntities()));
								}
								if (entity.isMultipartEntity()) {
									for (PartEntity<?> part : entity.getParts()) {
										ClientLevel.this.partEntities.put(part.getId(), part);
									}
								}
							}
						}
					}
					""",
			"fixture.NeoSerpent", """
					package fixture;

					import net.minecraft.world.entity.Entity;
					import net.neoforged.neoforge.entity.PartEntity;

					/** A NeoForge mod's multipart entity: it overrides only NeoForge's getParts(). */
					public class NeoSerpent extends Entity {
						public final PartEntity<?>[] parts = {new PartEntity<>(21), new PartEntity<>(22)};

						public NeoSerpent() {
							super(20);
						}

						@Override
						public boolean isMultipartEntity() {
							return true;
						}

						@Override
						public PartEntity<?>[] getNeoParts() {
							return parts;
						}
					}
					""",
			"fixture.ForgeSerpent", """
					package fixture;

					import net.minecraft.world.entity.Entity;
					import net.minecraftforge.entity.PartEntity;

					/** A MinecraftForge mod's multipart entity: it overrides only MinecraftForge's getParts(). */
					public class ForgeSerpent extends Entity {
						public final PartEntity<?>[] parts = {new PartEntity<>(31), new PartEntity<>(32)};

						public ForgeSerpent() {
							super(30);
						}

						@Override
						public boolean isMultipartEntity() {
							return true;
						}

						@Override
						public PartEntity<?>[] getParts() {
							return parts;
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(ClientPartTrackingInjector.PROPERTY);
	}

	/** The merged shape: NeoForge's getter, and every override of it, under the name it shares with MinecraftForge's. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String,String> source=new HashMap<>(STAND_INS);
        source.put("net.minecraftforge.entity.PartEntity",partEntity("net.minecraftforge.entity").replace("extends Entity {","extends net.neoforged.neoforge.entity.PartEntity<T> {"));
        source.put("net.minecraft.world.entity.boss.enderdragon.EnderDragon",source.get("net.minecraft.world.entity.boss.enderdragon.EnderDragon")
            .replace("/** As DragonPartsInjector leaves it: NeoForge's getParts() has the parts, MinecraftForge's is empty. */","/** Both native descriptors keep the actual part array. */")
            .replace("public final PartEntity<?>[] subEntities = {new PartEntity<>(11), new PartEntity<>(12)};","public final net.minecraftforge.entity.PartEntity<?>[] subEntities = {new net.minecraftforge.entity.PartEntity<>(11), new net.minecraftforge.entity.PartEntity<>(12)};")
            .replace("return new net.minecraftforge.entity.PartEntity<?>[0];","return subEntities;"));
        source.put("net.forbric.kernel.runtime.KernelMultipartViews",java.nio.file.Files.readString(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelMultipartViews.java")));
        source.put("net.forbric.api.VirtualGetters",java.nio.file.Files.readString(Path.of("src/main/java/net/forbric/api/VirtualGetters.java")));
        Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, source));
		String entity = ClientPartTrackingInjector.ENTITY;
		SimpleRemapper rename = new SimpleRemapper(Map.of(entity + ".getNeoParts" + ClientPartTrackingInjector.NEO_GET_PARTS, "getParts")) {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return super.mapMethodName(entity, name, descriptor);
			}
		};
		for (String name : List.copyOf(classes.keySet())) {
			ClassWriter writer = new ClassWriter(0);
			new ClassReader(classes.get(name)).accept(new ClassRemapper(writer, rename), 0);
			classes.put(name, writer.toByteArray());
		}
		return classes;
	}

	/** A client level tracking {@code type}: what went into dragonParts, then the ids in partEntities. */
	private static List<Object> track(ClassLoader loader, String type) throws Throwable {
		Class<?> levelType = loader.loadClass("net.minecraft.client.multiplayer.ClientLevel");
		Object level = InjectorExecution.construct(levelType);
		Object entity = InjectorExecution.construct(loader.loadClass(type));
		InjectorExecution.invoke(levelType.getField("callbacks").get(level), "onTrackingStart", entity);
		List<Object> dragonParts = List.copyOf((List<?>) levelType.getField("dragonParts").get(level));
		List<Object> forgeIds = List.copyOf(((Map<?, ?>) levelType.getField("partEntities").get(level)).keySet().stream().sorted().toList());
		return List.of(ids(dragonParts), forgeIds);
	}

	private static List<Object> ids(List<Object> entities) throws Throwable {
		List<Object> ids = new java.util.ArrayList<>();
		for (Object entity : entities) ids.add(InjectorExecution.invoke(entity, "getId"));
		return ids;
	}

	@Test void aNeoForgeModsMultipartEntityIsTrackedInDragonParts(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		byte[] repaired = InjectorExecution.transform(new ClientPartTrackingInjector(), CALLBACKS, original.get(CALLBACKS_INTERNAL),
				EnvType.CLIENT);
		assertNotSame(original.get(CALLBACKS_INTERNAL), repaired, "the reviewed shape was edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(CALLBACKS_INTERNAL, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		assertEquals(List.of(List.of(21, 22), List.of()), track(loader, "fixture.NeoSerpent"),
				"a NeoForge mod's parts go where NeoForge's onTrackingStart puts them");
		assertEquals(List.of(List.of(), List.of(31, 32)), track(loader, "fixture.ForgeSerpent"),
				"a MinecraftForge mod's parts keep MinecraftForge's tracking");
		assertEquals(List.of(List.of(11, 12), List.of(11,12)), track(loader, "net.minecraft.world.entity.boss.enderdragon.EnderDragon"),
				"the dragon's parts are added once, by its own case");

		ClassLoader stock = InjectorExecution.load(original);
		assertThrows(NullPointerException.class, () -> track(stock, "fixture.NeoSerpent"),
				"premise: as merged, a NeoForge mod's multipart entity coming into view throws");
		assertEquals(List.of(List.of(), List.of(31, 32)), track(stock, "fixture.ForgeSerpent"));
		assertSame(repaired, InjectorExecution.transform(new ClientPartTrackingInjector(), CALLBACKS, repaired, EnvType.CLIENT),
				"a callback that reads NeoForge's parts is left alone");
	}

	@Test void switchedOffTheCallbackIsLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = merged(work).get(CALLBACKS_INTERNAL);
		System.setProperty(ClientPartTrackingInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new ClientPartTrackingInjector(), CALLBACKS, bytes, EnvType.CLIENT));
	}
}
