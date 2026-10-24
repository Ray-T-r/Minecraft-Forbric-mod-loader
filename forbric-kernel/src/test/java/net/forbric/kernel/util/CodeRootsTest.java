/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * {@link CodeRoots#of} for the classes a code-source chain breaks on: each answers null, or its definer's jar, and none
 * is reported as a failure (CaughtErrorsExtension fails any test here during which the kernel catches one).
 */
class CodeRootsTest {
	@TempDir Path directory;

	@Test void aDynamicProxyCameFromNoJar() {
		// Proxy classes are defined with no code source: getCodeSource() is null, which is where gate-m39's NPE came from.
		Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Runnable.class }, (p, m, a) -> null);
		assertNull(proxy.getClass().getProtectionDomain().getCodeSource(), "the shape this test is about");
		assertNull(CodeRoots.of(proxy.getClass()));
	}

	@Test void aJdkClassCameFromNoJar() {
		assertNull(CodeRoots.of(String.class));
		assertNull(CodeRoots.of(int.class));
	}

	@Test void aClassFromAJarAnswersWithThatJar() throws Exception {
		Path jar = jar("owner.jar", "p/Anchor");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null)) {
			assertEquals(jar.toRealPath(), CodeRoots.of(loader.loadClass("p.Anchor")).toRealPath());
		}
	}

	@Test void aHiddenClassAnswersWithTheJarOfTheClassThatDefinedIt() throws Exception {
		// A lambda is a hidden class: Class.forName cannot find it by name, and it is defined in its lookup class's
		// protection domain -- so its jar is its definer's, the mod whose code it is.
		Path jar = directory.resolve("lambda owner.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new JarEntry("p/Anchor.class"));
			out.write(lambdaFactory("p/Anchor"));
			out.closeEntry();
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null)) {
			Class<?> hidden = loader.loadClass("p.Anchor").getMethod("make").invoke(null).getClass();
			assertTrue(hidden.isHidden(), "the shape this test is about");
			assertEquals(jar.toRealPath(), CodeRoots.of(hidden).toRealPath());
		}
	}

	private Path jar(String name, String internalName) throws Exception {
		Path jar = directory.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new JarEntry(internalName + ".class"));
			out.write(type(internalName));
			out.closeEntry();
		}
		return jar;
	}

	/** {@code public class <name> { public static Runnable make() { return () -> {}; } }}: a jar class that makes a real lambda. */
	static byte[] lambdaFactory(String internalName) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
		var make = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Ljava/lang/Runnable;", null, null);
		make.visitCode();
		make.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
								+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
				org.objectweb.asm.Type.getType("()V"),
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, internalName, "lambda$make$0", "()V", false),
				org.objectweb.asm.Type.getType("()V"));
		make.visitInsn(Opcodes.ARETURN);
		make.visitMaxs(0, 0);
		make.visitEnd();
		var body = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$make$0", "()V", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	static byte[] type(String internalName) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}
}
