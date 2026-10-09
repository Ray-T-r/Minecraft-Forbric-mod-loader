/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * {@code forbric-common-network-interop#startNextTask} is judged by its end state: the server's
 * {@code startNextTask} starts tasks through MinecraftForge's {@code ConfigurationTaskContext} overload, whose interface
 * default hands every other family's task back to the Consumer overload. The staged merge already keeps
 * MinecraftForge's own body there, so the repair rightly edits nothing, and the ledger used to score that as a missing
 * repair (gate-m9's two anchor checks went red while the handshake ran: "queued 4 MinecraftForge configuration
 * task(s)"). A body still handing any task the Consumer overload, or a null context, stays a miss.
 */
class ConfigurationTaskStartClaimTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	private static final String LISTENER = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
	private static final String LISTENER_NAME = LISTENER.replace('/', '.');
	private static final String TASK = "net/minecraft/server/network/ConfigurationTask";
	private static final String CONTEXT = "net/minecraftforge/network/config/ConfigurationTaskContext";
	private static final String PACKET = "net/minecraft/network/protocol/Packet";
	private static final String CLAIM = CommonNetworkInteropInjector.CLAIM_START_NEXT_TASK;
	private static final TransformContext SERVER = new TransformContext(EnvType.SERVER, false, "intermediary");

	@TempDir Path root;

	@Test void theStagedMinecraftForgeBodyLandsTheClaimAndIsLeftAsMerged() throws Exception {
		byte[] merged = NativeCoremodParityTest.read(MERGED, LISTENER);
		Set<String> hits = new HashSet<>();
		byte[] out = new CommonNetworkInteropInjector().transform(LISTENER_NAME, merged, null, hits::add);
		assertTrue(hits.contains(CLAIM), "the merged startNextTask already starts tasks through the context: " + hits);
		assertEquals(text(merged, "startNextTask"), text(out, "startNextTask"), "and nothing in it was edited");
	}

	@Test void theVanillaOverloadIsSwappedAndThenTheClaimHolds() {
		byte[] vanilla = listener(Shape.LAMBDA_CONSUMER);
		Set<String> hits = new HashSet<>();
		byte[] out = new CommonNetworkInteropInjector().transform(LISTENER_NAME, vanilla, null, hits::add);
		assertNotSame(vanilla, out);
		assertEquals(Set.of(CLAIM), hits);
		assertTrue(CommonNetworkInteropInjector.startsTasksThroughForgesContext(method(out, "startNextTask")));
	}

	/** The context reaches the call through a local rather than straight off the field: the same end state. */
	@Test void aContextKeptInALocalIsTheEndStateToo() {
		byte[] local = listener(Shape.CONTEXT_IN_A_LOCAL);
		Set<String> hits = new HashSet<>();
		assertSame(local, new CommonNetworkInteropInjector().transform(LISTENER_NAME, local, null, hits::add));
		assertEquals(Set.of(CLAIM), hits);
	}

	/**
	 * A look-alike the repair cannot swap: the Consumer comes from a field, not a lambda over this listener. Every
	 * task is still handed the overload MinecraftForge's own tasks refuse, so it is a miss, and the gate line says so.
	 */
	@Test void aConsumerTheRepairCannotSwapIsAMissTheGateSees() throws Exception {
		byte[] field = listener(Shape.FIELD_CONSUMER);
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, new CommonNetworkInteropInjector());
		String[] out = new String[1];
		assertSame(field, GateLogContract.capture(() -> chain.applyBeforeMixin(LISTENER_NAME, field, SERVER), out));
		List<String> missed = chain.ledger().report().misses().stream().map(AnchorLedger.Miss::transformer).toList();
		assertTrue(missed.contains(CLAIM), "missed: " + missed);
		// The stand-in has none of the listener's other repaired methods, so their claims miss too; this one's line is the point.
		String ours = String.join("\n", out[0].lines().filter(line -> line.contains(CLAIM + " was handed")).toList());
		assertEquals(1, GateLogContract.count(root, GateLogContract.pattern(GATE, "no repair was handed its target and declined"), ours), out[0]);
	}

	@Test void aNullContextIsNotTheEndState() {
		Set<String> hits = new HashSet<>();
		new CommonNetworkInteropInjector().transform(LISTENER_NAME, listener(Shape.NULL_CONTEXT), null, hits::add);
		assertFalse(hits.contains(CLAIM), "" + hits);
	}

	// ---------------------------------------------------------------------------------------------------------------

	private enum Shape { LAMBDA_CONSUMER, CONTEXT_IN_A_LOCAL, FIELD_CONSUMER, NULL_CONTEXT }

	/**
	 * The listener with its {@code taskContext}, a queue, a Consumer field and {@code startNextTask()} written for a
	 * shape: {@code ConfigurationTask task = (ConfigurationTask) queue.poll(); task.start(...);}. Only read back.
	 */
	private static byte[] listener(Shape shape) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LISTENER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "taskContext", "L" + CONTEXT + ";", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "configurationTasks", "Ljava/util/Queue;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sender", "Ljava/util/function/Consumer;", null, null).visitEnd();
		MethodVisitor send = cw.visitMethod(Opcodes.ACC_PUBLIC, "send", "(L" + PACKET + ";)V", null, null);
		send.visitCode();
		send.visitInsn(Opcodes.RETURN);
		send.visitMaxs(0, 0);
		send.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "startNextTask", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, LISTENER, "configurationTasks", "Ljava/util/Queue;");
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue", "poll", "()Ljava/lang/Object;", true);
		mv.visitTypeInsn(Opcodes.CHECKCAST, TASK);
		mv.visitVarInsn(Opcodes.ASTORE, 1);
		switch (shape) {
			case LAMBDA_CONSUMER -> {
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
								+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
				mv.visitInvokeDynamicInsn("accept", "(L" + LISTENER + ";)Ljava/util/function/Consumer;", metafactory,
						Type.getType("(Ljava/lang/Object;)V"),
						new Handle(Opcodes.H_INVOKEVIRTUAL, LISTENER, "send", "(L" + PACKET + ";)V", false),
						Type.getType("(L" + PACKET + ";)V"));
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TASK, "start", "(Ljava/util/function/Consumer;)V", true);
			}
			case CONTEXT_IN_A_LOCAL -> {
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitFieldInsn(Opcodes.GETFIELD, LISTENER, "taskContext", "L" + CONTEXT + ";");
				mv.visitVarInsn(Opcodes.ASTORE, 2);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitVarInsn(Opcodes.ALOAD, 2);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TASK, "start", "(L" + CONTEXT + ";)V", true);
			}
			case FIELD_CONSUMER -> {
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitFieldInsn(Opcodes.GETFIELD, LISTENER, "sender", "Ljava/util/function/Consumer;");
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TASK, "start", "(Ljava/util/function/Consumer;)V", true);
			}
			case NULL_CONTEXT -> {
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitInsn(Opcodes.ACONST_NULL);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TASK, "start", "(L" + CONTEXT + ";)V", true);
			}
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static MethodNode method(byte[] bytes, String name) {
		ClassNode node = new ClassNode();
		// Frames are left out: the transformer writes them back in its own compression, which says nothing about the code.
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	/** The method's code as text, so two versions compare by what they do rather than by constant-pool indices. */
	private static String text(byte[] bytes, String name) {
		Textifier printer = new Textifier();
		method(bytes, name).accept(new TraceMethodVisitor(printer));
		StringWriter out = new StringWriter();
		printer.print(new PrintWriter(out));
		return out.toString();
	}
}
