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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The inventory has to match what the multiplexer actually installs, in both directions.
 *
 * <p>The enum exists so the installer can check what it achieved against what was required. That only works if
 * the two lists agree: a bridge declared here and never installed is a permanent "not installed" warning, and a
 * bridge installed without being declared is one the verify pass cannot notice is missing.
 */
class GameEventBridgeInventoryTest {
	@Test
	void everyGameBusBridgeInTheInventoryIsActuallyInstalled() throws Exception {
		Set<GameEventBridge> declared = EnumSet.noneOf(GameEventBridge.class);
		for (GameEventBridge bridge : GameEventBridge.values()) {
			if (bridge.pass() == GameEventBridge.Pass.GAME_BUS) declared.add(bridge);
		}

		List<String> installed = bridgesNamedBy("install");
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		List<String> missing = new ArrayList<>();
		for (GameEventBridge bridge : declared) {
			if (!installed.contains(bridge.name())) missing.add(bridge.name());
		}
		assertEquals(List.of(), missing,
				"every GAME_BUS bridge the inventory declares must be installed by GameEventMultiplexer.install, or "
						+ "EventBridges.verify reports it missing on every single boot");
	}

	/**
	 * No bridge falls between the checks.
	 *
	 * <p>The check above covers GAME_BUS, and several below name particular bridges. Counted across the whole
	 * enum that left exactly one — GUI_OVERLAY_LAYERS, on CLIENT_HUD — covered by neither: declared, and nothing
	 * anywhere asserting it is reached. One is how many it takes, and the next pass someone adds would be the
	 * second, so this closes the set instead of adding another name to a list.
	 *
	 * <p>A bridge qualifies by being installed by the multiplexer, or by being late-installed — which the
	 * transformer that lands it records, and which {@code everyLatePassBridgeIsRecordedByTheTransformerThatLandsIt}
	 * is the check for.
	 */
	@Test
	void noBridgeIsCoveredByNeitherCheck() throws Exception {
		// Anywhere in the multiplexer, not just in install(): CLIENT_RELOAD_LISTENERS is recorded from a
		// different method of the same class, and which method does it is an implementation detail. Asking only
		// about install() reported it as uncovered when it is not — the first version of this test did exactly
		// that, which is the same shape as asserting on where a line is printed rather than on what is true.
		List<String> installed = bridgesNamedAnywhereInTheMultiplexer();
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		List<String> uncovered = new ArrayList<>();
		for (GameEventBridge bridge : GameEventBridge.values()) {
			if (installed.contains(bridge.name())) continue;
			if (bridge.pass().lateInstalled()) continue;
			uncovered.add(bridge.name() + " (" + bridge.pass() + ")");
		}
		assertEquals(List.of(), uncovered,
				"these bridges are declared and nothing asserts they are ever reached: " + uncovered);
	}

	/**
	 * The two hooks that were absent from the inventory until the audit found them. Naming them explicitly means a
	 * future edit that drops one has to argue with a test rather than quietly shrink the set.
	 */
	@Test
	void theServerStartingAndStoppedHooksAreBridged() throws Exception {
		List<String> installed = bridgesNamedBy("install");
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		assertTrue(installed.contains("SERVER_STARTING"),
				"MinecraftForge's handleServerStarting is the only thing that calls "
						+ "PermissionAPI.initializePermissionAPI, so without this bridge every permission question a "
						+ "Forge mod asks NPEs inside Forge's own API");
		assertTrue(installed.contains("SERVER_STOPPED"),
				"MinecraftForge's handleServerStopped is what unloads a per-world SERVER config, so without this "
						+ "bridge a second world opened in the same session reads the first world's values and each "
						+ "world leaks another file watcher");
	}

	/**
	 * Only the SERVER tick was ever bridged, and that is what made the gap invisible: ticking looked healthy in
	 * every log and every gate while a Forge mod's per-level and per-player work never ran, and while its key
	 * bindings did nothing when pressed (consumeClick is drained from the CLIENT tick).
	 */
	@Test
	void allFourTicksAreBridgedNotJustTheServerOne() throws Exception {
		List<String> installed = bridgesNamedBy("install");
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		for (String tick : List.of("SERVER_TICK_PRE", "SERVER_TICK_POST", "LEVEL_TICK_PRE", "LEVEL_TICK_POST",
				"PLAYER_TICK_PRE", "PLAYER_TICK_POST", "CLIENT_TICK_PRE", "CLIENT_TICK_POST",
				// The render tick is a fifth pair and a separate hole: a mod can poll keys from the client tick
				// and still build nothing, because only this one drives a map mod's region upload.
				"RENDER_FRAME_PRE", "RENDER_FRAME_POST")) {
			assertTrue(installed.contains(tick),
					tick + " is not installed — the merged base carries only NeoForge's hook for it, so a "
							+ "MinecraftForge mod's listener sits on a bus nobody posts to");
		}
	}

