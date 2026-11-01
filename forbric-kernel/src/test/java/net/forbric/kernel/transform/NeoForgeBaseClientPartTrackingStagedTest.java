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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NeoForge's own client tracks a NeoForge mod's multipart entity, with nothing of the kernel's in between.
 *
 * <p>The client used to disconnect on sight of such an entity: the merged base's {@code onTrackingStart} was once
 * MinecraftForge's body, which read only MinecraftForge's {@code getParts()} (null for every NeoForge mod's entity) and
 * dereferenced it. {@code ClientPartTrackingInjector} repaired that body until Stage 4 deleted it. On NeoForge's base the
 * body is NeoForge's: the vanilla dragon case, then {@code isMultipartEntity()}, NeoForge's {@code getParts()}, and the
 * parts added to {@code ClientLevel.dragonParts}. This links the base's own, unmodified callbacks against the base and the
 * NeoForge carrier in a real JVM and hands them a NeoForge mod's multipart entity and the Ender Dragon.
 *
 * <p>Class initializers that need a bootstrapped game or a running FML are left out (Entity and the dragon's chain down to
 * it, Level, ClientLevel, NeoForge's AttachmentHolder), and the level and entities are allocated bare:
 * {@code onTrackingStart} reads none of what they would have set.
 */
class NeoForgeBaseClientPartTrackingStagedTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path BASE = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_CARRIER = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final String CALLBACKS = "net/minecraft/client/multiplayer/ClientLevel$EntityCallbacks";
	private static final String LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final String DRAGON = "net/minecraft/world/entity/boss/enderdragon/EnderDragon";
	private static final String DRAGON_PART = "net/minecraft/world/entity/boss/enderdragon/EnderDragonPart";
	private static final String NEO_PART = "net/neoforged/neoforge/entity/PartEntity";
	private static final String NEO_GET_PARTS = "()[L" + NEO_PART + ";";
	private static final String START_DESC = "(L" + ENTITY + ";)V";

	/** What the body reads is NeoForge's part API alone: there is no other family's getParts() left to dereference. */
	@Test void theBasesCallbackReadsOnlyNeoForgesParts() throws Exception {
		ClassNode node = node(NativeCoremodParityTest.read(BASE, CALLBACKS));
		MethodNode start = node.methods.stream().filter(m -> m.name.equals("onTrackingStart") && m.desc.equals(START_DESC))
				.findFirst().orElseThrow();
		List<String> reads = new ArrayList<>();
		for (AbstractInsnNode insn : start.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ENTITY) && call.name.equals("getParts")) reads.add(call.desc);
		}
		assertEquals(List.of(NEO_GET_PARTS), reads, "onTrackingStart reads NeoForge's getParts() once, and nothing else's");
	}

	@Test void aNeoForgeModsMultipartEntityIsTrackedByTheBaseItself() throws Exception {
		try (Game game = new Game()) {
			Object entity = game.multipart(2);
			game.startTracking(entity);
			List<?> tracked = game.dragonParts();
			assertEquals(List.of(1, 2), game.ids(tracked), "tracked where NeoForge tracks them");
			assertSame(game.parts(entity)[0], tracked.get(0));
		}
	}

	@Test void theDragonsPartsAreTrackedByItsOwnCase() throws Exception {
		try (Game game = new Game()) {
			game.startTracking(game.dragon(2));
			assertEquals(List.of(1, 2), game.ids(game.dragonParts()), "by the dragon's own case, once");
		}
	}

	/**
	 * Control: the harness sees the disconnect when it happens. An entity that says it is multipart and answers null parts
	 * makes the callback throw out of onTrackingStart, which is what a body reading the wrong family's null array did to
	 * every NeoForge mod's entity on the merged base (for this broken entity it is native NeoForge behaviour).
	 */
	@Test void controlANullPartArrayThrowsOutOfTheCallback() throws Exception {
		try (Game game = new Game()) {
			Object entity = game.multipart(-1);
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> game.startTracking(entity));
			assertInstanceOf(NullPointerException.class, thrown.getCause());
		}
	}

	/** The base, the NeoForge carrier and the libraries they link against; the callbacks exactly as the base has them. */
	private static final class Game implements AutoCloseable {
		private final URLClassLoader loader;
		private final Object level;
		private final Object callbacks;
		private final Method start;

		Game() throws Exception {
			TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the base is class-file 69, which only Java 25 links");
			for (Path jar : List.of(BASE, NEO_CARRIER)) TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			Map<String, byte[]> defined = new HashMap<>();
			defined.put(dotted(CALLBACKS), NativeCoremodParityTest.read(BASE, CALLBACKS));
			defined.put(dotted(DRAGON_PART), NativeCoremodParityTest.read(BASE, DRAGON_PART));
			defined.put(dotted(DRAGON), withoutInitializer(NativeCoremodParityTest.read(BASE, DRAGON)));
			for (String name : List.of(ENTITY, "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob",
					"net/minecraft/world/level/Level", LEVEL)) {
				defined.put(dotted(name), withoutInitializer(NativeCoremodParityTest.read(BASE, name)));
			}
			// Entity's and Level's superclass asks FML whether this is a production run.
			String attachments = "net/neoforged/neoforge/attachment/AttachmentHolder";
			defined.put(dotted(attachments), withoutInitializer(NativeCoremodParityTest.read(NEO_CARRIER, attachments)));
			defined.put("fixture.NeoPart", part("fixture/NeoPart"));
			defined.put("fixture.NeoMultipartEntity", multipartEntity("fixture/NeoMultipartEntity"));

			List<URL> urls = new ArrayList<>(List.of(BASE.toUri().toURL(), NEO_CARRIER.toUri().toURL()));
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
			Class<?> callbacksClass = type(CALLBACKS);
			this.callbacks = unsafe().allocateInstance(callbacksClass);
			field(callbacksClass, "this$0").set(this.callbacks, level);
			start = callbacksClass.getDeclaredMethod("onTrackingStart", type(ENTITY));
			start.setAccessible(true);
		}

		void startTracking(Object entity) throws Exception {
			start.invoke(callbacks, entity);
		}

		/** A NeoForge mod's multipart entity whose getParts() answers {@code count} parts, ids 1 and up; null for a negative count. */
		Object multipart(int count) throws Exception {
			Class<?> partClass = loader.loadClass("fixture.NeoPart");
			Object parts = null;
			if (count >= 0) {
				parts = Array.newInstance(partClass.getSuperclass(), count);
				for (int i = 0; i < count; i++) Array.set(parts, i, entityWithId(partClass, i + 1));
			}
			Class<?> entityClass = loader.loadClass("fixture.NeoMultipartEntity");
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
			return (Object[]) field(entity.getClass(), "parts").get(entity);
		}

		List<?> dragonParts() throws Exception { return (List<?>) field(type(LEVEL), "dragonParts").get(level); }

		/** The parts by id: a bare entity cannot print itself, so an assertion message names them this way. */
		List<Integer> ids(List<?> entities) throws Exception {
			List<Integer> ids = new ArrayList<>();
			for (Object entity : entities) ids.add(field(type(ENTITY), "id").getInt(entity));
			return ids;
		}

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

	/** A NeoForge part, concrete so that it can be allocated. */
	private static byte[] part(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, NEO_PART, null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A NeoForge mod's multipart entity: isMultipartEntity() and NeoForge's getParts() over a field. */
	private static byte[] multipartEntity(String name) {
		String partsDesc = NEO_GET_PARTS.substring(2);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, ENTITY, null);
		cw.visitField(Opcodes.ACC_PUBLIC, "parts", partsDesc, null, null).visitEnd();
		MethodVisitor multipart = cw.visitMethod(Opcodes.ACC_PUBLIC, "isMultipartEntity", "()Z", null, null);
		multipart.visitCode();
		multipart.visitInsn(Opcodes.ICONST_1);
		multipart.visitInsn(Opcodes.IRETURN);
		multipart.visitMaxs(0, 0);
		multipart.visitEnd();
		MethodVisitor parts = cw.visitMethod(Opcodes.ACC_PUBLIC, "getParts", NEO_GET_PARTS, null, null);
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

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
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
