/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link MixinCollectionSourceAdapter} reads the redirect's host and its field as Mixin reads them, not as they are
 * spelled. The fixture is not the registry API's: other names throughout, a getter that keeps its first stream in a
 * local, a handler that keeps its replacement in a local, and a host whose method has a second overload declared after
 * it. Its selector written owner-prefixed, dotted, with whitespace, as two spellings, or as the bare name — bound to the
 * first declared overload, which the class the mod was compiled against confirms — and its field written dotted, with
 * whitespace, or without its owner or descriptor — read in that class's method, which still reads the field — are each
 * adapted exactly as the released spelling is; so is fabric-api's released registry redirect under other names, every
 * way of writing it. A selector binding the other overload, a bare name the compiled-against class binds to another
 * overload, a field of another owner, a shortened field that also names another member there, and a shortened field
 * with no compiled-against class leave the mixin exactly as written.
 */
@ResourceLock("system-properties")
class MixinCollectionSourceSpellingTest {
	public static class Seeds { public static List<Object> base = new ArrayList<>(); }
	/** A copy of the seeds (made unmodifiable through a second field) and an independent suffix, streamed by {@link #all}. */
	public static class Catalog {
		public static final List<Object> mirror = new ArrayList<>(Seeds.base);
		public static final List<Object> frozen = Collections.unmodifiableList(mirror);
		public static final List<Object> extras = List.of("extra");
		public static Stream<Object> all() { Stream<Object> head = frozen.stream(); return Stream.concat(head, extras.stream()); }
	}
	public static class Elsewhere { public static List<Object> base = new ArrayList<>(); }
	/** The merged host: the old field read became the getter; a second overload follows. */
	public static class Workshop {
		public static Stream<Object> gather() { return Catalog.all(); }
		public static Stream<Object> gather(int limit) { return Stream.of(limit); }
	}
	/** The host the mod was compiled against (read under {@link Workshop}'s name), still reading the field. */
	public static class WrittenWorkshop {
		public static Stream<Object> gather() { return Seeds.base.stream(); }
		public static Stream<Object> gather(int limit) { return Stream.of(limit); }
	}
	/** The same, its overloads declared the other way round: a bare name was written for the one-argument one. */
	public static class ReorderedWorkshop {
		public static Stream<Object> gather(int limit) { return Stream.of(limit); }
		public static Stream<Object> gather() { return Seeds.base.stream(); }
	}
	public static class Visitor {
		public static int calls;
		public static List<Object> substitute = new ArrayList<>();
		private static List<Object> swap() { calls++; List<Object> out = substitute; return out; }
	}

	private static final String WORKSHOP = Type.getInternalName(Workshop.class), SEEDS = Type.getInternalName(Seeds.class);
	private static final String GATHER = "gather()Ljava/util/stream/Stream;";
	private static final String RELEASED_SELECTOR = GATHER, RELEASED_TARGET = "L" + SEEDS + ";base:Ljava/util/List;";

