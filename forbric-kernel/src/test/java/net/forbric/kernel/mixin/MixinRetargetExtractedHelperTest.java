package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.api.Ecosystem;

/**
 * R5 over a synthetic {@code AbstractContainerScreen} shaped like the merged one: {@code extractSlot} keeps its body and
 * hands the item to {@code renderSlotContents} once, whose last act is the {@code itemDecorations} call Highlighter
 * injects after. The class carries the real names because only a row of the shipped {@code carrier-helpers.txt}
 * authorizes the move.
 */
class MixinRetargetExtractedHelperTest {
	private static final String SCREEN = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
	private static final String G = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
	private static final String SLOT = "Lnet/minecraft/world/inventory/Slot;";
	private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
	private static final String EXTRACT_SLOT = "(" + G + SLOT + "II)V";
	private static final String CONTENTS = "renderSlotContents";
	private static final String CONTENTS_DESC = "(" + G + STACK + SLOT + "Ljava/lang/String;)V";
	private static final String DECORATIONS_DESC = "(Lnet/minecraft/client/gui/Font;" + STACK + "IILjava/lang/String;)V";
	private static final String DECORATIONS = G + "itemDecorations" + DECORATIONS_DESC;
	private static final String VIA = "L" + SCREEN + ";" + CONTENTS + CONTENTS_DESC;
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String MIXIN = "test/ScreenMixin";
	private static final String OTHER = "test/OtherScreen";
	/** Highlighter's handler: extractSlot's own arguments and the callback. */
	private static final String HANDLER = "(" + G + SLOT + "II" + MixinRetarget.CALLBACK_INFO + ")V";

