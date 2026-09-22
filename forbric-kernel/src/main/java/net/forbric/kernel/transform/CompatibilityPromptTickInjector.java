/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Delivers late compatibility decisions at a client tick, never inside class loading or a server callback. */
public final class CompatibilityPromptTickInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.client.Minecraft";
	private static final String OWNER = "net/forbric/kernel/runtime/KernelCompatibilityPrompts";

	@Override public String name() { return "forbric-compatibility-prompt-tick"; }
	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"confirmed failures discovered after startup must reach the client at a safe tick"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!"tick".equals(method.name) || !"()V".equals(method.desc)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && OWNER.equals(call.owner) && "tick".equals(call.name)) return bytes;
			}
			InsnList hook = new InsnList();
			hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "tick", "(Lnet/minecraft/client/Minecraft;)V", false));
			method.instructions.insert(hook);
			method.maxStack = Math.max(method.maxStack, 1);
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		}
		return bytes;
	}
}
