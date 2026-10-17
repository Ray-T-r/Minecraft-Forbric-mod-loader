/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Where a mod's public registration API keeps what it is handed: the static map its register method puts the key and
 * value into, found by following the method's static calls through the class bytes — never by what the mod's
 * implementation calls its classes or fields. A mod laid out under other names is read the same way.
 */
public final class PublicApiStorage {
	private static final int MAX_DEPTH = 4;

	private PublicApiStorage() {
	}

	/**
	 * The static {@code Map} field into which the static method {@code api.name desc} puts its parameters {@code key} and
	 * {@code value} (as {@code map.put(key, value)}, directly or through static calls that pass them on unchanged), made
	 * accessible; null when the API is absent or puts them nowhere this reads.
	 */
	public static Field staticMapPutBy(ClassLoader loader, String api, String name, String desc, int key, int value) {
		if (loader == null) return null;
		Map<String, ClassNode> classes = new HashMap<>();
		String[] found = search(loader, classes, api, name, desc, Map.of(key, 0, value, 1), 0);
		if (found == null) return null;
		try {
			Field field = Class.forName(found[0].replace('/', '.'), false, loader).getDeclaredField(found[1]);
			if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) return null;
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unreachable) {
			Caught.warn(unreachable, "[Forbric/PublicApi] could not open the static map %s.%s that %s.%s puts into; taken as none", found[0], found[1], api, name);
			return null;
		}
	}

	/**
	 * In {@code owner.name desc}, whose parameters listed in {@code roles} carry the key (role 0) and the value (role 1):
	 * the {@code [owner, field]} of the static map they are put into, or null.
	 */
	private static String[] search(ClassLoader loader, Map<String, ClassNode> classes, String owner, String name, String desc,
			Map<Integer, Integer> roles, int depth) {
		if (depth > MAX_DEPTH || platform(owner)) return null;
		ClassNode node = read(loader, classes, owner);
		if (node == null) return null;
		MethodNode method = null;
		for (MethodNode candidate : node.methods) {
			if (candidate.name.equals(name) && candidate.desc.equals(desc) && (candidate.access & Opcodes.ACC_STATIC) != 0) method = candidate;
		}
		if (method == null || method.instructions.size() == 0) return null;
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
		} catch (AnalyzerException unreadable) {
			Caught.expected(unreadable);
			return null;
		}
		Map<Integer, Integer> slots = new HashMap<>();   // local slot -> parameter index
		int slot = 0;
		Type[] parameters = Type.getArgumentTypes(desc);
		for (int i = 0; i < parameters.length; i++) {
			slots.put(slot, i);
			slot += parameters[i].getSize();
		}
		for (int i = 0; i < method.instructions.size(); i++) {
			if (!(method.instructions.get(i) instanceof MethodInsnNode call) || frames[i] == null) continue;
			Frame<SourceValue> frame = frames[i];
			Type[] arguments = Type.getArgumentTypes(call.desc);
			int base = frame.getStackSize() - arguments.length;
			if (call.name.equals("put") && call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
					&& call.getOpcode() != Opcodes.INVOKESTATIC) {
				FieldInsnNode map = only(frame.getStack(base - 1), FieldInsnNode.class);
				Integer k = parameter(frame.getStack(base), slots), v = parameter(frame.getStack(base + 1), slots);
				if (map != null && map.getOpcode() == Opcodes.GETSTATIC && k != null && v != null
						&& Integer.valueOf(0).equals(roles.get(k)) && Integer.valueOf(1).equals(roles.get(v))) {
					return new String[] { map.owner, map.name };
				}
				continue;
			}
			if (call.getOpcode() != Opcodes.INVOKESTATIC) continue;
			Map<Integer, Integer> passed = new HashMap<>();
			for (int a = 0; a < arguments.length; a++) {
				Integer from = parameter(frame.getStack(base + a), slots);
				if (from != null && roles.containsKey(from)) passed.put(a, roles.get(from));
			}
			if (passed.size() < 2) continue;
			String[] found = search(loader, classes, call.owner, call.name, call.desc, passed, depth + 1);
			if (found != null) return found;
		}
		return null;
	}

	/** The parameter index a value is a plain read of, or null. */
	private static Integer parameter(SourceValue value, Map<Integer, Integer> slots) {
		VarInsnNode load = only(value, VarInsnNode.class);
		return load == null || load.getOpcode() != Opcodes.ALOAD ? null : slots.get(load.var);
	}

	private static <T extends AbstractInsnNode> T only(SourceValue value, Class<T> type) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode insn = value.insns.iterator().next();
		return type.isInstance(insn) ? type.cast(insn) : null;
	}

	private static ClassNode read(ClassLoader loader, Map<String, ClassNode> classes, String owner) {
		if (classes.containsKey(owner)) return classes.get(owner);
		ClassNode node = null;
		try (InputStream in = loader.getResourceAsStream(owner + ".class")) {
			if (in != null) {
				node = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		} catch (IOException | RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/PublicApi] could not read %s; taken as absent", owner);
			node = null;
		}
		classes.put(owner, node);
		return node;
	}

	private static boolean platform(String owner) {
		return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/");
	}
}
