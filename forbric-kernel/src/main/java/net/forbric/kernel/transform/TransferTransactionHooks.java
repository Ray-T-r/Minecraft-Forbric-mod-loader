package net.forbric.kernel.transform;

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The pinned lifecycle seams needed to pair the real Fabric 8.x and NeoForge 26.2 transactions. Original
 * validation and snapshot algorithms remain intact. Missing/drifted seams get no readiness marker, so the runtime
 * refuses a cross-API transfer before modifying either endpoint. Register before Mixin when Fabric transfer is present.
 */
public final class TransferTransactionHooks implements ClassTransformer {
	public static final String NEO = "net.neoforged.neoforge.transfer.transaction.Transaction";
	public static final String NEO_MANAGER = "net.neoforged.neoforge.transfer.transaction.TransactionManager";
	public static final String FABRIC = "net.fabricmc.fabric.impl.transfer.transaction.TransactionManagerImpl$TransactionImpl";
	public static final String FABRIC_MANAGER = "net.fabricmc.fabric.impl.transfer.transaction.TransactionManagerImpl";
	private static final String HOOK = "net/forbric/kernel/runtime/transfer/PairedTransactions";
	private static final String RESULT = "net/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext$Result";
	private static final String OUTER_CALLBACK = "net/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext$OuterCloseCallback";
	private static final String JOURNAL = "net/neoforged/neoforge/transfer/transaction/SnapshotJournal";
	private static final String MARKER = "forbric$transferHooks";

	@Override public String name() { return "forbric:paired-transfer-transactions"; }
	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(NEO, AnchorSet.Severity.REQUIRED, "NeoForge transfers cannot follow Fabric rollback"),
				new AnchorSet.Anchor(NEO_MANAGER, AnchorSet.Severity.REQUIRED, "NeoForge final notifications cannot wait for both transaction engines"),
				new AnchorSet.Anchor(FABRIC, AnchorSet.Severity.REQUIRED, "Fabric transfers cannot follow NeoForge rollback"),
				new AnchorSet.Anchor(FABRIC_MANAGER, AnchorSet.Severity.REQUIRED, "a close callback could open a peer scope during a paired commit"));
	}
	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!List.of(NEO, NEO_MANAGER, FABRIC, FABRIC_MANAGER).contains(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (node.methods.stream().anyMatch(m -> MARKER.equals(m.name))) return bytes;
		boolean neo = NEO.equals(className), fabric = FABRIC.equals(className);
		if (neo || fabric) {
			String desc = neo ? "(Z)V" : "(L" + RESULT + ";)V";
			MethodNode close = node.methods.stream().filter(m -> m.name.equals("close") && m.desc.equals(desc)).findFirst().orElse(null);
			if (close == null || (close.access & Opcodes.ACC_PRIVATE) == 0 || !calls(close, "validateCurrentTransaction")
					|| !calls(close, "validateOpen")) return bytes;
			if (fabric && replaceFinal(close, OUTER_CALLBACK, "afterOuterClose", "(L" + RESULT + ";)V",
					"fabricFinal", "(Ljava/lang/Object;Ljava/lang/Object;)V") != 1) return bytes;
			close.name = "forbric$nativeTransferClose";
			node.methods.add(wrapper(node.name, desc, neo));
		} else if (NEO_MANAGER.equals(className)) {
			int changed = 0;
			for (MethodNode method : node.methods) if (method.name.equals("processRootCommitQueue")) {
				changed += replaceFinal(method, JOURNAL, "callOnRootCommit", "()V", "neoFinal", "(Ljava/lang/Object;)V");
			}
			if (changed != 1 || !guardOpen(node, "(Lnet/neoforged/neoforge/transfer/transaction/TransactionContext;Ljava/lang/Class;)Lnet/neoforged/neoforge/transfer/transaction/Transaction;")) return bytes;
		} else {
			if (!guardOpen(node, "()Lnet/fabricmc/fabric/api/transfer/v1/transaction/Transaction;")) return bytes;
		}
		MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, MARKER, "()V", null, null);
		marker.visitCode(); marker.visitInsn(Opcodes.RETURN); marker.visitMaxs(0, 0); marker.visitEnd();
		node.methods.add(marker);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
	private static boolean guardOpen(ClassNode node, String descriptor) {
		for (MethodNode method : node.methods) if (method.name.equals("open") && method.desc.equals(descriptor)) {
			method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beforeOpen", "()V", false));
			return true;
		}
		return false;
	}
	private static boolean calls(MethodNode method, String name) {
		for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
		return false;
	}
	private static int replaceFinal(MethodNode method, String owner, String name, String desc, String hook, String hookDesc) {
		int count = 0;
		for (var instruction : method.instructions.toArray()) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
				method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hook, hookDesc, false));
				count++;
			}
		}
		return count;
	}
	private static MethodNode wrapper(String owner, String desc, boolean neo) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "close", desc, null, null);
		MethodVisitor v = method;
		v.visitCode();
		// Validate before beforeClose, so a caller closing the wrong nesting level cannot close its peer by accident.
		v.visitVarInsn(Opcodes.ALOAD, 0);
		if (neo) {
			String manager = NEO_MANAGER.replace('.', '/');
			v.visitFieldInsn(Opcodes.GETFIELD, owner, "manager", "L" + manager + ";");
			v.visitVarInsn(Opcodes.ALOAD, 0);
			v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, manager, "validateCurrentTransaction", "(L" + owner + ";)V", false);
		} else v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "validateCurrentTransaction", "()V", false);
		v.visitVarInsn(Opcodes.ALOAD, 0);
		v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "validateOpen", "()V", false);
		v.visitVarInsn(Opcodes.ALOAD, 0);
		v.visitVarInsn(neo ? Opcodes.ILOAD : Opcodes.ALOAD, 1);
		if (neo) { v.visitInsn(Opcodes.ICONST_1); v.visitInsn(Opcodes.IXOR); }
		else v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "wasCommitted", "()Z", false);
		v.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "beforeClose", "(Ljava/lang/Object;Z)V", false);
		Label start = new Label(), end = new Label(), handler = new Label();
		v.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
		v.visitLabel(start);
		v.visitVarInsn(Opcodes.ALOAD, 0);
		v.visitVarInsn(neo ? Opcodes.ILOAD : Opcodes.ALOAD, 1);
		v.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "forbric$nativeTransferClose", desc, false);
		v.visitLabel(end);
		v.visitVarInsn(Opcodes.ALOAD, 0); v.visitInsn(Opcodes.ACONST_NULL);
		v.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "afterClose", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
		v.visitInsn(Opcodes.RETURN);
		v.visitLabel(handler);
		v.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
		v.visitVarInsn(Opcodes.ASTORE, 2);
		v.visitVarInsn(Opcodes.ALOAD, 0); v.visitVarInsn(Opcodes.ALOAD, 2);
		v.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "afterClose", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
		v.visitVarInsn(Opcodes.ALOAD, 2); v.visitInsn(Opcodes.ATHROW);
		v.visitMaxs(3, 3); v.visitEnd();
		return method;
	}
}
