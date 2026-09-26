package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.api.Ecosystem;

/**
 * R5's reviewed tier over a synthetic {@code FogRenderer} and {@code ClientHooks} shaped like the merged ones:
 * {@code computeFogColor} ends in the hook call that absorbed vanilla's final {@code dest.set}, and the hook sets
 * {@code dest} and then does more. Real names, because only the reviewed row in {@link MergedBaseAbsorbedCalls}
 * authorizes the move.
 */
class MixinRetargetAbsorbedCallTest {
	private static final String FOG = "net/minecraft/client/renderer/fog/FogRenderer";
	private static final String HOOKS = "net/neoforged/neoforge/client/ClientHooks";
	private static final String V4 = "Lorg/joml/Vector4f;";
	private static final String ARGS = "Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF";
	private static final String COMPUTE = "(" + ARGS + V4 + ")V";
	private static final String HOOK_DESC = "(" + ARGS + "FFF" + V4 + ")V";
	private static final String SET = V4 + "set(FFFF)" + V4;
	private static final String HOOK = "L" + HOOKS + ";getFogColor" + HOOK_DESC;
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String MIXIN = "test/FogMixin";
	/** puzzleslib's handler: static, computeFogColor's own arguments and the callback. */
	private static final String HANDLER = "(" + ARGS + V4 + MixinRetarget.CALLBACK_INFO + ")V";

	@AfterEach
	void reset() {
		System.clearProperty(MergedBaseAbsorbedCalls.PROPERTY);
		System.clearProperty(MixinRetarget.EXTRACTED_HELPER_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	@Test
	void anInjectAfterTheAbsorbedSetFollowsItToTheHookCall() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(true);
		byte[] mixin = mixin(INJECT, HANDLER, "AFTER", false);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise");

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals(MixinRetarget.Element.AT_TARGET, plan.rewrites().get(0).element());
		assertEquals(SET, plan.rewrites().get(0).from());
		assertEquals(HOOK, plan.rewrites().get(0).to());
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver).verdict());

		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin), resolver).rewrites().size(),
				"MinecraftForge's jar sets dest as its last act too, after its own hook");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver).isEmpty(), "NeoForge's mods see the hook natively");
	}

	/** Before the set is not before the hook: the hook computes and sets in one call. Only AFTER is reviewed. */
	@Test
	void onlyAnAfterPointMoves() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, null, false)), resolver(true)).isEmpty());
	}

	/** A @WrapOperation's handler is the set itself, which now happens inside the hook with the hook's arguments. */
	@Test
	void aHandlerThatIsTheCallStays() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		String desc = "(" + V4 + "FFFFLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)" + V4;
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(WRAP, desc, null, false)), resolver(true)).isEmpty());
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", true)), resolver(true)).isEmpty(),
				"locals capture: computeFogColor's locals at the set are not the merged method's");
	}

	/** The review was about a hook that makes the call; one that no longer does, or cannot be read, moves nothing. */
	@Test
	void aHookThatNoLongerMakesTheCallMovesNothing() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false)), resolver(false)).isEmpty());
		Map<String, byte[]> onlyFog = Map.of(FOG + ".class", fog());
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false)), onlyFog::get).isEmpty());
	}

	/** The reviewed tier has its own switch; the census tier's does not turn it off. */
	@Test
	void theSwitchesAreSeparate() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinRetarget.EXTRACTED_HELPER_PROPERTY, "off");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false)), resolver(true)).rewrites().size());
		System.setProperty(MergedBaseAbsorbedCalls.PROPERTY, "off");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(INJECT, HANDLER, "AFTER", false)), resolver(true)).isEmpty());
	}

	// --- fixtures ---

	/** computeFogColor: some work, then the hook call as its last act. */
	private static byte[] fog() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FOG, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE, "computeFogColor", COMPUTE, null, null);
		m.visitCode();
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitVarInsn(Opcodes.FLOAD, 2);
		m.visitVarInsn(Opcodes.ALOAD, 3);
		m.visitVarInsn(Opcodes.ILOAD, 4);
		m.visitVarInsn(Opcodes.FLOAD, 5);
		m.visitInsn(Opcodes.FCONST_0);
		m.visitInsn(Opcodes.FCONST_1);
		m.visitInsn(Opcodes.FCONST_0);
		m.visitVarInsn(Opcodes.ALOAD, 6);
		m.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getFogColor", HOOK_DESC, false);
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** getFogColor: dest.set(r, g, b, 1), then (standing in for the fluid extension and the event) one more call. */
	private static byte[] hooks(boolean sets) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getFogColor", HOOK_DESC, null, null);
		m.visitCode();
		if (sets) {
			m.visitVarInsn(Opcodes.ALOAD, 8);
			m.visitVarInsn(Opcodes.FLOAD, 5);
			m.visitVarInsn(Opcodes.FLOAD, 6);
			m.visitVarInsn(Opcodes.FLOAD, 7);
			m.visitInsn(Opcodes.FCONST_1);
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/joml/Vector4f", "set", "(FFFF)" + V4, false);
			m.visitInsn(Opcodes.POP);
		}
		m.visitVarInsn(Opcodes.ALOAD, 8);
		m.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "post", "(" + V4 + ")V", false);
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(boolean hookSets) {
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(FOG + ".class", fog());
		classes.put(HOOKS + ".class", hooks(hookSets));
		return classes::get;
	}

	private static byte[] mixin(String kind, String handlerDesc, String shift, boolean locals) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor m = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = m.visitArray("value");
		targets.visit(null, Type.getObjectType(FOG));
		targets.visitEnd();
		m.visitEnd();
		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "computeFogColor", handlerDesc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(kind, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, "computeFogColor");
		method.visitEnd();
		AnnotationVisitor ats = inj.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", SET);
		if (shift != null) at.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", shift);
		at.visitEnd();
		ats.visitEnd();
		if (locals) inj.visitEnum("locals", "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;", "CAPTURE_FAILHARD");
		inj.visitEnd();
		h.visitCode();
		if (Type.getReturnType(handlerDesc).getSort() == Type.OBJECT) {
			h.visitInsn(Opcodes.ACONST_NULL);
			h.visitInsn(Opcodes.ARETURN);
		} else {
			h.visitInsn(Opcodes.RETURN);
		}
		h.visitMaxs(1, 12);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
