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

package net.forbric.kernel.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.metadata.DiscoveredMod;
import net.forbric.kernel.metadata.UnifiedDependency;

class ForbricModDiscovererTest {
	private static final String FABRIC_JSON = "{\n"
			+ "  \"schemaVersion\": 1,\n"
			+ "  \"id\": \"examplefabric\",\n"
			+ "  \"version\": \"2.3.4\",\n"
			+ "  \"name\": \"Example Fabric Mod\",\n"
			+ "  \"environment\": \"*\",\n"
			+ "  \"entrypoints\": { \"main\": [\"com.example.FabricMod\"] },\n"
			+ "  \"mixins\": [\"examplefabric.mixins.json\"],\n"
			+ "  \"accessWidener\": \"examplefabric.accesswidener\",\n"
			+ "  \"depends\": { \"fabricloader\": \">=0.15.0\", \"minecraft\": \"~1.21.11\" }\n"
			+ "}\n";

	private static final String FORGE_TOML = "modLoader=\"javafml\"\n"
			+ "loaderVersion=\"[47,)\"\n"
			+ "license=\"MIT\"\n\n"
			+ "[[mods]]\n"
			+ "modId=\"exampleforge\"\n"
			+ "version=\"${file.jarVersion}\"\n"
			+ "displayName=\"Example Forge Mod\"\n\n"
			+ "[[dependencies.exampleforge]]\n"
			+ "modId=\"forge\"\n"
			+ "mandatory=true\n"
			+ "versionRange=\"[47,)\"\n\n"
			+ "[[mixins]]\n"
			+ "config=\"exampleforge.mixins.json\"\n";

	private static Path writeJar(Path dir, String fileName, Map<String, String> entries, String implVersion) throws Exception {
		Path jar = dir.resolve(fileName);

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

		if (implVersion != null) {
			manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implVersion);
		}

		try (OutputStream os = Files.newOutputStream(jar);
				JarOutputStream jos = new JarOutputStream(os, manifest)) {
			for (Map.Entry<String, String> e : entries.entrySet()) {
				jos.putNextEntry(new ZipEntry(e.getKey()));
				jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				jos.closeEntry();
			}
		}

