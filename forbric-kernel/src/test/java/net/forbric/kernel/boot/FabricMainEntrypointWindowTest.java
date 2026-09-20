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
 * Pins WHERE the client's Fabric {@code main} entrypoints run, which is the whole of the fix.
 *
 * <p>Fabric's {@code Hooks.startClient} invokes {@code main} and then {@code client}, and its game patch inserts
 * that call inside {@code Minecraft.<init>} after {@code instance = this} — so on Fabric a {@code main} entrypoint
 * always sees a live {@code Minecraft.getInstance()}. The kernel ran them in its own pre-{@code Minecraft}
 * registration window instead, where the instance is still null, and a mod that caches it cached null forever:
 * ClickCrystals reads {@code Minecraft.getInstance()} into a {@code static final} interface field from the first
 * instruction of its {@code onInitialize}, and the client then died inside the constructor on
 * {@code SetScreenEvent.mc is null} — a stack naming the mod and the game and nothing about the loader.
 *
 * <p>There is no game here to boot, so the assertion is on the call graph: the two phases must be adjacent and in
 * Fabric's order in the constructor hook, and the pre-{@code Minecraft} window must not reach {@code main}
 * unconditionally. Both are exactly what a later edit would undo by accident.
 */
class FabricMainEntrypointWindowTest {
	@Test
	void theConstructorHookRunsMainThenClient() throws Exception {
		List<String> calls = fabricEntrypointCallsIn("onClientEntrypoints");
		assumeTrue(!calls.isEmpty(), "KernelLifecycle not compiled yet");

		assertEquals(List.of("runMainEntrypoints", "runClientEntrypoints"), calls,
				"Minecraft.<init> must run main then client, which is Hooks.startClient's own order");
	}

	/**
	 * The pre-{@code Minecraft} registration window ({@code registerNeoForgeContent}, the unfrozen span
	 * {@code driveNativeRegistration} opens) may still run them — a dedicated server has no {@code Minecraft} to wait
	 * for, and the switch puts the client back here — but never without asking first. An unguarded call is the
	 * state this fix replaced.
	 */
	@Test
	void theRegistrationWindowAsksBeforeRunningMain() throws Exception {
		MethodNode window = method("registerNeoForgeContent");
		assumeTrue(window != null, "KernelLifecycle not compiled yet");

		boolean callsMain = false;
		boolean asks = false;
		for (AbstractInsnNode insn : window.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if ("runMainEntrypoints".equals(call.name)) callsMain = true;
			if ("mainsRunInConstructor".equals(call.name)) asks = true;
		}

		assertTrue(callsMain, "the dedicated server still runs its main entrypoints here");
		assertTrue(asks, "on a client this window must defer to the constructor hook");
	}

	@Test
	void theSwitchPutsThemBackInThePreMinecraftWindow() {
		String previous = System.getProperty(KernelFabricEcosystem.MAIN_WINDOW_SWITCH);
		try {
			System.clearProperty(KernelFabricEcosystem.MAIN_WINDOW_SWITCH);
			assertTrue(KernelFabricEcosystem.mainsRunInConstructor(), "the constructor window is the default");

			System.setProperty(KernelFabricEcosystem.MAIN_WINDOW_SWITCH, "off");
			assertFalse(KernelFabricEcosystem.mainsRunInConstructor());
		} finally {
			if (previous == null) System.clearProperty(KernelFabricEcosystem.MAIN_WINDOW_SWITCH);
			else System.setProperty(KernelFabricEcosystem.MAIN_WINDOW_SWITCH, previous);
		}
	}

	/** The {@code KernelFabricEcosystem} entrypoint calls one method makes, in order. */
	private static List<String> fabricEntrypointCallsIn(String methodName) throws Exception {
		MethodNode method = method(methodName);
		if (method == null) return List.of();

		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (!call.owner.endsWith("/KernelFabricEcosystem")) continue;
			if (call.name.startsWith("run") && call.name.endsWith("Entrypoints")) calls.add(call.name);
		}
		return calls;
	}

	private static MethodNode method(String name) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(compiled)) return null;

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (name.equals(method.name)) return method;
		}
		return null;
	}
}
