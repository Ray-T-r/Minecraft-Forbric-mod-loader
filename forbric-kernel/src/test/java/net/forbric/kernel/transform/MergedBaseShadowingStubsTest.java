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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Methods the merge injected that hand off to an interface default while the superclass has a real one.
 *
 * <p>Measured across the whole merged base against both unmerged bases: 256 such methods, 253 of which exist in
 * neither unmerged base. {@code VehicleEntity.getDisplayName()} is one, and the method it bypasses,
 * {@code Entity.getDisplayName()}, is the one that applies team colours and prefixes — so a boat or minecart
 * loses its team formatting wherever its name is shown.
 *
 * <p>Why this rule is safe where a wider one once crashed every GUI screen at the title: a concrete superclass
 * method always beats an interface default, so when the chain has one there is nothing for competing defaults to
 * fight over. That crash happened in classes whose chain had none.
 */
class MergedBaseShadowingStubsTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final String VEHICLE = "net/minecraft/world/entity/vehicle/VehicleEntity";

	/** Reads any class out of the staged base, which is what the boot side hands the transformer. */
	private static Function<String, byte[]> resolver() {
		return path -> {
			try {
				return readClass(path);
			} catch (IOException unreadable) {
				return null;
			}
		};
	}

	@Test
	void theStubIsThereToBeginWith() throws Exception {
		ClassNode node = parse(bytesOf(VEHICLE));

		MethodNode stub = declared(node, "getDisplayName");
		assumeTrue(stub != null, "this base no longer has the stub — nothing to repair");
		assertTrue(bodyIsOneCall(stub), "the premise: its whole body hands off to an interface default");
	}

	@Test
	void theStubIsRemovedSoTheRealMethodIsInherited() throws Exception {
		// The merge tool now declines to synthesise these in the first place, so a freshly built base has none
		// and there is nothing here to remove. The load-time repair stays for a base built before that fix, and
		// this skips rather than failing — an absent defect is the good outcome, not a broken test.
		assumeTrue(declared(parse(bytesOf(VEHICLE)), "getDisplayName") != null,
				"this base no longer carries the stub — the merge tool stopped emitting it");

		byte[] repaired = new ForbricMergedBaseCompatTransformer(resolver())
				.transform(VEHICLE.replace('/', '.'), bytesOf(VEHICLE), null);

		assertTrue(declared(parse(repaired), "getDisplayName") == null,
				"with the stub gone the call reaches Entity's own method, which applies team formatting");
	}

	@Test
	void aMethodWhoseSuperclassAlsoJustDelegatesIsKept() throws Exception {
		// Vanilla's own pattern, present in both unmerged bases. Its superclass delegates the same way, so
        // removing the subclass's copy would change nothing and this rule must leave it alone.
		String widget = "net/minecraft/client/gui/components/AbstractContainerWidget";
		byte[] before = readClass(widget + ".class");
		assumeTrue(before != null, "that widget is absent from this base");

		ClassNode after = parse(new ForbricMergedBaseCompatTransformer(resolver())
				.transform(widget.replace('/', '.'), before, null));

		assertNotNull(declared(after, "nextFocusPath"),
				"a delegate whose superclass also delegates is vanilla's own shape, not merge damage");
	}

	@Test
	void withoutAResolverTheRepairStandsDown() throws Exception {
		// It cannot answer its own question without reading the superclass chain, and guessing is exactly what
		// the hand-kept allowlist beside it exists to avoid.
		byte[] input = bytesOf(VEHICLE);

		assertSame(input, new ForbricMergedBaseCompatTransformer()
				.transform(VEHICLE.replace('/', '.'), input, null));
	}

	@Test
	void aSecondPassChangesNothing() throws Exception {
		ForbricMergedBaseCompatTransformer transformer = new ForbricMergedBaseCompatTransformer(resolver());
		byte[] once = transformer.transform(VEHICLE.replace('/', '.'), bytesOf(VEHICLE), null);

		assertSame(once, transformer.transform(VEHICLE.replace('/', '.'), once, null),
				"with the stub gone there is nothing left to drop, and the pass must say so");
	}

	private static boolean bodyIsOneCall(MethodNode method) {
		int real = 0;
		for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() >= 0) real++;
		}
		return real <= 3 && (method.access & Opcodes.ACC_ABSTRACT) == 0;
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
		byte[] bytes = readClass(internalName + ".class");
		assumeTrue(bytes != null, internalName + " absent from this base");
		return bytes;
	}

	private static byte[] readClass(String entry) throws IOException {
		if (!Files.isRegularFile(MERGED_BASE)) return null;
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = jar.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = jar.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
