/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link UncalledDecisionEntries} on the unmodified Create Fly redstone-connection hook against the staged merged base and the
 * native references it carries. Natively {@code RedStoneWireBlock.getConnectingSide} asks the static
 * {@code shouldConnectTo(state, direction)} once and {@code shouldConnectTo(state)} (which forwards {@code (state, null)})
 * twice; NeoForge asks the neighbour's {@code state.canRedstoneConnectTo(level, pos, direction)} at the same three places, and
 * nothing calls {@code shouldConnectTo}. Create's cancellable HEAD hook moves to a wrap of those three calls, handed the state
 * and the direction. Renamed it still moves; with one of the carrier's calls made on another value, nothing moves.
 */
@ResourceLock("system-properties")
class UncalledDecisionEntriesStagedTest {
	private static final String WIRE = "net/minecraft/world/level/block/RedStoneWireBlock";
	private static final String MIXIN = "com/zurrtum/create/mixin/RedStoneWireBlockMixin";
	private static final String CONNECTING = "getConnectingSide(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)"
			+ "Lnet/minecraft/world/level/block/state/properties/RedstoneSide;";
	private static final String CARRIER = "Lnet/minecraft/world/level/block/state/BlockState;canRedstoneConnectTo(Lnet/minecraft/world/level/BlockGetter;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z";

	@AfterEach void reset() {
		System.clearProperty(UncalledDecisionEntries.PROPERTY);
	}

	private static Path merged() {
		Path jar = TestFixtures.stagedBase();
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, jar, "actual game required");
		return jar;
	}

	private static Function<String, ClassNode> game() {
		List<Path> jars = List.of(merged(), TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"),
				TestFixtures.stagedRoot().resolve("merged-base/forge-runtime-interop.jar"));
		Map<String, ClassNode> read = new HashMap<>();
		return owner -> read.computeIfAbsent(owner, name -> {
			for (Path jar : jars) {
				if (!Files.isRegularFile(jar)) continue;
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					var entry = zip.getEntry(name + ".class");
					if (entry == null) continue;
					ClassNode node = new ClassNode();
					new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
					return node;
				} catch (Exception unreadable) {
					return null;
				}
			}
			return null;
		});
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

	private static ClassNode create() throws Exception {
		ClassNode mixin = CreateGuestMixinFixture.mixin(MIXIN);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	@Test void createsHeadHookWrapsTheCarriersThreeDecisionsInGetConnectingSide() throws Exception {
		ClassNode mixin = create();
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "connectsTo");
		String desc = handler.desc, written = MixinInstructionFingerprint.hash(handler);
		assertEquals(1, UncalledDecisionEntries.adapt(mixin, game(), natives()));
		MethodNode original = StagedFabricMixinFixture.method(mixin, "connectsTo" + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original), "the hook keeps its body, without its annotation");
		assertEquals(desc, original.desc);
		assertEquals(written, MixinInstructionFingerprint.hash(original), "Create's own handler, unchanged");
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "connectsTo" + UncalledDecisionEntries.SUFFIX);
		AnnotationNode wrap = MixinFit.injectorOf(wrapper);
		assertEquals(UncalledDecisionEntries.WRAP, wrap.desc);
		assertEquals(List.of(CONNECTING), MixinFit.stringList(MixinFit.value(wrap, "method")));
		assertEquals(CARRIER, MixinFit.value(MixinFit.atNodes(wrap).getFirst(), "target"));
		assertNull(MixinFit.value(MixinFit.atNodes(wrap).getFirst(), "ordinal"), "every one of the three calls");
		assertEquals("(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/core/Direction;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z", wrapper.desc);
		assertEquals(0, wrapper.access & Opcodes.ACC_STATIC, "an instance method, as getConnectingSide is");
		assertEquals(1, calls(wrapper, mixin.name, original.name), "the hook, handed the state and the direction");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		assertEquals(0, UncalledDecisionEntries.adapt(mixin, game(), natives()), "a second pass changes nothing");
	}

	@Test void theMoveKnowsNoName() throws Exception {
		ClassNode mixin = create();
		mixin.name = "org/example/tide/Weir";
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "connectsTo");
		handler.name = "opens";
		assertEquals(1, UncalledDecisionEntries.adapt(mixin, game(), natives()));
		assertNotNull(StagedFabricMixinFixture.method(mixin, "opens" + UncalledDecisionEntries.SUFFIX));
	}

	/** One of the carrier's three calls made on another neighbour: the merged body no longer reads as the native one, and nothing moves. */
	@Test void aCarrierCallOnAnotherValueMovesNothing() throws Exception {
		Function<String, ClassNode> game = game();
		ClassNode wire = game.apply(WIRE);
		MethodNode connecting = wire.methods.stream().filter(m -> (m.name + m.desc).equals(CONNECTING)).findFirst().orElseThrow();
		// The middle call is handed the neighbour's position, read again: swap the direction it is handed for the reversed one.
		boolean changed = false;
		for (AbstractInsnNode instruction : connecting.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("canRedstoneConnectTo")) continue;
			AbstractInsnNode direction = call.getPrevious();
			if (direction.getOpcode() == Opcodes.ALOAD) {
				connecting.instructions.insert(direction, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/Direction", "getOpposite",
						"()Lnet/minecraft/core/Direction;", false));
				changed = true;
				break;
			}
		}
		assertTrue(changed, "the middle call takes the direction parameter");
		ClassNode mixin = create();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, UncalledDecisionEntries.adapt(mixin, owner -> owner.equals(WIRE) ? wire : game.apply(owner), natives()));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	/** Natively the decision is called: with the native class as the merged one there is nothing to move. */
	@Test void whereTheMergedClassStillAsksTheDecisionNothingMoves() throws Exception {
		ClassNode nativeWire = natives().apply(Ecosystem.FABRIC, WIRE);
		Function<String, ClassNode> game = game();
		assertEquals(0, UncalledDecisionEntries.adapt(create(), owner -> owner.equals(WIRE) ? nativeWire : game.apply(owner), natives()));
	}

	@Test void theSwitchLeavesTheHookAsCompiled() throws Exception {
		System.setProperty(UncalledDecisionEntries.PROPERTY, "off");
		assertEquals(0, UncalledDecisionEntries.adapt(create(), game(), natives()));
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) n++;
		return n;
	}
}
