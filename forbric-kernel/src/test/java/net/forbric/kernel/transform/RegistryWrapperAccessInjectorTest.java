/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.KernelBoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The real MinecraftForge registry wrappers, from the staged carrier: which classes stand in for vanilla's registries,
 * and a mod's reflective lookup on one, run in a real JVM against the merged game and both carriers.
 *
 * <p>The lookup is Meow Anti-Xray's {@code findBlockById}, made from this class — another package, as the mod's is —
 * on a bare {@code NamespacedDefaultedWrapper}, the class {@code BuiltInRegistries.BLOCK} is on the merged game. Its
 * {@code ForgeRegistry} is a same-package fixture answering {@code getRaw} and {@code getValue}, the one call each of
 * {@code getOptional} and the defaulted wrapper's {@code getValue} makes.
 *
 * <p>The wrappers are defined either as the carrier ships them, through this injector alone, or through every wrapper
 * repair KernelBoot registers ({@link #WRAPPER_REPAIRS}), as the game defines them. Only the last shows what a mod meets:
 * {@code getMethod} resolves the types of every public method of each class it searches, from the runtime class up to the
 * first that declares a match, so a repair that adds a public method naming a class the game does not have breaks every
 * lookup that reaches that class, before any access check.
 */
@ResourceLock("system-properties")
class RegistryWrapperAccessInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_CARRIER = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final Path FORGE_CARRIER = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final String REGISTRY = "net/minecraft/core/Registry";
	private static final String WRAPPER = "net/minecraftforge/registries/NamespacedWrapper";
	private static final String DEFAULTED_WRAPPER = "net/minecraftforge/registries/NamespacedDefaultedWrapper";
	private static final String FORGE_REGISTRY = "net/minecraftforge/registries/ForgeRegistry";
	private static final String FIXTURE_REGISTRY = "net/minecraftforge/registries/FixtureForgeRegistry";
	private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
	private static final String REMAP_MODE = "net/fabricmc/fabric/impl/registry/sync/RemappableRegistry$RemapMode";

	/**
	 * Every COREMOD transformer KernelBoot registers that edits a wrapper, in KernelBoot's order. Held to KernelBoot and to
	 * the transformers by {@link #theWrapperRepairsAreEveryTransformerThatNamesAWrapperInKernelBootsOrder}.
	 */
	private static final List<String> WRAPPER_REPAIRS = List.of(
			RegistryAliasParityInjector.class.getName(),
			RegistrySyncParityInjector.class.getName(),
			WrapperEntryAddedInjector.class.getName(),
			RegistryWrapperAccessInjector.class.getName());

	@AfterEach void reset() { System.clearProperty(RegistryWrapperAccessInjector.PROPERTY); }

	/**
	 * What the injector's target list must be, re-derived from the jars: every class in either carrier that implements
	 * vanilla's {@code Registry}, and which of them are not public. A carrier repin that adds one fails here.
	 */
	@Test void theWrappersAreTheOnlyNonPublicRegistriesInEitherCarrier() throws IOException {
		Map<String, Header> classes = new HashMap<>();
		for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) {
			TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			headers(jar, classes);
		}
		assertTrue(classes.get("net/minecraft/core/MappedRegistry").isPublic() && classes.get("net/minecraft/core/DefaultedMappedRegistry").isPublic(),
				"premise: the vanilla registries the wrappers stand in for are public");

		Set<String> carrierRegistries = new TreeSet<>(), nonPublic = new TreeSet<>();
		for (var entry : classes.entrySet()) {
			if (entry.getKey().startsWith("net/minecraft/") || !implementsRegistry(entry.getKey(), classes)) continue;
			carrierRegistries.add(entry.getKey());
			if (!entry.getValue().isPublic()) nonPublic.add(entry.getKey().replace('/', '.'));
		}
		assertTrue(carrierRegistries.contains(DEFAULTED_WRAPPER), "the census could not see the wrappers: " + carrierRegistries);
		assertEquals(new TreeSet<>(RegistryWrapperAccessInjector.WRAPPERS), nonPublic, "every carrier registry: " + carrierRegistries);
	}

	@Test void theStagedWrappersComeOutPublicAndOtherwiseUnchanged() {
		for (String wrapper : List.of(WRAPPER, DEFAULTED_WRAPPER)) {
			byte[] original = NativeCoremodParityTest.read(FORGE_CARRIER, wrapper);
			assertEquals(0, new ClassReader(original).getAccess() & Opcodes.ACC_PUBLIC, "premise: the carrier ships " + wrapper + " package-private");
			byte[] out = new RegistryWrapperAccessInjector().transform(wrapper.replace('/', '.'), original, null);
			assertEquals(new ClassReader(original).getAccess() | Opcodes.ACC_PUBLIC, new ClassReader(out).getAccess());
			assertEquals(original.length, out.length, "only the header's access changed");
			System.setProperty(RegistryWrapperAccessInjector.PROPERTY, "off");
			assertSame(original, new RegistryWrapperAccessInjector().transform(wrapper.replace('/', '.'), original, null));
			System.clearProperty(RegistryWrapperAccessInjector.PROPERTY);
		}
	}

	/**
	 * The crash: Meow Anti-Xray's lookup of a configured ore on the block registry the merged game hands it. And the same
	 * pattern on {@code getValue}, which the defaulted wrapper declares itself, so it needs that class public too.
	 */
	@Test void aModInvokesWhatItLookedUpOnTheRealBlockRegistrysClass() throws Exception {
		try (Game merged = new Game(Wrappers.AS_SHIPPED, null)) {
			IllegalAccessException refused = assertThrows(IllegalAccessException.class, () -> merged.lookUpAndInvoke("getOptional", "diamond_ore"),
					"control: as the carrier ships it, the lookup is refused");
			assertEquals("class " + Game.class.getName() + " cannot access a member of class net.minecraftforge.registries.NamespacedWrapper "
					+ "with modifiers \"public\"", refused.getMessage(), "the message Meow Anti-Xray's crash report carried");
			assertTrue(assertThrows(IllegalAccessException.class, () -> merged.lookUpAndInvoke("getValue", "diamond_ore")).getMessage()
					.contains("a member of class net.minecraftforge.registries.NamespacedDefaultedWrapper with modifiers \"public\""));
		}
		try (Game repaired = new Game(Wrappers.ACCESS_ONLY, null)) {
			assertEquals(Optional.of("diamond ore"), repaired.lookUpAndInvoke("getOptional", "diamond_ore"));
			assertEquals(Optional.empty(), repaired.lookUpAndInvoke("getOptional", "not_a_block"));
			assertEquals("diamond ore", repaired.lookUpAndInvoke("getValue", "diamond_ore"));
		}
	}

	/**
	 * The lookup on the wrappers as the game defines them, with fabric-api and without, the sync repair built by
	 * {@link RegistrySyncParityInjector#forGameLoader} as KernelBoot builds it. Without fabric-api there is no
	 * {@code RemapMode}, and {@code RegistrySyncParityInjector} used to add a public {@code remap} naming it whatever the
	 * game had: {@code getMethods()} and every {@code getMethod} that reaches {@code NamespacedWrapper} then threw
	 * {@code NoClassDefFoundError}, in every pack without fabric-api. That chain is kept here as the control, with the
	 * scope of the failure: a name the defaulted wrapper declares itself never reaches {@code NamespacedWrapper}.
	 */
	@Test void aModInvokesWhatItLookedUpOnTheWrappersTheGameDefinesWithAndWithoutFabricApi(@TempDir Path work) throws Exception {
		// The contract the kernel's mixin discovery reads off fabric-api's own registry-sync mixins, as at boot.
		Path sync = fabricRegistrySync(work);
		List<RegistrySyncParityInjector.RemapContract> contracts = noteRegistrySyncMixins(sync);
		assertEquals(1, contracts.size(), "premise: fabric-api's registry-sync mixins carry one remap contract: " + contracts);
		try {
			aModInvokesWhatItLookedUp(work, sync, contracts);
		} finally {
			RegistrySyncParityInjector.forgetMixins(SYNC_CONFIG);
		}
	}

	private void aModInvokesWhatItLookedUp(Path work, Path sync, List<RegistrySyncParityInjector.RemapContract> contracts) throws Exception {
		try (Game noFabricApi = new Game(Wrappers.bootChain(RegistrySyncParityInjector::forGameLoader), null)) {
			assertEquals(Optional.of("diamond ore"), noFabricApi.lookUpAndInvoke("getOptional", "diamond_ore"));
			assertEquals("diamond ore", noFabricApi.lookUpAndInvoke("getValue", "diamond_ore"));
			assertTrue(noFabricApi.declares(WRAPPER, "registerIdMapping"), "premise: the chain ran, and NeoForge's sync contract is there");
			assertFalse(noFabricApi.declares(WRAPPER, "remap"), "nothing calls fabric-api's remap without fabric-api");
			assertTrue(noFabricApi.publicMethodCount() > 50, "and getMethods lists the class");
			noFabricApi.lookUp("keySet");
		}
		try (Game remapAlways = new Game(Wrappers.bootChain(game -> new RegistrySyncParityInjector(type -> true, RemapContractSamples.lookup(contracts))), null)) {
			assertEquals("diamond ore", remapAlways.lookUpAndInvoke("getValue", "diamond_ore"),
					"control's scope: the defaulted wrapper declares getValue itself, so getMethod stops there and never "
							+ "resolves NamespacedWrapper's methods");
			NoClassDefFoundError broken = assertThrows(NoClassDefFoundError.class, () -> remapAlways.lookUpAndInvoke("getOptional", "diamond_ore"),
					"control: with remap added whatever the game has, a lookup that reaches NamespacedWrapper fails before any access check");
			assertEquals(REMAP_MODE, broken.getMessage());
			assertEquals(REMAP_MODE, assertThrows(NoClassDefFoundError.class, () -> remapAlways.lookUp("keySet")).getMessage());
			assertEquals(REMAP_MODE, assertThrows(NoClassDefFoundError.class, remapAlways::publicMethodCount).getMessage(),
					"getMethods reaches every class");
		}
		try (Game withFabricApi = new Game(Wrappers.bootChain(RegistrySyncParityInjector::forGameLoader), sync)) {
			assertEquals(Optional.of("diamond ore"), withFabricApi.lookUpAndInvoke("getOptional", "diamond_ore"));
			assertEquals("diamond ore", withFabricApi.lookUpAndInvoke("getValue", "diamond_ore"));
			assertTrue(withFabricApi.declares(WRAPPER, "remap"), "with fabric-api the wrapper keeps fabric-api's remap, which its sync calls");
		}
	}

	/**
	 * {@link #WRAPPER_REPAIRS} is every transformer whose code names a wrapper class, each constructed once by KernelBoot,
	 * in the order KernelBoot constructs them, and KernelBoot gets the sync repair from
	 * {@link RegistrySyncParityInjector#forGameLoader}, the factory the chain above calls. So the chain above runs the
	 * repairs, their order and the fabric-api decision the game runs on the wrappers, with the test's game loader in
	 * place of KernelBoot's.
	 */
	@Test void theWrapperRepairsAreEveryTransformerThatNamesAWrapperInKernelBootsOrder() throws Exception {
		Set<String> wrapperNames = Set.of(WRAPPER, DEFAULTED_WRAPPER, WRAPPER.replace('/', '.'), DEFAULTED_WRAPPER.replace('/', '.'));
		Set<String> naming = new TreeSet<>();
		for (var entry : InjectorExecutionCensusTest.classesBeside(ClassTransformer.class).entrySet()) {
			String outer = entry.getKey().contains("$") ? entry.getKey().substring(0, entry.getKey().indexOf('$')) : entry.getKey();
			if (!namesAnyOf(entry.getValue(), wrapperNames)) continue;
			Class<?> type = Class.forName(outer.replace('/', '.'), false, getClass().getClassLoader());
			if (ClassTransformer.class.isAssignableFrom(type) && !type.isInterface()) naming.add(type.getName());
		}
		assertEquals(new TreeSet<>(WRAPPER_REPAIRS), naming, "the transformers whose code names a wrapper class");

		ClassNode boot = new ClassNode();
		try (InputStream in = KernelBoot.class.getResourceAsStream("KernelBoot.class")) {
			new ClassReader(in.readAllBytes()).accept(boot, ClassReader.SKIP_DEBUG);
		}
		String sync = Type.getInternalName(RegistrySyncParityInjector.class);
		String factory = Type.getMethodDescriptor(RegistrySyncParityInjector.class.getMethod("forGameLoader", ClassLoader.class));
		List<String> constructed = new ArrayList<>();
		List<String> syncBuiltWithoutTheFactory = new ArrayList<>();
		for (MethodNode method : boot.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && WRAPPER_REPAIRS.contains(type.desc.replace('/', '.'))) {
					constructed.add(type.desc.replace('/', '.'));
					if (type.desc.equals(sync)) syncBuiltWithoutTheFactory.add(method.name);
				} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(sync)
						&& call.name.equals("forGameLoader") && call.desc.equals(factory)) {
					constructed.add(RegistrySyncParityInjector.class.getName());
				}
			}
		}
		assertEquals(List.of(), syncBuiltWithoutTheFactory, "KernelBoot builds RegistrySyncParityInjector only through forGameLoader, "
				+ "so its fabric-api decision is the one the chain test runs");
		assertEquals(WRAPPER_REPAIRS, constructed, "KernelBoot constructs each wrapper repair once, in this order");
	}

	/** Whether {@code classBytes} holds one of {@code names} as a string constant: an ldc or a constant field. */
	private static boolean namesAnyOf(byte[] classBytes, Set<String> names) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		for (FieldNode field : node.fields) if (field.value instanceof String value && names.contains(value)) return true;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof String value && names.contains(value)
						|| ldc.cst instanceof Type t && t.getSort() == Type.OBJECT && names.contains(t.getInternalName()))) return true;
			}
		}
		return false;
	}

	private static final String SYNC_CONFIG = "fabric-registry-sync-v0.mixins.json";

	/** Notes every mixin class in {@code jar} with the parity injector, as the kernel's discovery does; what it read. */
	private static List<RegistrySyncParityInjector.RemapContract> noteRegistrySyncMixins(Path jar) throws IOException {
		java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				if (entry.isDirectory()) continue;
				try (InputStream in = zip.getInputStream(entry)) { entries.put(entry.getName(), in.readAllBytes()); }
			}
		}
		List<RegistrySyncParityInjector.RemapContract> found = new java.util.ArrayList<>();
		RegistrySyncParityInjector.forgetMixins(SYNC_CONFIG);
		for (var entry : entries.entrySet()) {
			if (!entry.getKey().endsWith(".class")) continue;
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(entry.getValue()).accept(node, 0);
			if (net.forbric.kernel.mixin.MixinFit.mixinTargets(node).isEmpty()) continue;
			found.addAll(RegistrySyncParityInjector.remapContracts(node, entries::get));
			RegistrySyncParityInjector.noteMixin(SYNC_CONFIG, node, entries::get);
		}
		return found;
	}

	/** fabric-api's registry-sync module, the one jar of the pinned fabric-api that declares {@code RemapMode}. */
	private static Path fabricRegistrySync(Path work) throws IOException {
		Path api = TestFixtures.fabricApi();
		TestFixtures.requireFile(Fixture.STAGED, api, "the pinned Fabric API " + api + " absent");
		try (ZipFile zip = new ZipFile(api.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-registry-sync-v0-")).findFirst()
					.orElseThrow(() -> new AssertionError(api + " nests no fabric-registry-sync-v0"));
			Path jar = work.resolve("fabric-registry-sync-v0.jar");
			try (InputStream in = zip.getInputStream(nested)) {
				Files.write(jar, in.readAllBytes());
			}
			try (ZipFile sync = new ZipFile(jar.toFile())) {
				assertNotNull(sync.getEntry(REMAP_MODE + ".class"), "premise: the registry-sync module declares RemapMode");
			}
			return jar;
		}
	}

	/** How the wrappers are defined in a {@link Game}: from the carrier's bytes, with the game's loader to ask. */
	private interface Wrappers {
		byte[] define(String binaryName, byte[] carrier, ClassLoader game);

		Wrappers AS_SHIPPED = (name, carrier, game) -> carrier;
		Wrappers ACCESS_ONLY = (name, carrier, game) -> new RegistryWrapperAccessInjector().transform(name, carrier, null);

		/** Through {@link #WRAPPER_REPAIRS}, registered as KernelBoot registers them; {@code syncRepair} builds the sync repair for the game's loader. */
		static Wrappers bootChain(Function<ClassLoader, RegistrySyncParityInjector> syncRepair) {
			return (name, carrier, game) -> {
				TransformChain chain = new TransformChain();
				for (String repair : WRAPPER_REPAIRS) {
					try {
						chain.register(TransformPhase.COREMOD, repair.equals(RegistrySyncParityInjector.class.getName())
								? syncRepair.apply(game)
								: (ClassTransformer) Class.forName(repair).getDeclaredConstructor().newInstance());
					} catch (ReflectiveOperationException e) {
						throw new AssertionError(repair + " has no no-argument constructor; build it here as KernelBoot does", e);
					}
				}
				return chain.applyBeforeMixin(name, carrier, new TransformContext(EnvType.SERVER, false, "named"));
			};
		}
	}

	/**
	 * The merged game, both carriers and the libraries they link against, with the real wrappers and a fixture
	 * ForgeRegistry; and fabric-api's registry-sync module when {@code fabricRegistrySync} is not null. The kernel's own
	 * classes come from the kernel, as on a boot, for the hooks the wrapper repairs call.
	 */
	private static final class Game implements AutoCloseable {
		private final URLClassLoader loader;
		private final Object blocks;

		Game(Wrappers wrappers, Path fabricRegistrySync) throws Exception {
			TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the merged game is class-file 69, which only Java 25 links");
			for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			Map<String, byte[]> carrier = new HashMap<>();
			for (String wrapper : List.of(WRAPPER, DEFAULTED_WRAPPER)) carrier.put(wrapper.replace('/', '.'), NativeCoremodParityTest.read(FORGE_CARRIER, wrapper));
			byte[] fixture = fixtureRegistry();
			List<URL> urls = new ArrayList<>(List.of(MERGED.toUri().toURL(), NEO_CARRIER.toUri().toURL(), FORGE_CARRIER.toUri().toURL()));
			if (fabricRegistrySync != null) urls.add(fabricRegistrySync.toUri().toURL());
			for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier",
					"com/google/guava/guava", "it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
					"io/netty/netty-common", "io/netty/netty-buffer", "org/joml/joml", "com/mojang/authlib",
					"org/apache/commons/commons-lang3", "com/mojang/logging")) {
				Path library = newestUnder(pattern);
				TestFixtures.require(Fixture.MC_LIBRARIES, library != null, "no " + pattern + " jar in the local Minecraft libraries");
				urls.add(library.toUri().toURL());
			}
			// Identifier's codecs name Netty's; they moved between releases (netty-codec, then netty-codec-base).
			for (String pattern : List.of("io/netty/netty-codec-base", "io/netty/netty-codec/", "io/netty/netty-transport/", "io/netty/netty-handler")) {
				Path library = newestUnder(pattern);
				if (library != null) urls.add(library.toUri().toURL());
			}
			ClassLoader kernel = RegistryWrapperAccessInjectorTest.class.getClassLoader();
			loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
				@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					return name.startsWith("net.forbric.") ? kernel.loadClass(name) : super.loadClass(name, resolve);
				}

				@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = carrier.containsKey(name) ? wrappers.define(name, carrier.get(name), this)
							: name.equals(FIXTURE_REGISTRY.replace('/', '.')) ? fixture : null;
					return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.findClass(name);
				}
			};

			Class<?> wrapper = type(WRAPPER);
			blocks = unsafe().allocateInstance(type(DEFAULTED_WRAPPER));
			Object forgeRegistry = unsafe().allocateInstance(type(FIXTURE_REGISTRY));
			Map<Object, Object> values = new HashMap<>();
			values.put(id("minecraft", "diamond_ore"), "diamond ore");
			field(type(FIXTURE_REGISTRY), "values").set(forgeRegistry, values);
			field(wrapper, "delegate").set(blocks, forgeRegistry);
			field(type(DEFAULTED_WRAPPER), "delegate").set(blocks, forgeRegistry);
		}

		/** {@code BLOCK.getClass().getMethod(name, Identifier.class).invoke(BLOCK, id)}, its reflective failure as is. */
		Object lookUpAndInvoke(String name, String path) throws Exception {
			Method method = blocks.getClass().getMethod(name, type(IDENTIFIER));
			return method.invoke(blocks, id("minecraft", path));
		}

		/** {@code BLOCK.getClass().getMethod(name)}, its failure as is. */
		Method lookUp(String name) throws Exception {
			return blocks.getClass().getMethod(name);
		}

		/** Whether the wrapper {@code internalName}, as defined here, declares a method {@code name}. */
		boolean declares(String internalName, String name) throws Exception {
			for (Method method : type(internalName).getDeclaredMethods()) if (method.getName().equals(name)) return true;
			return false;
		}

		int publicMethodCount() {
			return blocks.getClass().getMethods().length;
		}

		private Object id(String namespace, String path) throws Exception {
			return type(IDENTIFIER).getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
		}

		private Class<?> type(String internalName) throws ClassNotFoundException {
			return Class.forName(internalName.replace('/', '.'), false, loader);
		}

		@Override public void close() throws IOException { loader.close(); }
	}

	/**
	 * A ForgeRegistry whose {@code getRaw} and {@code getValue} read a map: what the wrappers' {@code getOptional} and the
	 * defaulted wrapper's {@code getValue} call. Same runtime package as Forge's, so they override the real ones.
	 */
	private static byte[] fixtureRegistry() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FIXTURE_REGISTRY, null, FORGE_REGISTRY, null);
		cw.visitField(Opcodes.ACC_PUBLIC, "values", "Ljava/util/Map;", null, null).visitEnd();
		for (String name : List.of("getRaw", "getValue")) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "(L" + IDENTIFIER + ";)Ljava/lang/Object;", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitFieldInsn(Opcodes.GETFIELD, FIXTURE_REGISTRY, "values", "Ljava/util/Map;");
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private record Header(int access, String superName, String[] interfaces) {
		boolean isPublic() { return (access & Opcodes.ACC_PUBLIC) != 0; }
	}

	/** Every class in {@code jar}, first definition wins as on a class path; multi-release overlays are not classes. */
	private static void headers(Path jar, Map<String, Header> into) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					ClassReader reader = new ClassReader(in.readAllBytes());
					into.putIfAbsent(reader.getClassName(), new Header(reader.getAccess(), reader.getSuperName(), reader.getInterfaces()));
				}
			}
		}
	}

	private static boolean implementsRegistry(String name, Map<String, Header> classes) {
		if (name == null) return false;
		if (name.equals(REGISTRY)) return true;
		Header header = classes.get(name);
		if (header == null) return false; // a JDK or library type
		if (implementsRegistry(header.superName(), classes)) return true;
		for (String parent : header.interfaces()) if (implementsRegistry(parent, classes)) return true;
		return false;
	}

	private static Field field(Class<?> owner, String name) throws Exception {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (sun.misc.Unsafe) f.get(null);
	}

	private static Path newestUnder(String pattern) throws IOException {
		Path under = TestFixtures.minecraftDir().resolve("libraries").resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources") && !f.toString().contains("natives"))
					.sorted(Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
