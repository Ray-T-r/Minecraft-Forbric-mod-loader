/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The verdict binds an injector's member selectors the way Mixin will — a dotted owner, an owner prefix, whitespace, a
 * bare name's first declared overload, nothing it inherits, nothing for another owner — and
 * judges a name-only {@code @Inject}'s refusal on that same binding. The fixture is invented: a kiln whose
 * {@code fire()} lights its coal.
 */
class MixinFitSelectorSpellingTest {
	private static final String KILN = "spelled/fit/Kiln", HEARTH = "spelled/fit/Hearth";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

	@Test
	void aSelectorWrittenAnyWayMixinBindsItFitsTheMethodItBinds() {
		for (String selector : List.of("spelled.fit.Kiln.fire()V", "spelled.fit.Kiln.fire", "L" + KILN + ";fire", " fire ", "fire ()V")) {
			MixinFit.Result fit = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin(selector)), classes(kiln()));
			assertEquals(MixinFit.Verdict.FIT, fit.verdict(), selector + ": " + fit.unresolved());
		}
	}

	@Test
	void anotherOwnerOrAnInheritedMethodBindsNothing() {
		for (String selector : List.of("Lspelled/fit/Stove;fire", "spelled.fit.Stove.fire()V")) {
			MixinFit.Result fit = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin(selector)), classes(kiln()));
			assertNotEquals(MixinFit.Verdict.FIT, fit.verdict(), selector + " names another class: Mixin binds nothing in the kiln");
		}
		// The kiln inherits stoke() from its hearth; Mixin injects only into what the target declares.
		MixinFit.Result inherited = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin("stoke")), classes(kiln()));
		assertNotEquals(MixinFit.Verdict.FIT, inherited.verdict(), inherited.unresolved().toString());
	}

	/**
	 * A name binds the first overload, here {@code fire(int)}, and the handler was written for {@code fire()}, which
	 * returns whether it lit: Mixin rejects it however the name is written. A selector naming another owner binds
	 * nothing, which is no rejection; one spelling the descriptor or a quantifier is not a name alone.
	 */
	@Test
	void aNameOnlyRefusalIsJudgedOnTheBindingMixinMakes() {
		for (String selector : List.of("spelled.fit.Kiln.fire", "L" + KILN + ";fire", " fire ")) {
			ClassNode mixin = mixin(selector, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V");
			String why = MixinFit.stillRejected(mixin, mixin.methods.getFirst(), name -> name.equals(KILN) ? returningKiln() : null);
			assertNotNull(why, selector);
			assertTrue(why.contains("binds fire(I)V"), why);
		}
		for (String selector : List.of("Lspelled/fit/Stove;fire", "fire()Z", "fire*")) {
			ClassNode mixin = mixin(selector, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V");
			assertNull(MixinFit.stillRejected(mixin, mixin.methods.getFirst(), name -> name.equals(KILN) ? returningKiln() : null), selector);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static Function<String, byte[]> classes(ClassNode kiln) {
		Map<String, byte[]> bytes = Map.of(KILN + ".class", StagedFabricMixinFixture.bytes(kiln), HEARTH + ".class", StagedFabricMixinFixture.bytes(hearth()));
		return bytes::get;
	}

	/** A plain HEAD callback written for {@code fire()}, selected by {@code selector}. */
	private static ClassNode mixin(String selector) {
		return mixin(selector, "(" + CI + ")V");
	}

	private static ClassNode mixin(String selector, String handlerDesc) {
		ClassNode mixin = node("spelled/tenant/KilnWatch", "java/lang/Object");
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(KILN)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "onFire", handlerDesc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods.add(handler);
		return mixin;
	}

	/** {@code fire()} lights the coal. */
	private static ClassNode kiln() {
		ClassNode kiln = node(KILN, HEARTH);
		kiln.methods.add(body("fire", "()V"));
		return kiln;
	}

	/** {@code fire(int)} declared first, then {@code fire()}, which returns whether it lit. */
	private static ClassNode returningKiln() {
		ClassNode kiln = node(KILN, HEARTH);
		kiln.methods.add(body("fire", "(I)V"));
		MethodNode lit = new MethodNode(Opcodes.ACC_PUBLIC, "fire", "()Z", null, null);
		lit.instructions.add(new InsnNode(Opcodes.ICONST_1));
		lit.instructions.add(new InsnNode(Opcodes.IRETURN));
		kiln.methods.add(lit);
		return kiln;
	}

	private static ClassNode hearth() {
		ClassNode hearth = node(HEARTH, "java/lang/Object");
		hearth.methods.add(body("stoke", "()V"));
		return hearth;
	}

	private static MethodNode body(String name, String desc) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "spelled/fit/Coal", "light", "()V", false));
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		return method;
	}

	private static ClassNode node(String name, String superName) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = superName;
		return node;
	}
}
