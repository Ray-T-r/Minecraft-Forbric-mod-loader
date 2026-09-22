/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Joins raw losses to actual final class definitions; never equates a call site with event delivery. */
final class EffectiveHookEvidence {
	enum State { DIRECT_RESTORED, VIA_DEFINED_HELPER, OBSERVED_WITHOUT_HOOK, UNOBSERVED }
	private final Map<String, MethodNode> methods = new HashMap<>();
	private final Set<String> classes = new HashSet<>();

	EffectiveHookEvidence(Path directory) throws IOException {
		Path root = directory.toAbsolutePath().normalize();
		var rows = Files.readAllLines(root.resolve("definitions.tsv"));
		if (rows.isEmpty() || !rows.get(0).equals("# forbric-defined-classes-v1"))
			throw new IOException("Not a successful-definition evidence manifest: " + directory);
		for (String row : rows) {
			if (row.startsWith("#") || row.isBlank()) continue;
			String[] parts = row.split("\t", -1);
			if (parts.length != 2 || !parts[1].matches("[0-9a-f]{64}")) throw new IOException("Invalid definition row: " + row);
			Path file = root.resolve(parts[0] + ".class").normalize();
			if (!file.startsWith(root) || !classes.add(parts[0])) throw new IOException("Invalid/duplicate definition: " + parts[0]);
			byte[] bytes = Files.readAllBytes(file);
			try {
				if (!parts[1].equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
					throw new IOException("Defined-class hash changed: " + parts[0]);
			} catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (!node.name.equals(parts[0])) throw new IOException("Defined-class identity changed: " + parts[0]);
			for (MethodNode method : node.methods) methods.put(node.name + "#" + method.name + method.desc, method);
		}
		if (classes.isEmpty()) throw new IOException("No successful definitions were observed: " + directory);
	}

	State state(String caller, String hook) {
		String owner = caller.substring(0, caller.indexOf('#'));
		if (!classes.contains(owner)) return State.UNOBSERVED;
		MethodNode method = methods.get(caller);
		if (method == null) return State.OBSERVED_WITHOUT_HOOK;
		if (LostHookAttribution.hookCalls(method).contains(hook)) return State.DIRECT_RESTORED;
		return reaches(method, hook, new HashSet<>(), 0) ? State.VIA_DEFINED_HELPER : State.OBSERVED_WITHOUT_HOOK;
	}

	private boolean reaches(MethodNode method, String hook, Set<String> seen, int depth) {
		if (depth > 16) return false;
		for (var instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			String symbol = call.owner + "#" + call.name + call.desc;
			if (symbol.equals(hook)) return true;
			// Only an exact static kernel helper call can be resolved without guessing virtual dispatch. Event
			// bus listeners, reflection, lambdas and unobserved helper definitions remain outside this proof.
			if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.startsWith("net/forbric/kernel/runtime/")) continue;
			MethodNode target = methods.get(symbol);
			if (target != null && seen.add(symbol) && reaches(target, hook, seen, depth + 1)) return true;
		}
		return false;
	}
}
