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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.kernel.discovery.ForbricModDiscoverer;

/**
 * What a mod reads out of a kernel-built {@code IModInfo} has the same value TYPES the mod's own FML would have
 * given it — checked against the real FML classes, not against a description of them.
 *
 * <p>Both FMLs build {@code getModProperties()} as {@code NightConfigWrapper.getConfigElement("modproperties", id)}:
 * NeoForge answers a table with its {@code valueMap()}, MinecraftForge with an {@code ImmutableMap} of the same
 * entries. Both are SHALLOW, so a table one level down is still night-config's own {@code Config}, and an array of
 * tables is a {@code List} of them. The kernel used to flatten every level into {@code LinkedHashMap}/{@code
 * ArrayList}, and LibJF Config Core — which casts {@code getModProperties().get("libjf:config")} to {@code Config} —
 * failed to construct on every launch.
 *
 * <p>The oracle is the wrapper class out of each staged carrier, fed the same TOML night-config parses for the
 * kernel. Only the night-config on this test's classpath is used on both sides, which is also the truth at
 * runtime: {@link net.forbric.kernel.classloading.DelegationPolicy} pins {@code com.electronwill.nightconfig.} to
 * the one parent-loaded copy, so a carrier's bundled copy never defines a second {@code Config}.
 */
class FmlTomlShapeOracleTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path NEO_CARRIER = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_CARRIER = RUN.resolve("merged-base/forge-runtime-interop.jar");

	private static final String NEO_WRAPPER = "net.neoforged.fml.loading.moddiscovery.NightConfigWrapper";
	private static final String FORGE_WRAPPER = "net.minecraftforge.fml.loading.moddiscovery.NightConfigWrapper";

	/**
	 * Every value shape a {@code [modproperties]} table can take, in a MinecraftForge {@code mods.toml}: Iceberg's
	 * real list-of-strings declaration, scalars, a table two levels deep, and an array of tables.
	 */
	private static final String FORGE_TOML = """
			modLoader="javafml"
			loaderVersion="[65,)"
			license="MIT"

			[[mods]]
			modId="shapetest"
			version="1.0"
			displayName="Shape Test"

			[mods.custom]
			flag = true
			[mods.custom.inner]
			x = 1

			[mods."sodium:options"]
			"mixin.features.render.world.sky" = false

			[modproperties.shapetest]
			configuredProviders=["com.anthonyhilyard.iceberg.compat.configured.IcebergConfigProvider"]
			"fabric-renderer-api-v1:contains_renderer" = true
			weight = 3
			[modproperties.shapetest.nested]
			inner = "yes"
			[modproperties.shapetest.nested.deeper]
			depth = 2
			[[modproperties.shapetest."libjf:entrypoints"."libjf:config"]]
			value = "example.Config"
			""";

	@TempDir
	Path tmp;

	@Test
	void libjfTranslatesPropertiesHaveNeoForgesShape() throws Exception {
		String toml = libjfTranslate();
		try (URLClassLoader neo = neoOracle()) {
			Map<?, ?> nativeProperties = (Map<?, ?>) element(wrapper(neo, NEO_WRAPPER, root(toml)),
					"modproperties", "libjf_translate_v1").orElseThrow();
			Map<String, Object> kernel = discovered(toml, "META-INF/neoforge.mods.toml").getModProperties();

			assertEquals(shape(nativeProperties), shape(kernel));
			assertEquals("Config", shape(kernel.get("libjf:config")).substring(0, "Config".length()),
					"the value LibJF Config Core casts to com.electronwill.nightconfig.core.Config");
		}
	}

	@Test
	void everyPropertyShapeMatchesBothFmls() throws Exception {
		UnmodifiableConfig root = root(FORGE_TOML);
		Map<String, Object> kernel = discovered(FORGE_TOML, "META-INF/mods.toml").getModProperties();

		try (URLClassLoader neo = neoOracle()) {
			Object nativeProperties = element(wrapper(neo, NEO_WRAPPER, root), "modproperties", "shapetest").orElseThrow();
			assertEquals(shape(nativeProperties), shape(kernel), "NeoForge's valueMap()");
		}
		try (URLClassLoader forge = forgeOracle()) {
			Object nativeProperties = element(wrapper(forge, FORGE_WRAPPER, root), "modproperties", "shapetest").orElseThrow();
			assertEquals(shape(nativeProperties), shape(kernel), "MinecraftForge's ImmutableMap of the same entries");
		}
	}

	/**
	 * {@code IConfigurable.getConfigElement} on a mod's {@code [[mods]]} entry — the seam Sodium reads
	 * {@code sodium:options} through — answers each path the way NeoForge's {@code NightConfigWrapper} does: a table
	 * as its {@code valueMap()}, a scalar as itself, a missing key as empty.
	 */
	@Test
	void configElementsAnswerEveryPathTheWayNeoForgesWrapperDoes() throws Exception {
		UnmodifiableConfig entry = ((List<UnmodifiableConfig>) root(FORGE_TOML).get(List.of("mods"))).get(0);
		Map<String, Object> kernel = discovered(FORGE_TOML, "META-INF/neoforge.mods.toml").getConfigElements();

		try (URLClassLoader neo = neoOracle()) {
			Object wrapper = wrapper(neo, NEO_WRAPPER, entry);
			for (String[] path : List.of(new String[] {"sodium:options"}, new String[] {"custom"},
					new String[] {"custom", "inner"}, new String[] {"custom", "inner", "x"}, new String[] {"modId"},
					new String[] {"absent"}, new String[] {"custom", "absent"}, new String[] {"modId", "deeper"})) {
				assertEquals(shape(element(wrapper, path)), shape(PassiveSeeder.lookup(kernel, new Object[] {path})),
						String.join(".", path));
			}
		}
	}

	// --- the oracle -------------------------------------------------------------------------------------------

	private static URLClassLoader neoOracle() throws IOException {
		assumeTrue(Files.isRegularFile(NEO_CARRIER), "staged neoforge-runtime.jar absent");
		return new URLClassLoader(new URL[] {NEO_CARRIER.toUri().toURL()}, FmlTomlShapeOracleTest.class.getClassLoader());
	}

	/** MinecraftForge's wrapper builds its answer with Guava, which Minecraft supplies and the kernel never ships. */
	private static URLClassLoader forgeOracle() throws IOException {
		assumeTrue(Files.isRegularFile(FORGE_CARRIER), "staged forge-runtime-interop.jar absent");
		Path guava = newestGuava();
		assumeTrue(guava != null, "no Guava in the local Minecraft library tree");
		return new URLClassLoader(new URL[] {FORGE_CARRIER.toUri().toURL(), guava.toUri().toURL()},
				FmlTomlShapeOracleTest.class.getClassLoader());
	}

	private static Path newestGuava() throws IOException {
		String env = System.getenv("MC_DIR");
		Path root = Path.of(env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path under = root.resolve("com/google/guava/guava");
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString()))
					.reduce((a, b) -> b).orElse(null);
		}
	}

	/** The FML's own wrapper — both are constructed over a parsed config, exactly as their ModFileParser does. */
	private static Object wrapper(ClassLoader loader, String name, UnmodifiableConfig config) throws Exception {
		Constructor<?> ctor = Class.forName(name, true, loader).getConstructor(UnmodifiableConfig.class);
		ctor.setAccessible(true); // MinecraftForge's class is package-private
		return ctor.newInstance(config);
	}

	private static Optional<?> element(Object wrapper, String... path) throws Exception {
		Method m = wrapper.getClass().getMethod("getConfigElement", String[].class);
		m.setAccessible(true);
		return (Optional<?>) m.invoke(wrapper, (Object) path);
	}

	private static UnmodifiableConfig root(String toml) {
		return new TomlParser().parse(toml);
	}

	/** The kernel's side, through the whole discovery chain a jar actually takes to a {@link DiscoveredMod}. */
	private DiscoveredMod discovered(String toml, String entryName) throws IOException {
		Path jar = tmp.resolve("shape-" + Math.abs(toml.hashCode()) + "-" + entryName.hashCode() + ".jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(entryName));
			zip.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		List<DiscoveredMod> mods = new ForbricModDiscoverer().discoverJar(jar);
		assertEquals(1, mods.size(), "one [[mods]] entry: " + mods);
		return mods.get(0);
	}

	private static String libjfTranslate() throws IOException {
		try (InputStream in = FmlTomlShapeOracleTest.class.getResourceAsStream(
				"/forge/libjf-translate-v1.neoforge.mods.toml")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * The TYPE a reader can rely on, all the way down. {@code Config} is kept apart from other maps because that
	 * distinction is the whole bug: a checkcast to {@code Config} passes on one and throws on the other, and a
	 * reader branching on {@code instanceof Map} takes a different branch.
	 */
	static String shape(Object value) {
		if (value instanceof Optional<?> optional) return optional.map(v -> "Optional[" + shape(v) + "]").orElse("empty");
		if (value instanceof Config config) return "Config" + entries(config.valueMap());
		if (value instanceof UnmodifiableConfig config) return "UnmodifiableConfig" + entries(config.valueMap());
		if (value instanceof Map<?, ?> map) return "Map" + entries(map);
		if (value instanceof List<?> list) {
			List<String> out = new ArrayList<>();
			for (Object element : list) out.add(shape(element));
			return "List" + out;
		}
		return value == null ? "null" : value.getClass().getSimpleName() + "(" + value + ")";
	}

	private static String entries(Map<?, ?> map) {
		Map<String, String> sorted = new TreeMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), shape(entry.getValue()));
		return sorted.toString();
	}
}
