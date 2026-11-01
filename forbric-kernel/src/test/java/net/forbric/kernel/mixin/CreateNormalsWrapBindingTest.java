/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/**
 * Create's normals fix, a {@code @WrapOperation} on {@code FaceBakery.bakeQuad} inside
 * {@code UnbakedCuboidGeometry.bake(List, TextureSlots, ModelBaker, ModelState, ModelDebugName)}, binds a body that makes
 * that call. On the merged base it did not: MinecraftForge kept that signature as a builder stub over a
 * {@code bake(QuadCollection.Builder, …, Function)} overload, the wrap bound the stub where there is nothing to wrap, and
 * {@code MixinBuilderStubRebind} moved it (deleted in Stage 4 C3). NeoForge's static {@code bake(List, …)} is vanilla's own
 * body, so the wrap binds where Fabric binds it. The standing whole-jar form of this is
 * {@code NeoForgeBaseStructureCensusStagedTest.noVanillaBodyMovedBehindABuilderStub}.
 */
class CreateNormalsWrapBindingTest {
	private static final String GEOMETRY = "net/minecraft/client/resources/model/cuboid/UnbakedCuboidGeometry";
	private static final String BAKE_QUAD = "net/minecraft/client/resources/model/cuboid/FaceBakery";

	@Test void createsNormalsWrapBindsABodyThatBakesTheQuadsAsVanillasDoes() throws Exception {
		ClassNode base = full(TestFixtures.stagedBase()), vanilla = full(TestFixtures.vanillaJar());
		ClassNode mixin = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/UnbakedCuboidGeometryMixin");
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("bakeQuad")).findFirst().orElseThrow();
		for (ClassNode target : List.of(vanilla, base)) {
			List<MethodNode> bound = MixinTargetSelectors.bound(handler, target);
			assertNotNull(bound, "the selector reads");
			assertEquals(1, bound.size(), "the selector binds one body");
			assertEquals(1, java.util.Arrays.stream(bound.getFirst().instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
					&& call.owner.equals(BAKE_QUAD) && call.name.equals("bakeQuad")).count(),
					(target == base ? "NeoForge's" : "vanilla's") + " bound body makes the bakeQuad call the wrap names");
		}
	}

	private static ClassNode full(Path jar) throws Exception {
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, jar, "game jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(GEOMETRY + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		}
	}
}
