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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link RegistryRevertInjector} on NeoForge's real {@code RegistryManager}, from the staged NeoForge carrier — the class it
 * edits is NeoForge's on either base, so this is the genuine base's class as much as the merged base's.
 *
 * <p>It used to be one of {@link RegistrySyncParityInjector}'s edits; split off so it stays when MinecraftForge's wrapper half
 * goes. Two shapes are proved here: alone (the genuine base, no MinecraftForge), it rewrites the revert and captures at
 * {@code applySnapshot}'s head itself; after the parity injector (today's chain, KernelBoot's order), it rewrites the same
 * revert and its capture stands down, so {@code RegistryManager} comes out with exactly the edits the one injector made
 * before the split.
 */
class RegistryRevertInjectorTest {
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final String MANAGER = "net.neoforged.neoforge.registries.RegistryManager";
	private static final String REVERT = "net/forbric/kernel/boot/KernelRegistryRevert";
	private static final String SYNC = "net/forbric/kernel/boot/KernelForgeWrapperSync";

	private static byte[] manager() {
		return TestFixtures.requireEntry(Fixture.STAGED, NEO_RUNTIME, MANAGER.replace('.', '/') + ".class");
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	/** The static calls a method makes, as {@code owner.name}, in order. */
	private static List<String> calls(MethodNode m) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) out.add(call.owner + "." + call.name);
		}
		return out;
	}

	private static void assertRevertBody(ClassNode out) throws Exception {
		MethodNode revert = method(out, RegistryRevertInjector.REVERT_TO_FROZEN, "()V");
		assertNotNull(revert, "NeoForge's RegistryManager.revertToFrozen()V is gone — Minecraft.disconnect calls it");
		assertEquals(List.of(REVERT + ".revertToPreConnection"), calls(revert), "the revert's whole body is the kernel's");
		new Analyzer<>(new BasicVerifier()).analyze(out.name, revert);
	}

	/** The genuine base: no MinecraftForge, nothing else edits RegistryManager, and the revert needs a capture of its own. */
	@Test
	void aloneItRewritesTheRevertAndCapturesAtTheHeadOfApplySnapshot() throws Exception {
		byte[] in = manager();
		ClassNode original = parse(in);
		MethodNode before = method(original, RegistryRevertInjector.APPLY_SNAPSHOT, RegistryRevertInjector.APPLY_SNAPSHOT_DESC);
		assertNotNull(before, "premise: NeoForge's RegistryManager has applySnapshot(Map, boolean)");
		assertTrue(calls(before).stream().noneMatch(c -> c.startsWith("net/forbric/")), "premise: the carrier's own body");

		byte[] out = new RegistryRevertInjector().transform(MANAGER, in, null);
		assertNotSame(in, out, "the REQUIRED anchor lands on NeoForge's own class");
		ClassNode node = parse(out);
		assertRevertBody(node);
		MethodNode apply = method(node, RegistryRevertInjector.APPLY_SNAPSHOT, RegistryRevertInjector.APPLY_SNAPSHOT_DESC);
		List<String> applyCalls = calls(apply);
		assertEquals(REVERT + ".captureIfNeeded", applyCalls.get(0), "the capture is the first thing applySnapshot does");
		assertEquals(1, applyCalls.stream().filter(c -> c.equals(REVERT + ".captureIfNeeded")).count(), "once");
		assertTrue(applyCalls.stream().noneMatch(c -> c.startsWith(SYNC)), "and nothing of MinecraftForge's wrapper sync");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, apply);
	}

	/** Today's chain, in KernelBoot's order: the edits the parity injector made to this class before the split, exactly. */
	@Test
	void afterTheWrapperSyncTheCaptureStandsDownAndTheClassIsWhatItWasBeforeTheSplit() throws Exception {
		byte[] in = manager();
		RegistrySyncParityInjector parity = new RegistrySyncParityInjector(type -> true, target -> List.of());
		byte[] synced = parity.transform(MANAGER, in, null);
		assertNotSame(in, synced, "premise: the wrapper sync hooks applySnapshot");
		ClassNode syncedNode = parse(synced);
		MethodNode syncedRevert = method(syncedNode, RegistryRevertInjector.REVERT_TO_FROZEN, "()V");
		assertTrue(calls(syncedRevert).stream().noneMatch(c -> c.startsWith(REVERT)),
				"the parity injector no longer touches the revert: that edit is the revert injector's alone");

		byte[] out = new RegistryRevertInjector().transform(MANAGER, synced, null);
		ClassNode node = parse(out);
		assertRevertBody(node);
		List<String> applyCalls = calls(method(node, RegistryRevertInjector.APPLY_SNAPSHOT, RegistryRevertInjector.APPLY_SNAPSHOT_DESC));
		assertEquals(SYNC + ".beginSnapshotApplication", applyCalls.get(0), "the wrapper sync's prologue is still first");
		assertEquals(0, applyCalls.stream().filter(c -> c.equals(REVERT + ".captureIfNeeded")).count(),
				"its prologue captures already, so the revert's own capture stood down: " + applyCalls);
		assertEquals(calls(method(syncedNode, RegistryRevertInjector.APPLY_SNAPSHOT, RegistryRevertInjector.APPLY_SNAPSHOT_DESC)),
				applyCalls, "applySnapshot is exactly what the wrapper sync made it");
		// Every other method is the carrier's, untouched by either.
		ClassNode original = parse(in);
		for (MethodNode m : original.methods) {
			if (m.name.equals(RegistryRevertInjector.REVERT_TO_FROZEN) || m.name.equals(RegistryRevertInjector.APPLY_SNAPSHOT)) continue;
			assertEquals(calls(m), calls(method(node, m.name, m.desc)), m.name + m.desc);
		}
	}

	/**
	 * KernelBoot registers the revert after the wrapper sync, within the same phase, where registration order is the order
	 * they run: the other way round the revert would find no prologue, add its own capture, and the wrapper sync would add
	 * a second. Read from the compiled KernelBoot, as {@code TransformerRegistrationOrderTest} reads it.
	 */
	@Test
	void kernelBootRegistersTheRevertAfterTheWrapperSync() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net", "forbric", "kernel", "boot",
				"KernelBoot.class");
		assertTrue(java.nio.file.Files.isRegularFile(compiled), "KernelBoot is compiled before any test runs");
		ClassNode boot = parse(java.nio.file.Files.readAllBytes(compiled));
		int sync = -1, revert = -1, at = 0;
		for (MethodNode m : boot.methods) {
			if (!m.name.equals("launch")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				at++;
				if (insn instanceof MethodInsnNode call && call.owner.equals("net/forbric/kernel/transform/RegistrySyncParityInjector")
						&& call.name.equals("forGameLoader")) sync = at;
				if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
						&& type.desc.equals("net/forbric/kernel/transform/RegistryRevertInjector")) revert = at;
			}
		}
		assertTrue(sync > 0, "the wrapper sync is registered in launch()");
		assertTrue(revert > sync, "and the revert after it");
	}

	@Test
	void everyOtherClassIsHandedBackUntouched() {
		byte[] bytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
		assertSame(bytes, new RegistryRevertInjector().transform("net.minecraft.core.MappedRegistry", bytes, null));
	}
}
