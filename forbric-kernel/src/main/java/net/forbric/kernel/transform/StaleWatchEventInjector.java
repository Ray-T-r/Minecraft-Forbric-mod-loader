/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.nio.charset.StandardCharsets;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Hands every handler registered with night-config's {@code FileWatcher.addWatch} through {@code StaleWatchGuard} first,
 * so a change event that only reports a file's own creation in an already-watched directory does not run it.
 *
 * <p>Generic over call sites: NeoForge's {@code ConfigTracker.openConfig} and MinecraftForge's config handler are the
 * two in the carriers, and the reason it matters in the merged game is that the carriers' own configs keep
 * {@code config/} watched from startup (see StaleWatchGuard). At each call the stack is
 * {@code [watcher, file, handler]}; the guard is built from {@code file} and {@code handler} and replaces the handler,
 * nothing else changes. Calls inside {@code FileWatcher} itself (its overloads forwarding to each other) are left alone.
 * {@code -Dforbric.staleWatchGuard=off} registers handlers as shipped.
 */
public final class StaleWatchEventInjector implements ClassTransformer {
	static final String WATCHER = "com/electronwill/nightconfig/core/file/FileWatcher";
	static final String GUARD = "net/forbric/kernel/interop/StaleWatchGuard";

	@Override public String name() { return "forbric-stale-watch-event"; }

	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("every night-config FileWatcher.addWatch(file, handler) call site");
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (bytes == null || "off".equalsIgnoreCase(System.getProperty("forbric.staleWatchGuard", "on"))) return bytes;
		if (!new String(bytes, StandardCharsets.ISO_8859_1).contains(WATCHER)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (WATCHER.equals(node.name)) return bytes;
		int guarded = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || !WATCHER.equals(call.owner) || !"addWatch".equals(call.name)) continue;
				String file = switch (call.desc) {
					case "(Ljava/nio/file/Path;Ljava/lang/Runnable;)V" -> "Ljava/nio/file/Path;";
					case "(Ljava/io/File;Ljava/lang/Runnable;)V" -> "Ljava/io/File;";
					default -> null;
				};
				if (file == null || alreadyGuarded(call)) continue;
				// [watcher, file, handler] -> [watcher, file, handler, file, handler] -> [watcher, file, handler, guard]
				// -> [watcher, file, guard, handler] -> [watcher, file, guard]
				InsnList wrap = new InsnList();
				wrap.add(new InsnNode(Opcodes.DUP2));
				wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "guard", "(" + file + "Ljava/lang/Runnable;)Ljava/lang/Runnable;", false));
				wrap.add(new InsnNode(Opcodes.SWAP));
				wrap.add(new InsnNode(Opcodes.POP));
				method.instructions.insertBefore(call, wrap);
				guarded++;
			}
		}
		if (guarded == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean alreadyGuarded(MethodInsnNode call) {
		AbstractInsnNode before = call.getPrevious();
		while (before != null && before.getOpcode() < 0) before = before.getPrevious();
		if (before == null || before.getOpcode() != Opcodes.POP) return false;
		AbstractInsnNode swap = before.getPrevious();
		while (swap != null && swap.getOpcode() < 0) swap = swap.getPrevious();
		AbstractInsnNode guard = swap == null ? null : swap.getPrevious();
		while (guard != null && guard.getOpcode() < 0) guard = guard.getPrevious();
		return guard instanceof MethodInsnNode g && GUARD.equals(g.owner);
	}
}
