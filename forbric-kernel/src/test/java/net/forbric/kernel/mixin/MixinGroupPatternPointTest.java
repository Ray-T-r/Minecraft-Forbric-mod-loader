/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A {@code @Group} bound is proved impossible only from points whose instructions the plain member reading selects as
 * Mixin does. Mixin reads an {@code @At} target ending in {@code /} as a {@code MemberMatcher} pattern and strips a
 * quantifier off a name before matching; read as a plain member, either would select no call at all and "prove" a
 * minimum that Mixin meets — and a proved failure suppresses the whole mixin. Those points are unknown. The fixture is
 * invented: a kiln whose {@code fire} lights a {@code Wick} once.
 */
class MixinGroupPatternPointTest {
	private static final String KILN = "pattern/group/Kiln", CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

	@Test
	void aPatternOrQuantifiedPointIsUnknownNeverProvedEmpty() {
		for (String point : List.of("/light/", "/^light$/", "name=/^light$/", "owner=/Wick$/ name=/^light$/", "desc=/^\\(\\)V$/",
				"Lpattern/group/Wick;light+", "light*")) {
			assertEquals(List.of(), failures(group(1, point), kiln()),
					point + ": Mixin selects the wick's light() call with it; the plain reading would select nothing");
		}
	}

	@Test
	void aPlainPointThatSelectsNothingStillProvesTheMinimumImpossible() {
		// The look-alike: written plainly, a point naming a member the kiln never calls is still proved to bind nothing.
		for (String point : List.of("Lpattern/group/Wick;snuff()V", "pattern.group.Wick.snuff()V", "snuff", " Lpattern/group/Wick; snuff ")) {
			List<String> failures = failures(group(1, point), kiln());
			assertEquals(1, failures.size(), point + ": " + failures);
			assertTrue(failures.getFirst().contains("at most 0 can bind"), failures.getFirst());
		}
		for (String point : List.of("Lpattern/group/Wick;light()V", "light", "pattern.group.Wick.light")) {
			assertEquals(List.of(), failures(group(1, point), kiln()), point + " selects the call");
		}
	}

	@Test
	void theTargetReaderAgreesWithMixinOnlyForPlainMembers() {
		for (String plain : List.of("Lpattern/group/Wick;light()V", "pattern.group.Wick.light()V", "light", " Lpattern/group/Wick; light ()V ",
				"Lpattern/group/Wick;flame:I"))
			assertTrue(MixinTargetSelectors.readsAsMember(plain), plain);
		for (String other : List.of("/light/", "name=/^light$/", "owner=/Wick$/ name=/^light$/", "light+", "light*", "light{2}", "", "@Desc(\"x\")"))
			assertFalse(MixinTargetSelectors.readsAsMember(other), other);
		assertFalse(MixinTargetSelectors.readsAsMember(null));
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static List<String> failures(ClassNode mixin, ClassNode target) {
		return MixinGroupConstraints.failures(mixin, path -> path.equals(KILN + ".class") ? StagedFabricMixinFixture.bytes(target) : null);
	}

	/** One plain {@code @Inject} at {@code point} in {@code fire}, alone in a group whose minimum is {@code min}. */
	private static ClassNode group(int min, String point) {
		ClassNode mixin = new ClassNode();
		mixin.name = "pattern/tenant/KilnWatch";
		mixin.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;", "value", List.of(Type.getObjectType(KILN))));
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "onLight", "(" + CI + ")V", null, null);
		method.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;", "method", List.of("fire"), "at",
				List.of(annotation("Lorg/spongepowered/asm/mixin/injection/At;", "value", "INVOKE", "target", point))));
		method.invisibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Group;", "name", "lights", "min", min));
		mixin.methods.add(method);
		return mixin;
	}

	/** {@code fire()} lights the wick once. */
	private static ClassNode kiln() {
		ClassNode target = new ClassNode();
		target.name = KILN;
		target.version = Opcodes.V21;
		target.superName = "java/lang/Object";
		MethodNode fire = new MethodNode(Opcodes.ACC_PUBLIC, "fire", "()V", null, null);
		fire.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pattern/group/Wick", "light", "()V", false));
		fire.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(fire);
		return target;
	}

	private static AnnotationNode annotation(String desc, Object... values) {
		AnnotationNode a = new AnnotationNode(desc);
		a.values = new ArrayList<>(List.of(values));
		return a;
	}
}
