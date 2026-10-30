/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What the native reference may and may not testify to: a lambda the base reshaped from vanilla's, never one javac
 * merely renumbered (that name is a different lambda now) or one whose captures were only reordered (the permutation
 * census's case, proven by local names).
 */
class LambdaShapeEvidenceTest {
	private static final String SKY = "lambda$addSkyPass$0";
	private static final String VANILLA_SKY = "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V";
	private static final String NEO_SKY = "(Lnet/minecraft/client/renderer/state/level/SkyRenderState;Lorg/joml/Matrix4fc;"
			+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";

	@Test void aReshapedLambdaIsEvidence() {
		ClassNode vanilla = node(method(SKY, VANILLA_SKY)), genuine = node(method(SKY, NEO_SKY));
		assertEquals(List.of(VANILLA_SKY), LambdaShapeEvidence.reshaped(genuine, vanilla, SKY),
				"NeoForge's addSkyPass lambda takes another set of captures: a handler written for vanilla's was written for it");
	}

	@Test void aShapeTheClassStillHasIsNot() {
		ClassNode vanilla = node(method(SKY, VANILLA_SKY));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(node(method(SKY, VANILLA_SKY)), vanilla, SKY));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(node(method(SKY, NEO_SKY), method(SKY, VANILLA_SKY)), vanilla, SKY),
				"the merged base before the pruner: both bodies carried, and vanilla's shape is still declared");
	}

	@Test void aRenumberedLambdaIsNot() {
		ClassNode vanilla = node(method("lambda$tick$0", "(I)V"), method("lambda$tick$1", "(J)V"));
		ClassNode patched = node(method("lambda$tick$0", "(Ljava/lang/String;)V"), method("lambda$tick$1", "(I)V"),
				method("lambda$tick$2", "(J)V"));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(patched, vanilla, "lambda$tick$0"),
				"a patch put a lambda first: vanilla's lambda$tick$0 is lambda$tick$1 now, and nothing may be mapped onto the new $0");
	}

	@Test void aReorderedLambdaIsLeftToTheCensus() {
		ClassNode vanilla = node(method("lambda$load$1", "(Ljava/util/List;Ljava/lang/String;I)V"));
		ClassNode patched = node(method("lambda$load$1", "(ILjava/util/List;Ljava/lang/String;)V"));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(patched, vanilla, "lambda$load$1"));
		assertTrue(LambdaShapeEvidence.reordering("(ILjava/util/List;Ljava/lang/String;)V", "(Ljava/util/List;Ljava/lang/String;I)V"));
		assertFalse(LambdaShapeEvidence.reordering("(I)V", "(I)V"), "the same descriptor is not a reordering");
		assertFalse(LambdaShapeEvidence.reordering("(IJ)V", "(JI)Z"), "nor is another return type");
	}

	@Test void aLambdaThatOnlyGainedCapturesIsLeftToTheInsertionShim() {
		String fabric = "(Ljava/lang/String;Ljava/util/function/Consumer;)V";
		String carrier = "(Ljava/lang/String;Lnet/example/ShotEvent;Ljava/util/function/Consumer;)V";
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(node(method("lambda$grab$3", carrier)), node(method("lambda$grab$3", fabric)),
				"lambda$grab$3"), "Screenshot.grab's lambda gained an event between its captures: InsertedLambdaArgumentShim's case");
		assertTrue(LambdaShapeEvidence.insertion(carrier, fabric));
		assertFalse(LambdaShapeEvidence.insertion(NEO_SKY, VANILLA_SKY), "addSkyPass's captures were reordered as well as widened");
		assertFalse(LambdaShapeEvidence.insertion(fabric, fabric), "nothing inserted");
	}

	@Test void aNameTheClassDoesNotDeclareOrAMissingReferenceSaysNothing() {
		ClassNode vanilla = node(method(SKY, VANILLA_SKY));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(node(method("lambda$other$0", NEO_SKY)), vanilla, SKY));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(node(method(SKY, NEO_SKY)), null, SKY));
		assertEquals(List.of(), LambdaShapeEvidence.reshaped(node(method("render", NEO_SKY)), node(method("render", VANILLA_SKY)), "render"),
				"only a lambda's name is ambiguous this way");
	}

	@Test void thePrunersRecordStillCountsAndIsMarkedAsSuch() {
		var saved = net.forbric.kernel.transform.DuplicateLambdaPruneInjector.droppedForTest();
		try {
			String owner = "net/example/LambdaShapeEvidenceTestTarget";
			net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(owner, SKY, VANILLA_SKY);
			ClassNode target = node(method(SKY, NEO_SKY));
			target.name = owner;
			assertEquals(List.of(new LambdaShapeEvidence.Shape(VANILLA_SKY, true)), LambdaShapeEvidence.vanished(target, SKY),
					"unbound references (no game): the pruner's record alone, as before");
		} finally {
			net.forbric.kernel.transform.DuplicateLambdaPruneInjector.restoreDroppedForTest(saved);
		}
	}

	/**
	 * The reference is read only for a lambda the class declares. Every bare selector of a handler that does not bind as
	 * compiled reaches {@link LambdaShapeEvidence#vanished}, and a read re-reads, re-hashes and re-parses the whole class:
	 * for a method name, or a lambda this class has no lambda of, it could say nothing, so it must not cost anything.
	 */
	@Test void theReferenceIsReadOnlyForALambdaTheClassDeclares() {
		List<String> reads = new ArrayList<>();
		ClassNode vanilla = node(method(SKY, VANILLA_SKY), method("render", "()V"));
		java.util.function.Function<String, ClassNode> reference = owner -> {
			reads.add(owner);
			return vanilla;
		};
		ClassNode genuine = node(method(SKY, NEO_SKY), method("render", "(I)V"));
		assertEquals(List.of(), LambdaShapeEvidence.vanished(genuine, "render", reference));
		assertEquals(List.of(), LambdaShapeEvidence.vanished(genuine, "lambda$addSkyPass$7", reference));
		assertEquals(List.of(), reads, "a method's name, or a lambda the class does not declare, reads nothing");
		assertEquals(List.of(new LambdaShapeEvidence.Shape(VANILLA_SKY, false)), LambdaShapeEvidence.vanished(genuine, SKY, reference));
		assertEquals(List.of(genuine.name), reads, "the declared lambda reads its class once");
	}

	private static ClassNode node(MethodNode... methods) {
		ClassNode node = new ClassNode();
		node.name = "net/example/Target";
		node.methods = new ArrayList<>(List.of(methods));
		return node;
	}

	private static MethodNode method(String name, String desc) {
		return new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, name, desc, null, null);
	}
}
