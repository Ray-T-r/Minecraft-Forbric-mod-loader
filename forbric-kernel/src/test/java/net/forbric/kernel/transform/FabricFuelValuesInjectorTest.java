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

/** NeoForge's DataMapHooks.populateFuelValues runs Fabric's fuel events on its builder just before building it. */
@ResourceLock("system-properties")
class FabricFuelValuesInjectorTest {
	private static final Path NEO_RT = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"))
			.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String HOOKS = "net/neoforged/neoforge/common/DataMapHooks";

	@AfterEach void reset() { System.clearProperty(FabricFuelValuesInjector.PROPERTY); }

	@Test void theBuilderGoesThroughFabricRightBeforeItIsBuilt() throws Exception {
		byte[] original = NativeCoremodParityTest.read(NEO_RT, HOOKS);
		byte[] out = new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, original, null);
		assertNotSame(original, out);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		MethodNode populate = node.methods.stream().filter(m -> m.name.equals("populateFuelValues")).findFirst().orElseThrow();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : populate.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.name);
		assertEquals(List.of("apply", "build"), calls.subList(calls.size() - 2, calls.size()));
		new Analyzer<>(new BasicVerifier()).analyze(HOOKS, populate);
		assertSame(out, new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, out, null));
		System.setProperty(FabricFuelValuesInjector.PROPERTY, "off");
		assertSame(original, new FabricFuelValuesInjector().transform(FabricFuelValuesInjector.HOOKS, original, null));
	}
}
