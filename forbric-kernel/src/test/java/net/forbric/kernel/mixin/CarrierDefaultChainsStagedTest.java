/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link CarrierDefaultChains} on the unmodified upstream fabric-api sound mixin against the staged merged base and the native
 * references it carries. Natively {@code SoundEngine.play} asks {@code soundBuffers.getStream(path, looping)} and fabric-api
 * redirects that; NeoForge asks the sound ({@code SoundInstance.getStream}), whose default asks the buffers. The redirect
 * moves onto the sound's call, its handler kept as written; the mechanism is given no name: the mixin renamed, with one more
 * instruction, still moves; a {@code @Group} or the switch leaves it as compiled. The bed redirect, bound to two methods, is
 * not this mechanism's.
 */
@ResourceLock("system-properties")
class CarrierDefaultChainsStagedTest {
	private static final String ENGINE = "net/minecraft/client/sounds/SoundEngine";
	private static final String SOUND = "net/minecraft/client/resources/sounds/SoundInstance";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/client/sound/SoundEngineMixin";

	@AfterEach void reset() {
		System.clearProperty(CarrierDefaultChains.PROPERTY);
	}

	private static Path merged() {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		return jar;
	}

	/** Every class of the merged base, as the mixin service hands them to the adapters. */
	private static Function<String, ClassNode> game() {
		Path jar = merged();
		Map<String, ClassNode> read = new HashMap<>();
		return owner -> read.computeIfAbsent(owner, name -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(name + ".class");
				if (entry == null) return null;
				ClassNode node = new ClassNode();
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
				return node;
			} catch (Exception unreadable) {
				return null;
			}
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

	private static ClassNode fabric(String module, String name) throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin(module, name);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static int adapt(ClassNode mixin) {
		return CarrierDefaultChains.adapt(mixin, game(), natives());
	}

	private static MethodNode redirected(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	@Test void theStreamRedirectMovesOntoTheSoundsOwnCallAndKeepsItsHandlerAsWritten() throws Exception {
		ClassNode mixin = fabric("fabric-sound-api-v1", MIXIN);
		MethodNode handler = redirected(mixin);
		String name = handler.name, desc = handler.desc, written = MixinInstructionFingerprint.hash(handler);
		assertEquals(1, adapt(mixin));
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, name);
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst();
		assertEquals("L" + SOUND + ";getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;",
				MixinFit.value(at, "target"));
		assertEquals("(L" + SOUND + ";Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;ZL" + SOUND + ";)Ljava/util/concurrent/CompletableFuture;",
				wrapper.desc, "the sound's call, then the argument of play the handler captures");
		MethodNode original = StagedFabricMixinFixture.method(mixin, name + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original));
		assertEquals(desc, original.desc);
		assertEquals(written, MixinInstructionFingerprint.hash(original), "fabric-api's own handler, unchanged");
		assertEquals(1, calls(wrapper, CarrierDefaultChains.DISPATCH, "inherits"), "asked once, for the sound");
		assertEquals(1, calls(wrapper, mixin.name, original.name), "fabric-api's handler where the default asks the buffers");
		assertEquals(1, calls(wrapper, "net/minecraft/client/resources/sounds/Sound", "getPath"), "the default's own argument");
		assertEquals(1, calls(wrapper, SOUND, "getStream"), "a sound that overrides the method keeps its override");
		assertEquals(0, calls(wrapper, "net/minecraft/client/sounds/SoundBufferLibrary", "getStream"), "the vanilla call is the handler's alone");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		assertTrue(wrapper.instructions.toArray().length > 0 && hasFrames(wrapper), "the new handler carries its stack map frames");
		assertEquals(0, adapt(mixin), "a second pass changes nothing");
	}

	@Test void theMechanismKnowsNoNameTheSameRedirectRenamedAndChangedStillMoves() throws Exception {
		ClassNode mixin = fabric("fabric-sound-api-v1", MIXIN);
		mixin.name = "org/example/acoustics/EngineHook";
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = redirected(mixin);
		handler.name = "stream";
		handler.instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(1, adapt(mixin));
		assertNotNull(StagedFabricMixinFixture.method(mixin, "stream" + CarrierDecisions.SUFFIX));
	}

	@Test void aGroupOrTheSwitchLeavesTheRedirectAsCompiled() throws Exception {
		ClassNode grouped = fabric("fabric-sound-api-v1", MIXIN);
		redirected(grouped).visibleAnnotations.add(new AnnotationNode(CarrierDecisions.GROUP));
		assertEquals(0, adapt(grouped));
		System.setProperty(CarrierDefaultChains.PROPERTY, "off");
		assertEquals(0, adapt(fabric("fabric-sound-api-v1", MIXIN)));
	}

	/** Natively the vanilla call is in play itself: with the native body as the merged one there is nothing to move. */
	@Test void whereTheMergedMethodStillMakesTheVanillaCallNothingMoves() throws Exception {
		ClassNode mixin = fabric("fabric-sound-api-v1", MIXIN);
		ClassNode nativeEngine = natives().apply(Ecosystem.FABRIC, ENGINE);
		Function<String, ClassNode> game = game();
		assertEquals(0, CarrierDefaultChains.adapt(mixin, owner -> owner.equals(ENGINE) ? nativeEngine : game.apply(owner), natives()));
	}

	/** fabric-api's bed redirect binds two methods (the sleep start and the wake-up lambda): not this mechanism's to move. */
	@Test void theBedRedirectBoundToTwoMethodsIsNotMoved() throws Exception {
		assertEquals(0, adapt(fabric("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/LivingEntityMixin")));
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) n++;
		return n;
	}

	private static boolean hasFrames(MethodNode method) {
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.FrameNode) return true;
		return false;
	}
}
