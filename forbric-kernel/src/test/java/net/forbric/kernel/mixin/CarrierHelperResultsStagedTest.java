package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The three destroyBlock mixins as shipped — architectury's and apoli-legacy's from the popular pack, fabric-api's
 * onBlockBroken from the merged pack's fabric-api — against the real merged and vanilla bodies: the first two read by the
 * general locals mechanisms (MixinCapturedLocals, NativeLocalPins), fabric-api's AFTER by {@link CarrierHelperResults}, which
 * knows no mod: NeoForge's private removeBlock answers true exactly when it called Block.destroy, and the handler's
 * {@code @Local}s are read where the merged destroyBlock keeps the same values.
 */
@ResourceLock("system-properties")
class CarrierHelperResultsStagedTest {
	private static final Path MODS = Path.of("run/client-popular/mods");
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";
	private static final String TARGET = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String FABRIC = "net/fabricmc/fabric/mixin/event/interaction/ServerPlayerGameModeMixin";
	private static final String REMOVE_BLOCK_DESC = "(Lnet/minecraft/core/BlockPos;" + STATE + "ZLnet/minecraft/world/item/ItemStack;)Z";

	@AfterEach void reset() {
		System.clearProperty(CarrierHelperResults.PROPERTY);
	}

	/**
	 * architectury's locals capture is the general mechanism's: MixinCapturedLocals reads each captured local where the merged
	 * body keeps it, and nothing is left for the helper-result move to do with it.
	 */
	@Test void architecturysBreakCaptureIsReadWhereTheMergedBodyKeepsEachLocal() throws Exception {
		ClassNode mixin = architectury(), target = merged();
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		assertEquals(1, MixinCapturedLocals.adapt(mixin, name -> target, natives()));
		MethodNode handler = method(mixin, "onBreak");
		assertNull(MixinFit.value(MixinFit.injectorOf(handler), "locals"), "the capture is gone");
		assertEquals(List.of("index", 4), handler.invisibleParameterAnnotations[2].getFirst().values, "the block entity read from the level");
		assertEquals(List.of("index", 2), handler.invisibleParameterAnnotations[3].getFirst().values, "the state read from the level");
		assertEquals(0, CarrierHelperResults.adapt(mixin, name -> target, natives()), "nothing left here");
	}

	/**
	 * apoli-legacy's harvest modifier is the general mechanism's too: its ordinal picks {@code canDestroy} natively, and
	 * NativeLocalPins pins it to the merged local of that name and type.
	 */
	@Test void apolisHarvestModifierIsPinnedToTheMergedLocalOfTheSameNameAndType() throws Exception {
		ClassNode mixin = apoli(), target = merged();
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		assertEquals(1, NativeLocalPins.adapt(mixin, name -> target, natives()));
		AnnotationNode modify = MixinFit.injectorOf(method(mixin, "modifyEffectiveTool"));
		assertEquals(9, MixinFit.value(modify, "index"));
		assertNull(MixinFit.value(modify, "ordinal"));
		assertEquals(0, CarrierHelperResults.adapt(mixin, name -> target, natives()), "nothing left here");
		assertEquals(0, NativeLocalPins.adapt(mixin, name -> target, natives()), "a second pass changes nothing");
	}

	/** A table that declares the removal result over the whole method changes nothing: the pin is by index, not by count. */
	@Test void aBooleanTheTableDeclaresBeforeItsSlotIsWrittenDoesNotMoveThePin() throws Exception {
		ClassNode target = merged();
		MethodNode destroy = destroyBlock(target);
		LocalVariableNode changed = destroy.localVariables.stream().filter(l -> l.index == 10).findFirst().orElseThrow();
		changed.start = destroy.localVariables.stream().filter(l -> l.index == 0).findFirst().orElseThrow().start;
		ClassNode mixin = apoli();
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		assertEquals(1, NativeLocalPins.adapt(mixin, name -> target, natives()));
		assertEquals(9, MixinFit.value(MixinFit.injectorOf(method(mixin, "modifyEffectiveTool")), "index"));
	}

	@Test void vanillasOwnBodyIsWhatBothWereWrittenFor() throws Exception {
		ClassNode vanilla = vanilla();
		for (ClassNode mixin : List.of(architectury(), apoli(), fabricInteraction())) {
			MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
			byte[] before = StagedFabricMixinFixture.bytes(mixin);
			assertEquals(0, CarrierHelperResults.adapt(mixin, name -> vanilla, natives()), mixin.name);
			assertEquals(0, NativeLocalPins.adapt(mixin, name -> vanilla, natives()), mixin.name);
			assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		}
	}

