/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.interop.HudContextCallbackScope;

/**
 * {@link HudContextQueryInjector}'s output, run: while Create's train overlay holds a {@link HudContextCallbackScope}, the
 * HUD's contextual bar asks it for the next state (so the overlay can suppress the experience bar); with no scope the
 * HUD's own answer stands.
 *
 * <p>The hook is the kernel's real {@code KernelHudContextQuery}, compiled from {@code src/runtime/java} against a
 * stand-in {@code Hud} with the one call the edit keys on; the scope is the kernel's own class.
 */
@ExecutesInjector(HudContextQueryInjector.class)
class HudContextQueryInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelHudContextQuery.java");
	private static final String HUD = HudContextQueryInjector.TARGET;

	private static final String HUD_SOURCE = """
			package net.minecraft.client.gui;

			public class Hud {
				public enum ContextualInfo { EMPTY, EXPERIENCE, LOCATOR }

				public ContextualInfo current = ContextualInfo.EMPTY;

				public ContextualInfo nextContextualInfoState() {
					return ContextualInfo.EXPERIENCE;
				}

				public void updateContextualBarRenderer() {
					this.current = this.nextContextualInfoState();
				}
			}
			""";

	private static Object update(ClassLoader loader) throws Throwable {
		Object hud = InjectorExecution.construct(loader.loadClass(HUD));
		InjectorExecution.invoke(hud, "updateContextualBarRenderer");
		return hud.getClass().getField("current").get(hud);
	}

	@Test void createsScopeDecidesTheContextualBarAndOtherwiseTheHudDoes(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, byte[]> original = InjectorExecution.compile(work, Map.of(HUD, HUD_SOURCE,
				"net/forbric/kernel/runtime/KernelHudContextQuery.java", Files.readString(HOOK_SOURCE)));
		String internal = HUD.replace('.', '/');
		byte[] routed = InjectorExecution.transform(new HudContextQueryInjector(), HUD, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, routed);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(routed, loader));

		assertEquals("EXPERIENCE", String.valueOf(update(loader)), "no Create overlay: the HUD's own answer");
		Object empty = Enum.valueOf(loader.loadClass(HUD + "$ContextualInfo").asSubclass(Enum.class), "EMPTY");
		List<String> asked = new ArrayList<>();
		Object previous = HudContextCallbackScope.enter(args -> {
			asked.add("native would say " + ((Supplier<?>) args[1]).get());
			return empty;
		});
		try {
			assertSame(empty, update(loader), "the train overlay suppresses the contextual bar");
		} finally {
			HudContextCallbackScope.leave(previous);
		}
		assertEquals(List.of("native would say EXPERIENCE"), asked, "Create is handed the native query, not a guess");

		ClassLoader merged = InjectorExecution.load(original);
		Object stillPrevious = HudContextCallbackScope.enter(args -> {
			throw new AssertionError("asked");
		});
		try {
			assertEquals("EXPERIENCE", String.valueOf(update(merged)), "premise: as merged, Create is never asked");
		} finally {
			HudContextCallbackScope.leave(stillPrevious);
		}
	}
}
