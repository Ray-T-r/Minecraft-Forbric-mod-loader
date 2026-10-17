/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import net.forbric.kernel.util.ForbricLog;

/**
 * A real instruction for a capture {@link MixinGuiItemCaptureAdapter} moved to land on, so that both of Mixin's
 * local-variable walks find the locals there.
 *
 * <p>The adapter moves a TAIL capture to {@code INVOKE <the call that ends the body>, AFTER}. Where that call falls
 * straight into the join the tail sits on, AFTER is the join's label. To read the locals at a label, Mixin first steps
 * forward over pseudo-instructions, and its two walks step differently. Since 0.10.0 ({@code Locals.getLocalsAt}) it
 * stops before a frame. The 0.9.2 walk ({@code getLocalsAt092}) steps up to three nodes, onto the join's frame, and reads
 * it. Fabric Loader gives that walk to every mod that declares no lower bound on {@code fabricloader}. The frame belongs
 * to the join, and the join is also reached by the path that never stored the local, so the frame does not declare it:
 * Mixin reports "LVT ... has incompatible changes" and skips the handler. Nothing crashes, and the capture is gone.
 *
 * <p>A {@code NOP} right after the call is still inside the locals' scope and is not a pseudo-instruction, so neither
 * walk steps: both read the locals the body left, and the capture applies at every level. The code runs as before. The
 * 0.10.0 walk gives what it gave without the NOP, since the label and line number it used to stop on change nothing it
 * reads.
 *
 * <p>Only after a call the adapter moved a capture to, counted as the adapter counted it ({@code ordinal}, over the
 * method as Mixin is handed it), and only where the next node is a pseudo-instruction: a call followed by its result's
 * {@code pop} or by a {@code goto} already gives Mixin a real instruction. Inserted when Mixin is about to apply its
 * mixins to the target ({@link IExtension#preApply}), because that is the first point that has both. The adapter runs
 * while Mixin prepares the mixins, and on the first class Mixin weaves, that is after the kernel's own pre-Mixin passes.
 * The nodes Mixin's injection points match are unchanged: a {@code NOP} is no call, field access, jump or return, and it
 * has no frame of its own. {@code -Dforbric.captureLanding=off} leaves the target as it was.
 */
public final class MixinCaptureLanding implements IExtension {
	public static final String PROPERTY = "forbric.captureLanding";

	/** {@code method} is name + descriptor; {@code member} is {@code Lowner;name(desc)}; {@code ordinal} -1 is every one. */
	record Landing(String method, String member, int ordinal) { }

	/** Target internal name → the calls a moved capture lands after. */
	private static final Map<String, Set<Landing>> LANDINGS = new ConcurrentHashMap<>();

	/** Records that a capture was moved to {@code INVOKE member}, {@code AFTER}, in {@code method} of {@code owner}. */
	static void after(String owner, MethodNode method, String member, int ordinal) {
		if (owner == null || method == null || member == null) return;
		LANDINGS.computeIfAbsent(owner, o -> ConcurrentHashMap.newKeySet()).add(new Landing(method.name + method.desc, member, ordinal));
	}

	/** Inserts the NOPs {@code target} needs; returns how many. Package-private for the tests. */
	static int land(ClassNode target) {
		if (target == null || target.methods == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
		Set<Landing> wanted = LANDINGS.get(target.name);
		if (wanted == null) return 0;
		int placed = 0;
		for (Landing landing : wanted) {
			for (MethodNode method : target.methods) {
				if (method.instructions == null || !landing.method().equals(method.name + method.desc)) continue;
				int seen = 0;
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (!(insn instanceof MethodInsnNode call)
							|| !landing.member().equals("L" + call.owner + ";" + call.name + call.desc)) continue;
					if (landing.ordinal() < 0 || seen == landing.ordinal()) {
						AbstractInsnNode next = call.getNext();
						if (next != null && next.getOpcode() < 0) {
							method.instructions.insert(call, new InsnNode(Opcodes.NOP));
							placed++;
						}
					}
					seen++;
				}
			}
		}
		return placed;
	}

	/** Forgets every landing. For the tests, which reuse one JVM. */
	static void clear() {
		LANDINGS.clear();
	}

	@Override
	public boolean checkActive(MixinEnvironment environment) {
		return true;
	}

	@Override
	public void preApply(ITargetClassContext context) {
		try {
			int placed = land(context.getClassNode());
			if (placed > 0) {
				ForbricLog.info("[Forbric/Mixin] %s: %d moved locals capture(s) given a NOP to land on after their call",
						context.getClassNode().name, placed);
			}
		} catch (RuntimeException unexpected) {
			// A capture without its NOP is what the class had before this existed; it must not keep the class from weaving.
			ForbricLog.debug("[Forbric/Mixin] could not give the moved captures in %s their NOP: %s",
					context.getClassNode() == null ? "?" : context.getClassNode().name, String.valueOf(unexpected));
		}
	}

	@Override
	public void postApply(ITargetClassContext context) {
	}

	@Override
	public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {
	}
}
