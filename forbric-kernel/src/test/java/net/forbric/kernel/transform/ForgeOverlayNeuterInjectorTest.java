/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * Runs {@link ForgeOverlayNeuterInjector}'s output: a stand-in {@code ForgeLayeredDraw} whose {@code resolveLayers}
 * posts a registration event and then bakes, entered by a recording stand-in of the kernel hook.
 *
 * <p>The point of the seam is WHEN the hook runs, so the stand-ins are built to make that visible. The hook stand-in
 * silences every {@code minecraft:} leaf it finds; the event adds a mod layer and records what it saw of the vanilla
 * one; the bake copies the map. Entered at the head, the event already sees the no-op and the bake holds it, while the
 * mod's layer, which did not exist yet, survives. Any later entry point shows the live vanilla leaf in the bake.
 *
 * <p>The real {@code KernelForgeOverlayLayers} is game-side and absent from the test classpath; the stand-in is
 * defined child-first under its name, so the transformed class links against it exactly as it would in game.
 */
@ExecutesInjector(ForgeOverlayNeuterInjector.class)
class ForgeOverlayNeuterInjectorTest {
	private static final String DRAW = "net/minecraftforge/client/gui/overlay/ForgeLayeredDraw";
	private static final String DRAW_BINARY = "net.minecraftforge.client.gui.overlay.ForgeLayeredDraw";
	private static final String HOOK_BINARY = "net.forbric.kernel.runtime.KernelForgeOverlayLayers";

	private static final String DRAW_SOURCE = """
			package net.minecraftforge.client.gui.overlay;

			import java.util.ArrayList;
			import java.util.LinkedHashMap;
			import java.util.List;
			import java.util.Map;

			public class ForgeLayeredDraw {
				public final Map<String, String> namedLayers = new LinkedHashMap<>();
				public final List<String> order = new ArrayList<>();
				public final List<String> bakedLayers = new ArrayList<>();
				public final List<String> eventSaw = new ArrayList<>();

				public ForgeLayeredDraw() {
					namedLayers.put("minecraft:hotbar", "vanilla hotbar");
					order.add("minecraft:hotbar");
				}

				public ForgeLayeredDraw resolveLayers() {
					if (!order.isEmpty()) {
						// The registration event: a listener reads the vanilla leaf and adds its own layer.
						eventSaw.add(namedLayers.get("minecraft:hotbar"));
						namedLayers.put("xaerohud:hud", "mod hud");
						order.add("xaerohud:hud");
						// The bake: names resolved to whatever the map holds now.
						for (String name : order) bakedLayers.add(namedLayers.get(name));
						order.clear();
					}
					return this;
				}

				// Same name, other descriptor: not the anchor, so never entered.
				public void resolveLayers(String reason) {
					eventSaw.add("overload " + reason);
				}
			}
			""";

	private static final String HOOK_SOURCE = """
			package net.forbric.kernel.runtime;

			import java.util.ArrayList;
			import java.util.List;

			import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;

			public final class KernelForgeOverlayLayers {
				public static final List<Object> ROOTS = new ArrayList<>();

				public static void neuterVanillaLeaves(Object root) {
					ROOTS.add(root);
					((ForgeLayeredDraw) root).namedLayers.replaceAll(
							(name, layer) -> name.startsWith("minecraft:") ? "nothing" : layer);
				}
			}
			""";

	@Test void theHookRunsBeforeTheEventAndTheBake(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = standIns(work);
		byte[] transformed = transform(original.get(DRAW));
		assertNotSame(original.get(DRAW), transformed, "the injector did not match its own anchor");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(DRAW, transformed);

		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(transformed, loader));
		Object tree = InjectorExecution.construct(loader.loadClass(DRAW_BINARY));
		assertSame(tree, InjectorExecution.invoke(tree, "resolveLayers"), "resolveLayers must still return this");

		assertEquals(List.of(tree), roots(loader), "the hook must be handed the tree itself, once");
		assertEquals(List.of("nothing"), field(tree, "eventSaw"), "the event must see the leaf already neutered");
		assertEquals(List.of("nothing", "mod hud"), field(tree, "bakedLayers"),
				"the bake must hold the no-op for vanilla and leave the layer a mod added during the event alone");

		// A second call enters again; the hook is the one deciding what a repeat means, not the seam.
		InjectorExecution.invoke(tree, "resolveLayers");
		assertEquals(List.of(tree, tree), roots(loader));

