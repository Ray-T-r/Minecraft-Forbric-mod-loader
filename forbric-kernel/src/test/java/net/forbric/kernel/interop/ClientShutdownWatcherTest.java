/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A night-config watcher that takes longer than the exit sweep waits for its stop is still stopped, and the slow
 * confirmation is not a failure: the exit guard keeps sweeping, and a thread that never ends is what it reports.
 */
class ClientShutdownWatcherTest implements Opcodes {
	private static final String WATCHER = "com/electronwill/nightconfig/core/file/FileWatcher";

	@Test
	void aWatcherThatIsSlowToConfirmItsStopIsStoppedWithoutAFailure() {
		List<String> stopped = ClientShutdown.sweep(new WatcherLoader());
		assertEquals(1, stopped.size(), stopped::toString);
		assertTrue(stopped.getFirst().startsWith("night-config default instance"), stopped::toString);
	}

	/** night-config's FileWatcher, reduced to what the sweep reads: its default instance and a stop that never confirms. */
	private static byte[] slowWatcher() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(V17, ACC_PUBLIC | ACC_SUPER, WATCHER, null, "java/lang/Object", null);
		cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DEFAULT_INSTANCE", "L" + WATCHER + ";", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(ALOAD, 0);
		init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(NEW, WATCHER);
		clinit.visitInsn(DUP);
		clinit.visitMethodInsn(INVOKESPECIAL, WATCHER, "<init>", "()V", false);
		clinit.visitFieldInsn(PUTSTATIC, WATCHER, "DEFAULT_INSTANCE", "L" + WATCHER + ";");
		clinit.visitInsn(RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		MethodVisitor stop = cw.visitMethod(ACC_PUBLIC, "stopFuture", "()Ljava/util/concurrent/CompletableFuture;", null, null);
		stop.visitCode();
		stop.visitTypeInsn(NEW, "java/util/concurrent/CompletableFuture");
		stop.visitInsn(DUP);
		stop.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/CompletableFuture", "<init>", "()V", false);
		stop.visitInsn(ARETURN);
		stop.visitMaxs(0, 0);
		stop.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Defines the watcher itself, whatever the test class path holds under that name. */
	private static final class WatcherLoader extends ClassLoader {
		WatcherLoader() {
			super(ClientShutdownWatcherTest.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(WATCHER.replace('/', '.'))) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> done = findLoadedClass(name);
				if (done == null) {
					byte[] bytes = slowWatcher();
					done = defineClass(name, bytes, 0, bytes.length);
				}
				return done;
			}
		}
	}
}
