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
 * A universal jar's event subscribers must be registered for ONE family, the one that owns the jar.
 *
 * <p>Such a jar ships one subscriber class per family and arbitration has already picked which family runs it.
 * Registering both means the same handler runs twice for every event both families post — double drops, double
 * damage, double packets — and the mod cannot notice, because each call looks like the only one.
 *
 * <h2>Why this is a shape test</h2>
 *
 * <p>Honestly: no pack in any gate has a jar of that shape. The two universal jars in the gates ship their
 * subscriber classes for one family only, so the duplicate never occurs there and a live assertion would pass
 * whether or not the guard exists. Proving it live needs a purpose-built universal jar carrying a subscriber for
 * each Forge family, which does not exist yet. What IS assertable is that the registration pass asks arbitration
 * at all, and asks before it dispatches on family — which is exactly the line that was missing.
 */
class KernelEventSubscribersArbitrationTest {

	@Test
	void theRegistrationPassAsksArbitrationBeforeItDispatchesOnFamily() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelEventSubscribers.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelEventSubscribers not compiled yet");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode registerAll = node.methods.stream().filter(m -> "registerAll".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("registerAll is gone"));

		int suppressed = -1;
		int register = -1;
		AbstractInsnNode[] insns = registerAll.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			if (!(insns[i] instanceof MethodInsnNode call)) continue;
			if (suppressed < 0 && "suppressedFor".equals(call.name)
					&& call.owner.endsWith("MultiLoaderArbiter")) {
				suppressed = i;
			}
			if (register < 0 && ("registerForgeSubscriber".equals(call.name)
					|| "wireNeoSubscriber".equals(call.name))) {
				register = i;
			}
		}

		assertTrue(suppressed >= 0,
				"registerAll never asks MultiLoaderArbiter which family owns the jar, so a universal jar's "
						+ "subscribers are registered for BOTH and every handler runs twice");
		assertTrue(register >= 0, "registerAll no longer registers anything");
		assertTrue(suppressed < register,
				"arbitration has to be consulted BEFORE the subscriber is wired, or the duplicate is already made");
	}
}
