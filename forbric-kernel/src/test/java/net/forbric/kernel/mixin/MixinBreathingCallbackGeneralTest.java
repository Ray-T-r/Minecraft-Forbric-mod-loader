/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.mixin.CallbackSourceFixture.*;

import java.util.List;
import java.util.function.BiFunction;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.interop.BreathingCallbackScope;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Breathing callbacks written by mods that are not Create, and not the way Create wrote its pair: one wrap alone, either
 * call, a bare or owner-qualified selector, with or without the {@code @Local ServerLevel}, an {@code Object[]} built in
 * a local the way Kotlin builds it, a result kept in a local. Each is kept on its own. A wrap whose answer would change
 * whether the entity drowns, one that asks {@code original} about another entity, one that wants a local NeoForge's
 * call is not handed, and two wraps of the same call are left as compiled — never kept with their answer dropped.
 */
class MixinBreathingCallbackGeneralTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity", LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
	private static final String TAG = "Lnet/minecraft/tags/TagKey;";
	private static final String EYE = "L" + LIVING + ";isEyeInFluid(" + TAG + ")Z";
	private static final String WATER = "Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(L" + LIVING + ";)Z";
	private static final String WATER_DESC = "(L" + LIVING + ";" + OPERATION + ")Z", EYE_DESC = "(L" + LIVING + ";" + TAG + OPERATION + ")Z";
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);

	/** {@code original.call(mob) || Gills.grown(mob)}, the array built in a local; a bare {@code "baseTick"} selector; no @Local. */
	private static void gills(CallbackSourceFixture source, String name) {
		source.handler(name, WATER_DESC, WRAP, "method", List.of("baseTick"), "at", List.of(at("INVOKE", WATER))).code(code -> {
			Label grown = new Label();
			arrayInLocal(code, 3, 1);
			code.visitVarInsn(Opcodes.ALOAD, 2);
			code.visitVarInsn(Opcodes.ALOAD, 3);
			callBoolean(code);
			code.visitJumpInsn(Opcodes.IFEQ, grown);
			code.visitInsn(Opcodes.ICONST_1);
			code.visitInsn(Opcodes.IRETURN);
			code.visitLabel(grown);
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/example/scuba/Gills", "grown", "(L" + LIVING + ";)Z", false);
			code.visitInsn(Opcodes.IRETURN);
		});
	}

	/**
	 * {@code boolean wet = original.call(e, tag); if (!wet) Visor.scan(e, level); return wet;} with an owner-qualified
	 * selector and {@code @Local(ordinal = ordinal) ServerLevel level}: its answer is the call's, so nothing is lost.
	 */
	private static void visor(CallbackSourceFixture source, String name, int ordinal) {
		source.handler(name, "(L" + LIVING + ";" + TAG + OPERATION + LEVEL + ")Z", WRAP, "method", List.of("L" + LIVING + ";baseTick()V"),
				"at", List.of(at("INVOKE", EYE))).local(3, "ordinal", ordinal).code(code -> {
			Label done = new Label();
			code.visitVarInsn(Opcodes.ALOAD, 3);
			array(code, 1, 2);
			callBoolean(code);
			code.visitVarInsn(Opcodes.ISTORE, 5);
			code.visitVarInsn(Opcodes.ILOAD, 5);
			code.visitJumpInsn(Opcodes.IFNE, done);
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitVarInsn(Opcodes.ALOAD, 4);
			code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/example/visor/Visor", "scan", "(L" + LIVING + ";" + LEVEL + ")V", false);
			code.visitLabel(done);
			code.visitVarInsn(Opcodes.ILOAD, 5);
			code.visitInsn(Opcodes.IRETURN);
		});
	}

	/** {@code return original.call(e, tag) && !Goggles.on(e);}: the answer differs from the call's when goggles are on. */
	private static void goggles(CallbackSourceFixture source, String name) {
		source.handler(name, EYE_DESC, WRAP, "method", List.of("baseTick()V"), "at", List.of(at("INVOKE", EYE))).code(code -> {
			Label dry = new Label();
			code.visitVarInsn(Opcodes.ALOAD, 3);
			array(code, 1, 2);
			callBoolean(code);
			code.visitJumpInsn(Opcodes.IFEQ, dry);
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/example/goggles/Goggles", "on", "(L" + LIVING + ";)Z", false);
			code.visitJumpInsn(Opcodes.IFNE, dry);
			code.visitInsn(Opcodes.ICONST_1);
			code.visitInsn(Opcodes.IRETURN);
			code.visitLabel(dry);
			code.visitInsn(Opcodes.ICONST_0);
			code.visitInsn(Opcodes.IRETURN);
		});
	}

	private static int adapt(ClassNode mixin) { return MixinBreathingCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target, (family, name) -> null); }

	/** The two scope arguments the generated wrap hands {@code BreathingCallbackScope.enter}: a lambda, or null for none. */
	private static List<String> scopeArguments(ClassNode mixin) {
		MethodNode wrapper = mixin.methods.stream().filter(m -> m.name.equals("forbric$breathingCallback")).findFirst().orElseThrow();
		List<String> pushed = new java.util.ArrayList<>();
		for (AbstractInsnNode instruction : wrapper.instructions) {
			if (instruction instanceof MethodInsnNode call && call.name.equals("enter")) break;
			if (instruction.getOpcode() == Opcodes.ACONST_NULL) pushed.add("none");
			if (instruction instanceof InvokeDynamicInsnNode dynamic) pushed.add(((org.objectweb.asm.Handle) dynamic.bsmArgs[1]).getName());
		}
		return pushed;
	}

	@Test void aLoneWaterBreathingWrapWrittenAnotherWayIsKept() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/scuba/mixin/GillsMixin", LIVING);
		gills(source, "gills");
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin));
		assertEquals(List.of("none", "forbric$waterCallback"), scopeArguments(mixin), "no eye-in-water callback is invented");
		assertNotNull(MixinPlayerWorldCallbackAdapter.named(mixin, "gills$forbricOriginal"));
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin), "idempotence");
	}

	@Test void aLoneEyeWrapThatAnswersWhatTheCallAnswersIsKeptWithItsLevel() throws Exception {
		for (BiFunction<Ecosystem, String, ClassNode> references : List.<BiFunction<Ecosystem, String, ClassNode>>of((family, name) -> null, VANILLA)) {
			CallbackSourceFixture source = new CallbackSourceFixture("org/example/visor/mixin/VisorMixin", LIVING);
			visor(source, "scanEyes", 0);
			ClassNode mixin = source.build();
			assertEquals(1, MixinBreathingCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target, references));
			assertEquals(List.of("forbric$lavaCallback", "none"), scopeArguments(mixin));
			CarpetMixinAdapterTest.verify(mixin);
		}
	}

	@Test void bothWrapsWrittenInAnotherMixinAreKeptTogether() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/amphibian/mixin/AmphibianMixin", LIVING);
		visor(source, "eyes", 0);
		gills(source, "lungs");
		ClassNode mixin = source.build();
		assertEquals(2, MixinBreathingCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target, VANILLA));
		assertEquals(List.of("forbric$lavaCallback", "forbric$waterCallback"), scopeArguments(mixin));
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void anEyeWrapThatChangesTheAnswerIsLeftAsCompiledNotKeptWithItsAnswerDropped() throws Exception {
		CallbackSourceFixture alone = new CallbackSourceFixture("org/example/goggles/mixin/GogglesMixin", LIVING);
		goggles(alone, "dryEyes");
		ClassNode mixin = alone.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));

		CallbackSourceFixture beside = new CallbackSourceFixture("org/example/goggles/mixin/DiverMixin", LIVING);
		goggles(beside, "dryEyes");
		gills(beside, "gills");
		ClassNode both = beside.build();
		MethodNode refused = MixinPlayerWorldCallbackAdapter.named(both, "dryEyes");
		String body = MixinInstructionFingerprint.hash(refused);
		assertEquals(1, adapt(both), "the water wrap is kept on its own");
		assertEquals(List.of("none", "forbric$waterCallback"), scopeArguments(both));
		assertNotNull(MixinFit.injectorOf(refused), "the refused wrap keeps its injector, so its loss is reported");
		assertEquals("dryEyes", refused.name);
		assertEquals(body, MixinInstructionFingerprint.hash(refused));
	}

	@Test void aWrapThatAsksAboutAnotherEntityIsLeftAlone() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/tether/mixin/TetherMixin", LIVING);
		source.handler("tethered", WATER_DESC, WRAP, "method", List.of("baseTick"), "at", List.of(at("INVOKE", WATER))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 2);
			code.visitInsn(Opcodes.ICONST_1);
			code.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
			code.visitInsn(Opcodes.DUP);
			code.visitInsn(Opcodes.ICONST_0);
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getLastHurtByMob", "()L" + LIVING + ";", false);
			code.visitInsn(Opcodes.AASTORE);
			callBoolean(code);
			code.visitInsn(Opcodes.IRETURN);
		});
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void aWrapThatWantsALocalNeoForgesCallIsNotHandedIsLeftAlone() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/scuba/mixin/PlayerOnlyMixin", LIVING);
		source.handler("playersOnly", "(L" + LIVING + ";" + OPERATION + "Z)Z", WRAP, "method", List.of("baseTick"), "at", List.of(at("INVOKE", WATER)))
				.local(2).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 2);
			array(code, 1);
			callBoolean(code);
			code.visitInsn(Opcodes.IRETURN);
		});
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void aLevelLocalThatVanillaHadNoSlotForIsNotGuessedWhenTheNativeClassSaysSo() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/visor/mixin/SecondLevelMixin", LIVING);
		visor(source, "secondLevel", 1);
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, MixinBreathingCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target, VANILLA));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void twoWrapsOfOneCallAreLeftAloneForTheOneSlotScope() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/scuba/mixin/TwoGillsMixin", LIVING);
		gills(source, "gills");
		gills(source, "moreGills");
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void aWrapOfAnotherBaseTickCallWithTheSameShapeIsNotABreathingCallback() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/scuba/mixin/UnderwaterMixin", LIVING);
		source.handler("underwater", WATER_DESC, WRAP, "method", List.of("baseTick"), "at",
				List.of(at("INVOKE", "L" + LIVING + ";canBreatheUnderwater()Z"))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 2);
			array(code, 1);
			callBoolean(code);
			code.visitInsn(Opcodes.IRETURN);
		});
		ClassNode mixin = source.build();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void anAbsentCallbackLeavesThatDecisionToNeoForge() {
		Object entity = new Object(), level = new Object();
		Object previous = BreathingCallbackScope.enter(null, args -> Boolean.TRUE);
		try {
			BreathingCallbackScope.lava(entity, level);
			assertTrue(BreathingCallbackScope.water(entity, false, level));
		} finally {
			BreathingCallbackScope.leave(previous);
		}
		java.util.concurrent.atomic.AtomicInteger eyes = new java.util.concurrent.atomic.AtomicInteger();
		previous = BreathingCallbackScope.enter(args -> { eyes.incrementAndGet(); return null; }, null);
		try {
			BreathingCallbackScope.lava(entity, level);
			assertFalse(BreathingCallbackScope.water(entity, false, level), "no water callback: NeoForge's own answer");
			assertTrue(BreathingCallbackScope.water(entity, true, level));
		} finally {
			BreathingCallbackScope.leave(previous);
		}
		assertEquals(1, eyes.get());
	}
}
