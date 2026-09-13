/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * Covers the unified mod catalogue: the list a player's Mods screen is built from, which is the only list on a
 * Forbric instance that holds all three ecosystems. Each family's own screen lists its own family and is complete
 * for the loader it was written against — NeoForge's listed 3 of 16 jars on a real pack, and Mod Menu showed the
 * rest as bare ids it had no metadata for.
 */
class KernelModCatalogTest {
	@Test
	void everyEcosystemsModsLandInOneList(@TempDir Path dir) throws Exception {
		Path fabric = fabricJar(dir, "voxy", "Voxy", "A level-of-detail renderer.", "assets/voxy/icon.png");
		Path forge = forgeJar(dir, "META-INF/mods.toml", "biomesoplenty", "Biomes O' Plenty", "More biomes.");
		Path neo = forgeJar(dir, "META-INF/neoforge.mods.toml", "iris", "Iris Shaders", "Shader support.");

		KernelModCatalog.publish(List.of(
				mod(Ecosystem.FABRIC, "voxy", "0.2.19", fabric),
				mod(Ecosystem.FORGE, "biomesoplenty", "26.2.0.0.28", forge),
				mod(Ecosystem.NEOFORGE, "iris", "1.11.2", neo)), dir);

		assertEquals(3, ModCatalog.all().size());
		assertEquals(1, ModCatalog.count(Ecosystem.FABRIC));
		assertEquals(1, ModCatalog.count(Ecosystem.FORGE));
		assertEquals(1, ModCatalog.count(Ecosystem.NEOFORGE));
	}

	@Test
	void theDisplayFieldsDiscoveryDoesNotKeepAreReadBackFromTheJar(@TempDir Path dir) throws Exception {
		Path jar = fabricJar(dir, "voxy", "Voxy", "A level-of-detail renderer.", "assets/voxy/icon.png");
		KernelModCatalog.publish(List.of(mod(Ecosystem.FABRIC, "voxy", "0.2.19", jar)), dir);

		ModCatalog.Entry e = ModCatalog.all().get(0);
		assertEquals("Voxy", e.name());
		assertEquals("A level-of-detail renderer.", e.description());
		assertEquals("assets/voxy/icon.png", e.iconPath());
		assertEquals(List.of("cortex"), e.authors());
		assertEquals("0.2.19", e.version(), "the version stays discovery's — the jar's copy can disagree with it");
		assertEquals(jar.getFileName().toString(), e.jar());
	}

	/** A Forge-family jar's description comes from its toml, and neoforge.mods.toml wins over mods.toml. */
	@Test
	void aForgeFamilyModGetsItsTomlDescription(@TempDir Path dir) throws Exception {
		Path jar = forgeJar(dir, "META-INF/mods.toml", "terrablender", "TerraBlender", "A biome API.");
		KernelModCatalog.publish(List.of(mod(Ecosystem.FORGE, "terrablender", "26.2.0.0.2", jar)), dir);
		assertEquals("A biome API.", ModCatalog.all().get(0).description());
		assertEquals("TerraBlender", ModCatalog.all().get(0).name());
	}

	/**
	 * A jar that cannot be read still leaves its mod in the list.
	 *
	 * <p>The whole point of the screen is "what is installed", and a mod dropping out of that answer because its
	 * description would not parse is a worse failure than a missing sentence.
	 */
	@Test
	void anUnreadableJarCostsTheDescriptionAndNotTheMod(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("broken.jar");
		Files.write(jar, "not a zip".getBytes(StandardCharsets.UTF_8));
		KernelModCatalog.publish(List.of(mod(Ecosystem.FABRIC, "broken", "1.0", jar)), dir);

		assertEquals(1, ModCatalog.all().size());
		ModCatalog.Entry e = ModCatalog.all().get(0);
		assertEquals("broken", e.modId());
		assertEquals("", e.description());
		assertEquals("Broken Display Name", e.name(), "discovery's own display name is still the better answer");
	}

	@Test
	void aMissingJarIsNotAnError(@TempDir Path dir) {
		KernelModCatalog.publish(List.of(mod(Ecosystem.FABRIC, "gone", "1.0", Path.of("/nowhere/gone.jar"))), null);
		assertEquals(1, ModCatalog.all().size());
	}

