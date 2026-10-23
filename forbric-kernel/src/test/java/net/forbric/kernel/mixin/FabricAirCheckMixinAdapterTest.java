/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * fabric-block-api's two chunk-section air checks against a merged base that answers the same question with
 * {@code isEmpty()}. The {@code @Redirect} moves onto the call the merge makes, and stays as compiled wherever the
 * merged class still makes the vanilla call, makes neither call, or is not the mixin the redirect was written for.
 *
 * <p>Every class here is built in memory, so the test reads the same on a machine with no game files: what it pins is
 * the adapter's own decision, not the merge's output.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricAirCheckMixinAdapterTest {
	private static final String BLOCK_STATE = FabricAirCheckMixinAdapter.BLOCK_STATE;
	private static final String VANILLA_CALL = FabricAirCheckMixinAdapter.VANILLA_CALL;
	private static final String MERGED_CALL = FabricAirCheckMixinAdapter.MERGED_CALL;
	/** The two mixins fabric ships, and the merged class each redirect points into. */
	private static final String[][] CASES = {
			{ "net/fabricmc/fabric/mixin/block/LevelChunkSectionMixin", "net/minecraft/world/level/chunk/LevelChunkSection" },
			{ "net/fabricmc/fabric/mixin/block/ChunkSectionBlockStateCounterMixin",
					"net/minecraft/world/level/chunk/LevelChunkSection$1BlockCounter" },
	};

	/** fabric's mixin, named as the adapter keys on it, with one {@code @Redirect} of the vanilla air check. */
	private static ClassNode mixin() {
		return mixin(CASES[0][0]);
	}

	private static ClassNode mixin(String name) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		MethodNode redirect = new MethodNode(Opcodes.ACC_PRIVATE, "isAir", "(L" + BLOCK_STATE + ";)Z", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", VANILLA_CALL));
		AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		annotation.values = new ArrayList<>(List.of("method", List.of("setBlockState"), "at", List.of(at)));
		redirect.visibleAnnotations = new ArrayList<>(List.of(annotation));
		redirect.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		redirect.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_STATE, "isAir", "()Z", false));
		redirect.instructions.add(new InsnNode(Opcodes.IRETURN));
		redirect.maxLocals = 1;
		redirect.maxStack = 1;
		mixin.methods.add(redirect);
		return mixin;
	}

	/** The merged class: its {@code setBlockState} makes exactly the calls asked for, and nothing else. */
	private static ClassNode target(String name, boolean isEmpty, boolean isAir) {
		ClassNode target = new ClassNode();
		target.version = Opcodes.V21;
		target.access = Opcodes.ACC_PUBLIC;
		target.name = name;
		target.superName = "java/lang/Object";
		MethodNode host = new MethodNode(Opcodes.ACC_PUBLIC, "setBlockState", "(L" + BLOCK_STATE + ";)V", null, null);
		if (isEmpty) host.instructions.add(call("isEmpty"));
		if (isAir) host.instructions.add(call("isAir"));
		host.instructions.add(new InsnNode(Opcodes.RETURN));
		host.maxLocals = 2;
		host.maxStack = 1;
		target.methods.add(host);
		return target;
	}

	private static InsnList call(String name) {
		InsnList list = new InsnList();
		list.add(new VarInsnNode(Opcodes.ALOAD, 1));
		list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_STATE, name, "()Z", false));
		list.add(new InsnNode(Opcodes.POP));
		return list;
	}

	private static AnnotationNode point(ClassNode mixin) {
		return MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst();
	}

	private static byte[] bytes(ClassNode node) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The redirect moved onto the call the merge makes, and nothing else about the mixin changed. */
	private static void assertMoved(String mixinName, String targetName) {
		ClassNode mixin = mixin();
		ClassNode merged = target(targetName, true, false);
		byte[] targetBefore = bytes(merged);
		MethodNode handler = mixin.methods.getFirst();
		String descBefore = handler.desc;

		assertEquals(1, FabricAirCheckMixinAdapter.adapt(mixin, n -> merged), mixinName);
		assertEquals(MERGED_CALL, MixinFit.value(point(mixin), "target"), "the point names the call the merge makes");
		assertEquals("INVOKE", MixinFit.value(point(mixin), "value"), "the point is still an INVOKE");
		assertEquals(List.of("setBlockState"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")),
				"the host it was written for is unchanged");
		assertEquals(descBefore, handler.desc, "the handler still takes the BlockState and answers whether it is air");
		assertEquals(List.of(Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.IRETURN), opcodes(handler),
				"the handler's body is untouched");
		assertArrayEquals(targetBefore, bytes(merged), "the merged class is not edited; Mixin applies the handler");
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> merged), "a second pass finds nothing to do");
	}

	private static List<Integer> opcodes(MethodNode method) {
		List<Integer> opcodes = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) opcodes.add(insn.getOpcode());
		return opcodes;
	}

	@Test void bothOfFabricsAirChecksMoveOntoTheCallTheMergeMakes() {
		for (String[] row : CASES) assertMoved(row[0], row[1]);
	}

	@Test void aRedirectOfAnotherCallIsNotOurs() {
		ClassNode mixin = mixin();
		AnnotationNode at = point(mixin);
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if ("target".equals(at.values.get(i))) at.values.set(i + 1, "L" + BLOCK_STATE + ";defaultBlockState()L" + BLOCK_STATE + ";");
		}
		byte[] before = bytes(mixin);
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> target(CASES[0][1], true, false)));
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aMergedClassThatStillMakesTheVanillaCallIsLeftAlone() {
		ClassNode mixin = mixin();
		byte[] before = bytes(mixin);
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> target(CASES[0][1], false, true)),
				"the test is still there, so the redirect has something to bind to");
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aMergedClassWhereTheTestIsGoneAltogetherIsLeftAlone() {
		ClassNode mixin = mixin();
		byte[] before = bytes(mixin);
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> target(CASES[0][1], false, false)),
				"neither call is made, so redirecting onto isEmpty() would invent a test that is not there");
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void bothCallsPresentIsNotTheShapeThisWasWrittenFor() {
		ClassNode mixin = mixin();
		byte[] before = bytes(mixin);
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> target(CASES[0][1], true, true)),
				"vanilla's call is still made somewhere in the class, so the merged base is not the one seen here");
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void aMixinThatIsNotOnesOfTheseTwoIsLeftAlone() {
		ClassNode mixin = mixin("net/example/other/AirMixin");
		byte[] before = bytes(mixin);
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> target(CASES[0][1], true, false)));
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void anAbsentTargetIsLeftAlone() {
		ClassNode mixin = mixin();
		byte[] before = bytes(mixin);
		assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> null), "the merged class was not found");
		assertArrayEquals(before, bytes(mixin));
	}

	@Test void theOffSwitchLeavesFabricsRedirectsInPlace() {
		String old = System.setProperty(FabricAirCheckMixinAdapter.PROPERTY, "off");
		try {
			ClassNode mixin = mixin();
			byte[] before = bytes(mixin);
			assertEquals(0, FabricAirCheckMixinAdapter.adapt(mixin, n -> target(CASES[0][1], true, false)));
			assertArrayEquals(before, bytes(mixin));
		} finally {
			if (old == null) System.clearProperty(FabricAirCheckMixinAdapter.PROPERTY);
			else System.setProperty(FabricAirCheckMixinAdapter.PROPERTY, old);
		}
	}
}
