/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import static net.forbric.kernel.mixin.CallbackSourceFixture.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * {@code MixinBlockInteractionAdapters} serves whatever {@code @Local}s a redstone, bounce or block-break control
 * captures — annotated, or the target arguments Mixin appends after the {@code Operation}, which are the
 * {@code @Local(argsOnly = true)}s they stand for — each handed the slot that holds its value where the platform's
 * replacement call stands: proved by producer against the class the mod was compiled against, or read off the live body
 * without it. Never by a list of captures one mod declared. Written by mods that are not the adapter's sample (a relay
 * mod on getSignal, a trampoline mod on bounciness, a quarry mod on block breaking), each a different way: appended
 * arguments, fewer or other captures, a capture named by its debug name. A look-alike whose capture is no value of the
 * method, or that asks for something no {@code @Local} is, stays byte-for-byte as written.
 */
class MixinBlockInteractionCaptureTest {
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final BiFunction<Ecosystem, String, ClassNode> NONE = (family, name) -> null;
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;", POS = "Lnet/minecraft/core/BlockPos;";
	private static final String DIRECTION = "Lnet/minecraft/core/Direction;", BLOCK = "Lnet/minecraft/world/level/block/Block;";
	private static final String SIGNAL = "net/minecraft/world/level/SignalGetter", GETTER = "Lnet/minecraft/world/level/BlockGetter;";
	private static final String CONDUCTOR = STATE + "isRedstoneConductor(" + GETTER + POS + ")Z", GET_SIGNAL = "getSignal(" + POS + DIRECTION + ")I";
	private static final String ENTITY = "net/minecraft/world/entity/Entity", RESTITUTE = "restituteMovementAfterCollisions(" + STATE + "ZZLnet/minecraft/world/phys/Vec3;)V";
	private static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode", SERVER_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String DESTROY = "destroyBlock(" + POS + ")Z", PACKET = "Lnet/minecraft/network/protocol/Packet;";
	private static final String START_LAMBDA = "lambda$startDestroyBlock$1", START_DESC = "(" + STATE + POS + DIRECTION + "I)" + PACKET;
	private static final String SET_BLOCK = "Lnet/minecraft/world/level/Level;setBlock(" + POS + STATE + "I)Z";
	private static final String REMOVE_BLOCK = "Lnet/minecraft/server/level/ServerLevel;removeBlock(" + POS + "Z)Z";

	private static int adapt(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinBlockInteractionAdapters.adapt(mixin, CarpetMixinAdapterTest::target, references);
	}

	/** {@code return original.call(args...) as boolean}: the Operation at {@code operation}, the arguments' slots. */
	private static Consumer<MethodVisitor> passes(int operation, int... slots) {
		return code -> {
			code.visitVarInsn(Opcodes.ALOAD, operation);
			array(code, slots);
			callBoolean(code);
			code.visitInsn(Opcodes.IRETURN);
		};
	}

	/**
	 * For the wrapper that took {@code name}'s injector: each capture it hands the original, by its position among the
	 * wrapper's parameters, to the {@code @Local} index it reads; the original keeps its body.
	 */
	private static Map<Integer, Object> wrapperCaptures(ClassNode mixin, String name) {
		MethodNode wrapper = MixinPlayerWorldCallbackAdapter.named(mixin, name);
		assertNotNull(MixinFit.injectorOf(wrapper), name + " is the injector now");
		assertNotNull(MixinPlayerWorldCallbackAdapter.named(mixin, name + "$forbricOriginal"), "the original is kept, called by the wrapper");
		Map<Integer, Object> out = new LinkedHashMap<>();
		if (wrapper.invisibleParameterAnnotations != null) for (int i = 0; i < wrapper.invisibleParameterAnnotations.length; i++) {
			List<AnnotationNode> table = wrapper.invisibleParameterAnnotations[i];
			if (table == null) continue;
			for (AnnotationNode annotation : table) if (annotation.desc.equals(LOCAL)) out.put(i, MixinFit.value(annotation, "index"));
		}
		return out;
	}

