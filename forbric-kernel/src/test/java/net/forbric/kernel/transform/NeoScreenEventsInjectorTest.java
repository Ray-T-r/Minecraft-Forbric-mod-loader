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

/** NeoForge's screen Opening/Closing beside MinecraftForge's, in the real merged Gui.setScreen. */
@ResourceLock("system-properties")
class NeoScreenEventsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final String GUI = "net/minecraft/client/gui/Gui";

	@AfterEach void reset() { System.clearProperty(NeoScreenEventsInjector.PROPERTY); }

	@Test void neoForgeIsAskedRightAfterMinecraftForgeOnBothChanges() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, GUI);
		byte[] out = new NeoScreenEventsInjector().transform(NeoScreenEventsInjector.GUI, original, null);
		assertNotSame(original, out);
		MethodNode set = setScreen(out);
		List<String> order = new ArrayList<>();
		for (AbstractInsnNode insn : set.instructions) {
			if (insn instanceof MethodInsnNode call && (call.owner.equals(NeoScreenEventsInjector.RUNTIME)
					|| call.owner.equals(NeoScreenEventsInjector.FORGE) || call.name.equals("removed"))) order.add(call.name);
		}
		assertEquals(List.of("onScreenOpening", "neoForgeOpening", "takeNeoForgeNewScreen", "onScreenClose",
				"postNeoForgeClosing", "removed"), order);
		new Analyzer<>(new BasicVerifier()).analyze(GUI, set);
		assertSame(out, new NeoScreenEventsInjector().transform(NeoScreenEventsInjector.GUI, out, null), "a second pass changes nothing");
	}

	@Test void neoForgesOwnBodyAndTheSwitchAreLeftAlone() throws Exception {
		byte[] neo = NativeCoremodParityTest.read(NEO, GUI);
		assertSame(neo, new NeoScreenEventsInjector().transform(NeoScreenEventsInjector.GUI, neo, null), "NeoForge's body posts its own");
		System.setProperty(NeoScreenEventsInjector.PROPERTY, "off");
		byte[] merged = NativeCoremodParityTest.read(MERGED, GUI);
		assertSame(merged, new NeoScreenEventsInjector().transform(NeoScreenEventsInjector.GUI, merged, null));
	}

	private static MethodNode setScreen(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("setScreen") && m.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)V")).findFirst().orElseThrow();
	}
}
