/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StaleWatchEventInjector}'s output, run: a class that registers handlers with night-config's
 * {@code FileWatcher.addWatch} (both overloads) now registers guarded ones — an event that finds the file as it was at
 * registration runs nothing, a later edit runs the handler. The watcher is a recording stand-in under night-config's
 * name; the caller has a name of its own.
 */
@ExecutesInjector(StaleWatchEventInjector.class)
class StaleWatchEventInjectorExecutionTest {
	private static final String CALLER = "org.example.orchard.Keeper";
	private static final Map<String, String> STAND_INS = Map.of(
			StaleWatchEventInjector.WATCHER.replace('/', '.'), """
					package com.electronwill.nightconfig.core.file;
					import java.util.*;
					public final class FileWatcher {
						public static final List<Runnable> handlers = new ArrayList<>();
						public void addWatch(java.nio.file.Path file, Runnable handler) { handlers.add(handler); }
						public void addWatch(java.io.File file, Runnable handler) { handlers.add(handler); }
					}
					""",
			CALLER, """
					package org.example.orchard;
					import com.electronwill.nightconfig.core.file.FileWatcher;
					public final class Keeper {
						public static int reloads;
						public static void watch(java.nio.file.Path a, java.io.File b) {
							FileWatcher watcher = new FileWatcher();
							watcher.addWatch(a, () -> reloads++);
							watcher.addWatch(b, () -> reloads += 10);
						}
					}
					""");

	@Test void everyRegisteredHandlerRunsOnlyForAChangeAfterItsWatch(@TempDir Path work) throws Throwable {
		Path a = Files.writeString(work.resolve("a.toml"), "a = 1\n"), b = Files.writeString(work.resolve("b.toml"), "b = 1\n");
		Map<String, byte[]> original = InjectorExecution.compile(work.resolve("src"), STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		String internal = CALLER.replace('.', '/');
		classes.put(internal, InjectorExecution.transform(new StaleWatchEventInjector(), CALLER, original.get(internal), EnvType.SERVER));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(internal), loader));
		Class<?> keeper = loader.loadClass(CALLER);
		keeper.getMethod("watch", Path.class, java.io.File.class).invoke(null, a, b.toFile());
		List<?> handlers = (List<?>) InjectorExecution.getStatic(loader.loadClass(StaleWatchEventInjector.WATCHER.replace('/', '.')), "handlers");
		assertEquals(2, handlers.size());
		for (Object handler : handlers) ((Runnable) handler).run();      // the creations, reported late
		assertEquals(0, InjectorExecution.getStatic(keeper, "reloads"));
		Files.writeString(a, "a = 2\n");
		Files.writeString(b, "b = 2\n");
		for (Object handler : handlers) ((Runnable) handler).run();
		assertEquals(11, InjectorExecution.getStatic(keeper, "reloads"));

		ClassLoader shipped = InjectorExecution.load(original);
		Class<?> raw = shipped.loadClass(CALLER);
		raw.getMethod("watch", Path.class, java.io.File.class).invoke(null, a, b.toFile());
		for (Object handler : (List<?>) InjectorExecution.getStatic(shipped.loadClass(StaleWatchEventInjector.WATCHER.replace('/', '.')), "handlers")) ((Runnable) handler).run();
		assertEquals(11, InjectorExecution.getStatic(raw, "reloads"), "premise: as shipped, every event runs the handler");
	}

	@Test void theWatcherItselfAndTheSwitchAreLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] watcher = original.get(StaleWatchEventInjector.WATCHER);
		assertSame(watcher, InjectorExecution.transform(new StaleWatchEventInjector(), StaleWatchEventInjector.WATCHER.replace('/', '.'), watcher, EnvType.SERVER));
		byte[] caller = original.get(CALLER.replace('.', '/'));
		System.setProperty("forbric.staleWatchGuard", "off");
		try {
			assertSame(caller, InjectorExecution.transform(new StaleWatchEventInjector(), CALLER, caller, EnvType.SERVER));
		} finally {
			System.clearProperty("forbric.staleWatchGuard");
		}
	}
}
