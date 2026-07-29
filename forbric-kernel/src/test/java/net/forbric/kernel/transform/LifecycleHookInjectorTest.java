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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Verifies against the REAL merged-base bytecode that {@link LifecycleHookInjector} excises the genuine
 * FancyModLoader server-loading trigger from {@code net.minecraft.server.Main.main} — the kernel owns the
 * lifecycle, no genuine loader runs.
 */
class LifecycleHookInjectorTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private final LifecycleHookInjector injector = new LifecycleHookInjector();

	@Test
	void excisesServerModLoaderFromRealMain() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] original = readClass(MERGED_BASE, "net/minecraft/server/Main.class");
		assumeTrue(original != null, "net/minecraft/server/Main not in merged base");

		// Precondition: the real Main.main really does call a genuine ModLoader.load (else the test proves nothing).
		assertTrue(callsAnyModLoaderLoad(original), "merged Main.main should reference a genuine ServerModLoader.load");

		byte[] out = injector.transform(LifecycleHookInjector.SERVER_MAIN, original, null);

		assertTrue(injector.transformedServerEntry(), "injector saw the server entry");
		assertFalse(injector.missedRequiredExcision(), "injector must not report a missed required excision");
		assertFalse(callsAnyModLoaderLoad(out),
				"after excision, Main.main must NOT reference any genuine ServerModLoader.load");

		// And the class must still be well-formed / verifiable (frames recomputed by re-reading).
		ClassNode check = new ClassNode();
		new ClassReader(out).accept(check, 0);
		MethodNode main = check.methods.stream().filter(m -> m.name.equals("main")).findFirst().orElseThrow();
		assertTrue(main.instructions.size() > 0, "main still has a body");
	}

	@Test
	void passesThroughNonEntryClasses() {
		byte[] bogus = new byte[] {}; // never inspected — className gate short-circuits
		byte[] out = injector.transform("net.minecraft.world.level.block.Block", bogus, null);
		assertEquals(bogus, out, "non-entry classes returned unchanged");
		assertFalse(injector.transformedServerEntry());
	}

	private static boolean callsAnyModLoaderLoad(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!m.name.equals("main")) continue;
			for (var insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode c
						&& c.owner.endsWith("server/loading/ServerModLoader") && c.name.equals("load")) {
					return true;
				}
			}
		}
		return false;
	}

	private static byte[] readClass(Path jar, String entry) throws Exception {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			ZipEntry e = zf.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = zf.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
