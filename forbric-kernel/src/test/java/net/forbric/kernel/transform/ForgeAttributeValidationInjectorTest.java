/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** MinecraftForge's attribute validate callback calls the kernel's gate instead of DefaultAttributes.validate. */
@ResourceLock("system-properties")
class ForgeAttributeValidationInjectorTest {
	private static final Path FORGE = TestFixtures.stagedRoot()
			.resolve(TestFixtures.FORGE_CARRIER_JAR);

	@AfterEach void reset() { System.clearProperty(ForgeAttributeValidationInjector.PROPERTY); }

	@Test void onValidateGoesThroughTheKernel() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "forge runtime not staged: " + FORGE, FORGE);
		String target = ForgeAttributeValidationInjector.TARGET;
		byte[] original = NativeCoremodParityTest.read(FORGE, target.replace('.', '/'));
		byte[] out = new ForgeAttributeValidationInjector().transform(target, original, null);
		assertNotSame(original, out);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		int kernel = 0, vanilla = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.name.equals("validate")) continue;
				if (call.owner.equals(ForgeAttributeValidationInjector.RUNTIME)) kernel++;
				if (call.owner.equals(ForgeAttributeValidationInjector.DEFAULT_ATTRIBUTES)) vanilla++;
			}
		}
		assertEquals(1, kernel);
		assertEquals(0, vanilla);
		assertSame(out, new ForgeAttributeValidationInjector().transform(target, out, null), "a second pass adds nothing");
		System.setProperty(ForgeAttributeValidationInjector.PROPERTY, "off");
		assertSame(original, new ForgeAttributeValidationInjector().transform(target, original, null));
	}
}
