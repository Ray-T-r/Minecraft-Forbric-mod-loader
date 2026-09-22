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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The capability lifecycle methods the merged game's own code calls, against the REAL staged base.
 *
 * <p>The merge put each root type under NeoForge's attachment holder, dropping MinecraftForge's capability
 * superclass and the two lifecycle methods with it — while keeping MinecraftForge's method bodies further down.
 * {@code BlockEntity.onChunkUnloaded()} is one of those bodies, and its single instruction calls a method that
 * resolves nowhere: not on the class, not up its superclass chain, not as an interface default.
 *
 * <p>Nothing in the merged base reaches that today, so it is a trap rather than a crash — a MinecraftForge mod's
 * block entity overriding the method and calling {@code super}, which storage and machinery mods routinely do,
 * links against something that is not there.
 */
class MergedBaseCapabilityStubsTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final List<String> ROOTS = List.of(
			"net/minecraft/world/entity/Entity",
			"net/minecraft/world/level/block/entity/BlockEntity",
			"net/minecraft/world/level/Level");

	@Test
	void theRootTypesAreMissingThemInTheBase() throws Exception {
		// The premise. If a rebuilt base ever carries them, this repair is redundant and this is where that shows.
		for (String root : ROOTS) {
			ClassNode node = parse(bytesOf(root));
			assertTrue(declared(node, "invalidateCaps") == null,
					root + " already declares invalidateCaps — the capability stubs may be redundant now");
		}
	}

	@Test
	void theCallInsideOnChunkUnloadedNowResolves() throws Exception {
		String owner = "net/minecraft/world/level/block/entity/BlockEntity";
		ClassNode before = parse(bytesOf(owner));

		MethodNode unload = declared(before, "onChunkUnloaded");
		assumeTrue(unload != null, "this base's BlockEntity has no onChunkUnloaded");
		assertTrue(callsOwn(unload, owner, "invalidateCaps"),
				"the premise: BlockEntity.onChunkUnloaded calls its own invalidateCaps");

		ClassNode after = parse(new ForbricMergedBaseCompatTransformer()
				.transform(owner.replace('/', '.'), bytesOf(owner), null));

		MethodNode added = declared(after, "invalidateCaps");
		assertTrue(added != null, "the method the class calls on itself must exist after the repair");
		assertTrue("()V".equals(added.desc), "it has to match the call's descriptor, or nothing changes");
		assertTrue((added.access & Opcodes.ACC_PUBLIC) != 0,
				"a mod subclass overrides this, so it cannot be package-private");
	}

	@Test
	void theStubsDoNothing() throws Exception {
		// Not a capability system: there is nothing on these classes to invalidate or revive. A stub that did
		// something would be inventing behaviour the merged base has no state for.
		ClassNode after = parse(new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Entity", bytesOf("net/minecraft/world/entity/Entity"), null));

		for (String name : List.of("invalidateCaps", "reviveCaps")) {
			MethodNode stub = declared(after, name);
			assertTrue(stub != null, name + " was not added");
			int real = 0;
			for (AbstractInsnNode insn : stub.instructions) {
				if (insn.getOpcode() >= 0) real++;
			}
			assertTrue(real == 1, name + " should be a bare return, found " + real + " instructions");
		}
	}

	@Test
	void anUnrelatedClassIsUntouched() throws Exception {
		byte[] other = bytesOf("net/minecraft/world/level/block/entity/ChestBlockEntity");

		assertSame(other, new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.level.block.entity.ChestBlockEntity", other, null),
				"only the three root types get stubs; a subclass inherits them");
	}

	@Test
	void aSecondPassAddsNothingMore() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Entity", bytesOf("net/minecraft/world/entity/Entity"), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Entity", once, null);

		assertSame(once, twice, "the repair must stand down once the methods are there");
	}

	private static boolean callsOwn(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) {
				return true;
			}
		}
		return false;
	}

	private static MethodNode declared(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internalName) throws IOException {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = jar.getEntry(internalName + ".class");
			assumeTrue(entry != null, internalName + " absent from this base");
			try (InputStream in = jar.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
