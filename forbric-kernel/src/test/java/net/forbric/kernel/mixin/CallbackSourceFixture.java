/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Guest mixin classes for the callback adapter tests, written from scratch: any class and handler name, any selector
 * spelling, any extras, any body — so a test can say "the same problem, written another way" without the sample mod's
 * bytes.
 */
final class CallbackSourceFixture {
	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;", SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
	static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	private final ClassNode node = new ClassNode();

	CallbackSourceFixture(String name, String target) {
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		node.name = name;
		node.superName = "java/lang/Object";
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(target))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
	}

	/** A private instance handler {@code name desc}, annotated {@code injector} with {@code values}, whose body {@code code} writes. */
	Handler handler(String name, String desc, String injector, Object... values) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		AnnotationNode annotation = new AnnotationNode(injector);
		annotation.values = new ArrayList<>(Arrays.asList(values));
		method.visibleAnnotations = new ArrayList<>(List.of(annotation));
		node.methods.add(method);
		return new Handler(method);
	}

	ClassNode build() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ClassNode built = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(built, 0);
		return built;
	}

	/** An {@code @At(value, target)} with any further {@code key, value} pairs. */
	static AnnotationNode at(String value, String target, Object... more) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		if (target != null) { at.values.add("target"); at.values.add(target); }
		at.values.addAll(Arrays.asList(more));
		return at;
	}

	static final class Handler {
		final MethodNode method;
		Handler(MethodNode method) { this.method = method; }

		Handler statik() { method.access |= Opcodes.ACC_STATIC; return this; }

		/** Annotates parameter {@code parameter} with the sugar {@code desc} and its {@code key, value} pairs. */
		@SuppressWarnings("unchecked")
		Handler sugar(int parameter, String desc, Object... values) {
			int count = Type.getArgumentTypes(method.desc).length;
			if (method.invisibleParameterAnnotations == null) method.invisibleParameterAnnotations = new List[count];
			AnnotationNode annotation = new AnnotationNode(desc);
			annotation.values = values.length == 0 ? null : new ArrayList<>(Arrays.asList(values));
			if (method.invisibleParameterAnnotations[parameter] == null) method.invisibleParameterAnnotations[parameter] = new ArrayList<>();
			method.invisibleParameterAnnotations[parameter].add(annotation);
			return this;
		}

		Handler local(int parameter, Object... values) { return sugar(parameter, LOCAL, values); }

		Handler share(int parameter, String name) { return sugar(parameter, SHARE, "value", name); }

		Handler code(Consumer<MethodVisitor> code) {
			method.visitCode();
			code.accept(method);
			method.visitMaxs(0, 0);
			method.visitEnd();
			return this;
		}
	}

	/** Builds {@code new Object[]{ the given parameter slots, in order }} on the stack, javac's way. */
	static void array(MethodVisitor code, int... slots) {
		code.visitIntInsn(Opcodes.BIPUSH, slots.length);
		code.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
		for (int i = 0; i < slots.length; i++) {
			code.visitInsn(Opcodes.DUP);
			code.visitIntInsn(Opcodes.BIPUSH, i);
			code.visitVarInsn(Opcodes.ALOAD, slots[i]);
			code.visitInsn(Opcodes.AASTORE);
		}
	}

	/** Builds the same array into local {@code temp} first, then loads it: how a Kotlin compiler writes {@code arrayOf(...)}. */
	static void arrayInLocal(MethodVisitor code, int temp, int... slots) {
		code.visitIntInsn(Opcodes.BIPUSH, slots.length);
		code.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
		code.visitVarInsn(Opcodes.ASTORE, temp);
		for (int i = 0; i < slots.length; i++) {
			code.visitVarInsn(Opcodes.ALOAD, temp);
			code.visitIntInsn(Opcodes.BIPUSH, i);
			code.visitVarInsn(Opcodes.ALOAD, slots[i]);
			code.visitInsn(Opcodes.AASTORE);
		}
	}

	/** {@code ((Boolean) operation.call(array)).booleanValue()}, the array already on the stack above the operation. */
	static void callBoolean(MethodVisitor code) {
		code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call",
				"([Ljava/lang/Object;)Ljava/lang/Object;", true);
		code.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
		code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
	}
}
