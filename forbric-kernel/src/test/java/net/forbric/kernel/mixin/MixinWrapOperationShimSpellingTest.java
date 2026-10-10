/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The reordered-call wrap finds the bodies its injector binds as Mixin binds them and reads its point as Mixin reads a
 * target. fabric-api's pick-block wrap, renamed, with its selectors owner-qualified, dotted or doubled and its point
 * spaced or dotted, still wraps NeoForge's call; a bare name binds the first method of that name, so an overload declared
 * after it no longer hides the wrap and one declared before it is the method bound. A selector for another method or
 * owner is left alone.
 */
@ResourceLock("system-properties")
class MixinWrapOperationShimSpellingTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/event/interaction/ServerGamePacketListenerImplMixin";
	private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

	private static ClassNode pickBlock() throws Exception {
		return MixinCallbackSelectorSpellingTest.unrelatedNames(TransportSpellings.fabricMixin("fabric-events-interaction-v0", MIXIN));
	}
	private static int adapt(ClassNode mixin, ClassNode target) {
		return MixinWrapOperationShim.adapt(mixin, name -> name.equals(target.name) ? target : null);
	}
	/** The pick-block handler: the wrap whose point names the vanilla getCloneItemStack. */
	private static MethodNode pickHandler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.equals(TransportSpellings.WRAP)
				&& String.valueOf(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(), "target")).contains("getCloneItemStack"))
				.findFirst().orElseThrow(() -> new AssertionError("premise: the pick-block wrap"));
	}

	@Test void theRenamedWrapBindsNeoForgesCallAsShipped() throws Exception {
		assertEquals(1, adapt(pickBlock(), TransportSpellings.merged(LISTENER)), "premise: renamed, written as released");
	}

	/** Owner-qualified and dotted selectors bound nothing before (compared with name / name + desc as written). */
	@Test void aSelectorWrittenAnotherWayBindsTheSameBody() throws Exception {
		for (Map.Entry<String, BiFunction<String, String, List<String>>> spelling : PointRespelling.SELECTORS.entrySet()) {
			ClassNode mixin = pickBlock();
			assertTrue(PointRespelling.selectors(mixin, m -> m == pickHandler(mixin), spelling.getValue()) >= 1, "premise");
			assertEquals(1, adapt(mixin, TransportSpellings.merged(LISTENER)), spelling.getKey());
		}
	}

	/** A spaced point named no call before (the name kept its spaces); a dotted one is the same class. */
	@Test void aPointWrittenAnotherWayNamesTheSameCall() throws Exception {
		for (PointRespelling.Form form : PointRespelling.SAME_MEMBER) {
			ClassNode mixin = pickBlock();
			TransportSpellings.respell(pickHandler(mixin), form);
			assertEquals(1, adapt(mixin, TransportSpellings.merged(LISTENER)), form.id());
			AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(pickHandler(mixin))).getFirst();
			assertTrue(String.valueOf(MixinFit.value(at, "target")).startsWith("Lnet/minecraft/world/level/block/state/BlockState;getCloneItemStack("),
					"the point is rewritten to the merged call in full: " + MixinFit.value(at, "target"));
		}
	}

	@Test void aSelectorForAnotherMethodOrOwnerIsLeftAlone() throws Exception {
		ClassNode target = TransportSpellings.merged(LISTENER);
		MethodNode other = TransportSpellings.another(target, "handlePickItemFromBlock", false);
		for (String selector : List.of("L" + LISTENER + ";" + other.name + other.desc, "org.example.elsewhere.Listener.handlePickItemFromBlock")) {
			ClassNode mixin = pickBlock();
			TransportSpellings.select(pickHandler(mixin), selector);
			byte[] before = TransportSpellings.bytes(mixin);
			assertEquals(0, adapt(mixin, TransportSpellings.merged(LISTENER)), selector);
			assertArrayEquals(before, TransportSpellings.bytes(mixin), selector);
		}
	}

	// ---- a bare name and its overloads, on a class of its own -----------------------------------------------------------

	private static final String WIDGET = "org/example/gadget/Widget", LIB = "org/example/gadget/Lib";

	/**
	 * {@code Widget.render} twice: {@code render()} calling the carrier's widened {@code Lib.measure(III)}, and
	 * {@code render(I)} calling the {@code measure(II)} the handler names — in the given order.
	 */
	private static ClassNode widget(boolean widenedFirst) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC; node.name = WIDGET; node.superName = "java/lang/Object";
		MethodNode widened = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "()Ljava/lang/String;", null, null);
		for (int i = 0; i < 3; i++) widened.instructions.add(new InsnNode(Opcodes.ICONST_0 + i));
		widened.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIB, "measure", "(III)Ljava/lang/String;", false));
		widened.instructions.add(new InsnNode(Opcodes.ARETURN));
		MethodNode named = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "(I)Ljava/lang/String;", null, null);
		named.instructions.add(new InsnNode(Opcodes.ICONST_0)); named.instructions.add(new InsnNode(Opcodes.ICONST_1));
		named.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIB, "measure", "(II)Ljava/lang/String;", false));
		named.instructions.add(new InsnNode(Opcodes.ARETURN));
		node.methods.addAll(widenedFirst ? List.of(widened, named) : List.of(named, widened));
		return node;
	}

	/** One static wrap, {@code method = "render"}, on {@code Lib.measure(II)}, its point written {@code point}. */
	private static ClassNode viewer(String point) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC; node.name = "org/example/gadget/WidgetViewer"; node.superName = "java/lang/Object";
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", List.of(org.objectweb.asm.Type.getObjectType(WIDGET))));
		node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "sized", "(II" + OPERATION + ")Ljava/lang/String;", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point));
		AnnotationNode wrap = new AnnotationNode(TransportSpellings.WRAP);
		wrap.values = new ArrayList<>(List.of("method", List.of("render"), "at", List.of(at)));
		handler.visibleAnnotations = new ArrayList<>(List.of(wrap));
		handler.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); handler.instructions.add(new InsnNode(Opcodes.ARETURN));
		node.methods.add(handler);
		return node;
	}

	/** Before, both overloads were the wrap's bodies, and the one still calling measure(II) refused it. */
	@Test void aBareNameBindsTheFirstOverloadWhichNoLongerMakesTheCall() {
		for (String point : List.of("L" + LIB + ";measure(II)Ljava/lang/String;", " " + LIB.replace('/', '.') + " . measure (II)Ljava/lang/String;")) {
			ClassNode viewer = viewer(point);
			assertEquals(1, adapt(viewer, widget(true)), point);
			AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(viewer.methods.stream().filter(m -> m.name.equals("sized")).findFirst().orElseThrow())).getFirst();
			assertEquals("L" + LIB + ";measure(III)Ljava/lang/String;", MixinFit.value(at, "target"));
		}
	}

	@Test void aBareNameWhoseFirstOverloadStillMakesTheCallIsLeftAlone() {
		ClassNode viewer = viewer("L" + LIB + ";measure(II)Ljava/lang/String;");
		byte[] before = TransportSpellings.bytes(viewer);
		assertEquals(0, adapt(viewer, widget(false)), "render(I), declared first, is the method bound: the call binds as written");
		assertArrayEquals(before, TransportSpellings.bytes(viewer));
	}
}
