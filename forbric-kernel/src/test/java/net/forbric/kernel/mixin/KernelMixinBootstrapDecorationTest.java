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

package net.forbric.kernel.mixin;

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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * That the mod-id decoration happens, happens in time, and does not quietly bring a second one along.
 *
 * <p>Bytecode-shape, in the idiom {@link net.forbric.kernel.boot.KernelLifecycleFailureReportingTest} uses,
 * because the property is ORDER and no unit test can observe it any other way. Decorating after
 * {@code gotoPhase(INIT)} compiles, runs, throws nothing, and does nothing: the configs are already prepared and
 * the MixinInfos already built. That is a silent no-op, which is the failure mode this whole line of work exists
 * to remove — so it gets an assertion rather than a comment.
 */
class KernelMixinBootstrapDecorationTest {
	private static final Path COMPILED =
			Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
					"net", "forbric", "kernel", "mixin", "KernelMixinBootstrap.class").normalize();

	@Test
	void theDecorationHappensBeforeTheConfigsArePrepared() throws Exception {
		MethodNode init = method("init");
		assumeTrue(init != null, "KernelMixinBootstrap not compiled yet");

		int decorate = indexOf(init, call -> "nameTheModsBehindTheConfigs".equals(call.name));
		int prepare = indexOf(init, call -> "gotoPhase".equals(call.name));

		assertTrue(decorate >= 0, "init must still name the mods behind the configs");
		assertTrue(prepare >= 0, "init must still advance the Mixin phase");
		assertTrue(decorate < prepare,
				"decorating after gotoPhase(INIT) is a silent no-op — the configs are prepared and the MixinInfos "
						+ "built by then, so the decoration is set on something nobody reads again");
	}

	@Test
	void onlyTheModIdIsDecoratedAndNotTheCompatibilityLevel() throws Exception {
		MethodNode namer = method("nameTheModsBehindTheConfigs");
		assumeTrue(namer != null, "KernelMixinBootstrap not compiled yet");

		// Both keys are compile-time String constants, so javac inlines them as LDCs rather than leaving a
		// GETSTATIC to read the field name from. The VALUES are what is in the bytecode, so those are what this
		// looks for.
		List<String> keys = new ArrayList<>();
		for (AbstractInsnNode insn : namer.instructions) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) keys.add(text);
		}

		assertTrue(keys.contains(org.spongepowered.asm.mixin.FabricUtil.KEY_MOD_ID),
				"the mod id is the whole point");
		assertFalse(keys.contains(org.spongepowered.asm.mixin.FabricUtil.KEY_COMPATIBILITY),
				"KEY_COMPATIBILITY sets injector semantics; changing it per mod is a separate decision with its "
						+ "own evidence requirement, not a passenger on a naming change");
	}

	@Test
	void theSwitchIsReadBeforeAnythingIsDecorated() throws Exception {
		MethodNode namer = method("nameTheModsBehindTheConfigs");
		assumeTrue(namer != null, "KernelMixinBootstrap not compiled yet");

		int property = -1;
		int decorate = -1;
		AbstractInsnNode[] body = namer.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof LdcInsnNode ldc
					&& KernelMixinBootstrap.DECORATION_PROPERTY.equals(ldc.cst)) {
				property = i;
			} else if (body[i] instanceof MethodInsnNode call && "decorate".equals(call.name) && decorate < 0) {
				decorate = i;
			}
		}

		assertTrue(property >= 0, "the escape hatch must still exist: this change alters generated method names");
		assertTrue(decorate < 0 || property < decorate, "and it has to be honoured before anything is decorated");
	}

	private static int indexOf(MethodNode method, java.util.function.Predicate<MethodInsnNode> match) {
		AbstractInsnNode[] body = method.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && match.test(call)) return i;
		}
		return -1;
	}

	private static MethodNode method(String name) throws Exception {
		if (!Files.isRegularFile(COMPILED)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(COMPILED)).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}
}
