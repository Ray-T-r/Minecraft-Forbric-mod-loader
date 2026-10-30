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
 * instruction, still moves; a {@code @Group} or the switch leaves it as compiled. The bed occupancy redirect, bound to two
 * methods that each lost the write to the state's {@code setBedOccupied}, moves with one handler serving both.
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
		Path jar = TestFixtures.stagedBase();
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, jar, "actual game required");
		return jar;
	}

	/** Every class of the merged base and both carriers, as the mixin service hands them to the adapters. */
	private static Function<String, ClassNode> game() {
		List<Path> jars = List.of(merged(), TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR),
				TestFixtures.stagedRoot().resolve(TestFixtures.FORGE_INTEROP_JAR));
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
		}, null, TestFixtures.launchSources(jar));
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

	/**
	 * fabric-api's bed occupancy redirect binds two methods, the sleep start and the wake-up lambda; natively each writes the
	 * bed's OCCUPIED state with {@code Level.setBlock}, merged each asks the state's {@code setBedOccupied}, whose inherited
	 * implementation (IBlockStateExtension's default, then IBlockExtension's) makes that write. One new handler, bound to both,
	 * runs the defaults with fabric-api's handler at the write wherever the state and its block inherit them; a bed that
	 * overrides {@code setBedOccupied} keeps its own setter.
	 */
	@Test void theBedRedirectBoundToTwoMethodsMovesOntoTheStatesSetterForBoth() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the defaults a carrier chain walks to, through the base's hierarchy");
		ClassNode mixin = fabric("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/LivingEntityMixin");
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "setOccupiedState");
		String desc = handler.desc, written = MixinInstructionFingerprint.hash(handler);
		assertEquals(1, adapt(mixin), "the occupancy redirect, and no other handler of the mixin");
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "setOccupiedState");
		AnnotationNode redirect = MixinFit.injectorOf(wrapper);
		assertEquals(CarrierDecisions.REDIRECT, redirect.desc);
		assertEquals(List.of("lambda$stopSleeping$0", "startSleeping"), MixinFit.stringList(MixinFit.value(redirect, "method")), "still bound to both methods, as written");
		assertEquals("Lnet/minecraft/world/level/block/state/BlockState;setBedOccupied(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/world/entity/LivingEntity;Z)V", MixinFit.value(MixinFit.atNodes(redirect).getFirst(), "target"));
		assertEquals("(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/world/entity/LivingEntity;Z)V", wrapper.desc, "the carrier's call, nothing captured");
		MethodNode original = StagedFabricMixinFixture.method(mixin, "setOccupiedState" + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original));
		assertEquals(desc, original.desc);
		assertEquals(written, MixinInstructionFingerprint.hash(original), "fabric-api's own handler, unchanged");
		assertEquals(2, calls(wrapper, CarrierDefaultChains.DISPATCH, "inherits"), "asked for the state, then for its block");
		assertEquals(1, calls(wrapper, mixin.name, original.name), "fabric-api's handler where the block's default writes the bed");
		assertEquals(0, calls(wrapper, "net/minecraft/world/level/Level", "setBlock"), "the vanilla write is the handler's alone");
		assertEquals(1, calls(wrapper, "net/minecraft/world/level/block/state/BlockState", "setBedOccupied"), "a state that overrides it keeps its own");
		assertEquals(1, calls(wrapper, "net/minecraft/world/level/block/Block", "setBedOccupied"), "a bed that overrides it keeps its own setter");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		assertEquals(0, adapt(mixin), "a second pass changes nothing");
	}

	/** A redirect bound to two methods moves only when both lost the call to the same carrier call: one that still makes it stays. */
	@Test void aRedirectBoundToTwoMethodsStaysWhenOneOfThemStillMakesTheVanillaCall() throws Exception {
		ClassNode mixin = fabric("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/LivingEntityMixin");
		String living = "net/minecraft/world/entity/LivingEntity";
		ClassNode merged = game().apply(living), nativeLiving = natives().apply(Ecosystem.FABRIC, living);
		MethodNode lambda = merged.methods.stream().filter(m -> m.name.equals("lambda$stopSleeping$0")).findFirst().orElseThrow();
		MethodNode nativeLambda = nativeLiving.methods.stream().filter(m -> m.name.equals("lambda$stopSleeping$0")).findFirst().orElseThrow();
		merged.methods.set(merged.methods.indexOf(lambda), nativeLambda);
		Function<String, ClassNode> game = game();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, CarrierDefaultChains.adapt(mixin, owner -> owner.equals(living) ? merged : game.apply(owner), natives()));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), "the redirect is left exactly as written");
	}

	/**
	 * Additional Entity Attributes (nested in Origins Legacy) modifies vanilla's sinking constant in goDownInWater; NeoForge
	 * pushes it in ILivingEntityExtension.sinkInFluid's default (scaled by its swim-speed attribute) and goDownInWater calls
	 * that. The modifier moves onto the call, as a redirect running the default with the modifier at the constant.
	 */
	@Test void aConstantsModifierMovesOntoTheCallWhoseDefaultPushesIt() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the defaults a carrier chain walks to, through the base's hierarchy");
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR)),
				"the NeoForge carrier declares the default");
		ClassNode mixin = aea();
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, adapt(mixin), "the sinking constant, and not the rising one the merged body still pushes");
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "additionalEntityAttributes$knockDownwards");
		AnnotationNode redirect = MixinFit.injectorOf(wrapper);
		assertEquals(CarrierDecisions.REDIRECT, redirect.desc);
		assertEquals("Lnet/minecraft/world/entity/LivingEntity;sinkInFluid(Lnet/neoforged/neoforge/fluids/FluidType;)V",
				MixinFit.value(MixinFit.atNodes(redirect).getFirst(), "target"));
		assertEquals(1, calls(wrapper, mixin.name, "additionalEntityAttributes$knockDownwards" + CarrierDecisions.SUFFIX), "the modifier, at the constant");
		assertEquals(1, calls(wrapper, "net/minecraft/world/entity/LivingEntity", "sinkInFluid"), "an entity that overrides the method keeps its override");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
	}

	private static ClassNode aea() throws Exception {
		Path jar = Path.of("run/client-popular/mods/Origins-Legacy-1.12.18+26.2.jar");
		TestFixtures.require(TestFixtures.Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack Origins fixture absent");
		byte[] apoli, aea;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			apoli = zip.getInputStream(zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar")).readAllBytes();
		}
		aea = nested(apoli, "META-INF/jars/additionalentityattributes-2.2.4+26.2.jar");
		return MixinFit.parse(nested(aea, "de/dafuqs/additionalentityattributes/mixin/fabric/LivingEntityMixin.class"));
	}

	private static byte[] nested(byte[] jar, String entry) throws Exception {
		try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
			for (var each = in.getNextEntry(); each != null; each = in.getNextEntry()) if (each.getName().equals(entry)) return in.readAllBytes();
		}
		throw new AssertionError(entry + " absent");
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
