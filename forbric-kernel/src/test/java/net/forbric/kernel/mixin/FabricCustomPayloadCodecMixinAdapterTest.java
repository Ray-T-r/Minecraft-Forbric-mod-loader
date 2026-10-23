/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * fabric-networking's two payload-codec wrappers against a merged base whose {@code CustomPacketPayload.codec} takes
 * two more arguments. Moving the {@code @At} target alone would leave the handler the wrong shape — Mixin hands a
 * {@code @WrapOperation} handler one argument per target argument before its {@code Operation}, so the two new ones
 * arrive in the handler and have to be forwarded into the {@code Object[]} it passes to {@code Operation.call}.
 *
 * <p>Every class here is built in memory, and the widened handler is run through {@link BasicVerifier}: the point is
 * that the bytecode the adapter writes is well formed, which is what Mixin would refuse the mixin over.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricCustomPayloadCodecMixinAdapterTest {
	private static final String MIXIN = FabricCustomPayloadCodecMixinAdapter.MIXIN;
	private static final String TARGET = FabricCustomPayloadCodecMixinAdapter.TARGET;
	private static final String PAYLOAD = FabricCustomPayloadCodecMixinAdapter.PAYLOAD;
	private static final String OPERATION = FabricCustomPayloadCodecMixinAdapter.OPERATION;
	private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
	private static final String VANILLA_DESC = FabricCustomPayloadCodecMixinAdapter.VANILLA_DESC;
	private static final String MERGED_DESC = FabricCustomPayloadCodecMixinAdapter.MERGED_DESC;
	private static final String PROTOCOL = FabricCustomPayloadCodecMixinAdapter.PROTOCOL;
	private static final String FLOW = FabricCustomPayloadCodecMixinAdapter.FLOW;
	/** The two-argument wrapper fabric ships: {@code (FallbackProvider, List, Operation) StreamCodec}. */
	private static final String VANILLA_HANDLER = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;"
			+ "Ljava/util/List;L" + OPERATION + ";)" + "L" + STREAM_CODEC + ";";

	/** fabric's wrapper as compiled: one {@code Object[2]}, both arguments stored, forwarded to {@code Operation}. */
	private static MethodNode wrapper(int ordinal) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "wrap" + ordinal, VANILLA_HANDLER, null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE",
				"target", FabricCustomPayloadCodecMixinAdapter.VANILLA_CALL, "ordinal", ordinal));
		AnnotationNode annotation = new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		annotation.values = new ArrayList<>(List.of("method", List.of("<clinit>"), "at", List.of(at)));
		handler.visibleAnnotations = new ArrayList<>(List.of(annotation));
		handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		handler.instructions.add(new InsnNode(Opcodes.ICONST_2));
		handler.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		store(handler, 0, 0);
		store(handler, 1, 1);
		handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPERATION, "call",
				"([Ljava/lang/Object;)Ljava/lang/Object;", false));
		handler.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, STREAM_CODEC));
		handler.instructions.add(new InsnNode(Opcodes.ARETURN));
		handler.maxLocals = 3;
		handler.maxStack = 5;
		return handler;
	}

	private static void store(MethodNode handler, int index, int slot) {
		handler.instructions.add(new InsnNode(Opcodes.DUP));
		handler.instructions.add(new InsnNode(Opcodes.ICONST_0 + index));
		handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
		handler.instructions.add(new InsnNode(Opcodes.AASTORE));
	}

	private static ClassNode mixin() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		mixin.methods.add(wrapper(0));
		mixin.methods.add(wrapper(1));
		return mixin;
	}

	/** The merged class: {@code <clinit>} makes {@code four} four-argument calls and {@code vanilla} two-argument ones. */
	private static ClassNode target(int four, int vanilla) {
		ClassNode target = new ClassNode();
		target.version = Opcodes.V21;
		target.access = Opcodes.ACC_PUBLIC;
		target.name = TARGET;
		target.superName = "java/lang/Object";
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		for (int i = 0; i < vanilla; i++) clinit.instructions.add(codecCall(VANILLA_DESC));
		for (int i = 0; i < four; i++) clinit.instructions.add(codecCall(MERGED_DESC));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		clinit.maxLocals = 1;
		clinit.maxStack = 6;
		target.methods.add(clinit);
		return target;
	}

	/**
	 * A call to {@code codec}, with the arguments it takes and a {@code POP} after it. The merged form is answered by
	 * {@code staticCodec} below so the verifier sees the protocol, the flow and the four arguments on its stack.
	 */
	private static InsnList codecCall(String desc) {
		InsnList list = new InsnList();
		list.add(new InsnNode(Opcodes.ACONST_NULL));
		list.add(new InsnNode(Opcodes.ACONST_NULL));
		if (MERGED_DESC.equals(desc)) {
			list.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/network/ConnectionProtocol", "PLAY",
					PROTOCOL));
			list.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/network/protocol/PacketFlow", "CLIENTBOUND",
					FLOW));
		}
		list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD, "codec", desc, false));
		list.add(new InsnNode(Opcodes.POP));
		return list;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static AnnotationNode point(MethodNode handler) {
		return MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
	}

	private static void verify(ClassNode mixin) throws Exception {
		for (MethodNode method : mixin.methods) {
			if ((method.access & Opcodes.ACC_ABSTRACT) == 0) new Analyzer<>(new BasicVerifier()).analyze(mixin.name, method);
		}
	}

	/** How many entries the {@code Object[]} the wrapper builds holds, and which slots it reads to fill them. */
	private static int entries(MethodNode handler) {
		int entries = 0;
		for (AbstractInsnNode insn : handler.instructions) if (insn.getOpcode() == Opcodes.AASTORE) entries++;
		return entries;
	}

	private static List<Integer> slots(MethodNode handler) {
		List<Integer> slots = new ArrayList<>();
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof VarInsnNode var && !slots.contains(var.var)) slots.add(var.var);
		}
		java.util.Collections.sort(slots);
		return slots;
	}

	private static boolean sizeIsFour(MethodNode handler) {
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn.getOpcode() == Opcodes.ICONST_4) return true;
		}
		return false;
	}

	@Test void bothWrappersTakeAndForwardTheTwoArgumentsTheMergeAdded() throws Exception {
		ClassNode mixin = mixin();
		ClassNode merged = target(2, 0);
		byte[] targetBefore = bytes(merged);

		assertEquals(2, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> merged));
		for (int ordinal = 0; ordinal < 2; ordinal++) {
			MethodNode handler = mixin.methods.get(ordinal);
			assertEquals(FabricCustomPayloadCodecMixinAdapter.HANDLER_DESC, handler.desc,
					"the handler now takes the protocol and the flow before its Operation");
			assertEquals(FabricCustomPayloadCodecMixinAdapter.MERGED_CALL, MixinFit.value(point(handler), "target"),
					"the point names the four-argument call");
			assertEquals(ordinal, MixinFit.value(point(handler), "ordinal"), "the two calls stay told apart by ordinal");
			assertEquals(5, handler.maxLocals, "the two new slots are addressable, and the Operation moved past them");
			assertEquals(4, entries(handler), "the array Operation.call gets grows from two entries to four");
			assertTrue(sizeIsFour(handler), "and the size pushed before it is four, not two");
			assertEquals(List.of(0, 1, 2, 3, 4), slots(handler),
					"the two new arguments are read from the slots Mixin passes them in, and the Operation from the one it moved to");
		}
		verify(mixin);
		assertArrayEquals(targetBefore, bytes(merged), "the merged class is not edited; Mixin applies the handlers");
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> merged), "a second pass finds nothing to do");
	}

	@Test void aMergedClassStillMakingTheTwoArgumentCallIsLeftAlone() throws Exception {
		ClassNode mixin = mixin();
		byte[] before = bytes(mixin);
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(2, 1)),
				"the wrapper has something to bind to as compiled");
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aMergedClassMakingItOnceIsNotTheShapeThisWasWrittenFor() throws Exception {
		ClassNode mixin = mixin();
		byte[] before = bytes(mixin);
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(1, 0)),
				"fabric wraps both the PLAY and the CONFIGURATION call, so one is not the <clinit> this was written for");
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aPointWithoutAnOrdinalCannotTellTheTwoCallsApart() throws Exception {
		ClassNode mixin = mixin();
		for (MethodNode handler : mixin.methods) {
			AnnotationNode point = point(handler);
			for (int i = 0; i + 1 < point.values.size(); i += 2) {
				if ("ordinal".equals(point.values.get(i))) {
					point.values.remove(i + 1);
					point.values.remove(i);
					break;
				}
			}
		}
		byte[] before = bytes(mixin);
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(2, 0)));
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aSliceMeansThePointIsReadFromAnotherBody() throws Exception {
		ClassNode mixin = mixin();
		for (MethodNode handler : mixin.methods) {
			MixinFit.injectorOf(handler).values.addAll(List.of("slice", new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Slice;")));
		}
		byte[] before = bytes(mixin);
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(2, 0)));
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aHandlerWithoutTheOperationTailIsNotAWrapper() throws Exception {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		MethodNode handler = wrapper(0);
		handler.desc = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;"
				+ "Ljava/util/List;)" + "L" + STREAM_CODEC + ";";
		mixin.methods.add(handler);
		byte[] before = bytes(mixin);
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(2, 0)));
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aSelectorWrittenForAnotherMethodIsLeftAlone() throws Exception {
		ClassNode mixin = mixin();
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			for (int i = 0; i + 1 < injector.values.size(); i += 2) {
				if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, List.of("<init>"));
			}
		}
		byte[] before = bytes(mixin);
		assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(2, 0)));
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void theOffSwitchLeavesFabricsWrappersInPlace() throws Exception {
		String old = System.setProperty(FabricCustomPayloadCodecMixinAdapter.PROPERTY, "off");
		try {
			ClassNode mixin = mixin();
			byte[] before = bytes(mixin);
			assertEquals(0, FabricCustomPayloadCodecMixinAdapter.adapt(mixin, n -> target(2, 0)));
			assertArrayEquals(before, bytes(mixin));
		} finally {
			if (old == null) System.clearProperty(FabricCustomPayloadCodecMixinAdapter.PROPERTY);
			else System.setProperty(FabricCustomPayloadCodecMixinAdapter.PROPERTY, old);
		}
	}
}
