/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link MixinStubRebind} reads which carrier stub an injector sits on, and whether its selector is a name alone or carries
 * the descriptor, as Mixin binds the selector — not as it is spelled. fabric-api's elytra check (a bare {@code canGlide},
 * which binds the merged {@code LivingEntity}'s forwarding stub, the first declared) under other class and handler names,
 * its selector written with a dotted owner, an owner prefix with whitespace, a descriptor the mod did not write: each
 * moves onto the body exactly as the released spelling does. A mod of either Forge family moves along a row by its
 * selector's form, read the same way. owo's {@code @Share} trio, each sharer spelled differently, still moves as one group.
 * A selector naming another owner, binding the body itself, or a bare name the merged class binds to the body (declared
 * first) stays exactly where it is.
 */
@ResourceLock("system-properties")
@ResourceLock("ModCatalog")
class MixinStubRebindSpellingTest {
	private static final Path MERGED = TestFixtures.stagedBase();
	private static final Path OWO = Path.of("build/compat-inputs/sweep90/mods/owo-lib-0.13.1+26.2.jar");
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String BODY = "canGlide(Z)Z";
	private static final String LANGUAGE = "net/minecraft/locale/Language";
	private static final String STUB_JSON = "loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;)V";
	private static final String BODY_JSON = "loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;)V";
	private static final String DETECTOR = "net/minecraft/server/packs/repository/PackDetector";
	private static final String DETECT_STUB = "detectPackResources(Ljava/nio/file/Path;Ljava/util/List;)Ljava/lang/Object;";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	@AfterEach void reset() {
		MixinStubRebind.forget();
	}

	// ---- fabric-api's elytra check ------------------------------------------------------------------------------------

	/** The released check under other names: the baseline the spellings are measured against. */
	@Test void theReleasedCheckUnderOtherNamesMovesOntoTheBody() throws Exception {
		Elytra elytra = elytra();
		assertEquals(1, MixinStubRebind.adapt(elytra.mixin(), name -> elytra.living()));
		assertEquals(List.of(BODY), selectors(elytra.check()));
	}

