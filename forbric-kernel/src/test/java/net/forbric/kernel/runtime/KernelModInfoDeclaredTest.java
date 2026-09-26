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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.net.URLClassLoader;
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

import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.kernel.discovery.ForbricModDiscoverer;

/**
 * A kernel-built NeoForge {@code IModInfo} describes a mod nested inside another mod's jar with that mod's OWN
 * manifest, not with {@link ModPresence}, which lists only what sits in {@code mods/}.
 *
 * <p>LibJF is the case that paid for it: its modules are all jar-in-jar, it finds every entry point it has in
 * {@code getModProperties()}, and against the empty table the presence lookup answered for them, LibJF
 * Translate's {@code libjf:config} — registered on native NeoForge — was never registered.
 */
class KernelModInfoDeclaredTest {
	private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run");

	@TempDir
	Path tmp;

	@AfterEach
	void clear() {
		System.clearProperty("forbric.declaredModMetadata");
		ModPresence.publishForgeFamily(List.of());
		ModPresence.publishFabric(List.of());
	}

	@Test
	void aNestedModIsDescribedByWhatItsOwnJarDeclared() throws Exception {
		Path jar = nestedJar();
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(jar).get(0);
		// Presence knows only the outer jar — exactly as a boot publishes it.
		ModPresence.publishForgeFamily(List.of());

		try (URLClassLoader runtime = runtimeLoader()) {
			Object info = modInfo(runtime, jar, declared);
			Map<?, ?> properties = (Map<?, ?>) info.getClass().getMethod("getModProperties").invoke(info);
			assertTrue(properties.containsKey("libjf:entrypoints"),
					"LibJF reads libjf:config out of this table: " + properties);
			assertEquals("26.2.2+forge", info.getClass().getMethod("getVersion").invoke(info).toString());
			assertEquals("LibJF Translate", info.getClass().getMethod("getDisplayName").invoke(info));
		}
	}

	@Test
	void switchedOffItIsDescribedFromPresenceAloneAgain() throws Exception {
		Path jar = nestedJar();
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(jar).get(0);
		System.setProperty("forbric.declaredModMetadata", "off");

		try (URLClassLoader runtime = runtimeLoader()) {
			Object info = modInfo(runtime, jar, declared);
			Map<?, ?> properties = (Map<?, ?>) info.getClass().getMethod("getModProperties").invoke(info);
			assertTrue(properties.isEmpty(), "off => the old empty table: " + properties);
			assertEquals("0.0", info.getClass().getMethod("getVersion").invoke(info).toString());
			assertEquals("libjf_translate_v1", info.getClass().getMethod("getDisplayName").invoke(info));
		}
	}

	@Test
	void withNothingDeclaredPresenceStillAnswers() throws Exception {
		Path jar = nestedJar();
		ModPresence.publishForgeFamily(new ForbricModDiscoverer().discoverJar(jar));

		try (URLClassLoader runtime = runtimeLoader()) {
			Object info = modInfo(runtime, jar, null);
			assertEquals("26.2.2+forge", info.getClass().getMethod("getVersion").invoke(info).toString(),
					"a mod in mods/ keeps the description presence always gave it");
		}
	}

	@Test
	void anotherModsEntryIsNeverTakenForThisOne() throws Exception {
		Path jar = nestedJar();
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(jar).get(0);

		try (URLClassLoader runtime = runtimeLoader()) {
			Object info = Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
					.getConstructor(String.class, Path.class, DiscoveredMod.class)
					.newInstance("some_other_mod", jar, declared);
			Map<?, ?> properties = (Map<?, ?>) info.getClass().getMethod("getModProperties").invoke(info);
			assertTrue(properties.isEmpty(), "an entry for a different id must not describe this mod");
		}
	}

	private static Object modInfo(ClassLoader runtime, Path jar, DiscoveredMod declared) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
				.getConstructor(String.class, Path.class, DiscoveredMod.class)
				.newInstance("libjf_translate_v1", jar, declared);
	}

	/** libjf-translate-v1 as it ships inside libjf-26.2.2+forge.jar: javafml, no @Mod class, a config entry point. */
	private Path nestedJar() throws Exception {
		Path jar = tmp.resolve("libjf-translate-v1-26.2.2+forge.jar");
		String toml = "modLoader = \"javafml\"\n"
				+ "loaderVersion = \"[1,)\"\n"
				+ "license = \"MIT\"\n"
				+ "\n"
				+ "[[mods]]\n"
				+ "modId = \"libjf_translate_v1\"\n"
				+ "version = \"26.2.2+forge\"\n"
				+ "displayName = \"LibJF Translate\"\n"
				+ "\n"
				+ "[[modproperties.libjf_translate_v1.\"libjf:entrypoints\".\"libjf:config\"]]\n"
				+ "value = \"dev.jfronny.libjf.translate.impl.TranslateConfig\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
			zip.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	private static URLClassLoader runtimeLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path carrier = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(carrier), "staged runtime classes/carrier absent");
		return new URLClassLoader(new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL()},
				KernelModInfoDeclaredTest.class.getClassLoader());
	}
}
