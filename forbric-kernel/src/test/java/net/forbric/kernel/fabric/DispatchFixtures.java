/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Synthetic libraries that dispatch a custom Fabric entrypoint key, each written a different way, for the tests of
 * {@link EntrypointDispatchScan} and of the kernel dispatching such a key in a losing build's place. None of them is
 * shaped like any real library: names, keys, helper signatures and the way the key reaches the query all differ.
 */
public final class DispatchFixtures {
	public static final String FABRIC_LOADER = "net/fabricmc/loader/api/FabricLoader";
	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
			"metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;", false);

	private DispatchFixtures() {
	}

	/** An interface declaring {@code methods} as abstract, each with its own descriptor ({@code name+desc}). */
	public static byte[] contract(String name, String... methods) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null,
				"java/lang/Object", null);
		for (String method : methods) {
			int paren = method.indexOf('(');
			writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method.substring(0, paren), method.substring(paren),
					null, null).visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * A {@code main} entrypoint whose key is a {@code static final} field assigned in {@code <clinit>} (no
	 * ConstantValue attribute), copied into a local, queried with {@code getEntrypoints}, and invoked in a plain loop.
	 */
	public static byte[] mainLoopDispatcher(String name, String key, String hookType, String hookMethod) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "SLOT", "Ljava/lang/String;", null, null).visitEnd();
		MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitLdcInsn(key);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "SLOT", "Ljava/lang/String;");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		constructor(writer);
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
		init.visitCode();
		init.visitFieldInsn(Opcodes.GETSTATIC, name, "SLOT", "Ljava/lang/String;");
		init.visitVarInsn(Opcodes.ASTORE, 1);
		init.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance", "()L" + FABRIC_LOADER + ";", true);
		init.visitVarInsn(Opcodes.ALOAD, 1);
		init.visitLdcInsn(Type.getObjectType(hookType));
		init.visitMethodInsn(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "getEntrypoints", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", true);
		init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
		init.visitVarInsn(Opcodes.ASTORE, 2);
		Label loop = new Label(), done = new Label();
		init.visitLabel(loop);
		init.visitVarInsn(Opcodes.ALOAD, 2);
		init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
		init.visitJumpInsn(Opcodes.IFEQ, done);
		init.visitVarInsn(Opcodes.ALOAD, 2);
		init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
		init.visitTypeInsn(Opcodes.CHECKCAST, hookType);
		init.visitMethodInsn(Opcodes.INVOKEINTERFACE, hookType, hookMethod, "()V", true);
		init.visitJumpInsn(Opcodes.GOTO, loop);
		init.visitLabel(done);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * A {@code preLaunch} entrypoint that hands an instance helper the TYPE first and the KEY second (after a
	 * Kotlin-style {@code DUP}/{@code POP}), with a method reference picking one of the contract's methods.
	 */
	public static byte[] preLaunchHelperCaller(String name, String helper, String key, String hookType, String hookMethod) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object",
				new String[] {"net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint"});
		constructor(writer);
		MethodVisitor pre = writer.visitMethod(Opcodes.ACC_PUBLIC, "onPreLaunch", "()V", null, null);
		pre.visitCode();
		pre.visitTypeInsn(Opcodes.NEW, helper);
		pre.visitInsn(Opcodes.DUP);
		pre.visitMethodInsn(Opcodes.INVOKESPECIAL, helper, "<init>", "()V", false);
		pre.visitLdcInsn(Type.getObjectType(hookType));
		pre.visitLdcInsn(key);
		pre.visitInsn(Opcodes.DUP);
		pre.visitInsn(Opcodes.POP);
		pre.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", METAFACTORY,
				Type.getType("(Ljava/lang/Object;)V"),
				new Handle(Opcodes.H_INVOKEINTERFACE, hookType, hookMethod, "()V", true),
				Type.getType("(L" + hookType + ";)V"));
		pre.visitMethodInsn(Opcodes.INVOKEVIRTUAL, helper, "dispatchAll", "(Ljava/lang/Class;Ljava/lang/String;Ljava/util/function/Consumer;)V", false);
		pre.visitInsn(Opcodes.RETURN);
		pre.visitMaxs(0, 0);
		pre.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** The helper: forwards its second parameter as the key and its first as the type to {@code invokeEntrypoints}. */
	public static byte[] forwardingHelper(String name) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		constructor(writer);
		MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC, "dispatchAll", "(Ljava/lang/Class;Ljava/lang/String;Ljava/util/function/Consumer;)V", null, null);
		run.visitCode();
		run.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance", "()L" + FABRIC_LOADER + ";", true);
		run.visitVarInsn(Opcodes.ALOAD, 2);
		run.visitVarInsn(Opcodes.ALOAD, 1);
		run.visitVarInsn(Opcodes.ALOAD, 3);
		run.visitMethodInsn(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "invokeEntrypoints",
				"(Ljava/lang/String;Ljava/lang/Class;Ljava/util/function/Consumer;)V", true);
		run.visitInsn(Opcodes.RETURN);
		run.visitMaxs(0, 0);
		run.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A {@code main} entrypoint that only REGISTERS a callback; the callback's body is what queries the key. */
	public static byte[] callbackDispatcher(String name, String key, String hookType) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		constructor(writer);
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
		init.visitCode();
		init.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", METAFACTORY, Type.getType("()V"),
				new Handle(Opcodes.H_INVOKESTATIC, name, "lambda$later$0", "()V", false), Type.getType("()V"));
		init.visitMethodInsn(Opcodes.INVOKESTATIC, "example/events/WorldEvents", "onFirstLoad", "(Ljava/lang/Runnable;)V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor later = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$later$0", "()V", null, null);
		later.visitCode();
		directQuery(later, key, hookType);
		later.visitInsn(Opcodes.RETURN);
		later.visitMaxs(0, 0);
		later.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A {@code main} entrypoint querying {@code key} directly with {@code getEntrypointContainers}. */
	public static byte[] directDispatcher(String name, String key, String hookType) {
		return directDispatcher(name, key, hookType, "net/fabricmc/api/ModInitializer", "onInitialize");
	}

	/** The same, from the lifecycle interface and method given ({@code ClientModInitializer.onInitializeClient}, …). */
	public static byte[] directDispatcher(String name, String key, String hookType, String lifecycle, String method) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {lifecycle});
		constructor(writer);
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
		init.visitCode();
		directQuery(init, key, hookType);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A {@code main} entrypoint whose key is computed at run time, from a system property. */
	public static byte[] runtimeKeyDispatcher(String name, String hookType) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		constructor(writer);
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
		init.visitCode();
		init.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance", "()L" + FABRIC_LOADER + ";", true);
		init.visitLdcInsn("example.key");
		init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;", false);
		init.visitLdcInsn(Type.getObjectType(hookType));
		init.visitMethodInsn(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "getEntrypointContainers", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", true);
		init.visitInsn(Opcodes.POP);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A class that reads {@code key} as a plain string from a map, the way a build reads its own platform's metadata. */
	public static byte[] propertyReader(String name, String key) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor read = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(Ljava/util/Map;)Ljava/lang/Object;", null, null);
		read.visitCode();
		read.visitVarInsn(Opcodes.ALOAD, 0);
		read.visitLdcInsn(key);
		read.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
		read.visitInsn(Opcodes.ARETURN);
		read.visitMaxs(0, 0);
		read.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void directQuery(MethodVisitor method, String key, String hookType) {
		method.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance", "()L" + FABRIC_LOADER + ";", true);
		method.visitLdcInsn(key);
		method.visitLdcInsn(Type.getObjectType(hookType));
		method.visitMethodInsn(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "getEntrypointContainers", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", true);
		method.visitInsn(Opcodes.POP);
	}

	/**
	 * A consumer implementing {@code hookType}: its {@code hookMethod} counts its calls in the static int
	 * {@code CALLS}; any other no-argument method of the contract named in {@code others} fails the test if called.
	 */
	public static byte[] consumer(String name, String hookType, String hookMethod, String... others) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {hookType});
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CALLS", "I", null, null).visitEnd();
		constructor(writer);
		MethodVisitor hook = writer.visitMethod(Opcodes.ACC_PUBLIC, hookMethod, "()V", null, null);
		hook.visitCode();
		hook.visitFieldInsn(Opcodes.GETSTATIC, name, "CALLS", "I");
		hook.visitInsn(Opcodes.ICONST_1);
		hook.visitInsn(Opcodes.IADD);
		hook.visitFieldInsn(Opcodes.PUTSTATIC, name, "CALLS", "I");
		hook.visitInsn(Opcodes.RETURN);
		hook.visitMaxs(0, 0);
		hook.visitEnd();
		for (String other : others) {
			MethodVisitor wrong = writer.visitMethod(Opcodes.ACC_PUBLIC, other, "()V", null, null);
			wrong.visitCode();
			wrong.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
			wrong.visitInsn(Opcodes.DUP);
			wrong.visitLdcInsn(other + " is not the method the library invokes");
			wrong.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
			wrong.visitInsn(Opcodes.ATHROW);
			wrong.visitMaxs(0, 0);
			wrong.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A class with nothing in it. */
	public static byte[] plain(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void constructor(ClassWriter writer) {
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
	}

	/** A {@code fabric.mod.json} for {@code id} declaring {@code entrypoints} (key → class names). */
	public static byte[] fabricModJson(String id, Map<String, List<String>> entrypoints) {
		StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"entrypoints\":{");
		boolean first = true;
		for (var entry : new TreeMap<>(entrypoints).entrySet()) {
			if (!first) json.append(',');
			first = false;
			json.append('"').append(entry.getKey()).append("\":[");
			json.append(String.join(",", entry.getValue().stream().map(value -> '"' + value + '"').toList()));
			json.append(']');
		}
		return json.append("}}").toString().getBytes(StandardCharsets.UTF_8);
	}

	/** A NeoForge manifest for {@code id}. */
	public static byte[] neoForgeToml(String id) {
		return ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"1.0.0\"\n")
				.getBytes(StandardCharsets.UTF_8);
	}

	/** Internal class names become {@code .class} entries; anything containing a dot or a slash-terminated path is kept. */
	public static byte[] jar(Map<String, byte[]> entries) throws java.io.IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var entry : new TreeMap<>(entries).entrySet()) {
				String name = entry.getKey();
				if (!name.contains(".")) name = name + ".class";
				ZipEntry part = new ZipEntry(name);
				part.setTime(0);
				zip.putNextEntry(part);
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	public static Path write(Path file, Map<String, byte[]> entries) throws java.io.IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, jar(entries));
		return file;
	}
}
