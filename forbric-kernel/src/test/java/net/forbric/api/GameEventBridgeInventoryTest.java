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
				"PLAYER_TICK_PRE", "PLAYER_TICK_POST", "CLIENT_TICK_PRE", "CLIENT_TICK_POST")) {
			assertTrue(installed.contains(tick),
					tick + " is not installed — the merged base carries only NeoForge's hook for it, so a "
							+ "MinecraftForge mod's listener sits on a bus nobody posts to");
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
		assertEquals(GameEventBridge.Pass.GAME_BUS, GameEventBridge.PLAYER_TICK_PRE.pass(),
				"the player tick is common to both sides and belongs to the pass a server verifies");
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

	/** The GameEventBridge constants one method of GameEventMultiplexer reads, in order. */
	private static List<String> bridgesNamedBy(String method) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "GameEventMultiplexer.class");
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
}
