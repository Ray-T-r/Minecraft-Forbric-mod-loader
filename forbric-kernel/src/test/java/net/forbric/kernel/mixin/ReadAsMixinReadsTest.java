/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The readings the anchor adapters now share instead of comparing spellings: which method a selector reaches and whether
 * that hangs on declaration order ({@link MixinTargetSelectors#reach}, {@link MixinTargetSelectors#unambiguous}), what a
 * selector names when no class is at hand ({@link MixinTargetSelectors#spellsOnly}, {@link MixinCallbackShape#selectsMember}),
 * and the one member a point names in the method it was written for ({@link MixinCallbackShape#member},
 * {@link MixinCallbackShape#namesInEach}). Every name here is made up.
 */
class ReadAsMixinReadsTest {
	private static final String KILN = "org/example/pottery/Kiln", GLAZE = "org/example/pottery/Glaze", CLAY = "org/example/pottery/Clay";

	/** {@code Kiln}: fire(), fire(I), fire(J), a static vent() — in that order. */
	private static ClassNode kiln() {
		ClassNode kiln = new ClassNode();
		kiln.name = KILN;
		kiln.methods.add(method(Opcodes.ACC_PUBLIC, "fire", "()V"));
		kiln.methods.add(method(Opcodes.ACC_PUBLIC, "fire", "(I)V"));
		kiln.methods.add(method(Opcodes.ACC_PUBLIC, "fire", "(J)V"));
		kiln.methods.add(method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "vent", "()V"));
		return kiln;
	}

	private static MethodNode method(int access, String name, String desc) {
		MethodNode m = new MethodNode(access, name, desc, null, null);
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static MethodNode handler(Object selectors) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "glaze", "()V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", selectors, "at", new ArrayList<>(List.of(CallbackSourceFixture.at("HEAD", null)))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		return handler;
	}

	@Test void aSelectorReachesWhatMixinMatchesAndSaysWhetherOrderDecides() {
		ClassNode kiln = kiln();
		MixinTargetSelectors.Reach bare = MixinTargetSelectors.reach(handler(List.of("fire")), kiln).getFirst();
		assertEquals(3, bare.matched().size(), "a bare name matches every overload");
		assertTrue(bare.single(), "and binds one of them: the first declared");
		assertNull(bare.spelled());
		MixinTargetSelectors.Reach pinned = MixinTargetSelectors.reach(handler(List.of(" Lorg/example/pottery/Kiln; fire (I)V")), kiln).getFirst();
		assertEquals(List.of("(I)V"), pinned.matched().stream().map(m -> m.desc).toList());
		assertEquals("fire(I)V", pinned.spelled(), "whitespace and an owner prefix name the same method");
		MixinTargetSelectors.Reach dotted = MixinTargetSelectors.reach(handler(List.of("org.example.pottery.Kiln.fire(J)V")), kiln).getFirst();
		assertEquals(List.of("(J)V"), dotted.matched().stream().map(m -> m.desc).toList());
		MixinTargetSelectors.Reach every = MixinTargetSelectors.reach(handler(List.of("fire*")), kiln).getFirst();
		assertEquals(3, every.matched().size());
		assertFalse(every.single(), "a quantifier binds them all");
		ClassNode empty = new ClassNode();
		empty.name = KILN;
		MixinTargetSelectors.Reach undeclared = MixinTargetSelectors.reach(handler(List.of("fire(J)V")), empty).getFirst();
		assertTrue(undeclared.matched().isEmpty());
		assertEquals("fire(J)V", undeclared.spelled(), "in a class that does not declare it, a pinned selector still names its member");
		assertNull(MixinTargetSelectors.reach(handler(List.of("Lorg/example/pottery/Glaze;fire()V")), kiln), "another owner: Mixin refuses it");
	}

	@Test void onlyASelectorWhoseBindingDoesNotHangOnDeclarationOrderIsUnambiguous() {
		ClassNode kiln = kiln();
		assertNull(MixinTargetSelectors.unambiguous(handler(List.of("fire")), kiln), "a bare name shared by three overloads");
		assertEquals("(I)V", MixinTargetSelectors.unambiguous(handler(List.of("fire(I)V")), kiln).desc);
		assertEquals("(I)V", MixinTargetSelectors.unambiguous(handler(List.of("fire(I)V", "org.example.pottery.Kiln.fire(I)V")), kiln).desc,
				"two spellings of one method");
		MethodNode staticHandler = handler(List.of("vent"));
		staticHandler.access |= Opcodes.ACC_STATIC;
		assertEquals("vent", MixinTargetSelectors.unambiguous(staticHandler, kiln).name, "a name only one method carries");
		assertNull(MixinTargetSelectors.unambiguous(handler(List.of("fire(I)V", "fire(J)V")), kiln), "two methods");
	}

	@Test void withoutAClassASelectorNamesOnlyWhatItsParseAllows() {
		String owner = KILN;
		assertTrue(MixinTargetSelectors.spellsOnly(handler(List.of("fire")), owner, "fire(I)V"), "a bare name, as before: the class would decide");
		assertTrue(MixinTargetSelectors.spellsOnly(handler(List.of("fire(I)V")), owner, "fire(I)V"));
		assertTrue(MixinTargetSelectors.spellsOnly(handler(List.of("Lorg/example/pottery/Kiln;fire(I)V", " org.example.pottery.Kiln . fire ")), owner, "fire(I)V"));
		assertFalse(MixinTargetSelectors.spellsOnly(handler(List.of("fire(J)V")), owner, "fire(I)V"), "another overload");
		assertFalse(MixinTargetSelectors.spellsOnly(handler(List.of("Lorg/example/pottery/Glaze;fire(I)V")), owner, "fire(I)V"), "another owner");
		assertFalse(MixinTargetSelectors.spellsOnly(handler(List.of("fire*")), owner, "fire(I)V"), "a quantifier binds more");
		assertFalse(MixinTargetSelectors.spellsOnly(handler(List.of("fire", "vent")), owner, "fire(I)V"), "and another method");
		// With the class at hand, the class decides: a bare name binds the first declared overload.
		assertFalse(MixinCallbackShape.selectsMember(handler(List.of("fire")), kiln(), owner, "fire(I)V"));
		assertTrue(MixinCallbackShape.selectsMember(handler(List.of("fire")), kiln(), owner, "fire()V"));
		assertTrue(MixinCallbackShape.selectsMember(handler(List.of("fire")), null, owner, "fire(I)V"));
	}

	/** {@code bake()}: Clay.harden(I), then each of {@code more}, then a read of Glaze.SHEEN. */
	private static MethodNode bake(AbstractInsn... more) {
		MethodNode bake = new MethodNode(Opcodes.ACC_PUBLIC, "bake", "()V", null, null);
		bake.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLAY, "harden", "(I)V", false));
		for (AbstractInsn insn : more) bake.instructions.add(insn.node());
		bake.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, GLAZE, "SHEEN", "I"));
		bake.instructions.add(new InsnNode(Opcodes.RETURN));
		return bake;
	}
	private record AbstractInsn(org.objectweb.asm.tree.AbstractInsnNode node) { }
	private static AbstractInsn call(String owner, String name, String desc) {
		return new AbstractInsn(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
	}

	@Test void aPointNamesTheOneMemberItSelectsInTheMethodItWasWrittenFor() {
		String harden = "L" + CLAY + ";harden(I)V", sheen = "L" + GLAZE + ";SHEEN:I";
		assertEquals(harden, MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", " org.example.pottery.Clay . harden ( I ) V "), null),
				"spelled in full, the body is not needed");
		assertEquals(harden, MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", "harden(I)V"), bake()));
		assertEquals(harden, MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", "Lorg/example/pottery/Clay;harden"), bake()));
		assertEquals(sheen, MixinCallbackShape.member(CallbackSourceFixture.at("FIELD", "SHEEN"), bake()), "a field, by name only");
		assertNull(MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", "harden(I)V"), null), "no owner and no body");
		assertNull(MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", "harden(I)V"), bake(call(GLAZE, "harden", "(I)V"))),
				"another owner's harden(I) there too");
		assertNull(MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", "Lorg/example/pottery/Clay;harden"), bake(call(CLAY, "harden", "(J)V"))),
				"another overload there too");
		assertNull(MixinCallbackShape.member(CallbackSourceFixture.at("INVOKE", "temper(I)V"), bake()), "nothing selected");
	}

	@Test void aPointOfAnInjectorBindingSeveralMethodsMustNameTheMemberInEach() {
		String harden = "L" + CLAY + ";harden(I)V";
		AnnotationNode bare = CallbackSourceFixture.at("INVOKE", "harden(I)V");
		assertTrue(MixinCallbackShape.namesInEach(bare, harden, List.of(bake(), bake())));
		assertFalse(MixinCallbackShape.namesInEach(bare, harden, List.of(bake(), bake(call(GLAZE, "harden", "(I)V")))), "one of them selects another member");
		assertFalse(MixinCallbackShape.namesInEach(bare, harden, List.of()), "no method to read it in");
		assertTrue(MixinCallbackShape.namesInEach(CallbackSourceFixture.at("INVOKE", "org.example.pottery.Clay.harden(I)V"), harden, null), "spelled in full");
		assertFalse(MixinCallbackShape.namesInEach(CallbackSourceFixture.at("INVOKE", "Lorg/example/pottery/Glaze;harden(I)V"), harden, List.of(bake())),
				"another owner spelled out");
	}
}
