package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** MinecraftForge's registerParticleGroup on NeoForge's merged ParticleEngine, on the real class. */
@ResourceLock("system-properties")
class ParticleGroupsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final String ENGINE = "net/minecraft/client/particle/ParticleEngine";

	@AfterEach void reset() { System.clearProperty(ParticleGroupsInjector.PROPERTY); }

	@Test void registrationLandsWhereTheEngineReadsIt() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, ENGINE);
		ClassNode before = node(original);
		assertTrue(Arrays.stream(method(before, "registerParticleGroup").instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f
				&& f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("particleRenderOrder")), "premise: a static read of NeoForge's instance field");
		assertFalse(Arrays.stream(method(before, "<clinit>").instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("factories")),
				"premise: nothing initialises MinecraftForge's static map");

		byte[] out = new ParticleGroupsInjector().transform(ParticleGroupsInjector.ENGINE, original, null);
		ClassNode engine = node(out);
		assertTrue(Arrays.stream(method(engine, "<clinit>").instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f
				&& f.getOpcode() == Opcodes.PUTSTATIC && f.name.equals("factories")));
		MethodNode register = method(engine, "registerParticleGroup");
		assertTrue(Arrays.stream(register.instructions.toArray()).noneMatch(i -> i instanceof FieldInsnNode f && f.name.equals("particleRenderOrder")));
		assertTrue(Arrays.stream(register.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals(ParticleGroupsInjector.ORDER)));
		MethodNode ctor = method(engine, "<init>");
		MethodInsnNode merge = Arrays.stream(ctor.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.owner.equals(ParticleGroupsInjector.RUNTIME))
				.map(MethodInsnNode.class::cast).findFirst().orElseThrow();
		AbstractInsnNode previous = merge.getPrevious();
		while (!(previous instanceof MethodInsnNode)) previous = previous.getPrevious();
		assertEquals("postEvent", ((MethodInsnNode) previous).name, "right after NeoForge's group event");
		for (MethodNode m : engine.methods) if (m.name.equals("<clinit>") || m.name.equals("<init>") || m.name.equals("registerParticleGroup"))
			new Analyzer<>(new BasicVerifier()).analyze(ENGINE, m);
		assertSame(out, new ParticleGroupsInjector().transform(ParticleGroupsInjector.ENGINE, out, null), "a second pass changes nothing");
	}

	@Test void minecraftForgesOwnEngineAndTheSwitchAreLeftAlone() throws Exception {
		byte[] forge = NativeCoremodParityTest.read(FORGE, ENGINE);
		assertSame(forge, new ParticleGroupsInjector().transform(ParticleGroupsInjector.ENGINE, forge, null), "MinecraftForge initialises its own map");
		System.setProperty(ParticleGroupsInjector.PROPERTY, "off");
		byte[] merged = NativeCoremodParityTest.read(MERGED, ENGINE);
		assertSame(merged, new ParticleGroupsInjector().transform(ParticleGroupsInjector.ENGINE, merged, null));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}
}
