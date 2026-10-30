/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** NeoForge's real CommonHooks.sendRecipes filters the payload it built before sending it. */
@ResourceLock("system-properties")
class RecipeSyncFailSoftInjectorTest {
	private static final Path NEOFORGE_RUNTIME = TestFixtures.stagedRoot()
			.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String HOOKS = "net/neoforged/neoforge/common/CommonHooks";

	@AfterEach void reset() { System.clearProperty(RecipeSyncFailSoftInjector.PROPERTY); }

	@Test void thePayloadIsFilteredBetweenItsCreationAndItsSending() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, NEOFORGE_RUNTIME, "neoforge runtime not staged: " + NEOFORGE_RUNTIME);
		byte[] original = NativeCoremodParityTest.read(NEOFORGE_RUNTIME, HOOKS);
		byte[] out = new RecipeSyncFailSoftInjector().transform(RecipeSyncFailSoftInjector.COMMON_HOOKS, original, null);
		assertNotSame(original, out);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		MethodNode send = node.methods.stream()
				.filter(m -> m.name.equals("sendRecipes") && m.desc.equals(RecipeSyncFailSoftInjector.SEND_DESC)).findFirst().orElseThrow();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : send.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
		int create = calls.indexOf(RecipeSyncFailSoftInjector.PAYLOAD + ".create");
		assertEquals(RecipeSyncFailSoftInjector.HELPER + ".encodable", calls.get(create + 1), calls::toString);
		assertTrue(calls.indexOf("net/neoforged/neoforge/network/PacketDistributor.sendToPlayer") > create + 1, calls::toString);
		new Analyzer<>(new BasicVerifier()).analyze(HOOKS, send);

		assertSame(out, new RecipeSyncFailSoftInjector().transform(RecipeSyncFailSoftInjector.COMMON_HOOKS, out, null), "a second pass adds nothing");
		assertSame(original, new RecipeSyncFailSoftInjector().transform("net.neoforged.neoforge.common.NeoForgeMod", original, null));
		System.setProperty(RecipeSyncFailSoftInjector.PROPERTY, "off");
		assertSame(original, new RecipeSyncFailSoftInjector().transform(RecipeSyncFailSoftInjector.COMMON_HOOKS, original, null));
	}
}
