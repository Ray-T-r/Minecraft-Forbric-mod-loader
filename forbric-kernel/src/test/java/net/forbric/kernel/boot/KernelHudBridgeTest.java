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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the table that maps NeoForge's HUD layers onto Fabric's HUD roots, and the tripwires that stop it from
 * being "completed" into a double render.
 *
 * <p>The mapping is the whole risk surface of the HUD bridge. Every failure mode it has is visual and silent —
 * there is no log line for an element drawn twice — so the assertions here stand in for a check a human would
 * otherwise have to make by looking at the screen.
 */
class KernelHudBridgeTest {
	private static final Path NEOFORGE_RUNTIME =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "neoforge-runtime",
					"neoforge-runtime.jar").normalize();
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final Path CLIENT_MODS =
			Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();

	/**
	 * Left out on purpose. Each would be a double render or a mis-pairing:
	 * <ul>
	 *   <li>{@code subtitle_overlay} — Fabric's {@code subtitles} root is already dispatched by
	 *       {@code SubtitleOverlayMixin} on the untouched vanilla {@code SubtitleOverlay}. Bridging it renders
	 *       every {@code addLast} element twice.</li>
	 *   <li>{@code after_camera_decorations} — a stratum separator, no elements.</li>
	 *   <li>{@code contextual_info_bar} — Fabric's {@code INFO_BAR} pairs with the BACKGROUND layer instead.</li>
	 * </ul>
	 */
	private static final Set<String> DELIBERATELY_UNMAPPED =
			Set.of("after_camera_decorations", "contextual_info_bar", "subtitle_overlay");

	@Test
	void everyEntryIsANamespacedVanillaLayerId() {
		Map<String, String[]> mapping = KernelHudBridge.mapping();
		assertFalse(mapping.isEmpty());
		for (String id : mapping.keySet()) {
			assertTrue(id.startsWith("minecraft:"),
					id + " must be the full namespace:path — keying on the path alone would let a mod's own "
							+ "foo:crosshair match the vanilla crosshair layer");
		}
	}

	@Test
	void noFabricRootIsBridgedTwice() {
		// Two NeoForge layers both nesting the same Fabric root would render its elements once per layer.
		List<String> all = new ArrayList<>();
		for (String[] roots : KernelHudBridge.mapping().values()) all.addAll(List.of(roots));
		assertEquals(all.size(), new HashSet<>(all).size(), "a Fabric root may be wrapped by exactly one layer: " + all);
	}

	@Test
	void subtitlesIsNotBridged() {
		// The single most important assertion in this file. fabric-rendering-v1 dispatches the `subtitles` root
		// through SubtitleOverlayMixin, which targets vanilla's SubtitleOverlay — a class the merge did not touch,
		// so that root already works. Adding minecraft:subtitle_overlay here renders every addLast element twice,
		// and nothing in any log would say so.
		for (String[] roots : KernelHudBridge.mapping().values()) {
			assertFalse(List.of(roots).contains("SUBTITLES"),
					"SUBTITLES is dispatched by SubtitleOverlayMixin already — bridging it double-renders");
		}
		assertFalse(KernelHudBridge.mapping().containsKey("minecraft:subtitle_overlay"));
	}

	@Test
	void everyKeyIsARealNeoForgeLayerAndEveryValueARealFabricConstant() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");
		Path fabricApi = fabricApiJar();
		assumeTrue(fabricApi != null, "fabric-api jar absent from the client mods dir");

		Set<String> layerIds = idConstants(readFromJar(NEOFORGE_RUNTIME,
				"net/neoforged/neoforge/client/gui/VanillaGuiLayers.class"));
		Set<String> elementFields = staticFieldNames(readFromNestedJar(fabricApi, "fabric-rendering-v1",
				"net/fabricmc/fabric/api/client/rendering/v1/hud/VanillaHudElements.class"));

		for (String key : KernelHudBridge.mapping().keySet()) {
			String path = key.substring(key.indexOf(':') + 1);
			assertTrue(layerIds.contains(path), key + " is not a VanillaGuiLayers id — the NeoForge side drifted");
		}
		for (String[] roots : KernelHudBridge.mapping().values()) {
			for (String field : roots) {
				assertTrue(elementFields.contains(field),
						field + " is not a VanillaHudElements constant — the Fabric side drifted");
			}
		}
	}

	@Test
	void theUnmappedLayersAreExactlyTheOnesWeDecidedToLeaveOut() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		Set<String> layerIds = idConstants(readFromJar(NEOFORGE_RUNTIME,
				"net/neoforged/neoforge/client/gui/VanillaGuiLayers.class"));
		Set<String> unmapped = new TreeSet<>();
		for (String path : layerIds) {
			if (!KernelHudBridge.mapping().containsKey("minecraft:" + path)) unmapped.add(path);
		}

		// Forces whoever adds a mapping to come here and think about whether it double-renders, rather than
		// quietly filling in a gap.
		assertEquals(new TreeSet<>(DELIBERATELY_UNMAPPED), unmapped,
				"the set of unbridged vanilla layers changed — see KernelHudBridge's javadoc before mapping one");
	}

	@Test
	void hudMixinsResolvedAnchorsAreStillInDeadCode() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");

		// HudMixin resolves 7 of its anchors, all inside Hud.extractHotbarAndDecorations. That method is orphaned on
		// this base — nothing calls it — which is the entire reason the bridge cannot double-render against them.
		byte[] hud = readFromJar(MERGED_BASE, "net/minecraft/client/gui/Hud.class");
		ClassNode node = new ClassNode();
		new ClassReader(hud).accept(node, 0);

		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && "extractHotbarAndDecorations".equals(call.name)) {
					throw new AssertionError("Hud.extractHotbarAndDecorations is called again (from " + method.name
							+ ") — HudMixin's 7 resolved anchors are live, so hotbar, vehicle_health, "
							+ "contextual_info_bar_background, experience_level, selected_item_name and "
							+ "spectator_tooltip would now render twice. Re-check the bridge table before shipping.");
				}
			}
		}
	}

	@Test
	void withoutFabricRenderingTheLayerIsReturnedByIdentity() {
		// No loader bound: the registry cannot resolve, so the render path must be exactly what it is today —
		// same object, nothing allocated, no per-frame cost.
		KernelHudBridge.bind(null);
		Object layer = new Object();
		assertSame(layer, KernelHudBridge.wrap(new FakeId("minecraft:crosshair"), layer));
		assertSame(layer, KernelHudBridge.wrap(new FakeId("minecraft:not_a_layer"), layer));
		assertSame(layer, KernelHudBridge.wrap(null, layer));
	}

	@Test
	void offReturnsTheLayerUntouched() {
		System.setProperty("forbric.hudBridge", "off");
		try {
			assertFalse(KernelHudBridge.enabled());
			Object layer = new Object();
			assertSame(layer, KernelHudBridge.wrap(new FakeId("minecraft:crosshair"), layer));
		} finally {
			System.clearProperty("forbric.hudBridge");
		}
	}

	/** Stands in for {@code Identifier}, whose {@code toString()} is {@code namespace:path}. */
	private record FakeId(String id) {
		@Override
		public String toString() {
			return id;
		}
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/** The string constants a {@code VanillaGuiLayers}-style holder builds its ids from. */
	private static Set<String> idConstants(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		Set<String> ids = new HashSet<>();
		for (MethodNode m : node.methods) {
			if (!"<clinit>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) ids.add(s);
			}
		}
		return ids;
	}

	private static Set<String> staticFieldNames(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		Set<String> names = new HashSet<>();
		for (MethodNode m : node.methods) {
			if (!"<clinit>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)) names.add(field.name);
			}
		}
		return names;
	}

	private static Path fabricApiJar() throws Exception {
		if (!Files.isDirectory(CLIENT_MODS)) return null;
		try (var files = Files.list(CLIENT_MODS)) {
			return files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
		}
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	/** fabric-api ships its modules as jar-in-jar; pull one class out of the named nested module. */
	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}
}
