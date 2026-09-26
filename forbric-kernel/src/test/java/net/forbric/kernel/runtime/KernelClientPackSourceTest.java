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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The REAL merged {@code Pack.readPackMetadata}, carrying a stand-in for fusion's {@code @ModifyArg} on its
 * {@code Pack$Metadata} constructor, read by the kernel for a mod pack whose metadata is that of Rechiseled
 * Anti-Blocks ({@code pack_format} 55, several versions behind this game).
 *
 * <p>The stand-in is a static call on the overlay list at exactly the argument fusion's {@code PackMixin} modifies
 * (index 3 of that constructor) — what Mixin weaves for a {@code @ModifyArg}, without Mixin. It appends
 * {@code fusion-overrides}, as fusion does when the pack declares {@code "fusion": {"overrides_folder": ...}}.
 *
 * <p>The pack's resources are a stand-in too, answering the pack section as an already-parsed record: parsing its
 * {@code description} needs the text-component codecs, and those need a bootstrapped registry set, which a unit
 * test cannot have. Everything between that answer and the served {@code Pack} is the game's code.
 */
class KernelClientPackSourceTest {
	private static final Path STAGED =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path MERGED_BASE = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_CARRIER = STAGED.resolve("forge-runtime/forge-runtime.jar");
	private static final Path RUNTIME = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path MC = Path.of(System.getenv().getOrDefault("MC_DIR",
			System.getProperty("user.home") + "/Library/Application Support/minecraft"));
	private static final String SOURCE = "net/forbric/kernel/runtime/KernelClientPackSource";
	private static final String PACK = "net/minecraft/server/packs/repository/Pack";
	private static final String METADATA_INIT = "(Lnet/minecraft/network/chat/Component;"
			+ "Lnet/minecraft/server/packs/repository/PackCompatibility;Lnet/minecraft/world/flag/FeatureFlagSet;"
			+ "Ljava/util/List;Z)V";
	/**
	 * The two places the merged base asks FML something on this path, and there is no FML in a unit test: the
	 * SharedConstants initialiser asks whether this is production, and FeatureFlags' initialiser (reached through
	 * the feature-flags metadata section) asks for modded flags. They answer as a production game with none.
	 */
	private static final String FML_ENVIRONMENT = "net/neoforged/fml/loading/FMLEnvironment";
	private static final String FEATURE_FLAG_LOADER = "net/neoforged/neoforge/common/util/flag/FeatureFlagLoader";

	/** fusion's overlay hook, as the woven call reaches it. Public: the merged Pack calls it across loaders. */
	public static final class FusionHook {
		public static List<String> addFusionOverrideOverlay(List<String> overlays) {
			List<String> out = new ArrayList<>(overlays);
			out.add("fusion-overrides");
			return out;
		}
	}

	@Test
	void theVanillaReaderRunsTheHookItsModPutOnReadPackMetadata() throws Exception {
		try (Game game = game()) {
			Object vanilla = game.read("readThroughVanilla", true);
			assertEquals(List.of("fusion-overrides"), game.overlays(vanilla), "the hook's overlay is on the served pack");
			assertEquals("COMPATIBLE", game.compatibility(vanilla),
					"pack_format 55 reads as too old for this game; mod assets are served forced-compatible");

			Object neo = game.read("readWithTheJarsOwnMeta", true);
			assertEquals(List.of(), game.overlays(neo),
					"premise: NeoForge's reader builds the metadata itself, so the hook never runs — every jar's path before");
		}
	}

	/** No pack section: vanilla's reader has nothing, and the caller goes on to NeoForge's as before. */
	@Test
	void aPackWithoutMetadataGivesTheVanillaReaderNothing() throws Exception {
		try (Game game = game()) {
			assertNull(game.read("readThroughVanilla", false));
		}
	}

