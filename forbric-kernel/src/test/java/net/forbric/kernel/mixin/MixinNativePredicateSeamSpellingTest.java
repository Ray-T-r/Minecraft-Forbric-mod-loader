/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link MixinNativePredicateSeam} reads the wrap's methods and its atom as Mixin reads them: fabric-api's released
 * boat wrap under other names, its two selectors written owner-prefixed, dotted, as two spellings, with their
 * descriptors and whitespace, or as bare names that a compiled-against class with a later-declared overload still binds
 * to the same method, and its target written with whitespace, a dotted owner, no owner or no descriptor — read in the
 * two vanilla methods it was written for. Each is adapted exactly as the released spelling is. A bare name the
 * compiled-against class binds to another overload, a selector binding another method, a target naming another owner's
 * member, and a shortened target that also names another member there leave the mixin exactly as written.
 */
@ResourceLock("system-properties")
class MixinNativePredicateSeamSpellingTest {
	private static final String HOST = "net/minecraft/world/entity/vehicle/boat/AbstractBoat";
	private static final String RELEASED_TARGET = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z";

	@Test void theReleasedWrapUnderOtherNamesIsAdapted() throws Exception {
		Inputs in = inputs();
		ClassNode mixin = in.mixin();
		assertEquals(1, in.adapt(mixin));
		List<String> selectors = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(in.handler(mixin)), "method"));
		assertEquals(1, selectors.size());
		assertTrue(selectors.getFirst().contains("forbricsourcepredicate"), selectors.toString());
		assertEquals(0, in.adapt(mixin), "idempotence");
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayAreAdaptedAsReleased() throws Exception {
		Inputs in = inputs();
		String reference = in.released();
		ClassNode natives = in.natives().apply(HOST);
		Map<String, UnaryOperator<ClassNode>> forms = new LinkedHashMap<>();
		for (var form : PointRespelling.SELECTORS.entrySet()) forms.put(form.getKey(), mixin -> {
			assertEquals(2, PointRespelling.selectors(mixin, PointRespelling::any, form.getValue()), "premise: both selectors respelled");
			return mixin;
		});
		forms.put("descriptors and whitespace", mixin -> {
			List<String> spelled = new ArrayList<>();
			for (String selector : in.releasedSelectors()) spelled.add(" " + selector + " " + descriptor(natives, selector) + " ");
			select(in.handler(mixin), spelled);
			return mixin;
		});
		forms.put("one dotted with its descriptor, the other owner-prefixed bare", mixin -> {
			String first = in.releasedSelectors().getFirst(), second = in.releasedSelectors().get(1);
			select(in.handler(mixin), List.of(HOST.replace('/', '.') + "." + first + descriptor(natives, first), "L" + HOST + ";" + second));
			return mixin;
		});
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			ClassNode mixin = form.getValue().apply(in.mixin());
			assertAdaptedAs(in, in.natives(), reference, mixin);
		}));
	}

	/**
	 * Bare names, where the class the mod was compiled against also declares another overload of each AFTER the one it
	 * binds: Mixin still binds the first declared, so the wrap is adapted exactly as released.
	 */
	@Test void bareNamesTheCompiledAgainstClassBindsToTheFirstDeclaredAreAdaptedAsReleased() throws Exception {
		Inputs in = inputs();
		String reference = in.released();
		ClassNode mixin = in.mixin();
		assertAdaptedAs(in, overloaded(in, false), reference, mixin);
	}

	@TestFactory Stream<DynamicTest> targetsWrittenAnotherWayAreAdaptedAsReleased() throws Exception {
		Inputs in = inputs();
		String reference = in.released();
		ClassNode natives = in.natives().apply(HOST);
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			ClassNode mixin = in.mixin();
			assertEquals(1, PointRespelling.points(mixin, PointRespelling::any, form, m -> PointRespelling.bound(m, natives)), "premise");
			assertAdaptedAs(in, in.natives(), reference, mixin);
		}));
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	/** The same overloads declared BEFORE the methods the released wrap names: the bare names were written for them. */
	@Test void bareNamesTheCompiledAgainstClassBindsToAnotherOverloadAreLeftAlone() throws Exception {
		Inputs in = inputs();
		assertLeftAlone(in, overloaded(in, true), in.mixin());
	}

	@Test void aSelectorBindingAnotherMethodIsLeftAlone() throws Exception {
		Inputs in = inputs();
		ClassNode natives = in.natives().apply(HOST);
		MethodNode other = natives.methods.stream().filter(m -> !m.name.startsWith("<") && !in.releasedSelectors().contains(m.name)
				&& m.desc.equals("()Z") && (m.access & Opcodes.ACC_STATIC) == 0).findFirst().orElseThrow();
		for (List<String> selectors : List.of(List.of(HOST.replace('/', '.') + "." + other.name + other.desc),
				List.of(in.releasedSelectors().getFirst(), "L" + HOST + ";" + other.name))) {
			ClassNode mixin = in.mixin();
			select(in.handler(mixin), selectors);
			assertLeftAlone(in, in.natives(), mixin);
		}
	}

	@Test void aTargetNamingAnotherOwnersMemberIsLeftAlone() throws Exception {
		Inputs in = inputs();
		for (String target : List.of("Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z",
				"org.example.elsewhere.Gauge.is(Lnet/minecraft/tags/TagKey;)Z")) {
			ClassNode mixin = in.mixin();
			point(in.handler(mixin), target);
			assertLeftAlone(in, in.natives(), mixin);
		}
	}

	/**
	 * The two vanilla methods crowded with another owner's {@code is(TagKey)Z}, or with another {@code is} overload: the
	 * released spelling still names one call in each and is adapted, a shortened one names two.
	 */
	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoNamesAnotherMemberThereIsLeftAlone() throws Exception {
		Inputs in = inputs();
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			String crowd = otherOwner ? "another owner" : "another overload";
			tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still adapted", () -> {
				ClassNode mixin = in.mixin();
				assertEquals(1, in.adapt(mixin, crowded(in, otherOwner)));
			}));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					ClassNode mixin = in.mixin();
					ClassNode natives = in.natives().apply(HOST);
					assertEquals(1, PointRespelling.points(mixin, PointRespelling::any, form, m -> PointRespelling.bound(m, natives)), "premise");
					assertLeftAlone(in, crowded(in, otherOwner), mixin);
				}));
			}
		}
		return tests.stream();
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertAdaptedAs(Inputs in, Function<String, ClassNode> natives, String reference, ClassNode mixin) {
		assertEquals(1, in.adapt(mixin, natives), "adapted");
		assertEquals(reference, in.normalized(mixin), "adapted exactly as the released spelling");
		assertEquals(0, in.adapt(mixin, natives), "idempotence");
	}

	private static void assertLeftAlone(Inputs in, Function<String, ClassNode> natives, ClassNode mixin) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, in.adapt(mixin, natives));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), "the mixin was rewritten");
	}

	/**
	 * The native classes with another overload of each method the released selectors name, declared before ({@code first})
	 * or after the vanilla one. Its body is a bare return: it has no atom and the merged class has no such method.
	 */
	private static Function<String, ClassNode> overloaded(Inputs in, boolean first) {
		ClassNode host = in.natives().apply(HOST);
		int added = 0;
		for (String name : in.releasedSelectors()) {
			MethodNode decoy = new MethodNode(Opcodes.ACC_PUBLIC, name, "(I)Z", null, null);
			decoy.instructions.add(new InsnNode(Opcodes.ICONST_0));
			decoy.instructions.add(new InsnNode(Opcodes.IRETURN));
			decoy.maxStack = 1; decoy.maxLocals = 2;
			if (first) host.methods.addFirst(decoy); else host.methods.add(decoy);
			added++;
		}
		assertEquals(2, added, "premise: both names overloaded");
		return name -> name.equals(HOST) ? host : in.natives().apply(name);
	}

	/** The native classes with both vanilla methods crowded by {@link PointRespelling#crowd}. */
	private static Function<String, ClassNode> crowded(Inputs in, boolean otherOwner) {
		ClassNode host = in.natives().apply(HOST);
		assertEquals(2, PointRespelling.crowd(in.mixin(), PointRespelling::any, host, otherOwner), "premise: both methods crowded");
		return name -> name.equals(HOST) ? host : in.natives().apply(name);
	}

	/** The descriptor of the one method {@code name} names in {@code natives}. */
	private static String descriptor(ClassNode natives, String name) {
		List<MethodNode> named = natives.methods.stream().filter(m -> m.name.equals(name)).toList();
		assertEquals(1, named.size(), "premise: one " + name);
		return named.getFirst().desc;
	}

	private static void select(MethodNode handler, List<String> selectors) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(selectors));
	}

	private static void point(MethodNode handler, String target) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target", target);
	}

	/** The staged merged base and runtimes, vanilla as fabric-api was compiled against it, and the renamed released wrap. */
	private record Inputs(Function<String, ClassNode> classes, Function<String, ClassNode> natives, byte[] releasedBytes,
			String handlerName, List<String> releasedSelectors) {
		ClassNode mixin() { return parse(releasedBytes); }
		MethodNode handler(ClassNode mixin) { return mixin.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst().orElseThrow(); }
		int adapt(ClassNode mixin) { return adapt(mixin, natives); }
		int adapt(ClassNode mixin, Function<String, ClassNode> originals) { return MixinNativePredicateSeam.adapt(mixin, classes, originals); }

		/** The adapted released wrap. */
		String released() {
			ClassNode mixin = mixin();
			assertEquals(1, adapt(mixin));
			return normalized(mixin);
		}

		/**
		 * The adapted mixin with the original handler's target put back as released: the handler now wraps the call in its
		 * own source helper (its selectors replaced by the helper's), and the new wrap names both methods and the live query
		 * in full; what else differs is a different adaptation.
		 */
		String normalized(ClassNode mixin) {
			ClassNode copy = PointRespelling.copy(mixin);
			point(handler(copy), RELEASED_TARGET);
			return java.util.HexFormat.of().formatHex(CarpetMixinAdapterTest.bytes(copy));
		}
	}

	private static Inputs inputs() throws Exception {
		Path base = Path.of(System.getProperty("forbric.predicateBase", TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));
		Path neo = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"), forge = TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar");
		Path api = Path.of(System.getProperty("forbric.fabricApi", TestFixtures.fabricApi().toString()));
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "hash-pinned native predicate base, runtimes and the actual Fabric API", base, neo, forge, api);
		Map<String, byte[]> cache = new HashMap<>();
		Function<String, byte[]> resource = name -> cache.computeIfAbsent(name, path -> {
			for (Path jar : List.of(base, neo, forge)) try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				if (entry != null) return zip.getInputStream(entry).readAllBytes();
			} catch (java.io.IOException unreadable) { throw new IllegalStateException(unreadable); }
			return null;
		});
		NativeGameReferences references = new NativeGameReferences(resource);
		TestFixtures.require(TestFixtures.Fixture.STAGED, references.get(Ecosystem.FABRIC, HOST) != null, "native-indexed vanilla AbstractBoat required");
		byte[] guest = null;
		try (ZipFile outer = new ZipFile(api.toFile())) {
			var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();
			try (ZipInputStream inner = new ZipInputStream(outer.getInputStream(module))) {
				for (var e = inner.getNextEntry(); e != null; e = inner.getNextEntry())
					if (e.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/AbstractBoatMixin.class")) guest = inner.readAllBytes();
			}
		}
		assertNotNull(guest, "the boat wrap");
		ClassNode renamed = MixinCallbackSelectorSpellingTest.unrelatedNames(parse(guest));
		MethodNode handler = null;
		for (MethodNode method : renamed.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null) continue;
			assertNull(handler, "one injector");
			assertEquals(RELEASED_TARGET, MixinFit.value(MixinFit.atNodes(injector).getFirst(), "target"));
			handler = method;
		}
		assertNotNull(handler);
		assertTrue(handler.name.startsWith("respelled"), "renamed");
		List<String> selectors = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"));
		assertEquals(2, selectors.size(), "the released wrap names two methods");
		return new Inputs(name -> { byte[] bytes = resource.apply(name + ".class"); return bytes == null ? null : parse(bytes); },
				name -> references.get(Ecosystem.FABRIC, name), CarpetMixinAdapterTest.bytes(renamed), handler.name, List.copyOf(selectors));
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
