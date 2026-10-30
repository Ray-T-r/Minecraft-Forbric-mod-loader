/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.fabricmc.api.EnvType;

/**
 * MinecraftForge's real {@code ClearableLazy$Concurrent}, raced deterministically: one thread computes inside the lock
 * while a second has already read {@code null} and waits for that lock. Unpatched, the second returns {@code null};
 * patched, it returns the computed value.
 *
 * <p>The same race also runs on a stand-in compiled here with the double-checked lock the repair keys on, so the
 * repaired body is defined and raced on a checkout without the staged carrier.
 */
@ExecutesInjector(ForgeClearableLazyInjector.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ForgeClearableLazyInjectorTest {
	private static final Path FORGE_RUNTIME = TestFixtures.stagedRoot().resolve(TestFixtures.FORGE_CARRIER_JAR);
	private static final String ENTRY = "net/minecraftforge/common/util/ClearableLazy$Concurrent.class";
	private static final String INTERNAL = "net/minecraftforge/common/util/ClearableLazy$Concurrent";
	/** The idiom with its flaw: the locked check that finds the value set falls through to the first, null read. */
	private static final String STAND_IN = """
			package net.minecraftforge.common.util;

			import java.util.function.Supplier;

			public class ClearableLazy {
				public static final class Concurrent<T> {
					private final Object lock = new Object();
					private final Supplier<T> supplier;
					private volatile T instance;

					Concurrent(Supplier<T> supplier) {
						this.supplier = supplier;
					}

					public T get() {
						T ret = instance;
						if (ret == null) {
							synchronized (lock) {
								if (instance == null) return instance = supplier.get();
							}
						}
						return ret;
					}
				}
			}
			""";

	private static byte[] original() {
		return TestFixtures.requireEntry(Fixture.STAGED, FORGE_RUNTIME, ENTRY);
	}

	private static byte[] patched(byte[] original) {
		return new ForgeClearableLazyInjector().transform(ForgeClearableLazyInjector.TARGET, original, null);
	}

	@Test
	void theLosingThreadGetsTheComputedValueOnlyAfterTheRepair() throws Exception {
		byte[] original = original();
		assertNull(race(original), "premise: MinecraftForge's own body returns null to the thread that waited for the lock");
		assertEquals("computed", race(patched(original)));
	}

	@Test
	void aStandInsLosingThreadGetsTheComputedValueOnlyAfterTheRepair(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work,
				Map.of("net.minecraftforge.common.util.ClearableLazy", STAND_IN));
		byte[] concurrent = original.get(INTERNAL);
		byte[] repaired = InjectorExecution.transform(new ForgeClearableLazyInjector(), ForgeClearableLazyInjector.TARGET,
				concurrent, EnvType.SERVER);
		assertNotSame(concurrent, repaired, "the stand-in must have the shape the repair keys on, or the race proves nothing");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(INTERNAL, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		assertNull(race(InjectorExecution.load(original).loadClass(ForgeClearableLazyInjector.TARGET)),
				"premise: the stand-in returns null to the thread that waited for the lock");
		assertEquals("computed", race(loader.loadClass(ForgeClearableLazyInjector.TARGET)));
		assertSame(repaired, patched(repaired), "a body that already re-reads is left alone");
	}

	@Test
	void theRepairIsThreeInstructionsVerifiesAndRunsOnce() throws Exception {
		byte[] original = original();
		byte[] once = patched(original);
		assertNotSame(original, once);
		ClassNode node = new ClassNode();
		new ClassReader(once).accept(node, 0);
		var get = node.methods.stream().filter(m -> m.name.equals("get")).findFirst().orElseThrow();
		new Analyzer<>(new BasicVerifier()).analyze(node.name, get);
		ClassNode before = new ClassNode();
		new ClassReader(original).accept(before, 0);
		var oldGet = before.methods.stream().filter(m -> m.name.equals("get")).findFirst().orElseThrow();
		assertEquals(oldGet.instructions.size() + 3, get.instructions.size());
		assertSame(once, patched(once), "a body that already re-reads is left alone");
	}

	@Test
	void theOffSwitchAndOtherClassesAreLeftAlone() throws Exception {
		byte[] original = original();
		assertSame(original, new ForgeClearableLazyInjector().transform("net.minecraftforge.common.util.Lazy$Concurrent", original, null));
		String old = System.setProperty(ForgeClearableLazyInjector.PROPERTY, "off");
		try {
			assertSame(original, patched(original));
		} finally {
			if (old == null) System.clearProperty(ForgeClearableLazyInjector.PROPERTY);
			else System.setProperty(ForgeClearableLazyInjector.PROPERTY, old);
		}
	}

	/** {@link #race(Class)} on the carrier's own class with {@code concurrent} as its {@code ClearableLazy$Concurrent}. */
	private static Object race(byte[] concurrent) throws Exception {
		try (URLClassLoader carrier = new URLClassLoader(new URL[] { FORGE_RUNTIME.toUri().toURL() },
				ForgeClearableLazyInjectorTest.class.getClassLoader());
				Loader loader = new Loader(carrier, concurrent)) {
			return race(loader.loadClass(ForgeClearableLazyInjector.TARGET));
		}
	}

	/**
	 * Thread A enters {@code get()} and blocks inside the supplier while holding the lock; thread B then reads
	 * {@code null}, and is parked on the monitor. A finishes; B's answer is what this returns.
	 */
	private static Object race(Class<?> type) throws Exception {
		Constructor<?> make = type.getDeclaredConstructor(Supplier.class);
		make.setAccessible(true);
		CountDownLatch inSupplier = new CountDownLatch(1), release = new CountDownLatch(1);
		Supplier<Object> slow = () -> {
			inSupplier.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "computed";
		};
		Object lazy = make.newInstance(slow);
		Method get = type.getMethod("get");
		AtomicReference<Object> winner = new AtomicReference<>(), loser = new AtomicReference<>();
		Thread a = new Thread(() -> winner.set(call(get, lazy)), "lazy-winner");
		a.start();
		assertTrue(inSupplier.await(10, TimeUnit.SECONDS));
		Thread b = new Thread(() -> loser.set(call(get, lazy)), "lazy-loser");
		b.start();
		for (int i = 0; i < 1000 && b.getState() != Thread.State.BLOCKED; i++) Thread.sleep(5);
		assertEquals(Thread.State.BLOCKED, b.getState(), "the second thread must be waiting for the lock");
		release.countDown();
		a.join(10_000);
		b.join(10_000);
		assertEquals("computed", winner.get());
		return loser.get();
	}

	private static Object call(Method get, Object lazy) {
		try {
			return get.invoke(lazy);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Defines the given {@code ClearableLazy$Concurrent} bytes; everything else comes from the carrier. */
	private static final class Loader extends ClassLoader implements AutoCloseable {
		private final byte[] concurrent;

		Loader(ClassLoader parent, byte[] concurrent) {
			super(parent);
			this.concurrent = concurrent;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!ForgeClearableLazyInjector.TARGET.equals(name)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				return c != null ? c : defineClass(name, concurrent, 0, concurrent.length);
			}
		}

		@Override
		public void close() {
		}
	}
}
