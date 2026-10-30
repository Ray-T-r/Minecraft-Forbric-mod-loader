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
 * On the real merged CustomPacketPayload: NeoForge's codec overload goes through vanilla's from outside a build,
 * vanilla's hands back to NeoForge's inside one, and both keep their own bodies after the prologue.
 */
@ResourceLock("system-properties")
class PayloadCodecFunnelInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve(TestFixtures.BASE_JAR);

	@AfterEach void reset() { System.clearProperty(PayloadCodecFunnelInjector.PROPERTY); }

	@Test void neoForgesOverloadGoesThroughVanillasAndVanillasHandsBack() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, PayloadCodecFunnelInjector.OWNER);
		byte[] out = new PayloadCodecFunnelInjector().transform(PayloadCodecFunnelInjector.TARGET, original, null);
		assertNotSame(original, out);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);

		List<String> neo = calls(method(node, PayloadCodecFunnelInjector.NEO_DESC));
		assertEquals(PayloadCodecFunnelInjector.RUNTIME + ".protocol", neo.get(0));
		assertEquals(PayloadCodecFunnelInjector.RUNTIME + ".through", neo.get(1));
		assertTrue(neo.indexOf(PayloadCodecFunnelInjector.RUNTIME + ".through") < neo.indexOf("java/util/List.stream"),
				"NeoForge's own body still follows the prologue: " + neo);

		List<String> vanilla = calls(method(node, PayloadCodecFunnelInjector.VANILLA_DESC));
		assertEquals(List.of(PayloadCodecFunnelInjector.RUNTIME + ".protocol", PayloadCodecFunnelInjector.RUNTIME + ".protocol",
				PayloadCodecFunnelInjector.RUNTIME + ".flow", PayloadCodecFunnelInjector.OWNER + ".codec"), vanilla.subList(0, 4));
		assertTrue(vanilla.contains("java/util/List.stream"), "vanilla's own body is kept for callers outside a build");

		for (MethodNode m : node.methods) {
			if (m.name.equals("codec")) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
		assertSame(out, new PayloadCodecFunnelInjector().transform(PayloadCodecFunnelInjector.TARGET, out, null), "a second pass adds nothing");
		System.setProperty(PayloadCodecFunnelInjector.PROPERTY, "off");
		assertSame(original, new PayloadCodecFunnelInjector().transform(PayloadCodecFunnelInjector.TARGET, original, null));
	}

	private static MethodNode method(ClassNode node, String desc) {
		return node.methods.stream().filter(m -> m.name.equals("codec") && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESPECIAL) out.add(call.owner + "." + call.name);
		}
		return out;
	}
}
