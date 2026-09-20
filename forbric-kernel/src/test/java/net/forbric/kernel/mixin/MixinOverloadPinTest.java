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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 * that class, and never for a selector that binds. It changes no bytes — asserted here, because a rewrite would
 * move an injection somewhere the mod did not ask for, silently, which is worse than the exception.
 */
class MixinOverloadPinTest {
	private static final String TARGET = "net/minecraft/client/renderer/LevelRenderer";
	private static final String SLICE = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
	private static final String SKY = "Lnet/minecraft/client/renderer/state/level/SkyRenderState;";
	private static final String MATRIX = "Lorg/joml/Matrix4fc;";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String NAME = "lambda$addSkyPass$0";
	private static final String VANILLA_SHAPE = "(" + SLICE + SKY + ")V";

	@Test
	void aSelectorWhoseShapeTheMergeDroppedIsExplained() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		byte[] before = selectorsOf(mixin).toString().getBytes();

		assertEquals(1, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())),
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
	}

	/**
	 * A handler that fits neither what is there NOR what was dropped is a mod targeting something this game never
	 * had. Saying "the merge dropped it" would be a guess wearing a fact's clothes.
	 */
	@Test
	void aHandlerThatFitsNothingIsNotExplained() {
		ClassNode mixin = mixinWith("(Ljava/lang/String;" + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
	}

	@Test
	void aSelectorTheClassDoesNotDeclareAtAllIsNotExplained() {
		ClassNode target = new ClassNode();
		target.name = TARGET;
		target.methods = new ArrayList<>();
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(target)));
	}

	@Test
	void anExplicitSelectorIsNeverLookedAt() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME + VANILLA_SHAPE);
		assertEquals(0, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
	}

	@Test
	void aTargetThatCannotBeReadSaysNothing() {
		ClassNode mixin = mixinWith("(" + SLICE + SKY + CI + ")V", NAME);
		assertEquals(0, MixinOverloadPin.pin(mixin, name -> null));
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
		assertEquals(1, MixinOverloadPin.pin(mixin, targets(neoForgeLambdaOnly())));
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