	private static void assertUntouched(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin, references));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	// ---- the released controls: each capture reads what it read natively ---------------------------------------------

	@Test void theReleasedControlsHandEachCaptureTheSlotHoldingItsNativeValue() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String label = references == VANILLA ? "proved against vanilla" : "read off the live body";
			ClassNode signal = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/SignalGetterMixin");
			assertEquals(1, adapt(signal, references), label);
			assertEquals(Map.of(5, 2), wrapperCaptures(signal, "skip"), label + ": getSignal's side");
			ClassNode entity = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/EntityMixin");
			assertEquals(1, adapt(entity, references), label);
			assertEquals(Map.of(4, 2), wrapperCaptures(entity, "getBlockBounciness"), label + ": the state NeoForge's host takes second");
			ClassNode client = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/MultiPlayerGameModeMixin");
			assertEquals(2, adapt(client, references), label);
			assertEquals(Map.of(8, 5, 9, 6), wrapperCaptures(client, "breakBlock"), label + ": the old state and block, not NeoForge's earlier state");
			// Natively the one BlockState local at removeBlock is the state playerWillDestroy returned; NeoForge's body
			// keeps its own earlier state in a lower slot, which ordinal 0 would read there.
			ClassNode server = CreateGuestMixinFixture.mixin("com/zurrtum/create/mixin/ServerPlayerGameModeMixin");
			assertEquals(1, adapt(server, references), label);
			assertEquals(Map.of(6, 6, 7, 5), wrapperCaptures(server, "removeBlock"), label + ": the adjusted state and the block");
			for (ClassNode mixin : List.of(signal, entity, client, server)) { CarpetMixinAdapterTest.verify(mixin); assertEquals(0, adapt(mixin, references)); }
		}
	}

	// ---- getSignal: a relay mod -------------------------------------------------------------------------------------

	/** A relay mod's damper wrap of vanilla's isRedstoneConductor, taking {@code extras} after the Operation. */
	private static Handler relay(CallbackSourceFixture source, String selector, String extras) {
		return source.handler("damp", "(" + STATE + GETTER + POS + OPERATION + extras + ")Z", WRAP, "method", List.of(selector),
				"at", List.of(at("INVOKE", CONDUCTOR))).code(passes(4, 1, 2, 3));
	}

	@Test void aDamperTakingGetSignalsArgumentsAsMixinAppendsThemIsHandedThemWhereTheWeakPowerQueryStands() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			// A bare selector binds getSignal in the class the mod was compiled against; without it, the descriptor.
			String selector = references == VANILLA ? "getSignal" : GET_SIGNAL;
			CallbackSourceFixture appended = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(appended, selector, POS + DIRECTION);
			ClassNode mixin = appended.build();
			assertEquals(1, adapt(mixin, references), selector);
			assertEquals(Map.of(5, 1, 6, 2), wrapperCaptures(mixin, "damp"), "getSignal's own position and side");
			CarpetMixinAdapterTest.verify(mixin);
			assertEquals(0, adapt(mixin, references), "idempotence");

			// The side alone, annotated: the same slot as the appended side.
			CallbackSourceFixture annotated = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(annotated, selector, DIRECTION).local(4, "argsOnly", true);
			ClassNode local = annotated.build();
			assertEquals(1, adapt(local, references));
			assertEquals(Map.of(5, 2), wrapperCaptures(local, "damp"));

			// No capture at all: still the same control.
			CallbackSourceFixture bare = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(bare, selector, "");
			ClassNode none = bare.build();
			assertEquals(1, adapt(none, references));
			assertEquals(Map.of(), wrapperCaptures(none, "damp"));
			CarpetMixinAdapterTest.verify(none);
		}
	}

	@Test void aDamperCapturingTheQueriedStateReadsTheStateLocalTheWeakPowerQueryIsAsked() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? "getSignal" : GET_SIGNAL;
			CallbackSourceFixture source = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(source, selector, STATE).local(4);
			ClassNode mixin = source.build();
			assertEquals(1, adapt(mixin, references));
			assertEquals(Map.of(5, 3), wrapperCaptures(mixin, "damp"), "getSignal's own block state local");
		}
	}

	@Test void aDamperAskingForAValueGetSignalDoesNotHaveOrForSharedStateStays() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? "getSignal" : GET_SIGNAL;
			// getSignal's first argument is a position, not a side: Mixin refuses this handler on it.
			CallbackSourceFixture side = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(side, selector, DIRECTION);
			assertUntouched(side.build(), references);
			// A @Share is no value of the method.
			CallbackSourceFixture shared = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(shared, selector, "Lcom/llamalad7/mixinextras/sugar/ref/LocalBooleanRef;").share(4, "damped");
			assertUntouched(shared.build(), references);
			// No ItemStack is live where vanilla or NeoForge asks.
			CallbackSourceFixture stack = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
			relay(stack, selector, "Lnet/minecraft/world/item/ItemStack;").local(4);
			assertUntouched(stack.build(), references);
		}
	}

	// ---- bounciness: a trampoline mod -------------------------------------------------------------------------------

	/** A trampoline mod's wrap of vanilla's getBlockBounciness(Block), taking {@code extras} after the Operation. */
	private static ClassNode trampoline(String selector, String extras) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/trampoline/mixin/SpringMixin", ENTITY);
		source.handler("spring", "(L" + ENTITY + ";" + BLOCK + OPERATION + extras + ")D", WRAP, "method", List.of(selector),
				"at", List.of(at("INVOKE", "L" + ENTITY + ";getBlockBounciness(" + BLOCK + ")D"))).code(code -> {
			code.visitVarInsn(Opcodes.ALOAD, 3);
			array(code, 1, 2);
			code.visitMethodInsn(Opcodes.INVOKEINTERFACE, OPERATION.substring(1, OPERATION.length() - 1), "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true);
			code.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double");
			code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
			code.visitInsn(Opcodes.DRETURN);
		});
		return source.build();
	}

	@Test void aSpringTakingTheCollidedStateAsAppendedReadsItWhereNeoForgesHostTakesIt() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? "restituteMovementAfterCollisions" : RESTITUTE;
			ClassNode mixin = trampoline(selector, STATE);
			assertEquals(1, adapt(mixin, references), selector);
			assertEquals(Map.of(4, 2), wrapperCaptures(mixin, "spring"), "the state, which NeoForge's host takes after its position");
			CarpetMixinAdapterTest.verify(mixin);
		}
		// The state and both booleans, a longer run of the same arguments: the two booleans are told apart by their
		// producers in vanilla's body (without it, two arguments of one type are not told apart).
		ClassNode longer = trampoline("restituteMovementAfterCollisions", STATE + "ZZ");
		assertEquals(1, adapt(longer, VANILLA));
		assertEquals(Map.of(4, 2, 5, 3, 6, 4), wrapperCaptures(longer, "spring"));
		CarpetMixinAdapterTest.verify(longer);
	}

	@Test void aSpringAppendingWhatTheNativeMethodDoesNotTakeFirstStays() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? "restituteMovementAfterCollisions" : RESTITUTE;
			assertUntouched(trampoline(selector, "Lnet/minecraft/world/phys/Vec3;"), references);
			assertUntouched(trampoline(selector, POS), references);
		}
	}

	// ---- left click: a quarry mod's wrap in the start-destroy lambda --------------------------------------------------

	private static ClassNode quarry(String selector, String extras, Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/quarry/mixin/PickMixin", GAME_MODE);
		Handler handler = source.handler("pick", "(L" + GAME_MODE + ";" + POS + OPERATION + extras + ")Z", WRAP, "method", List.of(selector),
				"at", List.of(at("INVOKE", "L" + GAME_MODE + ";" + DESTROY))).code(passes(3, 1, 2));
		sugar.accept(handler);
		return source.build();
	}

	/**
	 * Written with vanilla's descriptor, the selector names a method the merged game declares under another one: it moves
	 * to NeoForge's lambda. Written as a bare name, it already binds NeoForge's lambda, the first of that name, and does not
	 * move. Either way NeoForge's lambda takes its event between the state and the position: Mixin would append that
	 * event where the handler takes the position, so each appended value names its slot there.
	 */
	@Test void aPickTakingTheLambdasCapturesAsAppendedLandsInNeoForgesLambdaWithEachPinned() throws Exception {
		ClassNode merged = CarpetMixinAdapterTest.target(GAME_MODE);
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? START_LAMBDA : START_LAMBDA + START_DESC;
			ClassNode mixin = quarry(selector, STATE + POS + DIRECTION, handler -> { });
			assertEquals(1, adapt(mixin, references), selector);
			MethodNode pick = MixinPlayerWorldCallbackAdapter.named(mixin, "pick");
			MethodNode bound = MixinTargetSelectors.one(pick, merged);
			assertNotNull(bound, selector);
			assertTrue(bound.desc.startsWith("(" + STATE + "Lnet/neoforged/"), bound.desc);
			assertEquals(List.of(1, 3, 4), List.of(index(pick, 3), index(pick, 4), index(pick, 5)));
			CarpetMixinAdapterTest.verify(mixin);
			assertEquals(0, adapt(mixin, references), "idempotence");
		}
		// The sample's form, a bare name with the side as an @Local(argsOnly = true): MixinExtras reads the one side of
		// NeoForge's lambda as written, so nothing is changed.
		ClassNode annotated = quarry(START_LAMBDA, DIRECTION, handler -> handler.local(3, "argsOnly", true));
		assertUntouched(annotated, VANILLA);
	}

	@Test void aPickWhoseCaptureIsNoValueOfTheLambdaStays() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			String selector = references == VANILLA ? START_LAMBDA : START_LAMBDA + START_DESC;
			// The lambda's first capture is the state, not the side.
			assertUntouched(quarry(selector, DIRECTION, handler -> { }), references);
			// No ItemStack is live in the lambda: the capture holds nothing there to be proved.
			assertUntouched(quarry(selector, "Lnet/minecraft/world/item/ItemStack;", handler -> handler.local(3)), references);
		}
	}

	private static Object index(MethodNode handler, int parameter) {
		AnnotationNode local = MixinStubRebind.sugar(handler, parameter, MixinRetarget.LOCAL_SUGAR);
		assertNotNull(local, "parameter " + parameter + " was given the @Local it stood for");
		return MixinFit.value(local, "index");
	}

	// ---- block breaking: the quarry mod's break controls ------------------------------------------------------------

	private static ClassNode clientBreak(String extras, Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/quarry/mixin/SeamMixin", GAME_MODE);
		Handler handler = source.handler("seam", "(Lnet/minecraft/world/level/Level;" + POS + STATE + "I" + OPERATION + extras + ")Z", WRAP,
				"method", List.of(DESTROY), "at", List.of(at("INVOKE", SET_BLOCK))).code(code -> { code.visitInsn(Opcodes.ICONST_1); code.visitInsn(Opcodes.IRETURN); });
		sugar.accept(handler);
		return source.build();
	}

	private static ClassNode serverBreak(String extras, Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/quarry/mixin/VeinMixin", SERVER_MODE);
		Handler handler = source.handler("vein", "(Lnet/minecraft/server/level/ServerLevel;" + POS + "Z" + OPERATION + extras + ")Z", WRAP,
				"method", List.of(DESTROY), "at", List.of(at("INVOKE", REMOVE_BLOCK))).code(passes(4, 1, 2));
		sugar.accept(handler);
		return source.build();
	}

	@Test void aBreakControlWithOtherCapturesIsHandedEachWhereThePlatformsCallStands() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			// destroyBlock's position, appended, then the block alone where the sample took the state and the block.
			ClassNode client = clientBreak(POS + BLOCK, handler -> handler.local(6));
			assertEquals(1, adapt(client, references));
			assertEquals(Map.of(8, 1, 9, 6), wrapperCaptures(client, "seam"), "destroyBlock's position and NeoForge's block local");
			CarpetMixinAdapterTest.verify(client);
			// The state alone, named by its debug name where the sample used an ordinal.
			ClassNode server = serverBreak(STATE, handler -> handler.local(4, "name", List.of("adjustedState")));
			assertEquals(1, adapt(server, references));
			assertEquals(Map.of(6, 6), wrapperCaptures(server, "vein"), "the state playerWillDestroy returned, at both of NeoForge's calls");
			CarpetMixinAdapterTest.verify(server);
		}
	}

	@Test void aBreakControlCapturingWhatNoNativeLocalHoldsStays() throws Exception {
		for (var references : List.of(VANILLA, NONE)) {
			// No ItemStack local is live at either call.
			assertUntouched(clientBreak("Lnet/minecraft/world/item/ItemStack;", handler -> handler.local(5)), references);
			// destroyBlock takes no block state: an appended one is not its argument.
			assertUntouched(clientBreak(STATE, handler -> { }), references);
		}
		// Natively one BlockState is live at removeBlock: a second by ordinal names nothing, whatever NeoForge's body holds.
		assertUntouched(serverBreak(STATE, handler -> handler.local(4, "ordinal", 1)), VANILLA);
	}

	// ---- the shape a control is read in -----------------------------------------------------------------------------

	@Test void readAgainstTheMethodItIsWrittenForAnAppendedArgumentServesWhereItsLocalWould() throws Exception {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/relay/mixin/DamperMixin", SIGNAL);
		relay(source, GET_SIGNAL, POS + DIRECTION);
		MethodNode handler = MixinPlayerWorldCallbackAdapter.named(source.build(), "damp");
		MethodNode written = MixinPlayerWorldCallbackAdapter.selector(CarpetMixinAdapterTest.target(SIGNAL), GET_SIGNAL);
		String operands = "(" + STATE + GETTER + POS + OPERATION + ")Z";
		MixinHandlerShape.Want[] wants = {MixinHandlerShape.Want.local(POS), MixinHandlerShape.Want.local(DIRECTION)};
		assertFalse(MixinCallbackShape.shape(handler, operands, wants), "read alone, an appended value is no @Local");
		assertTrue(MixinCallbackShape.shape(handler, written, operands, wants), "read against getSignal, it is getSignal's position and side");
		assertTrue(MixinCallbackShape.captures(handler, written, operands));
		assertFalse(MixinCallbackShape.captures(handler, null, operands), "alone, nothing says whose arguments they are");
		MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC, "getSignal", "(" + DIRECTION + POS + ")I", null, null);
		assertFalse(MixinCallbackShape.captures(handler, other, operands), "written for a method taking its side first, Mixin refuses it");
	}
}
