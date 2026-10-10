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
import java.util.function.IntUnaryOperator;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Without the class a mod was compiled against (no native reference, or one whose hash does not match), a block
 * interaction control's {@code @Local}s are read off the merged body alone. An {@code ordinal}, {@code index} or
 * {@code name} describes the native body, and the platform's own locals shift every position in it — NeoForge keeps an
 * earlier block state in a lower slot where vanilla's call stood — so a discriminator is read only where it means the
 * same in both bodies, and two captures that name different native locals never read one slot. Written by a mason mod
 * (chisel on the server, chip on the client) and a lens mod on getSignal, none of them the adapter's sample, each a way
 * the sample never wrote: a capture of the position parameter by ordinal or by index, a capture by debug name, the
 * vanilla slot number of a body local, a second ordinal. Every case is also run against vanilla, where the producer
 * proof answers what the fallback refuses.
 */
class MixinFallbackCaptureDiscriminatorTest {
	private static final BiFunction<Ecosystem, String, ClassNode> VANILLA = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);
	private static final BiFunction<Ecosystem, String, ClassNode> NONE = (family, name) -> null;
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;", POS = "Lnet/minecraft/core/BlockPos;";
	private static final String DIRECTION = "Lnet/minecraft/core/Direction;", BLOCK = "Lnet/minecraft/world/level/block/Block;";
	private static final String SIGNAL = "net/minecraft/world/level/SignalGetter", GETTER = "Lnet/minecraft/world/level/BlockGetter;";
	private static final String CONDUCTOR = STATE + "isRedstoneConductor(" + GETTER + POS + ")Z";
	private static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode", SERVER_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String SET_BLOCK = "Lnet/minecraft/world/level/Level;setBlock(" + POS + STATE + "I)Z";
	private static final String REMOVE_BLOCK = "Lnet/minecraft/server/level/ServerLevel;removeBlock(" + POS + "Z)Z";

	private static int adapt(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references) {
		return MixinBlockInteractionAdapters.adapt(mixin, CarpetMixinAdapterTest::target, references);
	}

	/** The wrapper that took {@code name}'s injector: each capture, by its position among the wrapper's parameters, to the slot it reads. */
	private static Map<Integer, Object> wrapperCaptures(ClassNode mixin, String name) {
		MethodNode wrapper = MixinPlayerWorldCallbackAdapter.named(mixin, name);
		assertNotNull(MixinFit.injectorOf(wrapper), name + " is the injector now");
		assertNotNull(MixinPlayerWorldCallbackAdapter.named(mixin, name + "$forbricOriginal"), "the original is kept, called by the wrapper");
		Map<Integer, Object> out = new LinkedHashMap<>();
		if (wrapper.invisibleParameterAnnotations != null) for (int i = 0; i < wrapper.invisibleParameterAnnotations.length; i++) {
			List<AnnotationNode> table = wrapper.invisibleParameterAnnotations[i];
			if (table != null) for (AnnotationNode annotation : table) if (annotation.desc.equals(LOCAL)) out.put(i, MixinFit.value(annotation, "index"));
		}
		return out;
	}

	private static void assertUntouched(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references, String why) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(mixin, references), why);
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), why);
	}

	private static void assertAdapted(ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> references, String handler, Map<Integer, Object> captures,
			String why) throws Exception {
		assertEquals(1, adapt(mixin, references), why);
		assertEquals(captures, wrapperCaptures(mixin, handler), why);
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, adapt(mixin, references), why + ": idempotence");
	}

	private static String label(BiFunction<Ecosystem, String, ClassNode> references) {
		return references == VANILLA ? "proved against vanilla" : "read off the merged body alone";
	}

	// ---- the server's break: a mason mod's chisel ---------------------------------------------------------------------

	/** A mason mod's wrap of vanilla's removeBlock in destroyBlock, by a bare selector, taking {@code extras} after the Operation. */
	private static ClassNode chisel(String extras, Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/mason/mixin/ChiselMixin", SERVER_MODE);
		Handler handler = source.handler("chisel", "(Lnet/minecraft/server/level/ServerLevel;" + POS + "Z" + OPERATION + extras + ")Z", WRAP,
				"method", List.of("destroyBlock"), "at", List.of(at("INVOKE", REMOVE_BLOCK)))
				.code(code -> { code.visitInsn(Opcodes.ICONST_1); code.visitInsn(Opcodes.IRETURN); });
		sugar.accept(handler);
		return source.build();
	}

	/**
	 * NeoForge's destroyBlock keeps the state it read first (slot 2) beside the state playerWillDestroy returned (slot 6),
	 * the one it hands its removeBlock; natively only the returned one is live at vanilla's call, in slot 4. A second
	 * ordinal, or two ordinals, name a local vanilla does not have there; index 2 is vanilla's block entity. The merged
	 * body has two states there, so each would read one of them — the old reading gave every one of them slot 6.
	 */
	@Test void aChiselNamingAStateTheMergedBodyCannotPlaceStaysAsWritten() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			String label = label(references);
			assertUntouched(chisel(STATE + STATE, h -> h.local(4, "ordinal", 0).local(5, "ordinal", 1)), references, label + ": two ordinals, two native locals");
			assertUntouched(chisel(STATE, h -> h.local(4, "ordinal", 1)), references, label + ": a second state");
			assertUntouched(chisel(STATE, h -> h.local(4, "index", 2)), references, label + ": index 2 holds no state natively");
			// Vanilla's earlier state is not live at its call: by name it is NeoForge's slot 2, not the state the call is handed.
			assertUntouched(chisel(STATE, h -> h.local(4, "name", List.of("state"))), references, label + ": the state read first");
		}
	}

	/** Vanilla's own slot for the returned state: only the native body says what slot 4 held, and there it is proved by producer. */
	@Test void aChiselNamingTheStateByItsVanillaSlotIsProvedOnlyAgainstVanilla() throws Exception {
		assertUntouched(chisel(STATE, h -> h.local(4, "index", 4)), NONE, "a body local's slot number is a position NeoForge's locals shift");
		assertAdapted(chisel(STATE, h -> h.local(4, "index", 4)), VANILLA, "chisel", Map.of(6, 6), "slot 4 held what playerWillDestroy returned");
	}

	/**
	 * An undiscriminated state and an ordinal-0 state name the one native state at vanilla's call — what vanilla proves.
	 * The merged body alone cannot say an ordinal names the same local as the undiscriminated capture, so it reads them
	 * to one slot only with that proof.
	 */
	@Test void twoChiselCapturesOfOneSlotNeedTheProofThatTheyNameOneNativeLocal() throws Exception {
		assertUntouched(chisel(STATE + STATE, h -> h.local(4).local(5, "ordinal", 0)), NONE, "different discriminators, one slot");
		assertAdapted(chisel(STATE + STATE, h -> h.local(4).local(5, "ordinal", 0)), VANILLA, "chisel", Map.of(6, 6, 7, 6), "both vanilla's slot 4");
	}

	/**
	 * What the merged body and vanilla's descriptor answer alike: destroyBlock's position, by ordinal or by index — the
	 * descriptor orders the parameters before any body local — beside the returned state by the first ordinal past them.
	 */
	@Test void aChiselTakingThePositionByOrdinalOrIndexIsHandedDestroyBlocksParameter() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			String label = label(references);
			assertAdapted(chisel(POS, h -> h.local(4, "ordinal", 0)), references, "chisel", Map.of(6, 1), label + ": the position by ordinal");
			assertAdapted(chisel(POS, h -> h.local(4, "index", 1)), references, "chisel", Map.of(6, 1), label + ": the position by index");
			assertAdapted(chisel(STATE + POS, h -> h.local(4, "ordinal", 0).local(5, "index", 1)), references, "chisel", Map.of(6, 6, 7, 1),
					label + ": the returned state and the position");
		}
	}

	// ---- the client's break: the mason mod's chip ---------------------------------------------------------------------

	private static ClassNode chip(String extras, Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/mason/mixin/ChipMixin", GAME_MODE);
		Handler handler = source.handler("chip", "(Lnet/minecraft/world/level/Level;" + POS + STATE + "I" + OPERATION + extras + ")Z", WRAP,
				"method", List.of("destroyBlock(" + POS + ")Z"), "at", List.of(at("INVOKE", SET_BLOCK)))
				.code(code -> { code.visitInsn(Opcodes.ICONST_1); code.visitInsn(Opcodes.IRETURN); });
		sugar.accept(handler);
		return source.build();
	}

	/**
	 * NeoForge's client destroyBlock has three states live where it asks the old state's onDestroyedByPlayer (slots 2, 5
	 * and 7); vanilla had one at setBlock, oldState in slot 3, and oldBlock in slot 4.
	 */
	@Test void aChipNamingTheOldStateByVanillaSlotOrASecondOrdinalStaysWithoutVanilla() throws Exception {
		assertUntouched(chip(STATE, h -> h.local(5, "index", 3)), NONE, "vanilla's slot 3 is a position, not the variable");
		assertAdapted(chip(STATE, h -> h.local(5, "index", 3)), VANILLA, "chip", Map.of(8, 5), "slot 3 held vanilla's old state");
		for (var references : List.of(NONE, VANILLA)) {
			String label = label(references);
			assertUntouched(chip(STATE + STATE, h -> h.local(5, "ordinal", 0).local(6, "ordinal", 1)), references, label + ": two ordinals");
			assertUntouched(chip(POS, h -> h.local(5, "ordinal", 1)), references, label + ": a second position");
			// NeoForge's state after the break is named, but the call is handed the old state: two locals, no proof which.
			assertUntouched(chip(STATE, h -> h.local(5, "name", List.of("removedBlockState"))), references, label + ": NeoForge's own state");
		}
	}

	/** A debug name is the variable's own: the old block by name, which the call is not handed, and the old state by name, which it is. */
	@Test void aChipNamingTheOldStateAndBlockReadsTheVariablesOfThoseNames() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			assertAdapted(chip(STATE + BLOCK, h -> h.local(5, "name", List.of("oldState")).local(6, "name", List.of("oldBlock"))), references, "chip",
					Map.of(8, 5, 9, 6), label(references));
		}
	}

	// ---- getSignal: a lens mod ---------------------------------------------------------------------------------------

	private static ClassNode lens(String extras, Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/lens/mixin/LensMixin", SIGNAL);
		Handler handler = source.handler("focus", "(" + STATE + GETTER + POS + OPERATION + extras + ")Z", WRAP,
				"method", List.of("getSignal(" + POS + DIRECTION + ")I"), "at", List.of(at("INVOKE", CONDUCTOR)))
				.code(code -> {
					code.visitVarInsn(Opcodes.ALOAD, 4);
					array(code, 1, 2, 3);
					callBoolean(code);
					code.visitInsn(Opcodes.IRETURN);
				});
		sugar.accept(handler);
		return source.build();
	}

	/** getSignal's side, by ordinal or by index — the descriptor's — and twice over, once as argsOnly: one native parameter. */
	@Test void aLensTakingTheSideByOrdinalOrIndexIsHandedGetSignalsSide() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			String label = label(references);
			assertAdapted(lens(DIRECTION, h -> h.local(4, "ordinal", 0)), references, "focus", Map.of(5, 2), label + ": by ordinal");
			assertAdapted(lens(DIRECTION, h -> h.local(4, "index", 2)), references, "focus", Map.of(5, 2), label + ": by index");
			assertAdapted(lens(DIRECTION + DIRECTION, h -> h.local(4).local(5, "argsOnly", true)), references, "focus", Map.of(5, 2, 6, 2),
					label + ": one parameter, read twice");
		}
	}

	@Test void aLensNamingASideGetSignalDoesNotHaveStays() throws Exception {
		for (var references : List.of(NONE, VANILLA)) {
			String label = label(references);
			assertUntouched(lens(DIRECTION, h -> h.local(4, "index", 1)), references, label + ": slot 1 is the position");
			assertUntouched(lens(DIRECTION, h -> h.local(4, "ordinal", 1)), references, label + ": a second side");
		}
	}

	// ---- with the native body: two native locals never read one live slot --------------------------------------------

	private static final String HOST = "org/example/kiln/Furnace", CTX = "Lorg/example/kiln/Hearth;", VAL = "Lorg/example/kiln/Ember;";

	/**
	 * A method that reads its hearth's ember into one local per entry of {@code reads} (each by the named method), then
	 * calls {@code call} on the hearth, handing it the locals listed in {@code handed}; every local live to the end.
	 */
	private static MethodNode furnace(List<String> reads, String call, int... handed) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "stoke", "(" + CTX + ")V", null, null);
		LabelNode start = new LabelNode(new Label()), end = new LabelNode(new Label());
		method.instructions.add(start);
		method.localVariables = new ArrayList<>();
		method.localVariables.add(new LocalVariableNode("this", "L" + HOST + ";", null, start, end, 0));
		method.localVariables.add(new LocalVariableNode("hearth", CTX, null, start, end, 1));
		for (int i = 0; i < reads.size(); i++) {
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTX.substring(1, CTX.length() - 1), reads.get(i), "()" + VAL, false));
			method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2 + i));
			LabelNode from = new LabelNode(new Label());
			method.instructions.add(from);
			method.localVariables.add(new LocalVariableNode("ember" + i, VAL, null, from, end, 2 + i));
		}
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		StringBuilder desc = new StringBuilder("(");
		for (int slot : handed) { method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot)); desc.append(VAL); }
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTX.substring(1, CTX.length() - 1), call, desc + ")V", false));
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		method.instructions.add(end);
		method.maxLocals = 2 + reads.size();
		method.maxStack = 1 + handed.length;
		return method;
	}

	private static MethodNode kilnHandler(Consumer<Handler> sugar) {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/kiln/mixin/BellowsMixin", HOST);
		Handler handler = source.handler("blow", "(" + CTX + OPERATION + VAL + VAL + ")V", WRAP, "method", List.of("stoke"),
				"at", List.of(at("INVOKE", CTX + "flare()V"))).code(code -> code.visitInsn(Opcodes.RETURN));
		sugar.accept(handler);
		return MixinPlayerWorldCallbackAdapter.named(source.build(), "blow");
	}

	private static Map<Integer, Integer> landed(MethodNode handler, MethodNode reference, MethodNode live, String liveCall) {
		AbstractInsnNode nativePoint = MixinCallbackProofs.points(reference, MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst()).getFirst();
		List<AbstractInsnNode> livePoints = new ArrayList<>();
		for (AbstractInsnNode instruction : live.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(liveCall)) livePoints.add(instruction);
		return MixinCallbackProofs.replacedCallLocals(handler, HOST, reference, nativePoint, reference.desc, HOST, live, livePoints, IntUnaryOperator.identity());
	}

	/**
	 * Natively the furnace reads its ember twice, into two locals; the live one reads it once and hands that local to its
	 * own call. Both natively-distinct embers have one producer, spelled alike, so each alone finds the live local: two
	 * values natively, one slot live, which no capture may read for both. Read for one native local twice, or for two
	 * produced apart, each reads its own.
	 */
	@Test void twoNativeLocalsProducedAlikeAreNotReadFromOneLiveSlot() {
		MethodNode twice = furnace(List.of("ember", "ember"), "flare"), once = furnace(List.of("ember"), "flareWith", 2);
		assertNull(landed(kilnHandler(h -> h.local(2, "ordinal", 0).local(3, "ordinal", 1)), twice, once, "flareWith"));
		assertEquals(Map.of(2, 2, 3, 2), landed(kilnHandler(h -> h.local(2, "ordinal", 0).local(3, "index", 2)), twice, once, "flareWith"),
				"both name native slot 2");
		MethodNode apart = furnace(List.of("ember", "spark"), "flare"), kept = furnace(List.of("ember", "spark"), "flareWith", 2);
		assertEquals(Map.of(2, 2, 3, 3), landed(kilnHandler(h -> h.local(2, "ordinal", 0).local(3, "ordinal", 1)), apart, kept, "flareWith"));
	}
}
