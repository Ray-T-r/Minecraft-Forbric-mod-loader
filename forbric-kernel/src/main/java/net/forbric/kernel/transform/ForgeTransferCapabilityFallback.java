package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Register AFTER ForgeCapabilityCompositionTransformer: use its real provider result, never replace it. */
public final class ForgeTransferCapabilityFallback implements ClassTransformer {
	public static final String TARGET = "net.minecraft.world.level.block.entity.BlockEntity";
	private static final String HOOK = "net/forbric/kernel/runtime/transfer/BlockTransferBridge";
	private static final String OPTIONAL = "net/minecraftforge/common/util/LazyOptional";
	private static final String DESC = "(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)L" + OPTIONAL + ";";
	@Override public String name() { return "forbric:forge-transfer-capability-fallback"; }
	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED, "Forge cannot query cross-ecosystem block item/fluid stores"));
	}
	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name)) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		if (node.methods.stream().anyMatch(method -> method.name.equals("forbric$forgeTransferFallback"))) return bytes;
		MethodNode query = node.methods.stream().filter(method -> method.name.equals("getCapability") && method.desc.equals(DESC)).findFirst().orElse(null);
		MethodNode invalidation = node.methods.stream().filter(method -> method.name.equals("invalidateCaps") && method.desc.equals("()V")).findFirst().orElse(null);
		if (query == null || invalidation == null) return bytes;
		boolean queryChanged = false, invalidateChanged = false;
		for (var instruction : query.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
			InsnList call = new InsnList();
			for (int local = 0; local <= 2; local++) call.add(new VarInsnNode(Opcodes.ALOAD, local));
			call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "forgeFallback", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
			call.add(new TypeInsnNode(Opcodes.CHECKCAST, OPTIONAL));
			query.instructions.insertBefore(instruction, call); queryChanged = true;
		}
		for (var instruction : invalidation.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
			InsnList call = new InsnList(); call.add(new VarInsnNode(Opcodes.ALOAD, 0));
			call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "forgeInvalidated", "(Ljava/lang/Object;)V", false));
			invalidation.instructions.insertBefore(instruction, call); invalidateChanged = true;
		}
		if (!queryChanged || !invalidateChanged) return bytes;
		MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "forbric$forgeTransferFallback", "()V", null, null);
		marker.visitCode(); marker.visitInsn(Opcodes.RETURN); marker.visitMaxs(0, 0); marker.visitEnd(); node.methods.add(marker);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
	}
}
