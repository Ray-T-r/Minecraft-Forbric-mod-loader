/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/**
 * The merged base kept vanilla's {@code UnbakedCuboidGeometry.bake(List, TextureSlots, ModelBaker, ModelState, ModelDebugName)}
 * as a builder stub over a {@code bake(QuadCollection.Builder, …, Function)} overload (MinecraftForge's), which made
 * vanilla's {@code FaceBakery.bakeQuad} calls, so Create's normals {@code @WrapOperation} on {@code bakeQuad} bound the
 * stub and the rebind moved it to the overload. Genuine NeoForge has no such overload: its static {@code bake(List, …)}
 * is vanilla's own body and makes the {@code bakeQuad} call itself (javap), so the wrap binds where Fabric binds it and
 * nothing moves.
 */
class MixinBuilderStubRebindTest {
	private static final String GEOMETRY = "net/minecraft/client/resources/model/cuboid/UnbakedCuboidGeometry";
	private static final String BAKE_QUAD = "net/minecraft/client/resources/model/cuboid/FaceBakery";

	@Test void onTheGenuineBaseCreatesNormalsWrapStaysOnVanillasBodyWhichBakesTheQuads() throws Exception {
		ClassNode base = full(TestFixtures.stagedBase()), vanilla = full(TestFixtures.vanillaJar());
		ClassNode mixin = create();
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("bakeQuad")).findFirst().orElseThrow();
		List<String> selectors = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"));
		List<MethodNode> bound = MixinTargetSelectors.bound(handler, base);
		assertNotNull(bound);
		assertEquals(1, bound.size(), "the selector binds one body");
		assertEquals(1, java.util.Arrays.stream(bound.getFirst().instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
				&& call.owner.equals(BAKE_QUAD) && call.name.equals("bakeQuad")).count(), "which makes the bakeQuad call itself, as vanilla's does");
		assertEquals(0, MixinBuilderStubRebind.adapt(mixin, name -> name.equals(GEOMETRY) ? base : null, (e, name) -> name.equals(GEOMETRY) ? vanilla : null));
		assertEquals(selectors, MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")), "nothing moves");
	}

	@Test void onVanillaOrSwitchedOffNothingMoves() throws Exception {
		ClassNode merged = full(TestFixtures.stagedBase()), vanilla = full(TestFixtures.vanillaJar());
		assertEquals(0, MixinBuilderStubRebind.adapt(create(), name -> vanilla, (e, name) -> vanilla), "vanilla's bake is the body");
		String old = System.setProperty(MixinBuilderStubRebind.PROPERTY, "off");
		try {
			assertEquals(0, MixinBuilderStubRebind.adapt(create(), name -> merged, (e, name) -> vanilla));
		} finally {
			if (old == null) System.clearProperty(MixinBuilderStubRebind.PROPERTY); else System.setProperty(MixinBuilderStubRebind.PROPERTY, old);
		}
	}

	private static ClassNode create() throws Exception {
		ClassNode mixin = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/UnbakedCuboidGeometryMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
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
