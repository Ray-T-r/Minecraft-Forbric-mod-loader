/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link ForgeHudPipelineInjector} on stand-ins of the three shapes a merged {@code Hud} can take: both families'
 * edits kept (MinecraftForge's tree built in the constructor and drawn first, returning before NeoForge's layer
 * manager draws), NeoForge's alone, and MinecraftForge's alone. Only the first is edited: there the HUD must draw
 * through NeoForge's manager and MinecraftForge's own build must not run, because the kernel's build is the one that
 * puts MinecraftForge's stack on that manager. The other two are the negative controls — a stand-down keyed on the
 * calls must not touch a Hud without them, nor take away the only pipeline a Forge-only Hud has.
 */
@ExecutesInjector(ForgeHudPipelineInjector.class)
class ForgeHudPipelineInjectorExecutionTest {
	private static final String HUD = ForgeHudPipelineInjector.TARGET;
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");

	private enum Shape { BOTH, NEOFORGE_ONLY, FORGE_ONLY }

	private static Map<String, String> standIns(Shape shape) {
		Map<String, String> sources = new HashMap<>();
		sources.put("net.minecraft.client.Minecraft", "package net.minecraft.client; public class Minecraft { }");
		sources.put("net.minecraft.client.DeltaTracker", "package net.minecraft.client; public class DeltaTracker { }");
		sources.put("net.minecraft.client.gui.GuiGraphicsExtractor", "package net.minecraft.client.gui; public class GuiGraphicsExtractor { }");
		sources.put("fixture.Trace", """
				package fixture;
				public final class Trace {
					public static final java.util.List<String> events = new java.util.ArrayList<>();
				}
				""");
		sources.put("net.minecraftforge.client.gui.overlay.ForgeLayeredDraw", """
				package net.minecraftforge.client.gui.overlay;

				import fixture.Trace;
				import net.minecraft.client.DeltaTracker;
				import net.minecraft.client.Minecraft;
				import net.minecraft.client.gui.GuiGraphicsExtractor;
				import net.minecraft.client.gui.Hud;

				public class ForgeLayeredDraw {
					public static void init(Hud hud, Minecraft minecraft) {
						Trace.events.add("forge build");
					}

					public static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
						Trace.events.add("forge draw");
					}
				}
				""");
		sources.put("net.neoforged.neoforge.client.gui.GuiLayerManager", """
				package net.neoforged.neoforge.client.gui;

				import fixture.Trace;
				import net.minecraft.client.DeltaTracker;
				import net.minecraft.client.gui.GuiGraphicsExtractor;

				public class GuiLayerManager {
					public void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
						Trace.events.add("neoforge draw");
					}
				}
				""");
		String build = shape == Shape.NEOFORGE_ONLY ? "" : "ForgeLayeredDraw.init(this, minecraft);";
		String forgeDraw = shape == Shape.NEOFORGE_ONLY ? ""
				: "if (Boolean.valueOf(true).booleanValue()) { ForgeLayeredDraw.extractRenderState(graphics, delta); return; }";
		String neoDraw = shape == Shape.FORGE_ONLY ? "" : "this.leftHeight = 39; layerManager.render(graphics, delta);";
		sources.put(HUD, """
				package net.minecraft.client.gui;

				import net.minecraft.client.DeltaTracker;
				import net.minecraft.client.Minecraft;
				import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
				import net.neoforged.neoforge.client.gui.GuiLayerManager;

				public class Hud {
					private final GuiLayerManager layerManager = new GuiLayerManager();
					public int leftHeight;

					public Hud(Minecraft minecraft) {
						%s
						fixture.Trace.events.add("vanilla layers registered");
					}

					public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
						%s
						%s
					}
				}
				""".formatted(build, forgeDraw, neoDraw));
		return sources;
	}

	private static List<?> run(ClassLoader loader) throws Throwable {
		Object hud = InjectorExecution.construct(loader.loadClass(HUD), InjectorExecution.construct(loader.loadClass("net.minecraft.client.Minecraft")));
		InjectorExecution.invoke(hud, "extractRenderState",
				InjectorExecution.construct(loader.loadClass("net.minecraft.client.gui.GuiGraphicsExtractor")),
				InjectorExecution.construct(loader.loadClass("net.minecraft.client.DeltaTracker")));
		return (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "events");
	}

	@Test void aHudThatKeptBothPipelinesDrawsThroughNeoForgesManagerAndLeavesTheBuildToTheKernel(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(Shape.BOTH));
		String internal = HUD.replace('.', '/');
		byte[] edited = InjectorExecution.transform(new ForgeHudPipelineInjector(), HUD, original.get(internal), EnvType.CLIENT);
		assertNotSame(original.get(internal), edited, "both pipelines kept is the shape this edits");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));

		assertEquals(List.of("vanilla layers registered", "neoforge draw"), run(loader),
				"MinecraftForge's own build does not run, and the HUD draws through NeoForge's manager");
		assertEquals(List.of("forge build", "vanilla layers registered", "forge draw"), run(InjectorExecution.load(original)),
				"premise: as merged, MinecraftForge builds its tree with the HUD and its draw returns before NeoForge's");
		assertSame(edited, InjectorExecution.transform(new ForgeHudPipelineInjector(), HUD, edited, EnvType.CLIENT),
				"a second pass finds nothing left to drop");
	}

	@Test void aHudWithOnlyNeoForgesPipelineIsLeftAlone(@TempDir Path work) throws Throwable {
		byte[] bytes = InjectorExecution.compile(work, standIns(Shape.NEOFORGE_ONLY)).get(HUD.replace('.', '/'));
		assertSame(bytes, InjectorExecution.transform(new ForgeHudPipelineInjector(), HUD, bytes, EnvType.CLIENT));
	}

	@Test void aHudWhoseOnlyPipelineIsMinecraftForgesKeepsIt(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, standIns(Shape.FORGE_ONLY));
		byte[] bytes = original.get(HUD.replace('.', '/'));
		assertSame(bytes, InjectorExecution.transform(new ForgeHudPipelineInjector(), HUD, bytes, EnvType.CLIENT),
				"without NeoForge's manager there is no other pipeline to keep the HUD on");
		assertEquals(List.of("forge build", "vanilla layers registered", "forge draw"), run(InjectorExecution.load(original)));
	}

	/**
	 * The staged base, whichever it is: after the transform, a Hud that draws through NeoForge's manager names
	 * MinecraftForge's tree nowhere — not on a base that never carried it, and not on one that spliced it in.
	 */
	@Test void theStagedHudDrawsThroughOnePipeline() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in;
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(HUD.replace('.', '/') + ".class");
			assertNotNull(entry, "the staged base has no Hud");
			try (InputStream stream = zip.getInputStream(entry)) {
				in = stream.readAllBytes();
			}
		}
		byte[] out = new ForgeHudPipelineInjector().transform(HUD, in, null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		int neoDraws = 0, forgeCalls = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if ("net/neoforged/neoforge/client/gui/GuiLayerManager".equals(call.owner) && "render".equals(call.name)) neoDraws++;
				if ("net/minecraftforge/client/gui/overlay/ForgeLayeredDraw".equals(call.owner)) forgeCalls++;
			}
			new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name, m);
		}
		assertTrue(neoDraws > 0, "the staged Hud draws through NeoForge's layer manager");
		assertEquals(0, forgeCalls, "and, transformed, never builds or draws MinecraftForge's tree itself");
	}
}
