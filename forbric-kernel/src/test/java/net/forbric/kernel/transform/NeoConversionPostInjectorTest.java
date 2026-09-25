package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** The merged Zombie's MinecraftForge conversion lambdas tell NeoForge too. */
@ResourceLock("system-properties")
class NeoConversionPostInjectorTest {
	private static final Path MERGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"))
			.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String ZOMBIE = "net/minecraft/world/entity/monster/zombie/Zombie";

	@AfterEach void reset() { System.clearProperty(NeoConversionPostInjector.PROPERTY); }

	@Test void everyForgeConversionPostGoesThroughTheKernel() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, ZOMBIE);
		assertEquals(2, count(original, NeoConversionPostInjector.FORGE), "premise: drowning/husk and villager lambdas are MinecraftForge's");
		byte[] out = new NeoConversionPostInjector().transform(NeoConversionPostInjector.ZOMBIE, original, null);
		assertEquals(0, count(out, NeoConversionPostInjector.FORGE));
		assertEquals(2, count(out, "net/forbric/kernel/runtime/KernelConversions"));
		assertSame(out, new NeoConversionPostInjector().transform(NeoConversionPostInjector.ZOMBIE, out, null));
		System.setProperty(NeoConversionPostInjector.PROPERTY, "off");
		assertSame(original, new NeoConversionPostInjector().transform(NeoConversionPostInjector.ZOMBIE, original, null));
	}

	private static int count(byte[] bytes, String owner) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int n = 0;
		for (MethodNode m : node.methods) for (AbstractInsnNode i : m.instructions)
			if (i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals("onLivingConvert")) n++;
		return n;
	}
}
