/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.KernelPredicateDelegates;

/**
 * {@link MixinPredicateDelegateAdapter} reads the search predicate's host as Mixin binds the selector, and the wrapped
 * {@code String.contains} as Mixin resolves the target in the method the mod was compiled against — never as strings
 * compared with {@code Lowner;name(desc)}. fabric-api's debug-option search wrap under other class and handler names,
 * its selector written with a dotted owner, an owner prefix, whitespace or two spellings, and its target with
 * whitespace, a dotted owner, and — where vanilla's {@code updateSearch} decides it — without its owner or descriptor:
 * each is lifted exactly as the released spelling is. A shortened target that also selects another owner's or
 * overload's call there, or a call left in the merged method, a target naming another owner, and a selector binding
 * another method leave the mixin exactly as written.
 */
@ResourceLock("DefinedMethodContracts")
class MixinPredicateDelegateSpellingTest {
	private static final String LIST = "net/minecraft/client/gui/screens/debug/DebugOptionsScreen$OptionList";
	private static final String CONTAINS = "Ljava/lang/String;contains(Ljava/lang/CharSequence;)Z";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

	@BeforeEach @AfterEach void reset() {
		DefinedMethodContracts.resetForTests();
		KernelPredicateDelegates.resetForTests();
	}

	private record Inputs(ClassNode mixin, MethodNode handler, Map<String, ClassNode> nodes, ClassNode original) {
		int adapt() { return MixinPredicateDelegateAdapter.adapt(mixin, nodes::get, n -> original); }
		boolean which(MethodNode method) { return method == handler; }
	}

	@Test void theReleasedWrapUnderOtherNamesIsLifted() throws Exception {
		assertLifted(inputs());
	}

