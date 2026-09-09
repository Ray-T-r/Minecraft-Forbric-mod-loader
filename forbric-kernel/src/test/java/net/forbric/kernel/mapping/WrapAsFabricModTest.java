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

package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;

class WrapAsFabricModTest {
	@Test
	void writesMixinsAndMultipleForgeClasses(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("com/example/Placeholder.class"));
			zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
			zip.closeEntry();
		}

		ForgeModRemapper.wrapAsFabricMod(jar, "examplemod", "3.1.4",
				List.of("com.example.ModA", "com.example.ModB"),
				List.of("examplemod.mixins.json", "examplemod.client.mixins.json"));

		String json;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			json = new String(Files.readAllBytes(fs.getPath("fabric.mod.json")), StandardCharsets.UTF_8);
		}

		assertTrue(json.contains("\"id\": \"examplemod\""), json);
		assertTrue(json.contains("\"version\": \"3.1.4\""), json);
		// Wrapped mods declare NO entrypoint; the Knot-loaded NeoForge runtime driver discovers @Mod classes
		// from the forbric:forgeClasses custom key below.
		assertTrue(!json.contains("\"entrypoints\""), json);
		assertTrue(json.contains("\"mixins\":"), json);
		assertTrue(json.contains("examplemod.mixins.json"), json);
		assertTrue(json.contains("examplemod.client.mixins.json"), json);
		assertTrue(json.contains("\"forbric:forgeClass\": \"com.example.ModA\""), json);
		assertTrue(json.contains("com.example.ModB"), json);
		// Family stamp: legacy overloads default to the traditional-Forge family (wrap7 format).
		assertTrue(json.contains("\"forbric:ecosystem\": \"forge\""), json);
	}

	@Test
	void neoforgeWrapStampsItsFamily(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("neo.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("a.txt"));
			zip.write("x".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		ForgeModRemapper.wrapAsFabricMod(jar, "neomod", "1.0.0", List.of("com.example.NeoMod"), List.of(),
				List.of(), List.of(), null, Ecosystem.NEOFORGE);

		String json;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			json = new String(Files.readAllBytes(fs.getPath("fabric.mod.json")), StandardCharsets.UTF_8);
		}

		assertTrue(json.contains("\"forbric:ecosystem\": \"neoforge\""), json);
	}

	@Test
	void singleClassConvenienceOmitsMixinsArray(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("a.txt"));
			zip.write("x".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		ForgeModRemapper.wrapAsFabricMod(jar, "solo", "1.0.0", "com.example.Solo");

		String json;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			json = new String(Files.readAllBytes(fs.getPath("fabric.mod.json")), StandardCharsets.UTF_8);
		}

		assertTrue(json.contains("\"forbric:forgeClass\": \"com.example.Solo\""), json);
		assertTrue(!json.contains("\"mixins\":"), "no mixins array when none declared: " + json);
	}

	@Test
	void emitsDependenciesAndSkipsPlatformIds(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("a.txt"));
			zip.write("x".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		ForgeModRemapper.wrapAsFabricMod(jar, "examplemod", "1.0.0",
				List.of("com.example.Mod"), List.of(),
				List.of(
						new UnifiedDependency("neoforge", ">=21.11", true),            // platform -> skipped
						new UnifiedDependency("minecraft", ">=1.21.11 <1.22", true),   // platform -> skipped
						new UnifiedDependency("jei", ">=15.0.0", true),                // hard -> depends
						new UnifiedDependency("cloth_config", ">=11.0.0", false)));     // optional -> recommends

		String json;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			json = new String(Files.readAllBytes(fs.getPath("fabric.mod.json")), StandardCharsets.UTF_8);
		}

		assertTrue(json.contains("\"depends\":"), json);
		assertTrue(json.contains("\"jei\": \">=15.0.0\""), json);
		assertTrue(json.contains("\"recommends\":"), json);
		assertTrue(json.contains("\"cloth_config\": \">=11.0.0\""), json);
		assertTrue(!json.contains("neoforge"), "platform dep neoforge must be skipped: " + json);
		assertTrue(!json.contains("\"minecraft\""), "platform dep minecraft must be skipped: " + json);
	}
}
