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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import net.forbric.api.Side;

import org.junit.jupiter.api.Test;

/**
 * Pins which config types the late-open pass may touch.
 *
 * <p>The pass exists because a Fabric mod registering a config from a client entrypoint is too late for the early
 * pass, and nothing else opens a non-STARTUP config — so the mod reads a config that was registered and never
 * loaded, and gets an exception rather than a default.
 *
 * <p>SERVER's absence is the part worth a test. A SERVER config is per-world and the server-about-to-start hook
 * loads it from the world directory; opening one here would load it from the global config directory first, and
 * the carrier's warning for the collision is asserted ABSENT by gate-m13 and gate-m14. Nothing else states that
 * constraint where someone adding a type would see it.
 */
class KernelLifecycleLateConfigTest {

	@Test
	void neitherSideEverOpensAServerConfigLate() {
		for (Side side : Side.values()) {
			assertFalse(KernelLifecycle.lateConfigTypes(side).contains("SERVER"),
					side + ": a SERVER config is per-world and belongs to the server-about-to-start hook. Opening "
							+ "it from the global config directory here makes the carrier overwrite it a moment "
							+ "later, which is what \"Overwriting non-null config\" means");
		}
	}

	@Test
	void theClientAlsoOpensItsOwnType() {
		assertEquals(List.of("STARTUP", "COMMON", "CLIENT"), KernelLifecycle.lateConfigTypes(Side.CLIENT));
	}

	@Test
	void aDedicatedServerHasNoClientConfigToOpen() {
		List<String> types = KernelLifecycle.lateConfigTypes(Side.DEDICATED_SERVER);
		assertEquals(List.of("STARTUP", "COMMON"), types);
		assertTrue(!types.contains("CLIENT"));
	}

	/**
	 * The late pass covers STARTUP; the EARLY pass must not.
	 *
	 * <p>{@code ConfigTracker.registerConfig} opens a STARTUP config eagerly, at registration — javap on the
	 * carrier: it loads {@code Type.STARTUP} at offset 58 and calls {@code openConfig} at 73. So naming STARTUP in
	 * {@code loadEarlyConfigs}, which goes through {@code ConfigTracker.loadConfigs} (a sweep of the whole type
	 * that does NOT skip a config with a loaded one), asks the carrier to open every STARTUP config a second time:
	 * one "Opening a config that was already loaded" warning each, {@code ModConfigEvent.Loading} delivered twice,
	 * the file re-read and a second watcher installed. The late pass keeps STARTUP because it opens only what has
	 * no loaded config yet, which is the honest way to catch one registered after the early sweep.
	 */
	@Test
	void theLatePassKeepsStartupBecauseItOnlyOpensWhatIsStillClosed() {
		assertTrue(KernelLifecycle.lateConfigTypes(Side.CLIENT).contains("STARTUP"),
				"the late pass skips a config that already has a loaded one, so covering STARTUP costs nothing and "
						+ "catches a spec registered after the early sweep");
	}

	/** The early pass goes through loadConfigs, which re-opens; STARTUP there is a double open, per config. */
	@Test
	void theEarlyPassDoesNotNameStartup() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		assertFalse(constantsOf(compiled, "loadEarlyConfigs").contains("STARTUP"),
				"loadEarlyConfigs must not name STARTUP: ConfigTracker.registerConfig already opened those, and "
						+ "loadConfigs re-opens whatever it is given");
	}

	/**
	 * Where the late pass runs is the whole of this fix. It used to sit in {@code registerNeoForgeContent}'s
	 * finally, which is BEFORE {@code loadEarlyConfigs} — so it opened each config and the early sweep then opened
	 * it again. After the setup lifecycle it is a second sweep of what construction and setup registered, and it
	 * cannot double-open anything.
	 */
	@Test
	void theLatePassRunsAfterTheEarlyPassAndAfterTheSetupLifecycle() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelLifecycle.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);

		MethodNode drive = method(node, "driveNativeRegistration");
		int early = firstCall(drive, "loadEarlyConfigs");
		int setup = firstCall(drive, "fireModSetupLifecycle");
		int late = firstCall(drive, "openLateConfigs");
		assertTrue(early >= 0 && setup >= 0 && late >= 0,
				"driveNativeRegistration must still run the early pass, the setup lifecycle and the late pass");
		assertTrue(late > early && late > setup,
				"the late config pass must run after loadEarlyConfigs and after fireModSetupLifecycle — before "
						+ "either, every config it opens is opened a second time by the early sweep");

		assertEquals(-1, firstCall(method(node, "registerNeoForgeContent"), "openLateConfigs"),
				"registerNeoForgeContent must not open late configs: it runs before loadEarlyConfigs, so every "
						+ "config it opened was re-opened, with a warning and a duplicate ModConfigEvent.Loading");
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> name.equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " is gone"));
	}

	/** Index of the first call to {@code name} in {@code m}, or -1. */
	private static int firstCall(MethodNode m, String name) {
		AbstractInsnNode[] insns = m.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (insns[i] instanceof MethodInsnNode call && name.equals(call.name)) return i;
		}
		return -1;
	}

	/** Every string constant a method loads, so a type list spelled as literals can be asserted on. */
	private static List<String> constantsOf(Path compiled, String method) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> out = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : method(node, method).instructions.toArray()) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) out.add(text);
		}
		return out;
	}
}
