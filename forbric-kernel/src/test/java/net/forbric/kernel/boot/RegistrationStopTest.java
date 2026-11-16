/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.kernel.ExpectCaught;

/**
 * What {@link RegistrationStop} stops the boot with when NeoForge's {@code ModLoader.waitForTask} answers: its
 * {@code ModLoadingException} is the stop, and anything else it throws (an accident of the loader's own, such as the NPE it
 * hits when it has no mod list to empty) is not — the boot then stops on what NeoForge's registration threw. The two
 * NeoForge classes are generated stand-ins, so the answer {@code waitForTask} gives is the only thing that varies;
 * {@code RegistrationStopGenuineCarrierTest} runs NeoForge's real {@code ModLoader}.
 */
class RegistrationStopTest {
	@AfterEach
	void forget() throws Exception {
		Field stopping = RegistrationStop.class.getDeclaredField("stopping");
		stopping.setAccessible(true);
		stopping.set(null, null);
	}

	@Test
	@ExpectCaught("instead of its mod-loading error")
	void whatNeoForgesModLoaderThrowsInsteadOfItsErrorIsNotTheStop() throws Exception {
		ClassLoader cl = neoForge(Type.getInternalName(NullPointerException.class));
		IllegalStateException failure = new IllegalStateException("a mod's RegisterEvent listener failed");

		Throwable stop = RegistrationStop.stop(cl, failure);

		assertSame(failure, stop, "the boot stops on what NeoForge's registration threw, not on ModLoader's own NPE: " + stop);
		assertTrue(RegistrationStop.isStop(failure) && RegistrationStop.stopped());
	}

	@Test
	void neoForgesModLoadingErrorIsTheStop() throws Exception {
		ClassLoader cl = neoForge("net/neoforged/fml/ModLoadingException");
		IllegalStateException failure = new IllegalStateException("a mod's RegisterEvent listener failed");

		Throwable stop = RegistrationStop.stop(cl, failure);

		assertSame(Class.forName(RegistrationStop.MOD_LOADING_EXCEPTION, false, cl), stop.getClass(), String.valueOf(stop));
		assertTrue(RegistrationStop.isStop(stop));
	}

	/**
	 * The kernel's line for a Fabric stage's stop names the stage and the mod without repeating Fabric Loader's own message,
	 * which the game's Main prints once with its trace, as natively (lane STOP review B-6: the line printed "%s" literally and
	 * the trace a second time).
	 */
	@Test
	void theFabricStopLineNamesTheStageAndTheModWithoutRepeatingFabricLoadersMessage() {
		RuntimeException stage = new RuntimeException("Could not execute entrypoint stage 'main' due to errors, provided by "
				+ "'forbricbrokencanary' at 'forbric.broken.Main'!", new IllegalStateException("boom"));

		String line = RegistrationStop.describeFabric(stage);

		assertEquals("the Fabric entrypoint stage 'main' failed in mod 'forbricbrokencanary'", line);
		assertTrue(!line.contains("Could not execute entrypoint stage"), line);
		assertTrue(RegistrationStop.describeFabric(new IllegalStateException("x")).contains("java.lang.IllegalStateException"));
		assertSame(stage, RegistrationStop.fabric(stage));
		assertTrue(RegistrationStop.isStop(stage));
	}

	/**
	 * {@code net.neoforged.fml.ModLoadingException} and a {@code net.neoforged.fml.ModLoader} whose {@code modList} is set
	 * (so nothing is bound) and whose {@code waitForTask} throws a new {@code thrown} (an internal name).
	 */
	private static ClassLoader neoForge(String thrown) {
		ClassWriter exception = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		exception.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/neoforged/fml/ModLoadingException", null,
				"java/lang/RuntimeException", null);
		MethodVisitor init = exception.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		exception.visitEnd();

		ClassWriter loader = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		loader.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/neoforged/fml/ModLoader", null, "java/lang/Object", null);
		loader.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "modList", "Ljava/lang/Object;", null, null).visitEnd();
		MethodVisitor clinit = loader.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, "net/neoforged/fml/ModLoader", "modList", "Ljava/lang/Object;");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		MethodVisitor wait = loader.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "waitForTask",
				"(Ljava/lang/String;Ljava/lang/Runnable;Ljava/util/concurrent/CompletableFuture;)V", null, null);
		wait.visitCode();
		wait.visitTypeInsn(Opcodes.NEW, thrown);
		wait.visitInsn(Opcodes.DUP);
		wait.visitMethodInsn(Opcodes.INVOKESPECIAL, thrown, "<init>", "()V", false);
		wait.visitInsn(Opcodes.ATHROW);
		wait.visitMaxs(0, 0);
		wait.visitEnd();
		loader.visitEnd();

		Map<String, byte[]> classes = Map.of("net.neoforged.fml.ModLoadingException", exception.toByteArray(),
				"net.neoforged.fml.ModLoader", loader.toByteArray());
		return new ClassLoader(RegistrationStopTest.class.getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				synchronized (getClassLoadingLock(name)) {
					byte[] bytes = classes.get(name);
					if (bytes == null) return super.loadClass(name, resolve);
					Class<?> c = findLoadedClass(name);
					return c != null ? c : defineClass(name, bytes, 0, bytes.length);
				}
			}
		};
	}
}