	@TestFactory Stream<DynamicTest> theSelectorWrittenAnotherWayIsLifted() {
		Map<String, BiFunction<String, String, List<String>>> forms = new LinkedHashMap<>(PointRespelling.SELECTORS);
		forms.put("owner prefix with whitespace", (owner, s) -> List.of(" L" + owner + "; " + s + " "));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.selectors(input.mixin(), input::which, form.getValue()), "premise");
			assertLifted(input);
		}));
	}

	@TestFactory Stream<DynamicTest> theTargetWrittenAnotherWayIsLifted() {
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.points(input.mixin(), input::which, form, m -> PointRespelling.bound(m, input.original())), "premise");
			assertLifted(input);
		}));
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoSelectsAnotherCallIsLeftAlone() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) for (boolean nativeSide : new boolean[] { true, false }) {
			String crowd = (otherOwner ? "another owner" : "another overload") + (nativeSide ? " natively" : " in the merged method");
			if (nativeSide) tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still lifted", () -> assertLifted(crowded(inputs(), otherOwner, true))));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					Inputs plain = inputs(), input = crowded(plain, otherOwner, nativeSide);
					assertEquals(1, PointRespelling.points(input.mixin(), input::which, form, m -> PointRespelling.bound(m, plain.original())), "premise");
					assertLeftAlone(input);
				}));
			}
		}
		return tests.stream();
	}

	@Test void aTargetNamingAnotherOwnerIsLeftAlone() throws Exception {
		for (String target : List.of("Ljava/lang/StringBuilder;contains(Ljava/lang/CharSequence;)Z", "org.example.elsewhere.Text.contains(Ljava/lang/CharSequence;)Z")) {
			Inputs input = inputs();
			MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(input.handler())).getFirst(), "target", target);
			assertLeftAlone(input);
		}
	}

	@Test void aSelectorBindingAnotherMethodIsLeftAlone() throws Exception {
		Inputs probe = inputs();
		MethodNode other = probe.nodes().get(LIST).methods.stream().filter(m -> !m.name.equals("updateSearch") && !m.name.startsWith("<")
				&& m.desc.equals("(Ljava/lang/String;)V")).findFirst().orElse(null);
		List<List<String>> selectors = new ArrayList<>(List.of(List.of("Lnet/minecraft/client/gui/screens/debug/DebugOptionsScreen;updateSearch"),
				List.of("updateSearch", "lambda$static$0")));
		if (other != null) selectors.add(List.of(LIST.replace('/', '.') + "." + other.name + other.desc));
		for (List<String> written : selectors) {
			Inputs input = inputs();
			MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(input.handler()), "method", new ArrayList<>(written));
			assertLeftAlone(input);
		}
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertLifted(Inputs input) {
		String body = MixinInstructionFingerprint.hash(input.handler());
		assertEquals(1, input.adapt());
		MethodNode outer = input.mixin().methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.equals(WRAP)
				&& m.desc.startsWith("(Ljava/lang/String;Ljava/util/function/Consumer;Ljava/util/function/Consumer;")).findFirst().orElseThrow();
		String target = MixinFit.asString(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(outer)).getFirst(), "target"));
		assertTrue(target.endsWith("(Ljava/lang/String;Ljava/util/function/Consumer;Ljava/util/function/Consumer;)V"), "the wrap follows the native delegate: " + target);
		assertNull(MixinFit.injectorOf(input.handler()), "the original predicate runs inside the lifted graph");
		assertEquals(body, MixinInstructionFingerprint.hash(input.handler()), "its body unchanged");
	}

	private static void assertLeftAlone(Inputs input) {
		byte[] before = bytes(input.mixin());
		assertEquals(0, input.adapt());
		assertArrayEquals(before, bytes(input.mixin()), "the mixin was rewritten");
	}

	/**
	 * {@code input} with its native ({@code nativeSide}) or merged {@code updateSearch} crowded by {@link PointRespelling#crowd},
	 * which reads the point as released: crowd before respelling it.
	 */
	private static Inputs crowded(Inputs input, boolean otherOwner, boolean nativeSide) {
		ClassNode copy = PointRespelling.copy(nativeSide ? input.original() : input.nodes().get(LIST));
		assertEquals(1, PointRespelling.crowd(input.mixin(), input::which, copy, otherOwner), "premise: crowded");
		if (nativeSide) return new Inputs(input.mixin(), input.handler(), input.nodes(), copy);
		Map<String, ClassNode> nodes = new HashMap<>(input.nodes());
		nodes.put(LIST, copy);
		return new Inputs(input.mixin(), input.handler(), nodes, input.original());
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** fabric-api's debug-option mixin under other names, the merged classes, and the option list as the mod was compiled against it. */
	private static Inputs inputs() throws Exception {
		Path api = Path.of(System.getProperty("forbric.fabricApi", TestFixtures.fabricApi().toString()));
		Path base = Path.of(System.getProperty("forbric.predicateBase", TestFixtures.stagedBase().toString()));
		Path neo = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(api) && Files.isRegularFile(base) && Files.isRegularFile(neo), "actual predicate inputs required");
		ClassNode released = null;
		try (ZipFile jar = new ZipFile(api.toFile())) {
			var module = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-rendering-v1-")).findFirst().orElseThrow();
			try (ZipInputStream nested = new ZipInputStream(jar.getInputStream(module))) {
				for (var entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry())
					if (entry.getName().equals("net/fabricmc/fabric/mixin/client/rendering/DebugOptionsScreenOptionListMixin.class")) released = parse(nested.readAllBytes());
			}
		}
		assertNotNull(released, "the released debug-option mixin");
		Map<String, ClassNode> nodes = new HashMap<>();
		ClassNode original;
		try (ZipFile jar = new ZipFile(base.toFile())) {
			for (String owner : List.of(LIST, "net/minecraft/client/gui/components/debug/DebugScreenEntries"))
				nodes.put(owner, parse(jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes()));
			original = new NativeGameReferences(p -> {
				try {
					var e = jar.getEntry(p);
					return e == null ? null : jar.getInputStream(e).readAllBytes();
				} catch (Exception e) {
					return null;
				}
			}).get(Ecosystem.FABRIC, LIST);
		}
		try (ZipFile jar = new ZipFile(neo.toFile())) {
			String owner = "net/neoforged/neoforge/client/ClientHooks";
			nodes.put(owner, parse(jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes()));
		}
		assertNotNull(original, "the option list as the mod was compiled against it");
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(released);
		MethodNode handler = null;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null && MixinFit.atNodes(injector).stream().anyMatch(at -> CONTAINS.equals(MixinFit.value(at, "target")))) {
				assertNull(handler, "one wrap of contains");
				handler = method;
			}
		}
		assertNotNull(handler, "the search wrap");
		assertTrue(handler.name.startsWith("respelled"), "renamed");
		return new Inputs(mixin, handler, nodes, original);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
