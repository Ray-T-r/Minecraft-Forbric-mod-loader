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

import org.junit.jupiter.api.Test;

/**
 * Pins how a Mixin member target is split into owner/name/desc.
 *
 * <p>This is a DIAGNOSTIC's parser, which is exactly why it needs pinning: when it is wrong it does not crash, it
 * lies. Reading the dotted owner form as part of the method name made every such {@code @At(target=…)} report as an
 * unresolved anchor forever — 11 of Shoulder Surfing's mixins looked half-applied when every anchor was present —
 * and would have made {@code -Dforbric.mixinFit=strict} drop mixins that fit.
 */
class MixinFitTest {
	@Test
	void descriptorFormOwnerIsStripped() {
		MixinFit.Member m = MixinFit.parseMember("Lnet/minecraft/client/CameraType;isFirstPerson()Z");
		assertEquals("net/minecraft/client/CameraType", m.owner());
		assertEquals("isFirstPerson", m.name());
		assertEquals("()Z", m.desc());
	}

	/** The case this fix exists for: Shoulder Surfing, malilib and litematica all write targets this way. */
	@Test
	void dottedInternalNameOwnerIsStripped() {
		MixinFit.Member m = MixinFit.parseMember("net/minecraft/client/CameraType.isFirstPerson()Z");
		assertEquals("net/minecraft/client/CameraType", m.owner());
		assertEquals("isFirstPerson", m.name(), "the owner must not end up glued to the method name");
		assertEquals("()Z", m.desc());
	}

	@Test
	void aFullyDottedOwnerBecomesAnInternalName() {
		MixinFit.Member m = MixinFit.parseMember("com.example.Foo.bar()V");
		assertEquals("com/example/Foo", m.owner(), "owners are compared against ASM's internal names");
		assertEquals("bar", m.name());
	}

	@Test
	void aConstructorTargetKeepsItsAngleBrackets() {
		MixinFit.Member m = MixinFit.parseMember("net/minecraft/client/model/Model.<init>");
		assertEquals("net/minecraft/client/model/Model", m.owner());
		assertEquals("<init>", m.name());
		assertNull(m.desc());
	}

	@Test
	void aBareNameHasNoOwner() {
		MixinFit.Member m = MixinFit.parseMember("addToTooltip");
		assertNull(m.owner());
		assertEquals("addToTooltip", m.name());
		assertNull(m.desc());
	}

	@Test
	void aFieldTargetSplitsOnTheColon() {
		MixinFit.Member m = MixinFit.parseMember("net/minecraft/client/gui/Hud.random:Lnet/minecraft/util/RandomSource;");
		assertEquals("net/minecraft/client/gui/Hud", m.owner());
		assertEquals("random", m.name());
		assertEquals("Lnet/minecraft/util/RandomSource;", m.desc());
	}

	/** Shoulder Surfing writes a space before the descriptor; Mixin ignores it, so this must too. */
	@Test
	void whitespaceInsideTheMemberIsIgnored() {
		MixinFit.Member m = MixinFit.parseMember(
				"net/minecraft/client/renderer/entity/EntityRenderer.createRenderState ()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;");
		assertEquals("net/minecraft/client/renderer/entity/EntityRenderer", m.owner());
		assertEquals("createRenderState", m.name(), "a trailing space must not become part of the method name");
		assertEquals("()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", m.desc());
	}

	@Test
	void aWildcardIsNotOursToJudge() {
		assertNull(MixinFit.parseMember("render*"), "a wildcard target must stay unjudged, not resolve to nothing");
	}
}
