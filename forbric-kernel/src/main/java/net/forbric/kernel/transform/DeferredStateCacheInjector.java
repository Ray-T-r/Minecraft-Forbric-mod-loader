/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.KernelDeferredStateCaches;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A block state's {@code initCache()} called while NeoForge's {@code RegisterEvent} is being dispatched waits for the end
 * of that dispatch, where NeoForge computes the caches of the blocks its mods register ({@link KernelDeferredStateCaches}
 * says why and when).
 *
 * <p>The failure it repairs: fabric-registry-sync's block-registry add callback calls {@code initCache()} on every state of
 * every block added, a NeoForge mod's block included; computing the collision shape calls the mod's {@code getShape},
 * which may read a sibling's {@code DeferredHolder} not registered yet, and the "Trying to access unbound value" thrown
 * there failed the mod's {@code RegisterEvent} and stopped the server. Natively NeoForge never computes a cache inside the
 * event, so the trigger is that window and nothing about the block or the caller.
 *
 * <pre>
 *   aload_0; invokestatic KernelDeferredStateCaches.defer(Object)Z; ifeq COMPUTE; return
 * COMPUTE: (F_SAME frame)              // initCache as the base has it
 * </pre>
 *
 * <p>Outside the window {@code defer} answers false and the method runs as before. Declined unless {@code initCache()V} is
 * a concrete instance method whose head carries no frame of its own. {@code -Dforbric.deferredStateCaches=off} leaves the
 * class as the base has it.
 */
public final class DeferredStateCacheInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";
	static final String METHOD = "initCache";
	static final String DESC = "()V";
	static final String HOOK = "net/forbric/kernel/util/KernelDeferredStateCaches";
	static final String HOOK_NAME = "defer";
	static final String HOOK_DESC = "(Ljava/lang/Object;)Z";

	@Override public String name() { return "forbric-deferred-state-caches"; }

	@Override public AnchorSet anchors() {
		if (!KernelDeferredStateCaches.enabled()) {
			return AnchorSet.scanned("block state caches are computed inside NeoForge's RegisterEvent with -D"
					+ KernelDeferredStateCaches.PROPERTY + "=off");
		}
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"fabric-api computes a NeoForge mod's block state caches inside RegisterEvent, and a block whose shape reads "
						+ "a sibling registered later in the event stops the boot with \"Trying to access unbound value\""));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(className) || bytes == null || bytes.length == 0 || !KernelDeferredStateCaches.enabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Registries] BlockStateBase.initCache waits for the end of NeoForge's RegisterEvent when "
				+ "called inside it — NeoForge computes its mods' block state caches after registration, and fabric-api's "
				+ "add callback computed them while a block's siblings were still unregistered");
		return writer.toByteArray();
	}

	/** Puts the guard at the head of {@code initCache()}; 1 when it did, 0 when it was there, -1 when declined. */
	static int repair(ClassNode node) {
		MethodNode initCache = null;
		for (MethodNode method : node.methods) {
			if (method.name.equals(METHOD) && method.desc.equals(DESC)) initCache = method;
		}
		if (initCache == null || (initCache.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
			return declined("BlockStateBase declares no concrete instance initCache()V");
		}
		for (AbstractInsnNode insn : initCache.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(HOOK) && call.name.equals(HOOK_NAME)) return 0;
		}
		AbstractInsnNode start = initCache.instructions.getFirst();
		// A frame before the first instruction (a loop back to the head) would sit at the same offset as the guard's own.
		for (AbstractInsnNode insn = start; insn != null && insn.getOpcode() < 0; insn = insn.getNext()) {
			if (insn instanceof FrameNode) return declined("initCache's first instruction carries a frame");
		}
		LabelNode compute = new LabelNode();
		InsnList head = new InsnList();
		head.add(new VarInsnNode(Opcodes.ALOAD, 0));
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, HOOK_NAME, HOOK_DESC, false));
		head.add(new JumpInsnNode(Opcodes.IFEQ, compute));
		head.add(new InsnNode(Opcodes.RETURN));
		head.add(compute);
		// The head of the method: `this`, an empty stack; every later frame was relative to that and still is.
		head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		// After the method's opening label, so its first line number and `this`'s local range cover the guard.
		if (start instanceof LabelNode) initCache.instructions.insert(start, head);
		else initCache.instructions.insert(head);
		return 1;
	}

	private static int declined(String why) {
		ForbricLog.warn("[Forbric/Registries] left BlockStateBase.initCache as it is: %s — a NeoForge mod's block state "
				+ "caches are computed inside RegisterEvent when fabric-api adds a block", why);
		return -1;
	}
}