	/**
	 * Commands and the player lifecycle. {@code Commands} on the merged base is 7 NeoForge references to 0
	 * MinecraftForge and {@code PlayerList} is 13 to 0, so without these a Forge mod's commands do not exist —
	 * "Unknown command" for a mod that loaded cleanly — and nothing it does on join, leave, respawn or a
	 * dimension change ever runs.
	 */
	@Test
	void commandsAndThePlayerLifecycleAreBridged() throws Exception {
		List<String> installed = bridgesNamedBy("install");
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		for (String bridge : List.of("REGISTER_COMMANDS", "PLAYER_LOGGED_IN", "PLAYER_LOGGED_OUT",
				"PLAYER_RESPAWN", "PLAYER_CHANGED_DIMENSION")) {
			assertTrue(installed.contains(bridge),
					bridge + " is not installed — the merged base calls only NeoForge's hook at that site, so the "
							+ "MinecraftForge listener sits on a bus nobody posts to");
		}
	}

	/**
	 * The cancellable ones. A MinecraftForge mod cancelling a death, a drop or an entity join is the whole point
	 * of listening, so these are not observers — the forward carries the veto back onto the NeoForge event.
	 */
	@Test
	void theCancellableEntityEventsAreBridged() throws Exception {
		List<String> installed = bridgesNamedBy("install");
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		for (String bridge : List.of("LIVING_DEATH", "LIVING_DROPS", "ENTITY_JOIN_LEVEL")) {
			assertTrue(installed.contains(bridge),
					bridge + " is not installed — a MinecraftForge mod's listener runs, decides and is ignored, "
							+ "which looks like it works");
		}
	}

