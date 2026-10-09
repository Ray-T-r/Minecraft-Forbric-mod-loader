/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Condition wrappers on the camera's two-float rotation calls, on the staged merged Camera against vanilla's, written
 * other ways than Do a Barrel Roll's three ordinal handlers: one handler without an ordinal, which wraps all four calls
 * natively, keeps the two the merged body still makes and gains a widened copy for the two it now makes with a roll; and
 * a handler this cannot prove (an occurrence vanilla's method does not have) beside the mod's own, which no longer costs
 * the others their move. A wrapper on a method that makes no such call is left alone.
 */
@ResourceLock("system-properties")
class CameraRotationWrapShapesTest {
	private static final String CAMERA = MixinCameraRollAdapter.CAMERA;
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;";
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVE = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);

	private static ClassNode camera() {
		return CarpetMixinAdapterTest.target(CAMERA);
	}

	private static Function<String, ClassNode> targets(ClassNode camera) {
		return name -> name.equals(CAMERA) ? camera : CarpetMixinAdapterTest.target(name);
	}

	private static ClassNode barrelRoll() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY,
				Path.of("build/compat-inputs/c2me-barrel-20261001/do_a_barrel_roll-fabric-3.8.4+26.2.jar"),
				"nl/enjarai/doabarrelroll/mixin/client/roll/CameraMixin"));
	}

	private static List<MethodNode> wraps(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.endsWith("/WrapWithCondition;")).toList();
	}

	/** A wrapper of {@code setRotation(FF)} in the method {@code selector} binds, at {@code ordinal} (none: null). */
	private static MethodNode wrap(String name, String selector, Integer ordinal) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, "(L" + CAMERA + ";FF)Z", null, null);
		AnnotationNode injector = new AnnotationNode(WRAP);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", MixinCameraRollAdapter.SHORT));
		if (ordinal != null) at.values.addAll(List.of("ordinal", ordinal));
		injector.values = new ArrayList<>(List.of("method", List.of(selector), "at", List.of(at)));
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		handler.instructions.add(new InsnNode(Opcodes.ICONST_1));
		handler.instructions.add(new InsnNode(Opcodes.IRETURN));
		handler.maxStack = 1;
		handler.maxLocals = 4;
		return handler;
	}

	private static AnnotationNode point(MethodNode handler) {
		return MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
	}

	@Test void oneWrapperWithoutAnOrdinalCoversEveryCallItWrappedNatively() throws Exception {
		ClassNode mixin = barrelRoll(), camera = camera();
		mixin.methods.removeAll(wraps(mixin));
		MethodNode steady = wrap("steady$everyView", "Lnet/minecraft/client/Camera;alignWithEntity(F)V", null);
		mixin.methods.add(steady);
		assertEquals(2, MixinCameraRollAdapter.adapt(mixin, targets(camera), NATIVE), "the widened copy, and the roll modifier");
		List<MethodNode> wraps = wraps(mixin);
		assertEquals(2, wraps.size());
		MethodNode copy = wraps.stream().filter(m -> m != steady).findFirst().orElseThrow();
		assertEquals("(L" + CAMERA + ";FF)Z", steady.desc, "the mod's handler keeps the two-float calls the merged body still makes");
		assertEquals(MixinCameraRollAdapter.SHORT, MixinFit.value(point(steady), "target"));
		assertNull(MixinFit.value(point(steady), "ordinal"));
		assertEquals("(L" + CAMERA + ";FFF)Z", copy.desc, "its copy takes the roll of the calls now made with one");
		assertEquals(MixinCameraRollAdapter.LONG, MixinFit.value(point(copy), "target"));
		assertNull(MixinFit.value(point(copy), "ordinal"), "both of them: every three-float call stands for a native one");
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, MixinCameraRollAdapter.adapt(mixin, targets(camera), NATIVE), "idempotence");
		assertEquals(2, wraps(mixin).size());
	}

	@Test void aHandlerThatCannotBeProvedNoLongerCostsTheOthersTheirMove() throws Exception {
		ClassNode mixin = barrelRoll(), camera = camera();
		MethodNode stray = wrap("stray$ninth", "alignWithEntity", 9);
		mixin.methods.add(stray);
		byte[] before = CarpetMixinAdapterTest.bytes(stray(stray));
		assertEquals(4, MixinCameraRollAdapter.adapt(mixin, targets(camera), NATIVE), "the mod's three wrappers and its roll modifier");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(stray(stray)), "the unprovable one as compiled");
		assertTrue(wraps(mixin).stream().filter(m -> m != stray).allMatch(CurrentBodyOrdinals::counted));
		CarpetMixinAdapterTest.verify(mixin);
	}

	@Test void aWrapperOfAMethodWithoutSuchCallsIsLeftAlone() throws Exception {
		ClassNode mixin = barrelRoll();
		mixin.methods.removeIf(m -> MixinFit.injectorOf(m) != null);
		mixin.methods.add(wrap("elsewhere$tick", "tick", null));
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, MixinCameraRollAdapter.adapt(mixin, targets(camera()), NATIVE));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	/** A one-method class holding a copy of {@code handler}, to compare its bytes. */
	private static ClassNode stray(MethodNode handler) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = "Stray";
		node.superName = "java/lang/Object";
		MethodNode copy = new MethodNode(handler.access, handler.name, handler.desc, handler.signature, null);
		handler.accept(copy);
		node.methods.add(copy);
		return node;
	}
}
