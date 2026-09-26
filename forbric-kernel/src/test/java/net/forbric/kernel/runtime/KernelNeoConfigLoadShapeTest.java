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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The compiled NeoForge config helper's shape: the early pass no longer hands a whole type to the carrier's
 * loadConfigs (which aborts the rest of the type at the first config that will not open), and BOTH passes reach
 * ModCatalog.mark through the shared per-config open.
 */
class KernelNeoConfigLoadShapeTest {
	private static final Path COMPILED = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
			"net", "forbric", "kernel", "runtime", "KernelConfigLoad.class").normalize();

	@Test
	void theEarlyPassOpensPerConfigAndBothPassesMarkTheCatalogue() throws Exception {
		assumeTrue(Files.isRegularFile(COMPILED), "runtime helper not compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(COMPILED)).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!m.name.equals("loadEarly")) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				assertFalse(insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& "net/neoforged/fml/config/ConfigTracker".equals(call.owner) && "loadConfigs".equals(call.name),
						"loadEarly hands whole types to ConfigTracker.loadConfigs again");
			}
		}
		assertTrue(reaches(node, "loadEarly", "net/neoforged/fml/config/ModConfig", "getLoadedConfig", new HashSet<>()),
				"the early pass skips a config the port already opened at registration, or it is opened twice");
		assertTrue(reaches(node, "openAtRegistration", "net/neoforged/fml/config/ModConfig", "getLoadedConfig", new HashSet<>())
				&& reaches(node, "openAtRegistration", "net/neoforged/fml/config/ModConfig", "getType", new HashSet<>()),
				"registration opens only an unloaded, non-SERVER config");
		for (String pass : new String[] { "loadEarly", "openLate", "openAtRegistration" }) {
			assertTrue(reaches(node, pass, "net/forbric/api/ModCatalog", "mark", new HashSet<>()),
					pass + " reaches ModCatalog.mark (directly or through the shared open)");
		}
	}

	/** Whether {@code method} calls {@code owner.name}, following calls into this class's own private helpers. */
	private static boolean reaches(ClassNode node, String method, String owner, String name, Set<String> seen) {
		if (!seen.add(method)) return false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals(method)) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (owner.equals(call.owner) && name.equals(call.name)) return true;
				if (node.name.equals(call.owner) && reaches(node, call.name, owner, name, seen)) return true;
			}
		}
		return false;
	}
}
