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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.FmlConfigElements;

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

	/**
	 * Every value shape a mods.toml's TOP LEVEL can take — what the owning {@code ModFileInfo.getConfigElement}
	 * answers from: strings, a boolean, a number, a list, a table with a dotted literal key the way Unlit Campfire
	 * writes {@code ["lithium:options"]}, and a table two levels deep.
	 */
	private static final String FILE_TOML = """
			modLoader="javafml"
			loaderVersion="[1,)"
			license="MIT"
			issueTrackerURL="https://example.invalid/issues"
			showAsResourcePack=false
			services=["a.b.Service"]
			weight=3

			[[mods]]
			modId="filetest"
			version="1.0"
			authors="Somebody"

			["lithium:options"]
			"mixin.world.block_entity_ticking.sleeping.campfire"=false

			[custom]
			flag=true
			[custom.inner]
			x=1
			""";

	@TempDir
	Path tmp;

	/**
	 * The owning file's {@code getConfigElement} answers every path of a mods.toml's top level the way BOTH FMLs'
	 * {@code NightConfigWrapper} over the parsed file do — the same value, of the same class. That is a
	 * {@code valueMap()} for a NeoForge table and Guava's {@code ImmutableMap} for a MinecraftForge one.
	 *
	 * <p>The root each oracle wraps is parsed the way that FML parses it: NeoForge's {@code ModFileParser} wraps
	 * {@code TomlFormat.createParser().parse(reader).unmodifiable()}, MinecraftForge's a loaded {@code FileConfig}.
	 */
	@Test
	void fileConfigElementsAnswerEveryPathTheWayBothFmlsDo() throws Exception {
		Map<String, Object> kernel = discovered(FILE_TOML, "META-INF/neoforge.mods.toml").getFileConfigElements();
		List<String[]> paths = List.of(new String[] {"modLoader"}, new String[] {"license"},
				new String[] {"issueTrackerURL"}, new String[] {"showAsResourcePack"}, new String[] {"services"},
				new String[] {"weight"}, new String[] {"lithium:options"},
				new String[] {"lithium:options", "mixin.world.block_entity_ticking.sleeping.campfire"},
				new String[] {"custom"}, new String[] {"custom", "flag"}, new String[] {"custom", "inner"},
				new String[] {"custom", "inner", "x"}, new String[] {"absent"}, new String[] {"custom", "absent"},
				new String[] {"license", "deeper"}, new String[] {"lithium"});

		try (URLClassLoader neo = neoOracle()) {
			Object wrapper = wrapper(neo, NEO_WRAPPER, TomlFormat.instance().createParser().parse(FILE_TOML).unmodifiable());
			for (String[] path : paths) {
				assertSameAnswer(element(wrapper, path), FmlConfigElements.neoForge(kernel, path), "NeoForge " + String.join(".", path));
			}
		}
		try (URLClassLoader forge = forgeOracle()) {
			Object wrapper = wrapper(forge, FORGE_WRAPPER, root(FILE_TOML));
			UnaryOperator<Map<String, Object>> guava = guavaCopy(forge);
			for (String[] path : paths) {
				assertSameAnswer(element(wrapper, path), FmlConfigElements.minecraftForge(kernel, guava, path),
						"MinecraftForge " + String.join(".", path));
			}
			assertTrue(Class.forName("com.google.common.collect.ImmutableMap", false, forge)
					.isInstance(FmlConfigElements.minecraftForge(kernel, guava, "custom").orElseThrow()),
					"MinecraftForge hands a table out as Guava's ImmutableMap");
		}
	}

	/**
	 * Unlit Campfire's real manifest: the top-level {@code ["lithium:options"]} table Lithium reads from the owning
	 * file comes back as the one-entry map NeoForge gives Lithium, and passes Lithium's own checks on it (a
	 * {@code Map}, every key a {@code String}, the value a {@code Boolean}). The kernel answered it empty, and the
	 * player's log said {@code 0 override(s) found}.
	 */
	@Test
	void unlitCampfiresLithiumOptionsAreTheMapNeoForgeGivesLithium() throws Exception {
		String toml = fixture("unlitcampfire.neoforge.mods.toml",
				"run/client-merged-pack/mods/unlitcampfire-neoforge-26.2-4.1.0.0.jar", "META-INF/neoforge.mods.toml");
		Map<String, Object> kernel = discovered(toml, "META-INF/neoforge.mods.toml").getFileConfigElements();

		Optional<Object> answer = FmlConfigElements.neoForge(kernel, "lithium:options");
		assertEquals(Optional.of(Map.of("mixin.world.block_entity_ticking.sleeping.campfire", false)), answer);
		try (URLClassLoader neo = neoOracle()) {
			Object wrapper = wrapper(neo, NEO_WRAPPER, TomlFormat.instance().createParser().parse(toml).unmodifiable());
			assertSameAnswer(element(wrapper, "lithium:options"), answer, "lithium:options");
			for (String key : new TreeMap<>(root(toml).valueMap()).keySet()) {
				if (key.equals("mods") || key.equals("mixins") || key.equals("accessTransformers")) continue;
				assertSameAnswer(element(wrapper, key), FmlConfigElements.neoForge(kernel, key), key);
			}
		}
	}

	/**
	 * The manifests of the mods that read these seams, through the kernel and through their own FML: every top-level
	 * key and every key of every {@code [[mods]]} entry answers the same value of the same class. Not Enough Crashes
	 * and Puzzles Lib are NeoForge mods, wthit a MinecraftForge one. Arrays of tables are left out: both wrappers
	 * throw for them, and nothing asks.
	 */
	@Test
	void theReadersOwnManifestsAnswerAsTheirOwnFmlDoes() throws Exception {
		String nec = fixture("notenoughcrashes.neoforge.mods.toml",
				"build/compat-inputs/sweep90/mods/notenoughcrashes-neoforge-4.4.9+26.2.jar", "META-INF/neoforge.mods.toml");
		String puzzles = fixture("puzzleslib.neoforge.mods.toml",
				"run/client-popular/mods/PuzzlesLib-v26.2.4-mc26.2.x-NeoForge.jar", "META-INF/neoforge.mods.toml");
		String wthit = fixture("wthit.mods.toml", "run/client-popular/mods/wthit-26.2-forge-20.0.0.jar", "META-INF/mods.toml");

		try (URLClassLoader neo = neoOracle()) {
			for (String toml : List.of(nec, puzzles)) {
				UnmodifiableConfig root = TomlFormat.instance().createParser().parse(toml).unmodifiable();
				everyKeyAnswersAlike(wrapper(neo, NEO_WRAPPER, root), root,
						discoveredAll(toml, "META-INF/neoforge.mods.toml"), null);
			}
		}
		try (URLClassLoader forge = forgeOracle()) {
			UnmodifiableConfig root = root(wthit);
			everyKeyAnswersAlike(wrapper(forge, FORGE_WRAPPER, root), root, discoveredAll(wthit, "META-INF/mods.toml"),
					guavaCopy(forge));
		}

		DiscoveredMod necMod = discovered(nec, "META-INF/neoforge.mods.toml");
		assertEquals(Optional.of("https://github.com/natanfudge/Not-Enough-Crashes/issues"),
				FmlConfigElements.neoForge(necMod.getFileConfigElements(), "issueTrackerURL"));
		assertEquals(Optional.of("Fudge"), FmlConfigElements.neoForge(necMod.getConfigElements(), "authors"));
		DiscoveredMod puzzlesMod = discovered(puzzles, "META-INF/neoforge.mods.toml");
		assertEquals(Optional.of("Fuzs"), FmlConfigElements.neoForge(puzzlesMod.getConfigElements(), "authors"));
		assertEquals(Optional.of("https://modrinth.com/mod/puzzles-lib"),
				FmlConfigElements.neoForge(puzzlesMod.getConfigElements(), "displayURL"));
		assertEquals(Optional.of("https://github.com/badasintended/wthit/issues"), FmlConfigElements.minecraftForge(
				discoveredAll(wthit, "META-INF/mods.toml").get(0).getFileConfigElements(), FmlConfigElements::unmodifiableCopy,
				"issueTrackerURL"));
	}

	/**
	 * The top level against {@code fileWrapper}; each mod's entry against a wrapper over that entry.
	 *
	 * @param guava null for NeoForge's answer, else MinecraftForge's with this copy
	 */
	private static void everyKeyAnswersAlike(Object fileWrapper, UnmodifiableConfig root, List<DiscoveredMod> mods,
			UnaryOperator<Map<String, Object>> guava) throws Exception {
		DiscoveredMod first = mods.get(0);
		for (Map.Entry<String, Object> entry : new TreeMap<>(root.valueMap()).entrySet()) {
			if (isArrayOfTables(entry.getValue())) continue;
			assertSameAnswer(element(fileWrapper, entry.getKey()), answer(first.getFileConfigElements(), guava,
					entry.getKey()), first.getId() + " file " + entry.getKey());
		}
		List<?> entries = (List<?>) root.get(List.of("mods"));
		assertEquals(entries.size(), mods.size());
		for (int i = 0; i < entries.size(); i++) {
			UnmodifiableConfig table = (UnmodifiableConfig) entries.get(i);
			Object entryWrapper = wrapper(fileWrapper.getClass().getClassLoader(), fileWrapper.getClass().getName(), table);
			for (String key : new TreeMap<>(table.valueMap()).keySet()) {
				assertSameAnswer(element(entryWrapper, key), answer(mods.get(i).getConfigElements(), guava, key),
						mods.get(i).getId() + " [[mods]] " + key);
			}
		}
	}

	private static Optional<Object> answer(Map<String, Object> elements, UnaryOperator<Map<String, Object>> guava,
			String... path) {
		return guava == null ? FmlConfigElements.neoForge(elements, path)
				: FmlConfigElements.minecraftForge(elements, guava, path);
	}

	/** {@code ImmutableMap.copyOf} from the Guava MinecraftForge's wrapper links against; the kernel's tests have none. */
	static UnaryOperator<Map<String, Object>> guavaCopy(ClassLoader loader) throws Exception {
		Method copyOf = Class.forName("com.google.common.collect.ImmutableMap", true, loader).getMethod("copyOf", Map.class);
		return entries -> {
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> copy = (Map<String, Object>) copyOf.invoke(null, entries);
				return copy;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		};
	}

	private static boolean isArrayOfTables(Object value) {
		return value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof UnmodifiableConfig;
	}

	/** The same shape all the way down, and the same class for the answer itself — which is what a cast checks. */
	private static void assertSameAnswer(Optional<?> nativeAnswer, Optional<?> kernelAnswer, String what) {
		assertEquals(shape(nativeAnswer), shape(kernelAnswer), what);
		if (nativeAnswer.isPresent()) {
			assertEquals(nativeAnswer.get().getClass(), kernelAnswer.orElseThrow().getClass(), what);
		}
	}

	/**
	 * A real manifest, from the test resources — and byte for byte the one in the pack's jar when that jar is here, so
	 * the fixture cannot drift from what players run.
	 */
	private static String fixture(String resource, String jar, String entry) throws IOException {
		byte[] bytes;
		try (InputStream in = FmlTomlShapeOracleTest.class.getResourceAsStream("/forge/" + resource)) {
			bytes = in.readAllBytes();
		}
		Path real = Path.of(System.getProperty("user.dir"), jar).normalize();
		if (Files.isRegularFile(real)) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(real.toFile())) {
				assertArrayEquals(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), bytes, resource + " vs " + real);
			}
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

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

	static Optional<?> element(Object wrapper, String... path) throws Exception {
		Method m = wrapper.getClass().getMethod("getConfigElement", String[].class);
		m.setAccessible(true);
		return (Optional<?>) m.invoke(wrapper, (Object) path);
	}

	private static UnmodifiableConfig root(String toml) {
		return new TomlParser().parse(toml);
	}

	/** The kernel's side, through the whole discovery chain a jar actually takes to a {@link DiscoveredMod}. */
	private DiscoveredMod discovered(String toml, String entryName) throws IOException {
		List<DiscoveredMod> mods = discoveredAll(toml, entryName);
		assertEquals(1, mods.size(), "one [[mods]] entry: " + mods);
		return mods.get(0);
	}

	/** Every mod the file declares, in order. */
	private List<DiscoveredMod> discoveredAll(String toml, String entryName) throws IOException {
		Path jar = tmp.resolve("shape-" + Math.abs(toml.hashCode()) + "-" + entryName.hashCode() + ".jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(entryName));
			zip.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return new ForbricModDiscoverer().discoverJar(jar);
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
