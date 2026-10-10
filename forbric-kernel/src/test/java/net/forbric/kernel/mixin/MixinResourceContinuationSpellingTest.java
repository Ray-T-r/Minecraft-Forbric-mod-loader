/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.KernelResourceContinuations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-api's sign-texture namespace wrap, renamed to an unrelated mixin and written the other ways Mixin reads the
 * same thing, still follows the identifier into the merged game's continuation. Its host is the one constructor its
 * selector binds in the merged class, the same one it binds in the class the mod was compiled against — owner-qualified,
 * dotted or doubled — and its point the one call it selects there: spaced, dotted, or without the owner or descriptor
 * that constructor decides. Before, the selector array had to be one string, compared with {@code name}/{@code name +
 * desc}, and the point compared with the default factory's full spelling: none of these moved. A bare {@code <init>}
 * whose first constructor is another one — in both classes, or only in the class the mod was compiled against — a
 * selector binding another method or naming another owner, and a short point that also selects another call there are
 * left byte for byte as they were.
 */
@ResourceLock("DefinedMethodContracts")
class MixinResourceContinuationSpellingTest {
	private static final String SCREEN = "net/minecraft/client/gui/screens/inventory/SignEditScreen";
	private static final String WRAP = TransportSpellings.WRAP;

	@BeforeEach @AfterEach void reset() {
		DefinedMethodContracts.resetForTests();
		KernelResourceContinuations.resetForTests();
	}

	/** The renamed mixin, the merged classes it reads, and the class the mod was compiled against. */
	private record Inputs(ClassNode mixin, Map<String, ClassNode> classes, ClassNode reference) {
		int adapt() { return MixinResourceContinuationAdapter.adapt(mixin, classes::get, name -> name.equals(reference.name) ? reference : null); }
		MethodNode handler() { return TransportSpellings.handler(mixin, WRAP); }
		ClassNode merged() { return classes.get(SCREEN); }
		String point() { return String.valueOf(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler())).getFirst(), "target")); }
	}

	private static Inputs inputs() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(
				TransportSpellings.fabricMixin("fabric-object-builder-api-v1", "net/fabricmc/fabric/mixin/object/builder/client/SignEditScreenMixin"));
		Map<String, ClassNode> classes = new HashMap<>();
		for (String name : List.of(SCREEN, "net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen",
				"net/minecraft/world/level/block/state/properties/WoodType", "net/minecraft/resources/Identifier", "net/minecraft/IdentifierException"))
			classes.put(name, TransportSpellings.merged(name));
		return new Inputs(mixin, classes, TransportSpellings.nativeClass(Ecosystem.FABRIC, SCREEN));
	}

	private static void assertMoved(Inputs input, String before, String why) {
		assertEquals(1, input.adapt(), why);
		assertNotEquals(before, input.point(), why + ": the point follows the continuation");
		assertTrue(input.point().startsWith("Lnet/minecraft/resources/Identifier;"), why + ": " + input.point());
	}

	@Test void theRenamedWrapFollowsTheContinuationAsShipped() throws Exception {
		Inputs input = inputs();
		assertMoved(input, input.point(), "premise: renamed, written as released");
	}

	@Test void aSelectorWrittenAnotherWayBindsTheSameConstructor() throws Exception {
		for (Map.Entry<String, BiFunction<String, String, List<String>>> spelling : PointRespelling.SELECTORS.entrySet()) {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.selectors(input.mixin(), TransportSpellings.injector(WRAP), spelling.getValue()), "premise");
			assertMoved(input, input.point(), spelling.getKey());
		}
	}

	@Test void aPointWrittenAnotherWayNamesTheSameCall() throws Exception {
		for (PointRespelling.Form form : PointRespelling.allForms()) {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.points(input.mixin(), TransportSpellings.injector(WRAP), form, m -> PointRespelling.bound(m, input.reference())),
					"premise: " + form.id() + " applies");
			assertMoved(input, input.point(), form.id());
		}
	}

	/** Another constructor declared after the one it was written for, in both classes: the bare {@code <init>} still binds it. */
	@Test void aBareConstructorNameBindsTheFirstConstructor() throws Exception {
		Inputs input = inputs();
		TransportSpellings.overload(input.merged(), "<init>", "(I)V", Opcodes.ACC_PUBLIC, false);
		TransportSpellings.overload(input.reference(), "<init>", "(I)V", Opcodes.ACC_PUBLIC, false);
		assertMoved(input, input.point(), "a constructor declared after");
	}

	// ---- look-alikes: left alone ---------------------------------------------------------------------------------------

	/** Before, every constructor the bare name matched was a host, so the one Mixin does not bind still moved the wrap. */
	@Test void aBareConstructorNameWhoseFirstConstructorIsAnotherIsLeftAlone() throws Exception {
		for (boolean inMerged : List.of(true, false)) {
			Inputs input = inputs();
			if (inMerged) TransportSpellings.overload(input.merged(), "<init>", "(I)V", Opcodes.ACC_PUBLIC, true);
			TransportSpellings.overload(input.reference(), "<init>", "(I)V", Opcodes.ACC_PUBLIC, true);
			assertUnchanged(input, inMerged ? "<init> binds the constructor declared first, in both classes"
					: "the class the mod was compiled against binds another constructor: not written for the merged one");
		}
	}

	@Test void aSelectorForAnotherMethodOrOwnerIsLeftAlone() throws Exception {
		Inputs input = inputs();
		MethodNode other = TransportSpellings.another(input.merged(), "<init>", false);
		TransportSpellings.select(input.handler(), "L" + SCREEN + ";" + other.name + other.desc);
		assertUnchanged(input, "a selector binding " + other.name);
		input = inputs();
		TransportSpellings.select(input.handler(), "Lorg/example/elsewhere/Screen;<init>");
		assertUnchanged(input, "a selector naming another owner");
	}

	/** Where the constructor also calls {@code withDefaultNamespace} on another owner (or of another descriptor), the short point names two calls. */
	@Test void aShortPointThatAlsoSelectsAnotherCallIsLeftAlone() throws Exception {
		for (boolean otherOwner : List.of(true, false)) {
			Inputs input = inputs();
			PointRespelling.Form form = PointRespelling.READ_IN_THE_WRITTEN_METHOD.get(otherOwner ? 0 : 1);
			assertEquals(1, PointRespelling.crowd(PointRespelling.copy(input.mixin()), TransportSpellings.injector(WRAP), input.reference(), otherOwner), "premise");
			TransportSpellings.respell(input.handler(), form);
			assertUnchanged(input, "the point " + form.id() + " beside a withDefaultNamespace " + (otherOwner ? "on another owner" : "of another descriptor"));
		}
	}

	private static void assertUnchanged(Inputs input, String why) {
		byte[] before = TransportSpellings.bytes(input.mixin());
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(input.handler())).getFirst();
		Object point = MixinFit.value(at, "target");
		assertEquals(0, input.adapt(), why);
		assertEquals(point, MixinFit.value(at, "target"), why);
		assertArrayEquals(before, TransportSpellings.bytes(input.mixin()), why + " — the mixin is not touched");
	}
}
