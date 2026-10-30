/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A mixin a config plugin adds from {@code getMixins()} is in none of the config's lists, so the kernel never recorded
 * which family's mod wrote it, and every adapter that compares against the native game declined it. Create's client
 * config adds LoadBlockModelMixin (and the cuboid-geometry mixins) that way when Fabric's renderer API is absent: its
 * cancellable {@code @Inject} at {@code CuboidModel.fromStream} was never moved to NeoForge's
 * {@code UnbakedModelParser.parse} and ran 0 times (natively once per block model). Mixin takes every mixin of a config
 * from the config's package, so that package now answers for them.
 */
class PluginAddedMixinEcosystemTest {
	private static final String MODELS = "net/minecraft/client/resources/model/ModelManager";
	private static final String PARSE = "Lnet/neoforged/neoforge/client/model/UnbakedModelParser;parse(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/UnbakedModel;";

	@AfterEach void forget() {
		MixinStubRebind.forget();
	}

	@Test void aPluginAddedMixinIsTheEcosystemOfItsConfigsPackage() {
		MixinStubRebind.notePackage("a/mixin", Ecosystem.FABRIC, "a.mixins.json");
		MixinStubRebind.notePackage("a/mixin/forge", Ecosystem.FORGE, "a.forge.mixins.json");
		assertEquals(Ecosystem.FABRIC, MixinStubRebind.ecosystemOf("a/mixin/client/Added"));
		assertEquals("a.mixins.json", MixinStubRebind.configOf("a/mixin/client/Added"));
		assertEquals(Ecosystem.FORGE, MixinStubRebind.ecosystemOf("a/mixin/forge/Added"), "the innermost config package");
		assertNull(MixinStubRebind.ecosystemOf("b/mixin/Added"));
		MixinStubRebind.noteEcosystem("a/mixin/Listed", Ecosystem.NEOFORGE, "listed.json");
		assertEquals(Ecosystem.NEOFORGE, MixinStubRebind.ecosystemOf("a/mixin/Listed"), "a listed mixin keeps its own row");
		MixinStubRebind.notePackage("c/mixin", Ecosystem.FABRIC, "c.fabric.json");
		MixinStubRebind.notePackage("c/mixin", Ecosystem.NEOFORGE, "c.neo.json");
		assertNull(MixinStubRebind.ecosystemOf("c/mixin/Added"), "two families' configs share the package");
		assertNull(MixinStubRebind.configOf("c/mixin/Added"));
	}

	/**
	 * A config two mods claim has no owner (MixinConfigOwners answers null): its listed mixins got no family, and every
	 * adapter that compares against the native game skipped them. The package fallback must not hand them a family —
	 * an outer config's, or another config's sharing the package — nor reach past the ambiguous config's package for a
	 * plugin-added mixin in it.
	 */
	@Test void aMixinOfAConfigWithAnAmbiguousOwnerGetsNoFamilyFromAPackage() {
		// Outer package of a Fabric config; the ambiguous config's package inside it.
		MixinStubRebind.notePackage("com/example/mixin", Ecosystem.FABRIC, "lib.mixins.json");
		MixinStubRebind.notePackage("com/example/mixin/common", null, "shared.mixins.json");
		MixinStubRebind.noteEcosystem("com/example/mixin/common/ListedMixin", null, "shared.mixins.json");
		assertNull(MixinStubRebind.ecosystemOf("com/example/mixin/common/ListedMixin"), "the outer config's family");
		assertNull(MixinStubRebind.ecosystemOf("com/example/mixin/common/Added"), "nor for a plugin-added one in its package");
		assertEquals(Ecosystem.FABRIC, MixinStubRebind.ecosystemOf("com/example/mixin/Added"), "the outer package still answers for its own");
		// One package, an ambiguous config and a NeoForge one, in either order.
		MixinStubRebind.notePackage("com/other/mixin", null, "ambiguous.mixins.json");
		MixinStubRebind.noteEcosystem("com/other/mixin/A", null, "ambiguous.mixins.json");
		MixinStubRebind.notePackage("com/other/mixin", Ecosystem.NEOFORGE, "client.mixins.json");
		assertNull(MixinStubRebind.ecosystemOf("com/other/mixin/A"));
		MixinStubRebind.notePackage("com/third/mixin", Ecosystem.NEOFORGE, "client.mixins.json");
		MixinStubRebind.notePackage("com/third/mixin", null, "ambiguous.mixins.json");
		MixinStubRebind.noteEcosystem("com/third/mixin/B", null, "ambiguous.mixins.json");
		assertNull(MixinStubRebind.ecosystemOf("com/third/mixin/B"));
		assertNull(MixinStubRebind.ecosystemOf("com/third/mixin/Added"), "the package may be the ambiguous config's");
		// Control: the same listed mixin under a single-owner config keeps its own family.
		MixinStubRebind.noteEcosystem("com/third/mixin/C", Ecosystem.NEOFORGE, "client.mixins.json");
		assertEquals(Ecosystem.NEOFORGE, MixinStubRebind.ecosystemOf("com/third/mixin/C"));
	}

	@Test void createsPluginAddedModelMixinLandsOnNeoForgesParser() throws Exception {
		ClassNode merged = full(TestFixtures.stagedBase(), MODELS);
		ClassNode vanilla = full(TestFixtures.vanillaJar(), MODELS);
		// As in production: no ecosystem, no native reference.
		java.util.function.BiFunction<Ecosystem, String, ClassNode> references = (ecosystem, name) -> ecosystem == Ecosystem.FABRIC
				&& name.equals(MODELS) ? vanilla : null;
		ClassNode unknown = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/LoadBlockModelMixin");
		assertEquals(0, MixinCarrierCallbackAdapters.adapt(unknown, name -> name.equals(MODELS) ? merged : null, references),
				"the defect: nothing says it is a Fabric mod's mixin");
		MixinStubRebind.notePackage("com/zurrtum/create/client/mixin", Ecosystem.FABRIC, "create.client.mixins.json");
		ClassNode mixin = CreateGuestMixinFixture.mixin("com/zurrtum/create/client/mixin/LoadBlockModelMixin");
		assertEquals(1, MixinCarrierCallbackAdapters.adapt(mixin, name -> name.equals(MODELS) ? merged : null, references));
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "deserialize");
		assertEquals(PARSE, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target"));
	}

	/** The class with its local variable tables, as the kernel's target and reference views read it. */
	private static ClassNode full(Path jar, String name) throws Exception {
		TestFixtures.require(TestFixtures.Fixture.STAGED, java.nio.file.Files.isRegularFile(jar), "game jar " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		}
	}
}
