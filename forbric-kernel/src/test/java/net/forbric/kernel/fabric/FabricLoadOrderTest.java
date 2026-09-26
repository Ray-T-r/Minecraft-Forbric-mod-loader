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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.DuplicateModArbiter;
import net.forbric.kernel.boot.FabricLoadOrder;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.MultiLoaderArbiter;
import net.forbric.kernel.mixin.MixinConfigOwners;

/**
 * Fabric mods run in Fabric Loader's order, which is by mod id. They used to run in Forbric's dependency order.
 *
 * <p>Fabric Loader 0.19.5 sorts its resolved set by id in {@code ModResolver.findCompatibleSet}. It lists its mods,
 * fills every entrypoint key and registers mixin configs in that order. Dependencies only decide which mods are
 * selected. The case that showed it matters is Pets Mod. Its client JOIN listener throws in every singleplayer
 * world, and fabric-api's JOIN invoker is a plain loop, so every listener registered after it is skipped. Natively
 * that spares bclib and OptiGUI. In dependency order, both registered after Pets Mod.
 *
 * <p>Everything here goes through {@link KernelFabricEcosystem#build} over real jars in a real {@code mods/}
 * directory, because the order is decided across discovery, registration and the freeze, not in one method.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("system-properties")
class FabricLoadOrderTest {
	static final List<String> RAN = new ArrayList<>();
	/** The JOIN event's listeners, in registration order, and the ones that ran when it fired. */
	static final List<Runnable> JOIN = new ArrayList<>();
	static final List<String> JOINED = new ArrayList<>();

	@TempDir
	Path gameDir;

	private Path mods;
	private Object previousLoader;

	@BeforeEach
	void setUp() throws Exception {
		mods = Files.createDirectories(gameDir.resolve("mods"));
		previousLoader = activeLoader().get(null);
		reset();
	}

	@AfterEach
	void tearDown() throws Exception {
		reset();
		activeLoader().set(null, previousLoader);
	}

	private static void reset() {
		KernelFabricLoader.resetForTests();
		KernelFabricEcosystem.resetPhasesForTests();
		KernelLanguageAdapters.reset();
		MultiLoaderArbiter.reset();
		DuplicateModArbiter.reset();
		ModPresence.publishFabric(List.of());
		System.clearProperty(FabricLoadOrder.SWITCH);
		RAN.clear();
		JOIN.clear();
		JOINED.clear();
	}

	// -------------------------------------------------------------------------------------------------------------
	// A synthetic set where jar-name order, dependency order and id order all differ
	// -------------------------------------------------------------------------------------------------------------

	/**
	 * Jar order is mmm, zzz-lib (with aaa-nested inside it), bbb. Dependency order moves mmm behind zzz-lib, which it
	 * requires. Fabric Loader's order is by id, and it ignores both.
	 */
	private void syntheticMods() throws IOException {
		jar("0-first.jar", manifest("mmm", "{\"zzz-lib\":\"*\"}", "{"
				+ "\"preLaunch\":[\"" + MmmPre.class.getName() + "\"],"
				+ "\"main\":[\"" + MmmMain.class.getName() + "\"],"
				+ "\"client\":[\"" + MmmClientA.class.getName() + "\",\"" + MmmClientB.class.getName() + "\"],"
				+ "\"order-probe\":[\"" + MmmProbe.class.getName() + "\"]}", null), Map.of());
		jar("1-second.jar", manifest("zzz-lib", "{}", "{"
				+ "\"preLaunch\":[\"" + ZzzPre.class.getName() + "\"],"
				+ "\"main\":[\"" + ZzzMain.class.getName() + "\"],"
				+ "\"client\":[\"" + ZzzClient.class.getName() + "\"]}", "META-INF/jars/aaa-nested.jar"),
				Map.of("META-INF/jars/aaa-nested.jar", jarBytes(manifest("aaa-nested", "{}", "{"
						+ "\"main\":[\"" + AaaMain.class.getName() + "\"],"
						+ "\"client\":[\"" + AaaClient.class.getName() + "\"]}", null))));
		jar("2-third.jar", manifest("bbb", "{}", "{"
				+ "\"main\":[\"" + BbbMain.class.getName() + "\"],"
				+ "\"client\":[\"" + BbbClient.class.getName() + "\"],"
				+ "\"order-probe\":[\"" + BbbProbe.class.getName() + "\"]}", null), Map.of());
	}

	/**
	 * Fabric Loader's order everywhere it applies: {@code getAllMods()} with the nested mod and the builtins sorted
	 * in, and every entrypoint key. A mod's own entrypoints keep the order it declared them in.
	 */
	@Test
	void fabricModsAndEveryEntrypointKeyFollowFabricLoadersIdOrder() throws Exception {
		syntheticMods();

		String log = build();
		KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();

		assertEquals(List.of("aaa-nested", "bbb", "fabricloader", "java", "minecraft", "mmm", "zzz-lib"), ids(loader),
				"getAllMods() is Fabric Loader's sorted list, nested mods and builtins included");
		assertEquals(List.of("aaa-nested.mixins.json", "bbb.mixins.json", "mmm.mixins.json", "zzz-lib.mixins.json"),
				mixinConfigs(), "mixin configs are registered walking the same list");
		assertEquals(List.of(BbbProbe.class.getName(), MmmProbe.class.getName()), definitions(loader, "order-probe"),
				"a key the kernel does not drive is in the same order");

		KernelFabricEcosystem.runPreLaunch();
		KernelFabricEcosystem.runMainEntrypoints();
		KernelFabricEcosystem.runClientEntrypoints();

		assertEquals(List.of("MmmPre", "ZzzPre",
				"AaaMain", "BbbMain", "MmmMain", "ZzzMain",
				"AaaClient", "BbbClient", "MmmClientA", "MmmClientB", "ZzzClient"), RAN);
		assertTrue(matchesGates(log), "the gates' Fabric-order line must be the one written:\n" + log);
		assertFalse(log.contains("initialise in dependency order"), log);
	}

	/**
	 * The consequence, reproduced: a listener that throws inside an invoker with no per-listener catch skips exactly
	 * the listeners Fabric would skip — those registered by mods whose ids sort after the thrower's.
	 */
	@Test
	void aThrowingJoinListenerSkipsExactlyTheListenersFabricWouldSkip() throws Exception {
		syntheticMods();
		build();
		KernelFabricEcosystem.runMainEntrypoints();
		KernelFabricEcosystem.runClientEntrypoints();

		assertEquals(List.of("aaa-nested", "bbb"), fireJoin());
	}

	/** The switch: Fabric mods keep the dependency order they had, and the log says that instead. */
	@Test
	void switchedOffFabricModsKeepTheDependencyOrder() throws Exception {
		System.setProperty(FabricLoadOrder.SWITCH, "off");
		syntheticMods();

		String log = build();
		KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();
		KernelFabricEcosystem.runPreLaunch();
		KernelFabricEcosystem.runMainEntrypoints();
		KernelFabricEcosystem.runClientEntrypoints();

		assertEquals(List.of("minecraft", "java", "fabricloader", "zzz-lib", "aaa-nested", "bbb", "mmm"), ids(loader));
		assertEquals(List.of("ZzzPre", "MmmPre",
				"ZzzMain", "AaaMain", "BbbMain", "MmmMain",
				"ZzzClient", "AaaClient", "BbbClient", "MmmClientA", "MmmClientB"), RAN);
		assertEquals(List.of("zzz-lib", "aaa-nested", "bbb"), fireJoin());
		assertFalse(matchesGates(log), "the Fabric-order line must not be written when the order was not applied");
		assertTrue(log.contains("4 Fabric mod(s) initialise in dependency order"), log);
	}

	/**
	 * What must NOT move. The Forge-family seeders get the Fabric mods in the order they always did, so NeoForge and
	 * MinecraftForge construction order cannot shift as a side effect. The class path comes back in discovery order,
	 * behind which KernelBoot keeps Minecraft's libraries ahead of every mod.
	 */
	@Test
	void theForgeFamilyInputAndTheClassPathKeepTheirOrder() throws Exception {
		syntheticMods();

		List<Path> jars = new ArrayList<>();
		build(jars);

		assertEquals(List.of("zzz-lib", "aaa-nested", "bbb", "mmm"),
				ModPresence.fabricMods().stream().map(DiscoveredMod::getId).toList());
		assertEquals(List.of("0-first.jar", "1-second.jar", "aaa-nested.jar", "2-third.jar"),
				jars.stream().map(jar -> jar.getFileName().toString()).toList());
	}

	/** Which container answers for an id is settled at registration, before the reorder, and stays settled. */
	@Test
	void aProvidesAliasStillResolvesToTheModThatWasRegisteredFirst() throws Exception {
		// "zzz-first" is registered first (its jar sorts first and nothing orders it later) and provides "shared";
		// "aaa-second" provides it too and sorts first by id. The reorder must not hand "shared" to the second.
		jar("0.jar", "{\"schemaVersion\":1,\"id\":\"zzz-first\",\"version\":\"1\",\"provides\":[\"shared\"]}", Map.of());
		jar("1.jar", "{\"schemaVersion\":1,\"id\":\"aaa-second\",\"version\":\"1\",\"provides\":[\"shared\"]}",
				Map.of());

		build();
		KernelFabricLoader loader = KernelFabricLoader.getInstanceOrNull();

		assertEquals(List.of("aaa-second", "fabricloader", "java", "minecraft", "zzz-first"), ids(loader));
		assertEquals("zzz-first", loader.getModContainer("shared").orElseThrow().getMetadata().getId());
	}

	// -------------------------------------------------------------------------------------------------------------
	// The real case: bclib, OptiGUI and Pets Mod, with their own fabric.mod.json
	// -------------------------------------------------------------------------------------------------------------

	/**
	 * The three mods' own manifests, verbatim from the sweep's jars ({@code bclib-26.201.2.jar},
	 * {@code optigui-2.3.0-beta.10+26.2.jar}, {@code pets-mod-0.8.4-26.2-fabric.jar}), next to stand-ins for what
	 * they depend on. Each stand-in has the real jar name, id, nesting and {@code depends}, and nothing else: no
	 * entrypoints, and none of the nested modules nothing here depends on (the rest of fabric-api and of wover). Those
	 * dependency declarations are what made the old order put Pets Mod first: bclib waits for wover and wunderlib,
	 * and OptiGUI waits for fabric-language-kotlin and five fabric-api modules.
	 */
	private void realMods() throws IOException {
		jar("bclib-26.201.2.jar", fixture("bclib-26.201.2.fabric.mod.json"), Map.of());
		jar("optigui-2.3.0-beta.10+26.2.jar", fixture("optigui-2.3.0-beta.10+26.2.fabric.mod.json"), Map.of());
		jar("pets-mod-0.8.4-26.2-fabric.jar", fixture("pets-mod-0.8.4-26.2-fabric.fabric.mod.json"), Map.of());

		jar("cloth-config-26.2.155.jar", stub("cloth-config", "{\"fabricloader\":\">=0.14.0\",\"minecraft\":\">=26.2-\"}"),
				Map.of());
		jar("fabric-language-kotlin-1.14.1+kotlin.2.4.20.jar",
				stub("fabric-language-kotlin", "{\"fabricloader\":\">=0.19.5\"}"), Map.of());

		Map<String, byte[]> modules = new LinkedHashMap<>();
		module(modules, "fabric-api-base-2.0.4+ece063239e.jar", "fabric-api-base", "{\"fabricloader\":\">=0.18.4\"}");
		module(modules, "fabric-events-interaction-v0-5.2.8+515ac5339e.jar", "fabric-events-interaction-v0",
				"{\"fabricloader\":\">=0.18.4\",\"fabric-api-base\":\"*\",\"fabric-networking-api-v1\":\"*\"}");
		module(modules, "fabric-key-mapping-api-v1-2.0.5+e2bdee789e.jar", "fabric-key-mapping-api-v1",
				"{\"fabricloader\":\">=0.18.4\"}");
		module(modules, "fabric-lifecycle-events-v1-4.1.4+29b6eb019e.jar", "fabric-lifecycle-events-v1",
				"{\"fabricloader\":\">=0.18.4\",\"fabric-api-base\":\"*\"}");
		module(modules, "fabric-networking-api-v1-6.3.4+2989c6a09e.jar", "fabric-networking-api-v1",
				"{\"fabricloader\":\">=0.18.4\",\"fabric-api-base\":\"*\"}");
		module(modules, "fabric-resource-loader-v0-3.3.20+4fc5413f9e.jar", "fabric-resource-loader-v0",
				"{\"fabricloader\":\">=0.18.4\",\"fabric-resource-loader-v1\":\"*\"}");
		module(modules, "fabric-resource-loader-v1-2.0.13+9edec1269e.jar", "fabric-resource-loader-v1",
				"{\"fabricloader\":\">=0.18.4\"}");
		jar("fabric-api-0.161.0+26.2.jar", stub("fabric-api",
				"{\"fabricloader\":\">=0.18.4\",\"java\":\">=25\",\"minecraft\":\"~26.2-\"}", modules.keySet()), modules);

		jar("worldweaver-26.201.2.jar", stub("wover", "{\"fabricloader\":\">=0.19.0\",\"minecraft\":[\"26.2\"],"
				+ "\"java\":\">=25\",\"fabric-api\":\">=0.155.0\",\"wunderlib\":\"26.201.x\"}",
				List.of("META-INF/jars/wunderlib-26.201.0.jar")),
				Map.of("META-INF/jars/wunderlib-26.201.0.jar", jarBytes(stub("wunderlib", "{\"fabricloader\":\">=0.19.0\","
						+ "\"fabric-api\":\">=0.155.0\",\"minecraft\":[\"26.2\"],\"java\":\">=25\"}"))));
	}

	/**
	 * Natively bclib's and OptiGUI's client entrypoints, which register their JOIN listeners, run before Pets Mod's.
	 * So Pets Mod's throw skips neither of them.
	 */
	@Test
	void bclibAndOptiguiRegisterTheirJoinListenersBeforePetsModAsOnFabric() throws Exception {
		realMods();

		String log = build();
		List<String> client = providers(KernelFabricLoader.getInstanceOrNull(), "client");

		assertEquals(List.of("bclib", "optigui", "optigui", "optigui", "optigui", "optigui", "optigui", "optigui",
				"pets-mod", "pets-mod", "pets-mod"), client);
		assertEquals("com.jeff.pets.client.network.PetsNetworked",
				definitions(KernelFabricLoader.getInstanceOrNull(), "client").get(10),
				"Pets Mod's JOIN listener is registered by its last client entrypoint");
		assertTrue(matchesGates(log), log);
	}

	/**
	 * The same three under the old order: Pets Mod's client entrypoints come before both, as they did in the Windows
	 * sweep (there pets-mod was client entrypoint 21–23, bclib 64 and OptiGUI 66–72), so its throw skipped both JOIN
	 * listeners.
	 */
	@Test
	void switchedOffPetsModRunsBeforeBothAsItDidUnderTheDependencyOrder() throws Exception {
		System.setProperty(FabricLoadOrder.SWITCH, "off");
		realMods();

		build();
		List<String> client = providers(KernelFabricLoader.getInstanceOrNull(), "client");

		assertTrue(client.lastIndexOf("pets-mod") < client.indexOf("bclib"), client.toString());
		assertTrue(client.lastIndexOf("pets-mod") < client.indexOf("optigui"), client.toString());
	}

	// -------------------------------------------------------------------------------------------------------------
	// The reorder itself
	// -------------------------------------------------------------------------------------------------------------

	@Test
	void theReorderRefusesAnythingButAPermutationAndChangesNothingWhenItDoes() {
		KernelFabricLoader loader = KernelFabricLoader.create(EnvType.CLIENT, gameDir, gameDir.resolve("config"),
				new String[0], "26.2");
		KernelModContainer a = container("a");
		KernelModContainer b = container("b");
		loader.register(a);
		loader.register(b);

		assertThrows(IllegalArgumentException.class, () -> loader.reorder(List.of(b)));
		assertThrows(IllegalArgumentException.class, () -> loader.reorder(List.of(b, b)));
		assertThrows(IllegalArgumentException.class, () -> loader.reorder(List.of(b, container("a"))));
		assertEquals(List.of("a", "b"), ids(loader));

		loader.reorder(List.of(b, a));
		assertEquals(List.of("b", "a"), ids(loader));

		loader.freeze();
		assertThrows(IllegalStateException.class, () -> loader.reorder(List.of(a, b)));
	}

	@Test
	void byModIdIsAPlainStringSortThatKeepsTiesAndPutsNoIdLast() {
		List<String[]> mods = List.of(new String[] {"petsmod-nyan-cat", "1"}, new String[] {null, "2"},
				new String[] {"pets-mod", "3"}, new String[] {"fabricloader", "4"}, new String[] {"fabric-api", "5"},
				new String[] {"pets-mod", "6"});

		List<String> sorted = FabricLoadOrder.byModId(mods, mod -> mod[0]).stream().map(mod -> mod[1]).toList();

		// '-' sorts before letters, as String.compareTo has it and as Fabric Loader's comparator does.
		assertEquals(List.of("5", "4", "3", "6", "1", "2"), sorted);
	}

	// -------------------------------------------------------------------------------------------------------------

	private String build() throws Exception {
		return build(new ArrayList<>());
	}

	/** Runs discovery and {@link KernelFabricEcosystem#build} as KernelBoot does, and returns what it logged. */
	private String build(List<Path> classPath) throws Exception {
		FabricModDiscovery discovery = KernelFabricEcosystem.scan(EnvType.CLIENT, gameDir,
				DuplicateModArbiter.Decision.none());
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream out = System.out;
		try {
			System.setOut(new PrintStream(log, true, StandardCharsets.UTF_8));
			classPath.addAll(KernelFabricEcosystem.build(discovery, EnvType.CLIENT, gameDir, "26.2", new String[0],
					DuplicateModArbiter.Decision.none(), null));
		} finally {
			System.setOut(out);
		}
		KernelFabricEcosystem.bindGameLoader(getClass().getClassLoader());
		return log.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Whether the Fabric-order check of gate-m9 AND of compat/assert.sh accepts {@code log}, by running each one's
	 * own pattern through {@code grep -acE} as they do, so the wording and the gates cannot drift apart.
	 */
	private boolean matchesGates(String log) throws Exception {
		boolean m9 = grep(gatePattern("run/gate-m9-client.sh", "Fabric mods initialise in Fabric Loader's order (by mod id)"),
				log);
		boolean compat = grep(gatePattern("run/compat/assert.sh", "Fabric init is Fabric Loader order"), log);
		assertEquals(m9, compat, "gate-m9 and compat/assert.sh disagree about the same line");
		return m9;
	}

	private static String gatePattern(String gate, String name) throws IOException {
		Matcher m = Pattern.compile("(?:check|ck)\\s+\"" + Pattern.quote(name) + "\"\\s*\\\\?\\s*\"([^\"]+)\"")
				.matcher(Files.readString(Path.of(gate)));
		if (!m.find()) throw new AssertionError(gate + " has no \"" + name + "\" check");
		return m.group(1);
	}

	private boolean grep(String pattern, String text) throws Exception {
		Path log = Files.writeString(gameDir.resolve("boot.log"), text);
		Process grep = new ProcessBuilder("grep", "-acE", pattern, log.toString()).redirectErrorStream(true).start();
		assertTrue(grep.waitFor(15, TimeUnit.SECONDS), "grep timed out");
		return !"0".equals(new String(grep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
	}

	private static Field activeLoader() throws NoSuchFieldException {
		Field field = KernelFabricEcosystem.class.getDeclaredField("loader");
		field.setAccessible(true);
		return field;
	}

	private static List<String> ids(KernelFabricLoader loader) {
		return loader.getAllMods().stream().map(mod -> mod.getMetadata().getId()).toList();
	}

	private static List<String> mixinConfigs() {
		return KernelFabricEcosystem.mixinConfigs().stream().map(MixinConfigOwners.Owned::config).toList();
	}

	/** Each entry of {@code key} as declared, without constructing anything. */
	private static List<String> definitions(KernelFabricLoader loader, String key) {
		return containers(loader, key).stream().map(EntrypointContainer::getDefinition).toList();
	}

	private static List<String> providers(KernelFabricLoader loader, String key) {
		return containers(loader, key).stream().map(c -> c.getProvider().getMetadata().getId()).toList();
	}

	/**
	 * Every entry under {@code key}. The real mods' classes are not on this class path, so each is handed on as
	 * matching (a lifecycle key's load failure goes to its driver) and nothing is constructed.
	 */
	private static List<EntrypointContainer<Object>> containers(KernelFabricLoader loader, String key) {
		return loader.getEntrypointContainers(key, Object.class);
	}

	/** fabric-api's {@code ClientPlayConnectionEvents.JOIN} invoker, in shape: a plain loop, no catch per listener. */
	private static List<String> fireJoin() {
		JOINED.clear();
		try {
			for (Runnable listener : JOIN) listener.run();
		} catch (RuntimeException thrown) {
			// The throw leaves the loop. fabric-networking's caller catches it and logs it, and that is all.
		}
		return List.copyOf(JOINED);
	}

	private static KernelModContainer container(String id) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\"}";
		return new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null);
	}

	private static String manifest(String id, String depends, String entrypoints, String nested) {
		return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"depends\":" + depends
				+ ",\"entrypoints\":" + entrypoints + ",\"mixins\":[\"" + id + ".mixins.json\"]"
				+ (nested == null ? "" : ",\"jars\":[{\"file\":\"" + nested + "\"}]") + "}";
	}

	private static String stub(String id, String depends) {
		return stub(id, depends, List.of());
	}

	private static String stub(String id, String depends, java.util.Collection<String> nested) {
		StringBuilder jars = new StringBuilder();
		for (String file : nested) jars.append(jars.length() == 0 ? "" : ",").append("{\"file\":\"").append(file)
				.append("\"}");
		return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"depends\":" + depends
				+ (nested.isEmpty() ? "" : ",\"jars\":[" + jars + "]") + "}";
	}

	private static void module(Map<String, byte[]> modules, String file, String id, String depends) throws IOException {
		modules.put("META-INF/jars/" + file, jarBytes(stub(id, depends)));
	}

	private static String fixture(String name) throws IOException {
		try (InputStream in = FabricLoadOrderTest.class.getResourceAsStream("/fabric-order/" + name)) {
			if (in == null) throw new AssertionError("missing test resource /fabric-order/" + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void jar(String name, String manifest, Map<String, byte[]> nested) throws IOException {
		try (OutputStream out = Files.newOutputStream(mods.resolve(name))) {
			writeJar(out, manifest, nested);
		}
	}

	private static byte[] jarBytes(String manifest) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeJar(out, manifest, Map.of());
		return out.toByteArray();
	}

	private static void writeJar(OutputStream target, String manifest, Map<String, byte[]> nested) throws IOException {
		try (JarOutputStream out = new JarOutputStream(target)) {
			out.putNextEntry(new JarEntry(FabricModDiscovery.MANIFEST));
			out.write(manifest.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			for (Map.Entry<String, byte[]> entry : nested.entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
	}

	// -------------------------------------------------------------------------------------------------------------
	// Entrypoints: each records that it ran; each client one registers a JOIN listener, and mmm's throws.
	// -------------------------------------------------------------------------------------------------------------

	public static final class MmmPre implements PreLaunchEntrypoint {
		@Override public void onPreLaunch() { RAN.add("MmmPre"); }
	}

	public static final class ZzzPre implements PreLaunchEntrypoint {
		@Override public void onPreLaunch() { RAN.add("ZzzPre"); }
	}

	public static final class AaaMain implements ModInitializer {
		@Override public void onInitialize() { RAN.add("AaaMain"); }
	}

	public static final class BbbMain implements ModInitializer {
		@Override public void onInitialize() { RAN.add("BbbMain"); }
	}

	public static final class MmmMain implements ModInitializer {
		@Override public void onInitialize() { RAN.add("MmmMain"); }
	}

	public static final class ZzzMain implements ModInitializer {
		@Override public void onInitialize() { RAN.add("ZzzMain"); }
	}

	public static final class AaaClient implements ClientModInitializer {
		@Override public void onInitializeClient() {
			RAN.add("AaaClient");
			JOIN.add(() -> JOINED.add("aaa-nested"));
		}
	}

	public static final class BbbClient implements ClientModInitializer {
		@Override public void onInitializeClient() {
			RAN.add("BbbClient");
			JOIN.add(() -> JOINED.add("bbb"));
		}
	}

	/** Pets Mod's shape: this listener dereferences something that is null in singleplayer. */
	public static final class MmmClientA implements ClientModInitializer {
		@Override public void onInitializeClient() {
			RAN.add("MmmClientA");
			JOIN.add(() -> {
				throw new NullPointerException("Cannot read field \"ip\" because getCurrentServer() is null");
			});
		}
	}

	public static final class MmmClientB implements ClientModInitializer {
		@Override public void onInitializeClient() { RAN.add("MmmClientB"); }
	}

	public static final class ZzzClient implements ClientModInitializer {
		@Override public void onInitializeClient() {
			RAN.add("ZzzClient");
			JOIN.add(() -> JOINED.add("zzz-lib"));
		}
	}

	public static final class MmmProbe implements Runnable {
		@Override public void run() { }
	}

	public static final class BbbProbe implements Runnable {
		@Override public void run() { }
	}
}
