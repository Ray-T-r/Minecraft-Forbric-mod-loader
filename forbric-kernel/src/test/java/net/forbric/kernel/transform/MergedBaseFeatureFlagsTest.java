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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The merged FeatureFlags.<clinit> calls NeoForge's modded-flag loader once; after the repair it calls the kernel's. */
class MergedBaseFeatureFlagsTest {
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
			"patched-mc-merged-26.2.jar").normalize();

	@Test
	void theModdedFlagLoaderCallIsSentToTheKernel() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED), "staged merged base absent");
		byte[] original;
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ZipEntry entry = zip.getEntry(ForbricMergedBaseCompatTransformer.FEATURE_FLAGS + ".class");
			assertNotNull(entry);
			try (InputStream in = zip.getInputStream(entry)) {
				original = in.readAllBytes();
			}
		}
		assertEquals(1, calls(original, ForbricMergedBaseCompatTransformer.NEO_FEATURE_FLAG_LOADER), "premise: NeoForge's patch calls its loader once");

		byte[] out = new ForbricMergedBaseCompatTransformer(n -> null).transform(
				ForbricMergedBaseCompatTransformer.FEATURE_FLAGS.replace('/', '.'), original, null);
		assertNotSame(original, out);
		assertEquals(0, calls(out, ForbricMergedBaseCompatTransformer.NEO_FEATURE_FLAG_LOADER));
		assertEquals(1, calls(out, ForbricMergedBaseCompatTransformer.KERNEL_FEATURE_FLAGS));
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		assertSame(out, new ForbricMergedBaseCompatTransformer(n -> null).transform(
				ForbricMergedBaseCompatTransformer.FEATURE_FLAGS.replace('/', '.'), out, null), "second pass");
	}

	private static int calls(byte[] bytes, String owner) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int n = 0;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<clinit>")) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
						&& ForbricMergedBaseCompatTransformer.LOAD_MODDED_FLAGS.equals(call.name)) n++;
			}
		}
		return n;
	}
}
