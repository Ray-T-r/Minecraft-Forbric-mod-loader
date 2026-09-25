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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A step that is ABSENT and a step that THREW must not be reported the same way.
 *
 * <p>{@code invokeGameDataOn} drives the registry bake, the freeze/unfreeze pair,
 * {@code CommonHooks.modifyAttributes}, {@code SpawnPlacements.fireSpawnPlacementEvent} and
 * {@code GameRuleCategory.registerModdedCategories}; {@code startGameBuses} opens both families' game buses.
 * Both used to catch Throwable straight into {@code ForbricLog.debug}, which is off unless
 * {@code -Dforbric.debug} is set — so "one mod's attribute handler threw and every mod after it in the list
 * lost its attributes" and "the Forge game bus never opened" were both invisible.
 *
 * <p>These are bytecode-shape tests because both methods only do anything against a live carrier: what is
 * assertable off-game is which log level each failure path reaches, and that is exactly what was wrong.
 */
class KernelLifecycleFailureReportingTest {
	@Test
	void anInvocationThatThrewIsWarnedAboutRatherThanDebugged() throws Exception {
		List<String> levels = logLevelsIn("invokeGameDataOn");
		assumeTrue(!levels.isEmpty(), "KernelLifecycle not compiled yet");
		assertEquals("warn", levels.get(levels.size() - 1),
				"the LAST reporting path in invokeGameDataOn is the one where the hook exists and threw, and it must "
						+ "WARN — every caller is a whole feature (the registry bake, the freeze, the attribute and "
						+ "spawn-placement events), and a debug line for those is the same as silence");
		assertTrue(levels.contains("debug"),
				"a class that is simply not on this runtime is ordinary — only one Forge family may be present — "
						+ "and stays at debug");
	}

	@Test
	void aBusThatFailedToStartIsWarnedAboutRatherThanDebugged() throws Exception {
		List<String> starter = logLevelsIn("startBus");
		assumeTrue(!starter.isEmpty(), "KernelLifecycle not compiled yet");
		assertEquals("warn", starter.get(starter.size() - 1),
				"the LAST reporting path in startBus is the bus that is present and failed to start, which leaves "
						+ "every game-event listener of that family, in every mod, on a bus nothing dispatches — "
						+ "that cannot be a debug line");
		assertTrue(starter.contains("debug"),
				"a single-family instance legitimately has only one of the two buses; that stays at debug");
	}

	/**
	 * One mod's listener must not cost the others their delivery. NeoForge's own {@code ModLoader.postEvent}
	 * rethrows the first failure, which is correct for a loader that then shows an error screen and stops — the
	 * kernel has no such screen and carries on booting, so it dispatches per container instead.
	 */
	@Test
	void modBusEventsAreDeliveredPerContainerRatherThanThroughTheAbortingFanOut() throws Exception {
		ClassNode node = compiled();
		assumeTrue(node != null, "KernelLifecycle not compiled yet");
		MethodNode post = node.methods.stream().filter(m -> "postModBusEvent".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("postModBusEvent is gone"));

		boolean usesFanOut = false;
		boolean acceptsPerContainer = false;
		for (AbstractInsnNode insn : post.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if ("getMethod".equals(call.name) || "invoke".equals(call.name)) continue;
			if ("postEvent".equals(call.name)) usesFanOut = true;
		}
		for (AbstractInsnNode insn : post.instructions.toArray()) {
			if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "acceptEvent".equals(ldc.cst)) {
				acceptsPerContainer = true;
			}
			if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "postEvent".equals(ldc.cst)) {
				usesFanOut = true;
			}
		}
		assertFalse(usesFanOut,
				"postModBusEvent must not go through ModLoader.postEvent: it aborts at the first mod whose listener "
						+ "throws, so every mod after it in the list silently never receives the event");
		assertTrue(acceptsPerContainer,
				"postModBusEvent must call ModContainer.acceptEvent per published container, so one mod's failure "
						+ "costs only that mod");
	}

	/**
	 * Phase by phase, as ModLoader.postEvent: every mod's HIGHEST before any mod's HIGH. One container with all its
	 * phases let an earlier mod's LOWEST tooltip appender register before a later mod's HIGHEST one.
	 */
	@Test
	void modBusEventsAreDeliveredPhaseByPhase() throws Exception {
		ClassNode node = compiled();
		assumeTrue(node != null, "KernelLifecycle not compiled yet");
		MethodNode deliver = node.methods.stream().filter(m -> "deliverModBusEvent".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("deliverModBusEvent is gone"));
		boolean priority = false;
		for (AbstractInsnNode insn : deliver.instructions.toArray()) {
			if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "net.neoforged.bus.api.EventPriority".equals(ldc.cst)) priority = true;
		}
		assertTrue(priority, "deliverModBusEvent must use ModContainer.acceptEvent(EventPriority, Event)");
	}

	private static ClassNode compiled() throws Exception {
		Path file = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(file)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, 0);
		return node;
	}

	/** The ForbricLog methods one kernel method calls, in order — its failure-reporting shape. */
	private static List<String> logLevelsIn(String method) throws Exception {
		ClassNode node = compiled();
		if (node == null) return List.of();
		MethodNode m = node.methods.stream().filter(x -> method.equals(x.name)).findFirst()
				.orElseThrow(() -> new AssertionError(method + " is gone"));
		List<String> levels = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call
					&& "net/forbric/kernel/util/ForbricLog".equals(call.owner)) {
				levels.add(call.name);
			}
		}
		return levels;
	}
}
