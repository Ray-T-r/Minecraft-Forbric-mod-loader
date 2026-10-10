/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.boot.KernelSharedPredicateContracts;

/**
 * {@link MixinSharedPredicateSeam} reads the source pair's host and anchors as Mixin reads them: fabric-api's released
 * {@code EntityMixin} pair under other names, with its two selectors written in two different ways (the pair is one host
 * however each is spelled), in an owner-prefixed, dotted or two-spelling array form, and with each anchor's target given
 * with whitespace, a dotted owner, no owner, no descriptor or both of the last — read, where shortened, in the vanilla
 * {@code updateSwimming()} the pair was written for. Each restores the pair exactly as the released spelling does. An
 * anchor that also names another member there (another owner's {@code isUnderWater()Z}, another {@code is} overload) or
 * another owner's member, and a ground selector binding another method, leave the mixin exactly as written.
 */
@ResourceLock("system-properties")
class MixinSharedPredicateSeamSpellingTest {
	private static final String HOST = "net/minecraft/world/entity/Entity";
	private static final String UNDER = "Lnet/minecraft/world/entity/Entity;isUnderWater()Z";
	private static final String GROUND = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z";

	/** The renamed source mixin and its two handlers, found once by the members their points name as released. */
	private record Pair(MixinSharedPredicateSeamTest.Inputs input, ClassNode mixin, MethodNode under, MethodNode ground) {
		boolean either(MethodNode method) { return method == under || method == ground; }
	}

	@AfterEach void reset() {
		SharedFinalSourceCertifier.resetForTests();
		KernelSharedPredicateContracts.resetForTests();
	}

