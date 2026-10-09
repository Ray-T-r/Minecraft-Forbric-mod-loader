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
 * Callbacks on vanilla's {@code StructureTemplate.placeEntities} from mods that are not Create and do not write Create's
 * three handlers: one handler alone, of another injector kind, with or without the level as a {@code @Local}, with the
 * selector spelled by name, by descriptor or owner-qualified. Each moves to {@code addEntitiesToWorld} on its own. A
 * handler at a call the merged body no longer makes, one that takes {@code placeEntities}' own parameters, a look-alike
 * in another method, two handlers at one point, and a capture nothing proves are left exactly as compiled.
 */
class MixinStructurePlacementGeneralTest {
	private static final String ST = MixinStructurePlacementAdapter.TARGET, OLD = MixinStructurePlacementAdapter.OLD, LIVE = MixinStructurePlacementAdapter.LIVE;
	private static final String PLACE_IN_WORLD = "placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z";
	private static final String ITERATOR = "Ljava/util/List;iterator()Ljava/util/Iterator;", LEVEL = "Lnet/minecraft/world/level/ServerLevelAccessor;";
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final BiFunction<Ecosystem, String, ClassNode> NONE = (family, name) -> null;

	private static int adapt(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinStructurePlacementAdapter.adapt(mixin, CarpetMixinAdapterTest::target, references);
	}