	@Test void theSwitchLeavesFabricApisMixinAlone() throws Exception {
		ClassNode target = merged(), mixin = fabricInteraction();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		System.setProperty(CarrierHelperResults.PROPERTY, "off");
		assertEquals(0, CarrierHelperResults.adapt(mixin, name -> target, natives()));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void fabricApisAfterBreakRunsOnTheHelpersAnswerWithItsLocalsReadByProducer() throws Exception {
		ClassNode mixin = fabricInteraction(), target = merged();
		String written = MixinInstructionFingerprint.hash(method(mixin, "onBlockBroken"));
		assertEquals(1, CarrierHelperResults.adapt(mixin, name -> target, natives()));
		MethodNode outer = method(mixin, "onBlockBroken");
		assertEquals("(ZLnet/minecraft/core/BlockPos;" + ENTITY + STATE + ")Z", outer.desc);
		AnnotationNode modify = MixinFit.injectorOf(outer);
		assertEquals(CarrierHelperResults.MODIFY_EXPRESSION_VALUE, modify.desc);
		assertEquals(List.of("destroyBlock"), MixinFit.stringList(MixinFit.value(modify, "method")));
		assertEquals("L" + TARGET + ";removeBlock" + REMOVE_BLOCK_DESC, MixinFit.value(MixinFit.atNodes(modify).getFirst(), "target"));
		assertEquals(List.of("argsOnly", true, "index", 1), outer.invisibleParameterAnnotations[1].getFirst().values, "the position, the method's argument");
		assertEquals(List.of("index", 4), outer.invisibleParameterAnnotations[2].getFirst().values, "the block entity read from the level, slot 4 merged");
		assertEquals(List.of("index", 6), outer.invisibleParameterAnnotations[3].getFirst().values, "the state playerWillDestroy returned, slot 6 merged");
		MethodNode original = method(mixin, "onBlockBroken" + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original), "fabric-api's own handler is only called");
		assertEquals(written, MixinInstructionFingerprint.hash(original), "fabric-api's own handler, unchanged");
		List<AbstractInsnNode> real = java.util.Arrays.stream(outer.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertTrue(real.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 1, "the helper's answer first");
		assertTrue(real.get(1) instanceof JumpInsnNode skip && skip.getOpcode() == Opcodes.IFEQ, "then skip when it made no call");
		assertTrue(real.stream().anyMatch(i -> i instanceof TypeInsnNode n && n.getOpcode() == Opcodes.NEW && n.desc.equals(CarrierHelperResults.RETURNABLE)),
				"a fresh callback is handed over");
		int call = java.util.stream.IntStream.range(0, real.size()).filter(k -> real.get(k) instanceof MethodInsnNode m
				&& m.name.equals("onBlockBroken" + CarrierDecisions.SUFFIX)).findFirst().orElseThrow();
		int skipTo = outer.instructions.indexOf(((JumpInsnNode) real.get(1)).label);
		assertTrue(outer.instructions.indexOf(real.get(call)) < skipTo, "fabric-api's handler runs only when the helper made the call");
		assertEquals(List.of(Opcodes.ILOAD, Opcodes.IRETURN), real.subList(real.size() - 2, real.size()).stream().map(AbstractInsnNode::getOpcode).toList(),
				"and the answer is returned unchanged");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, CarrierHelperResults.adapt(mixin, name -> target, natives()), "a second pass changes nothing");
		ClassNode fresh = fabricInteraction();
		for (MethodNode kept : fresh.methods) {
			if (kept.name.equals("onBlockBroken")) continue;
			MethodNode adapted = method(mixin, kept.name);
			assertEquals(kept.desc, adapted.desc, kept.name);
			assertEquals(kept.instructions.size(), adapted.instructions.size(), kept.name + " is left alone");
		}
	}

	/** The merged locals are found by what produced them, so a merged local named otherwise is still the one read. */
	@Test void theMergedLocalsAreFoundByProducerNotByName() throws Exception {
		ClassNode renamed = merged(), mixin = fabricInteraction();
		destroyBlock(renamed).localVariables.forEach(l -> l.name = "local" + l.index);
		assertEquals(1, CarrierHelperResults.adapt(mixin, name -> renamed, natives()));
		MethodNode outer = method(mixin, "onBlockBroken");
		assertEquals(List.of("index", 4), outer.invisibleParameterAnnotations[2].getFirst().values);
		assertEquals(List.of("index", 6), outer.invisibleParameterAnnotations[3].getFirst().values);
	}

