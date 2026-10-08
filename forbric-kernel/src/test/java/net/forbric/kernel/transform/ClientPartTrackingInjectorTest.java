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
 * The client tracks a multipart entity's NeoForge parts again: on the real merged callbacks, and in a real JVM, where the
 * repaired callbacks are linked — and so verified — against the merged game and both carriers, then handed a NeoForge
 * mod's multipart entity, a MinecraftForge mod's and the Ender Dragon. Class initializers that need a bootstrapped game or
 * a running FML are left out (Entity and the dragon's chain down to it, Level, ClientLevel, NeoForge's AttachmentHolder),
 * and the level and entities are allocated bare: {@code onTrackingStart} reads none of what they would have set.
 */
@ResourceLock("system-properties")
class ClientPartTrackingInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_CARRIER = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final String CALLBACKS = ClientPartTrackingInjector.CALLBACKS_INTERNAL;
	private static final String LEVEL = ClientPartTrackingInjector.LEVEL;
	private static final String ENTITY = ClientPartTrackingInjector.ENTITY;
	private static final String DRAGON = ClientPartTrackingInjector.DRAGON;
	private static final String DRAGON_PART = DragonPartsInjector.PART_INTERNAL;
	private static final String START_DESC = ClientPartTrackingInjector.TRACKING_START_DESC;
	private static final String NEO_GET_PARTS = ClientPartTrackingInjector.NEO_GET_PARTS;
	private static final String FORGE_GET_PARTS = ClientPartTrackingInjector.FORGE_GET_PARTS;

	@AfterEach void reset() { System.clearProperty(ClientPartTrackingInjector.PROPERTY); }

    @Test void theMergedCallbackAddsNeoForgesPartsAndSkipsMinecraftForgesWhenNull() throws Exception {
        byte[] merged=NativeCoremodParityTest.read(MERGED,CALLBACKS);
        assertEquals(List.of(NEO_GET_PARTS),partsRead(method(node(merged),"onTrackingStart",START_DESC)),"the current merge retains NeoForge's dependency-coherent body");
        assertSame(merged,new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS,merged,null));
        byte[] repaired=forgeRepair().transform(ClientPartTrackingInjector.CALLBACKS,merged,null);
        ClassNode node=node(repaired);MethodNode start=method(node,"onTrackingStart",START_DESC);
        assertEquals(List.of(NEO_GET_PARTS),partsRead(start),"the canonical read order remains intact");
        assertTrue(real(start).stream().anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals(ForgePartTrackingInjector.TRACK)));
        assertTrue(real(method(node,ForgePartTrackingInjector.TRACK,ForgePartTrackingInjector.TRACK_DESC)).stream().anyMatch(i->i instanceof MethodInsnNode call&&call.desc.equals(FORGE_GET_PARTS)));
        new Analyzer<>(new BasicVerifier()).analyze(CALLBACKS,start);
        assertSame(repaired,forgeRepair().transform(ClientPartTrackingInjector.CALLBACKS,repaired,null));
    }
    private static ForgePartTrackingInjector forgeRepair(){return new ForgePartTrackingInjector(owner->{
        try(java.util.zip.ZipFile jar=new java.util.zip.ZipFile(MERGED.toFile())){var entry=jar.getEntry(owner+".class");if(entry==null)return null;try(var input=jar.getInputStream(entry)){return input.readAllBytes();}}
        catch(Exception unavailable){return null;}
    });}

	@Test void neoForgesOwnCallbacksAreLeftAlone() throws Exception {
		byte[] own = NativeCoremodParityTest.read(NEO, CALLBACKS);
		assertSame(own, new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS, own, null));
	}

	@Test void theSwitchLeavesTheMergedCallbacksAlone() throws Exception {
		System.setProperty(ClientPartTrackingInjector.PROPERTY, "off");
		byte[] merged = NativeCoremodParityTest.read(MERGED, CALLBACKS);
		assertSame(merged, new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS, merged, null));
	}

	@Test void aNeoForgeModsMultipartEntityIsTrackedInsteadOfDisconnectingTheClient() throws Exception {
        try(Game merged=new Game(false)){
            Object entity=merged.multipart("fixture.NeoMultipartEntity","fixture.NeoPart",2);merged.startTracking(entity);
            assertEquals(List.of(1,2),merged.ids(merged.dragonParts()),"the actual Neo canonical body already tracks its own view");
        }
		try (Game repaired = new Game(true)) {
			Object entity = repaired.multipart("fixture.NeoMultipartEntity", "fixture.NeoPart", 2);
			repaired.startTracking(entity);
			List<?> tracked = repaired.dragonParts();
			assertEquals(List.of(1, 2), repaired.ids(tracked), "tracked where NeoForge tracks them");
			assertSame(repaired.parts(entity)[0], tracked.get(0));
			assertTrue(repaired.partEntities().isEmpty());
		}
	}

	@Test void aMinecraftForgeModsMultipartEntityKeepsItsTracking() throws Exception {
		try (Game repaired = new Game(true)) {
			Object entity = repaired.multipart("fixture.ForgeMultipartEntity", "fixture.ForgePart", 2);
			repaired.startTracking(entity);
			Map<?, ?> tracked = repaired.partEntities();
			assertEquals(Set.of(1, 2), new HashSet<>(tracked.keySet()), "MinecraftForge's own loop, as before");
			assertSame(repaired.parts(entity)[1], tracked.get(2));
			assertTrue(repaired.dragonParts().isEmpty(), "NeoForge's getParts() answers null for it, and is skipped");
		}
	}

	@Test void theDragonsPartsAreTrackedOnce() throws Exception {
		try (Game repaired = new Game(true)) {
			Object dragon = repaired.dragon(2);
			repaired.startTracking(dragon);
			assertEquals(List.of(1, 2), repaired.ids(repaired.dragonParts()), "by the dragon's own case, and only by it");
			assertEquals(Set.of(1, 2), new HashSet<>(repaired.partEntities().keySet()), "both native APIs keep the actual dragon parts");
		}
	}

	/** The merged game, both carriers and the libraries they link against; the callbacks merged or repaired. */
	private static final class Game implements AutoCloseable {
		private final URLClassLoader loader;
		private final Object level;
		private final Object callbacks;
		private final Method start;

		Game(boolean repaired) throws Exception {
			TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the merged game is class-file 69, which only Java 25 links");
			for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), jar + " absent");
			Map<String, byte[]> defined = new HashMap<>();
			byte[] callbacks = NativeCoremodParityTest.read(MERGED, CALLBACKS);
			if(repaired){callbacks=new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS,callbacks,null);callbacks=forgeRepair().transform(ClientPartTrackingInjector.CALLBACKS,callbacks,null);}
            defined.put(dotted(CALLBACKS),callbacks);
			// The coherent hierarchy keeps both native getParts APIs and their actual parts.
			defined.put(dotted(DRAGON_PART), NativeCoremodParityTest.read(MERGED, DRAGON_PART));
			defined.put(dotted(DRAGON), withoutInitializer(NativeCoremodParityTest.read(MERGED, DRAGON)));
			for (String name : List.of(ENTITY, "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob",
					"net/minecraft/world/level/Level", LEVEL)) {
				defined.put(dotted(name), withoutInitializer(NativeCoremodParityTest.read(MERGED, name)));
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
					"org/apache/commons/commons-lang3", "com/mojang/logging")) {
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
			loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
				@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = defined.get(name);
					return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.findClass(name);
				}
			};

			Class<?> levelClass = type(LEVEL);
			level = unsafe().allocateInstance(levelClass);
			field(levelClass, "players").set(level, new ArrayList<>());
			field(levelClass, "dragonParts").set(level, new ArrayList<>());
			field(levelClass, "partEntities").set(level, type("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap").getConstructor().newInstance());
			Class<?> callbacksClass = type(CALLBACKS);
			this.callbacks = unsafe().allocateInstance(callbacksClass);
			field(callbacksClass, "this$0").set(this.callbacks, level);
			start = callbacksClass.getDeclaredMethod("onTrackingStart", type(ENTITY));
			start.setAccessible(true);
		}

		void startTracking(Object entity) throws Exception {
			start.invoke(callbacks, entity);
		}

		/** A mod's multipart entity whose own family's {@code getParts()} answers {@code count} parts, ids 1 and up. */
		Object multipart(String entityName, String partName, int count) throws Exception {
			Class<?> partClass = loader.loadClass(partName);
			Object parts = Array.newInstance(partClass.getSuperclass(), count);
			for (int i = 0; i < count; i++) Array.set(parts, i, entityWithId(partClass, i + 1));
			Class<?> entityClass = loader.loadClass(entityName);
			Object entity = unsafe().allocateInstance(entityClass);
			field(entityClass, "parts").set(entity, parts);
			return entity;
		}

		/** An Ender Dragon with {@code count} parts in its subEntities. */
		Object dragon(int count) throws Exception {
			Class<?> partClass = type(DRAGON_PART);
			Object parts = Array.newInstance(partClass, count);
			for (int i = 0; i < count; i++) Array.set(parts, i, entityWithId(partClass, i + 1));
			Class<?> dragonClass = type(DRAGON);
			Object dragon = unsafe().allocateInstance(dragonClass);
			field(dragonClass, "subEntities").set(dragon, parts);
			return dragon;
		}

		Object[] parts(Object entity) throws Exception {
			Class<?> owner = entity.getClass();
			return (Object[]) field(owner, owner.getName().equals(dotted(DRAGON)) ? "subEntities" : "parts").get(entity);
		}

		List<?> dragonParts() throws Exception { return (List<?>) field(type(LEVEL), "dragonParts").get(level); }

		/** The parts by id: a bare entity cannot print itself, so an assertion message names them this way. */
		List<Integer> ids(List<?> entities) throws Exception {
			List<Integer> ids = new ArrayList<>();
			for (Object entity : entities) ids.add(field(type(ENTITY), "id").getInt(entity));
			return ids;
		}

		Map<?, ?> partEntities() throws Exception { return (Map<?, ?>) field(type(LEVEL), "partEntities").get(level); }

		private Object entityWithId(Class<?> type, int id) throws Exception {
			Object entity = unsafe().allocateInstance(type);
			field(type(ENTITY), "id").setInt(entity, id);
			return entity;
		}

		private Class<?> type(String internalName) throws ClassNotFoundException {
			return Class.forName(dotted(internalName), false, loader);
		}

		@Override public void close() throws Exception { loader.close(); }
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
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
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
