/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

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
 * The native-equivalence proof is asked when the target class is defined, after Mixin has applied the mixin; Mixin's
 * {@code MixinTargetContext.prepareInjections} takes each injector annotation off the handler node it was handed. The
 * proof reads the handler as it was remembered — which method its selectors bind, what its point names — not the node
 * Mixin has since consumed. The fixture is invented: a lantern whose {@code glow} reads a wick's brightness through a
 * redirected call.
 */
@ResourceLock("ModCatalog")
class MixinNativeEquivalenceRememberedTest {
	private static final String WICK = Type.getInternalName(Wick.class), WICK_TYPE = "L" + WICK + ";";
	private static final String LANTERN = "remembered/carrier/Lantern", GUEST = "remembered/tenant/GlowMixin";
	private static final String HANDLER = "brighten", HANDLER_DESC = "(Ljava/lang/Object;" + WICK_TYPE + "I)I", HOST_DESC = "(" + WICK_TYPE + "I)I";

	@BeforeEach @AfterEach void reset() { MixinNativeEquivalence.reset(); }

	@Test
	void theProofReadsTheHandlerAsRememberedAfterMixinConsumedIt() {
		for (String selector : List.of("glow", "glow" + HOST_DESC, "L" + LANTERN + ";glow", "remembered.carrier.Lantern.glow" + HOST_DESC)) {
			MixinNativeEquivalence.reset();
			ClassNode mixin = mixin(selector);
			MethodNode handler = mixin.methods.getFirst();
			String fingerprint = MixinInstructionFingerprint.hash(handler);
			MixinNativeEquivalence.remember(mixin);
			consumeAsMixinDoes(handler);
			assertNotNull(MixinNativeEquivalence.proof(mixin.name, HANDLER, HANDLER_DESC, fingerprint, lantern()),
					selector + ": the remembered handler binds glow, whose call already is the redirected operation");
		}
	}

	@Test
	void whatTheHandlerBecomesAfterItIsRememberedProvesNothing() {
		// The look-alike: remembered binding another method, the node later edited to bind glow. The proof stays with
		// what was remembered, so it does not borrow the later edit.
		ClassNode mixin = mixin("flicker");
		MethodNode handler = mixin.methods.getFirst();
		String fingerprint = MixinInstructionFingerprint.hash(handler);
		MixinNativeEquivalence.remember(mixin);
		assertNull(MixinNativeEquivalence.proof(mixin.name, HANDLER, HANDLER_DESC, fingerprint, lantern()), "flicker is not glow");
		((AnnotationNode) handler.visibleAnnotations.getFirst()).values.set(1, List.of("glow"));
		assertNull(MixinNativeEquivalence.proof(mixin.name, HANDLER, HANDLER_DESC, fingerprint, lantern()),
				"the handler as remembered still binds flicker");
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** What Mixin does to the node it was handed: the injector annotation removed, the handler renamed for merging. */
	private static void consumeAsMixinDoes(MethodNode handler) {
		handler.visibleAnnotations = new ArrayList<>();
		handler.name = "redirect$zza000$tenant$" + handler.name;
	}

	@SuppressWarnings("unchecked")
	private static ClassNode mixin(String selector) {
		ClassNode node = clazz(GUEST);
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(LANTERN))));
		node.visibleAnnotations = List.of(mixin);
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, HANDLER, HANDLER_DESC, null, null);
		handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		handler.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
		handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WICK, "shine", "(I)I", false));
		handler.instructions.add(new InsnNode(Opcodes.IRETURN));
		AnnotationNode injection = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lremembered/old/Oil;level()I"));
		injection.values = new ArrayList<>(List.of("method", List.of(selector), "at", at));
		handler.visibleAnnotations = new ArrayList<>(List.of(injection));
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

	/** {@code glow(wick, n)} returns {@code wick.shine(n)}; {@code flicker} does nothing of the kind. */
	private static ClassNode lantern() {
		ClassNode node = clazz(LANTERN);
		MethodNode glow = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glow", HOST_DESC, null, null);
		glow.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		glow.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		glow.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WICK, "shine", "(I)I", false));
		glow.instructions.add(new InsnNode(Opcodes.IRETURN));
		glow.maxLocals = 2;
		glow.maxStack = 2;
		node.methods.add(glow);
		MethodNode flicker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "flicker", HOST_DESC, null, null);
		flicker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		flicker.instructions.add(new InsnNode(Opcodes.IRETURN));
		flicker.maxLocals = 2;
		flicker.maxStack = 1;
		node.methods.add(flicker);
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

	public static class Wick {
		public int shine(int amount) { return amount; }
	}
}
