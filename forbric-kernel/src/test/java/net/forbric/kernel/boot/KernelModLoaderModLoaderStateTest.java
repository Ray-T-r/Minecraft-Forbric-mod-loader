/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link KernelModLoader#bindModLoaderModList}: NeoForge's {@code ModLoader} keeps the {@code ModList} it walks in a
 * static field only {@code gatherAndInitializeMods} sets, which the kernel does not run. Natively a mod may call
 * {@code ModLoader.dispatchParallelEvent} after loading (AE2 does, from a client reload listener); with the field null
 * that NPE'd ("ModLoader.modList is null") and the first resource reload failed.
 */
class KernelModLoaderModLoaderStateTest {
	private static final Pattern ARTIFACT_PATH = Pattern.compile("\"artifact\"\\s*:\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]+)\"");

	private URLClassLoader genuine;

	@AfterEach
	void close() throws Exception {
		if (genuine != null) genuine.close();
	}

	/** Stand-ins with the shape of FML's ModLoader: a private static {@code modList}. */
	static final class StandInModLoader {
		@SuppressWarnings("unused")
		private static Object modList;
	}

	@Test
	void bindsTheFieldAndIsIdempotent() throws Exception {
		Object list = new Object();
		assertTrue(KernelModLoader.bindModList(StandInModLoader.class, list));
		Field field = StandInModLoader.class.getDeclaredField("modList");
		field.setAccessible(true);
		assertSame(list, field.get(null));
		assertFalse(KernelModLoader.bindModList(StandInModLoader.class, list), "binding the same list twice is no change");
		Object replaced = new Object();
		assertTrue(KernelModLoader.bindModList(StandInModLoader.class, replaced), "a newer list replaces the old one");
		assertSame(replaced, field.get(null));
	}

	/** NeoForge's carrier and patched game over Minecraft's libraries; nothing is transformed, nothing of the game runs. */
	private static URLClassLoader genuine() throws Exception {
		Path game = TestFixtures.stagedBase(), neo = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base or its carrier is absent", game, neo);
		List<URL> urls = new ArrayList<>(List.of(neo.toUri().toURL(), game.toUri().toURL()));
		Path root = TestFixtures.minecraftDir().resolve("libraries");
		Path version = root.resolveSibling("versions/26.2/26.2.json");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(version), "no local Minecraft 26.2 version JSON");
		Matcher artifact = ARTIFACT_PATH.matcher(Files.readString(version));
		while (artifact.find()) {
			Path jar = root.resolve(artifact.group(1));
			if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}

	@Test
	void dispatchParallelEventRunsOnTheGenuineModLoaderOnceBound() throws Exception {
		genuine = genuine();
		ClassLoader cl = genuine;
		// What the kernel's seed does: the singleton, then setLoadedMods (PassiveSeeder.seedNeoForgeModList).
		PassiveSeeder.seedNeoForgeModList(cl);
		Class<?> modListCls = Class.forName("net.neoforged.fml.ModList", true, cl);
		Object live = modListCls.getMethod("get").invoke(null);
		Class<?> modLoader = Class.forName("net.neoforged.fml.ModLoader", true, cl);

		KernelModLoader.bindModLoaderModList(cl);

		Field field = modLoader.getDeclaredField("modList");
		field.setAccessible(true);
		assertSame(live, field.get(null), "ModLoader.modList must be the same object ModList.get() answers, as natively");

		// The call AE2 makes from its reload listener: a parallel event, then its deferred queue on the sync executor.
		Executor direct = Runnable::run;
		AtomicBoolean periodic = new AtomicBoolean();
		Runnable tick = () -> periodic.set(true);
		BiFunction<Object, Object, Object> noEvent = (container, queue) -> {
			throw new AssertionError("there is no container to post to");
		};
		try {
			modLoader.getMethod("dispatchParallelEvent", String.class, Executor.class, Executor.class, Runnable.class,
					BiFunction.class).invoke(null, "a mod's own parallel event", direct, direct, tick, noEvent);
		} catch (InvocationTargetException thrown) {
			throw new AssertionError("dispatchParallelEvent threw on the bound ModLoader", thrown.getCause());
		}
		assertTrue(periodic.get(), "dispatchParallelTask ran its periodic task, i.e. got past ModLoader.modList");
	}
}
