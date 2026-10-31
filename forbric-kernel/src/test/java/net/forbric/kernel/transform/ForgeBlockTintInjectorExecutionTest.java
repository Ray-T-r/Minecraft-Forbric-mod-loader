/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeBlockTintInjector}'s output, run: the live {@code BlockColors} that {@code createDefault} builds holds a
 * MinecraftForge mod's block tint as well as a NeoForge mod's, where as merged only NeoForge's registration event was
 * posted and the MinecraftForge mod's grass-coloured block rendered untinted.
 *
 * <p>The hook is the kernel's real {@code KernelForgeBlockColors}, compiled from {@code src/runtime/java} against
 * stand-ins for NeoForge's event and mod loader and MinecraftForge's client hooks; each lets its own mods register.
 */
@ExecutesInjector(ForgeBlockTintInjector.class)
@ResourceLock("system-properties")
class ForgeBlockTintInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeBlockColors.java");
	private static final String COLORS = ForgeBlockTintInjector.TARGET;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public abstract class Event { }",
			COLORS, """
					package net.minecraft.client.color.block;

					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

					public class BlockColors {
						public final Map<String, Integer> tints = new LinkedHashMap<>();

						public static BlockColors createDefault() {
							BlockColors colors = new BlockColors();
							colors.tints.put("minecraft:grass_block", 0x79C05A);
							ModLoader.postEvent(new RegisterColorHandlersEvent.BlockTintSources(colors));
							return colors;
						}
					}
					""",
			"net.neoforged.neoforge.client.event.RegisterColorHandlersEvent", """
					package net.neoforged.neoforge.client.event;

					import net.minecraft.client.color.block.BlockColors;
					import net.neoforged.bus.api.Event;

					public abstract class RegisterColorHandlersEvent extends Event {
						public static class BlockTintSources extends RegisterColorHandlersEvent {
							private final BlockColors colors;

							public BlockTintSources(BlockColors colors) {
								this.colors = colors;
							}

							public BlockColors getBlockColors() {
								return colors;
							}
						}
					}
					""",
			"net.neoforged.fml.ModLoader", """
					package net.neoforged.fml;

					import net.neoforged.bus.api.Event;
					import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

					public class ModLoader {
						public static void postEvent(Event event) {
							((RegisterColorHandlersEvent.BlockTintSources) event).getBlockColors().tints.put("neoforgemod:moss", 0x4A7A2C);
						}
					}
					""",
			"net.minecraftforge.client.ForgeHooksClient", """
					package net.minecraftforge.client;

					import net.minecraft.client.color.block.BlockColors;

					public class ForgeHooksClient {
						public static void onBlockColorsInit(BlockColors colors) {
							colors.tints.put("forgemod:grassy_bricks", 0x91BD59);
						}
					}
					""");

	@BeforeEach
	@AfterEach
	void reset() throws Throwable {
		System.clearProperty("forbric.forgeClientInit");
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelForgeBlockColors.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	private static Object tints(ClassLoader loader) throws Throwable {
		Object colors = InjectorExecution.invokeStatic(loader.loadClass(COLORS), "createDefault");
		return colors.getClass().getField("tints").get(colors);
	}

	@Test void bothFamiliesRegisterTheirTintsOnTheLiveColors(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = COLORS.replace('.', '/');
		byte[] posted = InjectorExecution.transform(new ForgeBlockTintInjector(), COLORS, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, posted);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(posted, loader));

		assertEquals(Map.of("minecraft:grass_block", 0x79C05A, "neoforgemod:moss", 0x4A7A2C, "forgemod:grassy_bricks", 0x91BD59),
				tints(loader));
		assertEquals(Map.of("minecraft:grass_block", 0x79C05A, "neoforgemod:moss", 0x4A7A2C), tints(InjectorExecution.load(original)),
				"premise: as merged, a MinecraftForge mod's block tint never reaches the live colors");
		assertSame(posted, InjectorExecution.transform(new ForgeBlockTintInjector(), COLORS, posted, EnvType.CLIENT),
				"an already redirected post is left alone");
	}

	@Test void switchedOffTheColorsAreLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(COLORS.replace('.', '/'));
		System.setProperty("forbric.forgeClientInit", "off");
		assertSame(bytes, InjectorExecution.transform(new ForgeBlockTintInjector(), COLORS, bytes, EnvType.CLIENT));
	}
}
