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

/**
 * Joins raw losses to actual final class definitions; never equates a call site with event delivery.
 *
 * <p>Reads the kernel's {@code -Dforbric.definedClassEvidence} session: {@code definitions.tsv} maps each defined
 * class to {@code blobs/<sha256>.class}. A session that lost a record is refused whole, because that record is a
 * class this would otherwise report as never loaded, and a helper whose only caller it was would stop counting as
 * one. The kernel says so twice: an {@code #incomplete} row when it can still write one, and always by removing
 * the {@code intact} marker -- the one that survives a full disk or a read-only manifest.
 *
 * <p>States, strongest first. DIRECT_RESTORED: the final caller itself makes the call, as often and in the same
 * invocation form as the patched source did. VIA_DEFINED_HELPER: it reaches the call through exact static calls
 * into defined {@code net/forbric/kernel/} classes. VIA_KERNEL_BRIDGE: the caller does not, but a defined kernel
 * method that no game class calls as a static helper invokes the exact symbol -- in practice an event-bus forward
 * that re-fires the lost hook when the retained side's event is posted; that the forward runs for THIS caller is
 * not proven, so it is reported apart from both repaired and residual. OBSERVED_WITHOUT_HOOK: residual loss.
 * UNOBSERVED: the caller's class was never defined, which is not a pass.
 */
final class EffectiveHookEvidence {
	enum State { DIRECT_RESTORED, VIA_DEFINED_HELPER, VIA_KERNEL_BRIDGE, OBSERVED_WITHOUT_HOOK, UNOBSERVED }
	static final String HEADER = "# forbric-defined-classes-v3";
	static final String INTACT = "intact";
	private static final String KERNEL = "net/forbric/kernel/";
	private final Map<String, MethodNode> methods = new HashMap<>();
	private final Set<String> classes = new HashSet<>();
	/**
	 * Every exact symbol a defined kernel method invokes when nothing calls that method as a static helper -- a
	 * listener lambda, an event forward. A helper statically wired into particular game callers restores only
	 * those, so its calls must not make every other caller's loss of the same hook look bridged.
	 */
	private final Set<String> kernelInvoked = new HashSet<>();

	EffectiveHookEvidence(Path directory) throws IOException {
		Path root = directory.toAbsolutePath().normalize();
		var rows = Files.readAllLines(root.resolve("definitions.tsv"));
		if (rows.isEmpty() || !rows.get(0).equals(HEADER))
			throw new IOException("Not a successful-definition evidence manifest (" + HEADER + "): " + directory);
		for (String row : rows)
			if (row.startsWith("#incomplete"))
				throw new IOException("The kernel could not record every definition in this session: " + row);
		if (!Files.isRegularFile(root.resolve(INTACT)))
			throw new IOException("The kernel lost a record in this session and could not write which: no "
					+ INTACT + " marker in " + directory);
		for (String row : rows) {
			if (row.startsWith("#") || row.isBlank()) continue;
			String[] parts = row.split("\t", -1);
			if (parts.length != 2 || !parts[1].matches("[0-9a-f]{64}")) throw new IOException("Invalid definition row: " + row);
			if (!classes.add(parts[0])) throw new IOException("Duplicate definition: " + parts[0]);
			byte[] bytes = Files.readAllBytes(root.resolve("blobs").resolve(parts[1] + ".class"));
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
		indexKernelForwards();
	}

	private void indexKernelForwards() {
		// Helpers: kernel methods a defined game class calls statically, and everything they call statically.
		Set<String> helpers = new HashSet<>();
		java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
		for (var method : methods.entrySet()) {
			if (method.getKey().startsWith(KERNEL)) continue;
			for (String target : staticKernelCalls(method.getValue())) if (helpers.add(target)) pending.add(target);
		}
		while (!pending.isEmpty()) {
			MethodNode helper = methods.get(pending.poll());
			if (helper == null) continue;
			for (String target : staticKernelCalls(helper)) if (helpers.add(target)) pending.add(target);
		}
		for (var method : methods.entrySet()) {
			if (!method.getKey().startsWith(KERNEL) || helpers.contains(method.getKey())) continue;
			if (method.getValue().instructions == null) continue;
			for (var instruction : method.getValue().instructions)
				if (instruction instanceof MethodInsnNode call) kernelInvoked.add(call.owner + "#" + call.name + call.desc);
		}
	}

	private static Set<String> staticKernelCalls(MethodNode method) {
		Set<String> out = new HashSet<>();
		if (method.instructions == null) return out;
		for (var instruction : method.instructions)
			if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& call.owner.startsWith(KERNEL)) out.add(call.owner + "#" + call.name + call.desc);
		return out;
	}

	/** A conflict row's hook: the merged caller has none of it, so any direct call in the final caller restores it. */
	State state(String caller, String hook) {
		return state(caller, hook, null);
	}

	/**
	 * A census row: {@code originalForms} is the patched source's opcode/itf occurrence count for {@code symbol}.
	 * The final caller restores it directly only when it covers every one of them -- a partial loss whose merged
	 * body kept one of two calls is not restored by that surviving call.
	 */
	State state(String caller, String symbol, Map<String, Integer> originalForms) {
		String owner = caller.substring(0, caller.indexOf('#'));
		if (!classes.contains(owner)) return State.UNOBSERVED;
		MethodNode method = methods.get(caller);
		if (method != null) {
			Map<String, Integer> finalForms = LostHookAttribution.platformCallForms(method).getOrDefault(symbol, Map.of());
			boolean direct = originalForms == null ? !finalForms.isEmpty()
					: LostHookAttribution.invocationOverlap(originalForms, finalForms) >= LostHookAttribution.occurrences(originalForms);
			if (direct) return State.DIRECT_RESTORED;
			if (reaches(method, symbol, new HashSet<>(), 0)) return State.VIA_DEFINED_HELPER;
		}
		return kernelInvoked.contains(symbol) ? State.VIA_KERNEL_BRIDGE : State.OBSERVED_WITHOUT_HOOK;
	}

	private boolean reaches(MethodNode method, String hook, Set<String> seen, int depth) {
		if (depth > 16 || method.instructions == null) return false;
		for (var instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			String symbol = call.owner + "#" + call.name + call.desc;
			// The caller's own calls were judged by count and form already; only a helper's call counts here.
			if (depth > 0 && symbol.equals(hook)) return true;
			// Only an exact static kernel helper call can be resolved without guessing virtual dispatch: runtime,
			// boot or interop, as long as its bytes were defined in this session. Event-bus listeners, reflection,
			// lambdas and unobserved helper definitions remain outside this proof.
			if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.startsWith(KERNEL)) continue;
			MethodNode target = methods.get(symbol);
			if (target != null && seen.add(symbol) && reaches(target, hook, seen, depth + 1)) return true;
		}
		return false;
	}
}