	/** Sorted by NAME, not by ecosystem: which loader built a mod is the thing a player should not have to know. */
	@Test
	void theListIsSortedByNameAcrossEcosystems(@TempDir Path dir) throws Exception {
		Path a = fabricJar(dir, "zoomify", "Zoomify", "", "");
		Path b = forgeJar(dir, "META-INF/mods.toml", "biomesoplenty", "Biomes O' Plenty", "");
		Path c = fabricJar(dir, "modmenu", "Mod Menu", "", "");
		KernelModCatalog.publish(List.of(
				mod(Ecosystem.FABRIC, "zoomify", "1", a),
				mod(Ecosystem.FORGE, "biomesoplenty", "1", b),
				mod(Ecosystem.FABRIC, "modmenu", "1", c)), dir);

		assertEquals(List.of("biomesoplenty", "modmenu", "zoomify"),
				ModCatalog.all().stream().map(ModCatalog.Entry::modId).toList());
	}

	/** One jar, two declared ids — the metadata is read once and asked for each. */
	@Test
	void twoModsInOneJarEachGetTheirOwnRow(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("pair.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			write(zip, "META-INF/mods.toml", """
					modLoader="javafml"
					loaderVersion="[1,)"
					license="Apache-2.0"
					[[mods]]
					modId="one"
					displayName="First"
					description='''the first one'''
					[[mods]]
					modId="two"
					displayName="Second"
					description='''the second one'''
					""");
		}
		KernelModCatalog.publish(List.of(
				mod(Ecosystem.FORGE, "one", "1", jar), mod(Ecosystem.FORGE, "two", "1", jar)), dir);

		assertEquals(2, ModCatalog.all().size());
		assertEquals("the first one", entry("one").description());
		assertEquals("the second one", entry("two").description());
	}

	/**
	 * A jar a mod carries inside itself is not a mod the player installed.
	 *
	 * <p>This is the whole difference between a list of sixteen things someone chose and a list of ninety-one,
	 * most of which are fabric-api's own modules and somebody's Kotlin runtime. Both sets are running; only one
	 * of them is what "what have I installed" is asking.
	 */
	@Test
	void aBundledJarIsNotSomethingThePlayerInstalled(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path jij = Files.createDirectories(dir.resolve(".forbric-kernel/jij/fabric-api"));
		Path installed = fabricJar(mods, "fabric-api", "Fabric API", "", "");
		Path carried = fabricJar(jij, "fabric-biome-api-v1", "Fabric Biome API", "", "");

		KernelModCatalog.publish(List.of(
				mod(Ecosystem.FABRIC, "fabric-api", "0.160.0", installed),
				mod(Ecosystem.FABRIC, "fabric-biome-api-v1", "1.0", carried)), mods);

		assertEquals(List.of("fabric-api"), ModCatalog.all().stream().map(ModCatalog.Entry::modId).toList(),
				"only the jar in mods/ is something the player installed");
		assertEquals(2, ModCatalog.everything().size(), "both are still RUNNING and both are still recorded");
		assertEquals(1, ModCatalog.count(Ecosystem.FABRIC), "the per-family count is of installed mods");
	}

	/** Fabric's extraction keeps the parent in the path, so the bundled jar can say who brought it. */
	@Test
	void aBundledJarNamesTheModThatCarriesIt(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path jij = Files.createDirectories(dir.resolve(".forbric-kernel/jij/fabric-api"));
		KernelModCatalog.publish(List.of(
				mod(Ecosystem.FABRIC, "fabric-api", "1", fabricJar(mods, "fabric-api", "Fabric API", "", "")),
				mod(Ecosystem.FABRIC, "fabric-biome-api-v1", "1",
						fabricJar(jij, "fabric-biome-api-v1", "Biome API", "", ""))), mods);

		assertEquals(List.of("fabric-biome-api-v1"),
				ModCatalog.bundledBy("fabric-api").stream().map(ModCatalog.Entry::modId).toList());
	}

	/**
	 * The Forge families' JarJar extraction flattens into one directory, so the parent is not recoverable there.
	 *
	 * <p>It is still bundled — which is the part that decides whether it is listed — and the honest answer to
	 * "brought by whom" is that the layout does not say, rather than a guess that would read as fact.
	 */
	@Test
	void aJarJarChildIsBundledEvenWhenItsParentIsNotRecorded(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path jarjar = Files.createDirectories(dir.resolve(".forbric-kernel/jarjar"));
		KernelModCatalog.publish(List.of(
				mod(Ecosystem.NEOFORGE, "iris", "1", forgeJar(mods, "META-INF/neoforge.mods.toml", "iris", "Iris", "")),
				mod(Ecosystem.NEOFORGE, "spruceui", "1",
						forgeJar(jarjar, "META-INF/neoforge.mods.toml", "spruceui", "SpruceUI", ""))), mods);

		assertEquals(List.of("iris"), ModCatalog.all().stream().map(ModCatalog.Entry::modId).toList());
		assertEquals(KernelModCatalog.UNKNOWN_PARENT,
				ModCatalog.everything().stream().filter(e -> e.modId().equals("spruceui")).findFirst()
						.orElseThrow().bundledBy());
	}

	/** No mods dir means the distinction cannot be drawn, and then nothing is hidden. */
	@Test
	void withoutAModsDirectoryEverythingCountsAsInstalled(@TempDir Path dir) throws Exception {
		Path jar = fabricJar(dir, "voxy", "Voxy", "", "");
		KernelModCatalog.publish(List.of(mod(Ecosystem.FABRIC, "voxy", "1", jar)), null);
		assertEquals(1, ModCatalog.all().size(), "showing too much beats hiding a mod that is really there");
	}

	@Test
	void publishingNothingEmptiesTheList(@TempDir Path dir) throws Exception {
		KernelModCatalog.publish(List.of(mod(Ecosystem.FABRIC, "x", "1", fabricJar(dir, "x", "X", "", ""))), dir);
		assertTrue(ModCatalog.all().size() > 0);
		KernelModCatalog.publish(List.of());
		assertEquals(List.of(), ModCatalog.all());
	}

	/** The screen renders these straight; a null in any of them is a crash mid-frame, not a blank line. */
	@Test
	void noFieldIsEverNull() {
		ModCatalog.Entry e = new ModCatalog.Entry(Ecosystem.FABRIC, "x", null, null, null, null, null, null, null);
		assertEquals("x", e.name(), "a nameless mod falls back to its id rather than rendering nothing");
		assertEquals("", e.version());
		assertEquals("", e.description());
		assertEquals(List.of(), e.authors());
		assertEquals("", e.jar());
		assertEquals("", e.iconPath());
	}

	@Test
	void theListIsImmutableToItsReaders() {
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.FABRIC, "x", "X", "1", "", List.of(), "x.jar", "", "")));
		List<ModCatalog.Entry> once = ModCatalog.all();
		assertSame(once, ModCatalog.all());
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, once::clear);
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static ModCatalog.Entry entry(String modId) {
		return ModCatalog.all().stream().filter(e -> e.modId().equals(modId)).findFirst().orElseThrow();
	}

	private static DiscoveredMod mod(Ecosystem ecosystem, String id, String version, Path jar) {
		String display = Character.toUpperCase(id.charAt(0)) + id.substring(1) + " Display Name";
		return new DiscoveredMod(ecosystem, id, version, display, List.of(), List.of(), null,
				jar.toString());
	}

	private static Path fabricJar(Path dir, String id, String name, String description, String icon)
			throws IOException {
		Path jar = dir.resolve(id + ".jar");
		String json = """
				{
				  "schemaVersion": 1,
				  "id": "%s",
				  "version": "9.9.9",
				  "name": "%s",
				  "description": "%s",
				  "authors": ["cortex"]%s
				}
				""".formatted(id, name, description, icon.isEmpty() ? "" : ",\n  \"icon\": \"" + icon + "\"");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			write(zip, "fabric.mod.json", json);
		}
		return jar;
	}

	private static Path forgeJar(Path dir, String entry, String id, String name, String description)
			throws IOException {
		Path jar = dir.resolve(id + ".jar");
		String toml = """
				modLoader="javafml"
				loaderVersion="[1,)"
				license="Apache-2.0"
				[[mods]]
				modId="%s"
				displayName="%s"
				description='''%s'''
				""".formatted(id, name, description);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			write(zip, entry, toml);
		}
		return jar;
	}

	private static void write(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
