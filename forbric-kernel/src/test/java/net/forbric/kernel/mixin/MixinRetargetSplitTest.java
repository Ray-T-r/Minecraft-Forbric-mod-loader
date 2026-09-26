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
 * R4 over a synthetic {@code Hud} shaped like the merged one: {@code extractPlayerHealth} dispatches to the layer
 * helpers NeoForge added, and the call Better Mount HUD redirects is made both in {@code extractFoodLevel} (a piece of
 * {@code extractPlayerHealth}) and in {@code extractVehicleHealth} (a layer of its own). The class carries the real
 * names because the move is authorized only by a row of the shipped {@code carrier-helpers.txt}.
 */
class MixinRetargetSplitTest {
	private static final String HUD = "net/minecraft/client/gui/Hud";
	private static final String G = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
	private static final String SHAPE = "(" + G + ")V";
	private static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
	private static final String MAX_HEARTS = "L" + HUD + ";getVehicleMaxHearts(" + LIVING + ")I";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String MIXIN = "test/HudMixin";
	private static final String OTHER = "test/OtherHud";

	@AfterEach
	void reset() {
		System.clearProperty(MixinRetarget.PROPERTY);
		System.clearProperty(MixinRetarget.SPLIT_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	@Test
	void aRedirectOnTheDispatcherMovesToThePieceThatMakesTheCall() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), false));
		byte[] mixin = redirect(MAX_HEARTS);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise");

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("extractPlayerHealth", plan.rewrites().get(0).from());
		assertEquals("extractFoodLevel" + SHAPE, plan.rewrites().get(0).to());
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver).verdict(),
				"the adapter keeps a plan only when the rewritten mixin re-evaluates better");
	}

	/** MinecraftForge keeps vanilla's body in extractPlayerHealth, so its mods were written against the call there too. */
	@Test
	void aMinecraftForgeModMovesAndANeoForgeModDoesNot() {
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), false));
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver).rewrites().size());
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver).isEmpty(),
				"a NeoForge mod was compiled against the split and natively misses the same way");
		MixinStubRebind.forget();
		assertTrue(MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver).isEmpty(),
				"no known owner, no move");
	}

	/** Both fits are pieces of the method: which one the mod meant cannot be told. */
	@Test
	void twoPiecesThatBothMakeTheCallAreRefused() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractFoodLevel", "extractVehicleHealth"), false));
		assertTrue(MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver).isEmpty());
	}

	/** A method with a branch is a body of its own, not a split: the helpers are not simply its pieces. */
	@Test
	void aDispatcherWithABranchIsNotASplit() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), true));
		assertTrue(MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver).isEmpty());
	}

	/**
	 * A cancellable {@code @Inject} is refused: cancelled in the piece it skips only the food bar, where vanilla's
	 * cancel skipped air and everything after it too. One that cannot cancel moves.
	 */
	@Test
	void aCancellableInjectIsRefusedAndAPlainOneMoves() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), false));
		assertTrue(MixinRetarget.plan(MixinFit.parse(inject(true)), resolver).isEmpty());
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(inject(false)), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("extractFoodLevel" + SHAPE, plan.rewrites().get(0).to());
	}

	/** A call no row names is not the table's to move, however the bytes look. */
	@Test
	void aCallWithoutARowIsRefused() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), false));
		assertTrue(MixinRetarget.plan(MixinFit.parse(redirect("L" + HUD + ";unlisted(" + LIVING + ")I")), resolver).isEmpty());
	}

	/** One fit is R3's, as before: the tie-break never runs, whatever the switch says. */
	@Test
	void aSingleFitIsR3sAndUnchanged() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), false, false));
		MixinRetarget.Plan on = MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver);
		System.setProperty(MixinRetarget.SPLIT_PROPERTY, "off");
		MixinRetarget.Plan off = MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver);
		assertEquals(off.describe(), on.describe());
		assertEquals(1, on.rewrites().size());
	}

	/**
	 * The new selector names Hud's piece, but the annotation is shared by every target: on a second target whose
	 * extractPlayerHealth still makes the call, the move would take a resolved anchor away.
	 */
	@Test
	void aMixinWithASecondTargetIsNotSplit() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Map<String, byte[]> classes = new HashMap<>(Map.of(HUD + ".class", hud(List.of("extractHealthLevel", "extractFoodLevel"), false)));
		classes.put(OTHER + ".class", other());
		Function<String, byte[]> resolver = classes::get;
		MixinRetarget.Plan alone = MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver);
		assertEquals(1, alone.rewrites().size(), "premise: with Hud alone it moves");
		byte[] both = withSecondTarget(redirect(MAX_HEARTS));
		assertTrue(MixinFit.evaluate(MixinRetarget.rewritten(both, alone), resolver).unresolved().stream()
				.anyMatch(u -> u.contains("OtherHud")), "premise: the moved selector breaks the other target");

		assertTrue(MixinRetarget.plan(MixinFit.parse(both), resolver).isEmpty());
	}

	@Test
	void theSwitchRefusesTwoFitsAsBefore() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(hud(List.of("extractHealthLevel", "extractFoodLevel"), false));
		System.setProperty(MixinRetarget.SPLIT_PROPERTY, "off");
		assertTrue(MixinRetarget.plan(MixinFit.parse(redirect(MAX_HEARTS)), resolver).isEmpty());
	}

	// --- fixtures ---

	private static byte[] hud(List<String> pieces, boolean branch) {
		return hud(pieces, branch, true);
	}

	/**
	 * {@code extractPlayerHealth} dispatching to {@code pieces}; {@code extractFoodLevel} and (when
	 * {@code vehicleLayer}) {@code extractVehicleHealth} make the redirected call, and {@code unlisted} too.
	 */
	private static byte[] hud(List<String> pieces, boolean branch, boolean vehicleLayer) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
			@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
		};
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HUD, null, "java/lang/Object", null);
		MethodVisitor d = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractPlayerHealth", SHAPE, null, null);
		d.visitCode();
		Label skip = new Label();
		if (branch) {
			d.visitVarInsn(Opcodes.ALOAD, 1);
			d.visitJumpInsn(Opcodes.IFNULL, skip);
		}
		for (String piece : pieces) {
			d.visitVarInsn(Opcodes.ALOAD, 0);
			d.visitVarInsn(Opcodes.ALOAD, 1);
			d.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, piece, SHAPE, false);
		}
		if (branch) d.visitLabel(skip);
		d.visitInsn(Opcodes.RETURN);
		d.visitMaxs(0, 0);
		d.visitEnd();
		for (String name : List.of("extractHealthLevel", "extractFoodLevel", "extractVehicleHealth")) {
			if (name.equals("extractVehicleHealth") && !vehicleLayer) continue;
			MethodVisitor m = cw.visitMethod(name.equals("extractVehicleHealth") ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE, name, SHAPE, null, null);
			m.visitCode();
			if (!name.equals("extractHealthLevel")) {
				for (String callee : List.of("getVehicleMaxHearts", "unlisted")) {
					m.visitVarInsn(Opcodes.ALOAD, 0);
					m.visitInsn(Opcodes.ACONST_NULL);
					m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, callee, "(" + LIVING + ")I", false);
					m.visitInsn(Opcodes.POP);
				}
			}
			m.visitInsn(Opcodes.RETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		for (String callee : List.of("getVehicleMaxHearts", "unlisted")) {
			MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, callee, "(" + LIVING + ")I", null, null);
			m.visitCode();
			m.visitInsn(Opcodes.ICONST_0);
			m.visitInsn(Opcodes.IRETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Better Mount HUD's shape: {@code @Redirect(method="extractPlayerHealth", at=@At(INVOKE, target))}, (Hud, LivingEntity)I. */
	private static byte[] redirect(String target) {
		return mixin(REDIRECT, "(L" + HUD + ";" + LIVING + ")I", target, false);
	}

	private static byte[] inject(boolean cancellable) {
		return mixin(INJECT, "(" + MixinRetarget.CALLBACK_INFO + ")V", MAX_HEARTS, cancellable);
	}

	private static byte[] mixin(String kind, String handlerDesc, String target, boolean cancellable) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor m = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = m.visitArray("value");
		targets.visit(null, Type.getObjectType(HUD));
		targets.visitEnd();
		m.visitEnd();
		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", handlerDesc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(kind, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, "extractPlayerHealth");
		method.visitEnd();
		AnnotationVisitor at = inj.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", target);
		at.visitEnd();
		if (cancellable) inj.visit("cancellable", Boolean.TRUE);
		inj.visitEnd();
		h.visitCode();
		if (Type.getReturnType(handlerDesc).equals(Type.INT_TYPE)) {
			h.visitInsn(Opcodes.ICONST_0);
			h.visitInsn(Opcodes.IRETURN);
		} else {
			h.visitInsn(Opcodes.RETURN);
		}
		h.visitMaxs(1, 4);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A second target whose {@code extractPlayerHealth} still makes the redirected call itself. */
	private static byte[] other() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OTHER, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractPlayerHealth", SHAPE, null, null);
		m.visitCode();
		m.visitInsn(Opcodes.ACONST_NULL);
		m.visitInsn(Opcodes.ACONST_NULL);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, "getVehicleMaxHearts", "(" + LIVING + ")I", false);
		m.visitInsn(Opcodes.POP);
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

	private static Function<String, byte[]> resolver(byte[] hud) {
		return Map.of(HUD + ".class", hud)::get;
	}
}
