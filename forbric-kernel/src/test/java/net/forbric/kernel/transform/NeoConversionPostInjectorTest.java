package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** The merged Zombie's MinecraftForge conversion lambdas tell NeoForge too. */
@ResourceLock("system-properties")
class NeoConversionPostInjectorTest {
	private static final Path MERGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"))
			.resolve(net.forbric.kernel.TestFixtures.BASE_JAR);
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

	/** A lambda that already posts NeoForge's Post itself next to MinecraftForge's is not told NeoForge a second time. */
	@Test void aLambdaThatAlreadyPostsNeoForgesConversionIsLeftAlone() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(NativeCoremodParityTest.read(MERGED, ZOMBIE)).accept(node, 0);
		MethodNode composed = null;
		for (MethodNode m : node.methods) for (AbstractInsnNode i : m.instructions) {
			if (composed == null && i instanceof MethodInsnNode c && c.owner.equals(NeoConversionPostInjector.FORGE) && c.name.equals("onLivingConvert")) {
				m.instructions.insert(c, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/neoforged/neoforge/event/EventHooks", "onLivingConvert", c.desc, false));
				m.instructions.insert(c, new VarInsnNode(Opcodes.ALOAD, 2));
				m.instructions.insert(c, new VarInsnNode(Opcodes.ALOAD, 0));
				composed = m;
			}
		}
		assertNotNull(composed, "premise: a MinecraftForge conversion lambda");
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] out = new NeoConversionPostInjector().transform(NeoConversionPostInjector.ZOMBIE, writer.toByteArray(), null);
		ClassNode after = new ClassNode();
		new ClassReader(out).accept(after, 0);
		for (MethodNode m : after.methods) {
			if (!m.name.equals(composed.name) || !m.desc.equals(composed.desc)) continue;
			for (AbstractInsnNode i : m.instructions) {
				assertFalse(i instanceof MethodInsnNode c && c.owner.equals("net/forbric/kernel/runtime/KernelConversions"),
						"the composed lambda keeps its own two posts");
			}
		}
		assertEquals(1, count(out, "net/forbric/kernel/runtime/KernelConversions"), "the other lambda is still redirected");
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
