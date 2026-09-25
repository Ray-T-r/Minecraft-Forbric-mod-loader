/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NeoForge's tooltip registration event goes to each mod on its own.
 *
 * <p>{@code ItemTooltipHandler.init} hands its {@code RegisterTooltipAppendersEvent} to {@code ModLoader.postEvent},
 * which rethrows the first listener failure — one mod registering an appender twice cost every mod after it its
 * tooltip lines — and posts nothing at all once any mod has failed to load. The one call goes to
 * {@code KernelNeoTooltips.postRegisterAppenders}, the kernel's per-container delivery; the event is the one
 * {@code init} just built, so nothing else in the method moves. Applied only when {@code init} posts exactly one event
 * and it is that one. {@code -Dforbric.neoTooltipAppenders=off} leaves the class as shipped (and the kernel does not
 * build the appenders at all).
 */
public final class NeoTooltipAppendersInjector implements ClassTransformer {
	static final String HANDLER = "net.neoforged.neoforge.common.tooltip.ItemTooltipHandler";
	static final String EVENT = "net/neoforged/neoforge/event/RegisterTooltipAppendersEvent";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelNeoTooltips";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(KernelLifecycle.NEO_TOOLTIP_APPENDERS, "on"));
	}

	@Override public String name() { return "forbric-neo-tooltip-appenders"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("NeoForge's tooltip appenders left unbuilt with -D" + KernelLifecycle.NEO_TOOLTIP_APPENDERS + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(HANDLER, AnchorSet.Severity.REQUIRED,
				"one mod's failing tooltip registration costs every later mod its tooltip lines"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !HANDLER.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/Tooltips] ItemTooltipHandler.init posts its RegisterTooltipAppendersEvent to each mod on "
				+ "its own — ModLoader.postEvent stopped at the first mod that threw");
		return writer.toByteArray();
	}

	static boolean repair(ClassNode handler) {
		for (MethodNode method : handler.methods) {
			if (!method.name.equals("init") || !method.desc.equals("()V") || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			MethodInsnNode post = null;
			int posts = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.equals(RUNTIME)) return false;
				if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("net/neoforged/fml/ModLoader")
						&& call.name.equals("postEvent") && call.desc.equals("(Lnet/neoforged/bus/api/Event;)V")) {
					post = call;
					posts++;
				}
			}
			if (posts != 1) return false;
			AbstractInsnNode previous = post.getPrevious();
			while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
			if (!(previous instanceof MethodInsnNode built) || built.getOpcode() != Opcodes.INVOKESPECIAL
					|| !built.owner.equals(EVENT) || !built.name.equals("<init>")) return false;
			post.owner = RUNTIME;
			post.name = "postRegisterAppenders";
			post.desc = "(L" + EVENT + ";)V";
			return true;
		}
		return false;
	}
}
