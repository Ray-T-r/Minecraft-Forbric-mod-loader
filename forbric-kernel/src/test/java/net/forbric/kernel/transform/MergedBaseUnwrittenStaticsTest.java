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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The full inventory of {@code static final} object fields that no code of their own class assigns, over the REAL
 * staged base and NeoForge carrier.
 *
 * <p>Such a field is null for the life of the process and nothing says so: the class links, loads and works until
 * something reads it. The merged Forge+NeoForge base had seven of them, because when both families patched a class
 * one family's static initialiser won whole and the loser's assignments went with it while the fields it added
 * stayed (among them {@code ResourceManagerRegistryLoadTask.LOGGER}, which the compat transformer's
 * giveTheUnwrittenLoggerAValue filled in, and {@code Ingredient}'s four vanilla codecs). NeoForge's own patched game
 * is one family's compile, so the census is empty, and that is what this test holds: a new entry is a field the base
 * ships null, which no transformer can be trusted to invent a value for.
 */
class MergedBaseUnwrittenStaticsTest {
	private static final List<Path> JARS = List.of(TestFixtures.stagedBase(), TestFixtures.neoCarrier());

	@Test
	void theBaseAndCarrierLeaveNoStaticFinalUnassigned() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged base or NeoForge carrier absent", JARS.toArray(Path[]::new));
		Set<String> found = new LinkedHashSet<>();
		for (Path jar : JARS) found.addAll(unwrittenObjectStatics(jar));
		assertEquals(Set.of(), found,
				"a static final object field nothing in its class assigns: null for the life of the process. Find out "
						+ "why the base ships it unassigned before anything papers over it.");
	}

	private static Set<String> unwrittenObjectStatics(Path jarPath) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		try (ZipFile jar = new ZipFile(jarPath.toFile())) {
			for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;

				ClassNode node = new ClassNode();
				try (InputStream in = jar.getInputStream(entry)) {
					new ClassReader(in.readAllBytes()).accept(node, 0);
				}
				for (FieldNode field : node.fields) {
					if ((field.access & Opcodes.ACC_STATIC) == 0) continue;
					if ((field.access & Opcodes.ACC_FINAL) == 0) continue;
					// A compile-time constant carries its value in the class file and needs no assignment.
					if (field.value != null) continue;
					if (!field.desc.startsWith("L") && !field.desc.startsWith("[")) continue;
					if (writesStatic(node, field.name)) continue;

					found.add(node.name + "." + field.name);
				}
			}
		}
		return found;
	}

	private static boolean writesStatic(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC
						&& node.name.equals(f.owner) && name.equals(f.name)) {
					return true;
				}
			}
		}
		return false;
	}
}
