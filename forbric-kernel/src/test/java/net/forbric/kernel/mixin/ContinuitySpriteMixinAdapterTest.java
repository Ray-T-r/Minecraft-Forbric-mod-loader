package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContinuitySpriteMixinAdapterTest {
	private ClassNode mixin() throws Exception {
		Path jar = Path.of("build/compat-inputs/player-loading/mods/continuity-3.0.1+26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "actual Continuity fixture required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry("me/pepperbell/continuity/client/mixin/SpriteSourceListMixin.class")).readAllBytes());
		}
	}

	@Test void actualCallbacksMoveToTheLiveOverloadAndKeepTheOriginalLoaderMapArgument() throws Exception {
		ClassNode mixin = mixin();
		ClassNode target = StagedFabricMixinFixture.game("net/minecraft/client/renderer/texture/atlas/SpriteSourceList", false);
		assertEquals(1, MixinSpriteLoaderCallbackAdapter.adapt(mixin, name -> target));
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "continuity$afterLoadSources");
		assertTrue(handler.desc.contains("Ljava/util/Set;"));
		assertTrue(String.valueOf(MixinFit.value(MixinFit.injectorOf(handler), "method")).contains("Ljava/util/Set;"));
		assertEquals(List.of(0, 1, 3, 4), java.util.stream.StreamSupport.stream(handler.instructions.spliterator(), false)
				.filter(i -> i instanceof VarInsnNode).map(i -> ((VarInsnNode)i).var).toList());
		MethodNode original = StagedFabricMixinFixture.method(mixin, "continuity$afterLoadSources$forbricOriginal");
		assertNull(MixinFit.injectorOf(original));
		assertTrue(handler.instructions.get(4) instanceof MethodInsnNode call && call.desc.equals(original.desc));
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
		assertEquals(0, MixinSpriteLoaderCallbackAdapter.adapt(mixin, name -> target));
	}

	@Test void aChangedLocalMapLayoutIsRefused() throws Exception {
		ClassNode mixin = mixin();
		ClassNode target = StagedFabricMixinFixture.game("net/minecraft/client/renderer/texture/atlas/SpriteSourceList", false);
		for (MethodNode method : target.methods) for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == 3) store.var = 6;
		}
		assertEquals(0, MixinSpriteLoaderCallbackAdapter.adapt(mixin, name -> target));
		assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "continuity$afterLoadSources")));
	}

	@Test void vanillaAndDisabledRepairKeepTheUpstreamMixin() throws Exception {
		ClassNode vanilla = StagedFabricMixinFixture.game("net/minecraft/client/renderer/texture/atlas/SpriteSourceList", true);
		assertEquals(0, MixinSpriteLoaderCallbackAdapter.adapt(mixin(), name -> vanilla));
		String previous = System.setProperty(MixinSpriteLoaderCallbackAdapter.PROPERTY, "off");
		try {
			ClassNode merged = StagedFabricMixinFixture.game("net/minecraft/client/renderer/texture/atlas/SpriteSourceList", false);
			assertEquals(0, MixinSpriteLoaderCallbackAdapter.adapt(mixin(), name -> merged));
		} finally {
			if (previous == null) System.clearProperty(MixinSpriteLoaderCallbackAdapter.PROPERTY); else System.setProperty(MixinSpriteLoaderCallbackAdapter.PROPERTY, previous);
		}
	}
}
