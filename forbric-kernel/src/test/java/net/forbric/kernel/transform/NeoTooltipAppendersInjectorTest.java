package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import net.forbric.kernel.boot.KernelLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's tooltip registration event, on the real ItemTooltipHandler. */
@ResourceLock("system-properties")
class NeoTooltipAppendersInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path NEO_RT = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String HANDLER = "net/neoforged/neoforge/common/tooltip/ItemTooltipHandler";

	@AfterEach void reset() { System.clearProperty(KernelLifecycle.NEO_TOOLTIP_APPENDERS); }

	@Test void initPostsItsOwnEventThroughTheKernelsDelivery() throws Exception {
		byte[] original = NativeCoremodParityTest.read(NEO_RT, HANDLER);
		assertEquals(1, calls(node(original), "net/neoforged/fml/ModLoader", "postEvent").size(),
				"premise: init hands its one event to ModLoader.postEvent");
		byte[] out = new NeoTooltipAppendersInjector().transform(NeoTooltipAppendersInjector.HANDLER, original, null);
		ClassNode handler = node(out);
		assertTrue(calls(handler, "net/neoforged/fml/ModLoader", "postEvent").isEmpty());
		List<MethodInsnNode> swapped = calls(handler, NeoTooltipAppendersInjector.RUNTIME, "postRegisterAppenders");
		assertEquals(1, swapped.size());
		assertEquals("(Lnet/neoforged/neoforge/event/RegisterTooltipAppendersEvent;)V", swapped.get(0).desc);
		assertEquals(Opcodes.INVOKESTATIC, swapped.get(0).getOpcode());
		MethodNode init = handler.methods.stream().filter(m -> m.name.equals("init")).findFirst().orElseThrow();
		new Analyzer<>(new BasicVerifier()).analyze(HANDLER, init);
		assertSame(out, new NeoTooltipAppendersInjector().transform(NeoTooltipAppendersInjector.HANDLER, out, null),
				"a second pass changes nothing");
	}

	@Test void theSwitchLeavesTheHandlerAlone() throws Exception {
		System.setProperty(KernelLifecycle.NEO_TOOLTIP_APPENDERS, "off");
		byte[] original = NativeCoremodParityTest.read(NEO_RT, HANDLER);
		assertSame(original, new NeoTooltipAppendersInjector().transform(NeoTooltipAppendersInjector.HANDLER, original, null));
	}

	@Test void aPostThatIsNotTheEventInitBuiltIsNotGuessed() throws Exception {
		ClassNode drifted = node(NativeCoremodParityTest.read(NEO_RT, HANDLER));
		MethodNode init = drifted.methods.stream().filter(m -> m.name.equals("init")).findFirst().orElseThrow();
		MethodInsnNode post = calls(drifted, "net/neoforged/fml/ModLoader", "postEvent").get(0);
		init.instructions.insertBefore(post, new InsnNode(Opcodes.NOP));
		init.instructions.insertBefore(post, new InsnNode(Opcodes.DUP));
		init.instructions.insertBefore(post, new InsnNode(Opcodes.POP));
		assertFalse(NeoTooltipAppendersInjector.repair(drifted), "the post must come straight after the event's constructor");
		ClassNode twice = node(NativeCoremodParityTest.read(NEO_RT, HANDLER));
		MethodNode init2 = twice.methods.stream().filter(m -> m.name.equals("init")).findFirst().orElseThrow();
		MethodInsnNode post2 = calls(twice, "net/neoforged/fml/ModLoader", "postEvent").get(0);
		init2.instructions.insert(post2, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/neoforged/fml/ModLoader", "postEvent",
				"(Lnet/neoforged/bus/api/Event;)V", false));
		init2.instructions.insert(post2, new InsnNode(Opcodes.ACONST_NULL));
		assertFalse(NeoTooltipAppendersInjector.repair(twice), "two posts: not the method this was written against");
	}

	private static List<MethodInsnNode> calls(ClassNode node, String owner, String name) {
		return node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.filter(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name))
				.map(MethodInsnNode.class::cast).toList();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
