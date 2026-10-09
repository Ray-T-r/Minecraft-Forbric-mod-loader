/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * The adapters run inside Mixin's read of every guest mixin, so one that throws costs the mod the whole mixin. A
 * {@code @WrapOperation} whose point names no member — MixinExtras' expression point carries its target in
 * {@code @Definition}s, not in {@code @At.target} — is simply not one of theirs.
 */
class MixinCarrierCallbackAdaptersTest {
	private static final String HOST = "fixture/expr/Host", MIXIN = "fixture/expr/HostMixin";

	@Test
	void aWrapOperationAtAnExpressionPointIsLeftAloneWithoutThrowing() {
		ClassNode mixin = mixin("MIXINEXTRAS:EXPRESSION");
		ClassNode host = host();
		assertEquals(0, assertDoesNotThrow(() -> MixinCarrierCallbackAdapters.adapt(mixin, name -> HOST.equals(name) ? host : null,
				(family, name) -> null)));
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getLast())).getFirst();
		assertEquals("MIXINEXTRAS:EXPRESSION", MixinFit.asString(MixinFit.value(at, "value")), "the mixin reaches Mixin as written");
	}

	@Test
	void aPointWithNoTargetNamesNoMember() {
		assertNull(MixinFit.parseMember(null));
	}

	/** {@code @Mixin(Host.class)} with {@code @WrapOperation(method = "run", at = @At(value))} and no target. */
	private static ClassNode mixin(String point) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, MIXIN, null, "java/lang/Object", null);
		var mixinAnnotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		var targets = mixinAnnotation.visitArray("value");
		targets.visit(null, Type.getObjectType(HOST));
		targets.visitEnd();
		mixinAnnotation.visitEnd();
		var handler = writer.visitMethod(Opcodes.ACC_PRIVATE, "wrap",
				"(Lfixture/expr/Host;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z", null, null);
		var injector = handler.visitAnnotation("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", true);
		var methods = injector.visitArray("method");
		methods.visit(null, "run");
		methods.visitEnd();
		var ats = injector.visitArray("at");
		var at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", point);
		at.visitEnd();
		ats.visitEnd();
		injector.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.ICONST_1);
		handler.visitInsn(Opcodes.IRETURN);
		handler.visitMaxs(1, 3);
		handler.visitEnd();
		writer.visitEnd();
		ClassNode node = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(node, 0);
		return node;
	}

	private static ClassNode host() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = HOST;
		node.superName = "java/lang/Object";
		MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()Z", null, null);
		run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		run.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HOST, "check", "()Z", false));
		run.instructions.add(new InsnNode(Opcodes.IRETURN));
		MethodNode check = new MethodNode(Opcodes.ACC_PUBLIC, "check", "()Z", null, null);
		check.instructions.add(new InsnNode(Opcodes.ICONST_0));
		check.instructions.add(new InsnNode(Opcodes.IRETURN));
		node.methods.addAll(List.of(run, check));
		return node;
	}
}
