/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.access.ClassTweakerTransformer;

/**
 * The symptom check for decision D52, on NeoForge's own base: a {@code ChunkGenerator} whose {@code featuresPerStep}
 * fabric-biome-api replaced, then NeoForge's {@code refreshFeaturesPerStep()} — the call
 * {@code ServerLifecycleHooks.runModifiers} makes on every level stem's generator between the biome and the structure
 * modifiers.
 *
 * <p>The generator is NeoForge's {@code ChunkGenerator} from the staged base, widened exactly as fabric-biome-api 18.0.6's
 * class tweaker widens it ({@code accessible} + {@code mutable featuresPerStep}), with its static initialiser removed and
 * its abstract methods stubbed so it can be allocated without a bootstrapped game; the supplier is written the way
 * {@code BiomeModificationImpl.lambda$finalizeWorldGen$1} writes it, {@code Suppliers.memoize} (Guava). Without
 * {@link FeaturesPerStepRefreshInjector} the base's {@code checkcast Lazy} throws there (the red check, measured on the
 * real bytes); with it, the supplier is rebuilt as NeoForge's {@code Lazy} of the constructor's computation — checked
 * against real biomes whose generation settings place known features, before and after the biome list changes — and a
 * NeoForge {@code Lazy} is still only invalidated.
 */
@ExecutesInjector(FeaturesPerStepRefreshInjector.class)
class FeaturesPerStepRefreshStagedTest {
	private static final String OWNER = "net/minecraft/world/level/chunk/ChunkGenerator";
	private static final String SOURCE = "net/minecraft/world/level/biome/BiomeSource";
	private static final String SUPPLIER = "Ljava/util/function/Supplier;";
	private static final String PLACED = "net/minecraft/world/level/levelgen/placement/PlacedFeature";
	private static final String SETTINGS = "net/minecraft/world/level/biome/BiomeGenerationSettings";
	private static final String HOLDER = "net/minecraft/core/Holder";
	private static final String HOLDER_SET = "net/minecraft/core/HolderSet";

