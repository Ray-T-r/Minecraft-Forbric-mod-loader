/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.mixin.CallbackSourceFixture.*;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Callbacks around vanilla's {@code Item.useOn} call in {@code ItemStack.useOn} from mods that are not Create and are not
 * written as Create's pair: a handler at the call alone, or one at a later point alone, with other captures (the
 * player or the clicked position instead of the item, by type or by ordinal), with or without a share, cancellable or
 * taking only its callback. Each is kept on its own, at the head or the return of {@code useOn}, inside a replay of
 * vanilla's own instructions. A later handler that reads its callback or cancels, a point past a call the replay would
 * have to make again, a local vanilla had no slot for, two handlers at one point, and any handler without the class the
 * mod was compiled against are left exactly as compiled.
 */
class MixinPlacementTransactionGeneralTest {
	private static final String STACK = "net/minecraft/world/item/ItemStack", CONTEXT = "Lnet/minecraft/world/item/context/UseOnContext;";
	private static final String USE_ON = "useOn(" + CONTEXT + ")Lnet/minecraft/world/InteractionResult;";
	private static final String CALL = "Lnet/minecraft/world/item/Item;" + USE_ON;
	private static final String WAS_ITEM = "Lnet/minecraft/world/InteractionResult$Success;wasItemInteraction()Z";
	private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;", POS = "Lnet/minecraft/core/BlockPos;";
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final Consumer<MethodVisitor> RETURN = code -> code.visitInsn(Opcodes.RETURN);

	private static int adapt(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinPlacementTransactionAdapter.adapt(mixin, CarpetMixinAdapterTest::target, references);
	}

	private static MethodNode named(ClassNode mixin, String name) {
		return mixin.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
	}

	private static String point(MethodNode handler) {
		return (String) MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "value");
	}

	/** Whether {@code wrapper} calls {@code original} once, inside the replayed vanilla code that decides whether it runs. */
	private static void assertReplayed(MethodNode wrapper, String original) {
		long calls = 0, guards = 0;
		for (AbstractInsnNode instruction : wrapper.instructions) {
			if (instruction instanceof MethodInsnNode call && call.name.equals(original)) calls++;
			if (instruction instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO) guards++;
		}
		assertEquals(1, calls, wrapper.name + " calls the handler once");
		assertTrue(guards > 0, wrapper.name + " carries vanilla's conditions");
	}

	private static void assertUntouched(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin, references));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void aLoneHandlerAtTheCallCapturingThePlayerIsReplayedAtTheHead() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/BuilderMixin", STACK);
		source.handler("beforeUse", "(" + CONTEXT + CIR + PLAYER + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", CALL)))
				.local(2).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		MethodNode wrapper = MixinFit.injectorOf(named(mixin, "beforeUse")) == null ? null : named(mixin, "beforeUse");
		assertNotNull(wrapper);
		assertEquals("HEAD", point(wrapper));
		assertReplayed(wrapper, "beforeUse$forbricOriginal");
		assertNull(named(mixin, "beforeUse$forbricReach"), "a handler at the call needs nothing carried");
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin, VANILLA), "idempotence");
	}

	@Test void aCancellableHandlerAtTheCallTakingOnlyItsCallbackAndThePositionStaysCancellable() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/GuardMixin", STACK);
		source.handler("guard", "(" + CIR + POS + ")V", INJECT, "method", List.of("L" + STACK + ";" + USE_ON), "at", List.of(at("INVOKE", CALL)),
				"cancellable", true).local(1, "ordinal", 0).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		MethodNode wrapper = named(mixin, "guard");
		assertEquals(Boolean.TRUE, MixinFit.value(MixinFit.injectorOf(wrapper), "cancellable"));
		assertReplayed(wrapper, "guard$forbricOriginal");
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aLoneLaterHandlerWithoutAShareIsReplayedAtTheReturnWhenTheCallWasReached() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/StatsMixin", STACK);
		source.handler("countUse", "(" + CIR + PLAYER + ")V", INJECT, "method", List.of(USE_ON), "at", List.of(at("INVOKE", WAS_ITEM)))
				.local(1, "ordinal", 0).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 2);
			code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/example/builder/Stats", "count", "(" + PLAYER + ")V", false);
			code.visitInsn(Opcodes.RETURN);
		});
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		MethodNode wrapper = named(mixin, "countUse"), reach = named(mixin, "countUse$forbricReach");
		assertEquals("RETURN", point(wrapper));
		assertEquals("HEAD", point(reach), "the head records whether vanilla reached the call");
		assertReplayed(wrapper, "countUse$forbricOriginal");
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin, VANILLA), "idempotence");
	}

	@Test void aLaterHandlerThatReadsItsCallbackStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/PeekMixin", STACK);
		source.handler("peek", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", WAS_ITEM))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 2);
			code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", "getReturnValue", "()Ljava/lang/Object;", false);
			code.visitInsn(Opcodes.POP);
			code.visitInsn(Opcodes.RETURN);
		});
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aLaterHandlerThatCancelsStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/StopMixin", STACK);
		source.handler("stop", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", WAS_ITEM)), "cancellable", true).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aPointPastACallTheReplayWouldMakeAgainStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/AwardMixin", STACK);
		source.handler("award", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"),
				"at", List.of(at("FIELD", "Lnet/minecraft/stats/Stats;ITEM_USED:Lnet/minecraft/stats/StatType;"))).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aLocalVanillaHadNoSlotForStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/ResultMixin", STACK);
		source.handler("result", "(" + CONTEXT + CIR + "Lnet/minecraft/world/InteractionResult;)V", INJECT, "method", List.of("useOn"),
				"at", List.of(at("INVOKE", CALL))).local(2).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void twoHandlersAtTheCallStay() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/TwiceMixin", STACK);
		source.handler("first", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", CALL))).code(RETURN);
		source.handler("second", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", CALL))).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void withoutTheClassTheModWasCompiledAgainstNothingMoves() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/builder/mixin/BlindMixin", STACK);
		source.handler("beforeUse", "(" + CONTEXT + CIR + ")V", INJECT, "method", List.of("useOn"), "at", List.of(at("INVOKE", CALL))).code(RETURN);
		assertUntouched(source.build(), (family, name) -> null);
	}
}
