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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.boot.KernelRegistryDirectories;

/**
 * WorldWeaver's world presets and biome data are read from the directory WorldWeaver ships them in, as on native
 * Fabric.
 *
 * <p>Real bytes throughout, wherever they exist on this machine: {@code Registries}' directory methods from vanilla
 * 26.2, from the merged base and from MinecraftForge's patched game; NeoForge's own {@code prefixNamespace}; and the
 * two mixin handlers that decide the outcome on native Fabric — fabric-registry-sync's {@code RegistriesMixin} and
 * WorldWeaver's {@code RegistryDataLoaderMixinEarly} — out of the sweep pack's jars. Only {@code Identifier},
 * {@code ResourceKey} and wover's "is this one of my registries" lookup are stand-ins, because the real ones drag in
 * DataFixerUpper and wover's whole registry. The directory each arm answers is then checked against the files
 * WorldWeaver's jar actually ships, which is exactly what the content census counted: 5+3 presets and 10 biome
 * entries on native Fabric, none on Forbric.
 */
class RegistryDirectoryOwnerInjectorTest {
	private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path MERGED_BASE = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE_PATCHED = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEO_RUNTIME = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path VANILLA = Path.of(System.getProperty("user.home"),
			"Library/Application Support/minecraft/versions/26.2/26.2.jar");
	private static final Path SWEEP = Path.of("build/compat-inputs/sweep90/mods");
	private static final Path FABRIC_API = SWEEP.resolve("fabric-api-0.161.0+26.2.jar");
	private static final Path WORLDWEAVER = SWEEP.resolve("worldweaver-26.201.2.jar");

	private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
	private static final String COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
	private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
	private static final String FABRIC_MIXIN = "net/fabricmc/fabric/mixin/registry/sync/RegistriesMixin";
	private static final String WOVER_MIXIN = "de/ambertation/wover/core/mixin/registry/RegistryDataLoaderMixinEarly";
	private static final String WOVER_BUILDER = "de/ambertation/wover/core/impl/registry/DatapackRegistryBuilderImpl";
	private static final Set<String> DIR_METHODS = Set.of("registryDirPath", "elementsDirPath", "tagsDirPath",
			"componentsDirPath");

	private static final String PRESET_INFO = "wover/world_preset_info";
	private static final String BIOME_DATA = "wover/worldgen/biome_data";

	@BeforeEach
	@AfterEach
	void reset() {
		System.clearProperty(RegistryDirectoryOwnerInjector.PROPERTY);
		ModPresence.publishForgeFamily(List.of());
		ModPresence.publishFabric(List.of());
	}

	@Test
	void theMergedBodyIsNeoForgesAndTheEditHandsEveryAnswerToTheOwner() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = classBytes(MERGED_BASE, REGISTRIES);
		assertTrue(calls(method(node(in), "registryDirPath"), COMMON_HOOKS, "prefixNamespace"),
				"the merged body prefixes through NeoForge's hook — if it stopped, re-derive the repair");

