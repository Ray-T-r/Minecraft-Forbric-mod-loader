/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * A MinecraftForge mod's multipart entity on the merged game: on the real merged classes, and in a real JVM, where the
 * repaired server callbacks, client callbacks, Level and debug hitboxes are linked — and so verified — against the merged
 * game and both carriers, then handed a MinecraftForge mod's multipart entity, a NeoForge mod's and the Ender Dragon. As in
 * {@link ClientPartTrackingInjectorTest}, class initializers that need a bootstrapped game are left out and the levels,
 * renderer and entities are allocated bare; what they reach besides the parts is stubbed or given its quiet state (a
 * chunk cache that tracks nothing, sleeping debug synchronizers, an entity with no passengers, a gizmo collector).
 */
@ResourceLock("system-properties")
class ForgePartTrackingInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_CARRIER = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final String SERVER_CALLBACKS = ForgePartTrackingInjector.SERVER_CALLBACKS_INTERNAL;
	private static final String CLIENT_CALLBACKS = ClientPartTrackingInjector.CALLBACKS_INTERNAL;
	private static final String SERVER_LEVEL = ForgePartTrackingInjector.SERVER_LEVEL;
	private static final String CLIENT_LEVEL = ForgePartTrackingInjector.CLIENT_LEVEL;
	private static final String LEVEL = ForgePartTrackingInjector.LEVEL_INTERNAL;
	private static final String HITBOXES = "net/minecraft/client/renderer/debug/EntityHitboxDebugRenderer";
	private static final String ENTITY = ForgePartTrackingInjector.ENTITY;
	private static final String DRAGON = ClientPartTrackingInjector.DRAGON;
	private static final String DRAGON_PART = DragonPartsInjector.PART_INTERNAL;
	private static final String TRACKING_DESC = ForgePartTrackingInjector.TRACKING_DESC;
	private static final String NEO_GET_PARTS = ForgePartTrackingInjector.NEO_GET_PARTS;
	private static final String FORGE_GET_PARTS = ForgePartTrackingInjector.FORGE_GET_PARTS;
	private static final String GET_ENTITIES_DESC = ForgePartTrackingInjector.GET_ENTITIES_DESC;

	@AfterEach void reset() { System.clearProperty(ForgePartTrackingInjector.PROPERTY); }

	@Test void theServerCallbacksTrackMinecraftForgesPartsAndTolerateANullNeoForgeGetParts() throws Exception {
		byte[] merged = NativeCoremodParityTest.read(MERGED, SERVER_CALLBACKS);
		ClassNode original = node(merged);
		for (String name : List.of("onTrackingStart", "onTrackingEnd")) {
			assertEquals(List.of(NEO_GET_PARTS), partsRead(method(original, name, TRACKING_DESC)),
					"premise: the merged " + name + " is NeoForge's body, reading only NeoForge's parts");
		}
		assertTrue(declaresPartEntities(MERGED, SERVER_LEVEL), "premise: the merged ServerLevel keeps MinecraftForge's partEntities");

		byte[] out = injector(MERGED).transform(ForgePartTrackingInjector.SERVER_CALLBACKS, merged, null);
		ClassNode repaired = node(out);
		assertBlock(method(repaired, "onTrackingStart", TRACKING_DESC), SERVER_CALLBACKS, ForgePartTrackingInjector.TRACK);
		assertBlock(method(repaired, "onTrackingEnd", TRACKING_DESC), SERVER_CALLBACKS, ForgePartTrackingInjector.UNTRACK);
		assertLoop(method(repaired, ForgePartTrackingInjector.TRACK, ForgePartTrackingInjector.TRACK_DESC), "put");
		assertLoop(method(repaired, ForgePartTrackingInjector.UNTRACK, ForgePartTrackingInjector.TRACK_DESC), "remove");
		for (MethodNode method : repaired.methods) new Analyzer<>(new BasicVerifier()).analyze(SERVER_CALLBACKS, method);
		assertSame(out, injector(MERGED).transform(ForgePartTrackingInjector.SERVER_CALLBACKS, out, null));
	}

    @Test void theClientsOnTrackingEndUntracksMinecraftForgesPartsAndToleratesANullNeoForgeGetParts() throws Exception {
        byte[] merged=NativeCoremodParityTest.read(MERGED,CLIENT_CALLBACKS);ClassNode original=node(merged);
        for(String direction:List.of("onTrackingStart","onTrackingEnd"))assertEquals(List.of(NEO_GET_PARTS),partsRead(method(original,direction,TRACKING_DESC)));
        byte[] out=injector(MERGED).transform(ForgePartTrackingInjector.CLIENT_CALLBACKS,merged,null);ClassNode repaired=node(out);
        for(String direction:List.of("onTrackingStart","onTrackingEnd")){
            String helper=direction.equals("onTrackingStart")?ForgePartTrackingInjector.TRACK:ForgePartTrackingInjector.UNTRACK;
            MethodNode method=method(repaired,direction,TRACKING_DESC);
            assertTrue(calls(method,helper),"each direction reaches the missing map effect at the shared native exit");
            assertLoop(method(repaired,helper,ForgePartTrackingInjector.TRACK_DESC),direction.equals("onTrackingStart")?"put":"remove");
            new Analyzer<>(new BasicVerifier()).analyze(CLIENT_CALLBACKS,method);
        }
        assertSame(out,injector(MERGED).transform(ForgePartTrackingInjector.CLIENT_CALLBACKS,out,null));
    }

    @Test void theHitboxesTolerateANullGetParts() throws Exception {
        byte[] original=NativeCoremodParityTest.read(MERGED,HITBOXES),out=injector(MERGED).transform(ForgePartTrackingInjector.HITBOXES,original,null);
        MethodNode show=node(out).methods.stream().filter(m->m.name.equals("showHitboxes")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(show.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals("net/forbric/kernel/runtime/KernelMultipartViews")&&call.name.equals("parts")),"the actual loop reads both native arrays with identity deduplication");
        assertTrue(partsRead(show).isEmpty());new Analyzer<>(new BasicVerifier()).analyze(HITBOXES,show);assertSame(out,injector(MERGED).transform(ForgePartTrackingInjector.HITBOXES,out,null));
    }

	@Test void getEntitiesAlsoLooksThroughGetPartEntitiesBeforeItReturns() throws Exception {
		byte[] merged = NativeCoremodParityTest.read(MERGED, LEVEL);
		MethodNode original = method(node(merged), "getEntities", GET_ENTITIES_DESC);
		assertTrue(calls(original, "dragonParts") && !calls(original, "getPartEntities"),
				"premise: the merged getEntities is NeoForge's body, looking through dragonParts() only");

		byte[] out = injector(MERGED).transform(ForgePartTrackingInjector.LEVEL, merged, null);
		ClassNode repaired = node(out);
		List<AbstractInsnNode> code = real(method(repaired, "getEntities", GET_ENTITIES_DESC));
		int ret = code.size() - 1;
		assertEquals(Opcodes.ARETURN, code.get(ret).getOpcode());
		assertTrue(code.get(ret - 2) instanceof MethodInsnNode find && find.getOpcode() == Opcodes.INVOKESTATIC
				&& find.name.equals(ForgePartTrackingInjector.FIND) && find.owner.equals(LEVEL), "just before `return output`");
		assertEquals(List.of(0, 1, 2, 3, ((VarInsnNode) code.get(ret - 1)).var),
                code.subList(ret - 8, ret - 3).stream().map(i -> ((VarInsnNode) i).var).toList(), "original arguments and output precede the visited identity set");
        assertTrue(code.get(ret-3) instanceof VarInsnNode seen && seen.getOpcode()==Opcodes.ALOAD);
		MethodNode find = method(repaired, ForgePartTrackingInjector.FIND, ForgePartTrackingInjector.FIND_DESC);
		assertTrue(calls(find, "getPartEntities") && calls(find, "getParent") && calls(find, "intersects"), "MinecraftForge's own loop");
		assertTrue(Arrays.stream(find.instructions.toArray()).anyMatch(i -> i instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
				&& cast.desc.equals(ForgePartTrackingInjector.FORGE_PART)), "a MinecraftForge part, never cast to NeoForge's");
		new Analyzer<>(new BasicVerifier()).analyze(LEVEL, method(repaired, "getEntities", GET_ENTITIES_DESC));
		new Analyzer<>(new BasicVerifier()).analyze(LEVEL, find);
		assertSame(out, injector(MERGED).transform(ForgePartTrackingInjector.LEVEL, out, null));
	}

	@Test void neoForgesOwnClassesAreLeftAlone() throws Exception {
		for (String name : List.of(SERVER_CALLBACKS, CLIENT_CALLBACKS, LEVEL, HITBOXES)) {
			byte[] own = NativeCoremodParityTest.read(NEO, name);
			assertSame(own, injector(NEO).transform(dotted(name), own, null), name);
		}
	}

	@Test void theSwitchLeavesAllFourAlone() throws Exception {
		System.setProperty(ForgePartTrackingInjector.PROPERTY, "off");
		for (String name : List.of(SERVER_CALLBACKS, CLIENT_CALLBACKS, LEVEL, HITBOXES)) {
			byte[] merged = NativeCoremodParityTest.read(MERGED, name);
			assertSame(merged, injector(MERGED).transform(dotted(name), merged, null), name);
		}
	}

	@Test void aMinecraftForgeModsMultipartEntityIsAddedToAndRemovedFromAServerWorld() throws Exception {
		try (Server merged = new Server(false)) {
			Object entity = merged.multipart("fixture.ForgeMultipartEntity", "fixture.ForgePart", 2);
			assertThrewOnNullParts(assertThrows(InvocationTargetException.class, () -> merged.start(entity),
					"control: as merged, NeoForge's getParts() answers null for a MinecraftForge mod's entity"), "onTrackingStart");
			assertThrewOnNullParts(assertThrows(InvocationTargetException.class, () -> merged.end(entity),
					"control: and removing it threw the same way, so the server could not unload or stop"), "onTrackingEnd");
			merged.partEntities().put(1, merged.parts(entity)[0]);
			assertTrue(merged.getEntities(null).isEmpty(), "control: as merged, getEntities never looks through partEntities");
		}
		try (Server repaired = new Server(true)) {
			Object entity = repaired.multipart("fixture.ForgeMultipartEntity", "fixture.ForgePart", 2);
			repaired.start(entity);
			Map<?, ?> tracked = repaired.partEntities();
			assertEquals(Set.of(1, 2), new HashSet<>(tracked.keySet()), "tracked where MinecraftForge tracks them");
			assertSame(repaired.parts(entity)[1], tracked.get(2));
			assertTrue(repaired.dragonParts().isEmpty(), "never where a NeoForge-typed consumer would cast them");
			assertEquals(List.of(1, 2), repaired.ids(repaired.getEntities(null)), "found by a box lookup, once each, as on MinecraftForge");
			assertEquals(List.of(), repaired.getEntities(entity), "but not by its own parent's lookup, as on MinecraftForge");
			repaired.end(entity);
			assertTrue(repaired.partEntities().isEmpty(), "and taken out again");
			assertTrue(repaired.getEntities(null).isEmpty());
		}
	}

	@Test void aNeoForgeModsMultipartEntityKeepsItsServerTracking() throws Exception {
		try (Server repaired = new Server(true)) {
			Object entity = repaired.multipart("fixture.NeoMultipartEntity", "fixture.NeoPart", 2);
			repaired.start(entity);
			assertEquals(Set.of(1, 2), new HashSet<>(repaired.dragonParts().keySet()), "NeoForge's own loop, as before");
			assertTrue(repaired.partEntities().isEmpty(), "MinecraftForge's getParts() answers null for it, and is skipped");
			assertEquals(List.of(1, 2), repaired.ids(repaired.getEntities(null)), "found once, through dragonParts()");
			repaired.end(entity);
			assertTrue(repaired.dragonParts().isEmpty());
		}
	}

	@Test void theDragonsPartsStayOutOfPartEntities() throws Exception {
		try (Server repaired = new Server(true)) {
			Object dragon = repaired.dragon(2);
			repaired.track(dragon);
			assertEquals(Set.of(1,2),new HashSet<>(repaired.partEntities().keySet()),"both native getParts descriptors retain the same actual parts");
            assertEquals(List.of(1,2),repaired.ids(repaired.getEntities(null)),"overlapping part registries produce each identity once");
		}
	}

	@Test void aMinecraftForgeModsMultipartEntityLeavesTheClientCleanly() throws Exception {
		try (Client merged = new Client(false)) {
			Object entity = merged.multipart("fixture.ForgeMultipartEntity", "fixture.ForgePart", 2);
            assertThrewOnNullParts(assertThrows(InvocationTargetException.class,()->merged.start(entity)),"onTrackingStart");
            merged.partEntities().put(1,merged.parts(entity)[0]);
			assertThrewOnNullParts(assertThrows(InvocationTargetException.class, () -> merged.end(entity),
					"control: as merged, NeoForge's getParts() answers null for a MinecraftForge mod's entity"), "onTrackingEnd");
		}
		try (Client repaired = new Client(true)) {
			Object entity = repaired.multipart("fixture.ForgeMultipartEntity", "fixture.ForgePart", 2);
			repaired.start(entity);
			repaired.end(entity);
			assertTrue(repaired.partEntities().isEmpty(), "taken out again, as MinecraftForge's onTrackingEnd does");
			assertTrue(repaired.dragonParts().isEmpty());
		}
	}

	@Test void aNeoForgeModsMultipartEntityKeepsItsClientTracking() throws Exception {
		try (Client repaired = new Client(true)) {
			Object entity = repaired.multipart("fixture.NeoMultipartEntity", "fixture.NeoPart", 2);
			repaired.start(entity);
			assertEquals(2, repaired.dragonParts().size());
			repaired.end(entity);
			assertTrue(repaired.dragonParts().isEmpty(), "NeoForge's own removal, as before");
			assertTrue(repaired.partEntities().isEmpty());
		}
	}

    @Test void theHitboxesOfAMinecraftForgeModsMultipartEntityAreDrawnWithoutThrowing() throws Exception {
        try(Hitboxes merged=new Hitboxes(false)){
            assertTrue(merged.show(merged.multipart("fixture.ForgeMultipartEntity","fixture.ForgePart",2))>0,"the actual native Forge renderer already draws its own API");
            assertThrewOnNullParts(assertThrows(InvocationTargetException.class,()->merged.show(merged.multipart("fixture.NeoMultipartEntity","fixture.NeoPart",2))),"showHitboxes");
        }
        try(Hitboxes repaired=new Hitboxes(true)){
            int forge=repaired.show(repaired.multipart("fixture.ForgeMultipartEntity","fixture.ForgePart",2));
            int neo=repaired.show(repaired.multipart("fixture.NeoMultipartEntity","fixture.NeoPart",2));
            assertEquals(forge,neo,"both APIs draw the same number of actual part boxes");
        }
    }

	/** The merged game, both carriers and the libraries they link against, with some classes defined from given bytes. */
	private abstract static class Game implements AutoCloseable {
		final URLClassLoader loader;
		final Object level;
		final Object callbacks;
		private final Method start;
		private final Method end;

		Game(String levelType, String callbacksType, Map<String, byte[]> defined) throws Exception {
			defined.putIfAbsent(dotted(levelType), withoutInitializer(NativeCoremodParityTest.read(MERGED, levelType)));
			loader = gameLoader(defined);
			level = unsafe().allocateInstance(loader.loadClass(levelClass()));
			Class<?> callbacksClass = type(callbacksType);
			callbacks = unsafe().allocateInstance(callbacksClass);
			field(callbacksClass, "this$0").set(callbacks, level);
			start = callbacksClass.getDeclaredMethod("onTrackingStart", type(ENTITY));
			end = callbacksClass.getDeclaredMethod("onTrackingEnd", type(ENTITY));
			start.setAccessible(true);
			end.setAccessible(true);
		}

		/** The class the level is allocated as: the level itself, or a fixture extending it. */
		abstract String levelClass();

		void start(Object entity) throws Exception { start.invoke(callbacks, entity); }

		void end(Object entity) throws Exception { end.invoke(callbacks, entity); }

		Object multipart(String entityName, String partName, int count) throws Exception {
			return ForgePartTrackingInjectorTest.multipart(loader, entityName, partName, count);
		}

		/** An Ender Dragon with {@code count} parts in its subEntities. */
        Object dragon(int count) throws Exception {
            Class<?> partClass=type(DRAGON_PART);Object parts=Array.newInstance(partClass,count);Object dragon=bareEntity(loader,type(DRAGON),0);
            for(int i=0;i<count;i++){
                Object part=bareEntity(loader,partClass,i+1);
                for(Class<?> owner=partClass;owner!=null;owner=owner.getSuperclass())for(Field state:owner.getDeclaredFields())if(state.getName().equals("parent")){state.setAccessible(true);state.set(part,dragon);}
                field(type(ENTITY),"bb").set(part,box(loader,i,0,0,i+1,1,1));Array.set(parts,i,part);
            }
            field(type(DRAGON),"subEntities").set(dragon,parts);return dragon;
        }

		Object[] parts(Object entity) throws Exception {
			return (Object[]) field(entity.getClass(), "parts").get(entity);
		}

		/** The parts by id, in id order: a bare entity cannot print itself, and a map of them has no order of its own. */
		List<Integer> ids(Iterable<?> entities) throws Exception {
			List<Integer> ids = new ArrayList<>();
			for (Object entity : entities) ids.add(field(type(ENTITY), "id").getInt(entity));
			ids.sort(null);
			return ids;
		}

		abstract Map<Integer, Object> partEntities() throws Exception;

		Class<?> type(String internalName) throws ClassNotFoundException {
			return Class.forName(dotted(internalName), false, loader);
		}

		@Override public void close() throws Exception { loader.close(); }
	}

	/**
	 * The server: its callbacks and Level merged or repaired, the level a {@code ServerLevel} whose entity lookup holds no
	 * whole entities (so that a box lookup returns parts only) and whose chunk cache tracks nothing.
	 */
	private static final class Server extends Game {
		Server(boolean repaired) throws Exception {
			super(SERVER_LEVEL, SERVER_CALLBACKS, classes(repaired));
			Class<?> server = type(SERVER_LEVEL);
			field(server, "dragonParts").set(level, type("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap").getConstructor().newInstance());
			field(server, "partEntities").set(level, type("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap").getConstructor().newInstance());
			field(server, "chunkSource").set(level, unsafe().allocateInstance(loader.loadClass("fixture.QuietChunkCache")));
			Object synchronizers = unsafe().allocateInstance(type("net/minecraft/util/debug/LevelDebugSynchronizers"));
			field(synchronizers.getClass(), "sleeping").setBoolean(synchronizers, true);
			field(server, "debugSynchronizers").set(level, synchronizers);
		}

		private static Map<String, byte[]> classes(boolean repaired) throws Exception {
			Map<String, byte[]> defined = new HashMap<>();
			byte[] callbacks = NativeCoremodParityTest.read(MERGED, SERVER_CALLBACKS);
			byte[] level = NativeCoremodParityTest.read(MERGED, LEVEL);
			if (repaired) {
				callbacks = injector(MERGED).transform(ForgePartTrackingInjector.SERVER_CALLBACKS, callbacks, null);
				level = injector(MERGED).transform(ForgePartTrackingInjector.LEVEL, level, null);
			}
			defined.put(dotted(SERVER_CALLBACKS), callbacks);
			defined.put(dotted(LEVEL), withoutInitializer(level));
			String chunkCache = "net/minecraft/server/level/ServerChunkCache";
			defined.put(dotted(chunkCache), withoutInitializer(NativeCoremodParityTest.read(MERGED, chunkCache)));
			defined.put("fixture.QuietChunkCache", quiet("fixture/QuietChunkCache", chunkCache, "addEntity", "removeEntity"));
			defined.put("fixture.BareServerLevel", bareLevel("fixture/BareServerLevel", SERVER_LEVEL, Opcodes.ACC_PUBLIC));
			defined.put("fixture.NoEntities", noEntities());
			return defined;
		}

		@Override String levelClass() { return "fixture.BareServerLevel"; }

		/** The added loop alone, as onTrackingStart calls it. */
		void track(Object entity) throws Exception {
			Method track = type(SERVER_CALLBACKS).getDeclaredMethod(ForgePartTrackingInjector.TRACK, type(ForgePartTrackingInjector.PARTS_MAP), type(ENTITY));
			track.setAccessible(true);
			track.invoke(null, partEntities(), entity);
		}

		@SuppressWarnings("unchecked")
		Map<Integer, Object> dragonParts() throws Exception { return (Map<Integer, Object>) field(type(SERVER_LEVEL), "dragonParts").get(level); }

		@SuppressWarnings("unchecked")
		@Override Map<Integer, Object> partEntities() throws Exception { return (Map<Integer, Object>) field(type(SERVER_LEVEL), "partEntities").get(level); }

		/** {@code level.getEntities(except, a box around every part, anything)}. */
		List<?> getEntities(Object except) throws Exception {
			Object around = box(loader, -10, -10, -10, 10, 10, 10);
			Predicate<Object> anything = entity -> true;
			return (List<?>) type(LEVEL).getMethod("getEntities", type(ENTITY), around.getClass(), Predicate.class).invoke(level, except, around, anything);
		}
	}

	/** The client: its callbacks as the game runs them (ClientPartTrackingInjector's onTrackingStart), onTrackingEnd merged or repaired. */
	private static final class Client extends Game {
		Client(boolean repaired) throws Exception {
			super(CLIENT_LEVEL, CLIENT_CALLBACKS, classes(repaired));
			Class<?> client = type(CLIENT_LEVEL);
			field(client, "players").set(level, new ArrayList<>());
			field(client, "dragonParts").set(level, new ArrayList<>());
			field(client, "partEntities").set(level, type("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap").getConstructor().newInstance());
		}

		private static Map<String, byte[]> classes(boolean repaired) throws Exception {
			Map<String, byte[]> defined = new HashMap<>();
			byte[] callbacks = new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS,
					NativeCoremodParityTest.read(MERGED, CLIENT_CALLBACKS), null);
			if (repaired) callbacks = injector(MERGED).transform(ForgePartTrackingInjector.CLIENT_CALLBACKS, callbacks, null);
			defined.put(dotted(CLIENT_CALLBACKS), callbacks);
			return defined;
		}

		@Override String levelClass() { return dotted(CLIENT_LEVEL); }

		List<?> dragonParts() throws Exception { return (List<?>) field(type(CLIENT_LEVEL), "dragonParts").get(level); }

		@SuppressWarnings("unchecked")
		@Override Map<Integer, Object> partEntities() throws Exception { return (Map<Integer, Object>) field(type(CLIENT_LEVEL), "partEntities").get(level); }
	}

	/** The renderer's native Forge body or the proved public Entity view, and the actual boxes each draws. */
	private static final class Hitboxes implements AutoCloseable {
		final URLClassLoader loader;
		private final Object renderer;
		private final Method show;
		private final List<Object> drawn = new ArrayList<>();

		Hitboxes(boolean repaired) throws Exception {
			Map<String, byte[]> defined = new HashMap<>();
			byte[] hitboxes = NativeCoremodParityTest.read(MERGED, HITBOXES);
			if (repaired) hitboxes = injector(MERGED).transform(ForgePartTrackingInjector.HITBOXES, hitboxes, null);
			defined.put(dotted(HITBOXES), withoutInitializer(hitboxes));
			loader = gameLoader(defined);
			Class<?> rendererClass = Class.forName(dotted(HITBOXES), false, loader);
			renderer = unsafe().allocateInstance(rendererClass);
			show = rendererClass.getDeclaredMethod("showHitboxes", Class.forName(dotted(ENTITY), false, loader), float.class, boolean.class);
			show.setAccessible(true);
		}

		/** Draws {@code entity}'s hitboxes as F3+B does, into a collector that keeps them; how many were drawn. */
		int show(Object entity) throws Exception {
			Class<?> collectorType = loader.loadClass("net.minecraft.gizmos.GizmoCollector");
			Object ignored = collectorType.getField("IGNORED").get(null);
			Object collector = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {collectorType}, (proxy, method, args) -> {
				if (!method.getName().equals("add")) throw new UnsupportedOperationException(method.getName());
				drawn.add(args[0]);
				return ignored;
			});
			drawn.clear();
			try (AutoCloseable collecting = (AutoCloseable) loader.loadClass("net.minecraft.gizmos.Gizmos")
					.getMethod("withCollector", collectorType).invoke(null, collector)) {
				show.invoke(renderer, entity, 0f, false);
			}
			return drawn.size();
		}

		/** {@code multipart(…)} with what the renderer reads of an entity set: a position, no motion, a box. */
		Object multipart(String entityName, String partName, int count) throws Exception {
			Object entity = ForgePartTrackingInjectorTest.multipart(loader, entityName, partName, count);
			placed(entity);
			Object parts = field(entity.getClass(), "parts").get(entity);
			for (int i = 0; i < Array.getLength(parts); i++) placed(Array.get(parts, i));
			return entity;
		}

		private void placed(Object entity) throws Exception {
			Class<?> entityClass = Class.forName(dotted(ENTITY), false, loader);
			Object zero = loader.loadClass("net.minecraft.world.phys.Vec3").getField("ZERO").get(null);
			field(entityClass, "position").set(entity, zero);
			field(entityClass, "deltaMovement").set(entity, zero);
			if (field(entityClass, "bb").get(entity) == null) field(entityClass, "bb").set(entity, box(loader, 0, 0, 0, 1, 1, 1));
		}

		@Override public void close() throws Exception { loader.close(); }
	}

	/**
	 * The merged game, both carriers and the libraries they link against; {@code defined}'s classes, then the dragon as the
	 * game runs it, the entity chain and the levels without their initializers, and the mod fixtures, defined from bytes.
	 */
	private static URLClassLoader gameLoader(Map<String, byte[]> defined) throws Exception {
		TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the merged game is class-file 69, which only Java 25 links");
		for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), jar + " absent");
		// The actual coherent runtime hierarchy supports both native part arrays.
		defined.put(dotted(DRAGON_PART), NativeCoremodParityTest.read(MERGED, DRAGON_PART));
		defined.put(dotted(DRAGON), withoutInitializer(NativeCoremodParityTest.read(MERGED, DRAGON)));
		for (String name : List.of(ENTITY, "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob", LEVEL)) {
			defined.putIfAbsent(dotted(name), withoutInitializer(NativeCoremodParityTest.read(MERGED, name)));
		}
		// Entity's and Level's superclass asks FML whether this is a production run.
		String attachments = "net/neoforged/neoforge/attachment/AttachmentHolder";
		defined.put(dotted(attachments), withoutInitializer(NativeCoremodParityTest.read(NEO_CARRIER, attachments)));
		defined.put("fixture.NeoPart", part("fixture/NeoPart", DragonPartsInjector.NEO_PART));
		defined.put("fixture.ForgePart", part("fixture/ForgePart", DragonPartsInjector.FORGE_PART));
		defined.put("fixture.NeoMultipartEntity", multipartEntity("fixture/NeoMultipartEntity", NEO_GET_PARTS));
		defined.put("fixture.ForgeMultipartEntity", multipartEntity("fixture/ForgeMultipartEntity", FORGE_GET_PARTS));

		List<URL> urls = new ArrayList<>(List.of(MERGED.toUri().toURL(), NEO_CARRIER.toUri().toURL(), FORGE_CARRIER.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier",
				"com/google/guava/guava", "it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				"io/netty/netty-common", "io/netty/netty-buffer", "org/joml/joml", "com/mojang/authlib",
				"org/apache/commons/commons-lang3", "com/mojang/logging", "com/mojang/jtracy")) {
			Path library = newestUnder(pattern);
			TestFixtures.require(Fixture.MC_LIBRARIES, library != null, "no " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		// Netty's codecs moved between releases (netty-codec, then netty-codec-base); whichever this install has.
		for (String pattern : List.of("io/netty/netty-codec-base", "io/netty/netty-codec/", "io/netty/netty-transport/",
				"io/netty/netty-handler")) {
			Path library = newestUnder(pattern);
			if (library != null) urls.add(library.toUri().toURL());
		}
        Map<String,String> helperSources=Map.of("net.forbric.kernel.runtime.KernelMultipartViews",Files.readString(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelMultipartViews.java")),
            "net.forbric.api.VirtualGetters",Files.readString(Path.of("src/main/java/net/forbric/api/VirtualGetters.java")));
        for(var entry:InjectorExecution.compile(Files.createTempDirectory("multipart-actual-helper"),helperSources,urls.stream().map(url->{try{return Path.of(url.toURI());}catch(Exception bad){throw new IllegalStateException(bad);}}).toList()).entrySet())defined.put(dotted(entry.getKey()),entry.getValue());
		return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = defined.get(name);
				return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.findClass(name);
			}
		};
	}

	/**
	 * A mod's multipart entity whose own family's {@code getParts()} answers {@code count} parts, ids 1 and up, each one
	 * block wide, side by side from the origin.
	 */
	private static Object multipart(URLClassLoader loader, String entityName, String partName, int count) throws Exception {
		Class<?> entityClass = loader.loadClass(entityName);
		Object entity = bareEntity(loader, entityClass, 0);
		Class<?> partClass = loader.loadClass(partName);
		Object parts = Array.newInstance(partClass.getSuperclass(), count);
		for (int i = 0; i < count; i++) {
			Object part = bareEntity(loader, partClass, i + 1);
			field(partClass.getSuperclass(), "parent").set(part, entity);
			field(Class.forName(dotted(ENTITY), false, loader), "bb").set(part, box(loader, i, 0, 0, i + 1, 1, 1));
			Array.set(parts, i, part);
		}
		field(entityClass, "parts").set(entity, parts);
		return entity;
	}

	/** An entity with an id, no passengers and no vehicle — what onTrackingEnd's unRide() reads. */
	private static Object bareEntity(URLClassLoader loader, Class<?> type, int id) throws Exception {
		Class<?> entityClass = Class.forName(dotted(ENTITY), false, loader);
		Object entity = unsafe().allocateInstance(type);
		field(entityClass, "id").setInt(entity, id);
		field(entityClass, "passengers").set(entity, loader.loadClass("com.google.common.collect.ImmutableList").getMethod("of").invoke(null));
		return entity;
	}

	private static Object box(URLClassLoader loader, double x0, double y0, double z0, double x1, double y1, double z1) throws Exception {
		return loader.loadClass("net.minecraft.world.phys.AABB").getConstructor(double.class, double.class, double.class,
				double.class, double.class, double.class).newInstance(x0, y0, z0, x1, y1, z1);
	}

	private static ForgePartTrackingInjector injector(Path jar) {
		return new ForgePartTrackingInjector(readerOf(jar));
	}

	private static Function<String, byte[]> readerOf(Path jar) {
		return name -> {
			try {
				return NativeCoremodParityTest.read(jar, name);
			} catch (Exception unreadable) {
				return null;
			}
		};
	}

	private static boolean declaresPartEntities(Path jar, String level) throws Exception {
		return node(NativeCoremodParityTest.read(jar, level)).fields.stream().anyMatch(f -> f.name.equals("partEntities"));
	}

	/** A control's NullPointerException, raised in the game by {@code method} itself: the null parts array it was handed. */
	private static void assertThrewOnNullParts(InvocationTargetException thrown, String method) {
		Throwable npe = thrown.getCause();
		assertInstanceOf(NullPointerException.class, npe);
		StackTraceElement game = Arrays.stream(npe.getStackTrace()).filter(f -> f.getClassName().startsWith("net.minecraft.")).findFirst().orElseThrow();
		assertEquals(method, game.getMethodName(), "thrown by " + game + ": " + npe.getMessage());
	}

	/** NeoForge's multipart block, read null-tolerantly, with MinecraftForge's tracking called just ahead of it. */
	private static void assertBlock(MethodNode method, String owner, String loop) {
		assertEquals(List.of(NEO_GET_PARTS), partsRead(method));
		assertTolerant(method, NEO_GET_PARTS);
		List<AbstractInsnNode> code = real(method);
		int asks = -1;
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && call.name.equals("isMultipartEntity")) asks = i;
		}
		assertTrue(code.get(asks - 2) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner)
				&& call.name.equals(loop) && call.desc.equals(ForgePartTrackingInjector.TRACK_DESC), method.name + " calls " + loop);
		assertTrue(code.get(asks - 3) instanceof VarInsnNode entity && entity.var == 1);
		assertTrue(code.get(asks - 4) instanceof FieldInsnNode parts && parts.name.equals("partEntities"), "MinecraftForge's own map");
		assertTrue(code.get(asks - 1) instanceof VarInsnNode receiver && receiver.var == 1 && code.get(asks + 1).getOpcode() == Opcodes.IFEQ,
				"NeoForge's `if (entity.isMultipartEntity())` follows, unchanged");
	}

	private static void assertTolerant(MethodNode method, String getPartsDesc) {
		List<AbstractInsnNode> code = real(method);
		int read = indexOf(code, getPartsDesc);
		String part = getPartsDesc.substring(4, getPartsDesc.length() - 1);
		assertEquals(List.of(Opcodes.ICONST_0, Opcodes.ANEWARRAY, Opcodes.INVOKESTATIC, Opcodes.CHECKCAST),
				code.subList(read + 1, read + 5).stream().map(AbstractInsnNode::getOpcode).toList(),
				"Objects.requireNonNullElse(entity.getParts(), new PartEntity[0])");
		assertEquals(part, ((TypeInsnNode) code.get(read + 2)).desc);
		assertEquals("requireNonNullElse", ((MethodInsnNode) code.get(read + 3)).name);
		assertEquals("[L" + part + ";", ((TypeInsnNode) code.get(read + 4)).desc);
	}

	/** MinecraftForge's own loop over its getParts(): null-checked, and keyed by each part's id. */
	private static void assertLoop(MethodNode loop, String mapCall) {
		assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, loop.access);
		assertEquals(List.of(FORGE_GET_PARTS), partsRead(loop));
		assertTrue(calls(loop, "isMultipartEntity") && calls(loop, "getId") && calls(loop, mapCall));
		assertTrue(Arrays.stream(loop.instructions.toArray()).anyMatch(i -> i.getOpcode() == Opcodes.IFNULL), "a null getParts() is skipped");
	}

	/** A part of one family: that family's PartEntity, concrete so that it can be allocated. */
	private static byte[] part(String name, String partEntity) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, partEntity, null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A mod's multipart entity, compiled against one family: isMultipartEntity() and that family's getParts() only. */
	private static byte[] multipartEntity(String name, String getPartsDesc) {
		String partsDesc = getPartsDesc.substring(2);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, ENTITY, null);
		cw.visitField(Opcodes.ACC_PUBLIC, "parts", partsDesc, null, null).visitEnd();
		MethodVisitor multipart = cw.visitMethod(Opcodes.ACC_PUBLIC, "isMultipartEntity", "()Z", null, null);
		multipart.visitCode();
		multipart.visitInsn(Opcodes.ICONST_1);
		multipart.visitInsn(Opcodes.IRETURN);
		multipart.visitMaxs(0, 0);
		multipart.visitEnd();
		MethodVisitor parts = cw.visitMethod(Opcodes.ACC_PUBLIC, "getParts", getPartsDesc, null, null);
		parts.visitCode();
		parts.visitVarInsn(Opcodes.ALOAD, 0);
		parts.visitFieldInsn(Opcodes.GETFIELD, name, "parts", partsDesc);
		parts.visitInsn(Opcodes.ARETURN);
		parts.visitMaxs(0, 0);
		parts.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code superName}, with each of {@code methods(Entity)} a no-op. */
	private static byte[] quiet(String name, String superName, String... methods) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, null);
		for (String method : methods) {
			MethodVisitor quiet = cw.visitMethod(Opcodes.ACC_PUBLIC, method, TRACKING_DESC, null, null);
			quiet.visitCode();
			quiet.visitInsn(Opcodes.RETURN);
			quiet.visitMaxs(0, 0);
			quiet.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code level}, whose {@code getEntities()} answers a lookup that holds no whole entities. */
	private static byte[] bareLevel(String name, String level, int access) {
		String getter = "net/minecraft/world/level/entity/LevelEntityGetter";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, level, null);
		MethodVisitor entities = cw.visitMethod(access, "getEntities", "()L" + getter + ";", null, null);
		entities.visitCode();
		entities.visitTypeInsn(Opcodes.NEW, "fixture/NoEntities");
		entities.visitInsn(Opcodes.DUP);
		entities.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixture/NoEntities", "<init>", "()V", false);
		entities.visitInsn(Opcodes.ARETURN);
		entities.visitMaxs(0, 0);
		entities.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A LevelEntityGetter whose box lookup finds nothing; nothing else of it is called. */
	private static byte[] noEntities() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/NoEntities", null, "java/lang/Object",
				new String[] {"net/minecraft/world/level/entity/LevelEntityGetter"});
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V", null, null);
		get.visitCode();
		get.visitInsn(Opcodes.RETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] withoutInitializer(byte[] bytes) {
		ClassNode node = node(bytes);
		node.methods.removeIf(m -> m.name.equals("<clinit>"));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static List<String> partsRead(MethodNode method) {
		List<String> read = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ENTITY) && call.name.equals("getParts")) read.add(call.desc);
		}
		return read;
	}

	private static boolean calls(MethodNode method, String name) {
		return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals(name));
	}

	private static int indexOf(List<AbstractInsnNode> code, String getPartsDesc) {
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && call.name.equals("getParts") && call.desc.equals(getPartsDesc)) return i;
		}
		throw new AssertionError("no getParts" + getPartsDesc);
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}

	private static String dotted(String internalName) {
		return internalName.replace('/', '.');
	}

	private static Field field(Class<?> owner, String name) throws Exception {
		for (Class<?> at = owner; at != null; at = at.getSuperclass()) {
			try {
				Field f = at.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException notHere) {
				// declared further up
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (sun.misc.Unsafe) f.get(null);
	}

	private static Path newestUnder(String pattern) throws java.io.IOException {
		Path root = TestFixtures.minecraftDir().resolve("libraries");
		Path under = root.resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources") && !f.toString().contains("natives"))
					.sorted(Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