	/**
	 * The client ticks must be their own pass. They name types in NeoForge's client event package, so a dedicated
	 * server must not resolve them — and as GAME_BUS they would be reported missing on every server boot, which
	 * turns the verify line from a signal into noise.
	 */
	@Test
	void theClientTicksAreVerifiedSeparatelyFromTheServerOnes() {
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.CLIENT_TICK_PRE.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.CLIENT_TICK_POST.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.RENDER_FRAME_PRE.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.RENDER_FRAME_POST.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.SCREEN_MOUSE_PRESSED_PRE.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.SCREEN_MOUSE_RELEASED_PRE.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.SCREEN_MOUSE_DRAG_PRE.pass());
		assertEquals(GameEventBridge.Pass.CLIENT_GAME_BUS, GameEventBridge.SCREEN_MOUSE_SCROLL_POST.pass());
		assertEquals(GameEventBridge.Pass.GAME_BUS, GameEventBridge.PLAYER_TICK_PRE.pass(),
				"the player tick is common to both sides and belongs to the pass a server verifies");
	}

	/**
	 * The screen MOUSE family, which the render-frame bridge does not reach.
	 *
	 * <p>A different producer in a different class: the merged {@code MouseHandler} routes every one of these to
	 * NeoForge's {@code ClientHooks} ({@code onButton} 316/345 and 448/473, {@code handleAccumulatedMovement}
	 * 247/288, {@code onScroll} 172/217), so a mod can be live on the frame and still dead on the mouse.
	 * MouseTweaks is exactly four listeners on exactly these four events and nothing else — with them missing it
	 * loads cleanly, registers cleanly, reports nothing and does nothing.
	 */
	@Test
	void theScreenMouseFamilyIsBridgedNotJustTheFrameAndTheTick() throws Exception {
		List<String> installed = bridgesNamedBy("install");
		assumeTrue(!installed.isEmpty(), "GameEventMultiplexer not compiled yet");

		for (String bridge : List.of("SCREEN_MOUSE_PRESSED_PRE", "SCREEN_MOUSE_RELEASED_PRE",
				"SCREEN_MOUSE_DRAG_PRE", "SCREEN_MOUSE_SCROLL_POST")) {
			assertTrue(installed.contains(bridge),
					bridge + " is not installed — the merged MouseHandler posts only NeoForge's event there, so a "
							+ "MinecraftForge mod's screen-mouse listener sits on a bus nobody posts to");
		}
	}

	/**
	 * None of the CLIENT_GAME_BUS bridges may appear in {@code DeadEventAudit}'s bridged map while the pass is
	 * not {@link GameEventBridge.Pass#lateInstalled()}.
	 *
	 * <p>{@code KernelLifecycle} passes {@code client=false} on a dedicated server, so those bridges are never
	 * installed there — and a bridged row whose pass is not late is a finding exactly when the bridge is absent.
	 * A row for one of these would therefore mark every mod that listens for it DEGRADED on every server boot,
	 * which is the noise that trains a reader to ignore the audit.
	 */
	@Test
	void noClientGameBusBridgeIsAuditedAsBridgedWhileThatPassIsNotLate() throws Exception {
		assumeTrue(!GameEventBridge.Pass.CLIENT_GAME_BUS.lateInstalled(),
				"CLIENT_GAME_BUS is late now, so a bridged row would be safe");
		Path audit = Path.of("src/main/java/net/forbric/kernel/boot/DeadEventAudit.java");
		assumeTrue(Files.exists(audit), "DeadEventAudit source not present");
		String bridged = Files.readString(audit);
		bridged = bridged.substring(bridged.indexOf("private static Map<String, GameEventBridge> bridged()"));
		bridged = bridged.substring(0, bridged.indexOf("\n\tprivate DeadEventAudit()"));
		for (GameEventBridge bridge : GameEventBridge.values()) {
			if (bridge.pass() != GameEventBridge.Pass.CLIENT_GAME_BUS) continue;
			assertTrue(!bridged.contains("GameEventBridge." + bridge.name()),
					bridge + " is a CLIENT_GAME_BUS bridge and must not be a DeadEventAudit BRIDGED row: a "
							+ "dedicated server never installs it, so every mod listening for its event would be "
							+ "named DEGRADED on every server boot");
		}
	}

	/** Every bridge has to say what it costs; a count that is short is not a diagnosis. */
	@Test
	void everyBridgeStatesWhatThePlayerLoses() {
		for (GameEventBridge bridge : GameEventBridge.values()) {
			assertTrue(bridge.cost() != null && bridge.cost().length() > 40,
					bridge + " must state what a player loses when it is not installed — that sentence is the whole "
							+ "reason this is an enum and not a count");
		}
	}

	/**
	 * The late passes are landed by class transformers, not the multiplexer, and a transformer that lands its
	 * redirect without recording the bridge makes {@code EventBridges.verify} report it MISSING on every boot —
	 * the noise that turns the line from a signal into wallpaper. So every late bridge must be recorded right
	 * where the redirect is written: a GameEventBridge read immediately followed by {@code EventBridges.installed}.
	 */
	@Test
	void everyLatePassBridgeIsRecordedByTheTransformerThatLandsIt() throws Exception {
		Set<String> recorded = new java.util.LinkedHashSet<>();
		for (String transformer : List.of("ForbricMergedBaseCompatTransformer", "ForgeBlockTintInjector",
				"ForgeCreativeTabsInjector", "ForgeSpawnPlacementsInjector")) {
			recorded.addAll(bridgesRecordedBy(compiled("transform", transformer)));
		}
		// A transformer may land only the SEAM and leave the recording to the game-side class it routes to — which
		// is the honest place for it when the install can still fail after the redirect is in the bytecode.
		// HudElementBridgeInjector appends the call; KernelForgeOverlayLayers is what knows whether the stack
		// actually went on.
		recorded.addAll(bridgesRecordedBy(runtimeCompiled("KernelForgeOverlayLayers")));
		// The tooltip seam is the same shape: the transformer writes the call, and the game-side class is the only
		// place that knows a tooltip was really built and the event really posted.
		recorded.addAll(bridgesRecordedBy(runtimeCompiled("KernelItemTooltips")));
		assumeTrue(!recorded.isEmpty(), "transformers not compiled yet");

		List<String> missing = new ArrayList<>();
		for (GameEventBridge bridge : GameEventBridge.values()) {
			if (bridge.pass().lateInstalled() && !recorded.contains(bridge.name())) missing.add(bridge.name());
		}
		assertEquals(List.of(), missing,
				"every late-pass bridge must be passed to EventBridges.installed by the transformer that lands its "
						+ "redirect, or the verify line reports it missing on every boot");
	}

	/** A class from the GAME-side output set, which links against the carriers and so compiles separately. */
	private static Path runtimeCompiled(String simpleName) {
		return Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
				"net", "forbric", "kernel", "runtime", simpleName + ".class");
	}

	private static Path compiled(String pkg, String simpleName) {
		return Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", pkg, simpleName + ".class");
	}

	/** The GameEventBridge constants one method of GameEventMultiplexer reads, in order. */
	/** Every bridge the multiplexer names, in any of its methods. */
	private static List<String> bridgesNamedAnywhereInTheMultiplexer() throws Exception {
		Path compiled = compiled("boot", "GameEventMultiplexer");
		if (!Files.isRegularFile(compiled)) return List.of();
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> names = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof FieldInsnNode field
						&& "net/forbric/api/GameEventBridge".equals(field.owner)
						&& !names.contains(field.name)) {
					names.add(field.name);
				}
			}
		}
		return names;
	}

	private static List<String> bridgesNamedBy(String method) throws Exception {
		Path compiled = compiled("boot", "GameEventMultiplexer");
		if (!Files.isRegularFile(compiled)) return List.of();

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> names = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if (!method.equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof FieldInsnNode field
						&& "net/forbric/api/GameEventBridge".equals(field.owner)
						&& !names.contains(field.name)) {
					names.add(field.name);
				}
			}
		}
		return names;
	}

	/** GameEventBridge constants a compiled class hands straight to {@code EventBridges.installed}. */
	private static List<String> bridgesRecordedBy(Path compiled) throws Exception {
		if (!Files.isRegularFile(compiled)) return List.of();
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> names = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (!(insn instanceof FieldInsnNode field) || !"net/forbric/api/GameEventBridge".equals(field.owner)) continue;
				AbstractInsnNode next = insn.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				if (next instanceof MethodInsnNode call && "net/forbric/api/EventBridges".equals(call.owner)
						&& "installed".equals(call.name)) {
					names.add(field.name);
				}
			}
		}
		return names;
	}
}
