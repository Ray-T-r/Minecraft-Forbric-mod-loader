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

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The MinecraftForge half of the setup lifecycle must be reachable on a pack that has no NeoForge mod.
 *
 * <p>This is a shape test on the compiled method rather than a behaviour test, because the behaviour needs a
 * live carrier: every call inside {@code fireModSetupLifecycle} resolves game classes reflectively, so the only
 * thing assertable off-game is the CONTROL FLOW — and the control flow is exactly what was wrong. The method
 * opened with {@code if (mods.isEmpty()) return;} over {@code publishedNeoMods()}, which on a classic
 * MinecraftForge pack is empty while {@code publishedForgeMods()} is full, so all four MinecraftForge phases
 * and NeoForge's own {@code RegistrationEvents.init} were skipped with no log line anywhere.
 *
 * <p>The invariant it pins: no {@code RETURN} may precede the first {@code fireForgeSetupPhase} call. The
 * {@code if (side.isClient()) return;} that follows it is deliberately allowed — the client's remaining phases
 * move to {@code fireClientSetupLifecycle}, which has no such guard at all.
 */
class KernelLifecycleSetupPhaseReachTest {
	@Test
	void theMinecraftForgePhasesAreReachableWithNoNeoForgeModLoaded() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode setup = node.methods.stream().filter(m -> "fireModSetupLifecycle".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("fireModSetupLifecycle is gone"));

		int firstReturn = -1;
		int firstForgePhase = -1;
		AbstractInsnNode[] insns = setup.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (firstReturn < 0 && insns[i].getOpcode() == Opcodes.RETURN) firstReturn = i;
			if (firstForgePhase < 0 && insns[i] instanceof MethodInsnNode call
					&& "fireForgeSetupPhase".equals(call.name)) {
				firstForgePhase = i;
			}
		}

		assertTrue(firstForgePhase >= 0,
				"fireModSetupLifecycle no longer posts any traditional-MinecraftForge setup phase");
		assertTrue(firstReturn < 0 || firstReturn > firstForgePhase,
				"fireModSetupLifecycle returns before it reaches the first fireForgeSetupPhase — a pack whose "
						+ "Forge-family mods are all traditional MinecraftForge gets no FMLCommonSetupEvent at all, "
						+ "which is the 'the world came out looking vanilla' failure");
	}

	/**
	 * The guard that was removed from the caller has to exist in the callee instead, or a MinecraftForge-only
	 * pack pays eight DeferredWorkQueue builds and eight "posted FML … to 0 NeoForge mod(s)" lines.
	 */
	@Test
	void theNeoForgePhaseStillNoOpsWhenNoNeoForgeModIsLoaded() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode phase = node.methods.stream().filter(m -> "fireSetupPhase".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("fireSetupPhase is gone"));

		int firstIsEmpty = -1;
		int firstForName = -1;
		AbstractInsnNode[] insns = phase.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (!(insns[i] instanceof MethodInsnNode call)) continue;
			if (firstIsEmpty < 0 && "isEmpty".equals(call.name)) firstIsEmpty = i;
			if (firstForName < 0 && "forName".equals(call.name)) firstForName = i;
		}

		assertTrue(firstIsEmpty >= 0 && (firstForName < 0 || firstIsEmpty < firstForName),
				"fireSetupPhase must return on an empty NeoForge mod set BEFORE it resolves game classes and "
						+ "builds a DeferredWorkQueue — its caller no longer guards that case");
	}
}
