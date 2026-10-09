/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.VanillaEarlyReturns;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The TAIL-capture move judged against the tail the mod was compiled against. A capture moves only where that tail
 * serves it and the merged one does not: a Fabric mod's capture on vanilla's guard-and-return body follows the body; the
 * same capture written against a body whose own tail could not serve it either — a NeoForge mod on NeoForge's folded
 * body, where CAPTURE_FAILSOFT skips it natively — stays as compiled. The point the move writes counts the merged body,
 * and says so ({@link CurrentBodyOrdinals}), so no later pass reads its ordinal as a native count.
 */
class MixinTailCaptureNativeTest {
	private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
	private static final String ITEM = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V";

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

	/** Item Glint Relight's capture under unrelated names. */
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

	@Test void vanillasTailServesTheGuiItemCaptureSoItFollowsTheBody() throws Exception {
		ClassNode split = node(new VanillaEarlyReturns().transform(GUI.replace('/', '.'), merged(), null));
		ClassNode vanilla = CreateInjectionAdaptersTest.nativeTarget(GUI);
		assertNotNull(vanilla);
		ClassNode mixin = capture();
		assertEquals(1, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? split : null, (family, name) -> name.equals(GUI) ? vanilla : null));
		MethodNode handler = tail(mixin);
		assertEquals("INVOKE", MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "value"));
		assertTrue(CurrentBodyOrdinals.counted(handler), "the point now counts the merged body");
	}

	@Test void aCaptureTheNativeTailCouldNotServeEitherStaysAsCompiled() throws Exception {
		ClassNode split = node(new VanillaEarlyReturns().transform(GUI.replace('/', '.'), merged(), null));
		// Compiled against the folded body (as a mod is against its own carrier's): its tail never held the render state.
		ClassNode folded = node(merged());
		ClassNode mixin = capture();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? split : null, (family, name) -> name.equals(GUI) ? folded : null));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
		// The class the mod was compiled against does not bind the handler: nothing proves what its tail held.
		ClassNode empty = new ClassNode();
		folded.accept(empty);
		empty.methods.removeIf(m -> (m.name + m.desc).equals(ITEM));
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? split : null, (family, name) -> name.equals(GUI) ? empty : null));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	/**
	 * What each platform's own GUI item held at its tail, read from the hash-pinned original bytes the merged base ships
	 * (NativeGameReferences' source): vanilla's (what a Fabric mod is compiled against) returned early and held the
	 * render state to its last return; NeoForge's and MinecraftForge's recompiled bodies folded the guard and did not. So
	 * the same capture moves for a Fabric mod and stays, as it runs on its own platform, for a NeoForge or Forge mod.
	 */
	@Test void eachPlatformsOwnGuiItemTailDecidesTheMove() throws Exception {
		ClassNode split = node(new VanillaEarlyReturns().transform(GUI.replace('/', '.'), merged(), null));
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		NativeGameReferences references = new NativeGameReferences(path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (java.io.IOException unreadable) {
				return null;
			}
		});
		for (Ecosystem ecosystem : List.of(Ecosystem.FABRIC, Ecosystem.NEOFORGE, Ecosystem.FORGE)) {
			ClassNode own = references.get(ecosystem, GUI);
			assertNotNull(own, ecosystem.name());
			ClassNode mixin = capture();
			byte[] before = CarpetMixinAdapterTest.bytes(mixin);
			int moved = MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(GUI) ? split : null, (family, name) -> name.equals(GUI) ? own : null);
			if (ecosystem == Ecosystem.FABRIC) assertEquals(1, moved, "vanilla's tail held the render state");
			else {
				assertEquals(0, moved, ecosystem + "'s own tail never held it: the capture is left as it runs there");
				assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
			}
		}
	}

	// ---- a shelf that stacks books: the same fold on a class that shares nothing with the GUI item ------------------

	private static final String SHELF = "org/example/library/Shelf", STACK = "stack(I)V", EMIT = "emit(Ljava/lang/String;)V";

	/**
	 * {@code stack(int n)}: natively {@code if (n <= 0) return; String title = "x"; emit(title) ×3;} — the title in scope to
	 * the last return. Folded: {@code if (n > 0) { String title = "x"; emit(title) ×2; }} — the title's scope ends at the
	 * join the tail sits on, whose frame drops it.
	 */
	private static ClassNode shelf(boolean folded) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = SHELF;
		node.superName = "java/lang/Object";
		MethodNode emit = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "emit", "(Ljava/lang/String;)V", null, null);
		emit.instructions.add(new InsnNode(Opcodes.RETURN));
		emit.maxLocals = 1;
		MethodNode stack = new MethodNode(Opcodes.ACC_PUBLIC, "stack", "(I)V", null, null);
		InsnList c = stack.instructions;
		LabelNode start = new LabelNode(), titled = new LabelNode(), end = new LabelNode(), join = new LabelNode(), body = new LabelNode();
		c.add(start);
		c.add(new VarInsnNode(Opcodes.ILOAD, 1));
		if (folded) {
			c.add(new JumpInsnNode(Opcodes.IFLE, join));
		} else {
			c.add(new JumpInsnNode(Opcodes.IFGT, body));
			c.add(new InsnNode(Opcodes.RETURN));
			c.add(body);
			c.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{SHELF, Opcodes.INTEGER}, 0, new Object[0]));
		}
		c.add(new LdcInsnNode("x"));
		c.add(new VarInsnNode(Opcodes.ASTORE, 2));
		c.add(titled);
		for (int i = 0; i < (folded ? 2 : 3); i++) {
			c.add(new VarInsnNode(Opcodes.ALOAD, 2));
			c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SHELF, "emit", "(Ljava/lang/String;)V", false));
		}
		if (folded) {
			c.add(join);
			c.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{SHELF, Opcodes.INTEGER}, 0, new Object[0]));
			c.add(new InsnNode(Opcodes.RETURN));
			c.add(end);
			stack.localVariables = new ArrayList<>(List.of(new LocalVariableNode("title", "Ljava/lang/String;", null, titled, join, 2)));
		} else {
			c.add(new InsnNode(Opcodes.RETURN));
			c.add(end);
			stack.localVariables = new ArrayList<>(List.of(new LocalVariableNode("title", "Ljava/lang/String;", null, titled, end, 2)));
		}
		stack.localVariables.add(new LocalVariableNode("this", "L" + SHELF + ";", null, start, end, 0));
		stack.localVariables.add(new LocalVariableNode("n", "I", null, start, end, 1));
		stack.maxLocals = 3;
		stack.maxStack = 1;
		node.methods.add(emit);
		node.methods.add(stack);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return node(writer.toByteArray());
	}

	/** A librarian mod's {@code @Inject(method = "stack", at = @At("TAIL"), locals = CAPTURE_FAILSOFT)} capturing the title. */
	private static ClassNode librarian() {
		CallbackSourceFixture source = new CallbackSourceFixture("org/example/librarian/mixin/CatalogueMixin", SHELF);
		source.handler("catalogue", "(I" + CallbackSourceFixture.CI + "Ljava/lang/String;)V", CallbackSourceFixture.INJECT, "method", List.of("stack"),
				"at", List.of(CallbackSourceFixture.at("TAIL", null)),
				"locals", new String[]{"Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;", "CAPTURE_FAILSOFT"})
				.code(code -> code.visitInsn(Opcodes.RETURN));
		return source.build();
	}

	@Test void aMovedCaptureCountsItsCallOnTheMergedBodyAndSaysSo() throws Exception {
		ClassNode merged = shelf(true), vanilla = shelf(false);
		assertTrue(MixinGuiItemCaptureAdapter.tailServes(vanilla, method(vanilla), List.of(org.objectweb.asm.Type.getType("Ljava/lang/String;"))),
				"premise: the guard returned early natively, so the title was in scope at the last return");
		ClassNode mixin = librarian();
		assertEquals(1, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(SHELF) ? merged : null, (family, name) -> name.equals(SHELF) ? vanilla : null));
		MethodNode handler = tail(mixin);
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
		assertEquals("L" + SHELF + ";" + EMIT, MixinFit.value(at, "target"));
		assertEquals(1, MixinFit.value(at, "ordinal"), "the second of the merged body's two emits, not of vanilla's three");
		assertTrue(CurrentBodyOrdinals.counted(handler), "a pass reading ordinals as native counts must leave this one alone");
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(SHELF) ? merged : null, (family, name) -> name.equals(SHELF) ? vanilla : null), "idempotence");
	}

	@Test void theSameCaptureOnABodyThatWasAlreadyFoldedNativelyStays() throws Exception {
		ClassNode merged = shelf(true), folded = shelf(true);
		assertFalse(MixinGuiItemCaptureAdapter.tailServes(folded, method(folded), List.of(org.objectweb.asm.Type.getType("Ljava/lang/String;"))));
		ClassNode mixin = librarian();
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		BiFunction<Ecosystem, String, ClassNode> references = (family, name) -> name.equals(SHELF) ? folded : null;
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(SHELF) ? merged : null, references));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
		// Without the class the mod was compiled against, the merged tail is the only evidence: the capture moves.
		assertEquals(1, MixinGuiItemCaptureAdapter.adapt(mixin, name -> name.equals(SHELF) ? merged : null, (family, name) -> null));
	}

	private static MethodNode method(ClassNode shelf) {
		return shelf.methods.stream().filter(m -> (m.name + m.desc).equals(STACK)).findFirst().orElseThrow();
	}

	private static MethodNode tail(ClassNode mixin) {
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.value(MixinFit.injectorOf(m), "locals") != null).findFirst().orElseThrow();
	}
}
