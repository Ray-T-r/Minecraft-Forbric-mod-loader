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

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
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
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class KernelGuestMixinAdapterTest {
	@org.junit.jupiter.api.BeforeEach
	@org.junit.jupiter.api.AfterEach
	void clearCompatibilityEvidence() { net.forbric.api.CompatibilityFindings.reset(); }

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

	/** One anchor resolves ({@code render}) and one does not ({@code methodThatNoLongerExists}) — PARTIAL. */
	private static byte[] halfMixin(String simpleName, String target) {
		ClassWriter cw = beginMixin(simpleName, target);
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

	/** The same config with a {@code plugin}, which is what makes the attribution deferrable. */
	private static byte[] configWithPlugin(String pkg, String plugin, String... clientMixins) {
		String json = new String(config(pkg, clientMixins), StandardCharsets.UTF_8);
		return json.replaceFirst("^\\{", java.util.regex.Matcher.quoteReplacement("{\"plugin\":\"" + plugin + "\","))
				.getBytes(StandardCharsets.UTF_8);
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
	void aSuppressedMixinMarksItsOwningModDegraded() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("x.mixins.json", "xmod", Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "xmod", "X", "1", "", List.of(), "x.jar", "", "")));
			String t = "net/minecraft/client/renderer/GameRenderer";
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(PKG + "/Dangling.class", danglingMixin("Dangling", t));

			assertEquals(List.of("Dangling"), KernelGuestMixinAdapter.unfitMixins("x.mixins.json",
					config(PKG.replace('/', '.'), "Dangling"), resolver(classes)));
			assertEquals(1, ModCatalog.failures().size(), "the owning mod's row says what was left out");
			ModCatalog.Entry xmod = ModCatalog.failures().get(0);
			assertEquals("xmod", xmod.modId());
			assertEquals(ModCatalog.Status.DEGRADED, xmod.status());
			assertTrue(xmod.statusDetail().contains("Dangling"), xmod.statusDetail());
		} finally {
			MixinConfigOwners.reset();
			ModCatalog.publish(previous);
		}
	}

	/**
	 * Iris' case: a mixin whose target exists only in the build of a duplicated mod the kernel did not load.
	 * Mixin says nothing about a target that never loads, so without this the mod boots clean and the interface
	 * the mixin was there to implant is missing — a ClassCastException at the first use, minutes later.
	 */
	@Test
	void aMixinTargetingAnArbitratedAwayClassIsNamedInsteadOfBeingSilentlyInert() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			net.forbric.kernel.boot.ArbitratedAwayClasses.reset();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("iris.mixins.json", "iris", Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "iris", "Iris", "1", "", List.of(), "i.jar", "", "")));
			String gone = "net/caffeinemc/mods/sodium/fabric/render/FluidRendererImpl";
			net.forbric.kernel.boot.ArbitratedAwayClasses.record(List.of(gone.replace('/', '.')),
					new net.forbric.kernel.boot.ArbitratedAwayClasses.Loss("sodium", Ecosystem.FABRIC,
							Ecosystem.NEOFORGE, "sodium-fabric-0.9.2.jar"));

			// The target's bytes are deliberately STILL SERVABLE, because in the live boot they were: the losing
			// jar stays readable on the owned classpath even though its classes are not the ones in play. A
			// resource check was silent on exactly this case; membership of the registry is the whole test.
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(gone + ".class", target(gone, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(PKG + "/MixinFluidRendererImpl.class", danglingMixin("MixinFluidRendererImpl", gone));

			assertTrue(KernelGuestMixinAdapter.unfitMixins("iris.mixins.json",
					config(PKG.replace('/', '.'), "MixinFluidRendererImpl"), resolver(classes)).isEmpty(),
					"nothing is suppressed — the mixin was never going to apply; what was missing is the report");
			assertEquals(1, ModCatalog.failures().size());
			String detail = ModCatalog.failures().get(0).statusDetail();
			assertTrue(detail.contains("FluidRendererImpl"), detail);
			assertTrue(detail.contains("sodium"), "the row must name the mod whose build was arbitrated away: " + detail);
			// And the ledger has it: the target never loads, so the mixin is confirmed not to run — but a missing
			// target is only a warning to native Mixin, so it is not a necessary loss that stops a launch.
			var finding = net.forbric.api.CompatibilityFindings.all().stream()
					.filter(f -> f.id().equals(MixinCompatibility.id("iris.mixins.json",
							PKG.replace('/', '.') + ".MixinFluidRendererImpl"))).findFirst().orElseThrow();
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, finding.confidence());
			assertFalse(finding.required());
			assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
		} finally {
			net.forbric.kernel.boot.ArbitratedAwayClasses.reset();
			MixinConfigOwners.reset();
			ModCatalog.publish(previous);
		}
	}

	/** A target that is merely absent — no arbitration removed it — is an ordinary compat mixin and stays quiet. */
	@Test
	void aMixinForAModThatIsSimplyNotInstalledIsNotReported() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			net.forbric.kernel.boot.ArbitratedAwayClasses.reset();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("q.mixins.json", "qmod", Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "qmod", "Q", "1", "", List.of(), "q.jar", "", "")));
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(PKG + "/MixinAbsent.class", danglingMixin("MixinAbsent", "de/example/NotInstalled"));

			KernelGuestMixinAdapter.unfitMixins("q.mixins.json",
					config(PKG.replace('/', '.'), "MixinAbsent"), resolver(classes));

			assertTrue(ModCatalog.failures().isEmpty(),
					"a compat mixin for an uninstalled mod is normal, and marking it would be the false positive "
							+ "this report exists to avoid");
		} finally {
			MixinConfigOwners.reset();
			ModCatalog.publish(previous);
		}
	}

	/**
	 * Iris beside a Sodium that stopped making the call Iris redirects: the anchor misses on ANOTHER MOD's class.
	 * Nothing has been observed to fail yet, so the finding is only SUSPECTED and asks nobody to continue or quit —
	 * but a suspicion still belongs in the details. The dependency dialog's mixin section reads ForeignMixinBreaks
	 * and the Mods screen reads the row; with neither fed, the render crash a frame later names no mod at all.
	 */
	@Test
	void aMissOnAnotherModsClassReachesTheDialogAndTheRowButStaysSuspected() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			ForeignMixinBreaks.reset();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("iris.mixins.json", "iris", Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "iris", "Iris", "1", "", List.of(), "i.jar", "", "")));
			String sodium = "net/caffeinemc/mods/sodium/client/render/chunk/RenderRegionManager";
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(sodium + ".class", target(sodium, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(PKG + "/MixinRenderRegionManager.class", halfMixin("MixinRenderRegionManager", sodium));

			assertTrue(KernelGuestMixinAdapter.unfitMixins("iris.mixins.json",
					config(PKG.replace('/', '.'), "MixinRenderRegionManager"), resolver(classes)).isEmpty(),
					"a half-fitting cross-mod mixin is kept, like every PARTIAL");

			List<ForeignMixinBreaks.Break> breaks = ForeignMixinBreaks.all();
			assertEquals(1, breaks.size(), "the dependency dialog's mixin section is fed");
			assertEquals("iris.mixins.json", breaks.get(0).config());
			assertEquals("MixinRenderRegionManager", breaks.get(0).mixin());
			assertTrue(breaks.get(0).anchors().stream().anyMatch(a -> a.contains("methodThatNoLongerExists")),
					breaks.get(0).anchors().toString());

			assertEquals(1, ModCatalog.failures().size(), "the Mods screen names the mod");
			assertEquals(ModCatalog.Status.DEGRADED, ModCatalog.failures().get(0).status());
			assertTrue(ModCatalog.failures().get(0).statusDetail().contains("another mod's class"),
					ModCatalog.failures().get(0).statusDetail());

			var finding = net.forbric.api.CompatibilityFindings.all().stream()
					.filter(f -> f.id().equals(MixinCompatibility.id("iris.mixins.json",
							PKG.replace('/', '.') + ".MixinRenderRegionManager"))).findFirst().orElseThrow();
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, finding.confidence());
			assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty(),
					"a preflight suspicion never asks the player to continue or quit");
		} finally {
			ForeignMixinBreaks.reset();
			MixinConfigOwners.reset();
			ModCatalog.publish(previous);
		}
	}

	/**
	 * A mixin on {@code ByteBufCodecs$15}, which the merge renumbered into three candidates, so nothing can move it.
	 * Every handler then binds — to the unrelated class that now carries that name — and the final-class check
	 * sees all of them attached. That is the evidence for "the anchors resolved", which is all it may discharge;
	 * the drift is a question about WHICH class, and it has to survive the attachment.
	 */
	@Test
	void aDriftedTargetIsNotDischargedByItsHandlersAttaching() {
		MixinCompatibility.reset();
		try {
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("drift.mixins.json", "driftmod", Ecosystem.FABRIC)));
			String drifted = "net/minecraft/network/codec/ByteBufCodecs$15";
			String mixinClass = PKG.replace('/', '.') + ".CodecMixin";
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(drifted + ".class", target(drifted, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(PKG + "/CodecMixin.class", shadowingMixin("CodecMixin", drifted, "unused"));
			byte[] cfg = ("{\"required\":true,\"package\":\"" + PKG.replace('/', '.') + "\",\"mixins\":[\"CodecMixin\"],"
					+ "\"injectors\":{\"defaultRequire\":1}}").getBytes(StandardCharsets.UTF_8);

			MixinCompatibility.rememberOriginalConfig("drift.mixins.json", cfg);
			assertTrue(KernelGuestMixinAdapter.unfitMixins("drift.mixins.json", cfg, resolver(classes)).isEmpty(),
					"a drifted target is PARTIAL and kept");

			org.objectweb.asm.tree.ClassNode mixin = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(classes.get(PKG + "/CodecMixin.class")).accept(mixin, 0);
			FinalMixinApplications.remember(mixin);
			// The final class: the merged handler, and the call the injector made to it.
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, drifted, null, "java/lang/Object", null);
			MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler$000$onRender", "()V", null, null);
			AnnotationVisitor merged = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", true);
			merged.visit("mixin", mixinClass);
			merged.visitEnd();
			handler.visitCode();
			handler.visitInsn(Opcodes.RETURN);
			handler.visitMaxs(0, 1);
			handler.visitEnd();
			MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
			render.visitCode();
			render.visitVarInsn(Opcodes.ALOAD, 0);
			render.visitMethodInsn(Opcodes.INVOKESPECIAL, drifted, "handler$000$onRender", "()V", false);
			render.visitInsn(Opcodes.RETURN);
			render.visitMaxs(1, 1);
			render.visitEnd();
			cw.visitEnd();
			FinalMixinApplications.observe(drifted.replace('/', '.'), cw.toByteArray(),
					(m, name, desc) -> List.of(new FinalMixinApplications.Renamed("handler$000$" + name, desc)));

			var findings = net.forbric.api.CompatibilityFindings.all();
			var whole = findings.stream().filter(f -> f.id().equals(MixinCompatibility.id("drift.mixins.json", mixinClass)))
					.findFirst().orElseThrow();
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.RESOLVED, whole.confidence(),
					"every anchor attached, and that part of the suspicion is answered");
			var drift = findings.stream().filter(f -> f.id().equals(MixinCompatibility.driftId("drift.mixins.json", mixinClass)))
					.findFirst().orElseThrow(() -> new AssertionError("no drift row survived: " + findings));
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, drift.confidence(),
					"attachment cannot say the handlers bound to the class vanilla compiled at that name");
			assertTrue(drift.evidence().stream().anyMatch(e -> e.contains("ByteBufCodecs$15")), drift.evidence().toString());
			assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
		} finally {
			MixinCompatibility.reset();
			MixinConfigOwners.reset();
		}
	}

	@Test
	void aConfigWithAPluginHoldsTheMarkBackUntilThePluginIsAsked() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			PluginDeclinedMixins.reset();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("p.mixins.json", "pmod", Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "pmod", "P", "1", "", List.of(), "p.jar", "", "")));
			String t = "net/minecraft/client/renderer/GameRenderer";
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(PKG + "/Dangling.class", danglingMixin("Dangling", t));

			// Same suppression as the test above; the only difference is that this config declares a plugin, and
			// the plugin is the one party that knows whether the mod wanted this mixin here at all.
			assertEquals(List.of("Dangling"), KernelGuestMixinAdapter.unfitMixins("p.mixins.json",
					configWithPlugin(PKG.replace('/', '.'), "com.example.ExamplePlugin", "Dangling"),
					resolver(classes)));
			assertTrue(ModCatalog.failures().isEmpty(), "the mark waits for the plugin's answer");
			assertEquals(1, PluginDeclinedMixins.pending());

			// No plugin instance was ever built, so there is no answer — and no answer marks the mod.
			PluginDeclinedMixins.resolve();
			assertEquals(1, ModCatalog.failures().size());
			assertTrue(ModCatalog.failures().get(0).statusDetail().contains("Dangling"));
		} finally {
			PluginDeclinedMixins.reset();
			MixinConfigOwners.reset();
			ModCatalog.publish(previous);
		}
	}

	/** Declines the mixin for the first target only, the way a plugin reading {@code targetClassName} can. */
	public static final class FirstTargetDecliningPlugin {
		public boolean shouldApplyMixin(String target, String mixin) {
			return !"net.minecraft.client.renderer.GameRenderer".equals(target);
		}
	}

	/** The kernel removes a mixin from EVERY target, so the plugin must be asked about every target too. */
	@Test
	void aSuppressionIsSettledAgainstEveryTargetNotJustTheFirst() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			PluginDeclinedMixins.reset();
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("p.mixins.json", "pmod", Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "pmod", "P", "1", "", List.of(), "p.jar", "", "")));
			String first = "net/minecraft/client/renderer/GameRenderer";
			String second = "net/minecraft/client/renderer/LevelRenderer";
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(first + ".class", target(first, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(second + ".class", target(second, "unused", Opcodes.ACC_PRIVATE, true));
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PKG + "/Both", null, "java/lang/Object", null);
			AnnotationVisitor at = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
			AnnotationVisitor value = at.visitArray("value");
			value.visit(null, Type.getObjectType(first));
			value.visit(null, Type.getObjectType(second));
			value.visitEnd();
			at.visitEnd();
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
			classes.put(PKG + "/Both.class", cw.toByteArray());

			assertEquals(List.of("Both"), KernelGuestMixinAdapter.unfitMixins("p.mixins.json",
					configWithPlugin(PKG.replace('/', '.'), FirstTargetDecliningPlugin.class.getName(), "Both"),
					resolver(classes)));
			PluginDeclinedMixins.rememberPlugin(new FirstTargetDecliningPlugin());
			PluginDeclinedMixins.resolve();

			assertEquals(1, ModCatalog.failures().size(),
					"the plugin would have applied the mixin to LevelRenderer, so leaving it out there is a loss");
		} finally {
			PluginDeclinedMixins.reset();
			MixinConfigOwners.reset();
			ModCatalog.publish(previous);
		}
	}

	@Test
	void anUnownedConfigMarksNobody() {
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		try {
			MixinConfigOwners.reset();
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "xmod", "X", "1", "", List.of(), "x.jar", "", "")));
			String t = "net/minecraft/client/renderer/GameRenderer";
			Map<String, byte[]> classes = new HashMap<>();
			classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
			classes.put(PKG + "/Dangling.class", danglingMixin("Dangling", t));

			assertEquals(List.of("Dangling"), KernelGuestMixinAdapter.unfitMixins("x.mixins.json",
					config(PKG.replace('/', '.'), "Dangling"), resolver(classes)));
			assertTrue(ModCatalog.failures().isEmpty(), "a config with no single owner names no mod: a wrong name is worse than none");
		} finally {
			ModCatalog.publish(previous);
		}
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

	/** The dependent half goes because the kernel dropped its sibling: confirmed not to run, recorded as such. */
	@Test
	void theDependentHalfOfACastContractIsAConfirmedFinding() {
		String gui = "net/minecraft/client/gui/render/SomeGuiThing";
		String game = "net/minecraft/client/renderer/GameRenderer";
		String contract = "net/fabricmc/fabric/impl/client/rendering/GuiRendererExtensions";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(gui + ".class", target(gui, "orphanedRenderers", Opcodes.ACC_PRIVATE, false));
		classes.put(game + ".class", target(game, "unused", Opcodes.ACC_PRIVATE, true));
		classes.put(PKG + "/GuiRendererMixin.class",
				shadowingMixin("GuiRendererMixin", gui, "orphanedRenderers", contract));
		classes.put(PKG + "/GameRendererMixin.class", castingMixin("GameRendererMixin", game, contract));

		KernelGuestMixinAdapter.unfitMixins("example.mixins.json",
				config(PKG.replace('/', '.'), "GuiRendererMixin", "GameRendererMixin"), resolver(classes));

		var dependent = net.forbric.api.CompatibilityFindings.all().stream()
				.filter(f -> f.id().equals(MixinCompatibility.id("example.mixins.json", PKG.replace('/', '.') + ".GameRendererMixin")))
				.findFirst().orElseThrow(() -> new AssertionError("the cascade left no finding: "
						+ net.forbric.api.CompatibilityFindings.all()));
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, dependent.confidence());
		assertFalse(dependent.required(), "the sibling's own row carries the necessity");
		assertTrue(dependent.evidence().stream().anyMatch(e -> e.contains("GuiRendererExtensions")), dependent.evidence().toString());
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
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("example.mixins.json", "halfmod", Ecosystem.FABRIC)));
		String t = "net/minecraft/client/renderer/GameRenderer";
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(t + ".class", target(t, "unused", Opcodes.ACC_PRIVATE, true));
		// One anchor resolves (render), one does not (methodThatNoLongerExists) → PARTIAL.
		classes.put(PKG + "/HalfMixin.class", halfMixin("HalfMixin", t));
		byte[] cfg = config(PKG.replace('/', '.'), "HalfMixin");

		assertTrue(KernelGuestMixinAdapter.unfitMixins("example.mixins.json", cfg, resolver(classes)).isEmpty(),
				"PARTIAL is kept by default so the change stays monotonic against the old rule");
		var finding = net.forbric.api.CompatibilityFindings.all().getFirst();
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, finding.confidence());
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());

		System.setProperty("forbric.mixinFit", "strict");
		try {
			assertFalse(KernelGuestMixinAdapter.unfitMixins("example.mixins.json", cfg, resolver(classes)).isEmpty(),
					"-Dforbric.mixinFit=strict opts into dropping half-applied mixins");
		} finally {
			System.clearProperty("forbric.mixinFit");
			MixinConfigOwners.reset();
		}
	}
}
