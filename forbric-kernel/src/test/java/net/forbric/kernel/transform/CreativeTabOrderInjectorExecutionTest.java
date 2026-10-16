/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CreativeTabOrderInjector}'s output, run: the order NeoForge's publisher receives goes through the kernel's
 * placement hook first, every time it is called, and what the hook returns is what gets published. The hook is a
 * recording stand-in under the runtime class's name; the placement rule itself is EcosystemTabPlacementTest's.
 */
@ExecutesInjector(CreativeTabOrderInjector.class)
class CreativeTabOrderInjectorExecutionTest {
	private static final String REGISTRY = CreativeTabOrderInjector.TARGET;
	private static final Map<String, String> STAND_INS = Map.of(
			CreativeTabOrderInjector.HOOK.replace('/', '.'), """
					package net.forbric.kernel.runtime;
					import java.util.*;
					public final class KernelCreativeTabOrder {
						public static final List<Object> seen = new ArrayList<>();
						public static List place(List sorted) {
							seen.add(new ArrayList<>(sorted));
							List placed = new ArrayList<>(sorted);
							Collections.reverse(placed);
							return placed;
						}
					}
					""",
			REGISTRY, """
					package net.neoforged.neoforge.common;
					import java.util.*;
					public final class CreativeModeTabRegistry {
						public static List<String> published = List.of();
						public static void sortTabs(List<String> chain) { setCreativeModeTabOrder(chain); }
						private static void setCreativeModeTabOrder(List<String> order) { published = List.copyOf(order); }
					}
					""");

	@Test void everyPublishedOrderGoesThroughTheHookFirst(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		String internal = REGISTRY.replace('.', '/');
		classes.put(internal, InjectorExecution.transform(new CreativeTabOrderInjector(), REGISTRY, original.get(internal), EnvType.CLIENT));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(internal), loader));
		Class<?> registry = loader.loadClass(REGISTRY);
		registry.getMethod("sortTabs", List.class).invoke(null, List.of("a", "b", "c"));
		assertEquals(List.of("c", "b", "a"), InjectorExecution.getStatic(registry, "published"));
		registry.getMethod("sortTabs", List.class).invoke(null, List.of("x", "y"));
		assertEquals(List.of("y", "x"), InjectorExecution.getStatic(registry, "published"), "a re-sort goes through it again");
		assertEquals(List.of(List.of("a", "b", "c"), List.of("x", "y")),
				InjectorExecution.getStatic(loader.loadClass(CreativeTabOrderInjector.HOOK.replace('/', '.')), "seen"));

		ClassLoader merged = InjectorExecution.load(original);
		Class<?> untouched = merged.loadClass(REGISTRY);
		untouched.getMethod("sortTabs", List.class).invoke(null, List.of("a", "b", "c"));
		assertEquals(List.of("a", "b", "c"), InjectorExecution.getStatic(untouched, "published"), "premise: as shipped, NeoForge's order is published");
	}

	@Test void otherClassesAndTheSwitchLeaveTheBytesAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] hook = original.get(CreativeTabOrderInjector.HOOK);
		assertSame(hook, InjectorExecution.transform(new CreativeTabOrderInjector(), CreativeTabOrderInjector.HOOK.replace('/', '.'), hook, EnvType.CLIENT));
		byte[] registry = original.get(REGISTRY.replace('.', '/'));
		System.setProperty(net.forbric.kernel.interop.EcosystemTabPlacement.PROPERTY, "off");
		try {
			assertSame(registry, InjectorExecution.transform(new CreativeTabOrderInjector(), REGISTRY, registry, EnvType.CLIENT));
		} finally {
			System.clearProperty(net.forbric.kernel.interop.EcosystemTabPlacement.PROPERTY);
		}
	}
}
