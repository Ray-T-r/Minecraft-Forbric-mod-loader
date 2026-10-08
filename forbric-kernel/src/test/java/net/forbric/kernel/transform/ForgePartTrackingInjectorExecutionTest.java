/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
 * {@link ForgePartTrackingInjector}'s output, run: a MinecraftForge mod's multipart entity is added to and removed from a
 * server world, stops being tracked by a client, is drawn by F3+B and has its parts found by {@code getEntities} — where
 * as merged the first three threw a NullPointerException on NeoForge's null {@code getParts()} and the parts were never
 * found. A NeoForge mod's multipart entity keeps NeoForge's tracking.
 *
 * <p>Stand-ins for the four classes in the shapes the edits key on: NeoForge's server callbacks and client
 * {@code onTrackingEnd}, MinecraftForge's client {@code onTrackingStart} (which already fills its {@code partEntities}),
 * the hitbox loop as {@link DragonPartsInjector} leaves it, and NeoForge's {@code getEntities}. The merged {@code Entity}
 * has one {@code getParts()} per family, differing only in return type, so NeoForge's is written as {@code getNeoParts}
 * and renamed in every class file; fastutil's {@code Int2ObjectMap} is a stand-in too.
 */
@ExecutesInjector(ForgePartTrackingInjector.class)
@ResourceLock("system-properties")
class ForgePartTrackingInjectorExecutionTest {
	private static final String SERVER = ForgePartTrackingInjector.SERVER_CALLBACKS;
	private static final String CLIENT = ForgePartTrackingInjector.CLIENT_CALLBACKS;
	private static final String HITBOXES = ForgePartTrackingInjector.HITBOXES;
	private static final String LEVEL = ForgePartTrackingInjector.LEVEL;
	private static final List<String> TARGETS = List.of(SERVER, CLIENT, HITBOXES, LEVEL);

	private static String partEntity(String pkg) {
		return """
				package %s;

				import net.minecraft.world.entity.Entity;
				import net.minecraft.world.phys.AABB;

				public class PartEntity<T extends Entity> extends Entity {
					private final T parent;

					public PartEntity(T parent, int id, AABB box) {
						super(id, box);
						this.parent = parent;
					}

					public T getParent() {
						return parent;
					}
				}
				""".formatted(pkg);
	}

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"it.unimi.dsi.fastutil.ints.Int2ObjectMap", """
					package it.unimi.dsi.fastutil.ints;

					public interface Int2ObjectMap<V> {
						V put(int key, V value);

						V remove(int key);

						java.util.List<Integer> keys();
					}
					""",
			"it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap", """
					package it.unimi.dsi.fastutil.ints;

					import java.util.List;
					import java.util.TreeMap;

					public class Int2ObjectOpenHashMap<V> implements Int2ObjectMap<V> {
						private final TreeMap<Integer, V> map = new TreeMap<>();

						public V put(int key, V value) {
							return map.put(key, value);
						}

						public V remove(int key) {
							return map.remove(key);
						}

						public List<Integer> keys() {
							return List.copyOf(map.keySet());
						}
					}
					""",
			"net.minecraft.world.phys.AABB", """
					package net.minecraft.world.phys;

					public record AABB(double min, double max) {
						public boolean intersects(AABB other) {
							return min < other.max && other.min < max;
						}
					}
					""",
			"net.minecraft.world.entity.Entity", """
					package net.minecraft.world.entity;

					import net.minecraft.world.phys.AABB;

					public class Entity {
						private final int id;
						private final AABB box;

						public Entity(int id, AABB box) {
							this.id = id;
							this.box = box;
						}

						public int getId() {
							return id;
						}

						public AABB getBoundingBox() {
							return box;
						}

						public String describe() {
							return getClass().getSimpleName() + "#" + id;
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
			"net.minecraftforge.common.extensions.IForgeLevel", """
					package net.minecraftforge.common.extensions;

					import java.util.Collection;
					import net.minecraftforge.entity.PartEntity;

