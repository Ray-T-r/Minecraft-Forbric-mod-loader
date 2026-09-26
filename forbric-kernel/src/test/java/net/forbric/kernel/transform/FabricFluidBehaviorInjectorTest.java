package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The merged EntityFluidInteraction.getFluidTypeByTag asks the kernel for a Fabric behaviour tag before NeoForge throws. */
@ResourceLock("system-properties")
class FabricFluidBehaviorInjectorTest {
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"),
			"run/merged-base/patched-mc-merged-26.2.jar");
	private static final String INTERACTION = "net/minecraft/world/entity/EntityFluidInteraction";

	@AfterEach void reset() { System.clearProperty(FabricFluidBehaviorInjector.PROPERTY); }

	@Test void anUnknownTagIsAskedAboutBeforeTheThrow() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, INTERACTION);
		byte[] out = new FabricFluidBehaviorInjector().transform(FabricFluidBehaviorInjector.INTERACTION, original, null);
		assertNotSame(original, out);
		MethodNode byTag = method(out);
		List<AbstractInsnNode> real = Arrays.stream(byTag.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		int ask = -1;
		for (int i = 0; i < real.size(); i++) {
			if (real.get(i) instanceof MethodInsnNode call && call.owner.equals(FabricFluidBehaviorInjector.RUNTIME) && call.name.equals("byTag")) ask = i;
		}
		assertTrue(ask > 0, "the kernel is asked");
		assertEquals(List.of(Opcodes.ALOAD, Opcodes.INVOKESTATIC, Opcodes.DUP, Opcodes.IFNULL, Opcodes.ARETURN, Opcodes.POP, Opcodes.NEW),
				real.subList(ask - 1, ask + 6).stream().map(AbstractInsnNode::getOpcode).toList(), "a kernel answer is returned; null still throws");
		assertEquals(2, real.stream().filter(i -> i instanceof FieldInsnNode f && (f.name.equals("WATER") || f.name.equals("LAVA"))
				&& f.owner.equals("net/minecraft/tags/FluidTags")).count(), "water and lava are NeoForge's answers, as before");
		new Analyzer<>(new BasicVerifier()).analyze(INTERACTION, byTag);
		assertSame(out, new FabricFluidBehaviorInjector().transform(FabricFluidBehaviorInjector.INTERACTION, out, null), "idempotent");
	}

	/** The JVM's own verifier, frames included: the class is defined and initialised over the staged game. */
	@Test void theRepairedClassPassesTheJvmVerifier() throws Exception {
		byte[] out = new FabricFluidBehaviorInjector().transform(FabricFluidBehaviorInjector.INTERACTION,
				NativeCoremodParityTest.read(MERGED, INTERACTION), null);
		try (URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of())) {
			ClassLoader repaired = new ClassLoader(game) {
				@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					if (!name.equals(FabricFluidBehaviorInjector.INTERACTION)) return super.loadClass(name, resolve);
					synchronized (getClassLoadingLock(name)) {
						Class<?> done = findLoadedClass(name);
						return done != null ? done : defineClass(name, out, 0, out.length);
					}
				}
			};
			Class<?> defined = Class.forName(FabricFluidBehaviorInjector.INTERACTION, true, repaired);
			assertSame(repaired, defined.getClassLoader());
		}
	}

	@Test void aShapeItDoesNotKnowIsLeftAsMerged() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(NativeCoremodParityTest.read(MERGED, INTERACTION)).accept(node, 0);
		MethodNode byTag = node.methods.stream().filter(m -> m.name.equals(FabricFluidBehaviorInjector.NAME)).findFirst().orElseThrow();
		byTag.instructions.insert(new InsnNode(Opcodes.ATHROW));
		byTag.instructions.insert(new InsnNode(Opcodes.ACONST_NULL));
		assertEquals(-1, FabricFluidBehaviorInjector.repair(node), "two throws: which one gives up on the tag is a guess");
	}

	@Test void offLeavesItAsMerged() throws Exception {
		System.setProperty(FabricFluidBehaviorInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(MERGED, INTERACTION);
		assertSame(original, new FabricFluidBehaviorInjector().transform(FabricFluidBehaviorInjector.INTERACTION, original, null));
	}

	private static MethodNode method(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(FabricFluidBehaviorInjector.NAME) && m.desc.equals(FabricFluidBehaviorInjector.DESC))
				.findFirst().orElseThrow();
	}
}
