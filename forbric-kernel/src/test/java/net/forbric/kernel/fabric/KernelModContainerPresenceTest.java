package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A presence identity reads its mod's files from the jar that loaded the mod, and owns no jar itself. */
class KernelModContainerPresenceTest {
	@TempDir Path dir;

	@Test void aPresenceIdentityFindsItsModsOwnFiles() throws Exception {
		Path jar = dir.resolve("[Lambd的动态光源] lambdynamiclights-4.12.2+26.2.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("lambdynlights.toml")); zip.write("mode = \"fancy\"\n".getBytes()); zip.closeEntry();
		}
		KernelModContainer alias = KernelModContainer.presence(KernelModMetadata.builtin("lambdynlights", "4.12.2", "LambDynamicLights"), jar);
		assertNull(alias.getJar(), "no code, mixins or assets are taken from a presence identity");
		assertEquals("builtin", alias.getMetadata().getType(), "Fabric's resource loader skips builtin mods");
		var file = alias.findPath("lambdynlights.toml");
		assertTrue(file.isPresent(), "the mod's default config is found in the jar that loaded it");
		assertEquals("mode = \"fancy\"\n", Files.readString(file.get()));
		assertTrue(alias.findPath("missing.toml").isEmpty());
	}

	@Test void aPresenceIdentityWithNothingToReadStaysEmpty() {
		KernelModContainer alias = KernelModContainer.presence(KernelModMetadata.builtin("somemod", "1", "Some Mod"), null);
		assertTrue(alias.getRootPaths().isEmpty());
		assertTrue(alias.findPath("anything").isEmpty());
	}
}
