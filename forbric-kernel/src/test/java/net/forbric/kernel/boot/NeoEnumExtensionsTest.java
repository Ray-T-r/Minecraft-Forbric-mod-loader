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
 * Covers the parts of the enum-extension capability that do not need a live NeoForge.
 *
 * <p>The NeoForge SPI is not on the test classpath, which makes these the SPI-ABSENT tests: the capability must
 * install nothing and report nothing rather than throwing, so a runtime without the enumextension package (or a
 * Fabric-only instance) still boots. The transform itself is NeoForge's own compiled code and is verified by
 * running the Odyssey pack, not here.
 */
class NeoEnumExtensionsTest {

	@Test
	void loadReportsZeroWhenTheSpiIsAbsentRatherThanThrowing(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir.resolve("toolbelt-1.0.jar"), "META-INF/enumextensions.json", "{\"entries\":[]}");

		// This classloader has no net.neoforged.* at all — the same shape as a runtime without the package.
		assertEquals(0, NeoEnumExtensions.load(getClass().getClassLoader(), List.of(jar)));
	}

	@Test
	void loadReportsZeroWhenNoModDeclaresExtensions(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir.resolve("plain-1.0.jar"), "META-INF/neoforge.mods.toml", "modId=\"plain\"");

		assertEquals(0, NeoEnumExtensions.load(getClass().getClassLoader(), List.of(jar)));
	}

	@Test
	void loadIsOffWhenTheSwitchSaysSo(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir.resolve("toolbelt-1.0.jar"), "META-INF/enumextensions.json", "{\"entries\":[]}");
		System.setProperty(NeoEnumExtensions.SWITCH, "off");
		try {
			assertEquals(0, NeoEnumExtensions.load(getClass().getClassLoader(), List.of(jar)));
		} finally {
			System.clearProperty(NeoEnumExtensions.SWITCH);
		}
	}

	@Test
	void loadSurvivesAnUnreadableJar(@TempDir Path dir) throws Exception {
		// A jar that fails to open must not abort the scan of the others — the previous behaviour for a bad jar
		// was silence, and that is still the right one here.
		Path notAJar = Files.writeString(dir.resolve("truncated.jar"), "not a zip");

		assertEquals(0, NeoEnumExtensions.load(getClass().getClassLoader(), List.of(notAJar)));
	}

	private static Path jarWith(Path jar, String entry, String content) throws Exception {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(entry));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
