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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
	/**
	 * A mixin targeting one class, with one {@code @Shadow} field that the target does not have — the simplest
	 * anchor that can fail.
	 */
	private static byte[] shadowMixin(String target) {
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "test/TheMixin",
				null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin =
				cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		org.objectweb.asm.FieldVisitor fv = cw.visitField(0, "notThere", "I", null, null);
		fv.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
		fv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** An empty class, so the {@code @Shadow} above cannot resolve against it. */
	private static byte[] emptyClass(String name) {
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
				"java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * The discriminator this repo now reports on, and the reason it is worth reporting.
	 *
	 * <p>An anchor that misses on a merged-base class is routine — measured across every gate log here, 1226 of
	 * them, all on runs that pass. One that misses on ANOTHER MOD's class did not occur once in that same set,
	 * and the one real instance found was Iris beside a Sodium build it no longer fits. Same unresolved anchor,
	 * completely different meaning, so {@code evaluate} has to tell them apart.
	 */
	@Test
	void anAnchorThatMissesOnAnotherModsClassIsSeparatedFromOneThatMissesOnTheGame() {
		String target = "net/example/OtherMod";
		java.util.function.Function<String, byte[]> resolver =
				name -> (target + ".class").equals(name) ? emptyClass(target) : null;

		MixinFit.Result asGame = MixinFit.evaluate(shadowMixin(target), resolver, name -> true);
		assertEquals(MixinFit.Verdict.UNFIT, asGame.verdict(), "the anchor misses either way");
		assertTrue(asGame.foreign().isEmpty(), "a merged-base miss is routine and must stay quiet");

		MixinFit.Result asMod = MixinFit.evaluate(shadowMixin(target), resolver, name -> false);
		assertEquals(1, asMod.foreign().size(), "a miss on another mod's class is the signal");
		assertTrue(asMod.foreign().get(0).contains("notThere"), asMod.foreign().toString());
	}

	@Test
	void anAnchorThatRESOLVESIsNeverReportedAsForeign() {
		// The obvious way to get this wrong: report every cross-mod mixin instead of every cross-mod mixin that
		// did not attach. Every pack is full of the former.
		String target = "net/example/OtherMod";
		org.objectweb.asm.ClassWriter cw =
				new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, target, null,
				"java/lang/Object", null);
		cw.visitField(0, "notThere", "I", null, null).visitEnd();
		cw.visitEnd();
		byte[] withField = cw.toByteArray();

		MixinFit.Result fit = MixinFit.evaluate(shadowMixin(target),
				name -> (target + ".class").equals(name) ? withField : null, name -> false);
		assertEquals(MixinFit.Verdict.FIT, fit.verdict());
		assertTrue(fit.foreign().isEmpty(), "it attached — there is nothing to tell the player");
	}

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
