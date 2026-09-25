package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The merged PotionBrewing.Builder.add wraps a MinecraftForge recipe on its way into NeoForge's list. */
@ResourceLock("system-properties")
class ForgeBrewingRecipesInjectorTest {
	private static final Path MERGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"))
			.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String BUILDER = "net/minecraft/world/item/alchemy/PotionBrewing$Builder";

	@AfterEach void reset() { System.clearProperty(ForgeBrewingRecipesInjector.PROPERTY); }

	@Test void theForgeRecipeIsWrappedBeforeItIsAppended() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, BUILDER);
		byte[] out = new ForgeBrewingRecipesInjector().transform(ForgeBrewingRecipesInjector.BUILDER, original, null);
		assertNotSame(original, out);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		MethodNode add = node.methods.stream().filter(m -> m.name.equals("add") && m.desc.contains("minecraftforge")).findFirst().orElseThrow();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : add.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.name);
		assertEquals(List.of("neoForge", "add"), calls);
		new Analyzer<>(new BasicVerifier()).analyze(BUILDER, add);
		assertSame(out, new ForgeBrewingRecipesInjector().transform(ForgeBrewingRecipesInjector.BUILDER, out, null));
		System.setProperty(ForgeBrewingRecipesInjector.PROPERTY, "off");
		assertSame(original, new ForgeBrewingRecipesInjector().transform(ForgeBrewingRecipesInjector.BUILDER, original, null));
	}
}
