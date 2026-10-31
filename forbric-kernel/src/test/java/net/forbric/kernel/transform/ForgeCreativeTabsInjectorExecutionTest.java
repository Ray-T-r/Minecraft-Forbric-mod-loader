/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeCreativeTabsInjector}'s output, run: a creative tab's contents now hold what a MinecraftForge mod adds
 * from its own build-contents event, besides the tab's own items and what NeoForge mods add, where as merged only
 * NeoForge's event was posted.
 *
 * <p>The hook is the kernel's real {@code KernelForgeCreativeTabs}, compiled from {@code src/runtime/java} against
 * stand-ins for the tab and both families' hooks; each stand-in hook runs the generator and then lets its own mods
 * add, as the real ones post their event.
 */
@ExecutesInjector(ForgeCreativeTabsInjector.class)
@ResourceLock("system-properties")
class ForgeCreativeTabsInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeCreativeTabs.java");
	private static final String TAB = ForgeCreativeTabsInjector.TARGET;

	private static final Map<String, String> STAND_INS = Map.of(
			TAB, """
					package net.minecraft.world.item;

					import java.util.ArrayList;
					import java.util.List;
					import net.neoforged.neoforge.event.EventHooks;

					public class CreativeModeTab {
						public interface DisplayItemsGenerator {
							void accept(ItemDisplayParameters parameters, Output output);
						}

						public interface Output {
							void accept(String item);
						}

						public record ItemDisplayParameters(boolean operator) {
						}

						public final List<String> displayItems = new ArrayList<>();
						private final DisplayItemsGenerator displayItemsGenerator = (parameters, output) -> output.accept("minecraft:stone");

						public void buildContents(ItemDisplayParameters parameters) {
							EventHooks.onCreativeModeTabBuildContents(this, displayItemsGenerator, parameters, displayItems::add);
						}
					}
					""",
			"net.neoforged.neoforge.event.EventHooks", """
					package net.neoforged.neoforge.event;

					import net.minecraft.world.item.CreativeModeTab;

					public class EventHooks {
						public static void onCreativeModeTabBuildContents(CreativeModeTab tab, CreativeModeTab.DisplayItemsGenerator generator,
								CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
							generator.accept(parameters, output);
							output.accept("neoforgemod:gear");
						}
					}
					""",
			"net.minecraftforge.common.ForgeHooks", """
					package net.minecraftforge.common;

					import net.minecraft.world.item.CreativeModeTab;

					public class ForgeHooks {
						public static void onCreativeModeTabBuildContents(CreativeModeTab tab, CreativeModeTab.DisplayItemsGenerator generator,
								CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
							generator.accept(parameters, output);
							output.accept("forgemod:wrench");
						}
					}
					""");

	@BeforeEach
	@AfterEach
	void reset() throws Throwable {
		System.clearProperty(ForgeCreativeTabsInjector.PROPERTY);
	}

	private static List<?> contents(ClassLoader loader) throws Throwable {
		Object tab = InjectorExecution.construct(loader.loadClass(TAB));
		InjectorExecution.invoke(tab, "buildContents",
				InjectorExecution.construct(loader.loadClass(TAB + "$ItemDisplayParameters"), false));
		return (List<?>) tab.getClass().getField("displayItems").get(tab);
	}

	@Test void aTabHoldsWhatEitherFamilysModsAdd(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelForgeCreativeTabs.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = TAB.replace('.', '/');
		byte[] served = InjectorExecution.transform(new ForgeCreativeTabsInjector(), TAB, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, served);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(served, loader));

		assertEquals(List.of("minecraft:stone", "neoforgemod:gear", "forgemod:wrench"), contents(loader),
				"the tab's own items and NeoForge's run inside MinecraftForge's collector, then MinecraftForge's mods add");
		assertEquals(List.of("minecraft:stone", "neoforgemod:gear"), contents(InjectorExecution.load(original)),
				"premise: as merged, a MinecraftForge mod cannot add to a creative tab");
		assertSame(served, InjectorExecution.transform(new ForgeCreativeTabsInjector(), TAB, served, EnvType.CLIENT),
				"a tab already served is left alone");
	}

	@Test void switchedOffTheTabIsLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelForgeCreativeTabs.java", Files.readString(HOOK_SOURCE));
		byte[] bytes = InjectorExecution.compile(work, sources).get(TAB.replace('.', '/'));
		System.setProperty(ForgeCreativeTabsInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new ForgeCreativeTabsInjector(), TAB, bytes, EnvType.CLIENT));
	}
}
