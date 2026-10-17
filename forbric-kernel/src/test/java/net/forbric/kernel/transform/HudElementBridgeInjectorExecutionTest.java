/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link HudElementBridgeInjector}'s output, run: {@code initModdedLayers} hands the manager over to MinecraftForge's
 * overlay stack once its own layers are in, and a layer added to NeoForge's {@code GuiLayerManager} is the one the mod
 * added — no kernel wrapper redraws anything around it (fabric-rendering-v1's own handlers dispatch its roots).
 *
 * <p>The bridge is a recording stand-in under {@code KernelHudBridge}'s name, defined child-first.
 */
@ExecutesInjector(HudElementBridgeInjector.class)
class HudElementBridgeInjectorExecutionTest {
	private static final String MANAGER = "net.neoforged.neoforge.client.gui.GuiLayerManager";
	private static final String IDENTIFIER = "net.minecraft.resources.Identifier";
	private static final String BRIDGE = "net.forbric.kernel.boot.KernelHudBridge";

	private static final Map<String, String> STAND_INS = Map.of(
			IDENTIFIER, """
					package net.minecraft.resources;

					public record Identifier(String path) {
					}
					""",
			"net.neoforged.neoforge.client.gui.GuiLayer", """
					package net.neoforged.neoforge.client.gui;

					import java.util.List;

					public interface GuiLayer {
						void render(List<String> drawn);
					}
					""",
			MANAGER, """
					package net.neoforged.neoforge.client.gui;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.BooleanSupplier;
					import net.minecraft.resources.Identifier;

					public class GuiLayerManager {
						public final List<GuiLayer> layers = new ArrayList<>();

						public GuiLayerManager add(Identifier id, GuiLayer layer) {
							layers.add(layer);
							return this;
						}

						public GuiLayerManager add(Identifier id, GuiLayer layer, BooleanSupplier condition) {
							layers.add(drawn -> {
								if (condition.getAsBoolean()) layer.render(drawn);
							});
							return this;
						}

						public void initModdedLayers() {
							layers.add(drawn -> drawn.add("a NeoForge mod's layer"));
						}

						public List<String> render() {
							List<String> drawn = new ArrayList<>();
							for (GuiLayer layer : layers) layer.render(drawn);
							return drawn;
						}
					}
					""",
			BRIDGE, """
					package net.forbric.kernel.boot;

					import java.util.ArrayList;
					import java.util.List;
					import net.neoforged.neoforge.client.gui.GuiLayer;
					import net.neoforged.neoforge.client.gui.GuiLayerManager;

					public final class KernelHudBridge {
						public static final List<String> calls = new ArrayList<>();

						public static void addForgeOverlayLayers(Object manager) {
							calls.add("forge overlays after " + ((GuiLayerManager) manager).layers.size() + " layers");
						}
					}
					""");

	private static Object layer(ClassLoader loader, String name) throws ClassNotFoundException {
		Class<?> type = loader.loadClass("net.neoforged.neoforge.client.gui.GuiLayer");
		return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {type}, (proxy, method, args) -> {
			if (!method.getName().equals("render")) throw new UnsupportedOperationException(method.getName());
			@SuppressWarnings("unchecked")
			List<String> drawn = (List<String>) args[0];
			drawn.add(name);
			return null;
		});
	}

	private static Object id(ClassLoader loader, String path) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(IDENTIFIER), path);
	}

	@SuppressWarnings("unchecked")
	private static List<String> hud(ClassLoader loader) throws Throwable {
		Object manager = InjectorExecution.construct(loader.loadClass(MANAGER));
		InjectorExecution.invoke(manager, "add", id(loader, "hotbar"), layer(loader, "hotbar"));
		InjectorExecution.invoke(manager, "add", id(loader, "boss_bar"), layer(loader, "boss bar"), (BooleanSupplier) () -> true);
		InjectorExecution.invoke(manager, "add", id(loader, "chat"), layer(loader, "chat"), (BooleanSupplier) () -> false);
		InjectorExecution.invoke(manager, "initModdedLayers");
		return (List<String>) InjectorExecution.invoke(manager, "render");
	}

	@Test void theOverlayStackGoesOnLastAndAddedLayersAreLeftAsAdded(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = MANAGER.replace('.', '/');
		byte[] routed = InjectorExecution.transform(new HudElementBridgeInjector(), MANAGER, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, routed);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(routed, loader));

		assertEquals(List.of("hotbar", "boss bar", "a NeoForge mod's layer"), hud(loader), "the added layers, as added");
		assertEquals(List.of("forge overlays after 4 layers"), InjectorExecution.getStatic(loader.loadClass(BRIDGE), "calls"),
				"the overlay stack is handed the manager after its own layers, and nothing else is routed");

		ClassLoader merged = InjectorExecution.load(original);
		assertEquals(List.of("hotbar", "boss bar", "a NeoForge mod's layer"), hud(merged));
		assertEquals(List.of(), InjectorExecution.getStatic(merged.loadClass(BRIDGE), "calls"),
				"premise: as merged, MinecraftForge's overlay stack is never handed the manager");
		assertSame(routed, InjectorExecution.transform(new HudElementBridgeInjector(), MANAGER, routed, EnvType.CLIENT),
				"a manager that already hands itself over is left alone");
	}
}
