/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import net.forbric.kernel.boot.DefaultPredicateDispatch;
import net.forbric.kernel.boot.DefinedMethodContracts;

/**
 * {@link MixinDefaultPredicateAdapter} reads the redirect's host and its old call as Mixin reads them, not as they are
 * spelled. The fixture is not the renderer API's: other names throughout, a handler that captures two of the target's
 * arguments instead of four and builds the rest itself, and a host whose method has a second overload declared after it.
 * Its selector written owner-prefixed, dotted, with whitespace, as two spellings, or as the bare name — bound to the first
 * declared overload, which the class the mod was compiled against confirms — and its target written dotted, with
 * whitespace, or without its owner or descriptor — read in that class's method, which still makes the old call — are each
 * adapted exactly as the released spelling is. A selector binding the other overload, a bare name the compiled-against
 * class binds to another overload, a target naming another owner's member, a shortened target that also names another
 * member there, and a shortened target with no compiled-against class leave the mixin exactly as written.
 */
@ResourceLock("DefinedMethodContracts")
class MixinDefaultPredicateSpellingTest {
	public static class Grid { public int reads; public int sample() { reads++; return 1; } }
	public static class Cell { }
	public static class Tint { }
	public interface BaseFlags {
		default int mask(Grid grid, Cell cell, Tint tint) { return grid.sample(); }
		default boolean test(Grid grid, Cell cell, Tint tint, int wanted) { return (shape().mask(grid, cell, tint) & wanted) != 0; }
		private Shape shape() { return (Shape) this; }
	}
	public interface ExtraFlags {
		default int mask(Grid grid, Cell cell, Tint tint, double noise) { return ((Shape) this).mask(); }
		default boolean test(Grid grid, Cell cell, Tint tint, double noise, int wanted) { return (mask(grid, cell, tint, noise) & wanted) != 0; }
	}
	public interface Shape extends BaseFlags, ExtraFlags {
		int mask();
		default boolean test(int wanted) { return (mask() & wanted) != 0; }
	}
	public interface Elsewhere { boolean test(int wanted); }
	/** The merged host: the old {@code test(int)} became the live four-argument call; a second overload follows. */
	public static class Painter {
		public static boolean paint(Shape shape, Grid grid, Cell cell, Tint tint, int wanted, double noise) { return shape.test(grid, cell, tint, wanted); }
		public static boolean paint(Shape shape, int wanted) { return shape.mask() == wanted; }
	}
	/** The host the mod was compiled against (read under {@link Painter}'s name), still making the old call. */
	public static class WrittenPainter {
		public static boolean paint(Shape shape, Grid grid, Cell cell, Tint tint, int wanted, double noise) { return shape.test(wanted); }
		public static boolean paint(Shape shape, int wanted) { return shape.mask() == wanted; }
	}
	/** The same, its overloads declared the other way round: a bare name was written for the two-argument one. */
	public static class ReorderedPainter {
		public static boolean paint(Shape shape, int wanted) { return shape.mask() == wanted; }
		public static boolean paint(Shape shape, Grid grid, Cell cell, Tint tint, int wanted, double noise) { return shape.test(wanted); }
	}
	public static class Picker {
		public static int calls;
		private static boolean pick(Shape shape, int wanted, Grid grid, Cell cell) { calls++; return shape.test(grid, cell, new Tint(), 0.25, wanted); }
	}

	private static final String PAINTER = Type.getInternalName(Painter.class), SHAPE = Type.getInternalName(Shape.class);
	private static final String PAINT = "paint(" + Type.getDescriptor(Shape.class) + Type.getDescriptor(Grid.class) + Type.getDescriptor(Cell.class)
			+ Type.getDescriptor(Tint.class) + "ID)Z";
	private static final String RELEASED_SELECTOR = PAINT, RELEASED_TARGET = "L" + SHAPE + ";test(I)Z";

	@BeforeEach @AfterEach void reset() { DefinedMethodContracts.resetForTests(); DefaultPredicateDispatch.resetForTests(); }

