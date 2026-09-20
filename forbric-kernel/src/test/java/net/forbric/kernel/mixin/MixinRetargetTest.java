/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** R1 over synthetic classes shaped like the merged {@code SimpleContainer.setItem} pair. */
class MixinRetargetTest {
	private static final String TARGET = "net/example/Container";
	private static final String STACK = "Lnet/example/ItemStack;";
	private static final String STUB = "setItem(I" + STACK + ")V";
	private static final String DELEGATE = "setItem(I" + STACK + "Z)V";
	private static final String AT_SET_CHANGED = "L" + TARGET + ";setChanged()V";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

	@AfterEach
	void reset() {
		System.clearProperty(MixinRetarget.PROPERTY);
		MixinRetarget.reset();
	}

	/** The merged shape: a 2-arg stub delegating to the 3-arg body that calls setChanged. */
	private static byte[] target(boolean branchInStub) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
		MethodVisitor stub = cw.visitMethod(Opcodes.ACC_PUBLIC, "setItem", "(I" + STACK + ")V", null, null);
		stub.visitCode();
		if (branchInStub) {
			Label skip = new Label();
			stub.visitVarInsn(Opcodes.ILOAD, 1);
			stub.visitJumpInsn(Opcodes.IFLT, skip);
			stub.visitVarInsn(Opcodes.ALOAD, 0);
			stub.visitVarInsn(Opcodes.ILOAD, 1);
			stub.visitVarInsn(Opcodes.ALOAD, 2);
			stub.visitInsn(Opcodes.ICONST_0);
			stub.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "setItem", "(I" + STACK + "Z)V", false);
			stub.visitLabel(skip);
		} else {
			stub.visitVarInsn(Opcodes.ALOAD, 0);
			stub.visitVarInsn(Opcodes.ILOAD, 1);
			stub.visitVarInsn(Opcodes.ALOAD, 2);
			stub.visitInsn(Opcodes.ICONST_0);
			stub.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "setItem", "(I" + STACK + "Z)V", false);
		}
		stub.visitInsn(Opcodes.RETURN);
		stub.visitMaxs(0, 0);
		stub.visitEnd();
		MethodVisitor body = cw.visitMethod(Opcodes.ACC_PUBLIC, "setItem", "(I" + STACK + "Z)V", null, null);
		body.visitCode();
		body.visitVarInsn(Opcodes.ALOAD, 0);
		body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "setChanged", "()V", false);
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();
		MethodVisitor changed = cw.visitMethod(Opcodes.ACC_PUBLIC, "setChanged", "()V", null, null);
		changed.visitCode();
		changed.visitInsn(Opcodes.RETURN);
		changed.visitMaxs(0, 0);
		changed.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A mixin with one injector of {@code kind} on {@code selector} at INVOKE setChanged, with the given handler
	 * descriptor; {@code localParam} marks the parameter index to annotate {@code @Local}, or -1.
	 */
	private static byte[] mixin(String kind, String selector, String handlerDesc, int localParam) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/ContainerMixin", null, "java/lang/Object", null);
		AnnotationVisitor m = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = m.visitArray("value");
		targets.visit(null, Type.getObjectType(TARGET));
		targets.visitEnd();
		m.visitEnd();
		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", handlerDesc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(kind, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, selector);
		method.visitEnd();
		AnnotationVisitor at = inj.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", AT_SET_CHANGED);
		at.visitEnd();
		inj.visitEnd();
		if (localParam >= 0) h.visitParameterAnnotation(localParam, MixinRetarget.LOCAL_SUGAR, true).visitEnd();
		h.visitCode();
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 8);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(byte[] targetBytes) {
		Map<String, byte[]> classes = Map.of(TARGET + ".class", targetBytes);
		return classes::get;
	}

	private static String selectorOf(ClassNode node) {
		for (MethodNode m : node.methods) {
			AnnotationNode a = MixinFit.injectorOf(m);
			if (a != null) return MixinFit.stringList(MixinFit.value(a, "method")).get(0);
		}
		return null;
	}

	@Test
	void aRedirectBoundToTheStubIsReboundToTheDelegateAndThenFits() {
		byte[] targetBytes = target(false);
		byte[] mixinBytes = mixin(REDIRECT, STUB, "(L" + TARGET + ";)V", -1);
		Function<String, byte[]> resolver = resolver(targetBytes);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixinBytes, resolver).verdict(), "premise");

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals(STUB, plan.rewrites().get(0).from());
		assertEquals(DELEGATE, plan.rewrites().get(0).to());

		byte[] rewritten = MixinRetarget.rewritten(mixinBytes, plan);
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(rewritten, resolver).verdict());
		assertEquals(DELEGATE, selectorOf(MixinFit.parse(rewritten)));
	}

	@Test
	void theServedClassNodeCarriesTheRewriteOnlyWhileAPlanIsRemembered() {
		byte[] mixinBytes = mixin(WRAP, STUB, "(L" + TARGET + ";" + OPERATION + ")V", -1);
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver(target(false)));
		assertEquals(1, plan.rewrites().size(), "@WrapOperation is @At-driven: movable");

		ClassNode fresh = MixinFit.parse(mixinBytes);
		assertEquals(0, MixinRetarget.applyRemembered("test.ContainerMixin", fresh), "nothing remembered yet");
		assertEquals(STUB, selectorOf(fresh));

		MixinRetarget.remember(plan);
		ClassNode served = MixinFit.parse(mixinBytes);
		assertEquals(1, MixinRetarget.applyRemembered("test.ContainerMixin", served), "the dotted name Mixin uses");
		assertEquals(DELEGATE, selectorOf(served));
	}

	@Test
	void anInjectCapturingTheStubsArgumentsIsNotMoved() {
		// (I, ItemStack, CallbackInfo) is the STUB's parameter list; the delegate takes (I, ItemStack, Z).
		byte[] mixinBytes = mixin(INJECT, STUB, "(I" + STACK + MixinRetarget.CALLBACK_INFO + ")V", -1);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver(target(false))).isEmpty(),
				"Mixin's capture wants the target's parameters exactly, and they differ");
		// Capturing nothing, or exactly the delegate's parameters, is movable.
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(INJECT, STUB, "(" + MixinRetarget.CALLBACK_INFO + ")V", -1)),
				resolver(target(false))).rewrites().size());
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(INJECT, STUB, "(I" + STACK + "Z" + MixinRetarget.CALLBACK_INFO + ")V", -1)),
				resolver(target(false))).rewrites().size());
	}

	@Test
	void aLocalSugarOfATypeTheDelegateDoesNotHaveIsNotMoved() {
		// @Local String: the delegate's parameters are (I, ItemStack, Z) — no String to capture.
		byte[] mixinBytes = mixin(WRAP, STUB, "(L" + TARGET + ";" + OPERATION + "Ljava/lang/String;)V", 2);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver(target(false))).isEmpty());
		// @Local boolean IS among the delegate's parameters.
		byte[] ok = mixin(WRAP, STUB, "(L" + TARGET + ";" + OPERATION + "Z)V", 2);
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(ok), resolver(target(false))).rewrites().size());
	}

	@Test
	void aStubWithABranchIsABodyOfItsOwn() {
		byte[] mixinBytes = mixin(REDIRECT, STUB, "(L" + TARGET + ";)V", -1);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver(target(true))).isEmpty());
	}

	@Test
	void aNameOnlySelectorIsLeftToOverloadResolution() {
		byte[] mixinBytes = mixin(REDIRECT, "setItem", "(L" + TARGET + ";)V", -1);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver(target(false))).isEmpty());
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixinBytes, resolver(target(false))).verdict(),
				"a bare name already binds to every overload");
	}

	@Test
	void switchedOffNoPlanIsComputed() {
		System.setProperty(MixinRetarget.PROPERTY, "off");
		byte[] mixinBytes = mixin(REDIRECT, STUB, "(L" + TARGET + ";)V", -1);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixinBytes), resolver(target(false))).isEmpty());
	}

	@Test
	void argumentConstructionInTheStubIsStillAStub() {
		// vanillaBurnTimes(Provider, Flags, I) = new Builder(provider, flags); iload 2; invokestatic vanillaBurnTimes(Builder, I)
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		String owner = "net/example/FuelValues";
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor stub = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "vanillaBurnTimes",
				"(Ljava/lang/Object;Ljava/lang/Object;I)L" + owner + ";", null, null);
		stub.visitCode();
		stub.visitTypeInsn(Opcodes.NEW, owner + "$Builder");
		stub.visitInsn(Opcodes.DUP);
		stub.visitVarInsn(Opcodes.ALOAD, 0);
		stub.visitVarInsn(Opcodes.ALOAD, 1);
		stub.visitMethodInsn(Opcodes.INVOKESPECIAL, owner + "$Builder", "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
		stub.visitVarInsn(Opcodes.ILOAD, 2);
		stub.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "vanillaBurnTimes", "(L" + owner + "$Builder;I)L" + owner + ";", false);
		stub.visitInsn(Opcodes.ARETURN);
		stub.visitMaxs(0, 0);
		stub.visitEnd();
		MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "vanillaBurnTimes",
				"(L" + owner + "$Builder;I)L" + owner + ";", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.ACONST_NULL);
		body.visitInsn(Opcodes.ARETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();
		cw.visitEnd();
		ClassNode node = MixinFit.parse(cw.toByteArray());
		MethodNode stubNode = node.methods.stream().filter(m -> m.desc.startsWith("(Ljava/lang/Object;")).findFirst().orElseThrow();
		MethodNode delegate = MixinRetarget.delegateOf(node, stubNode);
		assertTrue(delegate != null && delegate.desc.startsWith("(L" + owner + "$Builder;I)"), "NEW/DUP/<init> before the delegate call is argument construction");
	}
}
