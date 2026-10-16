package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 *
 * <p>The delegate fold once took every pure {@code I.super.m()} to a direct interface on sight, and so certified
 * classes that answer differently from the reviewed one, the last of them a root method that throws
 * AbstractMethodError. Each such class is here, run, and refused.
 */
class ForgeTransferShapeAuditFoldTest {
	private static final String PROBE = "fold/Probe";
	private static final String FLUID_TYPE = "L" + ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE) + ";";

	/** A class with a method reference to its own method and a fluid-type getter. */
	private static byte[] probe() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PROBE, null, "java/lang/Object", null);
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
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }

	@Test void aLambdaThunkDigestsAsTheMethodReferenceItReplaced() {
		byte[] original = probe();
		byte[] thunked = new LambdaInvocationThunkInjector().transform(PROBE, original, null);
		assertTrue(node(thunked).methods.stream().anyMatch(m -> m.name.startsWith(LambdaInvocationThunkInjector.PREFIX)), "premise: the injector added a thunk");
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(thunked));
	}

	@Test void aThunkForwardingSomewhereElseIsADifferentShape() {
		byte[] original = probe();
		ClassNode thunked = node(new LambdaInvocationThunkInjector().transform(PROBE, original, null));
		MethodNode thunk = thunked.methods.stream().filter(m -> m.name.startsWith(LambdaInvocationThunkInjector.PREFIX)).findFirst().orElseThrow();
		MethodInsnNode call = LambdaInvocationThunkInjector.invocation(thunk);
		call.name = "other";
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(bytes(thunked)));
	}

	@Test void aRecordedGetterDigestsAsTheGetter() {
		byte[] original = probe();
		byte[] recorded = new PredicateGetterResultRecorder().transform(PROBE, original, null);
		assertTrue(node(recorded).methods.stream().anyMatch(m -> m.name.equals("getFluidType") && m.maxStack > 1), "premise: the recorder recorded the getter");
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(recorded));
		// What the recorder hands on must still be the returned value: a record that replaced it is not folded.
		ClassNode changed = node(recorded);
		MethodNode getter = changed.methods.stream().filter(m -> m.name.equals("getFluidType")).findFirst().orElseThrow();
		for (var instruction : getter.instructions.toArray()) if (instruction.getOpcode() == Opcodes.DUP) getter.instructions.set(instruction, new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(bytes(changed)));
	}

	/*
	 * Interface-default delegates. The fold asks NativeMergeShapeRepair's own proof which delegates are the inherited
	 * default, so each case below is checked two ways: the fold and the repair agree, and, for every delegate the fold
	 * must leave in the digest, the two classes it would otherwise equate are shown to answer differently when run.
	 */

	@TempDir Path work;

	/** {@code leaf} with a pure {@code iface.super.unusual()} added, as the merge (or any transformer) would add it. */
	private static byte[] withDelegate(Map<String, byte[]> classes, String leaf, String iface) {
		ClassNode node = node(classes.get(leaf));
		node.methods.removeIf(m -> m.name.equals("unusual"));
		NativeMergeFixtures.delegate(node, iface, "unusual", "()I");
		return NativeMergeFixtures.bytes(node);
	}

	/** The repair's proof over {@code current}, with {@code natives} as both carriers' native classes. */
	private static NativeMergeShapeRepair proof(Map<String, byte[]> current, Map<String, byte[]> natives) throws Exception {
		return NativeMergeFixtures.repair(current, NativeMergeFixtures.references(natives, natives));
	}

	/** What {@code leaf}'s {@code unusual()} answers when the JVM runs {@code bytes} among {@code classes}. */
	private static Object run(Map<String, byte[]> classes, String leaf, byte[] bytes) throws Throwable {
		Map<String, byte[]> loaded = new HashMap<>(classes);
		loaded.put(leaf, bytes);
		Class<?> type = InjectorExecution.load(loaded).loadClass(leaf.replace('/', '.'));
		try {
			return type.getMethod("unusual").invoke(type.getConstructor().newInstance());
		} catch (java.lang.reflect.InvocationTargetException thrown) {
			if (thrown.getCause() instanceof IncompatibleClassChangeError error) return error.getClass().getSimpleName();
			throw thrown.getCause();
		}
	}

	/**
	 * Whether {@code delegated} digests as {@code without}, the same class lacking the delegate. The merged-base repair
	 * must drop that delegate exactly when the fold folds it: the fold is the repair's proof, not a second opinion.
	 */
	private static boolean folds(byte[] without, byte[] delegated, NativeMergeShapeRepair proof) {
		boolean folded = ForgeTransferShapeAudit.fingerprint(delegated, proof).equals(ForgeTransferShapeAudit.fingerprint(without, proof));
		assertEquals(proof.dropDefaultStubs(node(delegated)), folded, "the fold and the merged-base repair disagree");
		return folded;
	}

	@Test void aDelegateTheRepairProvesDigestsAsTheInheritedDefault() throws Throwable {
		var classes = InjectorExecution.compile(work, Map.of(
				"proved.Trait", "package proved; public interface Trait { default int unusual(){return 2;} }",
				"proved.Leaf", "package proved; public class Leaf implements Trait { }"));
		byte[] delegated = withDelegate(classes, "proved/Leaf", "proved/Trait");
		assertEquals(run(classes, "proved/Leaf", classes.get("proved/Leaf")), run(classes, "proved/Leaf", delegated), "premise: one dispatch target");
		assertTrue(folds(classes.get("proved/Leaf"), delegated, proof(classes, classes)));
		// Without a hierarchy there is no proof, and the delegate stays in the digest.
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(classes.get("proved/Leaf")), ForgeTransferShapeAudit.fingerprint(delegated));
		// A body that does anything besides delegating is not a delegate, so it stays in the digest.
		ClassNode changed = node(delegated);
		MethodNode stub = changed.methods.stream().filter(m -> m.name.equals("unusual")).findFirst().orElseThrow();
		stub.instructions.insertBefore(stub.instructions.getLast(), new org.objectweb.asm.tree.InsnNode(Opcodes.INEG));
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(classes.get("proved/Leaf"), proof(classes, classes)),
				ForgeTransferShapeAudit.fingerprint(bytes(changed), proof(classes, classes)));
	}

	@Test void aDelegateBothCarriersDeclaredIsNotTheMergesToFold() throws Throwable {
		// Neither native class may declare it: one that does was written on purpose, and the repair keeps it.
		var classes = InjectorExecution.compile(work, Map.of(
				"intended.Trait", "package intended; public interface Trait { default int unusual(){return 2;} }",
				"intended.Leaf", "package intended; public class Leaf implements Trait { }"));
		byte[] delegated = withDelegate(classes, "intended/Leaf", "intended/Trait");
		Map<String, byte[]> natives = new HashMap<>(classes);
		natives.put("intended/Leaf", delegated);
		assertFalse(folds(classes.get("intended/Leaf"), delegated, proof(classes, natives)));
	}

	@Test void aDelegateThatBypassesASuperclassImplementationIsRefused() throws Throwable {
		var classes = InjectorExecution.compile(work, Map.of(
				"bypass.Base", "package bypass; public class Base { public int unusual(){return 1;} }",
				"bypass.Trait", "package bypass; public interface Trait { default int unusual(){return 2;} }",
				"bypass.Leaf", "package bypass; public class Leaf extends Base implements Trait { }"));
		byte[] delegated = withDelegate(classes, "bypass/Leaf", "bypass/Trait");
		assertEquals(List.of(1, 2), List.of(run(classes, "bypass/Leaf", classes.get("bypass/Leaf")), run(classes, "bypass/Leaf", delegated)));
		assertFalse(folds(classes.get("bypass/Leaf"), delegated, proof(classes, classes)));
	}

	@Test void aDelegateToALessSpecificDefaultIsRefused() throws Throwable {
		var classes = new HashMap<>(InjectorExecution.compile(work, Map.of(
				"vague.Root", "package vague; public interface Root { default int unusual(){return 2;} }",
				"vague.Refined", "package vague; public interface Refined extends Root { default int unusual(){return 3;} }",
				"vague.Leaf", "package vague; public class Leaf implements Refined { }")));
		ClassNode leaf = node(classes.get("vague/Leaf"));
		leaf.interfaces.add(0, "vague/Root"); // a direct interface, so Root.super is a legal call; javac would not write it
		classes.put("vague/Leaf", bytes(leaf));
		byte[] delegated = withDelegate(classes, "vague/Leaf", "vague/Root");
		assertEquals(List.of(3, 2), List.of(run(classes, "vague/Leaf", classes.get("vague/Leaf")), run(classes, "vague/Leaf", delegated)));
		assertFalse(folds(classes.get("vague/Leaf"), delegated, proof(classes, classes)));
	}

	@Test void eitherSideOfAConflictingPairIsRefused() throws Throwable {
		var classes = InjectorExecution.compile(work, Map.of(
				"pair.Left", "package pair; public interface Left { default int unusual(){return 2;} }",
				"pair.Right", "package pair; public interface Right { default int unusual(){return 5;} }",
				"pair.Leaf", "package pair; public class Leaf implements Left, Right { public int unusual(){return Left.super.unusual();} }"));
		byte[] left = withDelegate(classes, "pair/Leaf", "pair/Left"), right = withDelegate(classes, "pair/Leaf", "pair/Right");
		ClassNode bare = node(classes.get("pair/Leaf"));
		bare.methods.removeIf(m -> m.name.equals("unusual"));
		byte[] unsettled = bytes(bare);
		assertEquals(List.of(2, 5, "IncompatibleClassChangeError"),
				List.of(run(classes, "pair/Leaf", left), run(classes, "pair/Leaf", right), run(classes, "pair/Leaf", unsettled)));
		// As the merge would meet it: neither native class declares the method, so only the conflict decides.
		Map<String, byte[]> natives = new HashMap<>(classes);
		natives.put("pair/Leaf", unsettled);
		NativeMergeShapeRepair proof = proof(classes, natives);
		assertFalse(folds(unsettled, left, proof));
		assertFalse(folds(unsettled, right, proof));
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(left, proof), ForgeTransferShapeAudit.fingerprint(right, proof));
	}

	@Test void aDelegateToAnAbstractDeclarationIsRefused() throws Throwable {
		var classes = InjectorExecution.compile(work, Map.of(
				"hollow.Base", "package hollow; public interface Base { int unusual(); }",
				"hollow.Filled", "package hollow; public interface Filled extends Base { default int unusual(){return 7;} }",
				"hollow.Empty", "package hollow; public interface Empty extends Base { }",
				"hollow.Leaf", "package hollow; public class Leaf implements Filled, Empty { }"));
		byte[] delegated = withDelegate(classes, "hollow/Leaf", "hollow/Empty");
		assertEquals(List.of(7, "AbstractMethodError"), List.of(run(classes, "hollow/Leaf", classes.get("hollow/Leaf")), run(classes, "hollow/Leaf", delegated)));
		assertFalse(folds(classes.get("hollow/Leaf"), delegated, proof(classes, classes)));
	}
}
