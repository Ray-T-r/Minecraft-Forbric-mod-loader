/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.mixin.CallbackSourceFixture.*;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * The three carrier changes {@code MixinCarrierCallbackAdapters} follows — {@code EntityFluidInteraction.update}'s boolean
 * becoming a predicate, {@code ModelManager.discoverModelDependencies} gaining NeoForge's standalone models, and the block
 * model reader {@code CuboidModel.fromStream} becoming {@code UnbakedModelParser.parse} — met by handlers that are not
 * Create's: one alone instead of Create's pair, another point, fewer or more {@code @Local}s, a callback-only
 * signature, another injector kind, another selector spelling. Each lands on its own. A handler that reads what no longer
 * exists, sits at a point the live body makes a different number of times, asks for a local nothing proves, shares its
 * point with another handler, or is typed by the replaced call's result, is left exactly as compiled.
 */
class MixinCarrierCallbackGeneralTest {
	private static final String EFI = "net/minecraft/world/entity/EntityFluidInteraction", ENTITY = "Lnet/minecraft/world/entity/Entity;";
	private static final String FLUID_LIVE = "update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)V";
	private static final String GET_Y = "Lnet/minecraft/core/BlockPos$MutableBlockPos;getY()I";
	private static final String MM = "net/minecraft/client/resources/model/ModelManager";
	private static final String DISCOVER = "discoverModelDependencies(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;";
	private static final String DISCOVER_ARGS = DISCOVER.substring(DISCOVER.indexOf('(') + 1, DISCOVER.indexOf(')'));
	private static final String RESOLVED_NEW = "(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;";
	private static final String FROM_STREAM = "Lnet/minecraft/client/resources/model/cuboid/CuboidModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;";
	private static final String PARSE = "Lnet/neoforged/neoforge/client/model/UnbakedModelParser;parse(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;";
	private static final String LAMBDA = "lambda$loadBlockModels$2";
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final BiFunction<Ecosystem, String, ClassNode> NONE = (family, name) -> null;
	private static final Consumer<MethodVisitor> RETURN = code -> code.visitInsn(Opcodes.RETURN);

	private static int adapt(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinCarrierCallbackAdapters.adapt(mixin, CarpetMixinAdapterTest::target, references);
	}

