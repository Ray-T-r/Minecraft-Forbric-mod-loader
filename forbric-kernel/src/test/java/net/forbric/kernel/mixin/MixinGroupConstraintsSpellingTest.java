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
 * A {@code @Group}'s bounds are counted from the methods its injectors bind as Mixin binds them, and the instructions
 * each point selects as Mixin resolves its target — an owner prefix, a dotted owner, whitespace, a quantifier, a bare
 * name's first declared overload, a point without its owner or descriptor. The fixture is invented: a furnace whose
 * {@code burn} ignites its fuel once.
 */
class MixinGroupConstraintsSpellingTest {
	private static final String FURNACE = "spelled/group/Furnace", CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

	@Test
	void selectorsWrittenAnyWayMixinBindsThemAreCounted() {
		for (String selector : List.of("L" + FURNACE + ";burn", "spelled.group.Furnace.burn()V", " burn ", "burn*", "burn ()V")) {
			assertTrue(failures(group(1, -1, selector, "L" + "spelled/group/Fuel;ignite()V"), furnace(false)).isEmpty(),
					selector + " binds burn, where the fuel ignites: the minimum is met");
			List<String> failures = failures(group(1, -1, selector, "Lspelled/group/Fuel;smoulder()V"), furnace(false));
			assertEquals(1, failures.size(), selector + ": nothing it binds smoulders, which the old reading could not tell");
			assertTrue(failures.getFirst().contains("at most 0 can bind"), failures.getFirst());
		}
		// A bare name binds the first declared overload, which ignites.
		assertTrue(failures(group(1, -1, "burn", "Lspelled/group/Fuel;ignite()V"), furnace(true)).isEmpty());
		assertEquals(1, failures(group(1, -1, "burn", "Lspelled/group/Fuel;quench()V"), furnace(true)).size(),
				"only the overload declared later quenches, and Mixin binds the first");
	}

	@Test
	void pointsWrittenAnyWayMixinResolvesThemAreCounted() {
		for (String point : List.of("spelled.group.Fuel.ignite()V", "ignite()V", "Lspelled/group/Fuel;ignite", " Lspelled/group/Fuel; ignite ()V ")) {
			assertTrue(failures(group(1, -1, "burn", point), furnace(false)).isEmpty(), point + " selects the ignition");
			// Two plain callbacks at it, the group allowing one: Mixin injects both.
			List<String> over = failures(group(1, 1, "burn", point, point), furnace(false));
			assertEquals(1, over.size(), point);
			assertTrue(over.getFirst().contains("at least 2 bind"), over.getFirst());
		}
	}

	@Test
	void anotherOwnerOrAnotherMemberBindsNothingAndAnUnmodelledFormProvesNothing() {
		assertEquals(1, failures(group(1, -1, "burn", "spelled.group.Spark.ignite()V"), furnace(false)).size(), "another owner's ignite is not called");
		assertEquals(1, failures(group(1, -1, "burn", "Lspelled/group/Fuel;ignite(I)V"), furnace(false)).size(), "another overload is not called");
		assertEquals(1, failures(group(1, -1, "bake", "Lspelled/group/Fuel;ignite()V"), furnace(false)).size(), "bake does not ignite");
		assertTrue(failures(group(1, -1, "Lspelled/group/Oven;burn", "Lspelled/group/Fuel;smoulder()V"), furnace(false)).isEmpty(),
				"a selector Mixin refuses for this target is unknown, never proved empty");
		assertTrue(failures(group(1, -1, "burn", "Lspelled/group/Fuel;smoulder{2}"), furnace(false)).isEmpty(), "a quantified point is unknown");
	}

	/**
	 * A pattern binds every match Mixin finds, and passes over what a mixin merged into the class — the mixin's own
	 * {@code @Unique} method among them, which it adds before its injectors are prepared.
	 */
	@Test
	void aPatternPassesOverTheMixinsOwnMergedMethodAsMixinDoes() {
		ClassNode mixin = group(2, -1, "/^fire|^burn/", "Lspelled/group/Fuel;ignite()V");
		MethodNode own = new MethodNode(Opcodes.ACC_PRIVATE, "fireAgain", "()V", null, null);
		own.visibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;"));
		own.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "spelled/group/Fuel", "ignite", "()V", false));
		own.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods.add(own);
		List<String> failures = failures(mixin, furnace(false));
		assertEquals(1, failures.size(), "only burn ignites among the methods the pattern binds: " + failures);
		assertTrue(failures.getFirst().contains("at most 1 can bind"), failures.getFirst());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static List<String> failures(ClassNode mixin, ClassNode target) {
		return MixinGroupConstraints.failures(mixin, path -> path.equals(FURNACE + ".class") ? StagedFabricMixinFixture.bytes(target) : null);
	}

	/** One plain {@code @Inject} per point, all on {@code selector}, in one group of {@code min}..{@code max}. */
	private static ClassNode group(int min, int max, String selector, String... points) {
		ClassNode mixin = new ClassNode();
		mixin.name = "spelled/tenant/FurnaceWatch";
		mixin.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;", "value", List.of(Type.getObjectType(FURNACE))));
		for (int i = 0; i < points.length; i++) {
			MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "watch" + i, "(" + CI + ")V", null, null);
			method.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Inject;", "method", List.of(selector), "at",
					List.of(annotation("Lorg/spongepowered/asm/mixin/injection/At;", "value", "INVOKE", "target", points[i]))));
			method.invisibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/injection/Group;", "name", "watchers", "min", min, "max", max));
			mixin.methods.add(method);
		}
		return mixin;
	}

	/** {@code burn()} ignites the fuel once; with {@code overload}, a later {@code burn(int)} quenches it; {@code bake} does neither. */
	private static ClassNode furnace(boolean overload) {
		ClassNode target = new ClassNode();
		target.name = FURNACE;
		target.version = Opcodes.V21;
		target.superName = "java/lang/Object";
		MethodNode burn = new MethodNode(Opcodes.ACC_PUBLIC, "burn", "()V", null, null);
		burn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "spelled/group/Fuel", "ignite", "()V", false));
		burn.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(burn);
		if (overload) {
			MethodNode quench = new MethodNode(Opcodes.ACC_PUBLIC, "burn", "(I)V", null, null);
			quench.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "spelled/group/Fuel", "quench", "()V", false));
			quench.instructions.add(new InsnNode(Opcodes.RETURN));
			target.methods.add(quench);
		}
		MethodNode bake = new MethodNode(Opcodes.ACC_PUBLIC, "bake", "()V", null, null);
		bake.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(bake);
		return target;
	}

	private static AnnotationNode annotation(String desc, Object... values) {
		AnnotationNode a = new AnnotationNode(desc);
		a.values = new ArrayList<>(List.of(values));
		return a;
	}
}
