/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The native-equivalence proof finds the method a handler's selectors bind as Mixin binds them, and the call its point
 * names as Mixin resolves it, however either is written — an owner prefix, a dotted owner, whitespace, a quantifier, a
 * point without its owner or descriptor that the class the mod was compiled against decides. The fixture is invented: a
 * dial whose final {@code turn} already reads the gauge the way the redirect would have.
 */
@ResourceLock("ModCatalog")
class MixinNativeEquivalenceSpellingTest {
	private static final String DIAL = "spelled/carrier/Dial", GAUGE = "spelled/carrier/Gauge", GUEST = "spelled/tenant/GaugePeek";
	private static final String TURN = "(L" + GAUGE + ";I)I", HANDLER_DESC = "(Ljava/lang/Object;L" + GAUGE + ";I)I";

	@BeforeEach @AfterEach void reset() { MixinNativeEquivalence.reset(); }

	@Test
	void selectorsAndPointsWrittenAnyWayMixinReadsThemProveTheSameMethod() {
		for (List<String> selectors : List.of(List.of("spelled.carrier.Dial.turn"), List.of(" turn "), List.of("turn*"),
				List.of("turn " + TURN), List.of("turn", "L" + DIAL + ";turn" + TURN))) {
			assertNotNull(proof(mixin(selectors, "Lold/Lever;pull()I"), dial(false), null), selectors.toString());
		}
		for (String point : List.of("old.Lever.pull()I", "pull()I", " Lold/Lever; pull ()I ")) {
			assertNotNull(proof(mixin(List.of("turn"), point), dial(false), null), point);
		}
		// Without a descriptor, the call it selects where the handler was written says what it returns.
		for (String point : List.of("Lold/Lever;pull", "pull")) {
			assertNotNull(proof(mixin(List.of("turn"), point), dial(false), owner -> nativeDial("()I")), point);
		}
	}

	@Test
	void anotherOwnerAnAmbiguousNameOrAnotherReturnIsNoProof() {
		assertNull(proof(mixin(List.of("Lspelled/carrier/Knob;turn"), "Lold/Lever;pull()I"), dial(false), null), "another owner: Mixin binds nothing");
		assertNull(proof(mixin(List.of("turn"), "Lold/Lever;pull()I"), dial(true), null), "a bare name two overloads share");
		assertNull(proof(mixin(List.of("turn*"), "Lold/Lever;pull()I"), dial(true), null), "a quantifier binding both overloads");
		assertNull(proof(mixin(List.of("turn"), "old.Lever.pull()J"), dial(false), null), "the point's call returns another type");
		assertNull(proof(mixin(List.of("turn"), "Lold/Lever;pull"), dial(false), null), "no class to read an undescribed point in");
		assertNull(proof(mixin(List.of("turn"), "pull"), dial(false), owner -> nativeDial("()J")), "the call it selects there returns another type");
		assertNull(proof(mixin(List.of("turn"), "pull"), dial(false), owner -> nativeDial(null)), "it selects nothing there");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static String proof(ClassNode mixin, ClassNode target, Function<String, ClassNode> natives) {
		MixinNativeEquivalence.remember(mixin);
		MethodNode handler = mixin.methods.getFirst();
		return MixinNativeEquivalence.proof(mixin.name, handler.name, handler.desc, MixinInstructionFingerprint.hash(handler), target, natives);
	}

	/** A redirect whose handler reads the gauge, both operands captured by index from the dial's own locals. */
	@SuppressWarnings("unchecked")
	private static ClassNode mixin(List<String> selectors, String point) {
		ClassNode node = clazz(GUEST);
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(DIAL))));
		node.visibleAnnotations = List.of(mixin);
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "peek", HANDLER_DESC, null, null);
		handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		handler.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
		handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GAUGE, "read", "(I)I", false));
		handler.instructions.add(new InsnNode(Opcodes.IRETURN));
		AnnotationNode injection = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point));
		injection.values = new ArrayList<>(List.of("method", new ArrayList<>(selectors), "at", at));
		handler.visibleAnnotations = List.of(injection);
		handler.invisibleParameterAnnotations = new List[3];
		handler.invisibleParameterAnnotations[1] = List.of(local(0));
		handler.invisibleParameterAnnotations[2] = List.of(local(1));
		handler.maxLocals = 3;
		handler.maxStack = 2;
		node.methods.add(handler);
		return node;
	}

	private static AnnotationNode local(int index) {
		AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
		local.values = new ArrayList<>(List.of("index", index));
		return local;
	}

	/** The merged dial: {@code turn} returns the gauge's reading; with {@code overload}, a {@code turn(String)} declared first. */
	private static ClassNode dial(boolean overload) {
		ClassNode node = clazz(DIAL);
		if (overload) {
			MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "turn", "(L" + GAUGE + ";II)I", null, null);
			other.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			other.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
			other.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GAUGE, "read", "(I)I", false));
			other.instructions.add(new InsnNode(Opcodes.IRETURN));
			node.methods.add(other);
		}
		MethodNode turn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "turn", TURN, null, null);
		turn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		turn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		turn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GAUGE, "read", "(I)I", false));
		turn.instructions.add(new InsnNode(Opcodes.IRETURN));
		turn.maxLocals = 2;
		turn.maxStack = 2;
		node.methods.add(turn);
		return node;
	}

	/** The dial the mod was compiled against: {@code turn} returns {@code Lever.pull} of {@code desc} (null: no such call). */
	private static ClassNode nativeDial(String desc) {
		ClassNode node = clazz(DIAL);
		MethodNode turn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "turn", TURN, null, null);
		if (desc != null) {
			turn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "old/Lever", "pull", desc, false));
			turn.instructions.add(new InsnNode(desc.endsWith("J") ? Opcodes.L2I : Opcodes.NOP));
		} else {
			turn.instructions.add(new InsnNode(Opcodes.ICONST_0));
		}
		turn.instructions.add(new InsnNode(Opcodes.IRETURN));
		node.methods.add(turn);
		return node;
	}

	private static ClassNode clazz(String name) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = "java/lang/Object";
		return node;
	}
}