	@Test void theReleasedSpellingUnderOtherNamesIsAdapted() throws Exception {
		Fixture fixture = new Fixture(WrittenPainter.class);
		assertEquals(1, fixture.adapt());
		MethodNode wrapper = fixture.handler();
		assertEquals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", MixinFit.injectorOf(wrapper).desc);
		assertEquals("L" + SHAPE + ";test(" + Type.getDescriptor(Grid.class) + Type.getDescriptor(Cell.class) + Type.getDescriptor(Tint.class) + "I)Z",
				MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(wrapper)).getFirst(), "target"));
		assertEquals(0, fixture.adapt(), "idempotence");
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayAreAdaptedAsReleased() throws Exception {
		String reference = released();
		String dotted = PAINTER.replace('/', '.');
		Map<String, List<String>> forms = new LinkedHashMap<>();
		forms.put("owner-prefixed", List.of("L" + PAINTER + ";" + PAINT));
		forms.put("dotted owner", List.of(dotted + "." + PAINT));
		forms.put("whitespace", List.of("  " + PAINT.replace("(", " (") + " "));
		forms.put("two spellings", List.of(PAINT, " L" + PAINTER + "; " + PAINT));
		// A bare name binds the first declared paint in the merged host, and the compiled-against host declares the same one first.
		forms.put("bare name, first declared", List.of("paint"));
		forms.put("dotted owner, bare name", List.of(dotted + ".paint"));
		forms.put("owner-prefixed bare name", List.of("L" + PAINTER + ";paint"));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			reset();
			Fixture fixture = new Fixture(WrittenPainter.class);
			fixture.select(form.getValue());
			assertAdaptedAs(reference, fixture);
		}));
	}

	@TestFactory Stream<DynamicTest> targetsWrittenAnotherWayAreAdaptedAsReleased() throws Exception {
		String reference = released();
		String dotted = SHAPE.replace('/', '.');
		Map<String, String> forms = new LinkedHashMap<>();
		forms.put("dotted owner", dotted + ".test(I)Z");
		forms.put("whitespace", "L" + SHAPE + "; test (I)Z");
		// Shortened: the compiled-against paint makes one call it selects, Shape.test(int).
		forms.put("no owner", "test(I)Z");
		forms.put("no descriptor", "L" + SHAPE + ";test");
		forms.put("dotted owner, no descriptor", " " + dotted + " . test");
		forms.put("name alone", "test");
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			reset();
			Fixture fixture = new Fixture(WrittenPainter.class);
			fixture.target(form.getValue());
			assertAdaptedAs(reference, fixture);
		}));
	}

	/** Bare dotted selector and the name alone as the target, at once: both decided where the mod was compiled. */
	@Test void bothShortenedAtOnceAreAdaptedAsReleased() throws Exception {
		String reference = released();
		reset();
		Fixture fixture = new Fixture(WrittenPainter.class);
		fixture.select(List.of(PAINTER.replace('/', '.') + ".paint"));
		fixture.target("test");
		assertAdaptedAs(reference, fixture);
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	@Test void aSelectorBindingTheOtherOverloadIsLeftAlone() throws Exception {
		Fixture fixture = new Fixture(WrittenPainter.class);
		fixture.select(List.of(PAINTER.replace('/', '.') + ".paint(" + Type.getDescriptor(Shape.class) + "I)Z"));
		fixture.assertLeftAlone();
	}

	/** Mixin would bind the merged host's first paint, but the mod's bare name was written for the other one. */
	@Test void aBareNameTheCompiledAgainstHostBindsToAnotherOverloadIsLeftAlone() throws Exception {
		for (List<String> selector : List.of(List.of("paint"), List.of("L" + PAINTER + ";paint"))) {
			reset();
			Fixture fixture = new Fixture(ReorderedPainter.class);
			fixture.select(selector);
			fixture.assertLeftAlone();
		}
		reset();
		Fixture missing = new Fixture(null);
		missing.select(List.of("paint"));
		missing.assertLeftAlone();
	}

	@Test void aTargetNamingAnotherOwnersMemberIsLeftAlone() throws Exception {
		for (String target : List.of("L" + Type.getInternalName(Elsewhere.class) + ";test(I)Z", Type.getInternalName(Elsewhere.class).replace('/', '.') + ".test(I)Z")) {
			reset();
			Fixture fixture = new Fixture(WrittenPainter.class);
			fixture.target(target);
			fixture.assertLeftAlone();
		}
	}

	/**
	 * The compiled-against paint also calls another owner's {@code test(int)}, or another {@code test} overload: the
	 * released spelling still names one call and is adapted; a target without that owner or descriptor names two.
	 */
	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoNamesAnotherMemberThereIsLeftAlone() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			String crowd = otherOwner ? "another owner" : "another overload";
			tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still adapted", () -> {
				reset();
				Fixture fixture = new Fixture(WrittenPainter.class);
				fixture.crowd(otherOwner);
				assertEquals(1, fixture.adapt());
			}));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					reset();
					Fixture fixture = new Fixture(WrittenPainter.class);
					assertEquals(1, PointRespelling.points(fixture.mixin, PointRespelling::any, form, m -> PointRespelling.bound(m, fixture.written)), "premise");
					fixture.crowd(otherOwner);
					fixture.assertLeftAlone();
				}));
			}
		}
		return tests.stream();
	}

	@Test void aShortTargetWithoutTheCompiledAgainstHostIsLeftAlone() throws Exception {
		for (String target : List.of("test(I)Z", "L" + SHAPE + ";test", "test")) {
			reset();
			Fixture fixture = new Fixture(null);
			fixture.target(target);
			fixture.assertLeftAlone();
		}
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	/** The adapted mixin of the released spelling, its selectors and target as released. */
	private static String released() throws Exception {
		DefinedMethodContracts.resetForTests(); DefaultPredicateDispatch.resetForTests();
		Fixture fixture = new Fixture(WrittenPainter.class);
		assertEquals(1, fixture.adapt());
		return fixture.normalized();
	}

	private static void assertAdaptedAs(String reference, Fixture fixture) {
		assertEquals(1, fixture.adapt(), "adapted");
		assertEquals(reference, fixture.normalized(), "adapted exactly as the released spelling");
		assertEquals(0, fixture.adapt(), "idempotence");
	}

	private static final class Fixture {
		final Map<String, ClassNode> nodes = new HashMap<>();
		final ClassNode mixin, written;

		Fixture(Class<?> writtenHost) throws Exception {
			for (Class<?> type : List.of(Grid.class, Cell.class, Tint.class, BaseFlags.class, ExtraFlags.class, Shape.class, Elsewhere.class,
					Painter.class, Picker.class)) nodes.put(Type.getInternalName(type), parse(type));
			written = writtenHost == null ? null : renamed(parse(writtenHost), Type.getInternalName(writtenHost), PAINTER);
			mixin = nodes.get(Type.getInternalName(Picker.class));
			AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
			target.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(PAINTER))));
			mixin.visibleAnnotations = new ArrayList<>(List.of(target));
			MethodNode handler = handler();
			AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
			at.values = new ArrayList<>(List.of("value", "INVOKE", "target", RELEASED_TARGET));
			AnnotationNode redirect = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
			redirect.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(RELEASED_SELECTOR)), "at", at));
			handler.visibleAnnotations = new ArrayList<>(List.of(redirect));
			@SuppressWarnings("unchecked") List<AnnotationNode>[] parameters = new List[4];
			for (int index = 2; index < 4; index++) {
				AnnotationNode capture = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
				capture.values = new ArrayList<>(List.of("argsOnly", true));
				parameters[index] = new ArrayList<>(List.of(capture));
			}
			handler.invisibleParameterAnnotations = parameters;
		}

		MethodNode handler() { return mixin.methods.stream().filter(m -> m.name.equals("pick")).findFirst().orElseThrow(); }
		AnnotationNode injector() { return MixinFit.injectorOf(handler()); }
		void select(List<String> selectors) { set(injector(), "method", new ArrayList<>(selectors)); }
		void target(String target) { set(MixinFit.atNodes(injector()).getFirst(), "target", target); }
		int adapt() {
			ClassNode host = written;
			return MixinDefaultPredicateAdapter.adapt(mixin, nodes::get, name -> name.equals(PAINTER) ? host : null);
		}
		void crowd(boolean otherOwner) {
			// Crowds before any respelling only where the point is still released; respelled points are crowded through a copy.
			ClassNode probe = PointRespelling.copy(mixin);
			for (MethodNode method : probe.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector != null) set(MixinFit.atNodes(injector).getFirst(), "target", RELEASED_TARGET);
			}
			assertEquals(1, PointRespelling.crowd(probe, PointRespelling::any, written, otherOwner), "premise: the written paint crowded");
		}
		void assertLeftAlone() {
			byte[] before = CarpetMixinAdapterTest.bytes(mixin);
			assertEquals(0, adapt());
			assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), "the mixin was rewritten");
		}
		/** The adapted mixin with every injector's selectors put back as released: what else differs is a different adaptation. */
		String normalized() {
			ClassNode copy = PointRespelling.copy(mixin);
			for (MethodNode method : copy.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector != null) set(injector, "method", new ArrayList<>(List.of(RELEASED_SELECTOR)));
			}
			return java.util.HexFormat.of().formatHex(CarpetMixinAdapterTest.bytes(copy));
		}
	}

	private static ClassNode parse(Class<?> type) throws Exception {
		try (var in = type.getResourceAsStream("/" + Type.getInternalName(type) + ".class")) {
			ClassNode node = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(node, 0);
			return node;
		}
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
