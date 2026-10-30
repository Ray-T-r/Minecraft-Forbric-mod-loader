package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.MixinConfigOwners;

/** NeoForge's own jar declares a mixin config; the kernel registers it as the genuine loader does. */
class RuntimeMixinConfigsTest {
	private static final Path STAGED = TestFixtures.stagedRoot();

	@Test void neoForgesRuntimeJarContributesItsOwnAccessorConfig() {
		Path neo = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.requireFile(Fixture.STAGED, neo, "staged NeoForge runtime absent");
		List<KernelForgeFamilyMixins.ForgeMixinConfig> configs = KernelBoot.discoverForgeMixinConfigs(List.of(neo), "runtime jar");
		assertEquals(List.of(new KernelForgeFamilyMixins.ForgeMixinConfig("neoforge.mixins.json", "neoforge", neo, Ecosystem.NEOFORGE)), configs);
		assertEquals(List.of("neoforge.mixins.json"),
				KernelForgeFamilyMixins.select(configs).stream().map(MixinConfigOwners.Owned::config).toList(),
				"nothing arbitrates NeoForge's own jar away");
	}

	@Test void minecraftForgesRuntimeDeclaresNone() {
		Path forge = STAGED.resolve("merged-base/forge-runtime-interop.jar");
		TestFixtures.requireFiles(Fixture.STAGED, "staged MinecraftForge runtime absent", forge);
		assertEquals(List.of(), KernelBoot.discoverForgeMixinConfigs(List.of(forge), "runtime jar"));
	}
}
