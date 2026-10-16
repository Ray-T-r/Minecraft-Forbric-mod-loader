package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * forge:condition is asked at ConditionalOps' one codec factory, the funnel the merged registry and data-file loaders
 * decode through: MinecraftForge's wrap goes in once, before Fabric's, so Fabric's stays the last thing before the exit
 * (which is what SupersededMixins reads as its repair being in the defined class).
 */
class MergedBaseForgeConditionsTest {
	private static final String BINARY = "net.neoforged.neoforge.common.conditions.ConditionalOps";
	private static final String ENTRY = "net/neoforged/neoforge/common/conditions/ConditionalOps.class";
	private static final String FACTORY = "createConditionalCodecWithConditions";
	private static final String FACTORY_DESC = "(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";

	private static byte[] carrier() throws Exception {
		return TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"), ENTRY);
	}

	/** The kernel calls right before the factory's only exit, in order. */
	private static List<String> wraps(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode factory = node.methods.stream().filter(m -> m.name.equals(FACTORY) && m.desc.equals(FACTORY_DESC)).findFirst().orElseThrow();
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode insn : factory.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		assertEquals(1, code.stream().filter(i -> i.getOpcode() == Opcodes.ARETURN).count());
		List<String> calls = new ArrayList<>();
		for (int i = code.indexOf(code.stream().filter(x -> x.getOpcode() == Opcodes.ARETURN).findFirst().orElseThrow()) - 1;
				i >= 0 && code.get(i) instanceof MethodInsnNode call && call.owner.startsWith("net/forbric/kernel/runtime/"); i--)
			calls.add(0, call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
		return calls;
	}

	@Test void minecraftForgeIsAskedBeforeFabricAtTheOnlyExit() throws Exception {
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(BINARY, carrier(), null);
		assertEquals(List.of("KernelForgeConditions.alsoAskForge", "KernelFabricConditions.alsoAskFabric"), wraps(repaired));
		assertSame(repaired, new ForbricMergedBaseCompatTransformer().transform(BINARY, repaired, null), "a second pass wraps nothing twice");
	}

	@Test void aFactoryFabricAlreadyWrapsGetsMinecraftForgesWrapInFrontOfIt() throws Exception {
		// The same class with only Fabric's wrap in it -- what an earlier kernel left -- still ends Forge then Fabric.
		ClassNode node = new ClassNode();
		new ClassReader(carrier()).accept(node, 0);
		MethodNode factory = node.methods.stream().filter(m -> m.name.equals(FACTORY) && m.desc.equals(FACTORY_DESC)).findFirst().orElseThrow();
		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn : factory.instructions) if (insn.getOpcode() == Opcodes.ARETURN) exit = insn;
		factory.instructions.insertBefore(exit, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFabricConditions",
				"alsoAskFabric", "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(BINARY, writer.toByteArray(), null);
		assertEquals(List.of("KernelForgeConditions.alsoAskForge", "KernelFabricConditions.alsoAskFabric"), wraps(repaired));
	}
}