		InjectorExecution.invoke(tree, "resolveLayers", "other");
		assertEquals(List.of(tree, tree), roots(loader), "an overload with another descriptor was entered");
	}

	@Test void untransformedTheVanillaLeafIsBakedLive(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = standIns(work);
		ClassLoader loader = InjectorExecution.load(original);
		Object tree = InjectorExecution.construct(loader.loadClass(DRAW_BINARY));
		InjectorExecution.invoke(tree, "resolveLayers");

		assertEquals(List.of(), roots(loader));
		assertEquals(List.of("vanilla hotbar"), field(tree, "eventSaw"));
		assertEquals(List.of("vanilla hotbar", "mod hud"), field(tree, "bakedLayers"));
	}

	/** The pre-mixin read and the define both offer the class; the second pass must not add a second call. */
	@Test void aSecondPassAddsNothing(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = standIns(work);
		byte[] once = transform(original.get(DRAW));
		byte[] twice = transform(once);
		assertSame(once, twice);

		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(DRAW, twice);
		ClassLoader loader = InjectorExecution.load(classes);
		Object tree = InjectorExecution.construct(loader.loadClass(DRAW_BINARY));
		InjectorExecution.invoke(tree, "resolveLayers");
		assertEquals(List.of(tree), roots(loader));
	}

	/** {@code resolveLayers} returns the tree for chaining; a {@code ()V} one is not the anchor. */
	@Test void aVoidResolveLayersIsNotTheAnchor(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, Map.of(DRAW_BINARY, """
				package net.minecraftforge.client.gui.overlay;
				public class ForgeLayeredDraw {
					public void resolveLayers() {
					}
				}
				""")).get(DRAW);
		assertSame(bytes, transform(bytes));
	}

	@Test void anotherClassWithTheSameMethodIsLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, Map.of("net.minecraftforge.client.gui.overlay.OtherDraw", """
				package net.minecraftforge.client.gui.overlay;
				public class OtherDraw {
					public ForgeLayeredDraw resolveLayers() {
						return null;
					}
				}
				""", DRAW_BINARY, DRAW_SOURCE));
		byte[] bytes = classes.get("net/minecraftforge/client/gui/overlay/OtherDraw");
		assertSame(bytes, InjectorExecution.transform(new ForgeOverlayNeuterInjector(),
				"net.minecraftforge.client.gui.overlay.OtherDraw", bytes, EnvType.CLIENT));
	}

	/**
	 * The leaves the kernel silences, read off a builder whose names are unrelated to any game: every lambda of the class
	 * registered under one of its own name constants in the static build method is a silenced leaf; the condition handed
	 * beside it (a {@code java.*} type), a layer registered under a name built at run time, and a lambda of the build method
	 * that is never registered are not.
	 */
	@Test void theSilencedLeavesAreTheLambdasRegisteredUnderTheTreesOwnNames(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, Map.of("org.tapis.Frame", """
				package org.tapis;
				public class Frame {
					public interface Leaf { void paint(Object canvas); }
					public record Name(String text) { }
					public static final Name SKY = new Name("sky"), SEA = new Name("sea");
					public Frame put(Name name, Leaf leaf) { return this; }
					public Frame putWhen(Name name, Leaf leaf, java.util.function.BooleanSupplier when) { return this; }
					public static void init(Object host) {
						Frame root = new Frame();
						root.put(SKY, canvas -> paintSky(host));
						root.putWhen(SEA, canvas -> paintSea(host), () -> host != null);
						root.put(new Name("mod"), canvas -> paintMod(host));
						Leaf spare = canvas -> paintSpare(host);
					}
					static void paintSky(Object host) { }
					static void paintSea(Object host) { }
					static void paintMod(Object host) { }
					static void paintSpare(Object host) { }
				}
				"""));
		org.objectweb.asm.tree.ClassNode frame = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(classes.get("org/tapis/Frame")).accept(frame, 0);
		java.util.Set<String> silenced = ForgeOverlayNeuterInjector.silencedLeafBodies(frame);
		java.util.List<String> bodies = new java.util.ArrayList<>();
		for (String key : silenced) {
			org.objectweb.asm.tree.MethodNode body = frame.methods.stream().filter(m -> key.equals(frame.name + "#" + m.name + m.desc)).findFirst().orElseThrow();
			for (var insn : body.instructions) if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) bodies.add(call.name);
		}
		assertEquals(List.of("paintSea", "paintSky"), bodies.stream().sorted().toList(), "silenced: " + silenced);
	}

	private static Map<String, byte[]> standIns(Path work) throws Exception {
		return InjectorExecution.compile(work, Map.of(DRAW_BINARY, DRAW_SOURCE, HOOK_BINARY, HOOK_SOURCE));
	}

	private static byte[] transform(byte[] bytes) {
		return InjectorExecution.transform(new ForgeOverlayNeuterInjector(), DRAW_BINARY, bytes, EnvType.CLIENT);
	}

	private static Object roots(ClassLoader loader) throws ReflectiveOperationException {
		return InjectorExecution.getStatic(loader.loadClass(HOOK_BINARY), "ROOTS");
	}

	private static Object field(Object tree, String name) throws ReflectiveOperationException {
		return tree.getClass().getField(name).get(tree);
	}
}
