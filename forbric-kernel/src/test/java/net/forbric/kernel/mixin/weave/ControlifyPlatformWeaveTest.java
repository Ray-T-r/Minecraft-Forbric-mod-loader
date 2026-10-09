/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.MixinPlatformIdentity;

/**
 * Controlify's real, unmodified {@code CompatMixinPlugin}, initialised under the real Mixin bootstrap: its static
 * initialiser switches on the Mixin service's name to load its platform half, and as merged it threw "Unsupported Mixin
 * service: Forbric". Nothing rewrites it now; the service tells its code the name of the platform its build is for.
 */
class ControlifyPlatformWeaveTest {
	private static final Path JAR = Path.of("build/sweep100-mac-network/mods/controlify-3.5.3+mc26.2-universal.jar");
	private static final List<String> CLASSES = List.of(
			"dev/isxander/controlify/compatibility/CompatMixinPlugin",
			"dev/isxander/controlify/compatibility/CompatMixinPlugin$Platform",
			"dev/isxander/controlify/compatibility/CompatMixinPlugin$Loader",
			"dev/isxander/controlify/compatibility/CompatMixinPlatform",
			"dev/isxander/controlify/fabric/compatibility/FabricCompatMixinPlatform",
			"dev/isxander/controlify/neoforge/compatibility/NeoforgeCompatMixinPlatform");
	private static final String CONFIG = "realplatform.mixins.json";
	private static final String PROBE = """
			package fixture.realplatform;

			/** Initialises the real plugin class and reports the platform its static initialiser chose, or why it threw. */
			public class Probe {
				public String platform() throws Exception {
					try {
						Class<?> plugin = Class.forName("dev.isxander.controlify.compatibility.CompatMixinPlugin", true,
								Probe.class.getClassLoader());
						java.lang.reflect.Field field = plugin.getDeclaredField("PLATFORM");
						field.setAccessible(true);
						Object chosen = field.get(null);
						return part(chosen, "loader") + " " + part(chosen, "impl").getClass().getSimpleName();
					} catch (ExceptionInInitializerError failed) {
						return "threw " + failed.getCause().getMessage();
					}
				}

				private static Object part(Object record, String accessor) throws Exception {
					java.lang.reflect.Method method = record.getClass().getDeclaredMethod(accessor);
					method.setAccessible(true);
					return method.invoke(record);
				}
			}
			""";

	@TempDir static Path work;

	private static Path fixture() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(JAR), "local mod fixture absent: " + JAR);
		Map<String, Path> resources = new LinkedHashMap<>();
		Path extracted = Files.createDirectories(work.resolve("controlify-classes"));
		for (String name : CLASSES) {
			Path file = extracted.resolve(name.replace('/', '_').replace('$', '_') + ".class");
			Files.write(file, TestFixtures.requireEntry(Fixture.THIRD_PARTY, JAR, name + ".class"));
			resources.put(name + ".class", file);
		}
		Path config = Files.writeString(work.resolve(CONFIG),
				"{\"required\": true, \"package\": \"fixture.realplatform.mixin\", \"compatibilityLevel\": \"JAVA_21\", \"mixins\": []}");
		resources.put(CONFIG, config);
		Path probe = Files.createDirectories(work.resolve("probe-src/fixture/realplatform")).resolve("Probe.java");
		Files.writeString(probe, PROBE);
		return WeaveHarness.fixture(work, "controlify-real", List.of(probe), resources);
	}

	private static void chose(Path fixture, String label, Ecosystem owner, Map<String, String> properties, String expected) throws Exception {
		Map<String, String> owned = new LinkedHashMap<>(properties);
		owned.put(WeaveHarnessMain.ORIGINS, "on");
		WeaveHarness.Result run = WeaveHarness.run(work, label, fixture, CONFIG, "controlify", owner, EnvType.CLIENT,
				"fixture.realplatform.Probe", "platform", owned);
		assertTrue(run.printedLine(WeaveHarnessMain.DONE + " " + expected), run.describe());
	}

	@Test void eachBuildOfControlifyLoadsItsOwnPlatformHalfUnrewritten() throws Exception {
		Path fixture = fixture();
		chose(fixture, "fabric", Ecosystem.FABRIC, Map.of(), "FABRIC FabricCompatMixinPlatform");
		chose(fixture, "neoforge", Ecosystem.NEOFORGE, Map.of(), "NEOFORGE NeoforgeCompatMixinPlatform");
		chose(fixture, "off", Ecosystem.FABRIC, Map.of(MixinPlatformIdentity.PROPERTY, "off"), "threw Unsupported Mixin service: Forbric");
	}
}
