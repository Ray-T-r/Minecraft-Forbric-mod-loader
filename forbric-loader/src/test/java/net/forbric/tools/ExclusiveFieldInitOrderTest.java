/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * A field only one platform declares, whose writer the merge dropped, is initialized where javac would have put its
 * initializer: right after the superclass constructor. The constructor below calls a method that reads it, which is
 * what a game class's constructor does when it loads its settings.
 */
class ExclusiveFieldInitOrderTest {
	@TempDir Path directory;

	private static final String SETTINGS = "net/minecraft/demo/Settings";

	@Test
	void aMethodTheConstructorCallsSeesTheFieldInitialized() throws Exception {
		ClassNode vanilla = settings(false, false), forge = settings(true, false), neo = settings(false, true);
		Path merged = directory.resolve("merged.jar");
		new MergedBaseBuilder().run(jar("vanilla.jar", vanilla), jar("forge.jar", forge), jar("neo.jar", neo), merged,
				directory.resolve("report.txt"), null, null);
		byte[] bytes;
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			bytes = zip.getInputStream(zip.getEntry(SETTINGS + ".class")).readAllBytes();
		}
		Class<?> type = new ClassLoader(getClass().getClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!name.equals(SETTINGS.replace('/', '.'))) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		}.loadClass(SETTINGS.replace('/', '.'));
		Object instance = type.getConstructor().newInstance();
		assertNotNull(type.getField("extra").get(instance));
	}

	/**
	 * Vanilla: a constructor that calls {@code load()}. MinecraftForge adds a map field initialized in its constructor
	 * and a {@code load} that clears it through a method it added. NeoForge adds an unrelated method of its own.
	 */
	private static ClassNode settings(boolean forge, boolean neo) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = SETTINGS;
		node.superName = "java/lang/Object";
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		if (forge) {
			node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "extra", "Ljava/util/Map;", null, null));
			init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
			init.instructions.add(new InsnNode(Opcodes.DUP));
			init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
			init.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, SETTINGS, "extra", "Ljava/util/Map;"));
		}
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SETTINGS, "load", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(init);
		MethodNode load = new MethodNode(Opcodes.ACC_PUBLIC, "load", "()V", null, null);
		if (forge) {
			load.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			load.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SETTINGS, "clearExtra", "()V", false));
			MethodNode clear = new MethodNode(Opcodes.ACC_PUBLIC, "clearExtra", "()V", null, null);
			clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			clear.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, SETTINGS, "extra", "Ljava/util/Map;"));
			clear.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "clear", "()V", true));
			clear.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(clear);
		}
		load.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(load);
		if (neo) {
			MethodNode added = new MethodNode(Opcodes.ACC_PUBLIC, "neoExtension", "()V", null, null);
			added.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(added);
		}
		return node;
	}

	private Path jar(String name, ClassNode node) throws Exception {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		Path path = directory.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry(node.name + ".class"));
			zip.write(writer.toByteArray());
			zip.closeEntry();
		}
		return path;
	}

}
