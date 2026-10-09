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
import net.forbric.kernel.mixin.MixinBlockQueryAdapters;

/**
 * What {@code MixinBlockQueryAdapters} decides on the stand-ins of {@link BlockQueryParityWeaveTest}. Moved: a wrap that
 * passes on the block it was handed — kept in a local, or put in an argument array built first, Kotlin's way — and a
 * value-only {@code @ModifyExpressionValue} whose ordinal the vanilla body translates. Left byte-for-byte alone: a wrap
 * that hands its original another block, reassigns its block on one path, or gives its Operation to a helper (an
 * original answering with the call site's operands would ignore what they pass); a wrap whose merged counterpart, read
 * beside the vanilla body, asks about another position; a wrap whose merged method asks a fluid, from which nothing leads
 * back to a block; and an ordinal with no vanilla body to count it in.
 */
class BlockQueryShapesTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/blockquery");
	@TempDir static Path work;
	private static Path vanilla, merged;

	@BeforeAll static void compile() throws Exception {
		List<Path> vanillaSources = new ArrayList<>(sources("common"));
		vanillaSources.addAll(sources("vanilla"));
		WeaveHarness.fixture(work, "bq-vanilla", vanillaSources, Map.of(), List.of("-g"));
		List<Path> mergedSources = new ArrayList<>(sources("common"));
		mergedSources.addAll(sources("merged"));
		mergedSources.addAll(sources("mods"));
		mergedSources.addAll(sources("shapes"));
		WeaveHarness.fixture(work, "bq-merged", mergedSources, Map.of(), List.of("-g"));
		vanilla = work.resolve("bq-vanilla-classes");
		merged = work.resolve("bq-merged-classes");
	}

	private static final Function<String, ClassNode> MERGED = name -> read(merged, name);
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVE = (family, name) -> read(vanilla, name);
	private static final BiFunction<Ecosystem, String, ClassNode> NO_NATIVE = (family, name) -> null;
	private static final String STATE_FRICTION = "Lnet/minecraft/world/level/block/state/BlockState;getFriction(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)F";

	@Test void wrapsThatPassTheirBlockOnMoveOntoTheStatesQuery() throws Exception {
		for (String mixin : List.of("org/example/slick/mixin/SlideMixin", "org/example/spread/mixin/SpreadMixin")) {
			for (var references : List.of(NATIVE, NO_NATIVE)) {
				ClassNode node = read(merged, mixin);
				assertEquals(1, MixinBlockQueryAdapters.adapt(node, MERGED, references), mixin);
				assertEquals(STATE_FRICTION, target(injector(node, "WrapOperation")), mixin);
				verify(node);
				assertEquals(0, MixinBlockQueryAdapters.adapt(node, MERGED, references), mixin + " idempotence");
			}
		}
	}

	@Test void aValueOnlyHookKeepsItsOrdinalWhereTheVanillaBodyPairsIt() throws Exception {
		ClassNode node = read(merged, "org/example/drifter/mixin/DriftMixin");
		assertEquals(1, MixinBlockQueryAdapters.adapt(node, MERGED, NATIVE));
		AnnotationNode point = point(injector(node, "ModifyExpressionValue"));
		assertEquals(STATE_FRICTION, value(point, "target"));
		assertEquals(1, value(point, "ordinal"), "the second vanilla query pairs with the second merged one");
		ClassNode unpaired = read(merged, "org/example/drifter/mixin/DriftMixin");
		byte[] before = bytes(unpaired);
		assertEquals(0, MixinBlockQueryAdapters.adapt(unpaired, MERGED, NO_NATIVE), "an ordinal is a vanilla count: no vanilla body, no translation");
		assertArrayEquals(before, bytes(unpaired));
	}

	@Test void wrapsThatDoNotPassTheirOwnBlockOnAreLeftAsWritten() throws Exception {
		for (String mixin : List.of("org/example/swapper/mixin/SwapMixin", "org/example/reassigned/mixin/ReassignMixin", "org/example/leaky/mixin/LeakMixin")) {
			for (var references : List.of(NATIVE, NO_NATIVE)) {
				ClassNode node = read(merged, mixin);
				byte[] before = bytes(node);
				assertEquals(0, MixinBlockQueryAdapters.adapt(node, MERGED, references), mixin);
				assertArrayEquals(before, bytes(node), mixin);
			}
		}
	}

	@Test void lookAlikesTheBodiesDoNotPairAreLeftAsWritten() throws Exception {
		for (String mixin : List.of("org/example/shifted/mixin/ShiftedMixin", "org/example/fluid/mixin/WobbleMixin")) {
			ClassNode node = read(merged, mixin);
			byte[] before = bytes(node);
			assertEquals(0, MixinBlockQueryAdapters.adapt(node, MERGED, NATIVE), mixin);
			assertArrayEquals(before, bytes(node), mixin);
		}
		ClassNode fluid = read(merged, "org/example/fluid/mixin/WobbleMixin");
		assertEquals(0, MixinBlockQueryAdapters.adapt(fluid, MERGED, NO_NATIVE), "no getter leads from a fluid back to a block");
	}

	private static MethodNode injector(ClassNode node, String kind) {
		List<MethodNode> found = node.methods.stream().filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.endsWith("/" + kind + ";"))).toList();
		assertEquals(1, found.size(), node.name);
		return found.getFirst();
	}

	private static AnnotationNode point(MethodNode handler) {
		AnnotationNode injector = handler.visibleAnnotations.stream().filter(a -> a.desc.contains("/injector/") || a.desc.contains("/injection/")).findFirst().orElseThrow();
		Object at = value(injector, "at");
		return (AnnotationNode) (at instanceof List<?> list ? list.getFirst() : at);
	}

	private static Object target(MethodNode handler) {
		return value(point(handler), "target");
	}

	private static Object value(AnnotationNode annotation, String key) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
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
