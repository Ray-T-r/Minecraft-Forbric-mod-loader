package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;

/**
 * An injector bound only to a merged-base method nothing in the merged game calls is reported as not running — the
 * shape of Better Mount HUD's XP redirect in {@code Hud.extractHotbarAndDecorations}, whose one vanilla caller NeoForge's
 * HUD layers replaced — with the shipped row, synthetic classes, and no staged jar.
 */
@ResourceLock("system-properties")
class MixinFitLivenessTest {
	private static final String HUD = "net/minecraft/client/gui/Hud";
	private static final String G = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";
	private static final String HOTBAR = "extractHotbarAndDecorations";
	private static final String HAS_EXPERIENCE = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z";
	private static final String MIXIN = "test/HudMixin";

	@AfterEach
	void reset() {
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		MixinStubRebind.forget();
		MergedBaseUncalledMethods.forgetGuests();
	}

	@Test
	void aRedirectBoundOnlyInAMethodNothingCallsIsPartialWithTheReason() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.unresolved().toString());
		assertEquals(1, fit.resolved(), "the @At member is still there");
		assertEquals(List.of("@Inject target Hud.extractHotbarAndDecorations never runs: nothing in the merged game calls it; "
				+ "vanilla and MinecraftForge call it from Hud.extractRenderState"), fit.unresolved());
		assertTrue(!fit.shouldSuppress(), "soft: reported, never dropped");
	}

	@Test
	void theSwitchReadsItAsResolvedAgain() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinFit.LIVENESS_PROPERTY, "off");
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/**
	 * The binding still counts against UNFIT: malilib's stub-bound hook (MixinStubRebind off) is bound to a stub nothing
	 * calls and misses its @At there; it must stay PARTIAL and kept, not become UNFIT and be dropped.
	 */
	@Test
	void aNeverRunningBindingNeverMakesAMixinUnfit() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, "Lnet/example/Gone;gone()Z"), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.unresolved().toString());
		assertEquals(2, fit.unresolved().size(), fit.unresolved().toString());
		assertTrue(!fit.shouldSuppress());
	}

	/** A transform that restores the call in the method's own class makes it live: the row is re-checked on the live bytes. */
	@Test
	void aCallBackInTheLiveClassMakesItRun() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(true))));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/**
	 * An installed mod calling the method makes it run: fabric-resource-loader, Iris and Collective call the
	 * {@code Language.loadFromJson} stub the merged game no longer does. The boot-time scan reads it from the constant pool.
	 */
	@Test
	void aModThatCallsTheMethodMakesItRun() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MergedBaseUncalledMethods.noteGuest(type("test/Caller", "draw", "()V", HUD, HOTBAR, G, false));
		assertTrue(MergedBaseUncalledMethods.calledByGuest(HOTBAR + G));
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/** NeoForge's own game never had the method, so a NeoForge mod was not promised a caller; nor is a mod nobody owns. */
	@Test
	void onlyTheEcosystemsWhoseGameCallsItAreJudged() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false)))).verdict());
		MixinStubRebind.forget();
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false)))).verdict());
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false)))).verdict());
	}

	/** A listed caller in another class is read through the resolver: vanilla's fluid renderer asking the fluid's tint. */
	@Test
	void aListedCallerInAnotherClassIsReadThroughTheResolver() {
		String model = "net/minecraft/client/renderer/block/FluidModel";
		String renderer = "net/minecraft/client/renderer/block/FluidRenderer";
		String tint = "tintSource", tintDesc = "()Lnet/minecraft/client/color/block/BlockTintSource;";
		String tesselate = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/world/level/material/FluidState;)V";
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] mixin = redirect(model, tint, "Ljava/lang/Object;hashCode()I");
		byte[] target = type(model, tint, tintDesc, "java/lang/Object", "hashCode", "()I", false);

		MixinFit.Result dead = MixinFit.evaluate(mixin, resolver(Map.of(model, target,
				renderer, type(renderer, "tesselate", tesselate, null, null, null, false))));
		assertEquals(MixinFit.Verdict.PARTIAL, dead.verdict(), dead.unresolved().toString());
		assertTrue(dead.unresolved().get(0).endsWith("vanilla and MinecraftForge call it from FluidRenderer.tesselate"),
				dead.unresolved().toString());

		MixinFit.Result live = MixinFit.evaluate(mixin, resolver(Map.of(model, target,
				renderer, type(renderer, "tesselate", tesselate, model, tint, tintDesc, false))));
		assertEquals(MixinFit.Verdict.FIT, live.verdict(), live.unresolved().toString());
	}

	private static final String HEALTH = "extractPlayerHealth", HEALTH_DESC = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";
	private static final String FORGE_TREE = "net/minecraftforge/client/gui/overlay/ForgeLayeredDraw";
	private static final String FORGE_LEAF = "lambda$init$3";
	private static final String FORGE_LEAF_DESC = "(Lnet/minecraft/client/gui/Hud;Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";

	/**
	 * A method only an uncalled method and a silenced leaf call never runs either: Balm's health events at the start and end of
	 * {@code Hud.extractPlayerHealth}, which vanilla calls only from the uncalled {@code extractHotbarAndDecorations} and
	 * MinecraftForge only from a leaf of its vanilla HUD tree, which the kernel silences. Read as running while that leaf still
	 * named it, the load report said nothing about two events that were never heard.
	 */
	@Test
	void aMethodOnlyDeadCodeCallsNeverRunsEither() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(inject(HUD, HEALTH), resolver(Map.of(HUD, healthHud(false), FORGE_TREE, forgeTree())));
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.unresolved().toString());
		assertEquals(List.of("@Inject target Hud.extractPlayerHealth never runs: nothing in the merged game calls it; vanilla and "
				+ "MinecraftForge call it from Hud.extractHotbarAndDecorations, ForgeLayeredDraw.lambda$init$3"), fit.unresolved());
	}

	/** The dead caller run again by a transform makes what it calls run too. */
	@Test
	void aLiveCallOfTheDeadCallerMakesItRun() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(inject(HUD, HEALTH), resolver(Map.of(HUD, healthHud(true), FORGE_TREE, forgeTree())));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/** …and so does an installed mod calling the dead caller. */
	@Test
	void aModCallingTheDeadCallerMakesItRun() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MergedBaseUncalledMethods.noteGuest(type("test/Caller", "draw", "()V", HUD, HOTBAR, G, false));
		MixinFit.Result fit = MixinFit.evaluate(inject(HUD, HEALTH), resolver(Map.of(HUD, healthHud(false), FORGE_TREE, forgeTree())));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Hud whose extractHotbarAndDecorations calls extractPlayerHealth; extractRenderState calls the former when asked. */
	private static byte[] healthHud(boolean renderCallsHotbar) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HUD, null, "java/lang/Object", null);
		MethodVisitor health = cw.visitMethod(Opcodes.ACC_PUBLIC, HEALTH, HEALTH_DESC, null, null);
		health.visitCode();
		health.visitInsn(Opcodes.RETURN);
		health.visitMaxs(0, 0);
		health.visitEnd();
		MethodVisitor hotbar = cw.visitMethod(Opcodes.ACC_PUBLIC, HOTBAR, G, null, null);
		hotbar.visitCode();
		hotbar.visitVarInsn(Opcodes.ALOAD, 0);
		hotbar.visitVarInsn(Opcodes.ALOAD, 1);
		hotbar.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, HEALTH, HEALTH_DESC, false);
		hotbar.visitInsn(Opcodes.RETURN);
		hotbar.visitMaxs(0, 0);
		hotbar.visitEnd();
		MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", G, null, null);
		render.visitCode();
		if (renderCallsHotbar) {
			render.visitVarInsn(Opcodes.ALOAD, 0);
			render.visitVarInsn(Opcodes.ALOAD, 1);
			render.visitVarInsn(Opcodes.ALOAD, 2);
			render.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, HOTBAR, G, false);
		}
		render.visitInsn(Opcodes.RETURN);
		render.visitMaxs(0, 0);
		render.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * MinecraftForge's tree with the one leaf that calls extractPlayerHealth, built — a method handle to it — by the tree's
	 * build method, which runs: the kernel silences the leaf it builds, so building it does not make it run.
	 */
	private static byte[] forgeTree() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FORGE_TREE, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "(Lnet/minecraft/client/gui/Hud;)V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitInvokeDynamicInsn("extract", "(Lnet/minecraft/client/gui/Hud;)Ljava/lang/Runnable;",
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
								+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
				org.objectweb.asm.Type.getType("()V"),
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, FORGE_TREE, FORGE_LEAF, FORGE_LEAF_DESC, false),
				org.objectweb.asm.Type.getType("()V"));
		init.visitInsn(Opcodes.POP);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor leaf = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, FORGE_LEAF, FORGE_LEAF_DESC, null, null);
		leaf.visitCode();
		leaf.visitVarInsn(Opcodes.ALOAD, 0);
		leaf.visitVarInsn(Opcodes.ALOAD, 1);
		leaf.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, HEALTH, HEALTH_DESC, false);
		leaf.visitInsn(Opcodes.RETURN);
		leaf.visitMaxs(0, 0);
		leaf.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code @Mixin(targets = target) class HudMixin { @Inject(method = method, at = @At("HEAD")) void before(GuiGraphicsExtractor, CallbackInfo) } } */
	private static byte[] inject(String target, String method) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target.replace('/', '.'));
		targets.visitEnd();
		mixin.visitEnd();
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "before",
				"(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, method);
		methods.visitEnd();
		AnnotationVisitor ats = inject.visitArray("at");
		AnnotationVisitor point = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		point.visit("value", "HEAD");
		point.visitEnd();
		ats.visitEnd();
		inject.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 0);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(Map<String, byte[]> classes) {
		return name -> classes.get(name.substring(0, name.length() - ".class".length()));
	}

	/** Hud with extractHotbarAndDecorations calling hasExperience, and extractRenderState — calling it back when asked. */
	private static byte[] hud(boolean renderCallsHotbar) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HUD, null, "java/lang/Object", null);
		MethodVisitor hotbar = cw.visitMethod(Opcodes.ACC_PUBLIC, HOTBAR, G, null, null);
		hotbar.visitCode();
		hotbar.visitInsn(Opcodes.ACONST_NULL);
		hotbar.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/MultiPlayerGameMode", "hasExperience", "()Z", false);
		hotbar.visitInsn(Opcodes.POP);
		hotbar.visitInsn(Opcodes.RETURN);
		hotbar.visitMaxs(0, 0);
		hotbar.visitEnd();
		MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", G, null, null);
		render.visitCode();
		if (renderCallsHotbar) {
			render.visitVarInsn(Opcodes.ALOAD, 0);
			render.visitVarInsn(Opcodes.ALOAD, 1);
			render.visitVarInsn(Opcodes.ALOAD, 2);
			render.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, HOTBAR, G, false);
		}
		render.visitInsn(Opcodes.RETURN);
		render.visitMaxs(0, 0);
		render.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class with one method, which calls {@code calleeOwner.callee} when one is given. */
	private static byte[] type(String name, String method, String desc, String calleeOwner, String callee, String calleeDesc,
			boolean isStatic) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), method, desc, null, null);
		mv.visitCode();
		if (callee != null) {
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, calleeOwner, callee, calleeDesc, false);
			mv.visitInsn(Opcodes.POP);
		}
		if (desc.endsWith("V")) mv.visitInsn(Opcodes.RETURN);
		else { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); }
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code @Mixin(targets = target) class HudMixin { @Redirect(method = method, at = @At(value = "INVOKE", target = at)) … }} */
	private static byte[] redirect(String target, String method, String at) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target.replace('/', '.'));
		targets.visitEnd();
		mixin.visitEnd();
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "hide", "(Ljava/lang/Object;)Z", null, null);
		AnnotationVisitor redirect = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Redirect;", true);
		AnnotationVisitor methods = redirect.visitArray("method");
		methods.visit(null, method);
		methods.visitEnd();
		AnnotationVisitor point = redirect.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		point.visit("value", "INVOKE");
		point.visit("target", at);
		point.visitEnd();
		redirect.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.ICONST_0);
		handler.visitInsn(Opcodes.IRETURN);
		handler.visitMaxs(0, 0);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
