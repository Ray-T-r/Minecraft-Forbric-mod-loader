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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Explaining a mixin failure whose reason is two files away.
 *
 * <p>Apoli's sky-skip targets {@code lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)} — vanilla's shape, and
 * the one MinecraftForge's base also has. The merge kept NeoForge's body of {@code addSkyPass}, whose lambda is
 * {@code (SkyRenderState, Matrix4fc, GpuBufferSlice)}, and the duplicate-lambda pruner then dropped the vanilla
 * chain because binding a mixin into dead code is worse than not binding. All Mixin can say after that is
 * "Invalid descriptor", which is true and tells nobody anything.
 *
 * <p>The diagnosis must be all-or-nothing: said only when the shape the handler fits really was dropped from
 * that class, and never for a selector that binds. It changes no bytes — asserted here, because a lambda's name is a
 * counter, and repointing it would move an injection somewhere the mod did not ask for, silently.
 *
 * <p>The pin (issue #56) is the other half: a bare name Mixin would bind to the other ecosystem's overload, declared
 * first, is pinned to the one overload its handler was written for — only when the first cannot take the handler, and
 * only when exactly one other can.
 */
@org.junit.jupiter.api.extension.ExtendWith(net.forbric.kernel.DroppedLambdaRecordRestore.class)
class MixinOverloadPinTest {
	private static final String TARGET = "net/minecraft/client/renderer/LevelRenderer";
	private static final String SLICE = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
	private static final String SKY = "Lnet/minecraft/client/renderer/state/level/SkyRenderState;";
	private static final String MATRIX = "Lorg/joml/Matrix4fc;";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String NAME = "lambda$addSkyPass$0";
	private static final String VANILLA_SHAPE = "(" + SLICE + SKY + ")V";
	private static final String MIXIN = "com/example/WorldRendererMixin";

	private static final String SERVER = "net/minecraft/server/MinecraftServer";
	private static final String CLICK = "handleCustomClickAction";
	private static final String ID = "Lnet/minecraft/resources/Identifier;";
	private static final String OPTIONAL = "Ljava/util/Optional;";
	/** NeoForge's overload, declared first on the merged base: fire the event, then call vanilla's. */
	private static final String CARRIER_CLICK = "(" + ID + OPTIONAL + "Lnet/minecraft/server/level/ServerPlayer;"
			+ "Lcom/mojang/authlib/GameProfile;)V";
	private static final String VANILLA_CLICK = "(" + ID + OPTIONAL + ")V";

	@BeforeEach
	void forgetEarlierDiagnoses() {
		MixinOverloadPin.clearReasons();
	}

	@Test
	void aSelectorWhoseShapeTheMergeDroppedIsExplained() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		byte[] before = selectorsOf(mixin).toString().getBytes();

		assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())), "a lambda is never pinned");
		assertEquals("its skipSkyRenderingForPhasingBlindness targets lambda$addSkyPass$0, a lambda of the addSkyPass "
				+ "body the byte merge did not keep", MixinOverloadPin.reasonFor(MIXIN),
				"the handler fits a shape this class no longer declares, and the pruner is why");
		assertEquals(new String(before), selectorsOf(mixin).toString(),
				"the diagnosis changes nothing: repointing the selector would move the injection somewhere the "
						+ "mod did not ask for");
	}

	/** A selector that binds is not a problem, so there is nothing to say about it. */
	@Test
	void aSelectorThatBindsIsNotExplained() {
		ClassNode mixin = mixinWith("(" + SKY + MATRIX + SLICE + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
		assertNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	/**
	 * A selector that binds is not explained EVEN IF a same-named body was dropped that it would also have fit.
	 *
	 * <p>The pruner drops a body per name, and a class can lose one that looks like the one it kept. Without the
	 * short-circuit on a binding target, every such mixin would be told its injection has no live target while it
	 * is running perfectly well — a false diagnosis is worse than none, because it sends the reader somewhere
	 * there is nothing to find.
	 */
	@Test
	void aSelectorThatBindsIsNotExplainedEvenWhenItsShapeWasAlsoDropped() {
		ClassNode target = neoForgeLambdaOnly();
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(
				TARGET, NAME, "(" + SKY + MATRIX + SLICE + ")V");

		ClassNode mixin = mixinWith("(" + SKY + MATRIX + SLICE + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(target)));
		assertNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	/**
	 * A handler that fits neither what is there NOR what was dropped is a mod targeting something this game never
	 * had. Saying "the merge dropped it" would be a guess wearing a fact's clothes.
	 */
	@Test
	void aHandlerThatFitsNothingIsNotExplained() {
		ClassNode mixin = mixinWith("(Ljava/lang/String;" + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
		assertNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	@Test
	void aSelectorTheClassDoesNotDeclareAtAllIsNotExplained() {
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>();
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(target)));
		assertNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	@Test
	void anExplicitSelectorIsNeverLookedAt() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME + VANILLA_SHAPE);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
		assertNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	@Test
	void aTargetThatCannotBeReadSaysNothing() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, name -> null));
		assertNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	@Test
	void theHandlerRuleIsTheTargetsParametersThenACallback() {
		assertTrue(MixinOverloadPin.fits("(" + SLICE + SKY + CI + ")V", VANILLA_SHAPE));
		assertTrue(MixinOverloadPin.fits("(" + SLICE + SKY + CI + "I)V", VANILLA_SHAPE),
				"trailing captured locals are allowed after the callback");
		assertFalse(MixinOverloadPin.fits("(" + SLICE + CI + ")V", VANILLA_SHAPE),
				"a handler taking only a prefix is not judged to fit: it would fit several shapes at once");
		assertFalse(MixinOverloadPin.fits("(" + SLICE + SKY + ")V", VANILLA_SHAPE),
				"no callback parameter at all is not an @Inject handler shape");
	}

	@Test
	void theEnclosingMethodIsReadOffTheLambdaName() {
		assertEquals("addSkyPass", MixinOverloadPin.enclosing("lambda$addSkyPass$0"));
		assertEquals("load", MixinOverloadPin.enclosing("lambda$load$12"));
		assertEquals("ordinary", MixinOverloadPin.enclosing("ordinary"));
	}

	@Test
	void theSwitchIsOnByDefaultAndOffSaysNothing() {
		String previous = System.getProperty(MixinOverloadPin.PROPERTY);
		try {
			System.clearProperty(MixinOverloadPin.PROPERTY);
			assertTrue(MixinOverloadPin.enabled());

			System.setProperty(MixinOverloadPin.PROPERTY, "off");
			ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
			assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
			assertNull(MixinOverloadPin.reasonFor(MIXIN));
			ClassNode carpet = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
			assertEquals(0, MixinOverloadPin.pin(carpet, targets(mergedServer())), "off pins nothing either");
			assertEquals(List.of(CLICK), selectorsOf(carpet));
		} finally {
			if (previous == null) System.clearProperty(MixinOverloadPin.PROPERTY);
			else System.setProperty(MixinOverloadPin.PROPERTY, previous);
		}
	}

	/** Both ways of naming a target are read: a class literal and a {@code targets = "…"} string. */
	@Test
	void bothWaysOfNamingATargetAreRead() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		mixin.visibleAnnotations = null;
		AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		annotation.values = new ArrayList<>(List.of("targets",
				new ArrayList<>(List.of(TARGET.replace('/', '.')))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(annotation));

		assertEquals(List.of(TARGET), MixinOverloadPin.targetsOf(mixin));
		MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly()));
		assertNotNull(MixinOverloadPin.reasonFor(MIXIN));
	}

	// --- the pin: overloads the merge put side by side, the other ecosystem's first (issue #56) ---

	@Test
	void aBareNameMixinWouldBindToTheCarriersOverloadIsPinnedToVanillas() {
		ClassNode mixin = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		assertEquals(1, MixinOverloadPin.pin(mixin, targets(mergedServer())));
		assertEquals(List.of(CLICK + VANILLA_CLICK), selectorsOf(mixin));
		assertNull(MixinOverloadPin.reasonFor(mixin.name), "a pinned selector binds: there is nothing to explain");
	}

	/** Mixin's own choice stands whenever it binds, even if another overload would fit "better". */
	@Test
	void aSelectorWhoseFirstOverloadBindsIsNeverMoved() {
		ClassNode written = carpetMixin("(" + ID + OPTIONAL + "Lnet/minecraft/server/level/ServerPlayer;"
				+ "Lcom/mojang/authlib/GameProfile;" + CI + ")V", CLICK);
		assertEquals(0, MixinOverloadPin.pin(written, targets(mergedServer())));
		assertEquals(List.of(CLICK), selectorsOf(written));

		// A handler that asks for no arguments binds every overload, so Mixin's first is what it gets.
		ClassNode bare = carpetMixin("(" + CI + ")V", CLICK);
		assertEquals(0, MixinOverloadPin.pin(bare, targets(mergedServer())));
		assertEquals(List.of(CLICK), selectorsOf(bare));
		assertNull(MixinOverloadPin.reasonFor(bare.name));
	}

	@Test
	void theReplacementMustTakeTheHandlerExactly() {
		// The callback a return type takes: a void overload is no home for a CallbackInfoReturnable handler.
		String cir = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
		ClassNode returnable = carpetMixin("(" + ID + OPTIONAL + cir + ")V", CLICK);
		assertEquals(0, MixinOverloadPin.pin(returnable, targets(mergedServer())));
		assertEquals(List.of(CLICK), selectorsOf(returnable));

		// Static-ness: a static handler is not pinned onto an instance overload.
		ClassNode statik = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		statik.methods.get(0).access |= Opcodes.ACC_STATIC;
		assertEquals(0, MixinOverloadPin.pin(statik, targets(mergedServer())));

		// Captured locals after the callback are the mod's business, and still the same overload.
		ClassNode locals = carpetMixin("(" + ID + OPTIONAL + CI + "I)V", CLICK);
		assertEquals(1, MixinOverloadPin.pin(locals, targets(mergedServer())));
		assertEquals(List.of(CLICK + VANILLA_CLICK), selectorsOf(locals));
	}

	@Test
	void anOverloadNothingFitsIsExplainedNotGuessed() {
		ClassNode server = mergedServer();
		server.methods.remove(1);
		server.methods.add(method(CLICK, "(" + ID + ")V"));
		ClassNode mixin = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(server)));
		assertEquals(List.of(CLICK), selectorsOf(mixin));
		assertNull(MixinOverloadPin.reasonFor(mixin.name), "fitting nothing is not the merge's doing");
	}

	/** Two targets that would pin the same bare name to different overloads: the kernel will not choose for one. */
	@Test
	void targetsThatDisagreeOnTheOverloadLeaveTheSelectorAlone() {
		ClassNode first = mergedServer();
		ClassNode second = mergedServer();
		second.name = "net/minecraft/server/dedicated/DedicatedServer";
		second.methods.get(1).desc = "(" + ID + OPTIONAL + "I)V";
		ClassNode mixin = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		mixin.visibleAnnotations.get(0).values = new ArrayList<>(List.of("value",
				new ArrayList<>(List.of(Type.getObjectType(first.name), Type.getObjectType(second.name)))));
		Map<String, ClassNode> both = Map.of(first.name, first, second.name, second);
		assertEquals(0, MixinOverloadPin.pin(mixin, both::get));
		assertEquals(List.of(CLICK), selectorsOf(mixin));

		// Agreeing targets pin; a target that lacks the name changes nothing either way.
		second.methods.get(1).desc = VANILLA_CLICK;
		ClassNode unrelated = new ClassNode();
		unrelated.name = "net/minecraft/server/Other";
		unrelated.methods = new ArrayList<>();
		mixin.visibleAnnotations.get(0).values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(
				Type.getObjectType(first.name), Type.getObjectType(second.name), Type.getObjectType(unrelated.name)))));
		Map<String, ClassNode> agreeing = Map.of(first.name, first, second.name, second, unrelated.name, unrelated);
		assertEquals(1, MixinOverloadPin.pin(mixin, agreeing::get));
		assertEquals(List.of(CLICK + VANILLA_CLICK), selectorsOf(mixin));
	}

	/**
	 * Only the injector Mixin fails whole is pinned. With several selectors Mixin skips a target that does not fit and
	 * the injection works on the others, so a pin would only add a second one; a {@code target} selector is Mixin's to
	 * resolve; and a {@code @Coerce} parameter takes a supertype, which the binding rule here does not model.
	 */
	@Test
	void anInjectorMixinDoesNotFailWholeIsLeftAlone() {
		ClassNode listed = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		AnnotationNode inject = listed.methods.get(0).visibleAnnotations.get(0);
		inject.values.set(1, new ArrayList<>(List.of(CLICK, "handleSomethingElse")));
		assertEquals(0, MixinOverloadPin.pin(listed, targets(mergedServer())));
		assertEquals(List.of(CLICK, "handleSomethingElse"), selectorsOf(listed));
		assertNull(MixinOverloadPin.destination(listed.methods.get(0), CLICK, mergedServer()));

		ClassNode targeted = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		targeted.methods.get(0).visibleAnnotations.get(0).values.addAll(List.of("target", new ArrayList<>()));
		assertEquals(0, MixinOverloadPin.pin(targeted, targets(mergedServer())));

		ClassNode coerced = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		@SuppressWarnings("unchecked")
		List<AnnotationNode>[] parameters = new List[3];
		parameters[1] = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Coerce;")));
		coerced.methods.get(0).invisibleParameterAnnotations = parameters;
		assertEquals(0, MixinOverloadPin.pin(coerced, targets(mergedServer())));
		assertEquals(List.of(CLICK), selectorsOf(coerced));

		// The single bare selector beside them still is.
		ClassNode single = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		assertEquals(1, MixinOverloadPin.pin(single, targets(mergedServer())));
	}

	@Test
	void whatThePinLeavesIsExplainedOnTheOverloadMixinActuallyPicks() {
		// Whenever the pin does not act (switched off, or targets that disagree), the diagnosis says which overload
		// Mixin binds and which one the handler was written for.
		ClassNode server = mergedServer();
		ClassNode mixin = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		MethodNode handler = mixin.methods.get(0);
		assertTrue(MixinOverloadPin.explain(mixin, handler, CLICK, List.of(SERVER), targets(server)));
		assertEquals("its onClick selects handleCustomClickAction by name, which binds the first of 2 methods the "
				+ "merged class declares with that name, " + CARRIER_CLICK + "; the handler was written for " + VANILLA_CLICK,
				MixinOverloadPin.reasonFor(mixin.name));

		MixinOverloadPin.clearReasons();
		ClassNode written = carpetMixin("(" + ID + OPTIONAL + "Lnet/minecraft/server/level/ServerPlayer;"
				+ "Lcom/mojang/authlib/GameProfile;" + CI + ")V", CLICK);
		assertFalse(MixinOverloadPin.explain(written, written.methods.get(0), CLICK, List.of(SERVER), targets(server)),
				"the first overload binds: the old check said this only when ANY overload fit, which is the #56 miss");
	}

	@Test
	void theDestinationIsWhereMixinFitShouldLook() {
		ClassNode server = mergedServer();
		ClassNode mixin = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		assertSame(server.methods.get(1), MixinOverloadPin.destination(mixin.methods.get(0), CLICK, server));

		ClassNode written = carpetMixin("(" + ID + OPTIONAL + "Lnet/minecraft/server/level/ServerPlayer;"
				+ "Lcom/mojang/authlib/GameProfile;" + CI + ")V", CLICK);
		assertNull(MixinOverloadPin.destination(written.methods.get(0), CLICK, server), "unchanged: Mixin's first");
		assertNull(MixinOverloadPin.destination(mixin.methods.get(0), CLICK + VANILLA_CLICK, server), "explicit already");
		MethodNode notAnInject = method("onClick", "(" + ID + OPTIONAL + CI + ")V");
		assertNull(MixinOverloadPin.destination(notAnInject, CLICK, server), "only @Inject is pinned");
	}

	@Test
	void theRealMergedServerIsPinnedToVanillasOverload() throws Exception {
		java.nio.file.Path merged = net.forbric.kernel.TestFixtures.stagedRoot()
				.resolve(net.forbric.kernel.TestFixtures.BASE_JAR).normalize();
		net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.STAGED, "the merged base", merged);
		ClassNode server = new ClassNode();
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(merged.toFile())) {
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(SERVER + ".class")).readAllBytes())
					.accept(server, org.objectweb.asm.ClassReader.SKIP_CODE);
		}
		List<String> order = server.methods.stream().filter(m -> m.name.equals(CLICK)).map(m -> m.desc).toList();
		assertEquals(List.of(CARRIER_CLICK, VANILLA_CLICK), order, "the shape issue #56 was reported on");

		ClassNode mixin = carpetMixin("(" + ID + OPTIONAL + CI + ")V", CLICK);
		assertEquals(1, MixinOverloadPin.pin(mixin, targets(server)));
		assertEquals(List.of(CLICK + VANILLA_CLICK), selectorsOf(mixin));
	}

	/** The merged {@code MinecraftServer}'s two overloads, in the order the merge left them. */
	private static ClassNode mergedServer() {
		ClassNode target = new ClassNode();
		target.name = SERVER;
		target.methods = new ArrayList<>(List.of(
				new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, CLICK, CARRIER_CLICK, null, null),
				new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, CLICK, VANILLA_CLICK, null, null)));
		return target;
	}

	/** carpet-org-addition's dialog hook: a name-only @Inject on MinecraftServer. */
	private static ClassNode carpetMixin(String handlerDesc, String selector) {
		ClassNode mixin = new ClassNode();
		mixin.name = "boat/carpetorgaddition/mixin/dialog/MinecraftServerMixin";
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		at.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(SERVER)))));
		mixin.visibleAnnotations = new ArrayList<>(List.of(at));
		MethodNode handler = method("onClick", handlerDesc);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	/** What the merged base looks like AFTER the pruner: NeoForge's lambda only, vanilla's recorded as dropped. */
	private static ClassNode neoForgeLambdaOnly() {
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(TARGET, NAME, VANILLA_SHAPE);
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>(List.of(method(NAME, "(" + SKY + MATRIX + SLICE + ")V")));
		return target;
	}

	private static Function<String, ClassNode> targets(ClassNode target) {
		Map<String, ClassNode> byName = Map.of(target.name, target);
		return byName::get;
	}

	private static MethodNode method(String name, String desc) {
		return new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, name, desc, null, null);
	}

	private static ClassNode mixinWith(String handlerDesc, String selector) {
		ClassNode mixin = new ClassNode();
		mixin.name = "com/example/WorldRendererMixin";

		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		at.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(TARGET)))));
		mixin.visibleAnnotations = new ArrayList<>(List.of(at));

		MethodNode handler = method("skipSkyRenderingForPhasingBlindness", handlerDesc);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	@SuppressWarnings("unchecked")
	private static List<String> selectorsOf(ClassNode mixin) {
		AnnotationNode inject = mixin.methods.get(0).visibleAnnotations.get(0);
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			if ("method".equals(inject.values.get(i))) return List.copyOf((List<String>) inject.values.get(i + 1));
		}
		return List.of();
	}
}
