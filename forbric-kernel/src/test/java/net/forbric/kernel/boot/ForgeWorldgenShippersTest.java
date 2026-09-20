package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Naming the mods that lose the feature when the bridge is off must not name NeoForge-only shippers. */
class ForgeWorldgenShippersTest {
	@TempDir Path temporary;

	@Test
	void forgeBiomeAndStructureModifierShippersAreNamedAndNeoForgeOnlyJarsAreNot() throws Exception {
		Path biome = jar("bop.jar", "data/bop/forge/biome_modifier/a.json", "data/bop/forge/biome_modifier/b.json");
		Path neoOnly = jar("neo.jar", "data/x/neoforge/biome_modifier/a.json");
		Path structure = jar("dungeons.jar", "data/dg/forge/structure_modifier/b.json");
		Path plain = jar("plain.jar", "assets/plain/lang/en_us.json");
		List<ForgeWorldgenShippers.Shipper> shippers = ForgeWorldgenShippers.scan(List.of(biome, neoOnly, structure, plain));
		assertEquals(List.of("bop.jar", "dungeons.jar"), shippers.stream().map(ForgeWorldgenShippers.Shipper::jar).toList());
		assertEquals(Set.of("bop"), shippers.get(0).namespaces());
		assertEquals(2, shippers.get(0).files());
		assertEquals(Set.of("dg"), shippers.get(1).namespaces());
	}

	@Test
	void anUnreadableJarIsSkippedNotFatal() throws Exception {
		Path broken = temporary.resolve("broken.jar");
		java.nio.file.Files.writeString(broken, "not a zip");
		assertTrue(ForgeWorldgenShippers.scan(List.of(broken)).isEmpty());
	}

	private Path jar(String name, String... entries) throws Exception {
		Path path = temporary.resolve(name);
		try (ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
			for (String entry : entries) {
				out.putNextEntry(new ZipEntry(entry));
				OutputStream ignored = out;
				ignored.write("{}".getBytes());
				out.closeEntry();
			}
		}
		return path;
	}
}
