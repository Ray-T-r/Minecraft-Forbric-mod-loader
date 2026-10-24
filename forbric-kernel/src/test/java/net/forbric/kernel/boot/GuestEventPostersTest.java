/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which events a loaded guest posts itself, read from its bytecode and from the hook classes the game serves.
 *
 * <p>Tombstone's phantom spawner calls {@code ForgeEventFactory.onPlayerSpawnPhantom}, so in a pack with it the event the
 * merged game never posts is posted after all. The dead-event audit asks this before it names anyone; these pin what
 * counts as posting: a call to (or a method reference to) a hook that constructs the event, directly or through another
 * hook of the hook classes, or constructing the event; nested jars count with their parent; mentioning the event in a
 * listener's signature does not.
 */
class GuestEventPostersTest {
	private static final String FACTORY = "net/minecraftforge/event/ForgeEventFactory";
	private static final String HOOKS = "net/minecraftforge/common/ForgeHooks";
	private static final String PHANTOMS = "net/minecraftforge/event/entity/player/PlayerSpawnPhantomsEvent";
	private static final String TAME = "net/minecraftforge/event/entity/living/AnimalTameEvent";
	@TempDir Path temporary;

	/** The two hook classes: ForgeEventFactory.onPhantoms constructs PHANTOMS, onTame constructs TAME; ForgeHooks.outer calls onTame. */
	private static Map<String, byte[]> hookClasses() {
		ClassWriter factory = new ClassWriter(0);
		factory.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FACTORY, null, "java/lang/Object", null);
		constructs(factory, "onPhantoms", PHANTOMS);
		constructs(factory, "onTame", TAME);
		factory.visitEnd();
		ClassWriter hooks = new ClassWriter(0);
		hooks.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		MethodVisitor mv = hooks.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "outer", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onTame", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		hooks.visitEnd();
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put(FACTORY, factory.toByteArray());
		out.put(HOOKS, hooks.toByteArray());
		return out;
	}

	private static void constructs(ClassWriter cw, String name, String event) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, event);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
	}

	/** A guest class whose one method makes {@code body}'s instruction. */
	private static byte[] guest(String name, java.util.function.Consumer<MethodVisitor> body) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		mv.visitCode();
		body.accept(mv);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(2, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A listener for PHANTOMS: names the event in its signature and posts nothing. */
	private static byte[] listener(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "(L" + PHANTOMS + ";)V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] jarBytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private Path jar(String name, Map<String, byte[]> entries) throws Exception {
		Path jar = temporary.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar)) {
			out.write(jarBytes(entries));
		}
		return jar;
	}

	private static Map<String, Set<String>> find(Set<String> events, List<Path> jars) {
		Map<String, byte[]> hooks = hookClasses();
		return GuestEventPosters.find(events, hooks::get, jars);
	}

	@Test void aGuestCallingTheHookThatConstructsTheEventPostsIt() throws Exception {
		Path spawner = jar("tomb.jar", Map.of("tomb/Spawner.class", guest("tomb/Spawner",
				mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onPhantoms", "()V", false))));
		Path waiting = jar("night.jar", Map.of("night/Listener.class", listener("night/Listener")));
		assertEquals(Map.of(PHANTOMS, Set.of("tomb.jar")), find(Set.of(PHANTOMS), List.of(spawner, waiting)));
	}

	@Test void listeningIsNotPosting() throws Exception {
		// The listener names the event in its signature; a reference is not a post, and the audit must still accuse it.
		Path waiting = jar("night.jar", Map.of("night/Listener.class", listener("night/Listener")));
		assertEquals(Map.of(), find(Set.of(PHANTOMS), List.of(waiting)));
	}

	@Test void aHookThatReachesThePosterThroughAnotherHookPostsItToo() throws Exception {
		Path tamer = jar("pets.jar", Map.of("pets/Tamer.class", guest("pets/Tamer",
				mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "outer", "()V", false))));
		assertEquals(Map.of(TAME, Set.of("pets.jar")), find(Set.of(TAME, PHANTOMS), List.of(tamer)));
	}

	@Test void constructingTheEventPostsIt() throws Exception {
		Path direct = jar("direct.jar", Map.of("d/Post.class", guest("d/Post", mv -> {
			mv.visitTypeInsn(Opcodes.NEW, PHANTOMS);
			mv.visitInsn(Opcodes.POP);
		})));
		assertEquals(Map.of(PHANTOMS, Set.of("direct.jar")), find(Set.of(PHANTOMS), List.of(direct)));
	}

	@Test void aMethodReferenceToThePosterPostsIt() throws Exception {
		Path ref = jar("ref.jar", Map.of("r/Ref.class", guest("r/Ref", mv -> {
			org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
					"metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
							+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
					false);
			mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", metafactory, org.objectweb.asm.Type.getType("()V"),
					new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, FACTORY, "onPhantoms", "()V", false), org.objectweb.asm.Type.getType("()V"));
			mv.visitInsn(Opcodes.POP);
		})));
		assertEquals(Map.of(PHANTOMS, Set.of("ref.jar")), find(Set.of(PHANTOMS), List.of(ref)));
	}

	@Test void aNestedJarPostsWithItsParentNamed() throws Exception {
		byte[] inner = jarBytes(Map.of("lib/Spawner.class", guest("lib/Spawner",
				mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onPhantoms", "()V", false))));
		Path outer = jar("outer.jar", Map.of("META-INF/jarjar/lib.jar", inner));
		assertEquals(Map.of(PHANTOMS, Set.of("outer.jar!/META-INF/jarjar/lib.jar")), find(Set.of(PHANTOMS), List.of(outer)));
	}

	@Test void withoutTheHookClassesOnlyConstructingCounts() throws Exception {
		// A class path that serves no hook class names no poster; a call is then nothing this can read as a post.
		Path spawner = jar("tomb.jar", Map.of("tomb/Spawner.class", guest("tomb/Spawner",
				mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onPhantoms", "()V", false))));
		assertEquals(Map.of(), GuestEventPosters.find(Set.of(PHANTOMS), name -> null, List.of(spawner)));
	}
}