	@Test void onNeoForgesOwnBaseTheRefreshThrowsOnTheSupplierFabricWrites() throws Exception {
		byte[] base = widened(read(OWNER));
		try (Game game = game(base)) {
			game.write(game.fabricSupplier(List.of("fabric")));
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class, game::refresh);
			assertInstanceOf(ClassCastException.class, thrown.getCause(),
					"NeoForge's refreshFeaturesPerStep casts fabric-biome-api's Guava supplier to its own Lazy");
		}
	}

	@Test void theRewrittenRefreshRebuildsTheSupplierFabricWrote() throws Exception {
		byte[] rewritten = new FeaturesPerStepRefreshInjector().transform(FeaturesPerStepRefreshInjector.GENERATOR, widened(read(OWNER)), null);
		try (Game game = game(rewritten)) {
			Object first = game.placed("first"), second = game.placed("second");
			Object plains = game.biome("plains", first);
			game.biomes.set(Set.of(plains));
			Object fabric = game.fabricSupplier(List.of("fabric"));
			game.write(fabric);
			game.refresh();
			Object rebuilt = game.read();
			assertNotSame(fabric, rebuilt, "the supplier fabric-biome-api wrote was replaced");
			assertEquals("net.neoforged.neoforge.common.util.Lazy", rebuilt.getClass().getName(),
					"the replacement is NeoForge's own Lazy, so its next refresh is an invalidate");
			// Computed from the generator's biome source and generation settings, as the constructor's Lazy is: one step,
			// holding the one feature the one biome places — not Fabric's answer, and not an empty list.
			assertEquals(List.of(List.of(first)), game.steps(rebuilt), "the rebuilt list is the constructor's computation over the current biomes");

			// What the modifiers do next: change the biomes, then refresh. The Lazy is invalidated in place and recomputes.
			game.biomes.set(Set.of(plains, game.biome("forest", second)));
			game.refresh();
			assertSame(rebuilt, game.read(), "a NeoForge Lazy is invalidated in place, never replaced");
			List<List<Object>> steps = game.steps(rebuilt);
			assertEquals(1, steps.size(), "both features are in step 0");
			assertEquals(Set.of(first, second), Set.copyOf(steps.get(0)), "the second refresh sees the biome added after the first");
		}
	}

	@Test void theRewrittenRefreshStillOnlyInvalidatesNeoForgesLazy() throws Exception {
		byte[] rewritten = new FeaturesPerStepRefreshInjector().transform(FeaturesPerStepRefreshInjector.GENERATOR, widened(read(OWNER)), null);
		try (Game game = game(rewritten)) {
			AtomicInteger computations = new AtomicInteger();
			Object lazy = game.loader.loadClass("net.neoforged.neoforge.common.util.Lazy").getMethod("of", Supplier.class)
					.invoke(null, (Supplier<Object>) () -> List.of("computed " + computations.incrementAndGet()));
			game.write(lazy);
			assertEquals(List.of("computed 1"), ((Supplier<?>) game.read()).get());
			game.refresh();
			assertSame(lazy, game.read());
			assertEquals(List.of("computed 2"), ((Supplier<?>) game.read()).get(), "invalidate, then recompute on the next read");
		}
	}

	@Test void theReviewedShapeIsRewrittenAndAnyOtherIsLeftWhole() throws Exception {
		ClassNode generator = node(read(OWNER));
		assertTrue(FeaturesPerStepRefreshInjector.rewrite(generator), "NeoForge's refreshFeaturesPerStep is the reviewed shape");
		MethodNode refresh = refresh(generator);
		assertFalse(java.util.Arrays.stream(refresh.instructions.toArray()).anyMatch(i -> i.getOpcode() == Opcodes.CHECKCAST),
				"the cast is gone");
		assertFalse(FeaturesPerStepRefreshInjector.rewrite(generator), "a second pass stands down");

		ClassNode other = node(read(OWNER));
		for (AbstractInsnNode insn : refresh(other).instructions.toArray()) {
			if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) cast.desc = "java/lang/Object";
		}
		assertFalse(FeaturesPerStepRefreshInjector.rewrite(other), "a body that is not the reviewed shape is not touched");
	}

	@Test void switchedOffTheBaseIsLeftAsItIs() throws Exception {
		String before = System.getProperty(FeaturesPerStepRefreshInjector.PROPERTY);
		System.setProperty(FeaturesPerStepRefreshInjector.PROPERTY, "off");
		try {
			byte[] base = read(OWNER);
			assertSame(base, new FeaturesPerStepRefreshInjector().transform(FeaturesPerStepRefreshInjector.GENERATOR, base, null));
			assertTrue(new FeaturesPerStepRefreshInjector().anchors().anchors().isEmpty(), "no anchor is declared while off");
		} finally {
			if (before == null) System.clearProperty(FeaturesPerStepRefreshInjector.PROPERTY);
			else System.setProperty(FeaturesPerStepRefreshInjector.PROPERTY, before);
		}
	}

	// ------------------------------------------------------------------------------------------------------------

	/** A generator allocated from {@code generatorBytes}, whose biome source answers {@link #biomes}, in a loader of the staged game. */
	private record Game(URLClassLoader loader, Class<?> type, Object generator, Field field, AtomicReference<Set<Object>> biomes,
			Map<Object, Object> settings) implements AutoCloseable {
		void write(Object supplier) throws Exception { field.set(generator, supplier); }
		Object read() throws Exception { return field.get(generator); }
		void refresh() throws Exception { type.getMethod("refreshFeaturesPerStep").invoke(generator); }
		/** What fabric-biome-api writes: {@code Suppliers.memoize(com.google.common.base.Supplier)}. */
		Object fabricSupplier(Object answer) throws Exception {
			Class<?> guava = loader.loadClass("com.google.common.base.Supplier");
			Object inner = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {guava},
					(proxy, method, args) -> method.getName().equals("get") ? answer : method.getName().equals("toString") ? "fabric" : null);
			return loader.loadClass("com.google.common.base.Suppliers").getMethod("memoize", guava).invoke(null, inner);
		}
		/** A {@code PlacedFeature}, told apart from the others by its placement list. */
		Object placed(String name) throws Exception {
			return loader.loadClass(PLACED.replace('/', '.')).getConstructor(loader.loadClass(HOLDER.replace('/', '.')), List.class)
					.newInstance(null, List.of(name));
		}
		/** A biome holder whose generation settings place {@code features} in step 0; the generator's settings getter answers it. */
		Object biome(String name, Object... features) throws Exception {
			Class<?> holder = loader.loadClass(HOLDER.replace('/', '.')), holderSet = loader.loadClass(HOLDER_SET.replace('/', '.'));
			List<Object> holders = new ArrayList<>();
			for (Object feature : features) holders.add(holder.getMethod("direct", Object.class).invoke(null, feature));
			Object step0 = holderSet.getMethod("direct", List.class).invoke(null, holders);
			Object generation = loader.loadClass(SETTINGS.replace('/', '.')).getConstructor(holderSet, List.class)
					.newInstance(null, List.of(step0));
			Object biome = holder.getMethod("direct", Object.class).invoke(null, name);
			settings.put(biome, generation);
			return biome;
		}
		/** The features of each step of a feature list, read through {@code StepFeatureData.features()}. */
		List<List<Object>> steps(Object featureList) throws Exception {
			List<List<Object>> steps = new ArrayList<>();
			for (Object step : (List<?>) ((Supplier<?>) featureList).get()) {
				steps.add(List.copyOf((List<?>) step.getClass().getMethod("features").invoke(step)));
			}
			return steps;
		}
		@Override public void close() throws IOException { loader.close(); }
	}

	private static Game game(byte[] generatorBytes) throws Exception {
		TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "actual classfile 69 needs Java 25");
		Path base = TestFixtures.stagedBase(), carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "NeoForge's base and carrier", base, carrier);
		Path build = Path.of(System.getProperty("user.dir"), "build", "classes", "java");
		Path helper = build.resolve("runtime/" + FeaturesPerStepRefreshInjector.RUNTIME + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(helper), "game-side classes not compiled: " + helper);
		Path libraries = TestFixtures.minecraftDir().resolve("libraries");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isDirectory(libraries), "Minecraft libraries required (Guava, DFU)");

		Map<String, byte[]> definitions = new HashMap<>();
		definitions.put(OWNER.replace('/', '.'), allocatable(generatorBytes));
		definitions.put(SOURCE.replace('/', '.'), allocatable(read(SOURCE)));
		// Their static codecs would pull the registries in; the tests only construct them.
		for (String plain : List.of(PLACED, SETTINGS)) definitions.put(plain.replace('/', '.'), allocatable(read(plain)));
		List<URL> urls = new ArrayList<>(List.of(base.toUri().toURL(), carrier.toUri().toURL(),
				build.resolve("runtime").toUri().toURL(), build.resolve("main").toUri().toURL()));
		try (var walk = Files.walk(libraries)) {
			for (Path jar : walk.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) urls.add(jar.toUri().toURL());
		}
		URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = definitions.get(name);
				return bytes == null ? super.findClass(name) : defineClass(name, bytes, 0, bytes.length);
			}
		};
		Class<?> type = loader.loadClass(OWNER.replace('/', '.'));
		Object generator = unsafe().allocateInstance(type);
		Class<?> sourceType = loader.loadClass(SOURCE.replace('/', '.'));
		Object source = unsafe().allocateInstance(sourceType);
		Field possible = sourceType.getDeclaredField("possibleBiomes");
		possible.setAccessible(true);
		AtomicReference<Set<Object>> biomes = new AtomicReference<>(Set.of());
		possible.set(source, (Supplier<Object>) biomes::get);
		Field biomeSource = type.getDeclaredField("biomeSource");
		biomeSource.setAccessible(true);
		biomeSource.set(generator, source);
		Map<Object, Object> settings = new HashMap<>();
		Field getter = type.getDeclaredField("generationSettingsGetter");
		getter.setAccessible(true);
		getter.set(generator, (java.util.function.Function<Object, Object>) settings::get);
		Field field = type.getDeclaredField("featuresPerStep");
		field.setAccessible(true);
		return new Game(loader, type, generator, field, biomes, settings);
	}

	/** {@code <clinit>} removed (no registry bootstrap) and every abstract method stubbed, so Unsafe can allocate it. */
	private static byte[] allocatable(byte[] bytes) {
		ClassNode node = node(bytes);
		node.methods.removeIf(m -> m.name.equals("<clinit>"));
		node.access &= ~Opcodes.ACC_ABSTRACT;
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_ABSTRACT) == 0) continue;
			method.access &= ~Opcodes.ACC_ABSTRACT;
			method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			method.instructions.add(new InsnNode(Opcodes.ATHROW));
			method.maxStack = 1;
			method.maxLocals = 1 + java.util.Arrays.stream(Type.getArgumentTypes(method.desc)).mapToInt(Type::getSize).sum();
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** What fabric-biome-api 18.0.6's class tweaker does to the field. */
	private static byte[] widened(byte[] generator) {
		String tweaker = "accessWidener v2 official\naccessible field " + OWNER + " featuresPerStep " + SUPPLIER
				+ "\nmutable field " + OWNER + " featuresPerStep " + SUPPLIER + "\n";
		return ClassTweakerTransformer.create(List.of(tweaker.getBytes(StandardCharsets.UTF_8)), (n, b) -> { })
				.transform(OWNER.replace('/', '.'), generator, null);
	}

	private static MethodNode refresh(ClassNode generator) {
		return generator.methods.stream().filter(m -> m.name.equals("refreshFeaturesPerStep")).findFirst().orElseThrow();
	}

	private static byte[] read(String internal) throws IOException {
		Path base = TestFixtures.stagedBase();
		TestFixtures.requireFile(Fixture.STAGED, base, "NeoForge's staged base absent");
		try (ZipFile zip = new ZipFile(base.toFile())) {
			var entry = zip.getEntry(internal + ".class");
			assertTrue(entry != null, internal + " is in NeoForge's base");
			try (var in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (sun.misc.Unsafe) field.get(null);
	}
}