	@Test void theReleasedSpellingUnderOtherNamesIsAdapted() throws Exception {
		Fixture fixture = new Fixture(WrittenWorkshop.class);
		assertEquals(1, fixture.adapt());
		MethodNode wrapper = fixture.mixin.methods.stream().filter(m -> m.name.equals("swap")).findFirst().orElseThrow();
		assertEquals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", MixinFit.injectorOf(wrapper).desc);
		assertEquals("L" + Type.getInternalName(Catalog.class) + ";all()Ljava/util/stream/Stream;",
				MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst(), "target"));
		assertEquals(0, fixture.adapt(), "idempotence");
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayAreAdaptedAsReleased() throws Exception {
		String reference = released();
		String dotted = WORKSHOP.replace('/', '.');
		Map<String, List<String>> forms = new LinkedHashMap<>();
		forms.put("owner-prefixed", List.of("L" + WORKSHOP + ";" + GATHER));
		forms.put("dotted owner", List.of(dotted + "." + GATHER));
		forms.put("whitespace", List.of(" gather ()Ljava/util/stream/Stream; "));
		forms.put("two spellings", List.of(GATHER, " L" + WORKSHOP + "; " + GATHER));
		// A bare name binds the first declared gather in the merged host, and the compiled-against host declares the same one first.
		forms.put("bare name, first declared", List.of("gather"));
		forms.put("dotted owner, bare name", List.of(dotted + ".gather"));
		forms.put("owner-prefixed bare name", List.of("L" + WORKSHOP + ";gather"));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Fixture fixture = new Fixture(WrittenWorkshop.class);
			fixture.select(form.getValue());
			assertAdaptedAs(reference, fixture);
		}));
	}

	@TestFactory Stream<DynamicTest> fieldsWrittenAnotherWayAreAdaptedAsReleased() throws Exception {
		String reference = released();
		String dotted = SEEDS.replace('/', '.');
		Map<String, String> forms = new LinkedHashMap<>();
		forms.put("dotted owner", dotted + ".base:Ljava/util/List;");
		forms.put("whitespace", "L" + SEEDS + "; base : Ljava/util/List;");
		// Shortened: the compiled-against gather() reads one field it selects, Seeds.base.
		forms.put("no owner", "base:Ljava/util/List;");
		forms.put("no descriptor", "L" + SEEDS + ";base");
		forms.put("dotted owner, no descriptor", " " + dotted + " . base");
		forms.put("name alone", "base");
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Fixture fixture = new Fixture(WrittenWorkshop.class);
			fixture.target(form.getValue());
			assertAdaptedAs(reference, fixture);
		}));
	}

	/** The bare dotted selector and the name alone as the field, at once: both decided where the mod was compiled. */
	@Test void bothShortenedAtOnceAreAdaptedAsReleased() throws Exception {
		String reference = released();
		Fixture fixture = new Fixture(WrittenWorkshop.class);
		fixture.select(List.of(WORKSHOP.replace('/', '.') + ".gather"));
		fixture.target("base");
		assertAdaptedAs(reference, fixture);
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	@Test void aSelectorBindingTheOtherOverloadIsLeftAlone() throws Exception {
		Fixture fixture = new Fixture(WrittenWorkshop.class);
		fixture.select(List.of(WORKSHOP.replace('/', '.') + ".gather(I)Ljava/util/stream/Stream;"));
		fixture.assertLeftAlone();
	}

	/** Mixin would bind the merged host's first gather, but the mod's bare name was written for the other one. */
	@Test void aBareNameTheCompiledAgainstHostBindsToAnotherOverloadIsLeftAlone() throws Exception {
		for (List<String> selector : List.of(List.of("gather"), List.of("L" + WORKSHOP + ";gather"))) {
			Fixture fixture = new Fixture(ReorderedWorkshop.class);
			fixture.select(selector);
			fixture.assertLeftAlone();
		}
		Fixture missing = new Fixture(null);
		missing.select(List.of("gather"));
		missing.assertLeftAlone();
	}

	@Test void aFieldOfAnotherOwnerIsLeftAlone() throws Exception {
		String other = Type.getInternalName(Elsewhere.class);
		for (String target : List.of("L" + other + ";base:Ljava/util/List;", other.replace('/', '.') + ".base:Ljava/util/List;")) {
			Fixture fixture = new Fixture(WrittenWorkshop.class);
			fixture.target(target);
			fixture.assertLeftAlone();
		}
	}

	/**
	 * The compiled-against gather() also reads another owner's {@code base} list, or a {@code base} of another type: the
	 * released spelling still names one field and is adapted; a target without that owner or descriptor names two.
	 */
	@TestFactory Stream<DynamicTest> aShortFieldThatAlsoNamesAnotherMemberThereIsLeftAlone() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			String crowd = otherOwner ? "another owner" : "another descriptor";
			tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still adapted", () -> {
				Fixture fixture = new Fixture(WrittenWorkshop.class);
				fixture.crowd(otherOwner);
				assertEquals(1, fixture.adapt());
			}));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					Fixture fixture = new Fixture(WrittenWorkshop.class);
					assertEquals(1, PointRespelling.points(fixture.mixin, PointRespelling::any, form, m -> PointRespelling.bound(m, fixture.written)), "premise");
					fixture.crowd(otherOwner);
					fixture.assertLeftAlone();
				}));
			}
		}
		return tests.stream();
	}

	@Test void aShortFieldWithoutTheCompiledAgainstHostIsLeftAlone() throws Exception {
		for (String target : List.of("base:Ljava/util/List;", "L" + SEEDS + ";base", "base")) {
			Fixture fixture = new Fixture(null);
			fixture.target(target);
			fixture.assertLeftAlone();
		}
	}

	// ---- the registry API's own redirect -----------------------------------------------------------------------------------

	/**
	 * fabric-api's released registry redirect under other class and handler names, its selector and its field written
	 * every other way Mixin reads them — the shortened fields read in vanilla's {@code RegistryPatchGenerator}, the class
	 * it was compiled against — is adapted exactly as released.
	 */
	@TestFactory Stream<DynamicTest> theRegistryRedirectWrittenAnotherWayIsAdaptedAsReleased() throws Exception {
		Real real = Real.open();
		ClassNode baseline = real.mixin();
		assertEquals(1, real.adapt(baseline), "the released spelling under other names");
		String reference = normalized(baseline, real.releasedSelectors());
		List<DynamicTest> tests = new ArrayList<>();
		for (var form : PointRespelling.SELECTORS.entrySet()) tests.add(DynamicTest.dynamicTest(form.getKey(), () -> {
			ClassNode mixin = real.mixin();
			assertEquals(1, PointRespelling.selectors(mixin, PointRespelling::any, form.getValue()), "premise");
			assertRealAdaptedAs(real, reference, mixin);
		}));
		for (PointRespelling.Form form : PointRespelling.allForms()) tests.add(DynamicTest.dynamicTest(form.id(), () -> {
			ClassNode mixin = real.mixin();
			ClassNode written = real.references().apply(Real.TARGET);
			assertEquals(1, PointRespelling.points(mixin, PointRespelling::any, form, m -> PointRespelling.bound(m, written)), "premise");
			assertRealAdaptedAs(real, reference, mixin);
		}));
		return tests.stream();
	}

	private static void assertRealAdaptedAs(Real real, String reference, ClassNode mixin) {
		assertEquals(1, real.adapt(mixin), "adapted");
		assertEquals(reference, normalized(mixin, real.releasedSelectors()), "adapted exactly as the released spelling");
		assertEquals(0, real.adapt(mixin), "idempotence");
	}

	/** The staged merged base, NeoForge's runtime (the getter's holder), vanilla as fabric-api was compiled against it. */
	private record Real(Function<String, ClassNode> classes, Function<String, ClassNode> references, byte[] released, List<String> releasedSelectors) {
		static final String TARGET = "net/minecraft/data/registries/RegistryPatchGenerator";

		static Real open() throws Exception {
			Path base = TestFixtures.stagedBase();
			Path neo = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
			Path api = TestFixtures.fabricApi();
			TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "staged merged base, NeoForge runtime and the actual Fabric API", base, neo, api);
			Map<String, byte[]> cache = new HashMap<>();
			Function<String, byte[]> resource = name -> cache.computeIfAbsent(name, path -> {
				for (Path jar : List.of(base, neo)) try (ZipFile zip = new ZipFile(jar.toFile())) {
					var entry = zip.getEntry(path);
					if (entry != null) return zip.getInputStream(entry).readAllBytes();
				} catch (java.io.IOException unreadable) { throw new IllegalStateException(unreadable); }
				return null;
			});
			NativeGameReferences natives = new NativeGameReferences(path -> {
				try (ZipFile zip = new ZipFile(base.toFile())) {
					var entry = zip.getEntry(path);
					return entry == null ? null : zip.getInputStream(entry).readAllBytes();
				} catch (java.io.IOException unreadable) { throw new IllegalStateException(unreadable); }
			}, null, TestFixtures.launchSources(base));
			Function<String, ClassNode> references = owner -> natives.get(Ecosystem.FABRIC, owner);
			TestFixtures.require(TestFixtures.Fixture.STAGED, references.apply(TARGET) != null, "native-indexed vanilla RegistryPatchGenerator required");
			byte[] released = null;
			try (ZipFile outer = new ZipFile(api.toFile())) {
				var module = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-registry-sync-v0-")).findFirst().orElseThrow();
				try (ZipInputStream inner = new ZipInputStream(outer.getInputStream(module))) {
					for (var e = inner.getNextEntry(); e != null; e = inner.getNextEntry())
						if (e.getName().equals("net/fabricmc/fabric/mixin/registry/sync/RegistryPatchGeneratorMixin.class")) released = inner.readAllBytes();
				}
			}
			assertNotNull(released, "the registry redirect");
			ClassNode renamed = MixinCallbackSelectorSpellingTest.unrelatedNames(parse(released));
			List<String> selectors = null;
			for (MethodNode method : renamed.methods) if (MixinFit.injectorOf(method) != null) {
				assertNull(selectors, "one injector");
				selectors = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method), "method"));
			}
			return new Real(name -> { byte[] bytes = resource.apply(name + ".class"); return bytes == null ? null : parse(bytes); },
					references, CarpetMixinAdapterTest.bytes(renamed), List.copyOf(selectors));
		}

		/** A fresh copy of the renamed redirect. */
		ClassNode mixin() { return parse(released); }

		int adapt(ClassNode mixin) { return MixinCollectionSourceAdapter.adapt(mixin, classes, references); }
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	/** The adapted mixin of the released spelling, its selector and field as released. */
	private static String released() throws Exception {
		Fixture fixture = new Fixture(WrittenWorkshop.class);
		assertEquals(1, fixture.adapt());
		return normalized(fixture.mixin, List.of(RELEASED_SELECTOR));
	}

	private static void assertAdaptedAs(String reference, Fixture fixture) {
		assertEquals(1, fixture.adapt(), "adapted");
		assertEquals(reference, normalized(fixture.mixin, List.of(RELEASED_SELECTOR)), "adapted exactly as the released spelling");
		assertEquals(0, fixture.adapt(), "idempotence");
	}

	/**
	 * The adapted mixin with every injector's selectors put back as released: the wrap keeps the selectors as written (Mixin
	 * binds them), and names the getter in full whatever the field's spelling; what else differs is a different adaptation.
	 */
	private static String normalized(ClassNode mixin, List<String> selectors) {
		ClassNode copy = PointRespelling.copy(mixin);
		for (MethodNode method : copy.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null) set(injector, "method", new ArrayList<>(selectors));
		}
		return java.util.HexFormat.of().formatHex(CarpetMixinAdapterTest.bytes(copy));
	}

	private static final class Fixture {
		final Map<String, ClassNode> nodes = new HashMap<>();
		final ClassNode mixin, written;

		Fixture(Class<?> writtenHost) throws Exception {
			for (Class<?> type : List.of(Seeds.class, Catalog.class, Elsewhere.class, Workshop.class, Visitor.class))
				nodes.put(Type.getInternalName(type), parse(type));
			written = writtenHost == null ? null : renamed(parse(writtenHost), Type.getInternalName(writtenHost), WORKSHOP);
			mixin = nodes.get(Type.getInternalName(Visitor.class));
			AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
			target.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(WORKSHOP))));
			mixin.visibleAnnotations = new ArrayList<>(List.of(target));
			AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
			at.values = new ArrayList<>(List.of("value", "FIELD", "target", RELEASED_TARGET));
			AnnotationNode redirect = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
			redirect.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(RELEASED_SELECTOR)), "at", at));
			handler().visibleAnnotations = new ArrayList<>(List.of(redirect));
		}

		MethodNode handler() { return mixin.methods.stream().filter(m -> m.name.equals("swap")).findFirst().orElseThrow(); }
		AnnotationNode injector() { return MixinFit.injectorOf(handler()); }
		void select(List<String> selectors) { set(injector(), "method", new ArrayList<>(selectors)); }
		void target(String target) { set(MixinFit.atNodes(injector()).getFirst(), "target", target); }
		int adapt() {
			ClassNode host = written;
			return MixinCollectionSourceAdapter.adapt(mixin, nodes::get, name -> name.equals(WORKSHOP) ? host : null);
		}
		void crowd(boolean otherOwner) {
			// Crowds through a copy whose field is still released, so a respelled field is crowded the same way.
			ClassNode probe = PointRespelling.copy(mixin);
			for (MethodNode method : probe.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector != null) set(MixinFit.atNodes(injector).getFirst(), "target", RELEASED_TARGET);
			}
			assertEquals(1, PointRespelling.crowd(probe, PointRespelling::any, written, otherOwner), "premise: the written gather crowded");
		}
		void assertLeftAlone() {
			byte[] before = CarpetMixinAdapterTest.bytes(mixin);
			assertEquals(0, adapt());
			assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), "the mixin was rewritten");
		}
	}

	private static ClassNode parse(Class<?> type) throws Exception {
		try (var in = type.getResourceAsStream("/" + Type.getInternalName(type) + ".class")) {
			return parse(in.readAllBytes());
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static ClassNode renamed(ClassNode node, String from, String to) {
		ClassNode out = new ClassNode();
		node.accept(new ClassRemapper(out, new SimpleRemapper(Opcodes.ASM9, from, to)));
		return out;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; }
		annotation.values.add(key); annotation.values.add(value);
	}
}
