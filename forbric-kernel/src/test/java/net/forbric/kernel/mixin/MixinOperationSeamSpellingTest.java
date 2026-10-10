/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * {@link MixinOperationSeamTransport} reads a callback's host as Mixin binds the selector, and the call it wraps as Mixin
 * resolves the target in the method the mod was compiled against, then reads that one member everywhere after — never
 * the target's spelling. fabric-api's screen-draw wrap under other class and handler names, its selector written with a
 * dotted owner, an owner prefix, whitespace or two spellings, and its target with whitespace, a dotted owner, and — where
 * vanilla's {@code Gui.extractRenderState} decides it — without the owner or the descriptor: each is carried to the
 * carrier's gateway exactly as the released spelling is. A shortened target that also selects another owner's or
 * overload's call natively, or any call left in the merged method, a target naming another owner, and a selector
 * binding another method or two leave the mixin exactly as written.
 */
@ResourceLock("system-properties")
class MixinOperationSeamSpellingTest {
	private static final String GUI = "net/minecraft/client/gui/Gui";
	private static final String HOST = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V";
	private static final String SCREEN_DRAW = "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
	private static final String GATEWAY_DRAW = "Lnet/minecraft/client/gui/Gui;drawScreen(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

	private final List<ForbricClassLoader> loaders = new ArrayList<>();

	@AfterEach void close() throws Exception {
		for (ForbricClassLoader loader : loaders) {
			MixinOperationSeamTransport.release(loader);
			loader.close();
		}
		MixinStubRebind.forget();
	}

	private record Draw(ClassNode mixin, MethodNode handler, ClassNode merged, BiFunction<Ecosystem, String, ClassNode> natives) {
		ClassNode nativeGui() { return natives.apply(Ecosystem.FABRIC, GUI); }
		boolean which(MethodNode method) { return method == handler; }
	}

	@Test void theReleasedWrapUnderOtherNamesIsCarried() throws Exception {
		assertCarried(draw());
	}

