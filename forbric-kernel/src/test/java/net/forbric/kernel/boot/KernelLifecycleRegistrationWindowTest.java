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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Two orderings the registration window depends on, both of which were wrong and neither of which fails loudly.
 *
 * <p>These are bytecode-shape tests: {@code registerNeoForgeContent} and {@code driveNativeRegistration} do
 * nothing off-game — every step resolves a carrier class reflectively — so what is assertable here is the
 * control flow, which is precisely what the defects were.
 */
class KernelLifecycleRegistrationWindowTest {
	/**
	 * Everything that closes the window must be reachable from a finally. Inline at the end of the try, anything
	 * that threw after {@code unfreeze} left every registry writable for the rest of the run and skipped the
	 * block-item link, the blockstate-id rebuild, the creative-tab sort and the {@code registriesLoaded} latch —
	 * which surface as an empty creative tab, a player kicked at spawn on the first block update, and mods
	 * refusing to register render layers.
	 */
	@Test
	void theRegistrationWindowIsClosedFromAFinally() throws Exception {
		ClassNode node = compiled();
		assumeTrue(node != null, "KernelLifecycle not compiled yet");
		MethodNode content = method(node, "registerNeoForgeContent");

		boolean hasFinally = content.tryCatchBlocks.stream().anyMatch(b -> b.type == null);
		assertTrue(hasFinally,
				"registerNeoForgeContent must have a finally covering the span it unfreezes the registries in");

		MethodNode close = method(node, "closeRegistrationWindow");
		List<String> steps = callsIn(close);
		for (String required : List.of("linkBlockItems", "freeze", "rebuildNeoForgeBlockStateIds",
				"sortNeoCreativeTabs")) {
			assertTrue(steps.contains(required),
					"closing the window must still " + required + " — each of these is a failure the kernel has "
							+ "already paid for once");
		}
		assertTrue(callsIn(content).contains("closeRegistrationWindow"),
				"registerNeoForgeContent must route its close through closeRegistrationWindow");
	}

	/**
	 * The game buses must open before the setup lifecycle, where genuine NeoForge opens them.
	 *
	 * <p>{@code CommonModLoader.begin} starts {@code NeoForge.EVENT_BUS} right after its "Config loading" task and
	 * before {@code load()} posts common setup. The kernel started it last, after every setup phase — and
	 * {@code IEventBus.post} on a bus that has not started returns silently, so a mod posting its own API event
	 * during construct, RegisterEvent or common setup posted into nothing: no listener, no error, no log.
	 */
	@Test
	void theGameBusesOpenBeforeTheSetupLifecycle() throws Exception {
		ClassNode node = compiled();
		assumeTrue(node != null, "KernelLifecycle not compiled yet");
		MethodNode drive = method(node, "driveNativeRegistration");

		int configs = firstCall(drive, "loadEarlyConfigs");
		int buses = firstCall(drive, "startGameBuses");
		int setup = firstCall(drive, "fireModSetupLifecycle");
		assertTrue(configs >= 0 && buses >= 0 && setup >= 0, "driveNativeRegistration lost one of its steps");
		assertTrue(buses > configs,
				"the buses open after the config pass, as in CommonModLoader.begin");
		assertTrue(buses < setup,
				"the buses must be open BEFORE the setup lifecycle: a mod that posts its own event from common "
						+ "setup posts into a bus nothing dispatches, silently");
	}

	private static ClassNode compiled() throws Exception {
		Path file = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		if (!Files.isRegularFile(file)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> name.equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " is gone"));
	}

	private static List<String> callsIn(MethodNode m) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call) out.add(call.name);
		}
		return out;
	}

	private static int firstCall(MethodNode m, String name) {
		AbstractInsnNode[] insns = m.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (insns[i] instanceof MethodInsnNode call && name.equals(call.name)) return i;
		}
		return -1;
	}

	/** Unused import guard: TryCatchBlockNode is read through the stream above. */
	@SuppressWarnings("unused")
	private static final Class<?> KEEP = TryCatchBlockNode.class;
}