	@TestFactory Stream<DynamicTest> theCheckWrittenAnotherWayMovesOntoTheBody() {
		Map<String, String> forms = new LinkedHashMap<>();
		forms.put("dotted owner", "net.minecraft.world.entity.LivingEntity.canGlide");
		forms.put("owner prefix with whitespace", " L" + LIVING + "; canGlide ");
		forms.put("owner prefix", "L" + LIVING + ";canGlide");
		forms.put("the stub's descriptor, dotted", "net.minecraft.world.entity.LivingEntity.canGlide()Z");
		forms.put("the stub's descriptor, with whitespace", "canGlide ()Z");
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Elytra elytra = elytra();
			select(elytra.check(), form.getValue());
			assertEquals(1, MixinStubRebind.adapt(elytra.mixin(), name -> elytra.living()), form.getValue());
			assertEquals(List.of(BODY), selectors(elytra.check()), form.getValue());
			assertEquals(0, MixinStubRebind.adapt(elytra.mixin(), name -> elytra.living()), "a second pass changes nothing");
		}));
	}

	/** Another owner's {@code canGlide}: Mixin does not bind it on LivingEntity, so nothing here may move it there. */
	@Test void aSelectorNamingAnotherOwnerStays() throws Exception {
		for (String selector : List.of("Lnet/minecraft/world/entity/player/Player;canGlide", "org.example.elsewhere.Glider.canGlide()Z")) {
			Elytra elytra = elytra();
			select(elytra.check(), selector);
			assertLeftAlone(elytra, selector);
		}
	}

	/** A selector binding the body itself, or binding both overloads, has no stub to leave. */
	@Test void aSelectorBindingAnotherMethodStays() throws Exception {
		for (String selector : List.of("net.minecraft.world.entity.LivingEntity.canGlide(Z)Z", "canGlide*", "updateFallFlying()V")) {
			Elytra elytra = elytra();
			select(elytra.check(), selector);
			assertLeftAlone(elytra, selector);
		}
	}

	/** A merged class that declares the body first: a bare name, however written, binds the body, and stays. */
	@Test void aBareNameTheMergedClassBindsToTheBodyStays() throws Exception {
		for (String selector : List.of("canGlide", "net.minecraft.world.entity.LivingEntity.canGlide", " L" + LIVING + "; canGlide")) {
			Elytra elytra = elytra();
			MethodNode body = elytra.living().methods.stream().filter(m -> (m.name + m.desc).equals(BODY)).findFirst().orElseThrow();
			elytra.living().methods.remove(body);
			elytra.living().methods.addFirst(body);
			select(elytra.check(), selector);
			assertLeftAlone(elytra, selector);
		}
	}

	// ---- a row's selector form, per family ----------------------------------------------------------------------------

	/**
	 * PackDetector's row moves a NeoForge mod's injector by its selector's form: each form written another way moves (or
	 * stays) exactly as its plain spelling does, for every family.
	 */
	@Test void aRowReadsTheSelectorsFormHoweverItIsWritten() throws Exception {
		ClassNode detector = merged(DETECTOR);
		String dotted = DETECTOR.replace('/', '.') + ".";
		Map<String, List<String>> forms = new LinkedHashMap<>();
		forms.put("detectPackResources", List.of(dotted + "detectPackResources", " L" + DETECTOR + "; detectPackResources"));
		forms.put(DETECT_STUB, List.of(dotted + DETECT_STUB, "L" + DETECTOR + "; detectPackResources ( Ljava/nio/file/Path;Ljava/util/List; ) Ljava/lang/Object;"));
		int moved = 0;
		for (Ecosystem ecosystem : List.of(Ecosystem.FABRIC, Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
			for (var form : forms.entrySet()) {
				int plain = detect(detector, ecosystem, form.getKey());
				moved += plain;
				for (String respelled : form.getValue())
					assertEquals(plain, detect(detector, ecosystem, respelled), ecosystem + ": " + respelled + " moves as " + form.getKey() + " does");
			}
		}
		assertTrue(moved > 0, "premise: the row moves some family's selector");
		assertEquals(1, detect(detector, Ecosystem.NEOFORGE, dotted + "detectPackResources"), "premise: a NeoForge mod's bare name moves");
	}

	private static int detect(ClassNode detector, Ecosystem ecosystem, String selector) {
		ClassNode mixin = synthetic("org/example/elsewhere/DetectorHook", DETECTOR, "observe", "(" + CIR + ")V", selector);
		MixinStubRebind.noteEcosystem(mixin.name, ecosystem);
		ClassNode copy = PointRespelling.copy(detector);
		return MixinStubRebind.adapt(mixin, name -> copy);
	}

	// ---- owo's share group ----------------------------------------------------------------------------------------------

	/**
	 * owo's three lang hooks share flags, so they move together or not at all. Each written another way they are still
	 * the same group on the same stub: all three move, none is left holding a value of its own.
	 */
	@Test void aShareGroupSpelledThreeWaysMovesWhole() throws Exception {
		ClassNode language = merged(LANGUAGE);
		ClassNode mixin = owo();
		String dotted = LANGUAGE.replace('/', '.') + ".";
		select(handler(mixin, "deNestNestedKeys"), dotted + STUB_JSON);
		select(handler(mixin, "handleRichTranslationsAndErrors"), " L" + LANGUAGE + "; " + STUB_JSON);
		select(handler(mixin, "doSkip"), "L" + LANGUAGE + ";loadFromJson ( Ljava/io/InputStream;Ljava/util/function/BiConsumer; ) V");
		assertEquals(3, MixinStubRebind.adapt(mixin, name -> language));
		for (String hook : List.of("deNestNestedKeys", "handleRichTranslationsAndErrors", "doSkip"))
			assertEquals(List.of(BODY_JSON), selectors(handler(mixin, hook)), hook);
	}

	/**
	 * A sharer already written for the body is not on the stub: it shares nothing there, and the other two join it. A
	 * sharer naming another owner cannot be read as bound to Language, and holds its group where it is.
	 */
	@Test void aSharerOnAnotherMethodOrUnreadableDecidesTheGroupByWhatItBinds() throws Exception {
		ClassNode language = merged(LANGUAGE);
		ClassNode onBody = owo();
		select(handler(onBody, "doSkip"), LANGUAGE.replace('/', '.') + "." + BODY_JSON);
		assertEquals(2, MixinStubRebind.adapt(onBody, name -> language));
		for (String hook : List.of("deNestNestedKeys", "handleRichTranslationsAndErrors", "doSkip"))
			assertEquals(BODY_JSON, MixinTargetSelectors.one(handler(onBody, hook), language).name + MixinTargetSelectors.one(handler(onBody, hook), language).desc, hook);

		ClassNode elsewhere = owo();
		select(handler(elsewhere, "doSkip"), "Lorg/example/elsewhere/Language;" + STUB_JSON);
		assertEquals(0, MixinStubRebind.adapt(elsewhere, name -> language));
		for (String hook : List.of("deNestNestedKeys", "handleRichTranslationsAndErrors"))
			assertEquals(List.of(STUB_JSON), selectors(handler(elsewhere, hook)), hook);
	}

	// ---- reading --------------------------------------------------------------------------------------------------------

	private record Elytra(ClassNode mixin, MethodNode check, ClassNode living) { }

	/** fabric-api's elytra mixin under other names, its {@code canGlide} check found by its point, and the merged class. */
	private static Elytra elytra() throws Exception {
		ClassNode released = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/elytra/LivingEntityMixin");
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(released);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode check = null;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector != null && List.of("canGlide").equals(MixinFit.stringList(MixinFit.value(injector, "method")))) {
				assertNull(check, "one canGlide check");
				check = method;
			}
		}
		assertNotNull(check, "the canGlide check");
		assertTrue(check.name.startsWith("respelled"), "renamed");
		return new Elytra(mixin, check, merged(LIVING));
	}

	private static void assertLeftAlone(Elytra elytra, String selector) {
		byte[] before = CarpetMixinAdapterTest.bytes(elytra.mixin());
		assertEquals(0, MixinStubRebind.adapt(elytra.mixin(), name -> elytra.living()), selector);
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(elytra.mixin()), selector + ": the mixin was rewritten");
	}

	private static ClassNode owo() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(OWO), OWO + " absent");
		ClassNode mixin;
		try (ZipFile zip = new ZipFile(OWO.toFile())) {
			mixin = MixinFit.parse(zip.getInputStream(zip.getEntry("io/wispforest/owo/mixin/text/LanguageMixin.class")).readAllBytes());
		}
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static ClassNode merged(String name) throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "actual game required");
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes());
		}
	}

	private static ClassNode synthetic(String name, String target, String handlerName, String desc, String selector) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, handlerName, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	private static MethodNode handler(ClassNode mixin, String name) {
		return mixin.methods.stream().filter(m -> m.name.equals(name) && MixinFit.injectorOf(m) != null).findFirst().orElseThrow();
	}

	private static void select(MethodNode handler, String selector) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(List.of(selector)));
	}

	private static List<String> selectors(MethodNode handler) {
		return MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"));
	}
}
