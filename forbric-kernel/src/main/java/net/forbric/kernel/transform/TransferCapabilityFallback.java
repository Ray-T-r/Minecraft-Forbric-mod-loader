package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds a fallback only at NeoForge's final "all native providers declined" result. */
public final class TransferCapabilityFallback implements ClassTransformer {
	public static final String TARGET = "net.neoforged.neoforge.capabilities.BlockCapability";
	private static final String HOOK = "net/forbric/kernel/runtime/transfer/BlockTransferBridge";
	private static final String DESC = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/lang/Object;)Ljava/lang/Object;";
	@Override public String name() { return "forbric:transfer-capability-fallback"; }
	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED, "NeoForge cannot discover Fabric item/fluid stores"));
	}
	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name)) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!method.name.equals("getCapability") || !method.desc.equals(DESC)) continue;
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)) return bytes;
			int nullReturns = 0;
			for (var instruction : method.instructions) if (instruction.getOpcode() == Opcodes.ACONST_NULL
					&& instruction.getNext() != null && instruction.getNext().getOpcode() == Opcodes.ARETURN) nullReturns++;
			if (nullReturns != 1) return bytes;
			for (var instruction : method.instructions.toArray()) {
				if (instruction.getOpcode() != Opcodes.ACONST_NULL || instruction.getNext().getOpcode() != Opcodes.ARETURN) continue;
				InsnList call = new InsnList();
				for (int local = 0; local <= 5; local++) call.add(new VarInsnNode(Opcodes.ALOAD, local));
				call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "neoFallback",
						"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
				method.instructions.insertBefore(instruction, call); method.instructions.remove(instruction);
			}
			MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "forbric$transferFallback", "()V", null, null);
			marker.visitCode(); marker.visitInsn(Opcodes.RETURN); marker.visitMaxs(0, 0); marker.visitEnd();
			node.methods.add(marker);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
		}
		return bytes;
	}
}