		byte[] out = transform(in);
		assertNotSame(in, out);
		ClassNode node = node(out);
		MethodNode body = method(node, "registryDirPath");
		assertTrue(calls(body, RegistryDirectoryOwnerInjector.HOOK_OWNER, "registryDirPath"));
		assertTrue(body.maxStack >= 3, "namespace and path ride on top of the merged answer");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, body);
		assertSame(out, transform(out), "a second pass must not wrap the answer twice");
	}

	@Test
	void woversRegistriesReadTheDirectoryWoverShipsAsOnNativeFabric() throws Exception {
		assumeAll(VANILLA, MERGED_BASE, NEO_RUNTIME, FABRIC_API, WORLDWEAVER);
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "wover")));

		Arm nativeFabric = arm(registries(VANILLA, false));
		Arm before = arm(registries(MERGED_BASE, false));
		Arm after = arm(registries(MERGED_BASE, true));

		// Native Fabric: wover's own mixin hands back vanilla's body before fabric-registry-sync can prefix it.
		assertEquals(PRESET_INFO, nativeFabric.elements("wover", PRESET_INFO));
		assertEquals(Map.of("minecraft", 5, "wover", 3), shipped(nativeFabric.elements("wover", PRESET_INFO)),
				"the census's native-Fabric count: five vanilla presets and wover's own three");
		assertEquals(Map.of("minecraft", 10), shipped(nativeFabric.elements("wover", BIOME_DATA)));

		// The merged body prefixed already, so wover handed back a directory nothing ships.
		assertEquals("wover/" + PRESET_INFO, before.elements("wover", PRESET_INFO));
		assertEquals(Map.of(), shipped(before.elements("wover", PRESET_INFO)), "the census's Forbric count");
		assertEquals(Map.of(), shipped(before.elements("wover", BIOME_DATA)));

		for (String registry : List.of(PRESET_INFO, BIOME_DATA)) {
			assertEquals(nativeFabric.elements("wover", registry), after.elements("wover", registry), registry);
			assertEquals(shipped(nativeFabric.elements("wover", registry)), shipped(after.elements("wover", registry)));
			// wover's mixin leaves tagsDirPath alone, so fabric-registry-sync prefixes its tags on both.
			assertEquals("tags/wover/" + registry, nativeFabric.tags("wover", registry));
			assertEquals(nativeFabric.tags("wover", registry), after.tags("wover", registry), registry);
		}
	}

	@Test
	void everyOtherRegistryEndsWhereItsOwnLoaderPutsIt() throws Exception {
		assumeAll(VANILLA, MERGED_BASE, NEO_RUNTIME, FABRIC_API, WORLDWEAVER);
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "wover"), mod(Ecosystem.FABRIC, "owo"),
				mod(Ecosystem.FABRIC, "cloth-config")));
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.NEOFORGE, "examplemod"),
				mod(Ecosystem.NEOFORGE, "cloth_config")));

		Arm nativeFabric = arm(registries(VANILLA, false));
		Arm before = arm(registries(MERGED_BASE, false));
		Arm after = arm(registries(MERGED_BASE, true));

		// A plain Fabric registry: vanilla body now, and fabric-registry-sync puts the namespace back as natively.
		assertEquals("owo_things", after.body("elementsDirPath", "owo", "owo_things"));
		assertEquals("owo/owo_things", after.elements("owo", "owo_things"));
		assertEquals(nativeFabric.elements("owo", "owo_things"), after.elements("owo", "owo_things"));
		assertEquals(before.elements("owo", "owo_things"), after.elements("owo", "owo_things"));
		assertEquals(before.tags("owo", "owo_things"), after.tags("owo", "owo_things"));

		// A NeoForge mod's registry, one no mod claims, one two ecosystems claim, and vanilla's: the merged answer.
		for (String[] id : new String[][] {{"examplemod", "thing"}, {"unclaimed", "thing"}, {"cloth_config", "thing"},
				{"minecraft", "worldgen/biome"}}) {
			assertEquals(before.body("elementsDirPath", id[0], id[1]), after.body("elementsDirPath", id[0], id[1]),
					id[0]);
			assertEquals(before.body("tagsDirPath", id[0], id[1]), after.body("tagsDirPath", id[0], id[1]), id[0]);
		}
		assertEquals("examplemod/thing", after.body("elementsDirPath", "examplemod", "thing"));
		assertEquals("worldgen/biome", after.body("elementsDirPath", "minecraft", "worldgen/biome"));
		// componentsDirPath is datagen's, and Fabric never prefixed it.
		assertEquals(nativeFabric.body("componentsDirPath", "owo", "owo_things"),
				after.body("componentsDirPath", "owo", "owo_things"));
	}

	@Test
	void minecraftForgesInlineBodyIsEditedAtBothOfItsReturns() throws Exception {
		assumeAll(FORGE_PATCHED);
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "wover")));
		byte[] in = classBytes(FORGE_PATCHED, REGISTRIES);
		MethodNode original = method(node(in), "registryDirPath");
		assertEquals(2, count(original, Opcodes.ARETURN), "MinecraftForge prefixes inline, with its own return");

		Arm forge = arm(registries(FORGE_PATCHED, true));
		assertEquals(PRESET_INFO, forge.body("elementsDirPath", "wover", PRESET_INFO));
		assertEquals("examplemod/thing", forge.body("elementsDirPath", "examplemod", "thing"));
		assertEquals("worldgen/biome", forge.body("elementsDirPath", "minecraft", "worldgen/biome"));
	}

	@Test
	void switchedOffRegistriesStaysAsMerged() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		System.setProperty(RegistryDirectoryOwnerInjector.PROPERTY, "off");
		byte[] in = classBytes(MERGED_BASE, REGISTRIES);
		assertSame(in, transform(in));
		assertTrue(new RegistryDirectoryOwnerInjector().anchors().anchors().isEmpty());
	}

	@Test
	void otherClassesAreLeftAlone() {
		byte[] bytes = {(byte) 0xCA, (byte) 0xFE};
		assertSame(bytes, new RegistryDirectoryOwnerInjector().transform("net.minecraft.core.Registry", bytes, null));
	}

	@Test
	void theHookFallsBackToTheMergedAnswerRatherThanThrowing() {
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "wover")));
		assertEquals("wover/x", KernelRegistryDirectories.registryDirPath("wover/x", null, "x"));
		assertEquals("wover/x", KernelRegistryDirectories.registryDirPath("wover/x", "wover", null));
		assertEquals(null, KernelRegistryDirectories.registryDirPath(null, "unclaimed", "x"));
		assertEquals("x", KernelRegistryDirectories.registryDirPath(null, "wover", "x"));
		ModPresence.publishFabric(List.of());
		assertEquals("wover/x", KernelRegistryDirectories.registryDirPath("wover/x", "wover", "x"),
				"before the Fabric mods are published, every registry keeps the merged answer");
	}

	// --- one arm: Registries' directory methods, plus the two mixins as Mixin lays them out on native Fabric ---

	private record Arm(Class<?> registries, Class<?> identifier, Class<?> resourceKey, Method fabricElements,
			Method fabricTags, Method wover) {
		Object key(String namespace, String path) throws Exception {
			Object id = identifier.getConstructor(String.class, String.class).newInstance(namespace, path);
			return resourceKey.getConstructor(identifier).newInstance(id);
		}

		String body(String method, String namespace, String path) throws Exception {
			return (String) registries.getMethod(method, resourceKey).invoke(null, key(namespace, path));
		}

		/**
		 * {@code elementsDirPath} with both mixins applied. wover's {@code @Inject} (priority 200, applied first) goes
		 * in before the return; fabric-registry-sync's {@code @ModifyReturnValue} goes in after it, right in front of
		 * the same return. A cancelled callback returns from inside the first, so the second never sees it.
		 */
		String elements(String namespace, String path) throws Exception {
			Object key = key(namespace, path);
			String body = (String) registries.getMethod("elementsDirPath", resourceKey).invoke(null, key);
			CallbackInfoReturnable<String> callback = new CallbackInfoReturnable<>("elementsDirPath", true, body);
			wover.invoke(null, key, callback);
			if (callback.isCancelled()) return callback.getReturnValue();
			return (String) fabricElements.invoke(null, body, key);
		}

		/** {@code tagsDirPath}: only fabric-registry-sync's modifier targets it. */
		String tags(String namespace, String path) throws Exception {
			Object key = key(namespace, path);
			return (String) fabricTags.invoke(null, registries.getMethod("tagsDirPath", resourceKey).invoke(null, key),
					key);
		}
	}

	private static Arm arm(byte[] registries) throws Exception {
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(REGISTRIES, registries);
		classes.put(IDENTIFIER, identifierStandIn());
		classes.put(RESOURCE_KEY, resourceKeyStandIn());
		classes.put(WOVER_BUILDER, woverBuilderStandIn());
		if (Files.isRegularFile(NEO_RUNTIME)) {
			classes.put(COMMON_HOOKS, only(node(classBytes(NEO_RUNTIME, COMMON_HOOKS)), Set.of("prefixNamespace")));
		}
		if (Files.isRegularFile(FABRIC_API)) {
			classes.put(FABRIC_MIXIN, only(node(nestedClassBytes(FABRIC_API, "fabric-registry-sync-v0-", FABRIC_MIXIN)),
					Set.of("prependDirectoryWithNamespace", "prependTagDirectoryWithNamespace")));
		}
		if (Files.isRegularFile(WORLDWEAVER)) {
			classes.put(WOVER_MIXIN, only(node(nestedClassBytes(WORLDWEAVER, "wover-core-api-", WOVER_MIXIN)),
					Set.of("prependDirectoryWithNamespace")));
		}
		Game game = new Game(classes);
		Class<?> key = game.loadClass(RESOURCE_KEY.replace('/', '.'));
		Method fabricElements = null;
		Method fabricTags = null;
		Method wover = null;
		if (classes.containsKey(FABRIC_MIXIN)) {
			Class<?> fabric = game.loadClass(FABRIC_MIXIN.replace('/', '.'));
			fabricElements = fabric.getMethod("prependDirectoryWithNamespace", String.class, key);
			fabricTags = fabric.getMethod("prependTagDirectoryWithNamespace", String.class, key);
		}
		if (classes.containsKey(WOVER_MIXIN)) {
			wover = game.loadClass(WOVER_MIXIN.replace('/', '.'))
					.getMethod("prependDirectoryWithNamespace", key, CallbackInfoReturnable.class);
		}
		return new Arm(game.loadClass(REGISTRIES.replace('/', '.')), game.loadClass(IDENTIFIER.replace('/', '.')), key,
				fabricElements, fabricTags, wover);
	}

	/** {@code Registries} from {@code jar}, optionally through the injector, cut down to its directory methods. */
	private static byte[] registries(Path jar, boolean repaired) throws IOException {
		byte[] bytes = classBytes(jar, REGISTRIES);
		if (repaired) {
			byte[] out = transform(bytes);
			assertNotSame(bytes, out, "the injector must edit " + jar.getFileName());
			bytes = out;
		}
		return only(node(bytes), DIR_METHODS);
	}

	/**
	 * The class with only {@code keep}, made public and stripped of annotations (the mixin handlers' name
	 * MixinExtras types). Frames are kept as they are; the JVM verifies them when the arm defines the class.
	 */
	private static byte[] only(ClassNode source, Set<String> keep) {
		ClassNode out = new ClassNode();
		out.version = source.version;
		out.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		out.name = source.name;
		out.superName = "java/lang/Object";
		for (MethodNode m : source.methods) {
			if (!keep.contains(m.name)) continue;
			m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
			m.visibleAnnotations = null;
			m.invisibleAnnotations = null;
			m.visibleParameterAnnotations = null;
			m.invisibleParameterAnnotations = null;
			out.methods.add(m);
		}
		assertEquals(keep.size(), out.methods.size(), source.name + " lacks one of " + keep);
		ClassWriter writer = new ClassWriter(0);
		out.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] identifierStandIn() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, IDENTIFIER, null,
				"java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "namespace", "Ljava/lang/String;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "path", "Ljava/lang/String;", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V",
				null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitVarInsn(Opcodes.ALOAD, 1);
		init.visitFieldInsn(Opcodes.PUTFIELD, IDENTIFIER, "namespace", "Ljava/lang/String;");
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitVarInsn(Opcodes.ALOAD, 2);
		init.visitFieldInsn(Opcodes.PUTFIELD, IDENTIFIER, "path", "Ljava/lang/String;");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		getter(cw, IDENTIFIER, "getNamespace", "namespace", "Ljava/lang/String;");
		getter(cw, IDENTIFIER, "getPath", "path", "Ljava/lang/String;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] resourceKeyStandIn() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, RESOURCE_KEY, null,
				"java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "L" + IDENTIFIER + ";", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + IDENTIFIER + ";)V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitVarInsn(Opcodes.ALOAD, 1);
		init.visitFieldInsn(Opcodes.PUTFIELD, RESOURCE_KEY, "id", "L" + IDENTIFIER + ";");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		getter(cw, RESOURCE_KEY, "identifier", "id", "L" + IDENTIFIER + ";");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** wover's "is this one of my datapack registries": its registries are all in its own namespace. */
	private static byte[] woverBuilderStandIn() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, WOVER_BUILDER, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isRegistered",
				"(L" + IDENTIFIER + ";)Z", null, null);
		m.visitCode();
		m.visitLdcInsn("wover");
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IDENTIFIER, "getNamespace", "()Ljava/lang/String;", false);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
		m.visitInsn(Opcodes.IRETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void getter(ClassWriter cw, String owner, String name, String field, String desc) {
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()" + desc, null, null);
		m.visitCode();
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc);
		m.visitInsn(Opcodes.ARETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
	}

	/** Defines the arm's classes itself and asks the test's loader for everything else (Mixin's callback types). */
	private static final class Game extends ClassLoader {
		private final Map<String, byte[]> classes;

		Game(Map<String, byte[]> classes) {
			super(RegistryDirectoryOwnerInjectorTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded != null) return loaded;
				byte[] bytes = classes.get(name.replace('.', '/'));
				if (bytes == null) return super.loadClass(name, resolve);
				return defineClass(name, bytes, 0, bytes.length);
			}
		}
	}

	// --- what WorldWeaver's jar ships, counted the way RegistryDataLoader lists it ---

	/** Elements per namespace under {@code data/<ns>/<dir>/}, over wover's jar and every jar nested in it. */
	private static Map<String, Integer> shipped(String dir) throws IOException {
		Map<String, Integer> byNamespace = new TreeMap<>();
		try (ZipFile outer = new ZipFile(WORLDWEAVER.toFile())) {
			for (ZipEntry entry : Collections.list(outer.entries())) {
				count(entry.getName(), dir, byNamespace);
				if (!entry.getName().startsWith("META-INF/jars/") || !entry.getName().endsWith(".jar")) continue;
				try (ZipInputStream nested = new ZipInputStream(outer.getInputStream(entry))) {
					for (ZipEntry inner; (inner = nested.getNextEntry()) != null; ) count(inner.getName(), dir, byNamespace);
				}
			}
		}
		return byNamespace;
	}

	private static void count(String name, String dir, Map<String, Integer> into) {
		if (!name.startsWith("data/") || !name.endsWith(".json")) return;
		int slash = name.indexOf('/', "data/".length());
		if (slash > 0 && name.startsWith(dir + "/", slash + 1)) into.merge(name.substring(5, slash), 1, Integer::sum);
	}

	// --- plumbing ---

	private static byte[] transform(byte[] bytes) {
		return new RegistryDirectoryOwnerInjector().transform(RegistryDirectoryOwnerInjector.TARGET, bytes, null);
	}

	private static void assumeAll(Path... inputs) {
		for (Path input : inputs) assumeTrue(Files.isRegularFile(input), input + " absent — skipping real-bytes check");
	}

	private static DiscoveredMod mod(Ecosystem ecosystem, String id) {
		return new DiscoveredMod(ecosystem, id, "1.0.0", id, List.of(), List.of(), null, id + ".jar");
	}

	private static byte[] classBytes(Path jar, String internalName) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internalName + ".class");
			assertNotNull(entry, internalName + " is not in " + jar.getFileName());
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static byte[] nestedClassBytes(Path jar, String nestedPrefix, String internalName) throws IOException {
		try (ZipFile outer = new ZipFile(jar.toFile())) {
			List<String> seen = new ArrayList<>();
			for (ZipEntry entry : Collections.list(outer.entries())) {
				if (!entry.getName().startsWith("META-INF/jars/" + nestedPrefix)) continue;
				seen.add(entry.getName());
				try (ZipInputStream nested = new ZipInputStream(outer.getInputStream(entry))) {
					for (ZipEntry inner; (inner = nested.getNextEntry()) != null; ) {
						if (inner.getName().equals(internalName + ".class")) return nested.readAllBytes();
					}
				}
			}
			throw new AssertionError(internalName + " not found in " + jar.getFileName() + " " + seen);
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		throw new AssertionError(node.name + " has no " + name);
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}

	private static int count(MethodNode method, int opcode) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() == opcode) n++;
		return n;
	}
}