	/** The released pair under other names: the baseline every form is measured against. */
	@Test void thePairUnderOtherNamesIsRestored() throws Exception {
		Pair pair = pair();
		assertRestored(pair, pair.input().source());
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayRestoreThePair() {
		Map<String, Consumer<Pair>> forms = new LinkedHashMap<>();
		// The pair spelled differently from each other: one host for Mixin, never two lists for a string comparison.
		forms.put("under with its descriptor, ground owner-prefixed bare", pair -> {
			select(pair.under(), List.of("updateSwimming()V"));
			select(pair.ground(), List.of("L" + HOST + ";updateSwimming"));
		});
		forms.put("both dotted, the ground in two spellings", pair -> {
			select(pair.under(), List.of("net.minecraft.world.entity.Entity.updateSwimming()V"));
			select(pair.ground(), List.of("updateSwimming", " L" + HOST + "; updateSwimming ()V"));
		});
		for (var form : PointRespelling.SELECTORS.entrySet())
			forms.put(form.getKey(), pair -> assertEquals(2, PointRespelling.selectors(pair.mixin(), pair::either, form.getValue()), "premise"));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Pair pair = pair();
			form.getValue().accept(pair);
			assertRestored(pair, pair.input().source());
		}));
	}

	@TestFactory Stream<DynamicTest> anchorsWrittenAnotherWayRestoreThePair() {
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			Pair pair = pair();
			ClassNode natives = pair.input().source().apply(HOST);
			assertEquals(2, PointRespelling.points(pair.mixin(), pair::either, form, m -> PointRespelling.bound(m, natives)), "premise: both anchors are respelled");
			assertRestored(pair, pair.input().source());
		}));
	}

	/** Every point shortened at once (no owner on one, a dotted owner and no descriptor on the other) and the selectors respelled. */
	@Test void everythingWrittenAnotherWayAtOnceRestoresThePair() throws Exception {
		Pair pair = pair();
		point(pair.under(), "isUnderWater()Z");
		point(pair.ground(), " net.minecraft.world.level.material.FluidState . is");
		select(pair.under(), List.of("net.minecraft.world.entity.Entity.updateSwimming"));
		select(pair.ground(), List.of("updateSwimming()V"));
		assertRestored(pair, pair.input().source());
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	/**
	 * The vanilla host crowded with another owner's {@code isUnderWater()Z} and {@code is(TagKey)Z}, or with another
	 * overload of each: the released spelling still names one member each and is restored, a shortened one names two.
	 */
	@TestFactory Stream<DynamicTest> aShortAnchorThatAlsoNamesAnotherMemberThereLeavesThePair() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) {
			String crowd = otherOwner ? "another owner" : "another overload";
			tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still restored", () -> {
				Pair pair = pair();
				assertRestored(pair, crowded(pair, otherOwner));
			}));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					Pair pair = pair();
					ClassNode natives = pair.input().source().apply(HOST);
					Function<String, ClassNode> crowded = crowded(pair, otherOwner);
					assertEquals(2, PointRespelling.points(pair.mixin(), pair::either, form, m -> PointRespelling.bound(m, natives)), "premise");
					assertLeftAlone(pair, crowded);
				}));
			}
		}
		return tests.stream();
	}

	@Test void anAnchorNamingAnotherOwnersMemberLeavesThePair() throws Exception {
		for (String target : List.of("Lnet/minecraft/world/entity/LivingEntity;isUnderWater()Z", "org.example.elsewhere.Gauge.isUnderWater()Z")) {
			Pair pair = pair();
			point(pair.under(), target);
			assertLeftAlone(pair, pair.input().source());
		}
		Pair pair = pair();
		point(pair.ground(), "Lorg/example/elsewhere/Gauge;is(Lnet/minecraft/tags/TagKey;)Z");
		assertLeftAlone(pair, pair.input().source());
	}

	/** The ground's selector, dotted, binding another method of the host: not the under's pair. */
	@Test void aGroundSelectorBindingAnotherMethodLeavesThePair() throws Exception {
		Pair pair = pair();
		ClassNode natives = pair.input().source().apply(HOST);
		MethodNode other = natives.methods.stream().filter(m -> !m.name.startsWith("<") && !m.name.equals("updateSwimming")
				&& m.desc.equals("()V") && (m.access & Opcodes.ACC_STATIC) == 0).findFirst().orElseThrow();
		select(pair.ground(), List.of("net.minecraft.world.entity.Entity." + other.name + other.desc));
		assertLeftAlone(pair, pair.input().source());
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertRestored(Pair pair, Function<String, ClassNode> source) {
		var input = pair.input();
		String underBody = MixinInstructionFingerprint.hash(pair.under()), groundBody = MixinInstructionFingerprint.hash(pair.ground());
		assertEquals(2, MixinSharedPredicateSeam.adapt(pair.mixin(), input.classes(), source, input.carrier()));
		for (MethodNode handler : List.of(pair.under(), pair.ground())) {
			List<String> selectors = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"));
			assertEquals(1, selectors.size());
			assertTrue(selectors.getFirst().contains("forbricsharedsource"), selectors.toString());
		}
		assertEquals(underBody, MixinInstructionFingerprint.hash(pair.under()));
		assertEquals(groundBody, MixinInstructionFingerprint.hash(pair.ground()));
		assertEquals(0, MixinSharedPredicateSeam.adapt(pair.mixin(), input.classes(), source, input.carrier()), "idempotence");
	}

	private static void assertLeftAlone(Pair pair, Function<String, ClassNode> source) {
		byte[] before = CarpetMixinAdapterTest.bytes(pair.mixin());
		assertEquals(0, MixinSharedPredicateSeam.adapt(pair.mixin(), pair.input().classes(), source, pair.input().carrier()));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(pair.mixin()), "the mixin was rewritten");
	}

	/** The native classes with vanilla's {@code updateSwimming()} crowded by {@link PointRespelling#crowd}. */
	private static Function<String, ClassNode> crowded(Pair pair, boolean otherOwner) {
		Function<String, ClassNode> source = pair.input().source();
		ClassNode crowd = PointRespelling.copy(source.apply(HOST));
		assertEquals(2, PointRespelling.crowd(pair.mixin(), pair::either, crowd, otherOwner), "premise: both anchors crowded");
		return name -> name.equals(HOST) ? crowd : source.apply(name);
	}

	/** fabric-api's pair under other class and handler names. */
	private static Pair pair() throws Exception {
		var input = MixinSharedPredicateSeamTest.inputs();
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(input.guest());
		MethodNode under = anchored(mixin, UNDER, "/ModifyExpressionValue;"), ground = anchored(mixin, GROUND, "/WrapOperation;");
		assertNotNull(under, "the under handler");
		assertNotNull(ground, "the ground handler");
		assertNotEquals(-1, under.name.indexOf("respelled"), "renamed");
		return new Pair(input, mixin, under, ground);
	}

	/** The one handler of that injector kind whose point names {@code member} as released. */
	private static MethodNode anchored(ClassNode mixin, String member, String kind) {
		MethodNode found = null;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || !injector.desc.endsWith(kind)) continue;
			for (AnnotationNode at : MixinFit.atNodes(injector))
				if (member.equals(MixinFit.asString(MixinFit.value(at, "target")))) {
					assertNull(found, "one handler names " + member);
					found = method;
				}
		}
		return found;
	}

	private static void select(MethodNode handler, List<String> selectors) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(selectors));
	}

	private static void point(MethodNode handler, String target) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target", target);
	}
}
