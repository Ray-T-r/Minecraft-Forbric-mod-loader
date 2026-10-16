package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The three rewrites the kernel itself makes to audited classes digest as the shape they were made from, and nothing
 * near them does. Synthetic classes, so CI holds this without the staged jars; ForgeTransferShapeAuditChainStagedTest
 * holds it on the real ones, through KernelBoot's whole pre-Mixin chain.
 */
class ForgeTransferShapeAuditFoldTest {
	private static final String PROBE = "fold/Probe", DEFAULTS = "fold/Defaults";
	private static final String FLUID_TYPE = "L" + ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE) + ";";

	/** A class with a method reference to its own method, a fluid-type getter and, optionally, a default delegate. */
	private static byte[] probe(boolean delegate) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PROBE, null, "java/lang/Object", new String[] { DEFAULTS });
		writer.visitField(Opcodes.ACC_PRIVATE, "type", FLUID_TYPE, null, null).visitEnd();
		var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
		var amount = writer.visitMethod(Opcodes.ACC_PUBLIC, "amount", "()I", null, null);
		amount.visitCode(); amount.visitInsn(Opcodes.ICONST_3); amount.visitInsn(Opcodes.IRETURN); amount.visitMaxs(0, 0); amount.visitEnd();
		var other = writer.visitMethod(Opcodes.ACC_PUBLIC, "other", "()I", null, null);
		other.visitCode(); other.visitInsn(Opcodes.ICONST_4); other.visitInsn(Opcodes.IRETURN); other.visitMaxs(0, 0); other.visitEnd();
		var supplier = writer.visitMethod(Opcodes.ACC_PUBLIC, "amounts", "()Ljava/util/function/IntSupplier;", null, null);
		supplier.visitCode(); supplier.visitVarInsn(Opcodes.ALOAD, 0);
		supplier.visitInvokeDynamicInsn("getAsInt", "(L" + PROBE + ";)Ljava/util/function/IntSupplier;",
				new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
				Type.getType("()I"), new Handle(Opcodes.H_INVOKEVIRTUAL, PROBE, "amount", "()I", false), Type.getType("()I"));
		supplier.visitInsn(Opcodes.ARETURN); supplier.visitMaxs(0, 0); supplier.visitEnd();
		var getter = writer.visitMethod(Opcodes.ACC_PUBLIC, "getFluidType", "()" + FLUID_TYPE, null, null);
		getter.visitCode(); getter.visitVarInsn(Opcodes.ALOAD, 0); getter.visitFieldInsn(Opcodes.GETFIELD, PROBE, "type", FLUID_TYPE);
		getter.visitInsn(Opcodes.ARETURN); getter.visitMaxs(0, 0); getter.visitEnd();
		if (delegate) {
			var stub = writer.visitMethod(Opcodes.ACC_PUBLIC, "unusual", "(J)I", null, null);
			stub.visitCode(); stub.visitVarInsn(Opcodes.ALOAD, 0); stub.visitVarInsn(Opcodes.LLOAD, 1);
			stub.visitMethodInsn(Opcodes.INVOKESPECIAL, DEFAULTS, "unusual", "(J)I", true);
			stub.visitInsn(Opcodes.IRETURN); stub.visitMaxs(0, 0); stub.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }

	@Test void aLambdaThunkDigestsAsTheMethodReferenceItReplaced() {
		byte[] original = probe(true);
		byte[] thunked = new LambdaInvocationThunkInjector().transform(PROBE, original, null);
		assertTrue(node(thunked).methods.stream().anyMatch(m -> m.name.startsWith(LambdaInvocationThunkInjector.PREFIX)), "premise: the injector added a thunk");
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(thunked));
	}

	@Test void aThunkForwardingSomewhereElseIsADifferentShape() {
		byte[] original = probe(true);
		ClassNode thunked = node(new LambdaInvocationThunkInjector().transform(PROBE, original, null));
		MethodNode thunk = thunked.methods.stream().filter(m -> m.name.startsWith(LambdaInvocationThunkInjector.PREFIX)).findFirst().orElseThrow();
		MethodInsnNode call = LambdaInvocationThunkInjector.invocation(thunk);
		call.name = "other";
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(bytes(thunked)));
	}

	@Test void aRecordedGetterDigestsAsTheGetter() {
		byte[] original = probe(true);
		byte[] recorded = new PredicateGetterResultRecorder().transform(PROBE, original, null);
		assertTrue(node(recorded).methods.stream().anyMatch(m -> m.name.equals("getFluidType") && m.maxStack > 1), "premise: the recorder recorded the getter");
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(recorded));
		// What the recorder hands on must still be the returned value: a record that replaced it is not folded.
		ClassNode changed = node(recorded);
		MethodNode getter = changed.methods.stream().filter(m -> m.name.equals("getFluidType")).findFirst().orElseThrow();
		for (var instruction : getter.instructions.toArray()) if (instruction.getOpcode() == Opcodes.DUP) getter.instructions.set(instruction, new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(bytes(changed)));
	}

	@Test void aDefaultDelegateDigestsAsTheInheritedDefault() {
		assertEquals(ForgeTransferShapeAudit.fingerprint(probe(true)), ForgeTransferShapeAudit.fingerprint(probe(false)));
		// A body that does anything besides delegating is not a delegate, so it stays in the digest.
		ClassNode node = node(probe(true));
		MethodNode stub = node.methods.stream().filter(m -> m.name.equals("unusual")).findFirst().orElseThrow();
		stub.instructions.insertBefore(stub.instructions.getLast(), new org.objectweb.asm.tree.InsnNode(Opcodes.INEG));
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(probe(false)), ForgeTransferShapeAudit.fingerprint(bytes(node)));
		// Nor is a delegate to an interface the class does not implement directly.
		ClassNode foreign = node(probe(true));
		foreign.interfaces.clear();
		ClassNode bare = node(probe(false));
		bare.interfaces.clear();
		assertFalse(ForgeTransferShapeAudit.fingerprint(bytes(foreign)).equals(ForgeTransferShapeAudit.fingerprint(bytes(bare))));
	}
}
