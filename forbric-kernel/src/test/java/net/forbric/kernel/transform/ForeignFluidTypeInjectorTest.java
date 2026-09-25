package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

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

/** The merged Fluid.getFluidType(), asking the kernel for a foreign fluid before NeoForge's throwing lookup. */
@ResourceLock("system-properties")
class ForeignFluidTypeInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String FLUID = "net/minecraft/world/level/material/Fluid";

	@AfterEach void reset() { System.clearProperty(ForeignFluidTypeInjector.PROPERTY); }

	@Test void theKernelIsAskedAfterTheCacheAndBeforeNeoForgesLookup() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, FLUID);
		byte[] out = new ForeignFluidTypeInjector().transform(ForeignFluidTypeInjector.FLUID, original, null);
		MethodNode type = method(out);
		List<AbstractInsnNode> real = Arrays.stream(type.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertEquals(Opcodes.GETFIELD, real.get(1).getOpcode(), "the cached field is read first");
		assertEquals(Opcodes.IFNONNULL, real.get(2).getOpcode(), "a cached type is returned as before");
		assertTrue(real.get(4) instanceof MethodInsnNode ask && ask.owner.equals(ForeignFluidTypeInjector.RUNTIME) && ask.name.equals("foreignType"));
		assertEquals(List.of(Opcodes.DUP, Opcodes.IFNULL, Opcodes.ARETURN, Opcodes.POP),
				real.subList(5, 9).stream().map(AbstractInsnNode::getOpcode).toList(), "a kernel answer is returned uncached; null falls through");
		assertTrue(real.stream().anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("getVanillaFluidType")),
				"NeoForge's lookup still answers every fluid it knows, and still caches it");
		new Analyzer<>(new BasicVerifier()).analyze(FLUID, type);
		assertSame(out, new ForeignFluidTypeInjector().transform(ForeignFluidTypeInjector.FLUID, out, null), "a second pass changes nothing");
	}

	@Test void theSwitchLeavesTheFluidAlone() throws Exception {
		System.setProperty(ForeignFluidTypeInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(MERGED, FLUID);
		assertSame(original, new ForeignFluidTypeInjector().transform(ForeignFluidTypeInjector.FLUID, original, null));
	}

	private static MethodNode method(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("getFluidType") && m.desc.equals("()L" + ForeignFluidTypeInjector.TYPE + ";"))
				.findFirst().orElseThrow();
	}
}
