/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * The break callback before vanilla's removeBlock, on the staged merged ServerPlayerGameMode, asking for the locals there
 * in ways Carpet does not: fewer captured locals, none, MixinExtras {@code @Local}s by type in either order; not
 * cancellable; the selector owner-qualified, the point with ordinal 0. Each lands after playerWillDestroy with every value
 * it asked for handed over by the merged slot its producer stored. Look-alikes — captures out of vanilla's order or past
 * the three, a {@code @Local} of the ambiguous state or by ordinal, an annotated capture, another occurrence — stay
 * byte-for-byte as compiled.
 */
@ResourceLock("system-properties")
class BlockBreakCallbackShapesTest {
	private static final String POS = MixinPlayerWorldCallbackAdapter.POS, CIR = MixinPlayerWorldCallbackAdapter.CIR;
	private static final String ENTITY = MixinPlayerWorldCallbackAdapter.ENTITY, STATE = MixinPlayerWorldCallbackAdapter.STATE;
	private static final String BLOCK = "L" + MixinPlayerWorldCallbackAdapter.BLOCK + ";";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	/** The merged destroyBlock's slots of the block entity, block and adjusted state (CarpetMixinAdapterTest checks the premise). */
	private static final int ENTITY_SLOT = 4, BLOCK_SLOT = 5, ADJUSTED_SLOT = 6;

	/** Carpet's mixin, renamed, with its break handler replaced by one of {@code extras} (locals capture when {@code capture}). */
	private static ClassNode variant(boolean capture, String... extras) throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.mixin("ServerPlayerGameMode_scarpetEventsMixin"));
		MethodNode carpet = mixin.methods.stream().filter(m -> m.desc.startsWith("(" + POS + CIR)).findFirst().orElseThrow();
		mixin.methods.remove(carpet);
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "breaker$onBreak", "(" + POS + CIR + String.join("", extras) + ")V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", MixinPlayerWorldCallbackAdapter.OLD_REMOVE, "ordinal", 0));
		inject.values = new ArrayList<>(List.of("method", List.of("L" + MixinPlayerWorldCallbackAdapter.GAME_MODE + ";" + MixinPlayerWorldCallbackAdapter.BREAK_HOST), "at", List.of(at)));
		if (capture) inject.values.addAll(List.of("locals", new String[] {"Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;", "CAPTURE_FAILSOFT"}));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxLocals = Type.getArgumentsAndReturnSizes(handler.desc) >> 2;
		mixin.methods.add(handler);
		return mixin;
	}

	private static MethodNode handler(ClassNode mixin) {
		return MixinPlayerWorldCallbackAdapter.named(mixin, "breaker$onBreak");
	}

	@SuppressWarnings("unchecked")
	private static void annotate(MethodNode handler, int parameter, AnnotationNode annotation) {
		int count = Type.getArgumentTypes(handler.desc).length;
		if (handler.invisibleParameterAnnotations == null) handler.invisibleParameterAnnotations = new List[count];
		handler.invisibleParameterAnnotations[parameter] = new ArrayList<>(List.of(annotation));
		handler.invisibleAnnotableParameterCount = count;
	}

	private static AnnotationNode local(Object... values) {
		AnnotationNode local = new AnnotationNode(LOCAL);
		if (values.length > 0) local.values = new ArrayList<>(List.of(values));
		return local;
	}

	/** Moved after playerWillDestroy, the locals capture gone, each extra a {@code @Local} of the merged slot given. */
	private static void assertServed(ClassNode mixin, int... slots) throws Exception {
		assertEquals(1, MixinPlayerWorldCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target));
		MethodNode handler = handler(mixin);
		AnnotationNode inject = MixinFit.injectorOf(handler);
		assertNull(MixinFit.value(inject, "locals"));
		assertEquals(MixinPlayerWorldCallbackAdapter.DROPS, MixinFit.value(MixinFit.atNodes(inject).getFirst(), "target"));
		int operands = Type.getArgumentTypes(handler.desc).length - slots.length;
		for (int p = 0; p < operands; p++) assertTrue(handler.invisibleParameterAnnotations == null || handler.invisibleParameterAnnotations[p] == null);
		for (int n = 0; n < slots.length; n++) {
			AnnotationNode local = handler.invisibleParameterAnnotations[operands + n].getFirst();
			assertEquals(LOCAL, local.desc);
			assertEquals(List.of("index", slots[n]), local.values);
		}
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, MixinPlayerWorldCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target), "idempotence");
	}

	@Test void aShorterCaptureNoneOrLocalsByTypeAreServed() throws Exception {
		assertServed(variant(true, ENTITY), ENTITY_SLOT);
		assertServed(variant(true, ENTITY, BLOCK), ENTITY_SLOT, BLOCK_SLOT);
		assertServed(variant(false));
		ClassNode locals = variant(false, BLOCK, ENTITY);
		annotate(handler(locals), 2, local());
		annotate(handler(locals), 3, local());
		assertServed(locals, BLOCK_SLOT, ENTITY_SLOT);
	}

	@Test void capturesVanillaCannotServeStayAsCompiled() throws Exception {
		assertUntouched(variant(true, BLOCK, ENTITY), "captured out of vanilla's order");
		assertUntouched(variant(true, ENTITY, BLOCK, STATE, STATE), "a fourth local");
		assertUntouched(variant(false, ENTITY), "an unannotated extra without a locals capture");
		ClassNode state = variant(false, STATE);
		annotate(handler(state), 2, local());
		assertUntouched(state, "a @Local of a type two locals have");
		ClassNode ordinal = variant(false, ENTITY);
		annotate(handler(ordinal), 2, local("ordinal", 0));
		assertUntouched(ordinal, "a @Local by ordinal");
		ClassNode coerced = variant(true, ENTITY);
		annotate(handler(coerced), 2, new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Coerce;"));
		assertUntouched(coerced, "an annotated capture");
		ClassNode second = variant(true, ENTITY);
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler(second))).getFirst(), "ordinal", 1);
		assertUntouched(second, "a second removeBlock");
	}

	private static void assertUntouched(ClassNode mixin, String why) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, MixinPlayerWorldCallbackAdapter.adapt(mixin, CarpetMixinAdapterTest::target), why);
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), why);
	}

	@Test void thePremiseSlotsAreTheProducersStores() {
		MethodNode host = MixinPlayerWorldCallbackAdapter.selector(CarpetMixinAdapterTest.target(MixinPlayerWorldCallbackAdapter.GAME_MODE),
				MixinPlayerWorldCallbackAdapter.BREAK_HOST);
		assertEquals(ENTITY_SLOT, MixinPlayerWorldCallbackAdapter.storedFrom(host, ENTITY_SLOT, MixinPlayerWorldCallbackAdapter.BLOCK_ENTITY).var);
		assertEquals(BLOCK_SLOT, MixinPlayerWorldCallbackAdapter.storedFrom(host, BLOCK_SLOT, MixinPlayerWorldCallbackAdapter.GET_BLOCK).var);
		assertEquals(ADJUSTED_SLOT, MixinPlayerWorldCallbackAdapter.storedFrom(host, ADJUSTED_SLOT, MixinPlayerWorldCallbackAdapter.WILL_DESTROY).var);
	}
}