		return jar;
	}

	private static Map<String, String> entry(String k, String v) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}

	private static DiscoveredMod byId(List<DiscoveredMod> mods, String id) {
		return mods.stream().filter(m -> id.equals(m.getId())).findFirst().orElse(null);
	}

	@Test
	void neoforgeManifestYieldsNeoForgeEcosystem(@TempDir Path mods) throws Exception {
		String neoToml = FORGE_TOML.replace("exampleforge", "exampleneo");
		Map<String, String> entries = entry("META-INF/neoforge.mods.toml", neoToml);
		entries.put("exampleneo.mixins.json", "{ \"package\": \"com.example.mixin\" }");
		Path jar = writeJar(mods, "neo-mod.jar", entries, "1.0.0");

		List<DiscoveredMod> found = new ForbricModDiscoverer().discoverJar(jar);

		assertEquals(1, found.size());
		assertEquals(Ecosystem.NEOFORGE, found.get(0).getEcosystem());
		assertEquals("exampleneo", found.get(0).getId());
	}

	@Test
	void dualTomlJarReportsBothFamilies(@TempDir Path mods) throws Exception {
		// A multiloader build shipping one toml per Forge family: BOTH are reported truthfully, each under
		// its own ecosystem — the boot policy (active game base) picks which one loads.
		Map<String, String> entries = entry("META-INF/mods.toml", FORGE_TOML);
		entries.put("META-INF/neoforge.mods.toml", FORGE_TOML.replace("mandatory=true", "type=\"required\""));
		Path jar = writeJar(mods, "dual-mod.jar", entries, "5.6.7");

		List<DiscoveredMod> found = new ForbricModDiscoverer().discoverJar(jar);

		assertEquals(2, found.size());
		assertEquals(1, found.stream().filter(m -> m.getEcosystem() == Ecosystem.NEOFORGE).count());
		assertEquals(1, found.stream().filter(m -> m.getEcosystem() == Ecosystem.FORGE).count());
		assertTrue(found.stream().allMatch(m -> "exampleforge".equals(m.getId())));
	}

	@Test
	void discoversBothEcosystemsInOneFolder(@TempDir Path mods) throws Exception {
		writeJar(mods, "fabric-mod.jar", entry("fabric.mod.json", FABRIC_JSON), null);
		Map<String, String> forgeEntries = entry("META-INF/mods.toml", FORGE_TOML);
		forgeEntries.put("exampleforge.mixins.json", "{ \"package\": \"com.example.mixin\" }"); // declared configs must exist in the jar
		writeJar(mods, "forge-mod.jar", forgeEntries, "5.6.7");

		// A jar that ships nothing recognizable must be ignored.
		writeJar(mods, "not-a-mod.jar", entry("com/example/Thing.class", "noise"), null);

		List<DiscoveredMod> found = new ForbricModDiscoverer().discover(mods);

		assertEquals(2, found.size(), "should find exactly the Fabric and Forge mods");

		DiscoveredMod fabric = byId(found, "examplefabric");
		assertNotNull(fabric);
		assertEquals(Ecosystem.FABRIC, fabric.getEcosystem());
		assertEquals("2.3.4", fabric.getVersion());
		assertEquals("Example Fabric Mod", fabric.getDisplayName());
		assertTrue(fabric.getMixinConfigs().contains("examplefabric.mixins.json"));
		assertEquals("examplefabric.accesswidener", fabric.getAccessConfig());
		assertTrue(fabric.getDependencies().stream()
				.anyMatch(d -> d.getModId().equals("fabricloader") && d.getVersionConstraint().equals(">=0.15.0")));

		DiscoveredMod forge = byId(found, "exampleforge");
		assertNotNull(forge);
		assertEquals(Ecosystem.FORGE, forge.getEcosystem());
		assertEquals("5.6.7", forge.getVersion(), "${file.jarVersion} should resolve from the manifest");
		assertTrue(forge.getMixinConfigs().contains("exampleforge.mixins.json"));

		UnifiedDependency forgeDep = forge.getDependencies().get(0);
		assertEquals("forge", forgeDep.getModId());
		assertEquals(">=47", forgeDep.getVersionConstraint(), "Maven range [47,) should translate to >=47");
		assertTrue(forgeDep.isMandatory());
	}

	@Test
	void aSingleJarMayDeclareBothLoaders(@TempDir Path mods) throws Exception {
		Map<String, String> both = new LinkedHashMap<>();
		both.put("fabric.mod.json", FABRIC_JSON);
		both.put("META-INF/mods.toml", FORGE_TOML);

		Path jar = writeJar(mods, "multi-loader.jar", both, "9.9.9");

		List<DiscoveredMod> found = new ForbricModDiscoverer().discoverJar(jar);

		assertEquals(2, found.size());
		assertEquals(1, found.stream().filter(m -> m.getEcosystem() == Ecosystem.FABRIC).count());
		assertEquals(1, found.stream().filter(m -> m.getEcosystem() == Ecosystem.FORGE).count());
	}

	@Test
	void dropsDeclaredButAbsentMixinConfig(@TempDir Path mods) throws Exception {
		// Multiloader jars often over-declare a sibling loader's mixin config (a NeoForge-style [[mixins]] block
		// listing a *.neoforge.mixins.json that only ships in the NeoForge jar). Handing such a phantom config to
		// Mixin aborts the game, so a declared config that isn't present in the jar must be dropped; one that is
		// present is kept.
		String toml = "modLoader=\"javafml\"\n"
				+ "loaderVersion=\"[47,)\"\n"
				+ "[[mods]]\n"
				+ "modId=\"multimixin\"\n"
				+ "version=\"1.0.0\"\n"
				+ "[[mixins]]\n"
				+ "config=\"multimixin.mixins.json\"\n"        // present below -> kept
				+ "[[mixins]]\n"
				+ "config=\"multimixin.neoforge.mixins.json\"\n"; // absent -> dropped

		Map<String, String> entries = entry("META-INF/mods.toml", toml);
		entries.put("multimixin.mixins.json", "{ \"package\": \"com.example.mixin\" }");

		Path jar = writeJar(mods, "multimixin.jar", entries, "1.0.0");
		DiscoveredMod forge = byId(new ForbricModDiscoverer().discoverJar(jar), "multimixin");

		assertNotNull(forge);
		assertTrue(forge.getMixinConfigs().contains("multimixin.mixins.json"), "present config kept");
		assertFalse(forge.getMixinConfigs().contains("multimixin.neoforge.mixins.json"), "absent config dropped");
	}
}
