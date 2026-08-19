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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JarInJar extraction, driven against real jars.
 *
 * <p>The case that matters is two mods nesting the SAME library under different file names — which is what
 * cookingforblockheads and shogi do with net.blay09.mods:shogi-api, at 26.2.0.1-SNAPSHOT and 26.2.0.3. A
 * name-keyed dedupe extracts both and puts two builds of one library on the class loader, where their classes
 * share names but not bytes and the winner is whichever the loader reaches first.
 */
class JarJarExtractionTest {
	@Test
	void twoVersionsOfOneArtifactResolveToTheHighest(@TempDir Path dir) throws Exception {
		Path hostA = hostNesting(dir.resolve("cookingforblockheads.jar"),
				"META-INF/jarjar/shogi-api-26.2.0.1-SNAPSHOT.jar", "net.blay09.mods", "shogi-api", "26.2.0.1");
		Path hostB = hostNesting(dir.resolve("shogi-neoforge.jar"),
				"META-INF/jarjar/net.blay09.mods.shogi-api-26.2.0.3.jar", "net.blay09.mods", "shogi-api", "26.2.0.3");

		List<Path> extracted = KernelBoot.extractForgeFamilyJarJar(List.of(hostA, hostB), dir.resolve("game"));

		assertEquals(List.of("net.blay09.mods.shogi-api-26.2.0.3.jar"),
				extracted.stream().map(p -> p.getFileName().toString()).toList());
	}

	@Test
	void theOrderOfTheHostsDoesNotChangeTheWinner(@TempDir Path dir) throws Exception {
		Path hostA = hostNesting(dir.resolve("shogi-neoforge.jar"),
				"META-INF/jarjar/net.blay09.mods.shogi-api-26.2.0.3.jar", "net.blay09.mods", "shogi-api", "26.2.0.3");
		Path hostB = hostNesting(dir.resolve("cookingforblockheads.jar"),
				"META-INF/jarjar/shogi-api-26.2.0.1-SNAPSHOT.jar", "net.blay09.mods", "shogi-api", "26.2.0.1");

		List<Path> extracted = KernelBoot.extractForgeFamilyJarJar(List.of(hostA, hostB), dir.resolve("game"));

		assertEquals(List.of("net.blay09.mods.shogi-api-26.2.0.3.jar"),
				extracted.stream().map(p -> p.getFileName().toString()).toList());
	}

	@Test
	void twoDifferentArtifactsAreBothKept(@TempDir Path dir) throws Exception {
		Path hostA = hostNesting(dir.resolve("a.jar"), "META-INF/jarjar/liba-1.0.jar", "com.example", "liba", "1.0");
		Path hostB = hostNesting(dir.resolve("b.jar"), "META-INF/jarjar/libb-1.0.jar", "com.example", "libb", "1.0");

		List<Path> extracted = KernelBoot.extractForgeFamilyJarJar(List.of(hostA, hostB), dir.resolve("game"));

		assertEquals(2, extracted.size());
	}

	@Test
	void aFabricStyleNestedJarWithNoMetadataStillComesThrough(@TempDir Path dir) throws Exception {
		// META-INF/jars/ children carry no jarjar metadata; they keep the file-name rule and must not be dropped.
		Path host = dir.resolve("apollib.jar");
		try (OutputStream out = Files.newOutputStream(host); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, "META-INF/jars/json5-java-3.0.0.jar", emptyJar());
		}

		List<Path> extracted = KernelBoot.extractForgeFamilyJarJar(List.of(host), dir.resolve("game"));

		assertEquals(List.of("json5-java-3.0.0.jar"),
				extracted.stream().map(p -> p.getFileName().toString()).toList());
	}

	@Test
	void aNestedJarNestingItsOwnIsStillReached(@TempDir Path dir) throws Exception {
		// Tectonic bundles apollib, apollib bundles json5-java, and one level of extraction leaves json5 on
		// nobody's classpath — which is how Tectonic's @Mod constructor died on Json5Lexer.
		Path inner = dir.resolve("apollib-source.jar");
		try (OutputStream out = Files.newOutputStream(inner); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, "META-INF/jars/json5-java-3.0.0.jar", emptyJar());
		}
		Path host = dir.resolve("tectonic.jar");
		try (OutputStream out = Files.newOutputStream(host); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, "META-INF/jarjar/apollib-1.1.4.jar", Files.readAllBytes(inner));
		}

		List<Path> extracted = KernelBoot.extractForgeFamilyJarJar(List.of(host), dir.resolve("game"));

		assertEquals(2, extracted.size());
		assertTrue(extracted.stream().anyMatch(p -> p.getFileName().toString().startsWith("json5-java")),
				"the innermost library must reach the class loader too");
	}

	// ---------------------------------------------------------------- fixtures

	/** A host jar nesting one library, with the JarJar metadata that names its coordinate. */
	private static Path hostNesting(Path jar, String entry, String group, String artifact, String version)
			throws Exception {
		String metadata = "{\"jars\":[{\"identifier\":{\"group\":\"" + group + "\",\"artifact\":\"" + artifact
				+ "\"},\"version\":{\"range\":\"[" + version + ",)\",\"artifactVersion\":\"" + version + "\"},"
				+ "\"path\":\"" + entry + "\"}]}";
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, "META-INF/jarjar/metadata.json", metadata.getBytes(StandardCharsets.UTF_8));
			write(zip, entry, emptyJar());
		}
		return jar;
	}

	private static byte[] emptyJar() throws Exception {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			write(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
		}
		return bytes.toByteArray();
	}

	private static void write(ZipOutputStream zip, String name, byte[] content) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
	}
}
