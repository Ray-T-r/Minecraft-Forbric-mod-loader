/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The adapter must drop a guest mixin exactly when the members it names no longer exist on the merged target —
 * not because of who owns the target class. Targets and mixins are synthesized with ASM so the test does not depend
 * on a real fabric-api jar or a real merged base.
 *
 * <p>The two cases that decide correctness, both measured on the real client set before being written down here:
 * the orphaned-{@code @Shadow} archetype MUST be caught ({@code GuiRenderer.pictureInPictureRenderers} — declared,
 * never assigned), and a PUBLIC field written by some other class MUST NOT be
 * ({@code MovingBlockRenderState.biome} — a render-state DTO, the shape renderer mods target most).
 */
class KernelGuestMixinAdapterTest {
	private static final String PKG = "net/example/mixin";
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

	// --- synthetic merged-base targets -------------------------------------------------------------------------

	/**
	 * A target declaring {@code field} of type {@code Ljava/util/Map;}, assigned in {@code <init>} only when
	 * {@code assigned}. Unassigned + private is the orphaned archetype.
	 */
	private static byte[] target(String internalName, String field, int access, boolean assigned) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		cw.visitField(access, field, "Ljava/util/Map;", null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (assigned) {
			ctor.visitVarInsn(Opcodes.ALOAD, 0);
			ctor.visitInsn(Opcodes.ACONST_NULL);
			ctor.visitFieldInsn(Opcodes.PUTFIELD, internalName, field, "Ljava/util/Map;");
		}
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(3, 1);
		ctor.visitEnd();

		// A method the mixins below can anchor an @Inject on.
		MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
		render.visitCode();
		render.visitInsn(Opcodes.RETURN);
		render.visitMaxs(0, 1);
		render.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	// --- synthetic guest mixins --------------------------------------------------------------------------------

	private static ClassWriter beginMixin(String simpleName, String targetInternalName, String... interfaces) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PKG + "/" + simpleName, null, "java/lang/Object",
				interfaces.length == 0 ? null : interfaces);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor arr = mixin.visitArray("value");
		arr.visit(null, Type.getObjectType(targetInternalName));
		arr.visitEnd();
		mixin.visitEnd();
		return cw;
	}

	/** A mixin that {@code @Shadow}s one Map field and injects into {@code render}. */
	private static byte[] shadowingMixin(String simpleName, String target, String field, String... interfaces) {
		ClassWriter cw = beginMixin(simpleName, target, interfaces);
		FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE, field, "Ljava/util/Map;", null, null);
		fv.visitAnnotation(SHADOW, false).visitEnd();
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onRender", "()V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, "render");
		methods.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A mixin whose @Inject names a method the target does not have. */
	private static byte[] danglingMixin(String simpleName, String target) {
		ClassWriter cw = beginMixin(simpleName, target);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onGone", "()V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, "methodThatNoLongerExists");
		methods.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A mixin whose one injector lists SEVERAL candidate selectors — Mixin's require=1 alternatives idiom. */
	private static byte[] multiSelectorMixin(String simpleName, String target, String... selectors) {
		ClassWriter cw = beginMixin(simpleName, target);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onEither", "()V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
		AnnotationVisitor methods = inject.visitArray("method");
		for (String s : selectors) methods.visit(null, s);
		methods.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A mixin that casts its target to {@code contract} — the dependent half of a cast contract. */
	private static byte[] castingMixin(String simpleName, String target, String contract) {
		ClassWriter cw = beginMixin(simpleName, target);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onRender", "()V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, "render");
		methods.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitTypeInsn(Opcodes.CHECKCAST, contract);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] accessorMixin(String simpleName, String targetInternalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
				PKG + "/" + simpleName, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor arr = mixin.visitArray("value");
		arr.visit(null, Type.getObjectType(targetInternalName));
		arr.visitEnd();
		mixin.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getThing", "()I", null, null);
		mv.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false).visitEnd();
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] config(String pkg, String... clientMixins) {
		StringBuilder sb = new StringBuilder("{\"package\":\"").append(pkg).append("\",\"client\":[");
		for (int i = 0; i < clientMixins.length; i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(clientMixins[i]).append('"');
		}
		sb.append("]}");
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static Function<String, byte[]> resolver(Map<String, byte[]> classes) {
		return path -> classes.get(path);
	}

	// --- tests --------------------------------------------------------------------------------------------------

	@Test
	void dropsOrphanedShadowButKeepsAnIntactMixinOnTheSameOwnedPackage() {
		String gui = "net/minecraft/client/gui/render/SomeGuiThing";
		String ok = "net/minecraft/client/renderer/LevelRenderer";
		Map<String, byte[]> classes = new HashMap<>();
		// The archetype: private, declared, NEVER assigned.
		classes.put(gui + ".class", target(gui, "orphanedRenderers", Opcodes.ACC_PRIVATE, false));
		// Same owned package, but the shadowed field is assigned — under the OLD rule this was dropped too.
		classes.put(ok + ".class", target(ok, "sectionsToRender", Opcodes.ACC_PRIVATE, true));

		classes.put(PKG + "/GuiRendererMixin.class",
				shadowingMixin("GuiRendererMixin", gui, "orphanedRenderers"));
		classes.put(PKG + "/LevelRendererMixin.class",
				shadowingMixin("LevelRendererMixin", ok, "sectionsToRender"));

		List<String> dropped = KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "GuiRendererMixin", "LevelRendererMixin"), resolver(classes));

		assertEquals(List.of("GuiRendererMixin"), dropped,
				"only the orphaned-@Shadow mixin should be dropped; owning the package is not a hazard");
	}

	@Test
	void keepsAPublicFieldWrittenElsewhere() {
		// MovingBlockRenderState.biome: public, read in-class, written by whoever populates the render state.
		// A class-local putfield scan calls this orphaned; judging only private fields is what makes it sound.
		String state = "net/minecraft/client/renderer/block/MovingBlockRenderState";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(state + ".class", target(state, "biome", Opcodes.ACC_PUBLIC, false));
		classes.put(PKG + "/MovingBlockRenderStateMixin.class",
				shadowingMixin("MovingBlockRenderStateMixin", state, "biome"));

		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "MovingBlockRenderStateMixin"), resolver(classes)).isEmpty(),
				"a public field may legitimately be assigned by another class — never call it orphaned");
	}

	@Test
	void dropsAMixinWhoseInjectTargetIsGone() {
		String t = "net/minecraft/client/renderer/GameRenderer";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
		classes.put(PKG + "/GoneMixin.class", danglingMixin("GoneMixin", t));

		assertEquals(List.of("GoneMixin"), KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "GoneMixin"), resolver(classes)));
	}

	@Test
	void oneOfSeveralSelectorsResolvingIsAFullyAppliedInjector() {
		// Mixin's `method` list is a set of CANDIDATES, not a conjunction: require=1 counts matches across the
		// whole list, so mods ship alternative names to span mappings. Iris's LevelRenderer mixin carries both
		// lambda$addSkyPass$0 and lambda$addSkyPass$8 for one handler; judging each selector separately reported
		// 11 of its 41 anchors missing while the injector was installed the whole time.
		String t = "net/minecraft/client/renderer/GameRenderer";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
		classes.put(PKG + "/AltMixin.class", multiSelectorMixin("AltMixin", t, "renderUnderOldName", "render"));

		String previous = System.setProperty("forbric.mixinFit", "strict");
		try {
			// FIT, not PARTIAL — so even strict keeps it.
			assertEquals(List.of(), KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
					config(PKG.replace('/', '.'), "AltMixin"), resolver(classes)));
		} finally {
			if (previous == null) System.clearProperty("forbric.mixinFit");
			else System.setProperty("forbric.mixinFit", previous);
		}
	}

	@Test
	void aRegexSelectorIsNotJudged() {
		// fabric-permission-api-v1's CommandSourceStackMixin selects every builder with `/^with/
		// desc=/CommandSourceStack;$/`. Matching that means reimplementing Mixin's selector engine; treating it
		// as a literal method name reports a miss for something Mixin resolves fine. Un-judgeable → resolved.
		String t = "net/minecraft/commands/CommandSourceStack";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
		classes.put(PKG + "/RegexMixin.class",
				multiSelectorMixin("RegexMixin", t, "/^with/ desc=/CommandSourceStack;$/"));

		assertEquals(List.of(), KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "RegexMixin"), resolver(classes)));
	}

	@Test
	void dropsTheDependentHalfOfACastContract() {
		// GuiRendererMixin implements GuiRendererExtensions and is dropped as a HAZARD; GameRendererMixin resolves
		// cleanly but casts to that interface, so keeping it alone would ClassCastException.
		String gui = "net/minecraft/client/gui/render/SomeGuiThing";
		String game = "net/minecraft/client/renderer/GameRenderer";
		String contract = "net/fabricmc/fabric/impl/client/rendering/GuiRendererExtensions";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(gui + ".class", target(gui, "orphanedRenderers", Opcodes.ACC_PRIVATE, false));
		classes.put(game + ".class", target(game, "unused", Opcodes.ACC_PRIVATE, true));
		classes.put(PKG + "/GuiRendererMixin.class",
				shadowingMixin("GuiRendererMixin", gui, "orphanedRenderers", contract));
		classes.put(PKG + "/GameRendererMixin.class", castingMixin("GameRendererMixin", game, contract));

		List<String> dropped = KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "GuiRendererMixin", "GameRendererMixin"), resolver(classes));

		assertTrue(dropped.contains("GuiRendererMixin"), "the orphaned mixin is the hazard");
		assertTrue(dropped.contains("GameRendererMixin"),
				"its cast-contract dependent must go with it, or the NPE becomes a CCE");
	}

	@Test
	void keepsPureAccessorsEvenOnAnOrphanedTarget() {
		String gui = "net/minecraft/client/gui/render/SomeGuiThing";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(gui + ".class", target(gui, "orphanedRenderers", Opcodes.ACC_PRIVATE, false));
		classes.put(PKG + "/GuiRendererAccessor.class", accessorMixin("GuiRendererAccessor", gui));

		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "GuiRendererAccessor"), resolver(classes)).isEmpty(),
				"an accessor injects no behaviour and other code casts to the interface it contributes");
	}

	@Test
	void honoursTheExplicitKeepListOverTheDerivedVerdict() {
		String gui = "net/minecraft/client/gui/render/SomeGuiThing";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(gui + ".class", target(gui, "orphanedRenderers", Opcodes.ACC_PRIVATE, false));
		classes.put(PKG + "/KeptMixin.class", shadowingMixin("KeptMixin", gui, "orphanedRenderers"));

		System.setProperty("forbric.keepMixins", "example.mixins.json:KeptMixin");
		try {
			assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
					config(PKG.replace('/', '.'), "KeptMixin"), resolver(classes)).isEmpty(),
					"-Dforbric.keepMixins must override the derived verdict");
		} finally {
			System.clearProperty("forbric.keepMixins");
		}
	}

	@Test
	void aTargetTheResolverCannotSeeIsNotJudged() {
		// JDK classes, other mods' classes and mixin-generated types all resolve to null; nothing to prove.
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(PKG + "/ForeignMixin.class",
				shadowingMixin("ForeignMixin", "com/example/other/Thing", "whatever"));

		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "ForeignMixin"), resolver(classes)).isEmpty());
	}

	@Test
	void anOrphanPostMixinFixupsSeedsIsNotAHazard() {
		// GuiRenderer.pictureInPictureRenderers IS orphaned in the merged base, but PostMixinFixups seeds it with an
		// empty map, so the mixins that @Shadow it work. Suppressing them instead broke MaLiLib: its MixinGameRenderer
		// read the same map through mod-owned state and NPE'd during Minecraft.<init>.
		String gui = "net/minecraft/client/gui/render/GuiRenderer";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(gui + ".class", target(gui, "pictureInPictureRenderers", Opcodes.ACC_PRIVATE, false));
		classes.put(PKG + "/GuiRendererMixin.class",
				shadowingMixin("GuiRendererMixin", gui, "pictureInPictureRenderers"));

		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "GuiRendererMixin"), resolver(classes)).isEmpty(),
				"a field the repair pass seeds must not be reported as an orphan");
	}

	@Test
	void returnsEmptyForNonMixinJsonOrMissingPackage() {
		assertTrue(KernelGuestMixinAdapter.unfitMixins(
				"x.json", "{\"not\":\"a mixin config\"}".getBytes(StandardCharsets.UTF_8), p -> null).isEmpty());
		assertTrue(KernelGuestMixinAdapter.unfitMixins(
				"x.json", "not even json".getBytes(StandardCharsets.UTF_8), p -> null).isEmpty());
	}

	@Test
	void toleratesAMixinClassThatCannotBeResolved() {
		byte[] cfg = config("net.example.mixin", "GhostMixin");
		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json", cfg, p -> null).isEmpty());
	}

	@Test
	void partialIsKeptByDefaultAndDroppedUnderStrict() {
		String t = "net/minecraft/client/renderer/GameRenderer";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
		// One anchor resolves (render), one does not (methodThatNoLongerExists) → PARTIAL.
		ClassWriter cw = beginMixin("HalfMixin", t);
		for (String[] spec : new String[][] {{"onRender", "render"}, {"onGone", "methodThatNoLongerExists"}}) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, spec[0], "()V", null, null);
			AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
			AnnotationVisitor methods = inject.visitArray("method");
			methods.visit(null, spec[1]);
			methods.visitEnd();
			inject.visitEnd();
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 1);
			mv.visitEnd();
		}
		cw.visitEnd();
		classes.put(PKG + "/HalfMixin.class", cw.toByteArray());
		byte[] cfg = config(PKG.replace('/', '.'), "HalfMixin");

		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json", cfg, resolver(classes)).isEmpty(),
				"PARTIAL is kept by default so the change stays monotonic against the old rule");

		System.setProperty("forbric.mixinFit", "strict");
		try {
			assertFalse(KernelGuestMixinAdapter.unfitMixins("example.mixins.json", cfg, resolver(classes)).isEmpty(),
					"-Dforbric.mixinFit=strict opts into dropping half-applied mixins");
		} finally {
			System.clearProperty("forbric.mixinFit");
		}
	}
}
