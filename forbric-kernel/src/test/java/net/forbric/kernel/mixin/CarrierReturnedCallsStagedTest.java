/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link CarrierReturnedCalls} on the unmodified upstream fabric-api entity mixin against the staged merged base and its
 * native references. Natively {@code LivingEntity.getBedOrientation()} returns {@code BedBlock.getBedOrientation(level, pos)}
 * when the entity has a sleeping position, and fabric-api wraps that call to ask {@code MODIFY_SLEEPING_DIRECTION}; NeoForge
 * answers by asking the bed block. The wrap moves to the answer at the one return reached with a sleeping position, handed
 * the level asked again and the position from its local, its handler kept as written. Renamed and changed it still moves; a
 * {@code @Group}, the switch, or a handler that hands its Operation another value leaves it as compiled.
 */
@ResourceLock("system-properties")
class CarrierReturnedCallsStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/entity/event/LivingEntityMixin";

	@AfterEach void reset() {
		System.clearProperty(CarrierReturnedCalls.PROPERTY);
	}

	private static Path merged() {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		return jar;
	}

	/** The merged class with its local variable tables, as the mixin service hands it to the adapters. */
	private static ClassNode living() throws Exception {
		try (ZipFile zip = new ZipFile(merged().toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(LIVING + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static BiFunction<Ecosystem, String, ClassNode> natives() {
		Path jar = merged();
		NativeGameReferences references = new NativeGameReferences(path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		});
		return references::get;
	}

	private static ClassNode fabric() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", MIXIN);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static int adapt(ClassNode mixin) throws Exception {
		ClassNode target = living();
		return CarrierReturnedCalls.adapt(mixin, owner -> owner.equals(LIVING) ? target : null, natives());
	}

	@Test void theSleepingDirectionWrapMovesToTheAnswerReachedWithASleepingPosition() throws Exception {
		ClassNode mixin = fabric();
		String written = MixinInstructionFingerprint.hash(StagedFabricMixinFixture.method(mixin, "onGetSleepingDirection"));
		assertEquals(1, adapt(mixin), "the sleeping direction, and nothing else of this mixin");
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "onGetSleepingDirection");
		AnnotationNode modify = MixinFit.injectorOf(wrapper);
		assertEquals(CarrierReturnedCalls.MODIFY_RETURN, modify.desc);
		assertEquals(List.of("getBedOrientation()Lnet/minecraft/core/Direction;"), MixinFit.stringList(MixinFit.value(modify, "method")));
		List<AnnotationNode> points = MixinFit.atNodes(modify);
		assertEquals(1, points.size());
		assertEquals("RETURN", MixinFit.value(points.getFirst(), "value"));
		assertEquals(1, MixinFit.value(points.getFirst(), "ordinal"), "the return with a sleeping position; not the one without");
		assertEquals("(Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;", wrapper.desc);
		AnnotationNode local = wrapper.invisibleParameterAnnotations[1].getFirst();
		assertEquals(CarrierDecisions.LOCAL, local.desc);
		assertEquals(List.of("index", 1), local.values, "the sleeping position, from the merged method's own local");
		assertTrue(calls(wrapper, LIVING, "level"), "the level, asked again as the merged method asks it there");
		assertTrue(calls(wrapper, CarrierReturnedCalls.CONSTANT_OWNER, "constant"), "an Operation answering with NeoForge's answer");
		MethodNode original = StagedFabricMixinFixture.method(mixin, "onGetSleepingDirection" + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original));
		assertEquals(written, MixinInstructionFingerprint.hash(original), "fabric-api's own handler, unchanged");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		assertEquals(0, adapt(mixin), "a second pass changes nothing");
	}

	@Test void theMechanismKnowsNoNameTheSameWrapRenamedAndChangedStillMoves() throws Exception {
		ClassNode mixin = fabric();
		mixin.name = "org/example/rest/SleeperHook";
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "onGetSleepingDirection");
		handler.name = "facing";
		handler.instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(1, adapt(mixin));
		assertNotNull(StagedFabricMixinFixture.method(mixin, "facing" + CarrierDecisions.SUFFIX));
	}

	@Test void aGroupTheSwitchOrAHandlerHandingItsOperationAnotherValueLeavesTheWrapAsCompiled() throws Exception {
		ClassNode grouped = fabric();
		StagedFabricMixinFixture.method(grouped, "onGetSleepingDirection").visibleAnnotations.add(new AnnotationNode(CarrierDecisions.GROUP));
		assertEquals(0, adapt(grouped));
		ClassNode other = fabric();
		MethodNode handler = StagedFabricMixinFixture.method(other, "onGetSleepingDirection");
		for (AbstractInsnNode instruction : handler.instructions)
			if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 2) {
				handler.instructions.set(load, new InsnNode(Opcodes.ACONST_NULL));
				break;
			}
		assertEquals(0, adapt(other), "the carrier's answer is not the answer for another position");
		System.setProperty(CarrierReturnedCalls.PROPERTY, "off");
		assertEquals(0, adapt(fabric()));
	}

	/** With the native body as the merged one the call is still made: nothing to move. */
	@Test void whereTheMergedMethodStillMakesTheCallNothingMoves() throws Exception {
		ClassNode nativeLiving = natives().apply(Ecosystem.FABRIC, LIVING);
		assertEquals(0, CarrierReturnedCalls.adapt(fabric(), owner -> owner.equals(LIVING) ? nativeLiving : null, natives()));
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		return false;
	}
}