	private static MethodNode handler(ClassNode mixin, String name) {
		return mixin.methods.stream().filter(m -> m.name.equals(name) && MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	private static List<String> selectors(ClassNode mixin, String name) {
		return MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler(mixin, name)), "method"));
	}

	private static String target(ClassNode mixin, String name) {
		return (String) MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler(mixin, name))).getFirst(), "target");
	}

	private static void assertUntouched(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin, references));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	// ---- EntityFluidInteraction.update(Entity, boolean) -> update(Entity, Predicate) ---------------------------------

	@Test void aLoneHeadCallbackTakingOnlyItsCallbackMoves() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/WadingMixin", EFI);
		source.handler("resetWading", "(" + CI + ")V", INJECT, "method", List.of("update"), "at", List.of(at("HEAD", null))).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, NONE));
		assertEquals(List.of(FLUID_LIVE), selectors(mixin, "resetWading"));
		assertEquals("(" + CI + ")V", handler(mixin, "resetWading").desc);
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin, NONE), "idempotence");
	}

	@Test void aLoneCallbackWithTheArgumentsAndNoCaptureMovesAndItsUnreadBooleanBecomesThePredicate() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/SplashMixin", EFI);
		source.handler("splash", "(" + ENTITY + "Z" + CI + ")V", INJECT, "method", List.of("L" + EFI + ";update(" + ENTITY + "Z)V"),
				"at", List.of(at("INVOKE", GET_Y))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/example/wading/Splash", "at", "(" + ENTITY + ")V", false);
			code.visitInsn(Opcodes.RETURN);
		});
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		assertEquals("(" + ENTITY + "Ljava/util/function/Predicate;" + CI + ")V", handler(mixin, "splash").desc);
		assertEquals(List.of(FLUID_LIVE), selectors(mixin, "splash"));
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aCallbackCapturingMoreLocalsThanCreateIsProvedSlotBySlot() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/RippleMixin", EFI);
		source.handler("ripple", "(" + CI + "Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V", INJECT,
				"method", List.of("update"), "at", List.of(at("INVOKE", GET_Y))).local(1).local(2, "ordinal", 0).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		assertEquals(List.of(FLUID_LIVE), selectors(mixin, "ripple"));
		CarpetMixinAdapterTest.verify(mixin);
		assertUntouchedWithout(source.build());
	}

	/** Without the native class the same captures are not guessed. */
	private static void assertUntouchedWithout(ClassNode mixin) { assertUntouched(mixin, NONE); }

	@Test void aTailCallbackMovesAlone() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/DryMixin", EFI);
		source.handler("dried", "(" + CI + ")V", INJECT, "method", List.of("update(" + ENTITY + "Z)V"), "at", List.of(at("TAIL", null))).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		assertEquals(List.of(FLUID_LIVE), selectors(mixin, "dried"));
	}

	@Test void aCallbackThatReadsTheBooleanStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/IgnoringMixin", EFI);
		source.handler("ignoring", "(" + ENTITY + "Z" + CI + ")V", INJECT, "method", List.of("update"), "at", List.of(at("HEAD", null))).code(code -> {
			Label skip = new Label();
			code.visitVarInsn(Opcodes.ILOAD, 2);
			code.visitJumpInsn(Opcodes.IFEQ, skip);
			code.visitVarInsn(Opcodes.ALOAD, 1);
			code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/example/wading/Splash", "at", "(" + ENTITY + ")V", false);
			code.visitLabel(skip);
			code.visitInsn(Opcodes.RETURN);
		});
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aCallbackAtACallTheLiveBodyNoLongerMakesStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/ClearMixin", EFI);
		source.handler("cleared", "(" + CI + ")V", INJECT, "method", List.of("update"),
				"at", List.of(at("INVOKE", "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void aSecondFluidStateNobodyHadStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/SecondFluidMixin", EFI);
		source.handler("second", "(" + CI + "Lnet/minecraft/world/level/material/FluidState;)V", INJECT, "method", List.of("update"),
				"at", List.of(at("INVOKE", GET_Y))).local(1, "ordinal", 1).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	@Test void twoCallbacksAtOnePointStay() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/wading/mixin/TwoHeadsMixin", EFI);
		source.handler("first", "(" + CI + ")V", INJECT, "method", List.of("update"), "at", List.of(at("HEAD", null))).code(RETURN);
		source.handler("second", "(" + CI + ")V", INJECT, "method", List.of("update"), "at", List.of(at("HEAD", null))).code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	// ---- ModelManager.discoverModelDependencies gains NeoForge's standalone models -------------------------------------

	@Test void aHeadCallbackTakingOnlyItsCallbackSelectsTheWiderMethod() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/CountMixin", MM);
		source.handler("countModels", "(" + CIR + ")V", INJECT, "method", List.of(DISCOVER), "at", List.of(at("HEAD", null))).statik().code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, NONE));
		List<String> selected = selectors(mixin, "countModels");
		assertEquals(1, selected.size());
		assertTrue(selected.getFirst().contains("StandaloneModelLoader$LoadedModels"), selected.toString());
		assertTrue(mixin.methods.stream().noneMatch(m -> m.name.endsWith("$forbricOriginal")), "a callback-only handler needs no wrapper");
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin, NONE), "idempotence");
	}

	@Test void aTailCallbackTakingVanillasArgumentsIsWrappedToStillReceiveThem() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/AfterMixin", MM);
		source.handler("afterDiscovery", "(" + DISCOVER_ARGS + CIR + ")V", INJECT, "method", List.of("discoverModelDependencies"),
				"at", List.of(at("TAIL", null))).statik().code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		MethodNode outer = handler(mixin, "afterDiscovery");
		assertEquals(5, Type.getArgumentTypes(outer.desc).length, "vanilla's three, NeoForge's fourth, the callback");
		assertNotNull(MixinPlayerWorldCallbackAdapter.named(mixin, "afterDiscovery$forbricOriginal"));
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void theDiscoveryCapturedByOrdinalIsProved() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/DiscoveryMixin", MM);
		source.handler("watchDiscovery", "(" + CIR + "Lnet/minecraft/client/resources/model/ModelDiscovery;)V", INJECT, "method", List.of(DISCOVER),
				"at", List.of(at("NEW", RESOLVED_NEW))).statik().local(1, "ordinal", 0).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		CarpetMixinAdapterTest.verify(mixin);
		assertUntouched(source.build(), NONE);
	}

	@Test void aPointTheWiderMethodMakesMoreOftenStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/EveryForEachMixin", MM);
		source.handler("eachGroup", "(" + CIR + ")V", INJECT, "method", List.of(DISCOVER),
				"at", List.of(at("INVOKE", "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))).statik().code(RETURN);
		assertUntouched(source.build(), VANILLA);
	}

	// ---- CuboidModel.fromStream(Reader) -> UnbakedModelParser.parse(Reader) --------------------------------------------

	@Test void aPlainCallbackAtTheReaderMovesWithoutBeingCancellable() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/ReadMixin", MM);
		source.handler("beforeRead", "(" + CIR + ")V", INJECT, "method", List.of(LAMBDA), "at", List.of(at("INVOKE", FROM_STREAM))).statik().code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, NONE));
		assertEquals(PARSE, target(mixin, "beforeRead"));
		assertEquals(0, adapt(mixin, NONE), "idempotence");
	}

	@Test void anArgumentModifierOfTheReaderMoves() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/ReaderMixin", MM);
		source.handler("wrapReader", "(Ljava/io/Reader;)Ljava/io/Reader;", "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "method", List.of(LAMBDA),
				"at", List.of(at("INVOKE", FROM_STREAM))).statik().code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 0);
			code.visitInsn(Opcodes.ARETURN);
		});
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		assertEquals(PARSE, target(mixin, "wrapReader"));
	}

	@Test void oneCapturedIdentifierInsteadOfCreatesTwoIsProved() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/IdMixin", MM);
		source.handler("readModel", "(" + CIR + "Lnet/minecraft/resources/Identifier;)V", INJECT, "method", List.of(LAMBDA),
				"at", List.of(at("INVOKE", FROM_STREAM)), "cancellable", true).statik().local(1, "ordinal", 0).code(RETURN);
		ClassNode mixin = source.build();
		assertEquals(1, adapt(mixin, VANILLA));
		assertEquals(PARSE, target(mixin, "readModel"));
		assertUntouched(source.build(), NONE);
	}

	@Test void aWrapTypedByTheVanillaResultStays() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/models/mixin/WrapReadMixin", MM);
		source.handler("wrapRead", "(Ljava/io/Reader;" + OPERATION + ")Lnet/minecraft/client/resources/model/cuboid/CuboidModel;", WRAP,
				"method", List.of(LAMBDA), "at", List.of(at("INVOKE", FROM_STREAM))).statik().code(code -> {
			code.visitInsn(Opcodes.ACONST_NULL);
			code.visitInsn(Opcodes.ARETURN);
		});
		assertUntouched(source.build(), VANILLA);
	}
}
