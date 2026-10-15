/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.LambdaInvocationThunkInjector;
import net.forbric.kernel.transform.TransformContext;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

/**
 * Lithium's typed hit predicates are {@code @ModifyExpressionValue(@Expression("this::canHitEntity"))} on
 * {@code AbstractArrow.findHitEntity/findHitEntities} (and three other projectiles). The kernel's
 * {@link LambdaInvocationThunkInjector} points that method reference at a private thunk, so the definition naming
 * {@code canHitEntity} matched no reference and the four injectors never attached. The definition now names the
 * thunk as well; a projectile that only inherits {@code canHitEntity} (no thunk of its own) keeps its definition.
 */
class MixinExpressionThunksTest {
	private static final Path SWEEP = Path.of("build/compat-inputs/sweep90/mods");
	private static final String ARROW = "net/minecraft/world/entity/projectile/arrow/AbstractArrow";
	private static final String FIREWORK = "net/minecraft/world/entity/projectile/FireworkRocketEntity";

	@Test void theArrowsMethodReferenceExpressionAlsoNamesTheThunkItNowGoesThrough() throws Exception {
		ClassNode arrow = thunked(ARROW, true);
		ClassNode mixin = lithium("AbstractArrowMixin");
		// The defect: after the thunk injector, no reference in the class names canHitEntity any more.
		assertTrue(references(arrow, "canHitEntity").isEmpty(), "the method reference was routed through a thunk");
		List<String> thunkRefs = new ArrayList<>();
		for (MethodNode m : arrow.methods) if (LambdaInvocationThunkInjector.isThunk(arrow.name, m)
				&& LambdaInvocationThunkInjector.invocation(m).name.equals("canHitEntity")) thunkRefs.add(m.name);
		assertEquals(1, thunkRefs.size(), "one thunk calls canHitEntity");
		assertFalse(references(arrow, thunkRefs.getFirst()).isEmpty(), "findHitEntities' reference names the thunk");

		assertEquals(1, MixinExpressionThunks.adapt(mixin, name -> name.equals(ARROW) ? arrow : null));
		AnnotationNode definition = MixinExpressionThunks.definitions(mixin.methods.stream().filter(m -> !m.name.equals("<init>")).findFirst().orElseThrow()).getFirst();
		assertEquals(List.of("L" + ARROW + ";canHitEntity(Lnet/minecraft/world/entity/Entity;)Z",
				"L" + ARROW + ";" + thunkRefs.getFirst() + "(Lnet/minecraft/world/entity/Entity;)Z"), MixinFit.value(definition, "method"));
		assertEquals(0, MixinExpressionThunks.adapt(mixin, name -> name.equals(ARROW) ? arrow : null), "named once");
	}

	@Test void aProjectileThatOnlyInheritsTheMethodOrHasNoThunkKeepsItsDefinition() throws Exception {
		ClassNode firework = thunked(FIREWORK, true);
		assertEquals(0, MixinExpressionThunks.adapt(lithium("FireworkRocketEntityMixin"), name -> name.equals(FIREWORK) ? firework : null));
		ClassNode plain = thunked(ARROW, false);
		assertEquals(0, MixinExpressionThunks.adapt(lithium("AbstractArrowMixin"), name -> name.equals(ARROW) ? plain : null),
				"no thunk, nothing to add");
	}

	private static List<InvokeDynamicInsnNode> references(ClassNode owner, String name) {
		List<InvokeDynamicInsnNode> out = new ArrayList<>();
		for (MethodNode m : owner.methods) for (AbstractInsnNode i : m.instructions) {
			if (i instanceof InvokeDynamicInsnNode indy && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h && h.getName().equals(name)) out.add(indy);
		}
		return out;
	}

	private static ClassNode thunked(String name, boolean thunks) throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged merged base");
		byte[] bytes;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			bytes = zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes();
		}
		if (thunks) bytes = new LambdaInvocationThunkInjector().transform(name.replace('/', '.'), bytes, new TransformContext(EnvType.CLIENT, false, "intermediary"));
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static ClassNode lithium(String mixin) throws Exception {
		Path jar = SWEEP.resolve("lithium-fabric-0.25.3+mc26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "sweep Lithium fixture");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry("net/caffeinemc/mods/lithium/mixin/entity/projectile_projectile_collisions/" + mixin + ".class")).readAllBytes());
		}
	}
}
