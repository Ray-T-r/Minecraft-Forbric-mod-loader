/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * A MinecraftForge-wrapped registry fires fabric-registry-sync's {@code RegistryEntryAddedCallback}: on the real wrapper,
 * and in a real JVM, where the repaired {@code register} is linked — and so verified — against the merged game and both
 * carriers, then used by the real fabric-menu-api to record a menu type registered after fabric-menu-api walked the
 * registry, as Farmer's Delight's cooking pot is. The one stand-in is what fabric-registry-sync's
 * {@code MappedRegistryMixin} adds to {@code MappedRegistry} — the add-object event and its getter; the event, its
 * factory, the listener and the codec map are fabric-api's own. The wrapper and its {@code ForgeRegistry} are allocated
 * bare, MenuType's initializer (every vanilla menu, which needs a bootstrapped game) is left out, and the menu type is an
 * {@code ExtendedMenuType} with only its stream codec set: {@code register} and fabric-menu-api read nothing else.
 */
@ResourceLock("system-properties")
class WrapperEntryAddedInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_CARRIER = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final Path FABRIC_API = TestFixtures.fabricApi();
	private static final String WRAPPER = "net/minecraftforge/registries/NamespacedWrapper";
	private static final String FORGE_REGISTRY = "net/minecraftforge/registries/ForgeRegistry";
	private static final String FIXTURE_REGISTRY = "net/minecraftforge/registries/FixtureForgeRegistry";
	private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
	private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
	private static final String MENU_TYPE = "net/minecraft/world/inventory/MenuType";
	private static final String EVENT = "net/fabricmc/fabric/api/event/Event";
	private static final String LISTENABLE = "net/fabricmc/fabric/impl/registry/sync/ListenableRegistry";
	private static final String CALLBACK = "net/fabricmc/fabric/api/event/registry/RegistryEntryAddedCallback";
	private static final String NETWORKING = "net/fabricmc/fabric/impl/menu/Networking";
	private static final String REGISTER_DESC = WrapperEntryAddedInjector.REGISTER_DESC;

	@AfterEach void reset() { System.clearProperty(WrapperEntryAddedInjector.PROPERTY); }

	@Test void theWrappersRegisterFiresTheEntryEventBeforeItReturns() throws Exception {
		byte[] wrapper = NativeCoremodParityTest.read(FORGE_CARRIER, WRAPPER);
		MethodNode original = method(node(wrapper), "register", REGISTER_DESC);
		assertTrue(calls(original, FORGE_REGISTRY, "add"), "premise: the wrapper registers into its ForgeRegistry");
		assertFalse(calls(original, MAPPED_REGISTRY, "register"),
				"premise: and never reaches MappedRegistry.register, at whose return fabric-registry-sync fires the event");
		assertTrue(node(NativeCoremodParityTest.read(FORGE_CARRIER, "net/minecraftforge/registries/NamespacedDefaultedWrapper"))
				.methods.stream().noneMatch(m -> m.name.equals("register")), "premise: block, item and the other defaulted wrappers inherit it");

		byte[] out = new WrapperEntryAddedInjector().transform(WrapperEntryAddedInjector.WRAPPER, wrapper, null);
		MethodNode register = method(node(out), "register", REGISTER_DESC);
		List<AbstractInsnNode> code = real(register);
		int returns = 0;
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i).getOpcode() != Opcodes.ARETURN) continue;
			returns++;
			assertTrue(code.get(i - 1) instanceof MethodInsnNode hook && hook.getOpcode() == Opcodes.INVOKESTATIC
					&& hook.owner.equals(WrapperEntryAddedInjector.HOOK_OWNER) && hook.name.equals(WrapperEntryAddedInjector.HOOK)
					&& hook.desc.equals(WrapperEntryAddedInjector.HOOK_DESC), "the event fires right before the holder is returned");
			assertEquals(List.of(0, 1, 2), code.subList(i - 4, i - 1).stream().map(load -> ((VarInsnNode) load).var).toList(),
					"handed the registry, the key and the entry");
		}
		assertEquals(real(original).stream().filter(i -> i.getOpcode() == Opcodes.ARETURN).count(), returns);
		assertTrue(returns > 0);
		new Analyzer<>(new BasicVerifier()).analyze(WRAPPER, register);
		assertSame(out, new WrapperEntryAddedInjector().transform(WrapperEntryAddedInjector.WRAPPER, out, null));
	}

	@Test void theSwitchLeavesTheWrapperAlone() throws Exception {
		System.setProperty(WrapperEntryAddedInjector.PROPERTY, "off");
		byte[] wrapper = NativeCoremodParityTest.read(FORGE_CARRIER, WRAPPER);
		assertSame(wrapper, new WrapperEntryAddedInjector().transform(WrapperEntryAddedInjector.WRAPPER, wrapper, null));
	}

	@Test void aPlainRegistryIsLeftAlone() throws Exception {
		byte[] plain = NativeCoremodParityTest.read(MERGED, MAPPED_REGISTRY);
		assertSame(plain, new WrapperEntryAddedInjector().transform(dotted(MAPPED_REGISTRY), plain, null));
	}

	/** The issue: fabric-menu-api walked the menu registry before Farmer's Delight registered its cooking pot. */
	@Test void aMenuRegisteredAfterFabricMenuApiWalkedTheRegistryGetsItsCodec() throws Exception {
		try (Game merged = new Game(false, true)) {
			Object early = merged.menuType(), pot = merged.menuType();
			merged.register("farmersdelight", "early", early);
			merged.walkAsFabricMenuApiDoes();
			merged.register("farmersdelight", "cooking_pot", pot);
			assertSame(merged.streamCodec(early), merged.codecs().get(merged.id("farmersdelight", "early")),
					"premise: the walk records what the wrapped registry already holds");
			assertNull(merged.codecs().get(merged.id("farmersdelight", "cooking_pot")),
					"control: as merged, the pot is never recorded — \"Codec for farmersdelight:cooking_pot is not registered!\"");
		}
		try (Game repaired = new Game(true, true)) {
			Object early = repaired.menuType(), pot = repaired.menuType();
			repaired.register("farmersdelight", "early", early);
			repaired.walkAsFabricMenuApiDoes();
			List<Object[]> heard = repaired.listen();
			repaired.register("farmersdelight", "cooking_pot", pot);
			assertSame(repaired.streamCodec(pot), repaired.codecs().get(repaired.id("farmersdelight", "cooking_pot")));
			assertEquals(1, heard.size());
			assertArrayEquals(new Object[] {1, repaired.id("farmersdelight", "cooking_pot"), pot}, heard.getFirst(),
					"with the raw id the wrapper's ForgeRegistry assigned, as Fabric passes MappedRegistry's");
		}
	}

	/** fabric-registry-sync refuses an id that is already an alias by throwing from its listener; that is the registration's. */
	@Test void aListenersExceptionIsTheRegistrations() throws Exception {
		try (Game repaired = new Game(true, true)) {
			repaired.listen(new IllegalArgumentException("Tried registering farmersdelight:pot, but it is already an alias"));
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
					() -> repaired.register("farmersdelight", "pot", repaired.menuType()));
			assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
			assertTrue(thrown.getCause().getMessage().contains("already an alias"));
		}
	}

	/** A pack without fabric-api: MappedRegistry has no event, and the wrapper registers exactly as merged. */
	@Test void withoutFabricApiTheWrapperRegistersAsBefore() throws Exception {
		try (Game repaired = new Game(true, false)) {
			Object thing = new Object();
			assertNotNull(repaired.register("examplemod", "thing", thing));
			assertEquals(List.of(thing), repaired.entries.values);
		}
	}

	/** What the fixture ForgeRegistry stores: entries in id order. Public, for the game-side fixture class to call. */
	public static final class Entries {
		final List<Object> ids = new ArrayList<>();
		final List<Object> values = new ArrayList<>();

		public int add(Object id, Object value) {
			ids.add(id);
			values.add(value);
			return values.size() - 1;
		}

		public Iterator<Object> iterator() { return values.iterator(); }

		public int id(Object value) { return values.indexOf(value); }

		public Object key(Object value) {
			int at = values.indexOf(value);
			return at < 0 ? null : ids.get(at);
		}
	}

	/** The merged game, both carriers, fabric-api's registry, event and menu modules, and the libraries they link against. */
	private static final class Game implements AutoCloseable {
		private final URLClassLoader loader;
		private final Path modules;
		private final Object wrapper;
		final Entries entries = new Entries();

		Game(boolean repaired, boolean fabric) throws Exception {
			TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the merged game is class-file 69, which only Java 25 links");
			for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			Map<String, byte[]> defined = new HashMap<>();
			byte[] wrapperBytes = NativeCoremodParityTest.read(FORGE_CARRIER, WRAPPER);
			defined.put(dotted(WRAPPER), repaired ? new WrapperEntryAddedInjector().transform(WrapperEntryAddedInjector.WRAPPER, wrapperBytes, null) : wrapperBytes);
			defined.put(dotted(FIXTURE_REGISTRY), fixtureRegistry());
			defined.put(dotted(MENU_TYPE), withoutInitializer(NativeCoremodParityTest.read(MERGED, MENU_TYPE)));
			List<URL> urls = new ArrayList<>(List.of(MERGED.toUri().toURL(), NEO_CARRIER.toUri().toURL(), FORGE_CARRIER.toUri().toURL()));
			if (fabric) {
				TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(FABRIC_API), "actual Fabric API fixture required");
				defined.put(dotted(MAPPED_REGISTRY), listenable(NativeCoremodParityTest.read(MERGED, MAPPED_REGISTRY)));
				modules = Files.createTempDirectory("wrapper-entry-events");
				for (String module : List.of("fabric-api-base", "fabric-registry-sync-v0", "fabric-networking-api-v1", "fabric-menu-api-v1")) {
					urls.add(extract(module).toUri().toURL());
				}
			} else {
				modules = null;
			}
			for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier",
					"com/google/guava/guava", "it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
					"io/netty/netty-common", "io/netty/netty-buffer", "org/joml/joml", "com/mojang/authlib",
					"org/apache/commons/commons-lang3", "com/mojang/logging")) {
				Path library = newestUnder(pattern);
				TestFixtures.require(Fixture.MC_LIBRARIES, library != null, "no " + pattern + " jar in the local Minecraft libraries");
				urls.add(library.toUri().toURL());
			}
			// Identifier's codecs name Netty's; they moved between releases (netty-codec, then netty-codec-base).
			for (String pattern : List.of("io/netty/netty-codec-base", "io/netty/netty-codec/", "io/netty/netty-transport/",
					"io/netty/netty-handler")) {
				Path library = newestUnder(pattern);
				if (library != null) urls.add(library.toUri().toURL());
			}
			ClassLoader kernel = WrapperEntryAddedInjectorTest.class.getClassLoader();
			loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
				@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = defined.get(name);
					if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
					// The kernel's boot side is the game's parent, as under ForbricClassLoader: the repaired register calls it,
					// and it carries Fabric Loader's API, which fabric-menu-api's ModInitializer is.
					boolean boot = name.startsWith("net.forbric.") || name.startsWith("net.fabricmc.api.") || name.startsWith("net.fabricmc.loader.");
					return boot ? kernel.loadClass(name) : super.findClass(name);
				}
			};

			Class<?> wrapperClass = type(WRAPPER);
			wrapper = unsafe().allocateInstance(wrapperClass);
			Object menu = type("net/minecraft/resources/ResourceKey").getMethod("createRegistryKey", type(IDENTIFIER))
					.invoke(null, id("minecraft", "menu"));
			field(type(MAPPED_REGISTRY), "key").set(wrapper, menu);
			field(wrapperClass, "registrationInfos").set(wrapper, new IdentityHashMap<>());
			field(wrapperClass, "registryLifecycle").set(wrapper, type("com/mojang/serialization/Lifecycle").getMethod("stable").invoke(null));
			field(wrapperClass, "holdersByName").set(wrapper, new HashMap<>());
			Object delegate = unsafe().allocateInstance(type(FIXTURE_REGISTRY));
			field(type(FIXTURE_REGISTRY), "entries").set(delegate, entries);
			field(wrapperClass, "delegate").set(wrapper, delegate);
			if (fabric) field(type(MAPPED_REGISTRY), "fabric_addObjectEvent").set(wrapper, addObjectEvent());
		}

		/** The wrapper's own register(key, value, info), as Registry.register calls it for a Fabric mod's entry. */
		Object register(String namespace, String path, Object value) throws Exception {
			Class<?> resourceKey = type("net/minecraft/resources/ResourceKey");
			Object key = resourceKey.getMethod("create", resourceKey, type(IDENTIFIER))
					.invoke(null, field(type(MAPPED_REGISTRY), "key").get(wrapper), id(namespace, path));
			Object builtIn = type("net/minecraft/core/RegistrationInfo").getField("BUILT_IN").get(null);
			Method register = type(WRAPPER).getDeclaredMethod("register", resourceKey, Object.class, type("net/minecraft/core/RegistrationInfo"));
			register.setAccessible(true);
			return register.invoke(wrapper, key, value, builtIn);
		}

		/** fabric-menu-api's main entrypoint: walk the menu registry, then listen, recording each ExtendedMenuType's codec. */
		void walkAsFabricMenuApiDoes() throws Exception {
			Class<?> networking = type(NETWORKING);
			// The recorder is onInitialize's own lambda; the walk-then-listen is forEachEntry, which it hands that lambda to.
			Method record = networking.getDeclaredMethod("lambda$onInitialize$0", type(MENU_TYPE), type(IDENTIFIER));
			record.setAccessible(true);
			Method forEachEntry = networking.getDeclaredMethod("forEachEntry", type("net/minecraft/core/Registry"), BiConsumer.class);
			forEachEntry.setAccessible(true);
			BiConsumer<Object, Object> recorder = (menuType, id) -> {
				try {
					record.invoke(null, menuType, id);
				} catch (ReflectiveOperationException e) {
					throw new AssertionError(e);
				}
			};
			forEachEntry.invoke(null, wrapper, recorder);
		}

		Map<?, ?> codecs() throws Exception { return (Map<?, ?>) type(NETWORKING).getField("CODEC_BY_ID").get(null); }

		/** A listener on the registry's add-object event: records what it hears, or throws {@code failure}. */
		List<Object[]> listen(RuntimeException... failure) throws Exception {
			List<Object[]> heard = new ArrayList<>();
			Object listener = Proxy.newProxyInstance(loader, new Class<?>[] {type(CALLBACK)}, (proxy, method, args) -> {
				if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
				if (failure.length > 0) throw failure[0];
				heard.add(args);
				return null;
			});
			type(EVENT).getMethod("register", Object.class).invoke(field(type(MAPPED_REGISTRY), "fabric_addObjectEvent").get(wrapper), listener);
			return heard;
		}

		/** A Fabric mod's ExtendedMenuType with its own stream codec, like Farmer's Delight's (CookingPotMenu, BlockPos). */
		Object menuType() throws Exception {
			Class<?> extended = type("net/fabricmc/fabric/api/menu/v1/ExtendedMenuType");
			Object menuType = unsafe().allocateInstance(extended);
			Object codec = Proxy.newProxyInstance(loader, new Class<?>[] {type("net/minecraft/network/codec/StreamCodec")},
					(proxy, method, args) -> method.getDeclaringClass() == Object.class ? objectMethod(proxy, method, args) : null);
			field(extended, "streamCodec").set(menuType, codec);
			return menuType;
		}

		Object streamCodec(Object menuType) throws Exception {
			return field(type("net/fabricmc/fabric/api/menu/v1/ExtendedMenuType"), "streamCodec").get(menuType);
		}

		Object id(String namespace, String path) throws Exception {
			return type(IDENTIFIER).getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
		}

		/**
		 * What fabric-registry-sync's MappedRegistryMixin builds in every registry's constructor: fabric-api's array-backed
		 * event, whose invoker hands each entry to every listener in turn.
		 */
		private Object addObjectEvent() throws Exception {
			Class<?> callback = type(CALLBACK);
			Method onEntryAdded = callback.getMethod("onEntryAdded", int.class, type(IDENTIFIER), Object.class);
			Function<Object[], Object> invokerFactory = listeners -> Proxy.newProxyInstance(loader, new Class<?>[] {callback}, (proxy, method, args) -> {
				if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
				for (Object listener : listeners) {
					try {
						onEntryAdded.invoke(listener, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				}
				return null;
			});
			return type("net/fabricmc/fabric/api/event/EventFactory").getMethod("createArrayBacked", Class.class, Function.class)
					.invoke(null, callback, invokerFactory);
		}

		private Path extract(String module) throws Exception {
			try (ZipFile api = new ZipFile(FABRIC_API.toFile())) {
				ZipEntry nested = api.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElseThrow();
				Path out = modules.resolve(module + ".jar");
				Files.copy(api.getInputStream(nested), out);
				return out;
			}
		}

		private Class<?> type(String internalName) throws ClassNotFoundException {
			return Class.forName(dotted(internalName), false, loader);
		}

		@Override public void close() throws Exception {
			loader.close();
			if (modules != null) {
				try (var files = Files.walk(modules)) {
					for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
				}
			}
		}
	}

	/**
	 * A ForgeRegistry that keeps its entries in an {@link Entries}: add, iterator, getID and getKey, the four the wrapper's
	 * register, iterator, getId and getKey hand to their delegate. Same runtime package, so its add overrides Forge's.
	 */
	private static byte[] fixtureRegistry() {
		String entries = Entries.class.getName().replace('.', '/');
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FIXTURE_REGISTRY, null, FORGE_REGISTRY, null);
		cw.visitField(Opcodes.ACC_PUBLIC, "entries", "L" + entries + ";", null, null).visitEnd();
		forward(cw, 0, "add", "(IL" + IDENTIFIER + ";Ljava/lang/Object;)I", new int[] {Opcodes.ALOAD, 2, Opcodes.ALOAD, 3},
				entries, "add", "(Ljava/lang/Object;Ljava/lang/Object;)I", null, Opcodes.IRETURN);
		forward(cw, Opcodes.ACC_PUBLIC, "iterator", "()Ljava/util/Iterator;", new int[0],
				entries, "iterator", "()Ljava/util/Iterator;", null, Opcodes.ARETURN);
		forward(cw, Opcodes.ACC_PUBLIC, "getID", "(Ljava/lang/Object;)I", new int[] {Opcodes.ALOAD, 1},
				entries, "id", "(Ljava/lang/Object;)I", null, Opcodes.IRETURN);
		forward(cw, Opcodes.ACC_PUBLIC, "getKey", "(Ljava/lang/Object;)L" + IDENTIFIER + ";", new int[] {Opcodes.ALOAD, 1},
				entries, "key", "(Ljava/lang/Object;)Ljava/lang/Object;", IDENTIFIER, Opcodes.ARETURN);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code access name desc { return [(cast)] this.entries.target(loads...); }} */
	private static void forward(ClassWriter cw, int access, String name, String desc, int[] loads, String owner, String target,
			String targetDesc, String cast, int returnOpcode) {
		MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, FIXTURE_REGISTRY, "entries", "L" + owner + ";");
		for (int i = 0; i < loads.length; i += 2) mv.visitVarInsn(loads[i], loads[i + 1]);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, target, targetDesc, false);
		if (cast != null) mv.visitTypeInsn(Opcodes.CHECKCAST, cast);
		mv.visitInsn(returnOpcode);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** The merged MappedRegistry with the add-object event fabric-registry-sync's MappedRegistryMixin gives it. */
	private static byte[] listenable(byte[] mappedRegistry) {
		ClassNode node = node(mappedRegistry);
		node.interfaces.add(LISTENABLE);
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "fabric_addObjectEvent", "L" + EVENT + ";", null, null));
		MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC, "fabric_getAddObjectEvent", "()L" + EVENT + ";", null, null);
		get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MAPPED_REGISTRY, "fabric_addObjectEvent", "L" + EVENT + ";"));
		get.instructions.add(new InsnNode(Opcodes.ARETURN));
		node.methods.add(get);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static Object objectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			default -> "proxy";
		};
	}

	private static byte[] withoutInitializer(byte[] bytes) {
		ClassNode node = node(bytes);
		node.methods.removeIf(m -> m.name.equals("<clinit>"));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}

	private static String dotted(String internalName) {
		return internalName.replace('/', '.');
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

	private static Path newestUnder(String pattern) throws java.io.IOException {
		Path root = TestFixtures.minecraftDir().resolve("libraries");
		Path under = root.resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources") && !f.toString().contains("natives"))
					.sorted(Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}

}
