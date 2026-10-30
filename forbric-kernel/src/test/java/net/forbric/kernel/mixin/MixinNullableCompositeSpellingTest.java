/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link MixinNullableCompositeCallback} reads a fallback callback's host as Mixin binds the selector, and the lookup it
 * captures as Mixin resolves the target in the method the mod was compiled against — never as strings compared with
 * {@code Lowner;name(desc)}. fabric-api's two hopper fallbacks under other class and handler names, their selectors written
 * with a dotted owner, an owner prefix, whitespace or two spellings, and their targets with whitespace, a dotted owner,
 * and — where vanilla's hopper method decides it — without the owner or the descriptor: each follows the composite
 * lookup exactly as the released spelling does. A shortened target that also selects another owner's or overload's call
 * there or in the merged method, a target naming another owner, and a selector binding another method leave the mixin as
 * written.
 */
@ResourceLock("system-properties")
class MixinNullableCompositeSpellingTest {
	private static final String HOPPER = "net/minecraft/world/level/block/entity/HopperBlockEntity";

	@AfterEach void reset() {
		System.clearProperty(MixinNullableCompositeCallback.PROPERTY);
	}

	private record Inputs(ClassNode mixin, Map<String, ClassNode> classes, Function<String, ClassNode> current, Function<String, ClassNode> source,
			Function<String, ClassNode> nativeSource, Function<String, ClassNode> runtimeSource) {
		int adapt() { return MixinNullableCompositeCallback.adapt(mixin, current, source, nativeSource, runtimeSource); }
		ClassNode reference() { return source.apply(HOPPER); }
	}

	@Test void theReleasedFallbacksUnderOtherNamesFollowTheLookup() throws Exception {
		assertFollowed(inputs(), 2);
	}

