/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;

/**
 * The ordinal hand-off between {@link MixinCameraRollAdapter} and {@link ThinnedCallOrdinals}, which run in that order
 * on the same node. An unrelated guest wraps the third {@code turn(FF)V} of {@code Gizmo.spin}; the carrier dropped the
 * first, so the wrapped call is the merged body's second. The camera pass counts it there (ordinal 1); read again as a
 * native count, ordinal 1 would be the native second call, which is merged ordinal 0 — the wrong call.
 */
class CurrentBodyOrdinalsTest {
	private static final String OWNER = "other/toy/Gizmo";
	private static final String TURN = "L" + OWNER + ";turn(FF)V";
	private static final String GUARD = "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

	@AfterEach void clear() {
		MixinStubRebind.forget();
	}

	@Test void anOrdinalCountedOverTheMergedBodyIsNotTranslatedAgain() {
		ClassNode original = gizmo(1f, 3f, 4f), current = gizmo(3f, 4f), mixin = guest("unrelated/vendor/SpinGuards", 2);
		assertEquals(1, MixinCameraRollAdapter.adapt(mixin, n -> current, (family, n) -> original));
		assertEquals(1, ordinal(mixin, "keepTurning"), "the camera pass counts the wrapped call over the merged body");
		assertTrue(CurrentBodyOrdinals.counted(handler(mixin, "keepTurning")));
		// The pipeline's next pass: the wrap keeps merged ordinal 1; the unrelated @Inject still has its native 2 re-counted.
		assertEquals(1, ThinnedCallOrdinals.adapt(mixin, n -> current, (family, n) -> original));
		assertEquals(1, ordinal(mixin, "keepTurning"), "not translated twice");
		assertEquals(1, ordinal(mixin, "watchTurn"), "a native ordinal in the same mixin is still re-counted");
		// The mark survives the node being written and read again, and makes both passes idempotent.
		ClassNode reread = MixinFit.parse(bytes(mixin));
		MixinStubRebind.noteEcosystem(reread.name, Ecosystem.FABRIC);
		assertEquals(0, MixinCameraRollAdapter.adapt(reread, n -> current, (family, n) -> original));
		assertEquals(0, ThinnedCallOrdinals.adapt(reread, n -> current, (family, n) -> original));
		assertEquals(1, ordinal(reread, "keepTurning"));
		assertEquals(1, ordinal(reread, "watchTurn"));
	}

	/** Without the mark the same merged ordinal is read as a native one and lands on the other call: the failure mode. */
	@Test void anUnmarkedOrdinalIsANativeCount() {
		ClassNode original = gizmo(1f, 3f, 4f), current = gizmo(3f, 4f);
		ClassNode once = guest("unrelated/vendor/SpinGuardsOnce", 1);
		assertEquals(2, ThinnedCallOrdinals.adapt(once, n -> current, (family, n) -> original));
		assertEquals(0, ordinal(once, "keepTurning"), "native 1 is merged 0");
		ClassNode native2 = guest("unrelated/vendor/SpinGuardsNative", 2);
		assertEquals(2, ThinnedCallOrdinals.adapt(native2, n -> current, (family, n) -> original));
		assertEquals(1, ordinal(native2, "keepTurning"), "native 2 is merged 1");
	}

	/**
	 * {@code spin(F)V} calls {@code turn(x, second)} then {@code tick()} once per value — {@code turn(2f, 1f)} for a
	 * second of 1, so that call's operands are its own.
	 */
	private static ClassNode gizmo(float... seconds) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = OWNER;
		node.superName = "java/lang/Object";
		MethodNode spin = new MethodNode(Opcodes.ACC_PUBLIC, "spin", "(F)V", null, null);
		for (float second : seconds) {
			spin.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			if (second == 1f) spin.instructions.add(new InsnNode(Opcodes.FCONST_2));
			else spin.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
			spin.instructions.add(new LdcInsnNode(second));
			spin.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "turn", "(FF)V", false));
			spin.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			spin.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "tick", "()V", false));
		}
		spin.instructions.add(new InsnNode(Opcodes.RETURN));
		spin.maxStack = 3;
		spin.maxLocals = 2;
		node.methods.add(spin);
		for (String[] method : new String[][] {{"turn", "(FF)V"}, {"tick", "()V"}}) {
			MethodNode body = new MethodNode(Opcodes.ACC_PUBLIC, method[0], method[1], null, null);
			body.instructions.add(new InsnNode(Opcodes.RETURN));
			body.maxLocals = Type.getArgumentsAndReturnSizes(method[1]) >> 2;
			node.methods.add(body);
		}
		return node;
	}

	/** A Fabric guest: a condition wrapped around {@code turn} at {@code ordinal}, and an {@code @Inject} before native turn #2. */
	private static ClassNode guest(String name, int ordinal) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = name;
		node.superName = "java/lang/Object";
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(OWNER)))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		MethodNode keep = handler(GUARD, "keepTurning", "(L" + OWNER + ";FF)Z", ordinal);
		keep.instructions.add(new InsnNode(Opcodes.ICONST_1));
		keep.instructions.add(new InsnNode(Opcodes.IRETURN));
		keep.maxStack = 1;
		keep.maxLocals = 4;
		MethodNode watch = handler(INJECT, "watchTurn", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", 2);
		watch.instructions.add(new InsnNode(Opcodes.RETURN));
		watch.maxLocals = 2;
		node.methods.addAll(List.of(keep, watch));
		MixinStubRebind.noteEcosystem(name, Ecosystem.FABRIC);
		return node;
	}

	private static MethodNode handler(String kind, String name, String desc, int ordinal) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", TURN, "ordinal", ordinal));
		AnnotationNode injector = new AnnotationNode(kind);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("spin")), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		return handler;
	}

	private static int ordinal(ClassNode mixin, String name) {
		return (Integer) MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler(mixin, name))).getFirst(), "ordinal");
	}

	private static MethodNode handler(ClassNode mixin, String name) {
		return mixin.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
