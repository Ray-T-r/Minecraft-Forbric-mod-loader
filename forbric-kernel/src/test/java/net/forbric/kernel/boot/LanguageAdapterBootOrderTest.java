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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Where the boot builds Fabric's language adapters: after the game loader is bound, before Mixin starts.
 *
 * <p>Bytecode-shape, in the idiom of {@code KernelMixinBootstrapDecorationTest}, because the property is ORDER. Built
 * before {@code bindGameLoader}, an adapter class resolves through the wrong loader; built after Mixin, an adapter
 * that writes config files (Configured Defaults) writes them after a mixin config plugin has already read them —
 * Fabric Loader builds them in {@code freeze()}, before {@code FabricMixinBootstrap.init}. Either mistake compiles,
 * runs and throws nothing.
 */
class LanguageAdapterBootOrderTest {
	private static final Path COMPILED = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
			"net", "forbric", "kernel", "boot", "KernelBoot.class").normalize();

	@Test
	void adaptersAreBuiltAfterTheGameLoaderIsBoundAndBeforeMixinStarts() throws Exception {
		MethodNode launch = methodCalling("constructLanguageAdapters");
		assertNotNull(launch, "KernelBoot must still construct the Fabric language adapters during launch");

		int bound = indexOf(launch, "KernelFabricEcosystem", "bindGameLoader");
		int built = indexOf(launch, "KernelFabricEcosystem", "constructLanguageAdapters");
		int mixin = indexOf(launch, "KernelMixinBootstrap", "init");

		assertTrue(bound >= 0 && mixin >= 0, "the two landmarks moved out of the method that builds the adapters");
		assertTrue(bound < built, "an adapter is a mod class: it must load through the bound game loader");
		assertTrue(built < mixin, "Fabric Loader builds every adapter before Mixin starts; after it, what an adapter's "
				+ "constructor writes arrives after the mixin config plugins that read it");
	}

	private static int indexOf(MethodNode method, String ownerSimpleName, String name) {
		AbstractInsnNode[] body = method.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && call.name.equals(name)
					&& call.owner.endsWith("/" + ownerSimpleName)) {
				return i;
			}
		}
		return -1;
	}

	private static MethodNode methodCalling(String name) throws Exception {
		if (!Files.isRegularFile(COMPILED)) return null;
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(COMPILED)).accept(node, 0);
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.name.equals(name)) return m;
			}
		}
		return null;
	}
}
