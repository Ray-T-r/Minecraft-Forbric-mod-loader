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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.transform.ModelFormatFunnelInjector;

/**
 * NeoForge's REAL model deserializer, funnelled by the REAL injector, deciding real model JSON against a real
 * fabric-model-loading registry and a real NeoForge loader table.
 *
 * <p>The one stand-in is the vanilla {@code CuboidModel$Deserializer}, because on the merged base it drags in both
 * carriers' client hooks. It is replaced by an adapter doing what that deserializer and the hooks on it do with the
 * {@code "loader"} key: fusion's HEAD hook claims {@code fusion:model}; otherwise MinecraftForge's
 * {@code getElements} reads the key with {@code GsonHelper.getAsString(json, "loader", null)} and throws
 * {@code Model loader '%s' not found} for an id nobody registered; no key at all is a plain cuboid.
 *
 * <p>Each case runs twice where it matters: through the funnelled deserializer, and through NeoForge's bytes as
 * shipped — which is the symptom (Traveler's Backpack's {@code fabric:type} models parsing as plain cuboids,
 * fusion's {@code "loader": "fusion:model"} dying on {@code Unknown loader}) reproduced, so the assertion on the
 * funnelled run is measured against the bug and not against a guess.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelModelFormatsTest {
	private static final Path STAGED =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path MERGED_BASE = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	/** The merged base names MinecraftForge types in signatures, so reflecting on it needs this carrier too. */
	private static final Path FORGE_CARRIER = STAGED.resolve("forge-runtime/forge-runtime.jar");
	/** The fabric-api jar build.gradle compiles the game side's transfer bridge against, found the same way. */
	private static final Path FABRIC_API = STAGED.getParent().getParent()
			.resolve("forbric-kernel/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar");
	private static final Path RUNTIME = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path MC = Path.of(System.getenv().getOrDefault("MC_DIR",
			System.getProperty("user.home") + "/Library/Application Support/minecraft"));
	private static final String TARGET = "net/neoforged/neoforge/client/model/UnbakedModelParser$Deserializer";
	private static final String DESERIALIZER = TARGET.replace('/', '.');

	@TempDir
	Path temp;

	@AfterEach
	void reset() {
		System.clearProperty(ModelFormatFunnelInjector.PROPERTY);
	}

	@Test
	void aFabricTypeModelIsParsedByTheDeserializerItsModRegistered() throws Exception {
		try (Game funnelled = game(true); Game shipped = game(false)) {
			assertEquals("fabric", funnelled.parse("{\"fabric:type\": \"fabrictest:backpack\", \"backpackTexture\": \"x\"}"));
			assertTrue(funnelled.fabricSaw.get(0).contains("backpackTexture"), "the whole object, as Fabric hands it");
			assertEquals("fabric", funnelled.parse("{\"fabric:type\": {\"id\": \"fabrictest:backpack\"}}"),
					"the object form Fabric accepts");

			assertEquals("plain", shipped.parse("{\"fabric:type\": \"fabrictest:backpack\"}"),
					"premise: as shipped, NeoForge never reads fabric:type — Traveler's Backpack's symptom");
		}
	}

	@Test
	void aFabricTypeNobodyRegisteredFailsTheWayFabricFailsIt() throws Exception {
		try (Game game = game(true)) {
			Throwable unknown = game.fail("{\"fabric:type\": \"fabrictest:missing\"}");
			assertEquals("Cannot deserialize custom unbaked model of unknown type 'fabrictest:missing'", unknown.getMessage());
			Throwable malformed = game.fail("{\"fabric:type\": []}");
			assertEquals("com.google.gson.JsonSyntaxException", malformed.getClass().getName());
			assertTrue(malformed.getMessage().startsWith("Expected fabric:type to be a string or object"), malformed.getMessage());

			assertEquals("plain", game.parse("{\"fabric:type\": {\"id\": \"fabrictest:missing\", \"optional\": true}}"),
					"optional and absent: a plain model, as on Fabric");
		}
	}

	/**
	 * One JSON shared by a mod's builds can carry both keys, and only the installed build registered anything. The
	 * NeoForge or MinecraftForge build next to fabric-api used to fail every such model on Fabric's "unknown type".
	 */
	@Test
	void aModelNamingBothKeysIsDecidedByTheBuildThatIsInstalled() throws Exception {
		try (Game game = game(true)) {
			assertEquals("neo", game.parse("{\"fabric:type\": \"fabrictest:missing\", \"loader\": \"neotest:fmt\"}"),
					"the NeoForge build is installed: NeoForge's loader, as on NeoForge");
			assertEquals("neo", game.parse("{\"fabric:type\": \"fabrictest:missing\", \"loader\": {\"id\": \"neotest:fmt\"}}"));
			assertEquals("fusion", game.parse("{\"fabric:type\": \"fabrictest:missing\", \"loader\": \"fusion:model\"}"),
					"a MinecraftForge build: the cuboid deserializer and the hooks on it, as on MinecraftForge");
			assertEquals("neo", game.parse("{\"fabric:type\": [], \"loader\": \"neotest:fmt\"}"),
					"a key that does not even parse is ignored as every non-Fabric loader ignores it");
			assertEquals("neo", game.parse("{\"fabric:type\": {\"optional\": true}, \"loader\": \"neotest:fmt\"}"));

			assertEquals("fabric", game.parse("{\"fabric:type\": \"fabrictest:backpack\", \"loader\": \"neotest:fmt\"}"),
					"a registered type is the mod's Fabric build saying it is the one running; on Fabric \"loader\" means nothing");
			assertTrue(game.fail("{\"fabric:type\": \"fabrictest:missing\"}").getMessage().startsWith("Cannot deserialize"),
					"with no loader to decide, a miss is still Fabric's error");
		}
	}

	/**
	 * A LinkageError is not an Exception: out of guest code it would leave ModelManager's per-model catch, fail the
	 * reload, and a failed reload drops every resource pack and leaves a black screen. One model fails instead.
	 */
	@Test
	void aForeignFormatThatDoesNotLinkFailsOneModelNotTheReload() throws Exception {
		try (Game game = game(true)) {
			Throwable fabric = game.fail("{\"fabric:type\": \"fabrictest:broken\"}");
			assertEquals("com.google.gson.JsonParseException", fabric.getClass().getName());
			assertTrue(fabric.getMessage().startsWith("fabric:type fabrictest:broken does not link"), fabric.getMessage());
			assertInstanceOf(NoSuchMethodError.class, fabric.getCause());

			Throwable forge = game.fail("{\"loader\": \"brokenfmt:model\"}");
			assertEquals("com.google.gson.JsonParseException", forge.getClass().getName());
			assertTrue(forge.getMessage().startsWith("\"loader\": \"brokenfmt:model\" does not link"), forge.getMessage());
			assertInstanceOf(NoClassDefFoundError.class, forge.getCause());

			assertEquals("fabric", game.parse("{\"fabric:type\": \"fabrictest:backpack\"}"), "and the next model still parses");
		}
	}

	@Test
	void aLoaderNeoForgeDoesNotOwnReachesTheDeserializerMinecraftForgeAndFusionRead() throws Exception {
		try (Game funnelled = game(true); Game shipped = game(false)) {
			assertEquals("fusion", funnelled.parse("{\"loader\": \"fusion:model\", \"type\": \"fusion:connecting\"}"));
			assertEquals(List.of("fusion:model"), funnelled.cuboidLoaders, "handed on with its loader intact");

			Throwable neo = shipped.fail("{\"loader\": \"fusion:model\"}");
			assertTrue(neo.getMessage().startsWith("Unknown loader: fusion:model"),
					"premise: as shipped, NeoForge throws before any of them look — " + neo.getMessage());
			assertTrue(shipped.cuboidLoaders.isEmpty());
		}
	}

	@Test
	void aLoaderNobodyClaimsStillFailsTheModel() throws Exception {
		try (Game game = game(true)) {
			Throwable nobody = game.fail("{\"loader\": \"nobody:fmt\"}");
			assertTrue(nobody.getMessage().startsWith("Model loader 'nobody:fmt' not found"),
					"the last deserializer that looked reports it: " + nobody.getMessage());
		}
	}

	@Test
	void neoForgesOwnLoadersKeepTheirPrecedence() throws Exception {
		try (Game game = game(true)) {
			assertEquals("neo", game.parse("{\"loader\": \"neotest:fmt\"}"));
			assertEquals("neo", game.parse("{\"loader\": {\"id\": \"neotest:fmt\"}}"));
			assertTrue(game.cuboidLoaders.isEmpty(), "a NeoForge loader never reaches the cuboid deserializer");
		}
	}

	@Test
	void neoForgesObjectFormKeepsItsOwnMeaning() throws Exception {
		try (Game funnelled = game(true); Game shipped = game(false)) {
			Throwable required = funnelled.fail("{\"loader\": {\"id\": \"nobody:fmt\"}}");
			assertTrue(required.getMessage().startsWith("Unknown loader: nobody:fmt"), "NeoForge's dialect, NeoForge's error");

			assertEquals("plain", funnelled.parse("{\"loader\": {\"id\": \"nobody:fmt\", \"optional\": true}, \"parent\": \"p\"}"));
			assertFalse(funnelled.cuboidSaw.get(0).contains("\"loader\""),
					"the optional miss is parsed plain WITHOUT the object, which MinecraftForge's half cannot read");
			assertTrue(funnelled.cuboidSaw.get(0).contains("\"parent\""), "and nothing else is dropped");

			Throwable merged = shipped.fail("{\"loader\": {\"id\": \"nobody:fmt\", \"optional\": true}}");
			assertEquals("com.google.gson.JsonSyntaxException", merged.getClass().getName(),
					"premise: as shipped, an optional miss hands the object to MinecraftForge's string read — " + merged);
		}
	}

	@Test
	void aVanillaModelIsUntouched() throws Exception {
		try (Game game = game(true)) {
			assertEquals("plain", game.parse("{\"parent\": \"minecraft:block/cube_all\"}"));
			assertEquals(1, game.cuboidSaw.size());
		}
	}

	/** Without fabric-model-loading nothing reads the key on any loader, so neither does the funnel. */
	@Test
	void withoutFabricModelLoadingTheKeyIsIgnoredAsEverywhere() throws Exception {
		try (Game game = game(true, false)) {
			assertEquals("plain", game.parse("{\"fabric:type\": \"fabrictest:backpack\"}"));
			assertEquals("plain", game.parse("{\"fabric:type\": []}"), "not even parsed");
		}
	}

	@Test
	void switchedOffTheFunnelAnswersNothing() throws Exception {
		System.setProperty(ModelFormatFunnelInjector.PROPERTY, "off");
		try (Game game = game(true)) {
			assertEquals("plain", game.parse("{\"fabric:type\": \"fabrictest:backpack\"}"));
			assertTrue(game.fail("{\"loader\": \"fusion:model\"}").getMessage().startsWith("Unknown loader: fusion:model"));
		}
	}

	// ---------------------------------------------------------------------------------------------------------

	/** One game-side world: its own loader, so the kernel's and fabric-api's statics start empty every time. */
	private Game game(boolean funnelled) throws Exception {
		return game(funnelled, true);
	}

	private Game game(boolean funnelled, boolean fabricApi) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE) && Files.isRegularFile(NEO_CARRIER)
				&& Files.isRegularFile(FORGE_CARRIER), "staged game jars absent");
		assumeTrue(Files.isDirectory(RUNTIME.resolve("net/forbric/kernel/runtime")), "game side not compiled");
		assumeTrue(Files.isRegularFile(FABRIC_API), "staged fabric-api absent at " + FABRIC_API);
		byte[] shipped = entry(NEO_CARRIER, TARGET + ".class");
		byte[] bytes = funnelled ? new ModelFormatFunnelInjector().transform(DESERIALIZER, shipped, null) : shipped;

		List<URL> urls = new ArrayList<>(List.of(RUNTIME.toUri().toURL(), MERGED_BASE.toUri().toURL(),
				NEO_CARRIER.toUri().toURL(), FORGE_CARRIER.toUri().toURL()));
		if (fabricApi) urls.add(modelLoadingModule().toUri().toURL());
		urls.addAll(minecraftLibraries());
		return new Game(new FunnelLoader(urls.toArray(URL[]::new), getClass().getClassLoader(), bytes), fabricApi);
	}

	private Path modelLoadingModule() throws IOException {
		Path out = temp.resolve("fabric-model-loading-api-v1.jar");
		if (Files.isRegularFile(out)) return out;
		try (ZipFile zip = new ZipFile(FABRIC_API.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-model-loading-api-v1-"))
					.findFirst().orElse(null);
			assumeTrue(nested != null, "this fabric-api does not nest fabric-model-loading-api-v1");
			try (InputStream in = zip.getInputStream(nested)) {
				Files.copy(in, out);
			}
			return out;
		}
	}

	private static List<URL> minecraftLibraries() throws IOException {
		Path version = MC.resolve("versions/26.2/26.2.json");
		assumeTrue(Files.isRegularFile(version), "Minecraft 26.2 version json absent");
		var json = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser()
				.parse(Files.newBufferedReader(version));
		List<URL> urls = new ArrayList<>();
		List<? extends com.electronwill.nightconfig.core.UnmodifiableConfig> libraries = json.get("libraries");
		for (var library : libraries) {
			String name = library.get(List.of("downloads", "artifact", "path"));
			if (name != null && Files.isRegularFile(MC.resolve("libraries").resolve(name))) {
				urls.add(MC.resolve("libraries").resolve(name).toUri().toURL());
			}
		}
		return urls;
	}

	private static byte[] entry(Path jar, String name) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			assumeTrue(entry != null, name + " absent from " + jar);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	/** Parent-first everywhere except NeoForge's deserializer, which is defined from the bytes under test. */
	private static final class FunnelLoader extends URLClassLoader {
		private final byte[] deserializer;

		FunnelLoader(URL[] urls, ClassLoader parent, byte[] deserializer) {
			super(urls, parent);
			this.deserializer = deserializer;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (DESERIALIZER.equals(name)) return defineClass(name, deserializer, 0, deserializer.length);
			return super.findClass(name);
		}
	}

	/**
	 * {@code CuboidModel.GSON}'s shape with the stand-in: NeoForge's deserializer as the hierarchy adapter for
	 * {@code UnbakedModel}, the stand-in as the exact adapter for {@code CuboidModel}. One NeoForge loader
	 * ({@code neotest:fmt}) and one Fabric deserializer ({@code fabrictest:backpack}) are registered the way their
	 * mods register them.
	 */
	private static final class Game implements AutoCloseable {
		final URLClassLoader loader;
		final List<String> fabricSaw = new ArrayList<>();
		final List<String> cuboidSaw = new ArrayList<>();
		final List<String> cuboidLoaders = new ArrayList<>();
		private final Object gson;
		private final Method fromJson;
		private final Class<?> unbakedModel;
		private final java.lang.reflect.Constructor<?> cuboidModel;
		private final Method identifier;
		private final Method parent;

		Game(URLClassLoader loader, boolean fabricApiInstalled) throws Exception {
			this.loader = loader;
			unbakedModel = type("net.minecraft.client.resources.model.UnbakedModel");
			Class<?> identifier = type("net.minecraft.resources.Identifier");
			Method parse = identifier.getMethod("parse", String.class);
			this.identifier = parse;
			Class<?> cuboid = type("net.minecraft.client.resources.model.cuboid.CuboidModel");
			cuboidModel = cuboid.getConstructor(type("net.minecraft.client.resources.model.geometry.UnbakedGeometry"),
					type("net.minecraft.client.resources.model.UnbakedModel$GuiLight"), Boolean.class,
					type("net.minecraft.client.resources.model.cuboid.ItemTransforms"),
					type("net.minecraft.client.resources.model.sprite.TextureSlots$Data"), identifier);
			this.parent = cuboid.getMethod("parent");
			Class<?> jsonDeserializer = type("com.google.gson.JsonDeserializer");
			Class<?> jsonObject = type("com.google.gson.JsonObject");
			Method getAsJsonObject = type("com.google.gson.JsonElement").getMethod("getAsJsonObject");
			Method getAsString = type("net.minecraft.util.GsonHelper")
					.getMethod("getAsString", jsonObject, String.class, String.class);
			Class<?> parseException = type("com.google.gson.JsonParseException");

			// NeoForge's loader table, as UnbakedModelParser.init() leaves it after RegisterLoaders.
			Class<?> neoLoader = type("net.neoforged.neoforge.client.model.UnbakedModelLoader");
			Object neo = Proxy.newProxyInstance(loader, new Class<?>[] {neoLoader},
					(proxy, method, args) -> "read".equals(method.getName()) ? model("neo") : null);
			Field loaders = type("net.neoforged.neoforge.client.model.UnbakedModelParser").getDeclaredField("LOADERS");
			loaders.setAccessible(true);
			loaders.set(null, type("com.google.common.collect.ImmutableMap").getMethod("of", Object.class, Object.class)
					.invoke(null, parse.invoke(null, "neotest:fmt"), neo));

			// A Fabric mod's deserializer, registered through fabric-model-loading's own API.
			if (fabricApiInstalled) {
				Class<?> fabricApi = type("net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer");
				Object fabric = Proxy.newProxyInstance(loader, new Class<?>[] {fabricApi}, (proxy, method, args) -> {
					if (!"deserialize".equals(method.getName())) return null;
					fabricSaw.add(String.valueOf(args[0]));
					return model("fabric");
				});
				fabricApi.getMethod("register", identifier, fabricApi).invoke(null, parse.invoke(null, "fabrictest:backpack"), fabric);
				// One compiled against another base: its first call into the game does not link.
				Object broken = Proxy.newProxyInstance(loader, new Class<?>[] {fabricApi}, (proxy, method, args) -> {
					if (!"deserialize".equals(method.getName())) return null;
					throw new NoSuchMethodError("'void net.minecraft.client.renderer.block.model.BlockModel.<init>()'");
				});
				fabricApi.getMethod("register", identifier, fabricApi).invoke(null, parse.invoke(null, "fabrictest:broken"), broken);
			}

			// The vanilla cuboid deserializer and what sits on it — see the class javadoc.
			Object standIn = Proxy.newProxyInstance(loader, new Class<?>[] {jsonDeserializer}, (proxy, method, args) -> {
				if (!"deserialize".equals(method.getName())) return null;
				Object json = getAsJsonObject.invoke(args[0]);
				cuboidSaw.add(String.valueOf(json));
				String id = (String) invoke(getAsString, null, json, "loader", null);
				if (id == null) return model("plain");
				cuboidLoaders.add(id);
				if ("fusion:model".equals(id)) return model("fusion");
				if ("brokenfmt:model".equals(id)) throw new NoClassDefFoundError("net/minecraftforge/client/model/Gone");
				throw (Throwable) parseException.getConstructor(String.class).newInstance(
						"Model loader '" + id + "' not found. Registered loaders: forge:obj");
			});

			Class<?> builder = type("com.google.gson.GsonBuilder");
			Object gsonBuilder = builder.getConstructor().newInstance();
			builder.getMethod("registerTypeHierarchyAdapter", Class.class, Object.class)
					.invoke(gsonBuilder, unbakedModel, type(DESERIALIZER).getConstructor().newInstance());
			builder.getMethod("registerTypeAdapter", java.lang.reflect.Type.class, Object.class)
					.invoke(gsonBuilder, cuboid, standIn);
			gson = builder.getMethod("create").invoke(gsonBuilder);
			fromJson = gson.getClass().getMethod("fromJson", String.class, Class.class);
		}

		/** Which model the JSON became: "neo", "fabric", "fusion" or "plain". */
		String parse(String json) throws Exception {
			try {
				Object model = fromJson.invoke(gson, json, unbakedModel);
				return String.valueOf(parent.invoke(model)).replace("marker:", "");
			} catch (InvocationTargetException e) {
				throw new AssertionError("expected a model from " + json, e.getCause());
			}
		}

		/** The exception parsing {@code json} ends in. */
		Throwable fail(String json) {
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
					() -> fromJson.invoke(gson, json, unbakedModel), json);
			Throwable cause = thrown.getCause();
			assertInstanceOf(RuntimeException.class, cause);
			return cause;
		}

		/**
		 * A real {@code CuboidModel} whose parent names it: Gson checks that an adapter asked for a
		 * {@code CuboidModel} returned one, as it does for fusion's hook in the game.
		 */
		private Object model(String name) throws Exception {
			return cuboidModel.newInstance(null, null, null, null, null, identifier.invoke(null, "marker:" + name));
		}

		private static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
			try {
				return method.invoke(receiver, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		private Class<?> type(String name) throws ClassNotFoundException {
			return Class.forName(name, true, loader);
		}

		@Override
		public void close() throws IOException {
			loader.close();
		}
	}
}