					public interface IForgeLevel {
						Collection<PartEntity<?>> getPartEntities();
					}
					""",
			"fixture.ForgeWorm", """
					package fixture;

					import net.minecraft.world.entity.Entity;
					import net.minecraft.world.phys.AABB;
					import net.minecraftforge.entity.PartEntity;

					/** A MinecraftForge mod's multipart entity: it overrides only MinecraftForge's getParts(). */
					public class ForgeWorm extends Entity {
						public final PartEntity<?>[] parts = {new PartEntity<>(this, 41, new AABB(0, 1)), new PartEntity<>(this, 42, new AABB(5, 6))};

						public ForgeWorm() {
							super(40, new AABB(0, 1));
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
					""",
			"fixture.NeoWorm", """
					package fixture;

					import net.minecraft.world.entity.Entity;
					import net.minecraft.world.phys.AABB;
					import net.neoforged.neoforge.entity.PartEntity;

					/** A NeoForge mod's multipart entity: it overrides only NeoForge's getParts(). */
					public class NeoWorm extends Entity {
						public final PartEntity<?>[] parts = {new PartEntity<>(this, 51, new AABB(0, 1)), new PartEntity<>(this, 52, new AABB(0, 1))};

						public NeoWorm() {
							super(50, new AABB(0, 1));
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
					"""));

	static {
        STAND_INS.put("net.minecraftforge.entity.PartEntity", partEntity("net.minecraftforge.entity").replace("extends Entity {", "extends net.neoforged.neoforge.entity.PartEntity<T> {").replace("super(id, box)", "super(parent, id, box)"));
        STAND_INS.put("fixture.ArbitraryDual", """
            package fixture;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.phys.AABB;
            import net.minecraftforge.entity.PartEntity;
            public class ArbitraryDual extends Entity {
                public static final class EqualPart extends PartEntity<ArbitraryDual> {
                    EqualPart(ArbitraryDual parent,int id){super(parent,id,new AABB(0,1));}
                    @Override public boolean equals(Object other){return other instanceof Entity;}
                    @Override public int hashCode(){return 1;}
                }
                public final PartEntity<?>[] parts={new EqualPart(this,61),new EqualPart(this,62)};
                public ArbitraryDual(){super(60,new AABB(0,1));}
                @Override public boolean isMultipartEntity(){return true;}
                @Override public PartEntity<?>[] getParts(){return parts;}
                @Override public net.neoforged.neoforge.entity.PartEntity<?>[] getNeoParts(){return parts;}
            }
            """);
        STAND_INS.put("fixture.UnnamedSplit", """
            package fixture;
            public class UnnamedSplit extends ArbitraryDual {
                private final net.neoforged.neoforge.entity.PartEntity<?>[] other={parts[1],new EqualPart(this,63),parts[0]};
                @Override public net.neoforged.neoforge.entity.PartEntity<?>[] getNeoParts(){return other;}
            }
            """);
		STAND_INS.put("net.minecraft.server.level.ServerLevel", """
				package net.minecraft.server.level;

				import java.util.List;
				import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
				import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
				import net.minecraft.world.entity.Entity;
				import net.neoforged.neoforge.entity.PartEntity;

				public class ServerLevel {
					/** MinecraftForge's. */
					private final Int2ObjectMap<net.minecraftforge.entity.PartEntity<?>> partEntities = new Int2ObjectOpenHashMap<>();
					/** NeoForge's. */
					private final Int2ObjectMap<PartEntity<?>> dragonParts = new Int2ObjectOpenHashMap<>();
					public final EntityCallbacks callbacks = new EntityCallbacks();

					public List<Integer> forgeParts() {
						return partEntities.keys();
					}

					public List<Integer> neoForgeParts() {
						return dragonParts.keys();
					}

					/** NeoForge's bodies. */
					public final class EntityCallbacks {
						public void onTrackingStart(Entity entity) {
							if (entity.isMultipartEntity()) {
								for (PartEntity<?> part : entity.getNeoParts()) {
									ServerLevel.this.dragonParts.put(part.getId(), part);
								}
							}
						}

						public void onTrackingEnd(Entity entity) {
							if (entity.isMultipartEntity()) {
								for (PartEntity<?> part : entity.getNeoParts()) {
									ServerLevel.this.dragonParts.remove(part.getId());
								}
							}
						}
					}
				}
				""");
		STAND_INS.put("net.minecraft.client.multiplayer.ClientLevel", """
				package net.minecraft.client.multiplayer;

				import java.util.ArrayList;
				import java.util.Arrays;
				import java.util.List;
				import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
				import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
				import net.minecraft.world.entity.Entity;
				import net.minecraftforge.entity.PartEntity;

				public class ClientLevel {
					private final Int2ObjectMap<PartEntity<?>> partEntities = new Int2ObjectOpenHashMap<>();
					private final List<Object> dragonParts = new ArrayList<>();
					public final EntityCallbacks callbacks = new EntityCallbacks();

					public List<Integer> forgeParts() {
						return partEntities.keys();
					}

					public final class EntityCallbacks {
						/** MinecraftForge's body, which the merge kept. */
						public void onTrackingStart(Entity entity) {
							if (entity.isMultipartEntity()) {
								for (PartEntity<?> part : entity.getParts()) {
									ClientLevel.this.partEntities.put(part.getId(), part);
								}
							}
						}

						/** NeoForge's body. */
						public void onTrackingEnd(Entity entity) {
							if (entity.isMultipartEntity()) {
								ClientLevel.this.dragonParts.removeAll(Arrays.asList(entity.getNeoParts()));
							}
						}
					}
				}
				""");
		STAND_INS.put(HITBOXES, """
				package net.minecraft.client.renderer.debug;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.world.entity.Entity;
				import net.neoforged.neoforge.entity.PartEntity;

				public class EntityHitboxDebugRenderer {
					/** MinecraftForge's loop, read through NeoForge's getParts() as DragonPartsInjector leaves it. */
					public static List<String> showHitboxes(Entity entity) {
						List<String> drawn = new ArrayList<>();
						drawn.add(entity.describe());
						if (entity.isMultipartEntity()) {
							for (PartEntity<?> part : entity.getNeoParts()) drawn.add(part.describe());
						}
						return drawn;
					}
				}
				""");
		STAND_INS.put(LEVEL, """
				package net.minecraft.world.level;

				import java.util.ArrayList;
				import java.util.Collection;
				import java.util.List;
				import java.util.function.Predicate;
				import net.minecraft.world.entity.Entity;
				import net.minecraft.world.phys.AABB;
				import net.minecraftforge.common.extensions.IForgeLevel;
				import net.minecraftforge.entity.PartEntity;

				public class Level implements IForgeLevel {
					public final List<Entity> entities = new ArrayList<>();
					public final List<PartEntity<?>> forgeParts = new ArrayList<>();
                    public final List<net.neoforged.neoforge.entity.PartEntity<?>> nativeParts=new ArrayList<>();
                    public Collection<net.neoforged.neoforge.entity.PartEntity<?>> dragonParts(){return nativeParts;}

					@Override
					public Collection<PartEntity<?>> getPartEntities() {
						return forgeParts;
					}

					/** NeoForge's body. */
					public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> selector) {
						List<Entity> output = new ArrayList<>();
						for (Entity entity : entities) {
							if (entity != except && selector.test(entity) && box.intersects(entity.getBoundingBox())) output.add(entity);
						}
                        for(net.neoforged.neoforge.entity.PartEntity<?> part:dragonParts()){
                            if(part!=except&&part.getParent()!=except&&selector.test(part)&&box.intersects(part.getBoundingBox()))output.add(part);
                        }
                        return output;
                    }
                }
                """);
	}

	@AfterEach void reset() {
		System.clearProperty(ForgePartTrackingInjector.PROPERTY);
	}

	/** The merged shape: NeoForge's getter, and every override of it, under the name it shares with MinecraftForge's. */
	private static Map<String, byte[]> merged(Path work) throws Exception {return merged(work,Map.of());}
    private static Map<String, byte[]> merged(Path work,Map<String,String> overrides) throws Exception {
		Map<String,String> sources=new HashMap<>(STAND_INS);sources.putAll(overrides);
        sources.put("net.forbric.kernel.runtime.KernelMultipartViews",java.nio.file.Files.readString(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelMultipartViews.java")));
        sources.put("net.forbric.api.VirtualGetters",java.nio.file.Files.readString(Path.of("src/main/java/net/forbric/api/VirtualGetters.java")));
        Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources));
		String entity = ForgePartTrackingInjector.ENTITY;
		SimpleRemapper rename = new SimpleRemapper(Map.of(entity + ".getNeoParts" + ForgePartTrackingInjector.NEO_GET_PARTS, "getParts")) {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return super.mapMethodName(entity, name, descriptor);
			}
		};
		for (var entry : classes.entrySet()) {
			ClassWriter writer = new ClassWriter(0);
			new ClassReader(entry.getValue()).accept(new ClassRemapper(writer, rename), 0);
			entry.setValue(writer.toByteArray());
		}
		return classes;
	}

	private static Map<String, byte[]> repaired(Map<String, byte[]> original) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new ForgePartTrackingInjector(original::get), target, original.get(internal),
					EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target + " is the reviewed shape");
			classes.put(internal, out);
		}
		return classes;
	}

	private static Object level(ClassLoader loader, String type) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(type));
	}

	private static void callback(Object level, String name, Object entity) throws Throwable {
		InjectorExecution.invoke(level.getClass().getField("callbacks").get(level), name, entity);
	}

	private static Object entity(ClassLoader loader, String type) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(type));
	}

	@Test void aMinecraftForgeMultipartEntityIsTrackedDrawnAndFound(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		Map<String, byte[]> classes = repaired(original);
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		Object server = level(loader, "net.minecraft.server.level.ServerLevel");
		Object worm = entity(loader, "fixture.ForgeWorm");
		callback(server, "onTrackingStart", worm);
		assertEquals(List.of(41, 42), InjectorExecution.invoke(server, "forgeParts"), "the server tracks its parts, as MinecraftForge's does");
		assertEquals(List.of(), InjectorExecution.invoke(server, "neoForgeParts"));
		callback(server, "onTrackingEnd", worm);
		assertEquals(List.of(), InjectorExecution.invoke(server, "forgeParts"), "and untracks them when the world removes it");

		Object neoWorm = entity(loader, "fixture.NeoWorm");
		callback(server, "onTrackingStart", neoWorm);
		assertEquals(List.of(51, 52), InjectorExecution.invoke(server, "neoForgeParts"), "a NeoForge mod's parts keep NeoForge's tracking");
		assertEquals(List.of(), InjectorExecution.invoke(server, "forgeParts"));
		callback(server, "onTrackingEnd", neoWorm);
		assertEquals(List.of(), InjectorExecution.invoke(server, "neoForgeParts"));

		Object client = level(loader, "net.minecraft.client.multiplayer.ClientLevel");
		callback(client, "onTrackingStart", worm);
		assertEquals(List.of(41, 42), InjectorExecution.invoke(client, "forgeParts"));
		callback(client, "onTrackingEnd", worm);
		assertEquals(List.of(), InjectorExecution.invoke(client, "forgeParts"), "the client stops tracking its parts");

		assertEquals(List.of("ForgeWorm#40", "PartEntity#41", "PartEntity#42"), InjectorExecution.invokeStatic(loader.loadClass(HITBOXES), "showHitboxes", worm),
                "F3+B reads the public native arrays without dropping either family");
        assertEquals(List.of("NeoWorm#50", "PartEntity#51", "PartEntity#52"), InjectorExecution.invokeStatic(loader.loadClass(HITBOXES), "showHitboxes", neoWorm));

		Object level = level(loader, LEVEL);
		Object[] parts = (Object[]) worm.getClass().getField("parts").get(worm);
		addAll(level, "entities", List.of(worm));
		addAll(level, "forgeParts", List.of(parts));
		Object box = InjectorExecution.construct(loader.loadClass("net.minecraft.world.phys.AABB"), 0.5, 2.0);
		Predicate<Object> any = e -> true;
		assertEquals(List.of(worm, parts[0]), InjectorExecution.invoke(level, "getEntities", null, box, any),
				"the part in the box is found, as MinecraftForge's getEntities finds it");
		assertEquals(List.of(), InjectorExecution.invoke(level, "getEntities", worm, box, any),
				"an entity's own parts are not found for it");

		ClassLoader stock = InjectorExecution.load(original);
		Object mergedWorm = entity(stock, "fixture.ForgeWorm");
		Object mergedServer = level(stock, "net.minecraft.server.level.ServerLevel");
		assertThrows(NullPointerException.class, () -> callback(mergedServer, "onTrackingStart", mergedWorm),
				"premise: as merged, a server adding it to a world throws");
		assertThrows(NullPointerException.class, () -> callback(mergedServer, "onTrackingEnd", mergedWorm),
				"premise: and so does removing it");
		Object mergedClient = level(stock, "net.minecraft.client.multiplayer.ClientLevel");
		assertThrows(NullPointerException.class, () -> callback(mergedClient, "onTrackingEnd", mergedWorm),
				"premise: as merged, the client stopping tracking it throws");
		assertThrows(NullPointerException.class,
				() -> InjectorExecution.invokeStatic(stock.loadClass(HITBOXES), "showHitboxes", mergedWorm),
				"premise: as merged, F3+B throws on every frame it is in view");
		Object mergedLevel = level(stock, LEVEL);
		addAll(mergedLevel, "entities", List.of(mergedWorm));
		addAll(mergedLevel, "forgeParts", List.of((Object[]) mergedWorm.getClass().getField("parts").get(mergedWorm)));
		Object mergedBox = InjectorExecution.construct(stock.loadClass("net.minecraft.world.phys.AABB"), 0.5, 2.0);
		assertEquals(List.of(mergedWorm), InjectorExecution.invoke(mergedLevel, "getEntities", null, mergedBox, any),
				"premise: as merged, its parts are never found");

		for (String target : TARGETS) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new ForgePartTrackingInjector(classes::get), target, once, EnvType.CLIENT),
					target + " is left alone the second time");
		}
	}

    @Test void sameNativeArrayIsVisitedOnceEvenWhenPartsCompareEqual(@TempDir Path work)throws Throwable{
        Map<String,byte[]> classes=repaired(merged(work));ClassLoader loader=InjectorExecution.load(classes);Object owner=entity(loader,"fixture.ArbitraryDual"),level=level(loader,LEVEL);Object[] parts=(Object[])owner.getClass().getField("parts").get(owner);
        addAll(level,"forgeParts",List.of(parts));addAll(level,"nativeParts",List.of(parts));Object box=InjectorExecution.construct(loader.loadClass("net.minecraft.world.phys.AABB"),0.0,2.0);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        List<?> found=(List<?>)InjectorExecution.invoke(level,"getEntities",null,box,(Predicate<Object>)value->{calls.incrementAndGet();return true;});assertEquals(2,found.size());assertSame(parts[0],found.get(0));assertSame(parts[1],found.get(1));assertEquals(2,calls.get(),"the additional SDK view does not repeat a native predicate");
        calls.set(0);assertEquals(List.of(),InjectorExecution.invoke(level,"getEntities",null,box,(Predicate<Object>)value->{calls.incrementAndGet();return false;}));assertEquals(2,calls.get(),"rejected identities are also already processed");
        assertEquals(List.of("ArbitraryDual#60","EqualPart#61","EqualPart#62"),InjectorExecution.invokeStatic(loader.loadClass(HITBOXES),"showHitboxes",owner));
        Object server=level(loader,"net.minecraft.server.level.ServerLevel");callback(server,"onTrackingStart",owner);assertEquals(List.of(61,62),InjectorExecution.invoke(server,"forgeParts"));assertEquals(List.of(61,62),InjectorExecution.invoke(server,"neoForgeParts"));callback(server,"onTrackingEnd",owner);assertEquals(List.of(),InjectorExecution.invoke(server,"forgeParts"));assertEquals(List.of(),InjectorExecution.invoke(server,"neoForgeParts"));
    }

    @Test void distinctNativeArraysRetainThePrimaryApiOrderAndUseIdentity(@TempDir Path work)throws Throwable{
        Map<String,byte[]> original=merged(work);Map<String,byte[]> classes=repaired(original);ClassLoader loader=InjectorExecution.load(classes);
        Object owner=entity(loader,"fixture.UnnamedSplit");
        assertEquals(List.of("UnnamedSplit#60","EqualPart#62","EqualPart#63","EqualPart#61"),InjectorExecution.invokeStatic(loader.loadClass(HITBOXES),"showHitboxes",owner),"the Neo canonical reader keeps its existing order and includes the other public view");
        // Change only the native reader's published return descriptor; no entity name participates in the repair.
        var node=new org.objectweb.asm.tree.ClassNode();new ClassReader(original.get(HITBOXES.replace('.','/'))).accept(node,0);
        for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call&&call.name.equals("getParts")&&call.desc.equals(ForgePartTrackingInjector.NEO_GET_PARTS))call.desc=ForgePartTrackingInjector.FORGE_GET_PARTS;
        ClassWriter writer=new ClassWriter(0);node.accept(writer);byte[] forgeFirst=InjectorExecution.transform(new ForgePartTrackingInjector(original::get),HITBOXES,writer.toByteArray(),EnvType.CLIENT);
        Map<String,byte[]> forgeClasses=new HashMap<>(classes);forgeClasses.put(HITBOXES.replace('.','/'),forgeFirst);ClassLoader forgeLoader=InjectorExecution.load(forgeClasses);
        assertEquals("",InjectorExecution.verify(forgeFirst,forgeLoader));
        assertEquals(List.of("UnnamedSplit#60","EqualPart#61","EqualPart#62","EqualPart#63"),InjectorExecution.invokeStatic(forgeLoader.loadClass(HITBOXES),"showHitboxes",entity(forgeLoader,"fixture.UnnamedSplit")),"the Forge canonical reader keeps Forge's order first");
    }

    @Test void debugOperationsRequiringTheNativePartTypeArePreserved(@TempDir Path work)throws Exception{
        for(String body:List.of("drawn.add(part.getParent().describe());","if(part instanceof net.minecraftforge.entity.PartEntity<?>)drawn.add(part.describe());")){
            String source=STAND_INS.get(HITBOXES).replace("drawn.add(part.describe());",body);
            Map<String,byte[]> classes=merged(work.resolve(Integer.toString(body.hashCode())),Map.of(HITBOXES,source));byte[] original=classes.get(HITBOXES.replace('.','/'));
            assertSame(original,InjectorExecution.transform(new ForgePartTrackingInjector(classes::get),HITBOXES,original,EnvType.CLIENT),"a receiver rewrite needs the shared public Entity contract, and a family type test cannot be widened");
        }
    }

	@SuppressWarnings("unchecked")
	private static void addAll(Object level, String field, List<Object> values) throws ReflectiveOperationException {
		((List<Object>) level.getClass().getField(field).get(level)).addAll(values);
	}

	@Test void switchedOffAllFourClassesAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = merged(work);
		System.setProperty(ForgePartTrackingInjector.PROPERTY, "off");
		for (String target : TARGETS) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new ForgePartTrackingInjector(original::get), target, bytes, EnvType.CLIENT), target);
		}
	}
}
