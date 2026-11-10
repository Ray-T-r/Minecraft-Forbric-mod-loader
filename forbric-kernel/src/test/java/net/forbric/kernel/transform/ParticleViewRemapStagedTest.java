/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Lane K2's defect, on the real classes: fabric-registry-sync 7.1.0's {@code Int2ObjectMapTracker} (out of the pinned
 * fabric-api) driven against the kernel's particle provider view ({@code KernelParticleProviders}) and, as the native
 * Fabric arm, against a plain {@code Int2ObjectOpenHashMap}, through a registry whose ids are remapped the way
 * {@code MappedRegistry.remap} remaps them: the id table is rebuilt first, then {@code RegistryIdRemapCallback} fires
 * with a change map holding every entry's old id → new id.
 *
 * <p>The registry is a proxy for {@code Registry} + fabric's {@code ListenableRegistry}, its two events fabric-api's own
 * {@code EventFactory} array-backed events, so {@code register} attaches the tracker exactly as it does in the game. The
 * view runs over a name → id table the test remaps, the same functions the game binds to the particle registry.
 */
@ExecutesInjector(ParticleViewTrackerInjector.class)
class ParticleViewRemapStagedTest {
	private static final String TRACKER = ParticleViewTrackerInjector.TRACKER.replace('/', '.');
	private static final String NAME = "ParticleEngine.providers";
	private static final List<String> TYPES = List.of("minecraft:flame", "balm:a", "balm:b", "sophisticatedcore:c");
	/** The server's order: every modded id moves, flame keeps 0. */
	private static final List<String> REMAPPED = List.of("minecraft:flame", "sophisticatedcore:c", "balm:a", "balm:b");

	/** fabric-registry-sync's mixin into MappedRegistry, whose remap(Object2IntMap, RemapMode) is the order in question. */
	private static final String REGISTRY_MIXIN = "net/fabricmc/fabric/mixin/registry/sync/MappedRegistryMixin";
	private static final String REMAP_EVENT = "fabric_getRemapEvent";

	private static Path nested;
	private static byte[] trackerBytes;
	private static byte[] registryMixinBytes;

