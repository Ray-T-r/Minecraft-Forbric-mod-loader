package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The compostables fallback over the COMPILED game-side class: only what somebody added or re-weighted after vanilla's
 * bootstrap shows, nothing shows before the bootstrap was seen, and NeoForge's data map decides whenever it listed the item.
 */
class KernelCompostablesTest {
	private URLClassLoader loader;
	private Class<?> map;
	private Object backing, vanilla;
	private Method put, containsKey, getFloat;

	@BeforeEach void load() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path fastutil = Path.of(System.getProperty("user.home"),
				"Library/Application Support/minecraft/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
		Path run = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar"), neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(fastutil) && Files.isRegularFile(merged) && Files.isRegularFile(neoRt),
				"game-side classes, the staged game and fastutil required");
		// The game's jars are there for the signatures only (reflection resolves every declared method's types); nothing
		// here initialises a game class.
		loader = new URLClassLoader(new URL[] { compiled.toUri().toURL(), fastutil.toUri().toURL(), merged.toUri().toURL(), neoRt.toUri().toURL() },
				ClassLoader.getPlatformClassLoader());
		map = loader.loadClass("it.unimi.dsi.fastutil.objects.Object2FloatMap");
		Class<?> open = loader.loadClass("it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap");
		backing = open.getConstructor().newInstance();
		vanilla = open.getConstructor().newInstance();
		map.getMethod("defaultReturnValue", float.class).invoke(backing, -1.0F);
		put = map.getMethod("put", Object.class, float.class);
		containsKey = map.getMethod("containsKey", Object.class);
		getFloat = map.getMethod("getFloat", Object.class);
	}

	@AfterEach void close() throws Exception {
		if (loader != null) loader.close();
	}

	private Object delta(BooleanSupplier recorded) throws Exception {
		Constructor<?> ctor = loader.loadClass("net.forbric.kernel.runtime.KernelCompostables$Delta")
				.getDeclaredConstructor(map, map, BooleanSupplier.class);
		ctor.setAccessible(true);
		return ctor.newInstance(backing, vanilla, recorded);
	}

	@Test void onlyWhatWasAddedOrChangedAfterBootstrapShows() throws Exception {
		// bootStrap: seeds 0.3, kelp 0.3 — recorded as vanilla's own and in the map.
		for (Object target : new Object[] { backing, vanilla }) {
			put.invoke(target, "wheat_seeds", 0.3F);
			put.invoke(target, "kelp", 0.3F);
		}
		// Later: Fabric's CompostableRegistry.add(dirt, 1.0) and a re-weighted kelp.
		put.invoke(backing, "dirt", 1.0F);
		put.invoke(backing, "kelp", 0.65F);
		Object delta = delta(() -> true);
		assertTrue((boolean) containsKey.invoke(delta, "dirt"));
		assertEquals(1.0F, (float) getFloat.invoke(delta, "dirt"));
		assertFalse((boolean) containsKey.invoke(delta, "wheat_seeds"),
				"vanilla's own entry is the data map's to decide: a datapack removing seeds must keep them out");
		assertEquals(-1.0F, (float) getFloat.invoke(delta, "wheat_seeds"));
		assertTrue((boolean) containsKey.invoke(delta, "kelp"), "a changed vanilla chance is somebody else's entry");
		assertEquals(0.65F, (float) getFloat.invoke(delta, "kelp"));
		assertFalse((boolean) containsKey.invoke(delta, "stick"));
		assertEquals(-1.0F, (float) getFloat.invoke(delta, "stick"), "absent answers -1, as COMPOSTABLES does");
		assertEquals(2, map.getMethod("size").invoke(delta));
	}

	@Test void nothingShowsUntilTheBootstrapWasSeen() throws Exception {
		put.invoke(backing, "wheat_seeds", 0.3F);
		AtomicBoolean recorded = new AtomicBoolean();
		Object delta = delta(recorded::get);
		assertFalse((boolean) containsKey.invoke(delta, "wheat_seeds"),
				"a declined bootStrap transform must not turn vanilla's whole table into mod entries");
		recorded.set(true);
		assertTrue((boolean) containsKey.invoke(delta, "wheat_seeds"), "recorded, and seeds were never vanilla's here");
	}

	@Test void neoForgeDecidesWhateverItsDataMapListed() throws Exception {
		Class<?> compostables = loader.loadClass("net.forbric.kernel.runtime.KernelCompostables");
		Method orContains = compostables.getMethod("orContains", float.class, boolean.class);
		Method orChance = compostables.getMethod("orChance", float.class, float.class);
		assertEquals(0.3F, (float) orContains.invoke(null, 0.3F, false), "listed: the fallback cannot veto");
		assertEquals(0.0F, (float) orContains.invoke(null, 0.0F, true), "listed at 0: the fallback cannot make it compostable");
		assertEquals(1.0F, (float) orContains.invoke(null, -1.0F, true), "a miss the fallback (or a wrap of it) accepts");
		assertEquals(-1.0F, (float) orContains.invoke(null, -1.0F, false));
		assertEquals(0.65F, (float) orChance.invoke(null, 0.65F, 1.0F));
		assertEquals(1.0F, (float) orChance.invoke(null, -1.0F, 1.0F));
		assertEquals(-1.0F, (float) orChance.invoke(null, -1.0F, -1.0F));
	}
}
