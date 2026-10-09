/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.VanillaEarlyReturns;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * The TAIL-capture repair decided on the target alone. The GUI item as the game hands it to Mixin — after
 * VanillaEarlyReturns gave the guard its own return, the tail is reached by the body alone and still declares no render
 * state — and the GUI item as merged, both repaired the same way; a capture the tail's frame already serves, a capture of
 * a type the body does not hold first, and two captures on one tail, left as compiled.
 */
class MixinTailCaptureTest {
	private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
	private static final String ITEM = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V";
	private static final String SUBMIT = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addItem(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;)V";

	private static byte[] merged() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "merged base required: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) { return zip.getInputStream(zip.getEntry(GUI + ".class")).readAllBytes(); }
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** Item Glint Relight's mixin under unrelated names, its selector owner-qualified. */
	private static ClassNode capture() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY,
				Path.of("build/sweep80-mac/v020-rounds/r3/mods/itemglintrelight-fabric-26.2-0.3.0+26.2.jar"),
				"celia/adwadg/itemglintrelight/mixin/client/GuiGraphicsItemOutlineMixin"));
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null && MixinFit.stringList(MixinFit.value(injector, "method")).contains(ITEM))
				MixinPlayerWorldCallbackAdapter.set(injector, "method", List.of("L" + GUI + ";" + ITEM));
		}
		return mixin;
	}

	private static MethodNode tailHandler(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null
				&& "TAIL".equals(MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(), "value"))).findFirst().orElseThrow();
	}

	private static AnnotationNode point(MethodNode handler) {
		return MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
	}

	@Test void theSplitGuiItemsTailStillLacksTheStateAndTheCaptureFollowsTheBody() throws Exception {
		byte[] split = new VanillaEarlyReturns().transform(GUI.replace('/', '.'), merged(), null);
		ClassNode target = node(split);
		MethodNode item = target.methods.stream().filter(m -> (m.name + m.desc).equals(ITEM)).findFirst().orElseThrow();
		assertNotNull(VanillaEarlyReturns.splitOf(GUI, item.name, item.desc), "premise: the guard returns by itself again");
		ClassNode mixin = capture();
		MethodNode handler = tailHandler(mixin);
		assertEquals(1, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? target : null));
		AnnotationNode at = point(handler);
		assertEquals("INVOKE", MixinFit.value(at, "value"));
		assertEquals(SUBMIT, MixinFit.value(at, "target"));
		assertEquals("AFTER", ((String[]) MixinFit.value(at, "shift"))[1]);
		assertNull(MixinFit.value(at, "ordinal"), "the one submission");
		assertNotNull(MixinFit.value(MixinFit.injectorOf(handler), "locals"), "still a locals capture, now where the locals live");
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? target : null), "idempotent");
	}

	@Test void aTailWhoseFrameServesTheCaptureIsLeftAtTheTail() throws Exception {
		ClassNode target = node(merged());
		MethodNode item = target.methods.stream().filter(m -> (m.name + m.desc).equals(ITEM)).findFirst().orElseThrow();
		// The same body with the render state declared before the guard: the tail's frame declares it on every path.
		FrameNode tail = null;
		for (AbstractInsnNode insn = VanillaEarlyReturns.lastReturn(item).getPrevious(); insn != null && insn.getOpcode() < 0; insn = insn.getPrevious())
			if (insn instanceof FrameNode frame) tail = frame;
		assertNotNull(tail);
		List<List<Object>> state = VanillaEarlyReturns.stateAt(GUI, item, tail);
		tail.type = org.objectweb.asm.Opcodes.F_FULL;
		tail.local = new java.util.ArrayList<>(state.get(0));
		tail.local.add("net/minecraft/client/renderer/item/TrackingItemStackRenderState");
		tail.stack = new java.util.ArrayList<>(state.get(1));
		ClassNode mixin = capture();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? target : null));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	@Test void aCaptureOfAnotherTypeOrTwoOnOneTailAreLeftAlone() throws Exception {
		ClassNode target = node(merged());
		Function<String, ClassNode> targets = name -> name.equals(GUI) ? target : null;
		ClassNode other = capture();
		MethodNode handler = tailHandler(other);
		handler.desc = handler.desc.replace("Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;)V", "Ljava/lang/String;)V");
		byte[] before = CarpetMixinAdapterTest.bytes(other);
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(other, targets), "the body holds no String first after the arguments");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(other));

		ClassNode twice = capture();
		MethodNode original = tailHandler(twice);
		MethodNode copy = new MethodNode(original.access, original.name + "Again", original.desc, original.signature, null);
		original.accept(copy);
		twice.methods.add(copy);
		before = CarpetMixinAdapterTest.bytes(twice);
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(twice, targets), "one point per tail: two captures there stay as compiled");
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(twice));
		assertEquals(Type.VOID_TYPE, Type.getReturnType(original.desc));
	}
}
