/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy;

/**
 * Reads the exact two-level JiJ that shadowed the kernel in the Windows popular-pack baseline.
 * Optional on a clean checkout; FORBRIC_COMPAT_FIXTURES_REQUIRED=1 makes absence a failure for a compatibility
 * validation run. Fixture paths can be overridden with FORBRIC_BADPACKETS_FIXTURE / FORBRIC_KERNEL_BOOT_FIXTURE.
 */
class KernelBundledMixinExtrasStagedTest {
	private static final String BOOTSTRAP = "com.llamalad7.mixinextras.MixinExtrasBootstrap";
	private static final String VERSION = "com/llamalad7/mixinextras/service/MixinExtrasVersion.class";
	private static final String CONFIG = "mixinextras.init.mixins.json";
	@TempDir Path temporary;

	@BeforeEach @AfterEach void reset() { System.clearProperty(KernelOwnedClasspath.SWITCH); }

	@ParameterizedTest @ValueSource(booleans = {true, false})
	void realBadpacketsNestedCopyCannotShadowTheSuppliedVersionUnlessExplicitlyDisabled(boolean suppliedFirst)
			throws Exception {
		Path badpackets = fixture("FORBRIC_BADPACKETS_FIXTURE",
				"build/compat-inputs/popular/mods/badpackets-forge-0.12.2.jar");
		Path boot = fixture("FORBRIC_KERNEL_BOOT_FIXTURE", "build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar");
		boolean present = Files.isRegularFile(badpackets) && Files.isRegularFile(boot);
		String missing = "stage the popular compatibility pack and the boot jar (or set fixture overrides): "
				+ badpackets + ", " + boot;
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, missing);
		assumeTrue(present, missing);

		List<Path> nested = KernelBoot.extractForgeFamilyJarJar(List.of(badpackets), temporary.resolve("game"));
		assertEquals(2, nested.size(), "this fixture must exercise both the Forge wrapper and its common child");
		Path wrapper = named(nested, "mixinextras-forge-0.3.5.jar");
		Path common = named(nested, "MixinExtras-0.3.5.jar");
		Path bundle = temporary.resolve("game/.forbric-kernel/lib/mixinextras-fabric.jar");
		Files.createDirectories(bundle.getParent());
		Files.write(bundle, bytes(boot, "META-INF/jars/mixinextras-fabric.jar"));
		String metadata = new String(bytes(bundle, "fabric.mod.json"), StandardCharsets.UTF_8);
		assertTrue(metadata.contains("\"version\": \"0.5.4\""), "expected the actual kernel 0.5.4 fixture: " + metadata);
		assertFalse(Arrays.equals(bytes(bundle, VERSION), bytes(common, VERSION)),
				"the version class, unlike Bootstrap.class, must distinguish these two actual releases");

		if (!suppliedFirst) System.setProperty(KernelOwnedClasspath.SWITCH, "off");
		List<Path> guests = new ArrayList<>(List.of(badpackets));
		guests.addAll(nested);
		List<URL> owned = KernelOwnedClasspath.compose(List.of(), List.of(), guests, List.of(), List.of(bundle));
		try (ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(URL[]::new), getClass().getClassLoader())) {
			loader.setJarFamilies(Map.of(badpackets, LoaderProbePolicy.Family.FORGE,
					wrapper, LoaderProbePolicy.Family.FORGE));
			Path classProvider = suppliedFirst ? bundle : common;
			Path configProvider = suppliedFirst ? bundle : wrapper;
			assertArrayEquals(bytes(classProvider, VERSION),
					loader.getPreMixinClassBytes(VERSION.substring(0, VERSION.length() - 6).replace('/', '.')));
			Class<?> bootstrap = Class.forName(BOOTSTRAP, false, loader);
			assertSame(loader, bootstrap.getClassLoader(), "an owned library must not be moved to the parent loader");
			assertEquals(classProvider.toUri().toURL(), bootstrap.getProtectionDomain().getCodeSource().getLocation());
			// getVersion only initializes the version enum; init() is deliberately not called, so this is not a
			// Mixin/game launch and has no global Mixin service state to contaminate other tests.
			assertEquals(suppliedFirst ? "0.5.4" : "0.3.5", bootstrap.getMethod("getVersion").invoke(null));
			Class<?> version = Class.forName(VERSION.substring(0, VERSION.length() - 6).replace('/', '.'), false, loader);
			assertEquals(classProvider.toUri().toURL(), version.getProtectionDomain().getCodeSource().getLocation());
			Class<?> localRef = Class.forName("com.llamalad7.mixinextras.sugar.ref.LocalRef", false, loader);
			assertSame(loader, localRef.getClassLoader(), "generated LocalRefs must share their API's game loader");
			try (InputStream in = loader.getGameResourceAsStream(CONFIG)) {
				assertNotNull(in);
				assertArrayEquals(bytes(configProvider, CONFIG), in.readAllBytes());
			}
			assertEquals("jar:" + configProvider.toUri().toURL() + "!/" + CONFIG, loader.findResource(CONFIG).toString());
			Class<?> guest = Class.forName("lol.bai.badpackets.impl.Constants", false, loader);
			assertSame(loader, guest.getClassLoader());
			assertEquals(badpackets.toUri().toURL(), guest.getProtectionDomain().getCodeSource().getLocation());
			assertEquals(LoaderProbePolicy.Family.FORGE, loader.familyOfClass(guest.getName()));
			Class<?> platform = Class.forName("com.llamalad7.mixinextras.platform.forge.MixinExtrasConfigPlugin", false, loader);
			assertEquals(wrapper.toUri().toURL(), platform.getProtectionDomain().getCodeSource().getLocation(),
					"the guest-only platform classes must remain accessible; do not discard the whole wrapper");
			assertEquals(LoaderProbePolicy.Family.FORGE, loader.familyOfClass(platform.getName()));
		}
	}

	private static Path fixture(String variable, String fallback) {
		String override = System.getenv(variable);
		return Path.of(override == null || override.isBlank() ? fallback : override).toAbsolutePath();
	}

	private static Path named(List<Path> paths, String name) {
		return paths.stream().filter(path -> path.getFileName().toString().equals(name)).findFirst().orElseThrow();
	}

	private static byte[] bytes(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			assertNotNull(zip.getEntry(entry), jar + " must provide " + entry);
			try (InputStream in = zip.getInputStream(zip.getEntry(entry))) { return in.readAllBytes(); }
		}
	}
}
