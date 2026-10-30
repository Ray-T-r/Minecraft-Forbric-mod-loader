/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

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

/** Each isModdedPayload in the merged common listeners' handleCustomPayload is followed by the kernel's verdict. */
@ResourceLock("system-properties")
class ForeignPayloadReceiveInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve(TestFixtures.BASE_JAR);

	@AfterEach void reset() { System.clearProperty(ForeignPayloadReceiveInjector.PROPERTY); }

	@Test void bothCommonListenersAskTheKernelAfterNeoForge() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		for (String target : ForeignPayloadReceiveInjector.TARGETS) {
			byte[] original = NativeCoremodParityTest.read(MERGED, target.replace('.', '/'));
			byte[] out = new ForeignPayloadReceiveInjector().transform(target, original, null);
			assertNotSame(original, out, target);
			ClassNode node = new ClassNode();
			new ClassReader(out).accept(node, 0);
			int sites = 0;
			for (MethodNode m : node.methods) {
				if (!m.name.equals("handleCustomPayload")) continue;
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof MethodInsnNode call && call.name.equals("isModdedPayload")) {
						assertEquals(Opcodes.DUP, previous(call).getOpcode(), target);
						AbstractInsnNode next = call.getNext();
						while (next.getOpcode() < 0) next = next.getNext();
						assertTrue(next instanceof MethodInsnNode hook && hook.name.equals(ForeignPayloadReceiveInjector.HOOK), target);
						sites++;
					}
				}
				new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
			}
			assertEquals(1, sites, target);
			assertSame(out, new ForeignPayloadReceiveInjector().transform(target, out, null), "a second pass adds nothing");
			System.setProperty(ForeignPayloadReceiveInjector.PROPERTY, "off");
			assertSame(original, new ForeignPayloadReceiveInjector().transform(target, original, null));
			System.clearProperty(ForeignPayloadReceiveInjector.PROPERTY);
		}
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}
}