	@TestFactory Stream<DynamicTest> theSelectorWrittenAnotherWayIsCarried() {
		Map<String, BiFunction<String, String, List<String>>> forms = new LinkedHashMap<>(PointRespelling.SELECTORS);
		forms.put("owner prefix with whitespace", (owner, s) -> List.of(" L" + owner + "; " + s + " "));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Draw draw = draw();
			assertEquals(1, PointRespelling.selectors(draw.mixin(), draw::which, form.getValue()), "premise");
			assertCarried(draw);
		}));
	}

	@TestFactory Stream<DynamicTest> theTargetWrittenAnotherWayIsCarried() {
		return PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(form.id(), () -> {
			Draw draw = draw();
			ClassNode natives = draw.nativeGui();
			assertEquals(1, PointRespelling.points(draw.mixin(), draw::which, form, m -> PointRespelling.bound(m, natives)), "premise");
			assertCarried(draw);
		}));
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	@TestFactory Stream<DynamicTest> aShortTargetThatAlsoSelectsAnotherCallIsLeftAlone() {
		List<DynamicTest> tests = new ArrayList<>();
		for (boolean otherOwner : new boolean[] { true, false }) for (boolean nativeSide : new boolean[] { true, false }) {
			String crowd = (otherOwner ? "another owner" : "another overload") + (nativeSide ? " natively" : " in the merged method");
			if (nativeSide) tests.add(DynamicTest.dynamicTest(crowd + " / released spelling still carried", () -> assertCarried(crowded(draw(), otherOwner, true))));
			for (PointRespelling.Form form : PointRespelling.READ_IN_THE_WRITTEN_METHOD) {
				if (otherOwner ? !form.dropsOwner() : !form.dropsDesc()) continue;
				tests.add(DynamicTest.dynamicTest(crowd + " / " + form.id(), () -> {
					Draw plain = draw(), draw = crowded(plain, otherOwner, nativeSide);
					ClassNode natives = plain.nativeGui();
					assertEquals(1, PointRespelling.points(draw.mixin(), draw::which, form, m -> PointRespelling.bound(m, natives)), "premise");
					assertLeftAlone(draw);
				}));
			}
		}
		return tests.stream();
	}

	@Test void aTargetNamingAnotherOwnerIsLeftAlone() throws Exception {
		for (String target : List.of("Lnet/minecraft/client/gui/screens/Overlay;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
				"org.example.elsewhere.Panel.extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")) {
			Draw draw = draw();
			MixinPlayerWorldCallbackAdapter.set(MixinFit.atNodes(MixinFit.injectorOf(draw.handler())).getFirst(), "target", target);
			assertLeftAlone(draw);
		}
	}

	@Test void aSelectorBindingAnotherMethodOrTwoIsLeftAlone() throws Exception {
		for (List<String> selectors : List.of(List.of("net.minecraft.client.gui.Gui.tick()V"), List.of("extractRenderState", "tick"),
				List.of("Lnet/minecraft/client/gui/Hud;extractRenderState"))) {
			Draw draw = draw();
			MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(draw.handler()), "method", new ArrayList<>(selectors));
			assertLeftAlone(draw);
		}
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private int adapt(Draw draw) {
		ForbricClassLoader loader = new ForbricClassLoader(new java.net.URL[0], getClass().getClassLoader());
		loaders.add(loader);
		return MixinOperationSeamTransport.adapt(draw.mixin(), name -> name.equals(GUI) ? draw.merged() : CarpetMixinAdapterTest.target(name), draw.natives(), loader);
	}

	private void assertCarried(Draw draw) throws Exception {
		String body = MixinInstructionFingerprint.hash(draw.handler());
		assertEquals(1, adapt(draw));
		MethodNode kept = draw.mixin().methods.stream().filter(m -> m.name.contains(draw.handler().name) && m.desc.equals(draw.handler().desc)
				&& MixinFit.injectorOf(m) == null).findFirst().orElseThrow();
		assertEquals(body, MixinInstructionFingerprint.hash(kept), "the wrap's own body, kept on its host");
		MethodNode moved = draw.mixin().methods.stream().filter(m -> m.name.endsWith("$invoke$scope") && MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
		assertEquals(List.of(HOST), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(moved), "method")));
		assertEquals(GATEWAY_DRAW, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(moved)).getFirst(), "target"));
		CarpetMixinAdapterTest.verify(draw.mixin());
	}

	private void assertLeftAlone(Draw draw) {
		byte[] before = CarpetMixinAdapterTest.bytes(draw.mixin());
		assertEquals(0, adapt(draw));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(draw.mixin()), "the mixin was rewritten");
	}

	/**
	 * {@code draw} with the native ({@code nativeSide}) or merged {@code extractRenderState} crowded by
	 * {@link PointRespelling#crowd}, which reads the point as released: crowd before respelling it.
	 */
	private static Draw crowded(Draw draw, boolean otherOwner, boolean nativeSide) {
		ClassNode copy = PointRespelling.copy(nativeSide ? draw.nativeGui() : draw.merged());
		assertEquals(1, PointRespelling.crowd(draw.mixin(), draw::which, copy, otherOwner), "premise: crowded");
		if (!nativeSide) return new Draw(draw.mixin(), draw.handler(), copy, draw.natives());
		// Only the class the mod was compiled against: the carriers' own Gui is how the seam proves the gateway's operands.
		BiFunction<Ecosystem, String, ClassNode> natives = draw.natives();
		return new Draw(draw.mixin(), draw.handler(), draw.merged(), (family, owner) -> family == Ecosystem.FABRIC && owner.equals(GUI) ? copy : natives.apply(family, owner));
	}

	/** fabric-api's screen mixin under other names, its screen-draw wrap, the merged Gui and the staged native classes. */
	private static Draw draw() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(StagedFabricMixinFixture.mixin("fabric-screen-api-v1", "net/fabricmc/fabric/mixin/screen/GuiMixin"));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = null;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null && MixinFit.atNodes(injector).stream().anyMatch(at -> SCREEN_DRAW.equals(MixinFit.value(at, "target")))) {
				assertNull(handler, "one wrap of the screen draw");
				handler = method;
			}
		}
		assertNotNull(handler, "the screen-draw wrap");
		assertTrue(handler.name.startsWith("respelled"), "renamed");
		BiFunction<Ecosystem, String, ClassNode> natives = NativeCallTestEvidence.staged();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED, natives.apply(Ecosystem.FABRIC, GUI) != null, "Gui as the mod was compiled against it");
		return new Draw(mixin, handler, StagedFabricMixinFixture.game(GUI, false), natives);
	}
}
