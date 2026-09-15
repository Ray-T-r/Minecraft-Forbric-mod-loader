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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A whole-artifact detector for the field-level form of "the mechanism was split across the two halves".
 *
 * <p>When the two ecosystems RE-TYPE the same vanilla field, both declarations survive the merge — same name,
 * different descriptor is legal — but only one side's {@code <init>}/{@code <clinit>} does. The other is null for
 * the life of the process, and every reader of it, in the class or in a mod, gets that null with no error of any
 * kind at the point of the mistake. It has cost this project the inventory key ({@code KeyMapping.MAP}) and every
 * Fabric-particle-API mod ({@code ParticleResources.providers}); both were found by a crash, months apart.
 *
 * <p>So the invariant is asserted over the artifact instead: after the kernel's compat transformer has run, every
 * declared descriptor variant of a duplicated field name must have at least one writer in its own class. Around
 * 11k classes, a few seconds, no game.
 *
 * <p>What it cannot see: a field written with something USELESS — a fresh empty map rather than a view of the live
 * one. That distinction is structural and is asserted in {@link MergedBaseParticleProvidersTest}.
 */
class MergedBaseNoUnwrittenDuplicateFieldTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	@Test
	void everyDuplicatedFieldNameHasAWriterForEveryOneOfItsTypes() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping whole-artifact scan");

		List<String> dead = new ArrayList<>();
		int scanned = 0;
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			for (ZipEntry entry : jar.stream().toList()) {
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (InputStream in = jar.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
				scanned++;
				String binary = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
				byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(binary, bytes, null);

				ClassNode node = new ClassNode();
				new ClassReader(repaired).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				collectDeadVariants(node, dead);
			}
		}
		assumeTrue(scanned > 1000, "this does not look like a full merged base (" + scanned + " classes)");
		assertTrue(dead.isEmpty(),
				"these merged fields exist in more than one type and at least one of those types is never written, "
						+ "so it is null for every reader — in the class and in every mod — and nothing throws where "
						+ "the mistake is: " + dead);
	}

	private static void collectDeadVariants(ClassNode node, List<String> dead) {
		Map<String, List<FieldNode>> byName = new LinkedHashMap<>();
		for (FieldNode f : node.fields) byName.computeIfAbsent(f.name, k -> new ArrayList<>()).add(f);

		for (Map.Entry<String, List<FieldNode>> e : byName.entrySet()) {
			if (e.getValue().size() < 2) continue;

			Set<String> written = new LinkedHashSet<>();
			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn : m.instructions) {
					if (!(insn instanceof FieldInsnNode f) || !f.name.equals(e.getKey())
							|| !f.owner.equals(node.name)) {
						continue;
					}
					if (f.getOpcode() == Opcodes.PUTFIELD || f.getOpcode() == Opcodes.PUTSTATIC) {
						written.add(f.desc);
					}
				}
			}
			for (FieldNode f : e.getValue()) {
				if (!written.contains(f.desc)) dead.add(node.name + "#" + f.name + " " + f.desc);
			}
		}
	}
}
