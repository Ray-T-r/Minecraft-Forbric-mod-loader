/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Prepends {@code KernelLifecycle.onClientFatalError()} to NeoForge's {@code ClientModLoader.reportFatalError}, the one
 * entry through which a NeoForge client reports an error that stops it. In the carrier its callers are {@code Main.main}'s
 * "Initialization" handler (before {@code new Minecraft}) and {@code ClientModLoader.finish} (inside
 * {@code Minecraft.<init>}); under the kernel {@code finish} is neutered, so Main's handler is the one live caller. The hook
 * gives NeoForge the early window its report draws the error screen on ({@code NeoFatalErrorWindow}, which leaves a report
 * made from inside the game to NeoForge's fallback); the carrier's body then runs unchanged.
 *
 * <p>The METHOD is rewritten, not a call site, so every caller is covered. No argument is read, no branch moves: one
 * {@code invokestatic ()V} at the head.
 */
public final class FatalErrorWindowInjector implements ClassTransformer {
	static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	static final String HOOK_NAME = "onClientFatalError";
	static final String METHOD = "reportFatalError";
	static final String DESC = "(Ljava/lang/Throwable;Ljava/nio/file/Path;Lnet/minecraft/CrashReport;)V";
	private static final String OWNER = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE);

	@Override
	public String name() {
		return "forbric-fatal-error-window";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(OWNER, AnchorSet.Severity.REQUIRED,
				"a NeoForge client that stops on a mod-loading error reports it without NeoForge's error screen: on macOS "
						+ "the player reads \"INVALID MESSAGE WITH QUOTES\" instead of the error"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !OWNER.equals(className)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!METHOD.equals(m.name) || !DESC.equals(m.desc) || (m.access & Opcodes.ACC_STATIC) == 0
					|| m.instructions.size() == 0) {
				continue;
			}
			m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, "()V", false));
			changed = true;
			ForbricLog.info("[Forbric/FatalError] prepended KernelLifecycle.%s to %s.%s — NeoForge's fatal-error report "
					+ "gets the early window it draws its error screen on", HOOK_NAME, className, METHOD);
		}
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
