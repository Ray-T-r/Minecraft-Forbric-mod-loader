/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Libraries that ask Fabric Loader for a custom entrypoint key the scan cannot follow to a constant — the key is held
 * somewhere it can see, but reaches the query through something it does not evaluate. Each one gets there its own way;
 * none is shaped like a real library, and none like the fixtures in {@link DispatchFixtures}.
 */
public final class UndeterminedKeyFixtures {
	private static final String LOADER = DispatchFixtures.FABRIC_LOADER;

	private UndeterminedKeyFixtures() {
	}

	/**
	 * A Kotlin {@code object} holding the key as a non-{@code const} {@code val}: a private {@code static final}
	 * field assigned in {@code <clinit>}, read through an instance getter on {@code INSTANCE}.
	 */
	public static byte[] kotlinObjectKeys(String name, String getter, String key) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, "java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "INSTANCE", "L" + name + ";", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "value", "Ljava/lang/String;", null, null).visitEnd();
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, name);
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "INSTANCE", "L" + name + ";");
		clinit.visitLdcInsn(key);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "value", "Ljava/lang/String;");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		MethodVisitor get = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, getter, "()Ljava/lang/String;", null, null);
		get.visitCode();
		get.visitFieldInsn(Opcodes.GETSTATIC, name, "value", "Ljava/lang/String;");
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A {@code main} entrypoint asking {@code getEntrypoints} for the key the object's getter returns. */
	public static byte[] getterKeyedMain(String name, String keys, String getter, String hookType) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		constructor(writer, null, null);
		MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
		main.visitCode();
		main.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "getInstance", "()L" + LOADER + ";", true);
		main.visitFieldInsn(Opcodes.GETSTATIC, keys, "INSTANCE", "L" + keys + ";");
		main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, keys, getter, "()Ljava/lang/String;", false);
		main.visitLdcInsn(Type.getObjectType(hookType));
		main.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOADER, "getEntrypoints", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", true);
		main.visitInsn(Opcodes.POP);
		main.visitInsn(Opcodes.RETURN);
		main.visitMaxs(0, 0);
		main.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * A {@code client} entrypoint whose constructor keeps the key in an instance field; its phase method hands that
	 * field and the type to a static helper, which is what queries ({@link #staticFanout}).
	 */
	public static byte[] fieldKeyedClient(String name, String key, String fanout, String hookType) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ClientModInitializer"});
		writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "channel", "Ljava/lang/String;", null, null).visitEnd();
		constructor(writer, name, key);
		MethodVisitor client = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitializeClient", "()V", null, null);
		client.visitCode();
		client.visitVarInsn(Opcodes.ALOAD, 0);
		client.visitFieldInsn(Opcodes.GETFIELD, name, "channel", "Ljava/lang/String;");
		client.visitLdcInsn(Type.getObjectType(hookType));
		client.visitMethodInsn(Opcodes.INVOKESTATIC, fanout, "each", "(Ljava/lang/String;Ljava/lang/Class;)V", false);
		client.visitInsn(Opcodes.RETURN);
		client.visitMaxs(0, 0);
		client.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** {@code static void each(String key, Class<?> type)}: queries {@code getEntrypointContainers} with its parameters. */
	public static byte[] staticFanout(String name) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, "java/lang/Object", null);
		MethodVisitor each = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "each", "(Ljava/lang/String;Ljava/lang/Class;)V", null, null);
		each.visitCode();
		each.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "getInstance", "()L" + LOADER + ";", true);
		each.visitVarInsn(Opcodes.ALOAD, 0);
		each.visitVarInsn(Opcodes.ALOAD, 1);
		each.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOADER, "getEntrypointContainers", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", true);
		each.visitInsn(Opcodes.POP);
		each.visitInsn(Opcodes.RETURN);
		each.visitMaxs(0, 0);
		each.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A {@code main} entrypoint with a constant key whose TYPE is looked up by name at run time. */
	public static byte[] reflectiveTypeMain(String name, String key, String typeName) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		constructor(writer, null, null);
		MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
		main.visitCode();
		main.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "getInstance", "()L" + LOADER + ";", true);
		main.visitLdcInsn(key);
		main.visitLdcInsn(typeName);
		main.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
		main.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOADER, "getEntrypoints", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", true);
		main.visitInsn(Opcodes.POP);
		main.visitInsn(Opcodes.RETURN);
		main.visitMaxs(0, 0);
		main.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A class that only checks whether a mod is loaded, by a constant id: a string constant, but no query of a key. */
	public static byte[] presenceCheck(String name, String modId) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, "java/lang/Object", null);
		MethodVisitor check = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "present", "()Z", null, null);
		check.visitCode();
		check.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "getInstance", "()L" + LOADER + ";", true);
		check.visitLdcInsn(modId);
		check.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOADER, "isModLoaded", "(Ljava/lang/String;)Z", true);
		check.visitInsn(Opcodes.IRETURN);
		check.visitMaxs(0, 0);
		check.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A public constructor; with {@code owner} non-null it also stores {@code key} in the instance field "channel". */
	private static void constructor(ClassWriter writer, String owner, String key) {
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (owner != null) {
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitLdcInsn(key);
			init.visitFieldInsn(Opcodes.PUTFIELD, owner, "channel", "Ljava/lang/String;");
		}
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
	}
}
