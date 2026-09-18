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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The dependency audit runs after the thing that writes half of what it reports.
 *
 * <p>Its second section lists mixins a mod wrote to attach to ANOTHER mod which did not attach — recorded by
 * KernelGuestMixinAdapter while Mixin parses each config, i.e. inside KernelMixinBootstrap.init. The audit used
 * to run from inside the NeoForge seeder, roughly thirty lines earlier in the same method, so the reader ran
 * before the writer every single time and ForeignMixinBreaks.all() was always empty. That section of the
 * player-facing dialog had never displayed anything.
 *
 * <p>gate-m20 could not catch it: it drives the dialog directly with synthetic rows, which is the right way to
 * test the dialog and no way at all to test when it is fed. Hence an ordering assertion here.
 */
class DependencyAuditOrderingTest {
	private static final Path LAUNCH =
			Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
					"net", "forbric", "kernel", "boot", "KernelBoot.class").normalize();

	@Test
	void theAuditRunsAfterMixinHasRegisteredItsConfigs() throws Exception {
		MethodNode launch = method("launch");
		assumeTrue(launch != null, "KernelBoot not compiled yet");

		int mixin = indexOfCall(launch, "init");
		int audit = indexOfCall(launch, "reportDependencies");

		assertTrue(mixin >= 0, "KernelMixinBootstrap.init is still called from launch");
		assertTrue(audit >= 0, "the audit must be driven from launch, not from inside a seeder that runs earlier");
		assertTrue(audit > mixin,
				"the audit reads what Mixin's config parse writes, so running it first reports an empty list and "
						+ "shows the player nothing — which is what it did for its whole existence");
	}

	@Test
	void theSeederNoLongerRunsTheAuditItself() throws Exception {
		// The other half: leaving the old call in place would make the assertion above true and change nothing.
		MethodNode seeder = seederMethodThatBuildsPresence();
		assumeTrue(seeder != null, "PassiveSeeder not compiled yet");

		assertTrue(indexOfCall(seeder, "report") < 0,
				"the seeder must hold the list rather than judging it, or the empty-list report is still there "
						+ "beside the working one");
	}

	private static MethodNode seederMethodThatBuildsPresence() throws Exception {
		Path file = LAUNCH.resolveSibling("PassiveSeeder.class");
		if (!Files.isRegularFile(file)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (indexOfCall(m, "publish") >= 0 && m.name.contains("seedNeoForgeLoadingModList")) return m;
		}
		return null;
	}

	private static int indexOfCall(MethodNode method, String calleeName) {
		if (method.instructions == null) return -1;
		AbstractInsnNode[] body = method.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && calleeName.equals(call.name)) return i;
		}
		return -1;
	}

	private static MethodNode method(String name) throws Exception {
		if (!Files.isRegularFile(LAUNCH)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(LAUNCH)).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}
}
