/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
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

/**
 * {@link MixinCrossHostPredicateIsland} reads the modifier's method and its atom as Mixin reads them: fabric-api's
 * released drowning modifier under other names, its selector written owner-prefixed, dotted, as two spellings, with its
 * descriptor and whitespace, or as a bare name that a compiled-against class with a later-declared overload still binds
 * to the same method, and its target written with whitespace, a dotted owner, no owner or no descriptor — read in the
 * vanilla {@code baseTick()} it was written for. Each moves the same island, with the same plan, as the released spelling.
 * A bare name the compiled-against class binds to another overload, a selector binding another method, a target naming
 * another owner's member, and a shortened target that also names another member there leave the mixin exactly as
 * written and register nothing.
 */
@ResourceLock("system-properties")
class MixinCrossHostPredicateIslandSpellingTest {
	private static final String HOST = "net/minecraft/world/entity/LivingEntity";
	private static final String RELEASED_TARGET = "Lnet/minecraft/world/entity/LivingEntity;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z";

	@AfterEach void reset() { MixinCrossHostPredicateIsland.resetForTests(); }

	@Test void theReleasedModifierUnderOtherNamesIsMoved() throws Exception {
		Outcome released = released();
		assertTrue(released.selector().contains("forbricsourceisland"), released.selector());
		assertNotNull(released.key());
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayMoveTheSameIsland() throws Exception {
		Outcome reference = released();
		Map<String, BiConsumer<ClassNode, MethodNode>> forms = new LinkedHashMap<>();
		for (var form : PointRespelling.SELECTORS.entrySet())
			forms.put(form.getKey(), (mixin, handler) -> assertEquals(1, PointRespelling.selectors(mixin, m -> m == handler, form.getValue()), "premise"));
		forms.put("descriptor and whitespace", (mixin, handler) -> select(handler, List.of(" baseTick ()V ")));
		forms.put("dotted owner with descriptor", (mixin, handler) -> select(handler, List.of(HOST.replace('/', '.') + ".baseTick()V")));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
				ClassNode mixin = renamed(in);
				form.getValue().accept(mixin, drowning(mixin));
				assertMovedAs(reference, in, natives(in), mixin);
			}
		}));
	}

	/** A bare name, where the compiled-against class also declares {@code baseTick(I)V} AFTER vanilla's: still vanilla's. */
	@Test void aBareNameTheCompiledAgainstClassBindsToTheFirstDeclaredMovesTheSameIsland() throws Exception {
		Outcome reference = released();
		try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
			ClassNode mixin = renamed(in);
			select(drowning(mixin), List.of("baseTick"));
			assertMovedAs(reference, in, overloaded(in, false), mixin);
		}
	}

	@TestFactory Stream<DynamicTest> targetsWrittenAnotherWayMoveTheSameIsland() throws Exception {
		Outcome reference = released();
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
				ClassNode mixin = renamed(in), natives = natives(in).apply(HOST);
				MethodNode handler = drowning(mixin);
				assertEquals(1, PointRespelling.points(mixin, m -> m == handler, form, m -> PointRespelling.bound(m, natives)), "premise");
				assertMovedAs(reference, in, natives(in), mixin);
			}
		}));
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	/** {@code baseTick(I)V} declared BEFORE vanilla's in the compiled-against class: the bare name was written for it. */
	@Test void aBareNameTheCompiledAgainstClassBindsToAnotherOverloadIsLeftAlone() throws Exception {
		try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
			ClassNode mixin = renamed(in);
			select(drowning(mixin), List.of("baseTick"));
			assertLeftAlone(in, overloaded(in, true), mixin);
		}
	}

	@Test void aSelectorBindingAnotherMethodIsLeftAlone() throws Exception {
		for (String selector : List.of(HOST.replace('/', '.') + ".tick()V", "L" + HOST + ";aiStep")) {
			try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
				ClassNode mixin = renamed(in);
				select(drowning(mixin), List.of(selector));
				assertLeftAlone(in, natives(in), mixin);
			}
		}
	}

	@Test void aTargetNamingAnotherOwnersMemberIsLeftAlone() throws Exception {
		for (String target : List.of("Lnet/minecraft/world/entity/player/Player;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z",
				"org.example.elsewhere.Gauge.isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z")) {
			try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
				ClassNode mixin = renamed(in);
				point(drowning(mixin), target);
				assertLeftAlone(in, natives(in), mixin);
			}
		}
	}

	/**
	 * Vanilla's {@code baseTick()} crowded with another owner's {@code isEyeInFluid(TagKey)Z}, or with another
	 * {@code isEyeInFluid} overload: the released spelling still names one call and moves, a shortened one names two.
	 */
	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoNamesAnotherMemberThereIsLeftAlone() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			String crowd = otherOwner ? "another owner" : "another overload";
			tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still moved", () -> {
				try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
					assertEquals(1, adapt(in, crowded(in, otherOwner), renamed(in)));
				}
			}));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
						ClassNode mixin = renamed(in), natives = natives(in).apply(HOST);
						MethodNode handler = drowning(mixin);
						assertEquals(1, PointRespelling.points(mixin, m -> m == handler, form, m -> PointRespelling.bound(m, natives)), "premise");
						assertLeftAlone(in, crowded(in, otherOwner), mixin);
					}
				}));
			}
		}
		return tests.stream();
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	/** What a move produced: the moved handler's selector, the plan's key, and the mixin with that handler's target as released. */
	private record Outcome(String selector, String key, String mixin) { }

	private static Outcome released() throws Exception {
		try (var in = new MixinCrossHostPredicateIslandTest.Inputs()) {
			ClassNode mixin = renamed(in);
			assertEquals(1, adapt(in, natives(in), mixin));
			return outcome(mixin);
		} finally {
			MixinCrossHostPredicateIsland.resetForTests();
		}
	}

	private static void assertMovedAs(Outcome reference, MixinCrossHostPredicateIslandTest.Inputs in, Function<String, ClassNode> natives, ClassNode mixin) {
		MixinCrossHostPredicateIsland.resetForTests();
		assertEquals(1, adapt(in, natives, mixin), "moved");
		assertEquals(reference, outcome(mixin), "moved exactly as the released spelling");
	}

	private static void assertLeftAlone(MixinCrossHostPredicateIslandTest.Inputs in, Function<String, ClassNode> natives, ClassNode mixin) {
		MixinCrossHostPredicateIsland.resetForTests();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, adapt(in, natives, mixin));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), "the mixin was rewritten");
		assertTrue(MixinCrossHostPredicateIsland.plans().isEmpty(), "a plan was registered");
	}

	private static Outcome outcome(ClassNode mixin) {
		MethodNode handler = drowning(mixin);
		String selector = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")).getFirst();
		var plans = MixinCrossHostPredicateIsland.plans();
		assertEquals(1, plans.size(), "one plan");
		ClassNode copy = PointRespelling.copy(mixin);
		point(drowning(copy), RELEASED_TARGET);
		return new Outcome(selector, plans.iterator().next().key(), java.util.HexFormat.of().formatHex(CarpetMixinAdapterTest.bytes(copy)));
	}

	private static int adapt(MixinCrossHostPredicateIslandTest.Inputs in, Function<String, ClassNode> natives, ClassNode mixin) {
		return MixinCrossHostPredicateIsland.adapt(mixin, in::current, natives, (name, run) -> { run.run(); return true; });
	}

	/** Vanilla as fabric-api was compiled against it. */
	private static Function<String, ClassNode> natives(MixinCrossHostPredicateIslandTest.Inputs in) {
		return name -> in.original.get(Ecosystem.FABRIC, name);
	}

	/** The native classes with {@code baseTick(I)V} declared before ({@code first}) or after vanilla's {@code baseTick()V}. */
	private static Function<String, ClassNode> overloaded(MixinCrossHostPredicateIslandTest.Inputs in, boolean first) {
		ClassNode host = natives(in).apply(HOST);
		MethodNode decoy = new MethodNode(Opcodes.ACC_PUBLIC, "baseTick", "(I)V", null, null);
		decoy.instructions.add(new InsnNode(Opcodes.RETURN));
		decoy.maxLocals = 2;
		if (first) host.methods.addFirst(decoy); else host.methods.add(decoy);
		return name -> name.equals(HOST) ? host : natives(in).apply(name);
	}

	/** The native classes with vanilla's {@code baseTick()} crowded by {@link PointRespelling#crowd}. */
	private static Function<String, ClassNode> crowded(MixinCrossHostPredicateIslandTest.Inputs in, boolean otherOwner) throws Exception {
		ClassNode host = natives(in).apply(HOST), probe = renamed(in);
		MethodNode handler = drowning(probe);
		assertEquals(1, PointRespelling.crowd(probe, m -> m == handler, host, otherOwner), "premise: baseTick crowded");
		return name -> name.equals(HOST) ? host : natives(in).apply(name);
	}

	/** fabric-api's fluid mixin under other class and handler names, read afresh. */
	private static ClassNode renamed(MixinCrossHostPredicateIslandTest.Inputs in) {
		ClassNode source = new ClassNode();
		new ClassReader(CarpetMixinAdapterTest.bytes(in.source)).accept(source, 0);
		ClassNode renamed = MixinCallbackSelectorSpellingTest.unrelatedNames(source);
		drowningName = drowningName(renamed);
		assertTrue(drowningName.startsWith("respelled"), "renamed");
		return renamed;
	}

	/** The drowning modifier, by the name {@link #renamed} gave it, which no respelling of its selector or target changes. */
	private static MethodNode drowning(ClassNode mixin) {
		assertNotNull(drowningName, "read through renamed first");
		return mixin.methods.stream().filter(m -> m.name.equals(drowningName)).findFirst().orElseThrow();
	}

	private static String drowningName;

	/**
	 * The drowning modifier of a pristine renamed copy: the one boolean {@code @ModifyExpressionValue} whose target, as
	 * released, is {@code isEyeInFluid}. Found by what it is, never by its name.
	 */
	private static String drowningName(ClassNode renamed) {
		String found = null;
		for (MethodNode method : renamed.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || !injector.desc.endsWith("/ModifyExpressionValue;") || !method.desc.equals("(Z)Z")
					|| MixinFit.atNodes(injector).stream().noneMatch(at -> RELEASED_TARGET.equals(MixinFit.value(at, "target")))) continue;
			assertNull(found, "one drowning modifier");
			found = method.name;
		}
		assertNotNull(found, "the drowning modifier");
		return found;
	}

	private static void select(MethodNode handler, List<String> selectors) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(selectors));
	}

	private static void point(MethodNode handler, String target) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target", target);
	}
}