	/** The routing: vanilla's reader only when the boot side says so, and NeoForge's after it, never instead of it. */
	@Test
	void buildPackTriesVanillasReaderFirstOnlyWhenAskedTo() throws Exception {
		Path compiled = RUNTIME.resolve(SOURCE + ".class");
		assumeTrue(Files.isRegularFile(compiled), "game side not compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode build = node.methods.stream().filter(m -> "buildPack".equals(m.name)
				&& "(Ljava/lang/String;Ljava/nio/file/Path;ZZ)Ljava/lang/Object;".equals(m.desc)).findFirst().orElseThrow();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : build.instructions) {
			if (insn instanceof MethodInsnNode call && SOURCE.equals(call.owner)) calls.add(call.name);
		}
		assertTrue(calls.indexOf("readThroughVanilla") >= 0, calls.toString());
		assertTrue(calls.indexOf("readThroughVanilla") < calls.indexOf("readWithTheJarsOwnMeta"), calls.toString());
		assertTrue(node.methods.stream().noneMatch(m -> "buildPack".equals(m.name)
				&& "(Ljava/lang/String;Ljava/nio/file/Path;Z)Ljava/lang/Object;".equals(m.desc)),
				"one entry point, so the boot side cannot reach the old one and skip the question");
	}

	// ---------------------------------------------------------------------------------------------------------

	private Game game() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE) && Files.isRegularFile(NEO_CARRIER)
				&& Files.isRegularFile(FORGE_CARRIER), "staged game jars absent");
		assumeTrue(Files.isDirectory(RUNTIME.resolve("net/forbric/kernel/runtime")), "game side not compiled");
		List<URL> urls = new ArrayList<>(List.of(RUNTIME.toUri().toURL(), MERGED_BASE.toUri().toURL(),
				NEO_CARRIER.toUri().toURL(), FORGE_CARRIER.toUri().toURL()));
		urls.addAll(minecraftLibraries());
		return new Game(new GameLoader(urls.toArray(URL[]::new), getClass().getClassLoader(),
				hooked(entry(MERGED_BASE, PACK + ".class")), productionEnvironment(), noModdedFlags()));
	}

	/** The merged Pack with the stand-in woven at the overlay argument of readPackMetadata's Metadata constructor. */
	private static byte[] hooked(byte[] pack) {
		ClassNode node = new ClassNode();
		new ClassReader(pack).accept(node, 0);
		int woven = 0;
		for (MethodNode method : node.methods) {
			if (!"readPackMetadata".equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
						|| !(PACK + "$Metadata").equals(init.owner) || !METADATA_INIT.equals(init.desc)) continue;
				// Stack: ..., Component, PackCompatibility, FeatureFlagSet, List, boolean. The boolean is computed
				// last (`aload resources; invokeinterface isHidden`), so right before it the List is on top.
				AbstractInsnNode isHidden = init.getPrevious();
				AbstractInsnNode resources = isHidden.getPrevious();
				method.instructions.insertBefore(resources, new MethodInsnNode(Opcodes.INVOKESTATIC,
						FusionHook.class.getName().replace('.', '/'), "addFusionOverrideOverlay",
						"(Ljava/util/List;)Ljava/util/List;", false));
				woven++;
			}
		}
		assertEquals(1, woven, "premise: the merged readPackMetadata builds its Metadata once, where fusion's hook sits");
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] productionEnvironment() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, FML_ENVIRONMENT, null, "java/lang/Object", null);
		var isProduction = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isProduction", "()Z", null, null);
		isProduction.visitCode();
		isProduction.visitInsn(Opcodes.ICONST_1);
		isProduction.visitInsn(Opcodes.IRETURN);
		isProduction.visitMaxs(1, 0);
		isProduction.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] noModdedFlags() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, FEATURE_FLAG_LOADER, null, "java/lang/Object", null);
		var load = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "loadModdedFlags",
				"(Lnet/minecraft/world/flag/FeatureFlagRegistry$Builder;)V", null, null);
		load.visitCode();
		load.visitInsn(Opcodes.RETURN);
		load.visitMaxs(0, 1);
		load.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
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

	/** Parent-first, except the hooked Pack and the two FML answers, which are defined from the bytes above. */
	private static final class GameLoader extends URLClassLoader {
		private final byte[] pack;
		private final byte[] environment;
		private final byte[] flags;

		GameLoader(URL[] urls, ClassLoader parent, byte[] pack, byte[] environment, byte[] flags) {
			super(urls, parent);
			this.pack = pack;
			this.environment = environment;
			this.flags = flags;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (PACK.replace('/', '.').equals(name)) return defineClass(name, pack, 0, pack.length);
			if (FML_ENVIRONMENT.replace('/', '.').equals(name)) return defineClass(name, environment, 0, environment.length);
			if (FEATURE_FLAG_LOADER.replace('/', '.').equals(name)) return defineClass(name, flags, 0, flags.length);
			return super.findClass(name);
		}
	}

	private static final class Game implements AutoCloseable {
		private final URLClassLoader loader;
		private final Class<?> source;
		private final Class<?> location;
		private final Class<?> supplier;
		private final Class<?> selection;
		private final Object locationInfo;
		private final Object selectionConfig;
		private final Object section;
		private final Object clientType;
		private final Field metadata;
		private final Method overlays;
		private final Method compatibility;

		Game(URLClassLoader loader) throws Exception {
			this.loader = loader;
			type("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
			source = type(SOURCE.replace('/', '.'));
			Class<?> component = type("net.minecraft.network.chat.Component");
			Object title = component.getMethod("literal", String.class).invoke(null, "forbric/antiblocksrechiseled");
			Class<?> packSource = type("net.minecraft.server.packs.repository.PackSource");
			location = type("net.minecraft.server.packs.PackLocationInfo");
			locationInfo = location.getConstructor(String.class, component, packSource, Optional.class)
					.newInstance("forbric/antiblocksrechiseled", title, packSource.getField("BUILT_IN").get(null), Optional.empty());
			Class<?> position = type(PACK.replace('/', '.') + "$Position");
			selection = type("net.minecraft.server.packs.PackSelectionConfig");
			selectionConfig = selection.getConstructor(boolean.class, position, boolean.class)
					.newInstance(false, position.getField("TOP").get(null), false);
			supplier = type(PACK.replace('/', '.') + "$ResourcesSupplier");

			// What Rechiseled Anti-Blocks' pack.mcmeta parses to: pack_format 55, its description.
			Class<?> packFormat = type("net.minecraft.server.packs.metadata.pack.PackFormat");
			Class<?> range = type("net.minecraft.util.InclusiveRange");
			Object formats = range.getConstructor(Comparable.class)
					.newInstance(packFormat.getMethod("of", int.class).invoke(null, 55));
			Class<?> sectionType = type("net.minecraft.server.packs.metadata.pack.PackMetadataSection");
			section = sectionType.getConstructor(component, range)
					.newInstance(component.getMethod("literal", String.class).invoke(null, "antiblocksrechiseled resources"), formats);
			clientType = sectionType.getField("CLIENT_TYPE").get(null);

			Class<?> pack = type(PACK.replace('/', '.'));
			metadata = pack.getDeclaredField("metadata");
			metadata.setAccessible(true);
			Class<?> meta = type(PACK.replace('/', '.') + "$Metadata");
			overlays = meta.getMethod("overlays");
			compatibility = meta.getMethod("compatibility");
		}

		/** One of KernelClientPackSource's two readers over a pack that has (or lacks) its pack section. */
		Object read(String reader, boolean withSection) throws Exception {
			Class<?> resourcesType = type("net.minecraft.server.packs.PackResources");
			Object resources = Proxy.newProxyInstance(loader, new Class<?>[] {resourcesType}, (proxy, method, args) ->
					switch (method.getName()) {
						case "getMetadataSection" -> withSection && args[0] == clientType ? section : null;
						case "location" -> locationInfo;
						case "isHidden" -> false;
						case "packId" -> "forbric/antiblocksrechiseled";
						case "knownPackInfo" -> Optional.empty();
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> null;
					});
			Object resourcesSupplier = Proxy.newProxyInstance(loader, new Class<?>[] {supplier},
					(proxy, method, args) -> method.getName().startsWith("open") ? resources : null);
			Method read = source.getDeclaredMethod(reader, location, supplier, selection);
			read.setAccessible(true);
			try {
				return read.invoke(null, locationInfo, resourcesSupplier, selectionConfig);
			} catch (InvocationTargetException e) {
				throw new AssertionError(reader + " threw", e.getCause());
			}
		}

		List<?> overlays(Object pack) throws Exception {
			return (List<?>) overlays.invoke(metadata.get(pack));
		}

		String compatibility(Object pack) throws Exception {
			return String.valueOf(compatibility.invoke(metadata.get(pack)));
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
