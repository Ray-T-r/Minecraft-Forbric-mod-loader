/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.interop.EcosystemTabPlacement;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Hands the creative tab order NeoForge is about to publish through {@code KernelCreativeTabOrder.place} first, so a
 * Fabric mod's tabs are paged by the Fabric pager's rule and every other tab keeps NeoForge's ({@link EcosystemTabPlacement}).
 *
 * <p>The hook goes at the head of {@code CreativeModeTabRegistry.setCreativeModeTabOrder(List)}, the one method every
 * NeoForge sort ends in — the boot-time sort and each resource reload's recalculation alike — so the placement holds
 * after every re-sort. {@code -Dforbric.creativeTabEcosystemOrder=off} publishes NeoForge's order as it is.
 */
public final class CreativeTabOrderInjector implements ClassTransformer {
	static final String TARGET = "net.neoforged.neoforge.common.CreativeModeTabRegistry";
	static final String METHOD = "setCreativeModeTabOrder";
	static final String DESC = "(Ljava/util/List;)V";
	static final String HOOK = "net/forbric/kernel/runtime/KernelCreativeTabOrder";

	@Override public String name() { return "forbric-creative-tab-order"; }

	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's creative tabs are paged by NeoForge's registration chain, between vanilla's tabs, instead of after "
						+ "them in id order as the Fabric pager pages them"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(className) || bytes == null || "off".equalsIgnoreCase(System.getProperty(EcosystemTabPlacement.PROPERTY, "on"))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESC.equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && HOOK.equals(call.owner)) return bytes;   // already in
			}
			InsnList head = new InsnList();
			head.add(new VarInsnNode(Opcodes.ALOAD, 0));
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "place", "(Ljava/util/List;)Ljava/util/List;", false));
			head.add(new VarInsnNode(Opcodes.ASTORE, 0));
			method.instructions.insert(head);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		}
		return bytes;
	}
}
