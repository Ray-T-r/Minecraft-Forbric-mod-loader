/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.KernelClientSmoke;

/**
 * The output of the three injectors that hand the kernel a tick, run:
 * <ul>
 *   <li>{@link CompatibilityPromptTickInjector}: every client tick hands the {@code Minecraft} to the compatibility
 *       prompts, before the tick's own work, so a late decision reaches the player at a safe point;</li>
 *   <li>{@link ClientSmokeTickInjector}: with {@code -Dforbric.clientSmoke=true} every client tick hands it to the smoke
 *       controller too, and without it the class is not touched;</li>
 *   <li>{@link ServerCompatibilityTickInjector}: every completed server tick, on each of its returns, hands the server
 *       to the late-compatibility check.</li>
 * </ul>
 * The hooks are recording stand-ins under their game-side and boot-side names, defined child-first: the real ones open
 * screens, stop the game or halt a server. What is under test is that the edited methods reach them, once per tick,
 * with the right object.
 */
@ExecutesInjector({CompatibilityPromptTickInjector.class, ClientSmokeTickInjector.class, ServerCompatibilityTickInjector.class})
@ResourceLock("system-properties")
class TickHookInjectorsExecutionTest {
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String SERVER = "net.minecraft.server.MinecraftServer";

	private static final Map<String, String> STAND_INS = Map.of(
			"fixture.Trace", """
					package fixture;
					public final class Trace {
						public static final java.util.List<String> events = new java.util.ArrayList<>();
					}
					""",
			MINECRAFT, """
					package net.minecraft.client;

					import fixture.Trace;

					public class Minecraft {
						public int ticks;

						public void tick() {
							if (ticks++ % 2 == 1) {
								Trace.events.add("tick " + ticks + " paused");
								return;
							}
							Trace.events.add("tick " + ticks);
						}
					}
					""",
			SERVER, """
					package net.minecraft.server;

					import java.util.function.BooleanSupplier;
					import fixture.Trace;

					public class MinecraftServer {
						public void tickServer(BooleanSupplier hasTimeLeft) {
							if (!hasTimeLeft.getAsBoolean()) {
								Trace.events.add("server tick cut short");
								return;
							}
							Trace.events.add("server tick");
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelCompatibilityPrompts", """
					package net.forbric.kernel.runtime;

					import fixture.Trace;
					import net.minecraft.client.Minecraft;

					public final class KernelCompatibilityPrompts {
						public static void tick(Minecraft minecraft) {
							Trace.events.add("prompts see tick " + minecraft.ticks);
						}
					}
					""",
			"net.forbric.kernel.boot.KernelClientSmoke", """
					package net.forbric.kernel.boot;

					import fixture.Trace;

					public final class KernelClientSmoke {
						public static void onClientTick(Object minecraft) {
							Trace.events.add("smoke sees " + minecraft.getClass().getSimpleName());
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelServerDuties", """
					package net.forbric.kernel.runtime;

					import fixture.Trace;

					public final class KernelServerDuties {
						public static void onCompatibilityTick(Object server) {
							Trace.events.add("late compatibility sees " + server.getClass().getSimpleName());
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(KernelClientSmoke.ENABLED);
	}

	private static ClassLoader transformed(Map<String, byte[]> original, String target, ClassTransformer... chain) {
		String internal = target.replace('.', '/');
		byte[] bytes = original.get(internal);
		for (ClassTransformer injector : chain) {
			byte[] out = InjectorExecution.transform(injector, target, bytes, EnvType.CLIENT);
			assertNotSame(bytes, out, injector.name());
			bytes = out;
		}
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, bytes);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(bytes, loader));
		return loader;
	}

	private static List<?> trace(ClassLoader loader) throws ReflectiveOperationException {
		return (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "events");
	}

	@Test void everyClientTickReachesThePromptsAndTheSmokeControllerFirst(@TempDir Path work) throws Throwable {
		System.setProperty(KernelClientSmoke.ENABLED, "true");
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		ClassLoader loader = transformed(original, MINECRAFT, new CompatibilityPromptTickInjector(), new ClientSmokeTickInjector());
		Object minecraft = InjectorExecution.construct(loader.loadClass(MINECRAFT));
		InjectorExecution.invoke(minecraft, "tick");
		InjectorExecution.invoke(minecraft, "tick");
		assertEquals(List.of("smoke sees Minecraft", "prompts see tick 0", "tick 1",
				"smoke sees Minecraft", "prompts see tick 1", "tick 2 paused"), trace(loader),
				"once per tick, at its head, whichever way the tick returns");

		Object stock = InjectorExecution.construct(InjectorExecution.load(original).loadClass(MINECRAFT));
		InjectorExecution.invoke(stock, "tick");
		assertEquals(List.of("tick 1"), trace(stock.getClass().getClassLoader()), "premise: as merged, nothing is told");
	}

	@Test void withoutTheSmokeHarnessTheClientIsNotTouchedByIt(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, STAND_INS).get(MINECRAFT.replace('.', '/'));
		assertSame(bytes, InjectorExecution.transform(new ClientSmokeTickInjector(), MINECRAFT, bytes, EnvType.CLIENT));
	}

	@Test void everyCompletedServerTickReachesTheLateCompatibilityCheck(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(InjectorExecution.compile(work, STAND_INS), SERVER, new ServerCompatibilityTickInjector());
		Object server = InjectorExecution.construct(loader.loadClass(SERVER));
		InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> true);
		InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> false);
		assertEquals(List.of("server tick", "late compatibility sees MinecraftServer",
				"server tick cut short", "late compatibility sees MinecraftServer"), trace(loader),
				"after the tick's own work, on each of its returns");
	}
}
