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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Holds the anonymous-class retarget to "only where the table is unambiguous".
 *
 * <p>Polymer is the case that paid for it. Its {@code ByteBufCodecsHolderMixin} targets
 * {@code ByteBufCodecs$30}, which on this base is a carrier's codec — vanilla's body, the one the mixin was
 * compiled against, is at {@code $32}. Left alone the mixin was auto-suppressed as UNFIT, Polymer's payload
 * plumbing went with it, and the client was disconnected at world join on a Netty stack that named neither the
 * class number nor the merge.
 *
 * <p>The refusals are the half worth pinning: a name with two possible homes must not be moved, because a wrong
 * home applies cleanly to unrelated code and reports nothing at all.
 */
class MixinAnonymousRetargetTest {
	private static final String PRESENT_EVERYWHERE = "net.minecraft.network.codec.ByteBufCodecs$32";

	@Test
	void aTargetWithOneHomeIsMovedThere() {
		ClassNode mixin = mixinTargeting("net/minecraft/network/codec/ByteBufCodecs$30");

		assertEquals(1, MixinAnonymousRetarget.retarget(mixin, name -> true));
		assertEquals(List.of("net/minecraft/network/codec/ByteBufCodecs$32"), targetsOf(mixin));
	}

	/** {@code $28}'s body is duplicated across {@code $29} and {@code $30}; either choice would be a coin flip. */
	@Test
	void aTargetWithTwoHomesIsLeftWhereItIs() {
		ClassNode mixin = mixinTargeting("net/minecraft/network/codec/ByteBufCodecs$28");

		assertEquals(0, MixinAnonymousRetarget.retarget(mixin, name -> true));
		assertEquals(List.of("net/minecraft/network/codec/ByteBufCodecs$28"), targetsOf(mixin));
	}

	/** A home this base does not carry is never written in — the rule can only move to a class that is here. */
	@Test
	void aHomeThatIsNotOnThisBaseIsNeverInvented() {
		ClassNode mixin = mixinTargeting("net/minecraft/network/codec/ByteBufCodecs$30");

		assertEquals(0, MixinAnonymousRetarget.retarget(mixin, name -> false));
		assertEquals(List.of("net/minecraft/network/codec/ByteBufCodecs$30"), targetsOf(mixin));
	}

	@Test
	void anUndriftedTargetIsUntouched() {
		ClassNode mixin = mixinTargeting("net/minecraft/client/Minecraft");

		assertEquals(0, MixinAnonymousRetarget.retarget(mixin, name -> true));
		assertEquals(List.of("net/minecraft/client/Minecraft"), targetsOf(mixin));
	}

	@Test
	void theSwitchLeavesEveryTargetAsCompiled() {
		String previous = System.getProperty(MixinAnonymousRetarget.PROPERTY);
		System.setProperty(MixinAnonymousRetarget.PROPERTY, "off");
		try {
			ClassNode mixin = mixinTargeting("net/minecraft/network/codec/ByteBufCodecs$30");
			assertEquals(0, MixinAnonymousRetarget.retarget(mixin, name -> true));
			assertNull(MixinAnonymousRetarget.home("net/minecraft/network/codec/ByteBufCodecs$30", name -> true));
		} finally {
			if (previous == null) System.clearProperty(MixinAnonymousRetarget.PROPERTY);
			else System.setProperty(MixinAnonymousRetarget.PROPERTY, previous);
		}
	}

	/** The resolver {@link MixinFit} shares, so the verdict and the rewrite cannot disagree. */
	@Test
	void theHomeResolverAnswersTheSameQuestionTheRewriteAsks() {
		assertEquals(PRESENT_EVERYWHERE.replace('.', '/'),
				MixinAnonymousRetarget.home("net/minecraft/network/codec/ByteBufCodecs$30", name -> true));
		assertNull(MixinAnonymousRetarget.home("net/minecraft/network/codec/ByteBufCodecs$28", name -> true));
	}

	private static ClassNode mixinTargeting(String target) {
		ClassNode mixin = new ClassNode();
		mixin.name = "com/example/SomeMixin";
		mixin.version = Opcodes.V21;
		AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		annotation.values = new ArrayList<>(List.of("targets", new ArrayList<>(List.of(target))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(annotation));
		return mixin;
	}

	@SuppressWarnings("unchecked")
	private static List<String> targetsOf(ClassNode mixin) {
		AnnotationNode annotation = mixin.invisibleAnnotations.get(0);
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if ("targets".equals(annotation.values.get(i))) return (List<String>) annotation.values.get(i + 1);
		}
		return List.of();
	}
}
