package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinEntitySoundCallbackAdapter;

/**
 * What {@code MixinEntitySoundCallbackAdapter} decides for sound wraps written in ways the sample mod did not use, on the
 * stand-in classes of {@link EntitySoundCallbackParityWeaveTest} (merged-shaped targets, vanilla-shaped native classes):
 * a bare name, a fully described or dotted owner-qualified selector, a static handler, captures by argument or by ordinal
 * listed in any order — each moved onto the playback call of exactly the method its selector names, each capture reading
 * the slot proved to hold the same value. And what it leaves byte-for-byte alone: the same wrap in a method the platform
 * did not move, a wrap written for a method that never played a sound, and a capture the vanilla body cannot resolve.
 */
class EntitySoundCallbackShapesTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/soundparity");
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	@TempDir static Path work;
	private static Path vanilla, merged;

	@BeforeAll static void compile() throws Exception {
		List<Path> vanillaSources = new ArrayList<>(sources("common"));
		vanillaSources.addAll(sources("vanilla"));
		WeaveHarness.fixture(work, "shapes-vanilla", vanillaSources, Map.of(), List.of("-g"));
		List<Path> mergedSources = new ArrayList<>(sources("common"));
		mergedSources.addAll(sources("merged"));
		mergedSources.addAll(sources("mods"));
		mergedSources.addAll(sources("shapes"));
		WeaveHarness.fixture(work, "shapes-merged", mergedSources, Map.of(), List.of("-g"));
		vanilla = work.resolve("shapes-vanilla-classes");
		merged = work.resolve("shapes-merged-classes");
	}

	private static final Function<String, ClassNode> MERGED = name -> read(merged, name);
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVE = (family, name) -> read(vanilla, name);
	private static final BiFunction<Ecosystem, String, ClassNode> NO_NATIVE = (family, name) -> null;

	@Test void stepWrapsWrittenAnyWayMoveOntoTheStepTheirSelectorNames() throws Exception {
		for (String mixin : List.of("org/example/echoes/mixin/StepEchoMixin", "org/example/chime/mixin/StepChimeMixin", "org/example/quiet/mixin/StaticStepMixin")) {
			ClassNode node = read(merged, mixin);
			assertEquals(1, MixinEntitySoundCallbackAdapter.adapt(node, MERGED, NATIVE), mixin);
			MethodNode wrapper = injector(node);
			assertEquals(List.of("playStepSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"),
					value(wrapper, "method"), mixin + ": only the step the selector names, never the muffled or combination steps");
			verify(node);
			assertEquals(0, MixinEntitySoundCallbackAdapter.adapt(node, MERGED, NATIVE), mixin + " idempotence");
		}
	}

	/** {@code @Local(ordinal = 2) int z, @Local(ordinal = 0) int x}: read at the slots of z and x, in the handler's order. */
	@Test void landingCapturesReadTheSlotsProvedToHoldTheirValues() throws Exception {
		for (var references : List.of(NATIVE, NO_NATIVE)) {
			ClassNode node = read(merged, "net/thud/mixin/FallThudMixin");
			assertEquals(1, MixinEntitySoundCallbackAdapter.adapt(node, MERGED, references));
			MethodNode wrapper = injector(node);
			assertEquals(List.of("playBlockFallSound()V"), value(wrapper, "method"));
			List<Integer> slots = new ArrayList<>();
			for (List<AnnotationNode> annotations : wrapper.invisibleParameterAnnotations) {
				if (annotations == null) continue;
				for (AnnotationNode annotation : annotations) if (annotation.desc.equals(LOCAL)) slots.add((Integer) annotation.values.get(1));
			}
			// The merged landing keeps xx, yy, zz in slots 1-3 (and a BlockPos in 4): z is slot 3, x slot 1.
			assertEquals(List.of(3, 1), slots);
			verify(node);
		}
	}

	@Test void lookAlikesAreLeftExactlyAsWritten() throws Exception {
		for (String mixin : List.of(
				"org/example/lookalike/mixin/PlaceSoundMixin",   // the platform did not move this query: it binds as written
				"org/example/elsewhere/mixin/StepOnMixin")) {    // the method never played a sound: nothing to move to
			ClassNode node = read(merged, mixin);
			byte[] before = bytes(node);
			assertEquals(0, MixinEntitySoundCallbackAdapter.adapt(node, MERGED, NATIVE), mixin);
			assertArrayEquals(before, bytes(node), mixin);
		}
	}

	/** Vanilla's landing keeps no BlockPos local; the merged one does. With the vanilla body at hand that local is not a guess. */
	@Test void aCaptureTheVanillaBodyCannotResolveIsNotTakenFromTheMergedOne() throws Exception {
		ClassNode node = read(merged, "org/example/guess/mixin/FallGuessMixin");
		byte[] before = bytes(node);
		assertEquals(0, MixinEntitySoundCallbackAdapter.adapt(node, MERGED, NATIVE));
		assertArrayEquals(before, bytes(node));
	}

	private static MethodNode injector(ClassNode node) {
		List<MethodNode> injectors = node.methods.stream().filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.endsWith("/WrapOperation;"))).toList();
		assertEquals(1, injectors.size(), node.name);
		return injectors.getFirst();
	}

	private static Object value(MethodNode method, String key) {
		AnnotationNode injector = method.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
		for (int i = 0; i + 1 < injector.values.size(); i += 2) if (injector.values.get(i).equals(key)) return injector.values.get(i + 1);
		return null;
	}

	private static void verify(ClassNode node) throws Exception {
		for (MethodNode method : node.methods) if ((method.access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) == 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode read(Path classes, String name) {
		Path file = classes.resolve(name + ".class");
		if (!Files.isRegularFile(file)) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(Files.readAllBytes(file)).accept(node, 0);
			return node;
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
	}

	private static List<Path> sources(String part) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES.resolve(part))) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
