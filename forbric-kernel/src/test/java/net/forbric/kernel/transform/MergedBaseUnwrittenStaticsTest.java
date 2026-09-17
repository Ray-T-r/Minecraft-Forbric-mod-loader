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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

/**
 * The full inventory of static fields the merge left with no assignment, against the REAL staged base.
 *
 * <p>When both families patch a class, one family's static initialiser wins whole and the loser's assignments go
 * with it — while the fields the loser ADDED are kept as declarations. Such a field is null for the life of the
 * process and nothing says so: the class links, loads and works until something reads it.
 *
 * <p>This is a census rather than a single case, because the value of knowing the list is that it cannot grow
 * quietly. Eight fields are in this state today; one has an obvious correct value and is repaired, and the other
 * seven carry codecs and callbacks that cannot be invented by a transformer and need the merge itself to stop
 * dropping them.
 */
class MergedBaseUnwrittenStaticsTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	/**
	 * What the merge leaves unassigned today, each with what reading it costs.
	 *
	 * <p>A NEW entry here means the merge dropped something else and nobody noticed; a MISSING one means it was
	 * repaired or the base stopped splitting that class. Either way the list has to be re-read, not re-blessed.
	 */
	private static final Set<String> KNOWN = new LinkedHashSet<>(List.of(
			// Repaired by ForbricMergedBaseCompatTransformer — see theLoggerIsRepaired below.
			"net/minecraft/resources/ResourceManagerRegistryLoadTask.LOGGER",
			// A datapack entry's condition being false lands in a branch that reads this; needs the merge fixed.
			"net/minecraft/world/item/crafting/Ingredient.VANILLA_CODEC",
			"net/minecraft/world/item/crafting/Ingredient.VANILLA_MAP_CODEC",
			"net/minecraft/world/item/crafting/Ingredient.VANILLA_CONTENTS_STREAM_CODEC",
			// Read by a mod calling Ingredient.serializer() directly.
			"net/minecraft/world/item/crafting/Ingredient.VANILLA_SERIALIZER",
			"net/minecraft/client/particle/ParticleEngine.factories",
			"net/minecraft/server/network/ServerConfigurationPacketListenerImpl.VANILLA_START",
			"net/minecraft/world/item/ItemDisplayContext.ADD_CALLBACK"));

	@Test
	void theCensusHasNotChanged() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");

		Set<String> found = unwrittenObjectStatics();

		assertEquals(KNOWN, found,
				"the set of static fields the merge leaves unassigned changed. A new entry means something else "
						+ "was dropped silently; a missing one means it was repaired. Re-read the list, do not "
						+ "just update it.");
	}

	@Test
	void theLoggerIsRepaired() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		String owner = "net/minecraft/resources/ResourceManagerRegistryLoadTask";
		byte[] in = readClass(owner + ".class");
		assumeTrue(in != null, "that class is absent from this base");

		ClassNode before = parse(in);
		assumeTrue(before.fields.stream().anyMatch(f -> "LOGGER".equals(f.name)),
				"no LOGGER field here any more — nothing to repair");
		assertTrue(!writesStatic(before, "LOGGER"), "the premise: nothing assigns it in the base");

		ClassNode after = parse(new ForbricMergedBaseCompatTransformer().transform(
				owner.replace('/', '.'), in, null));

		assertTrue(writesStatic(after, "LOGGER"), "the repair must assign it");
		assertEquals("<clinit>", firstAssigningMethod(after, "LOGGER"),
				"it has to be assigned by the class initialiser, not lazily by whoever reads it first");
		assertTrue(assignedAtTheTopOfClinit(after),
				"and at the TOP of the initialiser: anything else it does may log, and a repair that lands last "
						+ "leaves exactly the window this closes");
	}

	@Test
	void aClassWithAWrittenLoggerIsLeftAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		// Any class whose logger IS assigned must come back byte-identical, or the repair is rewriting the base
		// wholesale instead of filling one gap.
		byte[] in = readClass("net/minecraft/server/MinecraftServer.class");
		assumeTrue(in != null, "MinecraftServer absent from this base");
		assumeTrue(writesStatic(parse(in), "LOGGER"), "MinecraftServer's logger is not assigned here either");

		assertTrue(new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.server.MinecraftServer", in, null) == in,
				"a class with nothing to repair must be returned untouched");
	}

	private static Set<String> unwrittenObjectStatics() throws IOException {
		Set<String> found = new LinkedHashSet<>();
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class")) continue;

				ClassNode node;
				try (InputStream in = jar.getInputStream(entry)) {
					node = parse(in.readAllBytes());
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
		return firstAssigningMethod(node, name) != null;
	}

	private static String firstAssigningMethod(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC
						&& node.name.equals(f.owner) && name.equals(f.name)) {
					return method.name;
				}
			}
		}
		return null;
	}

	private static boolean assignedAtTheTopOfClinit(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (!"<clinit>".equals(method.name)) continue;
			int seen = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() < 0) continue; // labels, line numbers, frames
				seen++;
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC
						&& "LOGGER".equals(f.name)) {
					return seen <= 3; // ldc, invokestatic, putstatic
				}
			}
		}
		return false;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(String entry) throws IOException {
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = jar.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = jar.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
