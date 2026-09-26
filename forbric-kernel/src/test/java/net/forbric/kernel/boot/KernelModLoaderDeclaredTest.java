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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.Ecosystem;

/**
 * What {@link KernelModLoader#declaredMods} reads out of the jars it is handed — the description every NeoForge
 * container is built with.
 *
 * <p>The shape that paid for it is LibJF: an outer {@code lowcodefml} jar carrying twelve modules as jar-in-jar,
 * each declaring its entry points in its own {@code [modproperties]} table. Discovery's presence list only knows
 * the outer jar, so those tables never reached the containers built for the modules.
 */
class KernelModLoaderDeclaredTest {
	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		System.clearProperty("forbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
	}

	@Test
	void aNestedModIsDescribedByItsOwnManifest() throws Exception {
		Path outer = neoJar(tmp.resolve("mods/libjf.jar"), "lowcodefml", "libjf", "26.2.2", "");
		Path nested = neoJar(tmp.resolve("candidates/ab/libjf-translate-v1.jar"), "javafml", "libjf_translate_v1",
				"26.2.2+forge", "[[modproperties.libjf_translate_v1.\"libjf:entrypoints\".\"libjf:config\"]]\n"
						+ "value = \"dev.jfronny.libjf.translate.impl.TranslateConfig\"\n");

		Map<String, KernelModLoader.Declared> declared = KernelModLoader.declaredMods(List.of(outer, nested));

		KernelModLoader.Declared translate = declared.get("libjf_translate_v1");
		assertNotNull(translate, "the nested module must be described at all");
		assertEquals(nested, translate.jar(), "and by the jar that declares it, not the one that carried it");
		assertEquals("26.2.2+forge", translate.mod().getVersion());
		assertTrue(translate.mod().getModProperties().containsKey("libjf:entrypoints"),
				"its [modproperties] table is what LibJF finds libjf:config in: " + translate.mod().getModProperties());
		assertNotNull(declared.get("libjf"), "the outer jar declares a mod of its own");
	}

	@Test
	void onlyTheJarThatDeclaresAModDescribesItsContainer() throws Exception {
		Path first = neoJar(tmp.resolve("a.jar"), "javafml", "shared", "1.0", "");
		Path second = neoJar(tmp.resolve("b.jar"), "javafml", "shared", "2.0", "");
		Map<String, KernelModLoader.Declared> declared = KernelModLoader.declaredMods(List.of(first, second));

		assertEquals("1.0", KernelModLoader.declaredIn(declared, "shared", first).getVersion(), "first declaration wins");
		assertNull(KernelModLoader.declaredIn(declared, "shared", second),
				"a container built from b.jar must not be described with a.jar's claim to the same id");
		assertNull(KernelModLoader.declaredIn(declared, "absent", first));
		assertNull(KernelModLoader.declaredIn(declared, null, first));
	}

	@Test
	void theFamilyThatDoesNotOwnAUniversalJarDeclaresNothing() throws Exception {
		Path universal = tmp.resolve("universal.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(universal))) {
			put(zip, "META-INF/neoforge.mods.toml", toml("javafml", "universalmod", "1.0", ""));
			put(zip, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"universalmod\",\"version\":\"1.0\"}");
		}

		MultiLoaderArbiter.reset();
		assertEquals(Ecosystem.NEOFORGE, KernelModLoader.declaredMods(List.of(universal)).get("universalmod").mod()
				.getEcosystem(), "by default a universal jar belongs to NeoForge and declares its NeoForge mod");

		System.setProperty("forbric.multiLoaderPreference", "fabric");
		MultiLoaderArbiter.reset();
		assertFalse(KernelModLoader.declaredMods(List.of(universal)).containsKey("universalmod"),
				"a jar handed to Fabric has no NeoForge mod here, and no NeoForge container may be described by it");
	}

	@Test
	void anUnreadableJarCostsOnlyItself() throws Exception {
		Path broken = Files.writeString(tmp.resolve("broken.jar"), "not a zip");
		Path good = neoJar(tmp.resolve("good.jar"), "javafml", "good", "1.0", "");
		Map<String, KernelModLoader.Declared> declared = KernelModLoader.declaredMods(List.of(broken, good));
		assertEquals(List.of("good"), List.copyOf(declared.keySet()));
	}

	// --- fixtures ---

	static Path neoJar(Path jar, String language, String modId, String version, String extra) throws IOException {
		Files.createDirectories(jar.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			put(zip, "META-INF/neoforge.mods.toml", toml(language, modId, version, extra));
		}
		return jar;
	}

	static String toml(String language, String modId, String version, String extra) {
		return "modLoader=\"" + language + "\"\n"
				+ "loaderVersion=\"[1,)\"\n"
				+ "license=\"MIT\"\n"
				+ "\n"
				+ "[[mods]]\n"
				+ "modId=\"" + modId + "\"\n"
				+ "version=\"" + version + "\"\n"
				+ "displayName=\"" + modId + " display\"\n"
				+ "\n"
				+ extra;
	}

	static void put(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
