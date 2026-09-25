package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The two popular-pack destroyBlock mixins, as shipped, against the real merged and vanilla bodies. */
@ResourceLock("system-properties")
class FabricBlockBreakMixinAdapterTest {
	private static final Path MODS = Path.of("run/client-popular/mods");
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";

	@AfterEach void reset() {
		System.clearProperty(FabricBlockBreakMixinAdapter.PROPERTY);
	}

	@Test void architecturysBreakHandlerCapturesNeoForgesFrameAndStillGetsItsTwoValues() throws Exception {
		ClassNode mixin = architectury(), target = merged();
		assertEquals(1, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));

		MethodNode outer = method(mixin, "onBreak");
		assertEquals("(Lnet/minecraft/core/BlockPos;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
				+ STATE + "Lnet/neoforged/neoforge/event/level/block/BreakBlockEvent;" + ENTITY + ")V", outer.desc,
				"exactly the merged frame at the first getBlock(): state, event, block entity");
		assertNotNull(MixinFit.injectorOf(outer));
		MethodNode inner = method(mixin, "onBreak" + MixinHandlerShim.INNER_SUFFIX);
		assertNull(MixinFit.injectorOf(inner), "the original handler is now only called");
		List<Integer> loads = java.util.Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 2, 5, 3), loads, "this, pos, callback, then BlockEntity (slot 5) and BlockState (slot 3)");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "a second pass changes nothing");
	}

	@Test void apolisHarvestModifierMovesToTheOnlyBooleanNeoForgeHasAtMineBlock() throws Exception {
		ClassNode mixin = apoli(), target = merged();
		assertEquals(1, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertEquals(0, MixinFit.value(MixinFit.injectorOf(method(mixin, "modifyEffectiveTool")), "ordinal"));
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "ordinal 0 is no longer the one it adapts");
	}

	@Test void vanillasOwnBodyIsWhatBothWereWrittenFor() throws Exception {
		ClassNode vanilla = vanilla();
		for (ClassNode mixin : List.of(architectury(), apoli())) {
			byte[] before = StagedFabricMixinFixture.bytes(mixin);
			assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> vanilla), mixin.name);
			assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		}
	}

	@Test void aStateThatIsNotTheOneReadFromTheLevelIsNotHandedOver() throws Exception {
		ClassNode mixin = architectury(), target = merged();
		MethodNode destroy = target.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : destroy.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getBlockState")) call.name = "getExistingBlockState";
		}
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertNotNull(MixinFit.injectorOf(method(mixin, "onBreak")));
	}

	@Test void aSecondBooleanOrAnotherProducerLeavesTheOrdinalAlone() throws Exception {
		ClassNode target = merged();
		MethodNode destroy = target.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		LocalVariableNode changed = destroy.localVariables.stream().filter(l -> l.index == 10).findFirst().orElseThrow();
		LabelNode early = destroy.localVariables.stream().filter(l -> l.index == 9).findFirst().orElseThrow().start;
		changed.start = early;    // the removal result now in scope at mineBlock: two booleans, as in vanilla
		ClassNode mixin = apoli();
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertEquals(1, MixinFit.value(MixinFit.injectorOf(method(mixin, "modifyEffectiveTool")), "ordinal"));

		ClassNode other = merged();
		MethodNode body = other.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("canHarvestBlock")) call.name = "isSignalSource";
		}
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(apoli(), name -> other));
	}

	@Test void theSwitchAndOtherMixinsAreLeftAlone() throws Exception {
		ClassNode target = merged(), mixin = architectury();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		System.setProperty(FabricBlockBreakMixinAdapter.PROPERTY, "off");
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		System.clearProperty(FabricBlockBreakMixinAdapter.PROPERTY);
		mixin.name = "another/ServerPlayerGameModeMixin";
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
	}

	@Test void fabricApisAfterBreakFiresOnNeoForgesRemovalResultWithTheSameValues() throws Exception {
		ClassNode mixin = fabricInteraction(), target = merged();
		assertEquals(1, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		MethodNode outer = method(mixin, "onBlockBroken");
		assertEquals("(ZLnet/minecraft/core/BlockPos;" + ENTITY + STATE + ")Z", outer.desc);
		AnnotationNode modify = MixinFit.injectorOf(outer);
		assertEquals(FabricBlockBreakMixinAdapter.MODIFY_EXPRESSION_VALUE, modify.desc);
		assertEquals(List.of("destroyBlock"), MixinFit.stringList(MixinFit.value(modify, "method")));
		assertEquals("L" + FabricBlockBreakMixinAdapter.TARGET + ";removeBlock" + FabricBlockBreakMixinAdapter.REMOVE_BLOCK_DESC,
				MixinFit.value(MixinFit.atNodes(modify).getFirst(), "target"));
		assertEquals(Boolean.TRUE, MixinFit.value(outer.invisibleParameterAnnotations[1].getFirst(), "argsOnly"));
		assertEquals(List.of("blockEntity"), MixinFit.stringList(MixinFit.value(outer.invisibleParameterAnnotations[2].getFirst(), "name")));
		assertEquals(List.of("adjustedState"), MixinFit.stringList(MixinFit.value(outer.invisibleParameterAnnotations[3].getFirst(), "name")));
		assertNull(MixinFit.injectorOf(method(mixin, "onBlockBroken" + MixinHandlerShim.INNER_SUFFIX)), "fabric-api's own handler is only called");
		assertTrue(java.util.Arrays.stream(outer.instructions.toArray()).anyMatch(i -> i.getOpcode() == Opcodes.IFEQ), "only when the block was removed");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "a second pass changes nothing");
		assertNotNull(MixinFit.injectorOf(method(fabricInteraction(), "breakBlock")), "BEFORE binds as it is and is left alone");
	}

	@Test void fabricApisAfterBreakIsLeftAloneWhereTheMergedBodyDoesNotProveIt() throws Exception {
		ClassNode vanilla = vanilla(), mixin = fabricInteraction();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> vanilla), "vanilla calls Block.destroy itself");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		ClassNode renamed = merged();
		destroyBlock(renamed).localVariables.stream().filter(l -> l.name.equals("blockEntity")).forEach(l -> l.name = "entity");
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(fabricInteraction(), name -> renamed), "the block entity the handler names is not there");
		ClassNode noDestroy = merged();
		MethodNode helper = noDestroy.methods.stream().filter(m -> m.name.equals("removeBlock") && m.desc.equals(FabricBlockBreakMixinAdapter.REMOVE_BLOCK_DESC)).findFirst().orElseThrow();
		for (AbstractInsnNode insn : helper.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("destroy")) call.name = "destroyedElsewhere";
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(fabricInteraction(), name -> noDestroy), "removal no longer means Block.destroy ran");
		ClassNode callbackReader = fabricInteraction(), target = merged();
		method(callbackReader, "onBlockBroken").instructions.insert(new InsnNode(Opcodes.POP));
		method(callbackReader, "onBlockBroken").instructions.insert(new VarInsnNode(Opcodes.ALOAD, 2));
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(callbackReader, name -> target), "a handler that reads its callback cannot be handed null");
	}

	private static MethodNode destroyBlock(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
	}

	private static ClassNode fabricInteraction() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", FabricBlockBreakMixinAdapter.FABRIC);
	}

	private static ClassNode merged() throws Exception {
		return game(Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar"));
	}

	private static ClassNode vanilla() throws Exception {
		return game(Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/versions/26.2/26.2.jar"));
	}

	/** With its local variable table, as the mixin service reads it: that table is the adapter's evidence. */
	private static ClassNode game(Path jar) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(FabricBlockBreakMixinAdapter.TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode architectury() throws Exception {
		Path jar = MODS.resolve("architectury-fabric-21.1.10.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "popular-pack architectury fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(FabricBlockBreakMixinAdapter.ARCHITECTURY + ".class")).readAllBytes());
		}
	}

	private static ClassNode apoli() throws Exception {
		Path jar = MODS.resolve("Origins-Legacy-1.12.18+26.2.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "popular-pack Origins fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry nested = zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar");
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
					if (entry.getName().equals(FabricBlockBreakMixinAdapter.APOLI + ".class")) return MixinFit.parse(in.readAllBytes());
				}
			}
		}
		throw new AssertionError("apoli's mixin is not in the nested jar");
	}

	private static MethodNode method(ClassNode owner, String name) {
		return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}
}
