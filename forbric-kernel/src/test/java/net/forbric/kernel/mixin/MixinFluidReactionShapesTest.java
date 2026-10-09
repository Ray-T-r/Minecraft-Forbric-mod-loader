/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The lava-meets-water move on the staged merged classes, for the reaction written other ways than Carpet's deepslate
 * rule: the target named by string, the shadow fizz called twice, no fizz at all. Each is moved to the registries as a
 * whole, with the generated guard being vanilla's own test for reaching the point (lava beside water), not Carpet's
 * "flowing lava only". Look-alikes that read the block itself — a shadow field, a shadow whose body reads the block —
 * or anchor on another call are left byte-for-byte alone.
 */
@ResourceLock("system-properties")
class MixinFluidReactionShapesTest {
	private static final String DEEPSLATE = "LiquidBlock_renewableDeepslateMixin";
	private static final String FIZZ = "fizz";

	private static ClassNode renamed() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.mixin(DEEPSLATE));
	}

	private static MethodNode handler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	private static MethodInsnNode fizzCall(MethodNode handler) {
		for (var insn : handler.instructions) if (insn instanceof MethodInsnNode call && call.name.equals(FIZZ)) return call;
		throw new AssertionError("no fizz call");
	}

	private static int adapt(ClassNode mixin) {
		return MixinFluidReactionAdapter.adapt(mixin, CarpetMixinAdapterTest::target);
	}

	private static AnnotationNode declaration(ClassNode mixin) {
		return mixin.invisibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
	}

	/** The guard the move generates: vanilla's lava-and-water test, a fresh callback, the handler's answer handed on. */
	private static void assertVanillasTest(ClassNode mixin) {
		MethodNode outer = MixinPlayerWorldCallbackAdapter.named(mixin, "forbric$flowingFluidReaction");
		List<String> fields = new ArrayList<>();
		boolean fresh = false, answered = false;
		for (var insn : outer.instructions) {
			if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
			if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;Z)V")) fresh = true;
			if (insn instanceof MethodInsnNode call && call.name.equals("getReturnValueZ")) answered = true;
		}
		assertEquals(List.of("net/minecraft/tags/FluidTags.LAVA", "net/minecraft/tags/FluidTags.WATER"), fields, "lava beside water, source or flowing");
		assertTrue(fresh, "the handler gets a callback without a return value, as at any INVOKE point");
		assertTrue(answered, "a cancelling handler's own answer is handed on");
	}

	@Test void aTargetNamedByStringMovesWholeToTheRegistries() throws Exception {
		ClassNode mixin = renamed();
		AnnotationNode declaration = declaration(mixin);
		declaration.values = new ArrayList<>(List.of("targets", List.of("net.minecraft.world.level.block.LiquidBlock")));
		assertEquals(1, adapt(mixin));
		assertNull(MixinFit.value(declaration, "targets"), "the string form is replaced too, not left beside the registries");
		assertEquals(new HashSet<>(MixinFluidReactionAdapter.REGISTRIES), new HashSet<>(MixinFit.mixinTargets(mixin)));
		assertVanillasTest(mixin);
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin), "idempotence");
	}

	@Test void twoFizzCallsBothRunOnTheRelocatedCopy() throws Exception {
		ClassNode mixin = renamed();
		MethodNode handler = handler(mixin);
		MethodInsnNode fizz = fizzCall(handler);
		AbstractInsnNode receiver = MixinPlayerWorldCallbackAdapter.previous(MixinPlayerWorldCallbackAdapter.previous(
				MixinPlayerWorldCallbackAdapter.previous(fizz)));
		InsnList again = new InsnList();
		again.add(new VarInsnNode(Opcodes.ALOAD, 0));
		again.add(new VarInsnNode(Opcodes.ALOAD, 1));
		again.add(new VarInsnNode(Opcodes.ALOAD, 2));
		again.add(new MethodInsnNode(fizz.getOpcode(), fizz.owner, fizz.name, fizz.desc, fizz.itf));
		handler.instructions.insertBefore(receiver, again);
		assertEquals(1, adapt(mixin));
		MethodNode moved = mixin.methods.stream().filter(m -> m.name.endsWith("$forbricOriginal")).findFirst().orElseThrow();
		List<MethodInsnNode> calls = new ArrayList<>();
		for (var insn : moved.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("forbric$fluidReactionFizz")) calls.add(call);
		assertEquals(2, calls.size());
		assertTrue(calls.stream().allMatch(c -> c.getOpcode() == Opcodes.INVOKESTATIC));
		assertTrue((moved.access & Opcodes.ACC_STATIC) != 0);
		assertVanillasTest(mixin);
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aRuleThatNeverCallsFizzMovesWithoutACopy() throws Exception {
		ClassNode mixin = renamed();
		MethodNode handler = handler(mixin);
		MethodInsnNode fizz = fizzCall(handler);
		AbstractInsnNode b = MixinPlayerWorldCallbackAdapter.previous(fizz), a = MixinPlayerWorldCallbackAdapter.previous(b),
				self = MixinPlayerWorldCallbackAdapter.previous(a);
		for (AbstractInsnNode insn : List.of(self, a, b, fizz)) handler.instructions.remove(insn);
		mixin.methods.removeIf(m -> m.name.equals(FIZZ));
		assertEquals(1, adapt(mixin));
		assertNull(MixinPlayerWorldCallbackAdapter.named(mixin, "forbric$fluidReactionFizz"), "nothing to relocate");
		assertVanillasTest(mixin);
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aRuleThatReadsTheBlockStaysAsCompiled() throws Exception {
		// A shadow field of the block, read by the handler: it cannot move to a registry that has no such field.
		ClassNode field = renamed();
		FieldNode fluid = new FieldNode(Opcodes.ACC_PROTECTED, "fluid", "Lnet/minecraft/world/level/material/FlowingFluid;", null, null);
		fluid.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
		field.fields.add(fluid);
		MethodNode handler = handler(field);
		InsnList read = new InsnList();
		read.add(new VarInsnNode(Opcodes.ALOAD, 0));
		read.add(new FieldInsnNode(Opcodes.GETFIELD, field.name, "fluid", fluid.desc));
		read.add(new InsnNode(Opcodes.POP));
		handler.instructions.insert(read);
		assertUntouched(field, CarpetMixinAdapterTest::target, "a shadow field");

		// A shadow whose body in the merged block reads the block itself: it has no static copy.
		Function<String, ClassNode> readsItself = name -> {
			ClassNode target = CarpetMixinAdapterTest.target(name);
			if (name.equals(MixinFluidReactionAdapter.LIQUID)) {
				MethodNode fizz = target.methods.stream().filter(m -> m.name.equals(FIZZ)).findFirst().orElseThrow();
				InsnList self = new InsnList();
				self.add(new VarInsnNode(Opcodes.ALOAD, 0));
				self.add(new InsnNode(Opcodes.POP));
				fizz.instructions.insert(self);
			}
			return target;
		};
		assertUntouched(renamed(), readsItself, "a shadow whose body reads the block");

		// The handler hands `this` to something other than a shadow call.
		ClassNode escapes = renamed();
		InsnList hand = new InsnList();
		hand.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hand.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		hand.add(new InsnNode(Opcodes.POP));
		handler(escapes).instructions.insert(hand);
		assertUntouched(escapes, CarpetMixinAdapterTest::target, "this handed elsewhere");
	}

	@Test void aRuleAtAnotherCallIsNotTheReaction() throws Exception {
		ClassNode mixin = renamed();
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler(mixin))).getFirst();
		MixinPlayerWorldCallbackAdapter.set(at, "target", "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z");
		assertUntouched(mixin, CarpetMixinAdapterTest::target, "another call");
		ClassNode second = renamed();
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler(second))).getFirst(), "ordinal", 1);
		assertUntouched(second, CarpetMixinAdapterTest::target, "an occurrence vanilla's method does not have");
	}

	private static void assertUntouched(ClassNode mixin, Function<String, ClassNode> targets, String why) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, MixinFluidReactionAdapter.adapt(mixin, targets), why);
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), why);
	}
}