	@Test void fabricApisAfterBreakIsLeftAloneWhereTheMergedBodyDoesNotProveIt() throws Exception {
		ClassNode noDestroy = merged();
		MethodNode helper = noDestroy.methods.stream().filter(m -> m.name.equals("removeBlock") && m.desc.equals(REMOVE_BLOCK_DESC)).findFirst().orElseThrow();
		for (AbstractInsnNode insn : helper.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("destroy")) call.name = "destroyedElsewhere";
		assertLeftAlone(noDestroy, "the helper no longer makes the call");

		ClassNode alwaysTrue = merged();
		MethodNode yes = alwaysTrue.methods.stream().filter(m -> m.name.equals("removeBlock") && m.desc.equals(REMOVE_BLOCK_DESC)).findFirst().orElseThrow();
		AbstractInsnNode answer = java.util.Arrays.stream(yes.instructions.toArray()).filter(i -> i.getOpcode() == Opcodes.IRETURN).findFirst().orElseThrow().getPrevious();
		yes.instructions.set(answer, new InsnNode(Opcodes.ICONST_1));
		assertLeftAlone(alwaysTrue, "an answer of true whether or not it destroyed proves nothing");

		ClassNode overridable = merged();
		overridable.methods.stream().filter(m -> m.name.equals("removeBlock") && m.desc.equals(REMOVE_BLOCK_DESC)).findFirst().orElseThrow().access = Opcodes.ACC_PROTECTED;
		for (MethodNode m : overridable.methods) for (AbstractInsnNode insn : m.instructions)
			if (insn instanceof MethodInsnNode call && call.name.equals("removeBlock") && call.desc.equals(REMOVE_BLOCK_DESC)) call.setOpcode(Opcodes.INVOKEVIRTUAL);
		assertLeftAlone(overridable, "a helper a subclass may override is not the code that runs");

		ClassNode cancellable = fabricInteraction(), target = merged();
		AnnotationNode inject = MixinFit.injectorOf(method(cancellable, "onBlockBroken"));
		inject.values.add("cancellable");
		inject.values.add(Boolean.TRUE);
		byte[] before = StagedFabricMixinFixture.bytes(cancellable);
		assertEquals(0, CarrierHelperResults.adapt(cancellable, name -> target, natives()), "a cancellable injector would cancel at another place");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(cancellable));
	}

	/** A handler that reads its callback is handed a real one: not cancellable, named for the method, as Mixin's would be. */
	@Test void aHandlerThatReadsItsCallbackIsHandedARealOne() throws Exception {
		ClassNode callbackReader = fabricInteraction(), target = merged();
		method(callbackReader, "onBlockBroken").instructions.insert(new InsnNode(Opcodes.POP));
		method(callbackReader, "onBlockBroken").instructions.insert(new VarInsnNode(Opcodes.ALOAD, 2));
		assertEquals(1, CarrierHelperResults.adapt(callbackReader, name -> target, natives()));
	}

	/** The move knows no name: fabric-api's mixin and handler renamed (and one instruction added) still move. */
	@Test void theMoveKnowsNoName() throws Exception {
		ClassNode mixin = fabricInteraction(), target = merged();
		mixin.name = "org/example/quarry/BreakHook";
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		MethodNode handler = method(mixin, "onBlockBroken");
		handler.name = "afterSmash";
		handler.instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(1, CarrierHelperResults.adapt(mixin, name -> target, natives()));
		assertNotNull(method(mixin, "afterSmash" + CarrierDecisions.SUFFIX));
	}

	private static void assertLeftAlone(ClassNode target, String why) throws Exception {
		ClassNode mixin = fabricInteraction();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, CarrierHelperResults.adapt(mixin, name -> target, natives()), why);
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), why);
	}

	/** The class each ecosystem's mods were compiled against, from the staged merged base's native references. */
	private static java.util.function.BiFunction<net.forbric.api.Ecosystem, String, ClassNode> natives() {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		NativeGameReferences references = new NativeGameReferences(path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		});
		return references::get;
	}

	private static MethodNode destroyBlock(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
	}

	private static ClassNode fabricInteraction() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", FABRIC);
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		return mixin;
	}

	private static ClassNode merged() throws Exception {
		return game(Fixture.STAGED, TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"));
	}

	private static ClassNode vanilla() throws Exception {
		return game(Fixture.MC_LIBRARIES, TestFixtures.vanillaJar());
	}

	/** With its local variable table, as the mixin service reads it: that table is the adapter's evidence. */
	private static ClassNode game(Fixture kind, Path jar) throws Exception {
		TestFixtures.require(kind, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode architectury() throws Exception {
		Path jar = MODS.resolve("architectury-fabric-21.1.10.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack architectury fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry("dev/architectury/mixin/fabric/MixinServerPlayerGameMode" + ".class")).readAllBytes());
		}
	}

	private static ClassNode apoli() throws Exception {
		Path jar = MODS.resolve("Origins-Legacy-1.12.18+26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack Origins fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry nested = zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar");
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
					if (entry.getName().equals("io/github/apace100/apoli/mixin/ServerPlayerInteractionManagerMixin" + ".class")) return MixinFit.parse(in.readAllBytes());
				}
			}
		}
		throw new AssertionError("apoli's mixin is not in the nested jar");
	}

	private static MethodNode method(ClassNode owner, String name) {
		return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}
}
