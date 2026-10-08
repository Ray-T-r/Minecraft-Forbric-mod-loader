/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Checks a declared binary API without executing jar code or tying compatibility to a release hash. */
public final class ApiJarContract {
	private ApiJarContract() { }
	public static List<String> verify(List<String> contract, Function<String, byte[]> resources) {
		Map<String, ClassNode> classes = new HashMap<>();
		Function<String, ClassNode> types = name -> classes.computeIfAbsent(name, key -> {
			byte[] bytes = resources.apply(key + ".class"); if (bytes == null) return null;
			ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
		});
		List<String> missing = new ArrayList<>();
		for (String raw : contract) {
			String line = raw.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
			String[] parts = line.split("\\s+");
			if (parts.length < 3 || !Set.of("class", "method", "field").contains(parts[0])) throw new IllegalArgumentException("Invalid API contract: " + line);
			ClassNode owner = types.apply(parts[1]);
			if (parts[0].equals("class")) {
				if (parts.length != 3) throw new IllegalArgumentException("Invalid API class contract: " + line);
				if (owner == null || !access(owner.access, parts[2])) missing.add(line);
			} else {
				if (parts.length != 5) throw new IllegalArgumentException("Invalid API member contract: " + line);
				Integer access = member(owner, parts[0], parts[2], parts[3], types, new HashSet<>());
				if (access == null || !access(access, parts[4])) missing.add(line);
			}
		}
		return List.copyOf(missing);
	}
	private static Integer member(ClassNode type, String kind, String name, String desc, Function<String, ClassNode> types, Set<String> visited) {
		if (type == null || !visited.add(type.name)) return null;
		if (kind.equals("field")) {
			for (var field : type.fields) if (field.name.equals(name) && field.desc.equals(desc)) return field.access;
		} else for (var method : type.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method.access;
		Integer inherited = type.superName == null || kind.equals("field") ? null : member(types.apply(type.superName), kind, name, desc, types, visited);
		if (inherited != null) return inherited;
		for (String parent : type.interfaces) {
			inherited = member(types.apply(parent), kind, name, desc, types, visited); if (inherited != null) return inherited;
		}
		return kind.equals("field") && type.superName != null ? member(types.apply(type.superName), kind, name, desc, types, visited) : null;
	}
	private static boolean access(int actual, String conditions) {
		for (String condition : conditions.split(",")) {
			int flag = switch (condition) {
				case "public" -> Opcodes.ACC_PUBLIC; case "static" -> Opcodes.ACC_STATIC; case "interface" -> Opcodes.ACC_INTERFACE;
				case "instance" -> -Opcodes.ACC_STATIC; default -> throw new IllegalArgumentException("Unknown API access condition: " + condition);
			};
			if (flag > 0 && (actual & flag) == 0 || flag < 0 && (actual & -flag) != 0) return false;
		}
		return true;
	}
	/** contract file, API jar, then optional dependency jars used for inherited declarations. */
	public static void main(String[] args) throws IOException {
		if (args.length < 2) throw new IllegalArgumentException("API contract file and API jar required");
		List<ZipFile> jars = new ArrayList<>();
		try {
			for (int i = 1; i < args.length; i++) jars.add(new ZipFile(args[i]));
			List<String> missing = verify(Files.readAllLines(Path.of(args[0])), resource -> {
				for (ZipFile jar : jars) {
					var entry = jar.getEntry(resource); if (entry == null) continue;
					try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
					catch (IOException failure) { throw new IllegalStateException("Cannot read API declaration " + resource, failure); }
				}
				return null;
			});
			if (!missing.isEmpty()) throw new IllegalStateException("API jar does not satisfy the required binary contract:\n" + String.join("\n", missing));
			System.out.println("API binary contract verified: " + args[1]);
		} finally { for (ZipFile jar : jars) jar.close(); }
	}
}