	private static List<String> selectors(ClassNode mixin, String handler) {
		return MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(MixinPlayerWorldCallbackAdapter.named(mixin, handler)), "method"));
	}

	/** {@code return (Iterator) original.call(list);} */
	private static final Consumer<MethodVisitor> PASS_ITERATOR = code -> {
		code.visitVarInsn(Opcodes.ALOAD, 2);
		array(code, 1);
		code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true);
		code.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Iterator");
		code.visitInsn(Opcodes.ARETURN);
	};
	private static final Consumer<MethodVisitor> RETURN = code -> code.visitInsn(Opcodes.RETURN);

	private static ClassNode iteratorWrap(String selector, Object... local) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/RuinsMixin", ST);
		String desc = "(Ljava/util/List;" + OPERATION + (local.length > 0 ? LEVEL : "") + ")Ljava/util/Iterator;";
		Handler handler = source.handler("skipGhosts", desc, WRAP, "method", List.of(selector), "at", List.of(at("INVOKE", ITERATOR)));
		if (local.length > 0) handler.local(2, local);
		handler.code(PASS_ITERATOR);
		return source.build();
	}

	@Test void aLoneIteratorWrapWithoutCapturesMovesWhateverItsSelectorSpelling() throws Exception {
		ClassNode described = iteratorWrap(OLD);
		assertEquals(1, adapt(described, NONE));
		assertEquals(List.of(LIVE), selectors(described, "skipGhosts"));
		CarpetMixinAdapterTest.verify(described);
		assertEquals(0, adapt(described, NONE), "idempotence");
		ClassNode bare = iteratorWrap("placeEntities");
		assertEquals(1, adapt(bare, VANILLA), "a bare name is read off the class the mod was compiled against");
		assertEquals(List.of(LIVE), selectors(bare, "skipGhosts"));
	}

	@Test void aLoneTailCallbackMovesWithoutTheOthers() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/AfterEntitiesMixin", ST);
		source.handler("afterEntities", "(" + CI + ")V", INJECT, "method", List.of("L" + ST + ";" + OLD), "at", List.of(at("TAIL", null))).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, NONE));
		assertEquals(List.of(LIVE), selectors(mixin, "afterEntities"));
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aLonePickupTakingNoneOfTheCallersArgumentsMovesToTheMergedCall() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/BeforeEntitiesMixin", ST);
			String selector = references == NONE ? PLACE_IN_WORLD : "placeInWorld";
			source.handler("beforeEntities", "(" + CIR + ")V", INJECT, "method", List.of(selector), "at", List.of(at("INVOKE", "L" + ST + ";" + OLD))).code(RETURN);
			ClassNode mixin = source.build();
			assertEquals(1, adapt(mixin, references), selector);
			AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(MixinPlayerWorldCallbackAdapter.named(mixin, "beforeEntities"))).getFirst();
			assertEquals("L" + ST + ";" + LIVE, MixinFit.value(at, "target"));
			assertEquals(0, adapt(mixin, references), "idempotence");
		}
	}

	@Test void aLevelCaptureIsProvedByWhatThePlacementCallHandsTheMethod() throws Exception {
		ClassNode argsOnly = iteratorWrap(OLD, "argsOnly", true);
		assertEquals(1, adapt(argsOnly, VANILLA));
		ClassNode anyLocal = iteratorWrap("placeEntities", "ordinal", 0);
		assertEquals(1, adapt(anyLocal, VANILLA), "an ordinal-discriminated capture of the level is the level argument in both bodies");
		assertEquals(List.of(LIVE), selectors(anyLocal, "skipGhosts"));
		CarpetMixinAdapterTest.verify(anyLocal);
	}

	@Test void anotherInjectorKindAtACallBothBodiesMakeMoves() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/TagCopyMixin", ST);
		source.handler("stampCopy", "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
				"method", List.of(OLD), "at", List.of(at("INVOKE", "Lnet/minecraft/nbt/CompoundTag;copy()Lnet/minecraft/nbt/CompoundTag;"))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitInsn(Opcodes.ARETURN);
		});
		source.handler("afterEntities", "(" + CI + ")V", INJECT, "method", List.of(OLD), "at", List.of(at("TAIL", null))).code(RETURN);
		source.handler("beforeEntities", "(" + CIR + ")V", INJECT, "method", List.of(PLACE_IN_WORLD), "at", List.of(at("INVOKE", "L" + ST + ";" + OLD))).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(3, adapt(mixin, VANILLA), "three unrelated handlers, none of them Create's");
		assertEquals(List.of(LIVE), selectors(mixin, "stampCopy"));
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aWrapAtACallTheMergedBodyNoLongerMakesStays() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/MirrorMixin", ST);
			String transform = "L" + ST + ";transform(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;";
			source.handler("mirrored", "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;" + OPERATION + ")Lnet/minecraft/core/BlockPos;",
					WRAP, "method", List.of(OLD), "at", List.of(at("INVOKE", transform))).code(code -> {
				code.visitVarInsn(Opcodes.ALOAD, 1);
				code.visitInsn(Opcodes.ARETURN);
			});
			assertUntouched(source.build(), references);
		}
	}

	@Test void aTailCallbackTakingPlaceEntitiesOwnParametersStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/ArgumentsMixin", ST);
		source.handler("afterEntities", OLD.substring(OLD.indexOf('('), OLD.indexOf(')')) + CI + ")V", INJECT, "method", List.of(OLD), "at", List.of(at("TAIL", null))).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aLookAlikeInAnotherMethodStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/ElsewhereMixin", ST);
		source.handler("skipGhosts", "(Ljava/util/List;" + OPERATION + ")Ljava/util/Iterator;", WRAP, "method", List.of(PLACE_IN_WORLD),
				"at", List.of(at("INVOKE", ITERATOR))).code(PASS_ITERATOR);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void twoCallbacksAtOnePointStayAsCompiled() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/TwoTailsMixin", ST);
		source.handler("first", "(" + CI + ")V", INJECT, "method", List.of(OLD), "at", List.of(at("TAIL", null))).code(RETURN);
		source.handler("second", "(" + CI + ")V", INJECT, "method", List.of(OLD), "at", List.of(at("TAIL", null))).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aCaptureNothingProvesIsNotGuessed() throws Exception {
		assertUntouched(iteratorWrap(OLD, "ordinal", 0), NONE);
		// placeEntities' second BlockPos is the rotation pivot; addEntitiesToWorld is handed one BlockPos, the position.
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/ruins/mixin/PivotMixin", ST);
		source.handler("skipGhosts", "(Ljava/util/List;" + OPERATION + "Lnet/minecraft/core/BlockPos;)Ljava/util/Iterator;", WRAP, "method", List.of(OLD),
				"at", List.of(at("INVOKE", ITERATOR))).local(2, "ordinal", 1).code(PASS_ITERATOR);
		assertUntouched(source.build(), VANILLA);
	}

	private static void assertUntouched(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin, references));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}
}