	@BeforeAll static void extractFabricModules() throws IOException {
		Path api = TestFixtures.fabricApi();
		TestFixtures.requireFiles(Fixture.STAGED, "the pinned fabric-api", api);
		nested = Files.createTempDirectory("k2-fapi");
		try (ZipFile outer = new ZipFile(api.toFile())) {
			for (String prefix : List.of("fabric-api-base-", "fabric-registry-sync-v0-")) {
				ZipEntry entry = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + prefix)).findFirst()
						.orElseThrow(() -> new AssertionError(api + " nests no " + prefix + "*.jar"));
				Path out = nested.resolve(prefix + "module.jar");
				try (var in = outer.getInputStream(entry)) {
					Files.copy(in, out);
				}
				if (prefix.startsWith("fabric-registry-sync")) {
					try (ZipInputStream inner = new ZipInputStream(Files.newInputStream(out))) {
						for (ZipEntry e = inner.getNextEntry(); e != null; e = inner.getNextEntry()) {
							if (e.getName().equals(ParticleViewTrackerInjector.TRACKER + ".class")) trackerBytes = inner.readAllBytes();
							if (e.getName().equals(REGISTRY_MIXIN + ".class")) registryMixinBytes = inner.readAllBytes();
						}
					}
				}
			}
		}
		assertNotNull(trackerBytes, "fabric-registry-sync nests " + TRACKER);
		assertNotNull(registryMixinBytes, "fabric-registry-sync nests " + REGISTRY_MIXIN);
	}

	@AfterAll static void cleanUp() throws IOException {
		if (nested == null) return;
		try (var walk = Files.walk(nested)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}

	/** The native Fabric arm, through the rewritten tracker: a raw-id map is still tracked and re-keyed. */
	@Test void aRawIdMapIsStillReKeyedOnRemapAsOnNativeFabric() throws Exception {
		try (World world = world(detached())) {
			Map<Integer, Object> raw = world.rawMap();
			for (String type : TYPES) raw.put(world.id(type), world.provider(type));
			world.register(raw);
			world.remap(REMAPPED);
			for (String type : TYPES) {
				assertSame(world.provider(type), raw.get(world.id(type)), type + " answers under the id the remap gave it");
			}
			assertEquals(TYPES.size(), raw.size());
		}
	}

	/** The defect as found: fabric-registry-sync's tracker, attached to the view, throws from the view's clear(). */
	@Test void theTrackerAsWrittenThrowsOnTheViewsClear() throws Exception {
		try (World world = world(trackerBytes)) {
			Map<Integer, Object> view = world.view();
			world.register(view);
			Throwable thrown = assertThrows(Throwable.class, () -> world.remap(REMAPPED));
			assertTrue(causes(thrown).stream().anyMatch(UnsupportedOperationException.class::isInstance),
					"the remap reaches IntKeyedView.clear(), which is unsupported: " + causes(thrown));
		}
	}

	/**
	 * The fix: the view is not tracked, and after the remap every provider answers under its type's new id through the
	 * view while NeoForge's Identifier-keyed map holds exactly what it held. Red on the tracker as written (the clear()
	 * above).
	 */
	@Test void afterARemapEveryProviderAnswersUnderItsTypesNewIdAndNeoForgesMapIsIntact() throws Exception {
		try (World world = world(detached())) {
			Map<Integer, Object> view = world.view();
			for (String type : TYPES) view.put(world.id(type), world.provider(type));
			Map<Object, Object> neoforgeBefore = new LinkedHashMap<>(world.byName);
			world.register(view);
			world.remap(REMAPPED);
			for (String type : TYPES) {
				assertSame(world.provider(type), view.get(world.id(type)), type + " answers under the id the remap gave it");
				assertSame(world.provider(type), world.byName.get(world.key(type)), type + " is still in NeoForge's map by name");
			}
			assertEquals(neoforgeBefore, world.byName, "NeoForge's map is untouched by the remap");
			assertEquals(TYPES.size(), view.size());
		}
	}

	/**
	 * The premise of not tracking it, on this test's model of {@code remap}: once the registry has its new ids, the view
	 * answers them with no event fired, so a tracker that re-keys it applies the change a second time. This cannot fail
	 * against the game by itself (the model rebuilds the ids first because the test says so); that the game's
	 * {@code remap} rebuilds them before the event fires, and that the view reads the fields it rebuilds, is pinned on
	 * the real bytes by {@link #theRealRemapRebuildsTheIdsTheViewReadsBeforeItFiresTheEvent}.
	 */
	@Test void theViewAnswersTheNewIdsBeforeTheRemapEventFires() throws Exception {
		try (World world = world(detached())) {
			Map<Integer, Object> view = world.view();
			for (String type : TYPES) view.put(world.id(type), world.provider(type));
			Map<String, Integer> before = new HashMap<>();
			for (String type : TYPES) before.put(type, world.id(type));
			world.reassign(REMAPPED);
			for (String type : TYPES) {
				assertSame(world.provider(type), view.get(world.id(type)), type + " under its new id, no event fired");
			}
			assertFalse(world.provider("balm:a") == view.get(before.get("balm:a")),
					"the old id of balm:a now names another type, and answers that type's provider");
		}
	}

	/**
	 * The order the premise above rests on, on the real bytes, where a reordering upstream would show. fabric-registry-sync
	 * 7.1.0's {@code MappedRegistryMixin.remap} clears and refills {@code byId}/{@code toId} and only then calls
	 * {@code fabric_getRemapEvent().invoker().onRemap(...)}; the genuine base's {@code MappedRegistry.getId(T)} reads
	 * {@code toId} and {@code byId(int)} reads {@code byId}, the two fields the mixin shadows with the same descriptors,
	 * which is what the view's ids are in the game. If the event moved before the rebuild, the view would be stale when
	 * the tracker runs and detaching it would be wrong. The order check is shown able to fail: the same method with the
	 * event call moved to its head is judged out of order.
	 */
	@Test void theRealRemapRebuildsTheIdsTheViewReadsBeforeItFiresTheEvent() throws Exception {
		Path base = TestFixtures.stagedBase();
		TestFixtures.requireFiles(Fixture.STAGED, "NeoForge's base", base);
		ClassNode registry;
		try (ZipFile zip = new ZipFile(base.toFile())) {
			ZipEntry entry = zip.getEntry("net/minecraft/core/MappedRegistry.class");
			assertNotNull(entry, base + " holds net/minecraft/core/MappedRegistry");
			try (var in = zip.getInputStream(entry)) {
				registry = node(in.readAllBytes());
			}
		}
		assertEquals(List.of("toId"), fieldsRead(method(registry, "getId", "(Ljava/lang/Object;)I")),
				"the base's MappedRegistry.getId(T) answers from toId");
		assertEquals(List.of("byId", "byId"), fieldsRead(method(registry, "byId", "(I)Ljava/lang/Object;")),
				"the base's MappedRegistry.byId(int) answers from byId");
		ClassNode mixin = node(registryMixinBytes);
		for (String field : List.of("byId", "toId")) {
			assertEquals(field(registry, field).desc, field(mixin, field).desc, "the mixin's " + field + " shadows the base's");
		}
		MethodNode remap = method(mixin, "remap",
				"(Lit/unimi/dsi/fastutil/objects/Object2IntMap;Lnet/fabricmc/fabric/impl/registry/sync/RemappableRegistry$RemapMode;)V");
		assertTrue(rebuildsTheIdsBeforeTheEvent(remap.instructions),
				"fabric-registry-sync's remap writes byId/toId (set, put) and reads them for the last time before it fires the remap event");

		InsnList reordered = new InsnList();
		InsnList rest = new InsnList();
		boolean inEvent = false;
		for (AbstractInsnNode insn : remap.instructions.toArray()) {
			remap.instructions.remove(insn);
			if (insn instanceof MethodInsnNode call && call.name.equals(REMAP_EVENT)) {
				inEvent = true;
				AbstractInsnNode receiver = rest.getLast();
				rest.remove(receiver);
				reordered.add(receiver);
			}
			(inEvent ? reordered : rest).add(insn);
			if (insn instanceof MethodInsnNode call && call.name.equals("onRemap")) inEvent = false;
		}
		reordered.add(rest);
		assertFalse(rebuildsTheIdsBeforeTheEvent(reordered), "the check fails on a remap that fires the event before the rebuild");
	}

	/** Whether every touch of byId/toId comes before the remap event call, with a write to each among them. */
	private static boolean rebuildsTheIdsBeforeTheEvent(InsnList instructions) {
		int index = 0, event = -1, lastTouch = -1;
		boolean setById = false, putToId = false;
		for (AbstractInsnNode insn : instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
					&& (field.name.equals("byId") || field.name.equals("toId"))) lastTouch = index;
			if (insn instanceof MethodInsnNode call) {
				if (call.name.equals(REMAP_EVENT) && event < 0) event = index;
				if (event < 0 && call.owner.equals("it/unimi/dsi/fastutil/objects/ObjectList") && call.name.equals("set")) setById = true;
				if (event < 0 && call.owner.equals("it/unimi/dsi/fastutil/objects/Reference2IntMap") && call.name.equals("put")) putToId = true;
			}
			index++;
		}
		return event >= 0 && lastTouch >= 0 && lastTouch < event && setById && putToId;
	}

	private static List<String> fieldsRead(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) out.add(field.name);
		}
		return out;
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(owner.name + " has no " + name + desc));
	}

	private static FieldNode field(ClassNode owner, String name) {
		return owner.fields.stream().filter(f -> f.name.equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError(owner.name + " has no field " + name));
	}

	@Test void switchedOffTheTrackerIsAttachedAsWritten() throws Exception {
		String before = System.getProperty(ParticleViewTrackerInjector.PROPERTY);
		System.setProperty(ParticleViewTrackerInjector.PROPERTY, "off");
		try {
			assertSame(trackerBytes, new ParticleViewTrackerInjector().transform(TRACKER, trackerBytes, null));
			assertFalse(new ParticleViewTrackerInjector().anchors().anchors().stream().anyMatch(a -> a.binaryName().equals(TRACKER)));
		} finally {
			if (before == null) System.clearProperty(ParticleViewTrackerInjector.PROPERTY);
			else System.setProperty(ParticleViewTrackerInjector.PROPERTY, before);
		}
	}

	@Test void onlyTheTrackersRegisterGetsTheGuardAndASecondPassStandsDown() {
		ClassNode node = node(trackerBytes);
		assertTrue(ParticleViewTrackerInjector.detach(node), "fabric-registry-sync 7.1.0's register is the reviewed shape");
		for (MethodNode method : node.methods) {
			boolean guarded = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(ParticleViewTrackerInjector.VIEWS)
						&& call.name.equals(ParticleViewTrackerInjector.GUARD)) guarded = true;
			}
			assertEquals(method.name.equals("register"), guarded, method.name + method.desc);
		}
		assertFalse(ParticleViewTrackerInjector.detach(node), "a second pass stands down");
		ClassNode other = node(trackerBytes);
		other.name = "net/fabricmc/fabric/impl/registry/sync/trackers/IdMapperTracker";
		assertFalse(ParticleViewTrackerInjector.detach(other), "another tracker is not touched");
	}

	private static byte[] detached() {
		byte[] out = new ParticleViewTrackerInjector().transform(TRACKER, trackerBytes, null);
		assertFalse(out == trackerBytes, "the transformer rewrote fabric-registry-sync's tracker");
		return out;
	}

	private static List<Throwable> causes(Throwable t) {
		List<Throwable> out = new ArrayList<>();
		for (Throwable c = t; c != null && !out.contains(c); c = c.getCause()) out.add(c);
		return out;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static World world(byte[] tracker) throws Exception {
		TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "actual classfile 69 needs Java 25");
		Path base = TestFixtures.stagedBase(), carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "NeoForge's base and carrier", base, carrier);
		Path build = Path.of(System.getProperty("user.dir"), "build", "classes", "java");
		Path helper = build.resolve("runtime/" + ParticleViewTrackerInjector.VIEWS + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(helper), "game-side classes not compiled: " + helper);
		Path libraries = TestFixtures.minecraftDir().resolve("libraries");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isDirectory(libraries), "Minecraft libraries required (fastutil, Guava, slf4j)");
		List<URL> urls = new ArrayList<>(List.of(build.resolve("runtime").toUri().toURL(), base.toUri().toURL(), carrier.toUri().toURL(),
				nested.resolve("fabric-api-base-module.jar").toUri().toURL(),
				nested.resolve("fabric-registry-sync-v0-module.jar").toUri().toURL()));
		try (var walk = Files.walk(libraries)) {
			for (Path jar : walk.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) urls.add(jar.toUri().toURL());
		}
		URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				synchronized (getClassLoadingLock(name)) {
					if (name.equals(TRACKER)) {
						Class<?> done = findLoadedClass(name);
						return done != null ? done : defineClass(name, tracker, 0, tracker.length);
					}
					return super.loadClass(name, resolve);
				}
			}
		};
		return new World(loader);
	}

	/** One registry the test owns: a name ↔ id table, the two fabric events on it, and NeoForge's provider map. */
	private static final class World implements AutoCloseable {
		final URLClassLoader loader;
		final Map<Object, Object> byName = new HashMap<>();
		final Map<Integer, Object> keyById = new HashMap<>();
		final Map<Object, Integer> idByKey = new HashMap<>();
		final Map<String, Object> providers = new HashMap<>();
		final Object addEvent, remapEvent, registry;
		final Method fromNamespaceAndPath;

		World(URLClassLoader loader) throws Exception {
			this.loader = loader;
			fromNamespaceAndPath = loader.loadClass("net.minecraft.resources.Identifier")
					.getMethod("fromNamespaceAndPath", String.class, String.class);
			for (int i = 0; i < TYPES.size(); i++) {
				Object key = key(TYPES.get(i));
				keyById.put(i, key);
				idByKey.put(key, i);
			}
			addEvent = event("net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback");
			remapEvent = event("net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback");
			Class<?> registryType = loader.loadClass("net.minecraft.core.Registry");
			Class<?> listenable = loader.loadClass("net.fabricmc.fabric.impl.registry.sync.ListenableRegistry");
			registry = Proxy.newProxyInstance(loader, new Class<?>[] {registryType, listenable}, (proxy, method, args) -> switch (method.getName()) {
				case "fabric_getAddObjectEvent" -> addEvent;
				case "fabric_getRemapEvent" -> remapEvent;
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				case "toString" -> "TestParticleRegistry";
				default -> throw new UnsupportedOperationException(method.getName());
			});
		}

		Object key(String name) throws Exception {
			String[] parts = name.split(":");
			return fromNamespaceAndPath.invoke(null, parts[0], parts[1]);
		}

		int id(String name) throws Exception {
			return idByKey.get(key(name));
		}

		Object provider(String type) {
			return providers.computeIfAbsent(type, t -> "provider of " + t);
		}

		@SuppressWarnings("unchecked")
		Map<Integer, Object> rawMap() throws Exception {
			return (Map<Integer, Object>) loader.loadClass("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap")
					.getConstructor().newInstance();
		}

		@SuppressWarnings("unchecked")
		Map<Integer, Object> view() throws Exception {
			Method factory = loader.loadClass(ParticleViewTrackerInjector.VIEWS.replace('/', '.'))
					.getDeclaredMethod("intKeyedView", Map.class, IntFunction.class, ToIntFunction.class);
			factory.setAccessible(true);
			IntFunction<Object> keyOf = keyById::get;
			ToIntFunction<Object> idOf = k -> idByKey.getOrDefault(k, -1);
			return (Map<Integer, Object>) factory.invoke(null, byName, keyOf, idOf);
		}

		void register(Map<Integer, Object> map) throws Exception {
			Class<?> tracker = loader.loadClass(TRACKER);
			tracker.getMethod("register", loader.loadClass("net.minecraft.core.Registry"), String.class,
					loader.loadClass("it.unimi.dsi.fastutil.ints.Int2ObjectMap")).invoke(null, registry, NAME, map);
		}

		/** The registry's byId/toId rebuilt to {@code order} — the first half of MappedRegistry.remap. */
		Map<Integer, Integer> reassign(List<String> order) throws Exception {
			Map<Integer, Integer> change = new LinkedHashMap<>();
			Map<Integer, Object> oldKeys = new HashMap<>(keyById);
			keyById.clear();
			idByKey.clear();
			for (int i = 0; i < order.size(); i++) {
				Object key = key(order.get(i));
				keyById.put(i, key);
				idByKey.put(key, i);
			}
			for (Map.Entry<Integer, Object> e : oldKeys.entrySet()) change.put(e.getKey(), idByKey.get(e.getValue()));
			return change;
		}

		/** All of MappedRegistry.remap: rebuild the ids, then fire RegistryIdRemapCallback with every old → new id. */
		void remap(List<String> order) throws Exception {
			Map<Integer, Object> oldKeys = new HashMap<>(keyById);
			Map<Integer, Integer> change = reassign(order);
			@SuppressWarnings("unchecked")
			Map<Integer, Integer> changeMap = (Map<Integer, Integer>) loader.loadClass("it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap")
					.getConstructor().newInstance();
			changeMap.putAll(change);
			Class<?> stateType = loader.loadClass("net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback$RemapState");
			Object state = Proxy.newProxyInstance(loader, new Class<?>[] {stateType}, (proxy, method, args) -> switch (method.getName()) {
				case "getRawIdChangeMap" -> changeMap;
				case "getIdFromOld" -> oldKeys.get((Integer) args[0]);
				case "getIdFromNew" -> keyById.get((Integer) args[0]);
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> "RemapState";
			});
			Object invoker = loader.loadClass("net.fabricmc.fabric.api.event.Event").getMethod("invoker").invoke(remapEvent);
			loader.loadClass("net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback").getMethod("onRemap", stateType)
					.invoke(invoker, state);
		}

		private Object event(String typeName) throws Exception {
			Class<?> type = loader.loadClass(typeName);
			Function<Object[], Object> invokers = listeners -> Proxy.newProxyInstance(loader, new Class<?>[] {type},
					(InvocationHandler) (proxy, method, args) -> {
						if (method.getDeclaringClass() == Object.class) return method.getName().equals("hashCode")
								? System.identityHashCode(proxy) : method.getName().equals("equals") ? proxy == args[0] : typeName;
						for (Object listener : listeners) method.invoke(listener, args);
						return null;
					});
			return loader.loadClass("net.fabricmc.fabric.api.event.EventFactory")
					.getMethod("createArrayBacked", Class.class, Function.class).invoke(null, type, invokers);
		}

		@Override public void close() throws IOException {
			loader.close();
		}
	}
}
