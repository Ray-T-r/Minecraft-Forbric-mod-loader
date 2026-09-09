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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Tells a cross-mod compatibility mixin apart from genuine dead weight.
 *
 * <p>The adapter drops a guest mixin whose anchors all fail to resolve against the merged base, on the reasoning
 * that it was going to be dead weight. That is wrong exactly when the missing member is ANOTHER mod's mixin's
 * contribution — measured with Physics Mod, whose two Sodium/Iris integration mixins were the two the adapter
 * deleted, silently. These tests pin the question the adapter now asks first.
 */
class ForeignMixinTargetsTest {
	private static final String RELAX = "forbric.relaxGuestMixins";
	private String relaxBefore;

	@BeforeEach
	void clear() {
		relaxBefore = System.getProperty(RELAX);
		ForeignMixinTargets.reset();
	}

	@AfterEach
	void restore() {
		if (relaxBefore == null) System.clearProperty(RELAX);
		else System.setProperty(RELAX, relaxBefore);
		ForbricMixinService.setGuestConfigs(List.of());
		ForeignMixinTargets.reset();
	}

	/** The Physics Mod case: physicsmod's mixin and sodium's mixin name the same target class. */
	@Test
	void aTargetAnotherConfigAlsoMixesIntoIsClaimed() {
		Stage stage = new Stage();
		stage.config("physicsmod.mixins.json", "physicsmod.mixin", "MixinVertexTransform", "net/example/SpriteCoordinateExpander");
		stage.config("sodium.mixins.json", "sodium.mixin", "SpriteCoordinateExpanderMixin", "net/example/SpriteCoordinateExpander");
		ForbricMixinService.setGuestConfigs(stage.names());

		assertTrue(ForeignMixinTargets.claimedByAnotherConfig("physicsmod.mixins.json",
				List.of("net/example/SpriteCoordinateExpander"), stage),
				"sodium's mixin adds the member physicsmod's mixin is looking for — the mixin must be kept");
	}

	/** The negative: a config that is the ONLY one touching its target really is on its own. */
	@Test
	void aTargetOnlyThisConfigMixesIntoIsNotClaimed() {
		Stage stage = new Stage();
		stage.config("physicsmod.mixins.json", "physicsmod.mixin", "MixinVertexTransform", "net/example/SpriteCoordinateExpander");
		stage.config("sodium.mixins.json", "sodium.mixin", "SomethingElseMixin", "net/example/Unrelated");
		ForbricMixinService.setGuestConfigs(stage.names());

		assertFalse(ForeignMixinTargets.claimedByAnotherConfig("physicsmod.mixins.json",
				List.of("net/example/SpriteCoordinateExpander"), stage),
				"nobody else adds to that class, so an unresolvable anchor really is dead weight");
	}

	/** A config never sees itself as the other mod, even when it declares two mixins on one class. */
	@Test
	void aConfigDoesNotClaimItsOwnTarget() {
		Stage stage = new Stage();
		stage.config("physicsmod.mixins.json", "physicsmod.mixin", "MixinVertexTransform", "net/example/Shared");
		ForbricMixinService.setGuestConfigs(stage.names());

		assertFalse(ForeignMixinTargets.claimedByAnotherConfig("physicsmod.mixins.json",
				List.of("net/example/Shared"), stage),
				"the asking config's own claim must not count as a foreign one");
	}

	@Test
	void noTargetsIsNeverClaimed() {
		Stage stage = new Stage();
		stage.config("a.mixins.json", "a.mixin", "AMixin", "net/example/Foo");
		ForbricMixinService.setGuestConfigs(stage.names());

		assertFalse(ForeignMixinTargets.claimedByAnotherConfig("b.mixins.json", List.of(), stage));
	}

	/**
	 * The reason {@code registeredConfigs} is recorded BEFORE the relax gate and unconditionally: whether configs
	 * are relaxed is a different question from which classes some other mod's mixin adds members to. Emptying the
	 * index with {@code -Dforbric.relaxGuestMixins=off} would silently restore the bug this whole class fixes.
	 */
	@Test
	void theIndexSurvivesRelaxGuestMixinsBeingOff() {
		System.setProperty(RELAX, "off");
		Stage stage = new Stage();
		stage.config("physicsmod.mixins.json", "physicsmod.mixin", "MixinVertexTransform", "net/example/SpriteCoordinateExpander");
		stage.config("sodium.mixins.json", "sodium.mixin", "SpriteCoordinateExpanderMixin", "net/example/SpriteCoordinateExpander");
		ForbricMixinService.setGuestConfigs(stage.names());

		assertEquals(2, ForbricMixinService.registeredConfigNames().size(),
				"the relax switch must not empty the config index");
		assertTrue(ForeignMixinTargets.claimedByAnotherConfig("physicsmod.mixins.json",
				List.of("net/example/SpriteCoordinateExpander"), stage));
	}

	/** An unreadable or non-mixin resource must cost only itself, never the rest of the index. */
	@Test
	void oneUnparseableConfigDoesNotCostTheOthers() {
		Stage stage = new Stage();
		stage.raw("broken.mixins.json", "this is not json {{{".getBytes(StandardCharsets.UTF_8));
		stage.config("physicsmod.mixins.json", "physicsmod.mixin", "MixinVertexTransform", "net/example/Shared");
		stage.config("sodium.mixins.json", "sodium.mixin", "SharedMixin", "net/example/Shared");
		ForbricMixinService.setGuestConfigs(stage.names());

		assertTrue(ForeignMixinTargets.claimedByAnotherConfig("physicsmod.mixins.json",
				List.of("net/example/Shared"), stage),
				"a broken config must not take the working ones' contribution with it");
	}

	// --- staging ----------------------------------------------------------------------------------------------

	/** A fake jar: config name -> config JSON, and {@code pkg/Name.class} -> mixin bytes. */
	private static final class Stage implements Function<String, byte[]> {
		private final Map<String, byte[]> resources = new HashMap<>();
		private final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();

		void raw(String configName, byte[] bytes) {
			names.add(configName);
			resources.put(configName, bytes);
		}

		/** One config declaring one mixin class, which targets {@code target}. */
		void config(String configName, String pkg, String mixinName, String target) {
			raw(configName, ("{\"package\": \"" + pkg + "\", \"mixins\": [\"" + mixinName + "\"]}")
					.getBytes(StandardCharsets.UTF_8));
			String path = pkg.replace('.', '/') + "/" + mixinName + ".class";
			resources.put(path, mixinClass(pkg.replace('.', '/') + "/" + mixinName, target));
		}

		java.util.Collection<String> names() {
			return names;
		}

		@Override
		public byte[] apply(String path) {
			return resources.get(path);
		}
	}

	/** {@code @Mixin(targets = "<target>") class <internalName> {}} */
	private static byte[] mixinClass(String internalName, String target) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, internalName, null, "java/lang/Object", null);

		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		mixin.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}
