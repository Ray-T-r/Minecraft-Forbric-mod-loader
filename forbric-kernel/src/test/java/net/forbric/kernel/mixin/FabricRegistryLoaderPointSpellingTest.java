/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;

/**
 * fabric-registry-sync's registry-loader wrap with its point written as Mixin also reads it — with whitespace, with the
 * owner dotted — is recognised ({@code matches}) and restored ({@code adapt}) exactly as the descriptor spelling is: a
 * mixin the kernel recognises but cannot adapt is refused outright (MergedBaseMixinCompat), so recognising a spelling
 * the adapter then misreads would cost the mod its whole mixin.
 */
class FabricRegistryLoaderPointSpellingTest {
	private static ClassNode mixin() throws Exception {
		Path path = Path.of("build/compat-inputs/player-loading/api/fabric-registry-sync-v0-7.1.1+c7bd5b8e9e.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(path), path + " absent");
		try (ZipFile zip = new ZipFile(path.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry("net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin.class")).readAllBytes());
		}
	}

	@Test void theLoaderWrapWrittenAnotherWayIsRestoredTheSame() throws Exception {
		ClassNode target = StagedFabricMixinFixture.game("net/minecraft/resources/RegistryDataLoader", false);
		List<Function<MixinFit.Member, String>> forms = List.of(
				m -> "L" + m.owner() + "; " + m.name() + " " + m.desc(),
				m -> m.owner().replace('/', '.') + "." + m.name() + m.desc());
		for (Function<MixinFit.Member, String> form : forms) {
			ClassNode mixin = mixin();
			boolean respelled = false;
			for (MethodNode method : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector == null || !injector.desc.endsWith("/WrapOperation;")) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
					if (member == null || member.owner() == null || member.desc() == null) continue;
					MixinPlayerWorldCallbackAdapter.set(at, "target", form.apply(member));
					respelled = true;
				}
			}
			assertTrue(respelled, "premise: the wrap names its call");
			assertTrue(FabricRegistryLoaderMixinAdapter.matches(mixin));
			assertEquals(2, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> target));
			CarpetMixinAdapterTest.verify(mixin);
			assertEquals(0, FabricRegistryLoaderMixinAdapter.adapt(mixin, name -> target), "idempotence");
		}
	}
}