	@TestFactory Stream<DynamicTest> theSelectorsWrittenAnotherWayFollowTheLookup() {
		Map<String, BiFunction<String, String, List<String>>> forms = new LinkedHashMap<>(PointRespelling.SELECTORS);
		forms.put("owner prefix with whitespace", (owner, s) -> List.of(" L" + owner + "; " + s + " "));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Inputs input = inputs();
			assertEquals(2, PointRespelling.selectors(input.mixin(), MixinNullableCompositeSpellingTest::hook, form.getValue()), "premise");
			assertFollowed(input, 2);
		}));
	}

	@TestFactory Stream<DynamicTest> theTargetsWrittenAnotherWayFollowTheLookup() {
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			Inputs input = inputs();
			ClassNode reference = input.reference();
			assertEquals(2, PointRespelling.points(input.mixin(), MixinNullableCompositeSpellingTest::hook, form, m -> PointRespelling.bound(m, reference)), "premise");
			assertFollowed(input, 2);
		}));
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoSelectsAnotherCallLeavesTheFallbacks() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) for (boolean nativeSide : new boolean[] { true, false }) {
			String crowd = (otherOwner ? "another owner" : "another overload") + (nativeSide ? " natively" : " in the merged method");
			if (nativeSide) tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still followed", () -> assertFollowed(crowded(inputs(), otherOwner, true), 2)));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					Inputs plain = inputs(), input = crowded(plain, otherOwner, nativeSide);
					ClassNode reference = plain.reference();
					assertEquals(2, PointRespelling.points(input.mixin(), MixinNullableCompositeSpellingTest::hook, form, m -> PointRespelling.bound(m, reference)), "premise");
					assertLeftAlone(input);
				}));
			}
		}
		return tests.stream();
	}

	@Test void aTargetNamingAnotherOwnerLeavesThatFallback() throws Exception {
		for (String owner : List.of("Lnet/minecraft/world/level/block/entity/DropperBlockEntity;", "org.example.elsewhere.Funnel.")) {
			Inputs input = inputs();
			MethodNode insert = handler(input.mixin(), "getAttachedContainer");
			AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(insert)).getFirst();
			MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
			MixinPlayerWorldCallbackAdapter.set(at, "target", owner + member.name() + member.desc());
			String body = MixinInstructionFingerprint.hash(insert);
			assertEquals(1, input.adapt(), "the other fallback still follows its lookup");
			assertNotNull(MixinFit.injectorOf(insert), "this one stays as written");
			assertEquals(body, MixinInstructionFingerprint.hash(insert));
		}
	}

	@Test void aSelectorBindingAnotherMethodLeavesThatFallback() throws Exception {
		for (List<String> selectors : List.of(List.of("Lnet/minecraft/world/level/block/entity/DropperBlockEntity;ejectItems"),
				List.of(HOPPER.replace('/', '.') + ".pushItemsTick"), List.of("ejectItems", "pushItemsTick"))) {
			Inputs input = inputs();
			MethodNode insert = handler(input.mixin(), "getAttachedContainer");
			MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(insert), "method", new ArrayList<>(selectors));
			assertEquals(1, input.adapt(), selectors + ": the other fallback still follows its lookup");
			assertNotNull(MixinFit.injectorOf(insert), selectors + ": this one stays as written");
		}
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertFollowed(Inputs input, int count) {
		Map<String, String> bodies = new HashMap<>();
		for (MethodNode m : input.mixin().methods) if (hook(m)) bodies.put(m.name, MixinInstructionFingerprint.hash(m));
		assertEquals(count, input.adapt());
		for (var body : bodies.entrySet()) {
			MethodNode inner = input.mixin().methods.stream().filter(m -> m.name.contains(body.getKey() + "$forbricnullablecomposite")).findFirst().orElseThrow();
			assertEquals(body.getValue(), MixinInstructionFingerprint.hash(inner), "the fallback's own body");
			MethodNode outer = input.mixin().methods.stream().filter(m -> m.name.equals(body.getKey())).findFirst().orElseThrow();
			assertTrue(outer.desc.contains("ContainerOrHandler;"), "captures the composite lookup");
		}
		assertEquals(0, input.adapt(), "once");
	}

	private static void assertLeftAlone(Inputs input) {
		byte[] before = bytes(input.mixin());
		assertEquals(0, input.adapt());
		assertArrayEquals(before, bytes(input.mixin()), "the mixin was rewritten");
	}

	/** One of the two fallbacks: a cancellable {@code @Inject} at an {@code INVOKE_ASSIGN}. */
	private static boolean hook(MethodNode method) {
		AnnotationNode injector = MixinFit.injectorOf(method);
		return injector != null && injector.desc.endsWith("/Inject;") && MixinFit.atNodes(injector).stream().anyMatch(at -> "INVOKE_ASSIGN".equals(MixinFit.value(at, "value")));
	}

	/** The fallback whose point names {@code lookup}. */
	private static MethodNode handler(ClassNode mixin, String lookup) {
		return mixin.methods.stream().filter(m -> hook(m) && MixinFit.parseMember(MixinFit.asString(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(), "target"))).name().equals(lookup)).findFirst().orElseThrow();
	}

	/**
	 * {@code input} with the native ({@code nativeSide}) or merged hopper crowded by {@link PointRespelling#crowd}, which
	 * reads the points as released: crowd before respelling them.
	 */
	private static Inputs crowded(Inputs input, boolean otherOwner, boolean nativeSide) {
		ClassNode copy = PointRespelling.copy(nativeSide ? input.reference() : input.current().apply(HOPPER));
		assertEquals(2, PointRespelling.crowd(input.mixin(), MixinNullableCompositeSpellingTest::hook, copy, otherOwner), "premise: crowded");
		if (nativeSide) return new Inputs(input.mixin(), input.classes(), input.current(), n -> n.equals(HOPPER) ? copy : input.source().apply(n), input.nativeSource(), input.runtimeSource());
		return new Inputs(input.mixin(), input.classes(), n -> n.equals(HOPPER) ? copy : input.current().apply(n), input.source(), input.nativeSource(), input.runtimeSource());
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** fabric-api's hopper mixin under other names, the merged and NeoForge classes, and vanilla as the mod was compiled against it. */
	private static Inputs inputs() throws Exception {
		Path api = Path.of(System.getProperty("forbric.fabricApi", TestFixtures.fabricApi().toString()));
		Path base = Path.of(System.getProperty("forbric.predicateBase", TestFixtures.stagedBase().toString()));
		Path neo = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "actual API/indexed base/runtime required", api, base, neo);
		ClassNode released = null;
		try (ZipFile jar = new ZipFile(api.toFile())) {
			var module = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-transfer-api-v1-")).findFirst().orElseThrow();
			try (ZipInputStream nested = new ZipInputStream(jar.getInputStream(module))) {
				for (var e = nested.getNextEntry(); e != null; e = nested.getNextEntry())
					if (e.getName().equals("net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class")) released = parse(nested.readAllBytes());
			}
		}
		assertNotNull(released, "the released hopper mixin");
		Map<String, ClassNode> classes = new HashMap<>();
		Function<String, byte[]> raw = path -> {
			try (ZipFile jar = new ZipFile(base.toFile())) {
				var entry = jar.getEntry(path);
				return entry == null ? null : jar.getInputStream(entry).readAllBytes();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};
		Function<String, ClassNode> runtime = name -> {
			try (ZipFile jar = new ZipFile(neo.toFile())) {
				var entry = jar.getEntry(name + ".class");
				return entry == null ? null : parse(jar.getInputStream(entry).readAllBytes());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};
		Function<String, ClassNode> current = name -> classes.computeIfAbsent(name, n -> {
			byte[] bytes = raw.apply(n + ".class");
			return bytes == null ? runtime.apply(n) : parse(bytes);
		});
		NativeGameReferences originals = new NativeGameReferences(raw);
		assertNotNull(originals.get(Ecosystem.FABRIC, HOPPER), "the hopper as the mod was compiled against it");
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(released);
		assertTrue(mixin.methods.stream().filter(MixinNullableCompositeSpellingTest::hook).allMatch(m -> m.name.startsWith("respelled")), "renamed");
		return new Inputs(mixin, classes, current, n -> originals.get(Ecosystem.FABRIC, n), n -> originals.get(Ecosystem.NEOFORGE, n), runtime);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
