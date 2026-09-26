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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A Fabric mod's config events must reach the porting layer that a Fabric mod's callbacks are registered with.
 *
 * <p>The container the kernel gives such a mod carried a null event bus, and the method that delivers a config
 * event returns immediately when the bus is null. So every "my config loaded" and "my config changed on disk"
 * callback a mod registered through that API was dropped in silence — which is the entire feature.
 *
 * <h2>Why this is a shape test</h2>
 *
 * <p>Honestly: this class is game-side, compiled against the carrier jars, so it cannot be instantiated in a
 * unit test. And no pack in any gate registers a config through the porting layer — the Fabric mods in them use
 * other config libraries — so there is no live boot that exercises it either. A purpose-built Fabric canary
 * calling that API would be the real proof and does not exist yet.
 *
 * <p>What is assertable is the compiled shape: that the container is built with a bus rather than a null, and
 * that all three config events are forwarded to the porting layer's three entry points.
 */
class ConfigPortBridgeForwardingTest {

	private static ClassNode bridge() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
				"net", "forbric", "kernel", "runtime", "KernelConfigPortBridge.class");
		assumeTrue(Files.isRegularFile(compiled), "game-side classes not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		return node;
	}

	@Test
	void theContainerIsBuiltWithABusRatherThanANull() throws Exception {
		ClassNode node = bridge();

		boolean buildsBus = false;
		boolean buildsContainer = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.endsWith("BusBuilder") && "builder".equals(call.name)) buildsBus = true;
				if (call.owner.endsWith("KernelContainers") && "container".equals(call.name)) buildsContainer = true;
			}
		}

		assertTrue(buildsContainer, "the bridge no longer builds a container at all");
		assertTrue(buildsBus,
				"the container is built without an event bus, and the method that delivers a config event returns "
						+ "immediately when the bus is null — so every callback is dropped in silence");
	}

	@Test
	void bothRegistrationFormsOpenTheConfigRightAfterRegisteringIt() throws Exception {
		ClassNode node = bridge();
		int forms = 0;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("registerConfig")) continue;
			forms++;
			int registered = -1, opened = -1, index = 0;
			for (AbstractInsnNode insn : method.instructions) {
				index++;
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.endsWith("ConfigTracker") && call.name.equals("registerConfig")) registered = index;
				if (call.owner.endsWith("KernelConfigLoad") && call.name.equals("openAtRegistration")) opened = index;
				assertTrue(!call.name.equals("loadConfigs"), "a whole-type load from the bridge opens other mods' configs");
			}
			// Traveler's Backpack registers COMMON and reads it in the same onInitialize: the port opens it there.
			assertTrue(registered > 0 && opened > registered, method.desc + " opens after registering");
		}
		assertTrue(forms >= 2, "both the 3-arg and the 4-arg forms");
	}

	@Test
	void allThreeConfigEventsAreForwarded() throws Exception {
		ClassNode node = bridge();

		Set<String> events = new LinkedHashSet<>();
		Set<String> sinks = new LinkedHashSet<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof LdcInsnNode ldc)) continue;
				if (ldc.cst instanceof Type type && type.getClassName().contains("ModConfigEvent$")) {
					events.add(type.getClassName().substring(type.getClassName().indexOf('$') + 1));
				}
				if (ldc.cst instanceof String text
						&& (text.equals("onLoading") || text.equals("onReloading") || text.equals("onUnloading"))) {
					sinks.add(text);
				}
			}
		}

		// Loading is the one mods use most, but a config edited on disk while the game runs fires Reloading and
		// nothing else — that is the case a mod's live-reload support depends on entirely.
		assertTrue(events.containsAll(Set.of("Loading", "Reloading", "Unloading")),
				"not every config event is forwarded; found " + events);
		assertTrue(sinks.containsAll(Set.of("onLoading", "onReloading", "onUnloading")),
				"not every porting-layer entry point is named; found " + sinks);
	}

	@Test
	void thePortingLayerIsReachedByNameSoItCanBeAbsent() throws Exception {
		// The porting layer is a MOD. Linking against it would make the game side fail to load without it, and
		// most packs do not have it.
		ClassNode node = bridge();

		boolean byName = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text
						&& text.startsWith("fuzs.forgeconfigapiport")) {
					byName = true;
				}
			}
		}

		assertTrue(byName, "the porting layer must be named as a string and resolved at runtime, not linked");
	}
}
