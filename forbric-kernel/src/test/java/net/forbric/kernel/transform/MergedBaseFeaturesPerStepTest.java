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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers giving {@code ChunkGenerator.featuresPerStep} vanilla's descriptor back, against the REAL staged base.
 *
 * <p>The subject has to be the real merged bytecode: what this repair reacts to is what the other repository's
 * merge emits for a RE-TYPED vanilla field, and a synthetic fixture would only assert that the test author and
 * the transformer agree.
 *
 * <p>What it is protecting, in one sentence: fabric-api's biome API writes this field directly with vanilla's
 * descriptor, so while MinecraftForge's {@code ClearableLazy} descriptor is the only one, a dedicated server with
 * any Fabric biome modification installed does not start.
 */
class MergedBaseFeaturesPerStepTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String ENTRY = "net/minecraft/world/level/chunk/ChunkGenerator.class";
	private static final String BINARY = "net.minecraft.world.level.chunk.ChunkGenerator";
	private static final String LAZY = "Lnet/minecraftforge/common/util/ClearableLazy;";
	private static final String SUPPLIER = "Ljava/util/function/Supplier;";

	@Test
	void theFieldCarriesVanillasDescriptorAndOnlyThatOne() throws Exception {
		List<String> descriptors = new ArrayList<>();
		for (FieldNode field : repaired().fields) {
			if ("featuresPerStep".equals(field.name)) descriptors.add(field.desc);
		}
		assertEquals(List.of(SUPPLIER), descriptors,
				"exactly one featuresPerStep, carrying vanilla's descriptor. Two declarations would be the "
						+ "duplicate-field shape this repair must not create, and MinecraftForge's alone is the "
						+ "NoSuchFieldError fabric-api's BiomeModificationImpl hits on a direct putfield");
	}

	/**
	 * The assertion that rejects "restored the descriptor, kept the access".
	 *
	 * <p>fabric-api asks for this field by (owner, name, DESCRIPTOR) in {@code fabric-biome-api-v1.classtweaker} —
	 * {@code accessible} and {@code mutable} — and the kernel applies class tweakers one phase EARLIER than this
	 * repair. So that request could never have matched the {@code ClearableLazy}-typed declaration, and a repair
	 * that fixes only the descriptor leaves the field {@code private final}: the cross-class {@code PUTFIELD} in
	 * {@code BiomeModificationImpl} then throws {@code IllegalAccessError} instead of {@code NoSuchFieldError},
	 * the server still does not start, and every test that looks only at descriptors still passes.
	 */
	@Test
	void theFieldIsAlsoWriteableFromAnotherClass() throws Exception {
		for (FieldNode field : repaired().fields) {
			if (!"featuresPerStep".equals(field.name)) continue;
			assertTrue((field.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0,
					"featuresPerStep is not public — fabric-api's putfield comes from another class");
			assertTrue((field.access & org.objectweb.asm.Opcodes.ACC_FINAL) == 0,
					"featuresPerStep is still final — a putfield from outside the declaring class is rejected "
							+ "outright, whatever the descriptor says");
		}
	}

	/**
	 * The assertion that rejects "add a second field for fabric-api to write". A vanilla-typed field nothing reads
	 * would satisfy the descriptor, boot the server, and leave the biome modification silently unapplied — which
	 * is worse than the crash, because nothing says so.
	 */
	@Test
	void everyReaderReadsTheOneFieldTheConstructorWrites() throws Exception {
		ClassNode node = repaired();
		int written = 0;
		int read = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof FieldInsnNode access) || !"featuresPerStep".equals(access.name)) continue;
				assertEquals(SUPPLIER, access.desc,
						"a leftover " + LAZY + " access in " + method.name + method.desc
								+ " — a half-retyped field is a NoSuchFieldError somewhere less legible");
				if (access.getOpcode() == Opcodes.PUTFIELD) written++;
				if (access.getOpcode() == Opcodes.GETFIELD) read++;
			}
		}
		assertTrue(written >= 1, "the constructor must still write the field");
		assertTrue(read >= 1, "and something must still read it — an unread field is the silent failure");
	}

	/**
	 * {@code refreshFeaturesPerStep} is the one use a plain {@code Supplier} cannot serve. A {@code CHECKCAST} to
	 * {@code ClearableLazy} there would look right and then throw {@code ClassCastException} in worldgen the first
	 * time fabric-api had replaced the value — the same mods, a different crash.
	 */
	@Test
	void invalidationGoesThroughTheGuardAndNotAThroughCast() throws Exception {
		MethodNode refresh = method(repaired(), "refreshFeaturesPerStep", "()V");
		boolean guarded = false;
		for (AbstractInsnNode insn : refresh.instructions) {
			if (insn instanceof MethodInsnNode call) {
				assertTrue(!"net/minecraftforge/common/util/ClearableLazy".equals(call.owner),
						"refreshFeaturesPerStep still calls ClearableLazy directly on a field that a Fabric "
								+ "modification is allowed to replace");
				if (call.getOpcode() == Opcodes.INVOKESTATIC
						&& "net/forbric/kernel/runtime/KernelChunkGenerator".equals(call.owner)
						&& "invalidate".equals(call.name)) {
					assertEquals("(" + SUPPLIER + ")V", call.desc,
							"the guard takes the receiver GETFIELD already left on the stack, so the replacement "
									+ "is one instruction for one and no frame changes");
					guarded = true;
				}
			}
			assertTrue(insn.getOpcode() != Opcodes.CHECKCAST,
					"a cast here is the ClassCastException this guard exists to avoid");
		}
		assertTrue(guarded, "refreshFeaturesPerStep must invalidate through KernelChunkGenerator");
	}

	/** Reads stay reads: the value is a {@code Supplier} either way, so this is a descriptor change, not a call. */
	@Test
	void readsAreRetargetedAtSupplierGet() throws Exception {
		ClassNode node = repaired();
		int through = 0;
		for (MethodNode method : node.methods) {
			AbstractInsnNode[] body = method.instructions.toArray();
			for (int i = 0; i < body.length - 1; i++) {
				if (!(body[i] instanceof FieldInsnNode access) || access.getOpcode() != Opcodes.GETFIELD
						|| !"featuresPerStep".equals(access.name)) {
					continue;
				}
				if (body[i + 1] instanceof MethodInsnNode call && "get".equals(call.name)) {
					assertEquals("java/util/function/Supplier", call.owner,
							"the read in " + method.name + method.desc + " must go through Supplier.get()");
					assertTrue(call.itf, "Supplier is an interface, so the call has to stay INVOKEINTERFACE");
					through++;
				}
			}
		}
		assertTrue(through >= 1, "at least one read of the list must survive — validate() and applyBiomeDecoration "
				+ "are what actually consume it");
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "the repair must stand down once the field is vanilla-typed");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null))
				.accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(ENTRY);
		assumeTrue(in != null, "ChunkGenerator absent from this base");
		ClassNode node = new ClassNode();
		new ClassReader(in).accept(node, 0);
		assumeTrue(node.fields.stream().anyMatch(f -> "featuresPerStep".equals(f.name) && LAZY.equals(f.desc)),
				"this base no longer re-types ChunkGenerator.featuresPerStep — nothing to repair");
		return in;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		assertNotNull(null, "missing " + name + desc);
		throw new AssertionError();
	}

	private static byte[] readClass(String entry) throws IOException {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			var e = zip.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
