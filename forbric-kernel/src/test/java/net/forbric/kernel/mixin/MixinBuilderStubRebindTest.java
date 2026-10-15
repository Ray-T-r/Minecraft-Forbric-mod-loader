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
 * NeoForge keeps vanilla's {@code UnbakedCuboidGeometry.bake(List, TextureSlots, ModelBaker, ModelState, ModelDebugName)}
 * as a builder stub over {@code bake(QuadCollection.Builder, …, Function)}, which makes vanilla's
 * {@code FaceBakery.bakeQuad} calls. Create's normals {@code @WrapOperation} on {@code bakeQuad} bound the stub (census:
 * natively once in {@code bake}, on Forbric nowhere) and now binds the overload.
 */
class MixinBuilderStubRebindTest {
	private static final String GEOMETRY = "net/minecraft/client/resources/model/cuboid/UnbakedCuboidGeometry";
	private static final String BUILDER_BAKE = "bake(Lnet/minecraft/client/resources/model/geometry/QuadCollection$Builder;Ljava/util/List;"
			+ "Lnet/minecraft/client/resources/model/sprite/TextureSlots;Lnet/minecraft/client/resources/model/ModelBaker;"
			+ "Lnet/minecraft/client/renderer/block/dispatch/ModelState;Lnet/minecraft/client/resources/model/ModelDebugName;Ljava/util/function/Function;)V";

	@Test void createsNormalsWrapMovesToTheOverloadThatBakesTheQuads() throws Exception {
		ClassNode merged = full(TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar")), vanilla = full(TestFixtures.vanillaJar());
		ClassNode mixin = create();
		assertEquals(1, MixinBuilderStubRebind.adapt(mixin, name -> name.equals(GEOMETRY) ? merged : null, (e, name) -> name.equals(GEOMETRY) ? vanilla : null));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("bakeQuad")).findFirst().orElseThrow();
		assertEquals(List.of(BUILDER_BAKE), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
	}

	@Test void onVanillaOrSwitchedOffNothingMoves() throws Exception {
		ClassNode merged = full(TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar")), vanilla = full(TestFixtures.vanillaJar());
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
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(jar), "game jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(GEOMETRY + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		}
	}
}
