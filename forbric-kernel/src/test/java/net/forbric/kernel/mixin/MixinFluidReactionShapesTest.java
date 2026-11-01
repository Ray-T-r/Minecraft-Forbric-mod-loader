/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The lava-meets-water rule on the staged NeoForge base, for the reaction written other ways than Carpet's deepslate rule:
 * the target named by string, the shadow fizz called twice, no fizz at all, a rule that reads the block. NeoForge's
 * placement asks vanilla's {@code shouldSpreadLiquid}, where each binds as compiled; its neighbour change asks NeoForge's
 * registry, and each gets the same wrap there: vanilla's own walk to the point ({@code KernelFluidReactions}), a fresh
 * callback, the handler's answer handed on, the registry otherwise. The rule stays in the liquid block, so what it reads of
 * the block is its own to read. A hook at another call is not the reaction and is left byte-for-byte alone.
 *
 * <p>(On the merged base these shapes moved whole to both families' registries, because placement asked MinecraftForge's;
 * that relocation needs MinecraftForge's registry and never runs on NeoForge's base.)
 */
@ResourceLock("system-properties")
class MixinFluidReactionShapesTest {
	private static final String DEEPSLATE = "LiquidBlock_renewableDeepslateMixin";
	private static final String FIZZ = "fizz";

	/** Carpet's rule under unrelated names, still recorded as a Fabric mod's (the wrap is for a Fabric mod's handler). */
	private static ClassNode renamed() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.mixin(DEEPSLATE));
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		return mixin;
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

	/** The copy the wrap runs, the handler's body under the kernel's name. */
	private static MethodNode copy(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> m.name.endsWith("$forbricOriginal")).findFirst().orElseThrow();
	}

	/** The wrap: vanilla's walk to the reaction, a fresh callback, the handler's answer handed on, else the registry. */
	private static void assertVanillasWalk(ClassNode mixin) {
		MethodNode wrap = MixinPlayerWorldCallbackAdapter.named(mixin, "forbric$neighbourFluidReaction");
		boolean walk = false, fresh = false, answered = false, registry = false;
		for (var insn : wrap.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			walk |= call.owner.equals(MixinFluidReactionAdapter.RUNTIME) && call.name.equals("reachesWaterReaction");
			fresh |= call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;Z)V");
			answered |= call.name.equals("getReturnValueZ");
			registry |= call.name.equals("call");
		}
		assertTrue(walk, "vanilla's walk decides whether the point is reached");
		assertTrue(fresh, "the handler gets a callback without a return value, as at any INVOKE point");
		assertTrue(answered, "a cancelling handler's own answer is handed on");
		assertTrue(registry, "otherwise the registry runs as written");
		assertNotNull(MixinFit.injectorOf(handler(mixin)), "placement keeps the handler bound as compiled");
	}

	@Test void aTargetNamedByStringIsServedOnANeighbourChange() throws Exception {
		ClassNode mixin = renamed();
		AnnotationNode declaration = declaration(mixin);
		declaration.values = new ArrayList<>(List.of("targets", List.of("net.minecraft.world.level.block.LiquidBlock")));
		assertEquals(1, adapt(mixin));
		assertEquals(List.of("targets", List.of("net.minecraft.world.level.block.LiquidBlock")), declaration.values, "the declaration is left as written");
		assertVanillasWalk(mixin);
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin), "idempotence");
	}

	@Test void twoFizzCallsBothRunInTheCopy() throws Exception {
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
		List<MethodInsnNode> calls = new ArrayList<>();
		for (var insn : copy(mixin).instructions) if (insn instanceof MethodInsnNode call && call.name.equals(FIZZ)) calls.add(call);
		assertEquals(2, calls.size());
		assertTrue(calls.stream().allMatch(c -> c.getOpcode() == fizz.getOpcode()), "the shadow is called as written, in the block");
		assertEquals(0, copy(mixin).access & Opcodes.ACC_STATIC);
		assertVanillasWalk(mixin);
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aRuleThatNeverCallsFizzIsServedToo() throws Exception {
		ClassNode mixin = renamed();
		MethodNode handler = handler(mixin);
		MethodInsnNode fizz = fizzCall(handler);
		AbstractInsnNode b = MixinPlayerWorldCallbackAdapter.previous(fizz), a = MixinPlayerWorldCallbackAdapter.previous(b),
				self = MixinPlayerWorldCallbackAdapter.previous(a);
		for (AbstractInsnNode insn : List.of(self, a, b, fizz)) handler.instructions.remove(insn);
		mixin.methods.removeIf(m -> m.name.equals(FIZZ));
		assertEquals(1, adapt(mixin));
		assertVanillasWalk(mixin);
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aRuleThatReadsTheBlockIsServedInTheBlock() throws Exception {
		// A shadow field of the block, read by the handler: the copy stays in the block, where the field is.
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
		assertEquals(1, adapt(field), "a shadow field");
		assertVanillasWalk(field);
		CarpetMixinAdapterTest.verify(field);

		// The handler hands `this` to something other than a shadow call.
		ClassNode escapes = renamed();
		InsnList hand = new InsnList();
		hand.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hand.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		hand.add(new InsnNode(Opcodes.POP));
		handler(escapes).instructions.insert(hand);
		assertEquals(1, adapt(escapes), "this handed elsewhere");
		CarpetMixinAdapterTest.verify(escapes);
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