	@AfterEach
	void reset() {
		System.clearProperty(MixinRetarget.PROPERTY);
		System.clearProperty(MixinRetarget.EXTRACTED_HELPER_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	@Test
	void anInjectAfterTheMovedCallFollowsItToTheHelperCall() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		Function<String, byte[]> resolver = resolver(screen(1, false));
		byte[] mixin = mixin(INJECT, HANDLER, "AFTER", false, false);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise");

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		MixinRetarget.Rewrite rewrite = plan.rewrites().get(0);
		assertEquals(MixinRetarget.Element.AT_TARGET, rewrite.element());
		assertEquals(DECORATIONS, rewrite.from());
		assertEquals(VIA, rewrite.to());
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver).verdict(),
				"the adapter keeps a plan only when the rewritten mixin re-evaluates better");

		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin), resolver).rewrites().size(), "stock 26.2 has the call inline too");
	}

	/** BEFORE the call is not before the helper call: the helper draws the item first. */
	@Test
	void anInjectBeforeACallThatIsNotTheHelpersFirstActStays() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, null, false, false)), resolver(screen(1, false))).isEmpty());
	}

	/** NeoForge's own mods were compiled against renderSlotContents and miss natively the same way. */
	@Test
	void aNeoForgeModIsLeftAsCompiled() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false, false)), resolver(screen(1, false))).isEmpty());
	}

	/** Locals are extractSlot's, and the ones vanilla had at that point are not the merged method's. */
	@Test
	void anInjectCapturingLocalsOrSugarStays() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		Function<String, byte[]> resolver = resolver(screen(1, false));
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", true, false)), resolver).isEmpty());
		String withLocal = "(" + G + SLOT + "II" + MixinRetarget.CALLBACK_INFO + STACK + ")V";
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, withLocal, "AFTER", false, true)), resolver).isEmpty());
	}

	/** A helper called twice, or a call that is no longer the helper's last act, is not the one program point. */
	@Test
	void theLiveBytesMustStillBeTheRowsShape() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		byte[] mixin = mixin(INJECT, HANDLER, "AFTER", false, false);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(screen(2, false))).isEmpty(), "called twice");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(screen(1, true))).isEmpty(), "work after the call");
	}

	/** A @Redirect's handler IS the call; moving it would need the protected helper to have no other caller. */
	@Test
	void aRedirectOfTheMovedCallStays() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		String desc = "(" + G + "Lnet/minecraft/client/gui/Font;" + STACK + "IILjava/lang/String;)V";
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(REDIRECT, desc, null, false, false)), resolver(screen(1, false))).isEmpty());
	}

	/**
	 * The new point names AbstractContainerScreen's helper, but the annotation is shared by every target: on a second
	 * target whose extractSlot still makes the call itself, the move would take a resolved anchor away.
	 */
	@Test
	void aMixinWithASecondTargetKeepsItsPoint() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		Map<String, byte[]> classes = new HashMap<>(Map.of(SCREEN + ".class", screen(1, false)));
		classes.put(OTHER + ".class", other());
		Function<String, byte[]> resolver = classes::get;
		MixinRetarget.Plan alone = MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false, false)), resolver);
		assertEquals(1, alone.rewrites().size(), "premise: with AbstractContainerScreen alone it moves");
		byte[] both = withSecondTarget(mixin(INJECT, HANDLER, "AFTER", false, false));
		assertTrue(MixinFit.evaluate(MixinRetarget.rewritten(both, alone), resolver).unresolved().stream()
				.anyMatch(u -> u.contains(CONTENTS)), "premise: the moved point is not in the other target's extractSlot");

		assertTrue(MixinRetarget.plan(MixinFit.parse(both), resolver).isEmpty());
	}

	@Test
	void theSwitchLeavesThePointAsCompiled() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		System.setProperty(MixinRetarget.EXTRACTED_HELPER_PROPERTY, "off");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false, false)), resolver(screen(1, false))).isEmpty());
	}

	// --- fixtures ---

	/**
	 * {@code extractSlot} guards and calls {@code renderSlotContents} {@code calls} times; the helper draws the item and
	 * then the decorations, and with {@code workAfter} does something more after them.
	 */
	private static byte[] screen(int calls, boolean workAfter) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
			@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
		};
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, SCREEN, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractSlot", EXTRACT_SLOT, null, null);
		m.visitCode();
		Label skip = new Label();
		m.visitVarInsn(Opcodes.ILOAD, 3);
		m.visitJumpInsn(Opcodes.IFNE, skip);
		for (int i = 0; i < calls; i++) {
			m.visitVarInsn(Opcodes.ALOAD, 0);
			m.visitVarInsn(Opcodes.ALOAD, 1);
			m.visitInsn(Opcodes.ACONST_NULL);
			m.visitVarInsn(Opcodes.ALOAD, 2);
			m.visitInsn(Opcodes.ACONST_NULL);
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SCREEN, CONTENTS, CONTENTS_DESC, false);
		}
		m.visitLabel(skip);
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();

		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PROTECTED, CONTENTS, CONTENTS_DESC, null, null);
		h.visitCode();
		h.visitVarInsn(Opcodes.ALOAD, 1);
		h.visitVarInsn(Opcodes.ALOAD, 2);
		h.visitInsn(Opcodes.ICONST_0);
		h.visitInsn(Opcodes.ICONST_0);
		h.visitInsn(Opcodes.ICONST_0);
		h.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G.substring(1, G.length() - 1), "item", "(" + STACK + "III)V", false);
		h.visitVarInsn(Opcodes.ALOAD, 1);
		h.visitInsn(Opcodes.ACONST_NULL);
		h.visitVarInsn(Opcodes.ALOAD, 2);
		h.visitInsn(Opcodes.ICONST_0);
		h.visitInsn(Opcodes.ICONST_0);
		h.visitVarInsn(Opcodes.ALOAD, 4);
		h.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G.substring(1, G.length() - 1), "itemDecorations", DECORATIONS_DESC, false);
		if (workAfter) {
			h.visitVarInsn(Opcodes.ALOAD, 1);
			h.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G.substring(1, G.length() - 1), "flush", "()V", false);
		}
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 0);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * One injector on {@code extractSlot} at INVOKE itemDecorations; {@code shift} null for none, {@code locals} adds a
	 * locals capture, {@code local} marks the last parameter {@code @Local}.
	 */
	private static byte[] mixin(String kind, String handlerDesc, String shift, boolean locals, boolean local) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor m = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = m.visitArray("value");
		targets.visit(null, Type.getObjectType(SCREEN));
		targets.visitEnd();
		m.visitEnd();
		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "renderSlot", handlerDesc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(kind, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, "extractSlot");
		method.visitEnd();
		AnnotationVisitor ats = inj.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", DECORATIONS);
		if (shift != null) at.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", shift);
		at.visitEnd();
		ats.visitEnd();
		if (locals) inj.visitEnum("locals", "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;", "CAPTURE_FAILHARD");
		inj.visitEnd();
		if (local) h.visitParameterAnnotation(Type.getArgumentTypes(handlerDesc).length - 1, MixinRetarget.LOCAL_SUGAR, false).visitEnd();
		h.visitCode();
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 8);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A second target whose {@code extractSlot} still makes the decorations call itself. */
	private static byte[] other() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OTHER, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractSlot", EXTRACT_SLOT, null, null);
		m.visitCode();
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitInsn(Opcodes.ACONST_NULL);
		m.visitInsn(Opcodes.ACONST_NULL);
		m.visitInsn(Opcodes.ICONST_0);
		m.visitInsn(Opcodes.ICONST_0);
		m.visitInsn(Opcodes.ACONST_NULL);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G.substring(1, G.length() - 1), "itemDecorations", DECORATIONS_DESC, false);
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code mixin} with {@link #OTHER} added to its {@code @Mixin} targets. */
	@SuppressWarnings("unchecked")
	private static byte[] withSecondTarget(byte[] mixin) {
		ClassNode node = MixinFit.parse(mixin);
		for (AnnotationNode a : node.invisibleAnnotations) {
			if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) ((List<Object>) a.values.get(1)).add(Type.getObjectType(OTHER));
		}
		ClassWriter cw = new ClassWriter(0);
		node.accept(cw);
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(byte[] screen) {
		return Map.of(SCREEN + ".class", screen)::get;
	}
}
